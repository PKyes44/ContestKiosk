package com.yes.visionvoicedemo.cameras;

import com.yes.visionvoicedemo.cameras.textdetector.TextGraphic;

import java.util.ArrayList;

public interface TextObjectInterface {
    void onTextInfoAdded(ArrayList<TextObject> textObject);
}
