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

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.media.MediaRecorder;
import android.os.Build.VERSION_CODES;
import android.os.Bundle;
import android.speech.RecognizerIntent;
import android.util.Log;
import android.util.Size;
import android.view.MotionEvent;
import android.view.View;
import android.widget.AdapterView;
import android.widget.AdapterView.OnItemSelectedListener;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ToggleButton;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraInfoUnavailableException;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;
import androidx.core.util.Pair;
import androidx.lifecycle.ViewModelProvider;
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory;

import com.google.android.gms.common.annotation.KeepName;
import com.google.mlkit.common.MlKitException;
import com.google.mlkit.vision.text.Text;
import com.yes.visionvoicedemo.R;
import com.yes.visionvoicedemo.STTActivity;
import com.yes.visionvoicedemo.cameras.textdetector.TextGraphic;
import com.yes.visionvoicedemo.cameras.textdetector.TextRecognitionProcessor;
import com.yes.visionvoicedemo.cameras.preference.PreferenceUtils;
//import com.google.mlkit.vision.demo.preference.SettingsActivity;
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Live preview demo app for ML Kit APIs using CameraX. */
@KeepName
@RequiresApi(VERSION_CODES.LOLLIPOP)
public class CameraXLivePreviewActivity extends AppCompatActivity
    implements OnItemSelectedListener, CompoundButton.OnCheckedChangeListener {

  private Integer REQUEST_RECORD_AUDIO_PERMISSION = 200;
  private MediaRecorder mediaRecorder;
  private Boolean isRecording = false;
  private Button recordButton;
  private TextView convertedTextView;
  private STTActivity voiceOrderActivity;

  private static final String TAG = "CameraXLivePreview";

  private static final String TEXT_RECOGNITION_KOREAN = "한국어 인식";

  private static final String STATE_SELECTED_MODEL = "selected_model";

  private PreviewView previewView;
  private GraphicOverlay graphicOverlay;

  @Nullable private ProcessCameraProvider cameraProvider;
  @Nullable private Preview previewUseCase;
  @Nullable private ImageAnalysis analysisUseCase;
  @Nullable private VisionImageProcessor imageProcessor;
  private boolean needUpdateGraphicOverlayImageSourceInfo;

  private String selectedModel = TEXT_RECOGNITION_KOREAN;
  private int lensFacing = CameraSelector.LENS_FACING_BACK;
  private CameraSelector cameraSelector;


  @SuppressLint({"MissingInflatedId", "CutPasteId"})
  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    Log.d(TAG, "onCreate");

    if (savedInstanceState != null) {
      selectedModel = savedInstanceState.getString(STATE_SELECTED_MODEL, TEXT_RECOGNITION_KOREAN);
    }
    cameraSelector = new CameraSelector.Builder().requireLensFacing(lensFacing).build();

    setContentView(R.layout.activity_vision_camerax_live_preview);
    previewView = findViewById(R.id.preview_view);
    if (previewView == null) {
      Log.d(TAG, "previewView is null");
    }
    graphicOverlay = findViewById(R.id.graphic_overlay);
    if (graphicOverlay == null) {
      Log.d(TAG, "graphicOverlay is null");
    }

    Spinner spinner = findViewById(R.id.spinner);
    List<String> options = new ArrayList<>();
    options.add(TEXT_RECOGNITION_KOREAN);

    // Creating adapter for spinner
    ArrayAdapter<String> dataAdapter = new ArrayAdapter<>(this, R.layout.spinner_style, options);
    // Drop down layout style - list view with radio button
    dataAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
    // attaching data adapter to spinner
    spinner.setAdapter(dataAdapter);
    spinner.setOnItemSelectedListener(this);

    ToggleButton facingSwitch = findViewById(R.id.facing_switch);
    facingSwitch.setOnCheckedChangeListener(this);

    new ViewModelProvider(this, AndroidViewModelFactory.getInstance(getApplication()))
        .get(CameraXViewModel.class)
        .getProcessCameraProvider()
        .observe(
            this,
            provider -> {
              cameraProvider = provider;
              bindAllCameraUseCases();
            });
    recordButton = findViewById(R.id.record_btn);
    convertedTextView = findViewById(R.id.convertedTextByUser);

    recordButton.setOnClickListener(new View.OnClickListener() {
      @Override
      public void onClick(View v) {
        if (isRecording) {
          stopRecording();
        } else {
          startRecording();
        }
      }
    });

    previewView.setOnTouchListener(new View.OnTouchListener() {
      @Override
      public boolean onTouch(View v, MotionEvent event) {
        int action = event.getAction();
        float curX = event.getX();
        float curY = event.getY();

        if (action == MotionEvent.ACTION_DOWN) {
          graphicOverlay.invalidate(); // 그래픽 오버레이를 다시 그려주도록 invalidate() 함수 호출
          TextGraphic textGraphic = getTouchedTextGraphic(curX, curY);
          if (textGraphic != null) {
            String text = textGraphic.getText();
            Toast.makeText(CameraXLivePreviewActivity.this, text, Toast.LENGTH_SHORT).show();
          } else {
            Toast.makeText(CameraXLivePreviewActivity.this, "Text not found", Toast.LENGTH_SHORT).show();
          }
          return true;
        }
        return false;
      }
    });
  }

  private TextGraphic getTouchedTextGraphic(float x, float y) {
    for (GraphicOverlay.Graphic graphic : graphicOverlay.getGraphics()) {
      if (graphic != null && graphic.contains(x, y)) {
        return (TextGraphic) graphic;
      }
    }
    return null;
  }

  private void startRecording() {
// 외부 캐시 디렉토리 경로를 가져옵니다
    File cacheDir = getExternalCacheDir();
    if (cacheDir != null) {
      String filePath = cacheDir.getAbsolutePath() + "/audio_file.3gp";

      // MediaRecorder 객체 생성
      mediaRecorder = new MediaRecorder();
      mediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
      mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.THREE_GPP);
      mediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB);

      // 녹음된 오디오 데이터를 파일에 저장할 경로를 설정합니다
      mediaRecorder.setOutputFile(filePath);
      try {
        mediaRecorder.prepare();
      } catch (IOException e) {
        e.printStackTrace();
      }

      mediaRecorder.start();

      isRecording = true;
    }
  }


  private void stopRecording() {
    if (mediaRecorder != null) {
      mediaRecorder.stop();
      mediaRecorder.release();
      mediaRecorder = null;
    }
    isRecording = false;
    // 녹음된 오디오 파일을 텍스트로 변환하여 TextView에 표시
    convertAudioToText();
  }

  private void convertAudioToText() {
    Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
    intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
    intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ko-KR");
    // 음성 인식 앱을 호출하여 오디오를 텍스트로 변환
    startActivityForResult(intent, REQUEST_RECORD_AUDIO_PERMISSION);
  }

  @Override
  protected void onActivityResult(int requestCode, int resultCode, Intent data) {
    super.onActivityResult(requestCode, resultCode, data);
    if (requestCode == REQUEST_RECORD_AUDIO_PERMISSION && resultCode == RESULT_OK) {
      ArrayList<String> results = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
      String convertedText = results.get(0);
      convertedTextView.setText(convertedText);
      if (convertedText != null) {
        List<Pair<String, Integer>> orderInfo;
        TextView botTxt = findViewById(R.id.convertedTextByBot);
        List<String> ments = null;
        Pair<List<Pair<String, Integer>>, List<String>> order = voiceOrderActivity.processOrder(convertedText);
        orderInfo = order.first;
        ments = order.second;
        Log.d("ments is", String.valueOf(ments));
        botTxt.setText(ments != null ? ments.get(0) : "");
      }
    }
  }

  @Override
  protected void onStop() {
    super.onStop();
    if (mediaRecorder != null) {
      mediaRecorder.release();
      mediaRecorder = null;
    }
  }

  @Override
  protected void onSaveInstanceState(@NonNull Bundle bundle) {
    super.onSaveInstanceState(bundle);
    bundle.putString(STATE_SELECTED_MODEL, selectedModel);
  }

  @Override
  public synchronized void onItemSelected(AdapterView<?> parent, View view, int pos, long id) {
    // An item was selected. You can retrieve the selected item using
    // parent.getItemAtPosition(pos)
    selectedModel = parent.getItemAtPosition(pos).toString();
    Log.d(TAG, "Selected model: " + selectedModel);
    bindAnalysisUseCase();
  }

  @Override
  public void onNothingSelected(AdapterView<?> parent) {
    // Do nothing.
  }

  @Override
  public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
    if (cameraProvider == null) {
      return;
    }
    int newLensFacing =
        lensFacing == CameraSelector.LENS_FACING_FRONT
            ? CameraSelector.LENS_FACING_BACK
            : CameraSelector.LENS_FACING_FRONT;
    CameraSelector newCameraSelector =
        new CameraSelector.Builder().requireLensFacing(newLensFacing).build();
    try {
      if (cameraProvider.hasCamera(newCameraSelector)) {
        Log.d(TAG, "Set facing to " + newLensFacing);
        lensFacing = newLensFacing;
        cameraSelector = newCameraSelector;
        bindAllCameraUseCases();
        return;
      }
    } catch (CameraInfoUnavailableException e) {
      // Falls through
    }
    Toast.makeText(
            getApplicationContext(),
            "This device does not have lens with facing: " + newLensFacing,
            Toast.LENGTH_SHORT)
        .show();
  }

  @Override
  public void onResume() {
    super.onResume();
    bindAllCameraUseCases();
  }

  @Override
  protected void onPause() {
    super.onPause();
    if (imageProcessor != null) {
      imageProcessor.stop();
    }
  }

  @Override
  public void onDestroy() {
    super.onDestroy();
    if (imageProcessor != null) {
      imageProcessor.stop();
    }
  }

  private void bindAllCameraUseCases() {
    if (cameraProvider != null) {
      // As required by CameraX API, unbinds all use cases before trying to re-bind any of them.
      cameraProvider.unbindAll();
      bindPreviewUseCase();
      bindAnalysisUseCase();
    }
  }

  private void bindPreviewUseCase() {
    if (!PreferenceUtils.isCameraLiveViewportEnabled(this)) {
      return;
    }
    if (cameraProvider == null) {
      return;
    }
    if (previewUseCase != null) {
      cameraProvider.unbind(previewUseCase);
    }

    Preview.Builder builder = new Preview.Builder();
    Size targetResolution = PreferenceUtils.getCameraXTargetResolution(this, lensFacing);
    if (targetResolution != null) {
      builder.setTargetResolution(targetResolution);
    }
    previewUseCase = builder.build();
    previewUseCase.setSurfaceProvider(previewView.getSurfaceProvider());
    cameraProvider.bindToLifecycle(/* lifecycleOwner= */ this, cameraSelector, previewUseCase);
  }

  private void bindAnalysisUseCase() {
    if (cameraProvider == null) {
      return;
    }
    if (analysisUseCase != null) {
      cameraProvider.unbind(analysisUseCase);
    }
    if (imageProcessor != null) {
      imageProcessor.stop();
    }

    try {
      if (TEXT_RECOGNITION_KOREAN.equals(selectedModel)) {
        Log.i(TAG, "Using on-device Text recognition Processor for Latin and Korean.");
        imageProcessor =
                new TextRecognitionProcessor(this, new KoreanTextRecognizerOptions.Builder().build());
      } else {
        throw new IllegalStateException("Invalid model name");
      }
    } catch (Exception e) {
      Log.e(TAG, "Can not create image processor: " + selectedModel, e);
      Toast.makeText(
              getApplicationContext(),
              "Can not create image processor: " + e.getLocalizedMessage(),
              Toast.LENGTH_LONG)
          .show();
      return;
    }

    ImageAnalysis.Builder builder = new ImageAnalysis.Builder();
    Size targetResolution = PreferenceUtils.getCameraXTargetResolution(this, lensFacing);
    if (targetResolution != null) {
      builder.setTargetResolution(targetResolution);
    }
    analysisUseCase = builder.build();

    needUpdateGraphicOverlayImageSourceInfo = true;
    analysisUseCase.setAnalyzer(
        // imageProcessor.processImageProxy will use another thread to run the detection underneath,
        // thus we can just runs the analyzer itself on main thread.
        ContextCompat.getMainExecutor(this),
        imageProxy -> {
          if (needUpdateGraphicOverlayImageSourceInfo) {
            boolean isImageFlipped = lensFacing == CameraSelector.LENS_FACING_FRONT;
            int rotationDegrees = imageProxy.getImageInfo().getRotationDegrees();
            if (rotationDegrees == 0 || rotationDegrees == 180) {
              graphicOverlay.setImageSourceInfo(
                  imageProxy.getWidth(), imageProxy.getHeight(), isImageFlipped);
            } else {
              graphicOverlay.setImageSourceInfo(
                  imageProxy.getHeight(), imageProxy.getWidth(), isImageFlipped);
            }
            needUpdateGraphicOverlayImageSourceInfo = false;
          }
          try {
            imageProcessor.processImageProxy(imageProxy, graphicOverlay);
          } catch (MlKitException e) {
            Log.e(TAG, "Failed to process image. Error: " + e.getLocalizedMessage());
            Toast.makeText(getApplicationContext(), e.getLocalizedMessage(), Toast.LENGTH_SHORT)
                .show();
          }
        });

    cameraProvider.bindToLifecycle(/* lifecycleOwner= */ this, cameraSelector, analysisUseCase);
  }

}
