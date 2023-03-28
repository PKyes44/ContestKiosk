/*
 * Copyright 2020 Google LLC. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.yes.visionvoicedemo.cameras;

import static java.lang.Math.max;
import static java.lang.Math.min;

import android.annotation.SuppressLint;
import android.app.ActivityManager;
import android.app.ActivityManager.MemoryInfo;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.ImageFormat;
import android.media.Image;
import android.os.Build.VERSION_CODES;
import android.os.SystemClock;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.GuardedBy;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.camera.core.ExperimentalGetImage;
import androidx.camera.core.ImageProxy;

import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.TaskExecutors;
import com.google.android.gms.tasks.Tasks;
import com.google.android.odml.image.BitmapMlImageBuilder;
import com.google.android.odml.image.ByteBufferMlImageBuilder;
import com.google.android.odml.image.MediaMlImageBuilder;
import com.google.android.odml.image.MlImage;
import com.google.mlkit.common.MlKitException;
import com.google.mlkit.vision.common.InputImage;
import com.yes.visionvoicedemo.cameras.preference.PreferenceUtils;

import org.opencv.android.Utils;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.MatOfPoint;
import org.opencv.core.Scalar;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Timer;
import java.util.TimerTask;

/**
 * Abstract base class for vision frame processors. Subclasses need to implement {@link
 * #onSuccess(Object, GraphicOverlay)} to define what they want to with the detection results and
 * {@link #detectInImage(InputImage)} to specify the detector object.
 *
 * @param <T> The type of the detected feature.
 */
public abstract class VisionProcessorBase<T> implements VisionImageProcessor {

  protected static final String MANUAL_TESTING_LOG = "LogTagForTest";
  private static final String TAG = "VisionProcessorBase";

  private final ActivityManager activityManager;
  private final Timer fpsTimer = new Timer();
  private final ScopedExecutor executor;
  private final TemperatureMonitor temperatureMonitor;

  // Whether this processor is already shut down
  private boolean isShutdown;

  // Used to calculate latency, running in the same thread, no sync needed.
  private int numRuns = 0;
  private long totalFrameMs = 0;
  private long maxFrameMs = 0;
  private long minFrameMs = Long.MAX_VALUE;
  private long totalDetectorMs = 0;
  private long maxDetectorMs = 0;
  private long minDetectorMs = Long.MAX_VALUE;

  // Frame count that have been processed so far in an one second interval to calculate FPS.
  private int frameProcessedInOneSecondInterval = 0;
  private int framesPerSecond = 0;

  // To keep the latest images and its metadata.
  @GuardedBy("this")
  private ByteBuffer latestImage;

  @GuardedBy("this")
  private FrameMetadata latestImageMetaData;
  // To keep the images and metadata in process.
  @GuardedBy("this")
  private ByteBuffer processingImage;

  @GuardedBy("this")
  private FrameMetadata processingMetaData;

  protected VisionProcessorBase(Context context) {
    activityManager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
    executor = new ScopedExecutor(TaskExecutors.MAIN_THREAD);
    fpsTimer.scheduleAtFixedRate(
        new TimerTask() {
          @Override
          public void run() {
            framesPerSecond = frameProcessedInOneSecondInterval;
            frameProcessedInOneSecondInterval = 0;
          }
        },
        /* delay= */ 0,
        /* period= */ 1000);
    temperatureMonitor = new TemperatureMonitor(context);
  }

  // -----------------Code for processing single still image----------------------------------------
  @Override
  public void processBitmap(Bitmap bitmap, final GraphicOverlay graphicOverlay) {
    long frameStartMs = SystemClock.elapsedRealtime();

    if (isMlImageEnabled(graphicOverlay.getContext())) {
      MlImage mlImage = new BitmapMlImageBuilder(bitmap).build();
      requestDetectInImage(
          mlImage,
          graphicOverlay,
          /* originalCameraImage= */ null,
          /* shouldShowFps= */ false,
          frameStartMs);
      mlImage.close();

      return;
    }

    requestDetectInImage(
        InputImage.fromBitmap(bitmap, 0),
        graphicOverlay,
        /* originalCameraImage= */ null,
        /* shouldShowFps= */ false,
        frameStartMs);
  }

