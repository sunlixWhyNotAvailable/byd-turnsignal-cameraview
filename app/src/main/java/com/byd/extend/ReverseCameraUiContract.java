package com.byd.extend;

import android.content.SharedPreferences;

import com.byd.extend.ui.DisplayTarget;
import com.byd.extend.ui.ReverseElement;

/** Narrow UI seam for changing the destination assigned to one Reverse element. */
public final class ReverseCameraUiContract {
    private ReverseCameraUiContract() {}

    public static void saveElementTarget(
            SharedPreferences preferences, ReverseElement element, DisplayTarget target) {
        if (element == null || target == null) {
            throw new IllegalArgumentException("Reverse element and target are required");
        }
        int paneId;
        switch (element) {
            case Background: paneId = ReverseCameraLayout.BACKGROUND_PANE_ID; break;
            case Widget: paneId = ReverseCameraLayout.WIDGET_PANE_ID; break;
            case Rear: paneId = ReverseCameraLayout.REAR_CAMERA_INDEX; break;
            case RearLeft: paneId = ReverseCameraLayout.REAR_LEFT_CAMERA_INDEX; break;
            case RearRight: paneId = ReverseCameraLayout.REAR_RIGHT_CAMERA_INDEX; break;
            default: throw new AssertionError(element);
        }
        ReverseCameraController.saveElementTarget(preferences, paneId, target.ordinal());
    }
}
