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

package com.yes.visionvoicedemo.cameras.textdetector;

import static java.lang.Math.max;
import static java.lang.Math.min;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.util.Log;
import android.view.MotionEvent;
import android.widget.Toast;

import com.yes.visionvoicedemo.cameras.CameraXLivePreviewActivity;
import com.yes.visionvoicedemo.cameras.GraphicOverlay;
import com.yes.visionvoicedemo.cameras.GraphicOverlay.Graphic;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.Text.Element;
import com.google.mlkit.vision.text.Text.Line;
import com.google.mlkit.vision.text.Text.Symbol;
import com.google.mlkit.vision.text.Text.TextBlock;
import com.yes.visionvoicedemo.cameras.TextObject;
import com.yes.visionvoicedemo.cameras.TextObjectInterface;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Graphic instance for rendering TextBlock position, size, and ID within an associated graphic
 * overlay view.
 */
public class TextGraphic extends Graphic{

  private static final String TAG = "TextGraphic";
  private static final String TEXT_WITH_LANGUAGE_TAG_FORMAT = "%s:%s";

  private static final int TEXT_COLOR = Color.BLACK;
  private static final int MARKER_COLOR = Color.WHITE;
  private static final float TEXT_SIZE = 54.0f;
  private static final float STROKE_WIDTH = 4.0f;

  private final Paint rectPaint;
  private final Paint textPaint;
  private final Paint labelPaint;
  private final Text text;

  private final TextObjectInterface textObjectInterface;

  private final boolean shouldGroupTextInBlocks;
  private final boolean showLanguageTag;
  private final boolean showConfidence;

  public TextGraphic(
          GraphicOverlay overlay,
          Text text,
          TextObjectInterface textObjectInterface,
          boolean shouldGroupTextInBlocks,
          boolean showLanguageTag,
          boolean showConfidence) {
    super(overlay);

    this.text = text;
    this.textObjectInterface = textObjectInterface;
    this.shouldGroupTextInBlocks = shouldGroupTextInBlocks;
    this.showLanguageTag = showLanguageTag;
    this.showConfidence = showConfidence;

    rectPaint = new Paint();
    rectPaint.setColor(MARKER_COLOR);
    rectPaint.setStyle(Paint.Style.STROKE);
    rectPaint.setStrokeWidth(STROKE_WIDTH);

    textPaint = new Paint();
    textPaint.setColor(TEXT_COLOR);
    textPaint.setTextSize(TEXT_SIZE);

    labelPaint = new Paint();
    labelPaint.setColor(MARKER_COLOR);
    labelPaint.setStyle(Paint.Style.FILL);

    // Redraw the overlay, as this graphic has been added.
    postInvalidate();
  }

  public String getText() {
    return text.getText();
  }

  public void updateText() {
    postInvalidate();
  }

  @Override
  public boolean onTouchEvent(MotionEvent event) {
    return false;
  }

  /** Draws the text block annotations for position, size, and raw value on the supplied canvas. */
  @Override
  public void draw(Canvas canvas) {
    Log.d(TAG, "Text is: " + text.getText());

    ArrayList<TextObject> textObjects = new ArrayList<>();
    for (TextBlock textBlock : text.getTextBlocks()) {

      if (shouldGroupTextInBlocks) {
        String text =
                showLanguageTag
                        ? String.format(
                        TEXT_WITH_LANGUAGE_TAG_FORMAT,
                        textBlock.getRecognizedLanguage(),
                        textBlock.getText())
                        : textBlock.getText();
        RectF rectF = new RectF(textBlock.getBoundingBox());

        TextObject textInfo = new TextObject(text, rectF);
        textObjects.add(textInfo);

        drawText(
                text,
                rectF,
                TEXT_SIZE * textBlock.getLines().size() + 2 * STROKE_WIDTH,
                canvas);
      } else {
        for (Line line : textBlock.getLines()) {
          String text =
                  showLanguageTag
                          ? String.format(
                          TEXT_WITH_LANGUAGE_TAG_FORMAT, line.getRecognizedLanguage(), line.getText())
                          : line.getText();
          text =
                  showConfidence
                          ? String.format(Locale.KOREA, "%s (%.2f)", text, line.getConfidence())
                          : text;
          RectF rectF = new RectF(line.getBoundingBox());
          TextObject textInfo = new TextObject(text, rectF);
          textObjects.add(textInfo);
          drawText(text, rectF, TEXT_SIZE + 2 * STROKE_WIDTH, canvas);
        }
      }
    }
    textObjectInterface.onTextInfoAdded(textObjects);
  }

//  public List<TextObject> getTextObjects() {
//    return textObjects;
//  }

  @Override
  public boolean contains(float x, float y) {
    return false;
  }

  private void drawText(String text, RectF rect, float textHeight, Canvas canvas) {
    // If the image is flipped, the left will be translated to right, and the right to left.
    float x0 = translateX(rect.left);
    float x1 = translateX(rect.right);
    rect.left = min(x0, x1);
    rect.right = max(x0, x1);
    rect.top = translateY(rect.top);
    rect.bottom = translateY(rect.bottom);
    canvas.drawRect(rect, rectPaint);
    float textWidth = textPaint.measureText(text);
    canvas.drawRect(
        rect.left - STROKE_WIDTH,
        rect.top - textHeight,
        rect.left + textWidth + 2 * STROKE_WIDTH,
        rect.top,
        labelPaint);
    // Renders the text at the bottom of the box.
    canvas.drawText(text, rect.left, rect.top - STROKE_WIDTH, textPaint);
  }

//  public TextObject getNearestTextObject(float x, float y) {
//    TextObject nearestTextObject = null;
//    float nearestDistance = Float.MAX_VALUE;
//    Toast.makeText(getApplicationContext(), String.valueOf(textObjects.size()), Toast.LENGTH_SHORT).show();
//    for (TextObject textObject : textObjects) {
//      float distance = distance(x, y, textObject.getRect().centerX(), textObject.getRect().centerY());
//      if (distance < nearestDistance) {
//        nearestDistance = distance;
//        nearestTextObject = textObject;
//      }
//    }
//    return nearestTextObject;
//  }

  private float distance(float x1, float y1, float x2, float y2) {
    return (float) Math.sqrt(Math.pow(x1 - x2, 2) + Math.pow(y1 - y2, 2));
  }

}