  // -----------------Code for processing live preview frame from Camera1 API-----------------------
  @Override
  public synchronized void processByteBuffer(
      ByteBuffer data, final FrameMetadata frameMetadata, final GraphicOverlay graphicOverlay) {
    latestImage = data;
    latestImageMetaData = frameMetadata;
    if (processingImage == null && processingMetaData == null) {
      processLatestImage(graphicOverlay);
    }
  }

  private synchronized void processLatestImage(final GraphicOverlay graphicOverlay) {
    processingImage = latestImage;
    processingMetaData = latestImageMetaData;
    latestImage = null;
    latestImageMetaData = null;
    if (processingImage != null && processingMetaData != null && !isShutdown) {
      processImage(processingImage, processingMetaData, graphicOverlay);
    }
  }

  private void processImage(
      ByteBuffer data, final FrameMetadata frameMetadata, final GraphicOverlay graphicOverlay) {
    long frameStartMs = SystemClock.elapsedRealtime();

    // If live viewport is on (that is the underneath surface view takes care of the camera preview
    // drawing), skip the unnecessary bitmap creation that used for the manual preview drawing.
    Bitmap bitmap =
        PreferenceUtils.isCameraLiveViewportEnabled(graphicOverlay.getContext())
            ? null
            : BitmapUtils.getBitmap(data, frameMetadata);

    if (isMlImageEnabled(graphicOverlay.getContext())) {
      MlImage mlImage =
          new ByteBufferMlImageBuilder(
                  data,
                  frameMetadata.getWidth(),
                  frameMetadata.getHeight(),
                  MlImage.IMAGE_FORMAT_NV21)
              .setRotation(frameMetadata.getRotation())
              .build();

      requestDetectInImage(mlImage, graphicOverlay, bitmap, /* shouldShowFps= */ true, frameStartMs)
          .addOnSuccessListener(executor, results -> processLatestImage(graphicOverlay));

      // This is optional. Java Garbage collection can also close it eventually.
      mlImage.close();
      return;
    }

    requestDetectInImage(
            InputImage.fromByteBuffer(
                data,
                frameMetadata.getWidth(),
                frameMetadata.getHeight(),
                frameMetadata.getRotation(),
                InputImage.IMAGE_FORMAT_NV21),
            graphicOverlay,
            bitmap,
            /* shouldShowFps= */ true,
            frameStartMs)
        .addOnSuccessListener(executor, results -> processLatestImage(graphicOverlay));
  }

  // -----------------Code for processing live preview frame from CameraX API-----------------------
  @Override
  @RequiresApi(VERSION_CODES.LOLLIPOP)
  @ExperimentalGetImage
  public void processImageProxy(ImageProxy image, GraphicOverlay graphicOverlay) {
    long frameStartMs = SystemClock.elapsedRealtime();
    if (isShutdown) {
      image.close();
      return;
    }

    Bitmap bitmap = null;
    if (!PreferenceUtils.isCameraLiveViewportEnabled(graphicOverlay.getContext())) {
      bitmap = BitmapUtils.getBitmap(image);
    }
    ImageProxy image2 = image;

// Convert ImageProxy to Mat
    Mat rgba = new Mat(image2.getHeight(), image2.getWidth(), CvType.CV_8UC4);
    ByteBuffer buffer = image2.getPlanes()[0].getBuffer();
    byte[] bytes = new byte[buffer.remaining()];
    buffer.get(bytes);
    rgba.put(0, 0, bytes);

// Convert to grayscale
    Mat gray = new Mat();
    Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY);

// Apply thresholding to make image binary
    Mat binary = new Mat();
    Imgproc.threshold(gray, binary, 0, 255, Imgproc.THRESH_BINARY | Imgproc.THRESH_OTSU);

// Apply edge detection to get the edges of the object in the image
    Mat edges = new Mat();
    Imgproc.Canny(binary, edges, 100, 200);

// Remove small objects from the image
    Mat kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, new Size(5, 5));
    Mat closed = new Mat();
    Imgproc.morphologyEx(edges, closed, Imgproc.MORPH_CLOSE, kernel);
    Mat hierarchy = new Mat();
    List<MatOfPoint> contours = new ArrayList<>();
    Imgproc.findContours(closed, contours, hierarchy, Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE);
    for (int i = 0; i < contours.size(); i++) {
      double area = Imgproc.contourArea(contours.get(i));
      if (area < 50) {
        Imgproc.drawContours(closed, contours, i, new Scalar(0, 0, 0), -1);
      }
    }

