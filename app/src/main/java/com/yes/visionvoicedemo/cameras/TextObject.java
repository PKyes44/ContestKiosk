package com.yes.visionvoicedemo.cameras;

import android.graphics.RectF;

import java.util.List;

public class TextObject {
    public String text;
    public RectF rectF;

    public TextObject(String text, RectF rectF) {
        this.text = text;
        this.rectF = rectF;
    }

    public void setText(String text) {
        this.text = text;
    }
    public void setRect (RectF rect) {
        this.rectF = rect;
    }
    public String getText() {
        return this.text;
    }
    public RectF getRect() {
        return this.rectF;
    }
}