// Convert Mat back to ImageProxy
    matToImageProxy(closed, image2);

    if (isMlImageEnabled(graphicOverlay.getContext())) {
      MlImage mlImage =
          new MediaMlImageBuilder(Objects.requireNonNull(image2.getImage()))
              .setRotation(image2.getImageInfo().getRotationDegrees())
              .build();

      requestDetectInImage(
              mlImage,
              graphicOverlay,
              /* originalCameraImage= */ bitmap,
              /* shouldShowFps= */ true,
              frameStartMs)
          // When the image is from CameraX analysis use case, must call image.close() on received
          // images when finished using them. Otherwise, new images may not be received or the
          // camera may stall.
          // Currently MlImage doesn't support ImageProxy directly, so we still need to call
          // ImageProxy.close() here.
          .addOnCompleteListener(results -> image2.close());
      return;
    }

    requestDetectInImage(
            InputImage.fromMediaImage(Objects.requireNonNull(image2.getImage()), image2.getImageInfo().getRotationDegrees()),
            graphicOverlay,
            /* originalCameraImage= */ bitmap,
            /* shouldShowFps= */ true,
            frameStartMs)
        // When the image is from CameraX analysis use case, must call image.close() on received
        // images when finished using them. Otherwise, new images may not be received or the camera
        // may stall.
        .addOnCompleteListener(results -> image.close());
  }

  public static void matToImageProxy(Mat mat, ImageProxy imageProxy) {
    assert mat.channels() == 1 || mat.channels() == 3;
    int imageWidth = mat.width();
    int imageHeight = mat.height();
    int bufferSize = mat.channels() * imageWidth * imageHeight;
    ByteBuffer byteBuffer = ByteBuffer.allocateDirect(bufferSize);
    byteBuffer.order(ByteOrder.nativeOrder());
    if (mat.channels() == 1) {
      byte[] buffer = new byte[bufferSize];
      mat.get(0, 0, buffer);
      byteBuffer.put(buffer);
    } else if (mat.channels() == 3) {
      byte[] buffer = new byte[bufferSize];
      mat.get(0, 0, buffer);
      // swap R and B channels
      for (int i = 0; i < bufferSize; i += 3) {
        byte temp = buffer[i];
        buffer[i] = buffer[i + 2];
        buffer[i + 2] = temp;
      }
      byteBuffer.put(buffer);
    }
    @SuppressLint("UnsafeExperimentalUsageError") Image.Plane[] planes = imageProxy.getImage().getPlanes();
    planes[0].getBuffer().put(byteBuffer);
  }



  // -----------------Common processing logic-------------------------------------------------------
  private Task<T> requestDetectInImage(
      final InputImage image,
      final GraphicOverlay graphicOverlay,
      @Nullable final Bitmap originalCameraImage,
      boolean shouldShowFps,
      long frameStartMs) {
    return setUpListener(
        detectInImage(image), graphicOverlay, originalCameraImage, shouldShowFps, frameStartMs);
  }

  private Task<T> requestDetectInImage(
      final MlImage image,
      final GraphicOverlay graphicOverlay,
      @Nullable final Bitmap originalCameraImage,
      boolean shouldShowFps,
      long frameStartMs) {
    return setUpListener(
        detectInImage(image), graphicOverlay, originalCameraImage, shouldShowFps, frameStartMs);
  }

  private Task<T> setUpListener(
      Task<T> task,
      final GraphicOverlay graphicOverlay,
      @Nullable final Bitmap originalCameraImage,
      boolean shouldShowFps,
      long frameStartMs) {
    final long detectorStartMs = SystemClock.elapsedRealtime();
    return task.addOnSuccessListener(
            executor,
            results -> {
              long endMs = SystemClock.elapsedRealtime();
              long currentFrameLatencyMs = endMs - frameStartMs;
              long currentDetectorLatencyMs = endMs - detectorStartMs;
              if (numRuns >= 500) {
                resetLatencyStats();
              }
              numRuns++;
              frameProcessedInOneSecondInterval++;
              totalFrameMs += currentFrameLatencyMs;
              maxFrameMs = max(currentFrameLatencyMs, maxFrameMs);
              minFrameMs = min(currentFrameLatencyMs, minFrameMs);
              totalDetectorMs += currentDetectorLatencyMs;
              maxDetectorMs = max(currentDetectorLatencyMs, maxDetectorMs);
              minDetectorMs = min(currentDetectorLatencyMs, minDetectorMs);

              // Only log inference info once per second. When frameProcessedInOneSecondInterval is
              // equal to 1, it means this is the first frame processed during the current second.
              if (frameProcessedInOneSecondInterval == 1) {
                Log.d(TAG, "Num of Runs: " + numRuns);
                Log.d(
                    TAG,
                    "Frame latency: max="
                        + maxFrameMs
                        + ", min="
                        + minFrameMs
                        + ", avg="
                        + totalFrameMs / numRuns);
                Log.d(
                    TAG,
                    "Detector latency: max="
                        + maxDetectorMs
                        + ", min="
                        + minDetectorMs
                        + ", avg="
                        + totalDetectorMs / numRuns);
                MemoryInfo mi = new MemoryInfo();
                activityManager.getMemoryInfo(mi);
                long availableMegs = mi.availMem / 0x100000L;
                Log.d(TAG, "Memory available in system: " + availableMegs + " MB");
                temperatureMonitor.logTemperature();
              }

              graphicOverlay.clear();
              if (originalCameraImage != null) {
                graphicOverlay.add(new CameraImageGraphic(graphicOverlay, originalCameraImage));
              }
              VisionProcessorBase.this.onSuccess(results, graphicOverlay);
              if (!PreferenceUtils.shouldHideDetectionInfo(graphicOverlay.getContext())) {
                graphicOverlay.add(
                    new InferenceInfoGraphic(
                        graphicOverlay,
                        currentFrameLatencyMs,
                        currentDetectorLatencyMs,
                        shouldShowFps ? framesPerSecond : null));
              }
              graphicOverlay.postInvalidate();
            })
        .addOnFailureListener(
            executor,
            e -> {
              graphicOverlay.clear();
              graphicOverlay.postInvalidate();
              String error = "Failed to process. Error: " + e.getLocalizedMessage();
              Toast.makeText(
                      graphicOverlay.getContext(),
                      error + "\nCause: " + e.getCause(),
                      Toast.LENGTH_SHORT)
                  .show();
              Log.d(TAG, error);
              e.printStackTrace();
              VisionProcessorBase.this.onFailure(e);
            });
  }

  @Override
  public void stop() {
    executor.shutdown();
    isShutdown = true;
    resetLatencyStats();
    fpsTimer.cancel();
    temperatureMonitor.stop();
  }

  private void resetLatencyStats() {
    numRuns = 0;
    totalFrameMs = 0;
    maxFrameMs = 0;
    minFrameMs = Long.MAX_VALUE;
    totalDetectorMs = 0;
    maxDetectorMs = 0;
    minDetectorMs = Long.MAX_VALUE;
  }

  protected abstract Task<T> detectInImage(InputImage image);

  protected Task<T> detectInImage(MlImage image) {
    return Tasks.forException(
        new MlKitException(
            "MlImage is currently not demonstrated for this feature",
            MlKitException.INVALID_ARGUMENT));
  }

  protected abstract void onSuccess(@NonNull T results, @NonNull GraphicOverlay graphicOverlay);

  protected abstract void onFailure(@NonNull Exception e);

  protected boolean isMlImageEnabled(Context context) {
    return false;
  }
}
