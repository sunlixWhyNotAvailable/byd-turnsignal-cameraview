package com.byd.extend;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Executable seam for the editor's visibility-only rendering contract. */
public class ReverseEditorRenderingPolicyTest {
    @Test
    public void hiddenPaneIsSkippedUnlessItIsTheSelectedOutline() {
        assertFalse(ReverseCameraEditorView.shouldDrawElement(
                ReverseCameraLayout.VISIBILITY_REAR, ReverseCameraLayout.REAR_LEFT_CAMERA_INDEX,
                ReverseCameraLayout.REAR_CAMERA_INDEX));
        assertTrue(ReverseCameraEditorView.shouldDrawElement(
                ReverseCameraLayout.VISIBILITY_REAR, ReverseCameraLayout.REAR_LEFT_CAMERA_INDEX,
                ReverseCameraLayout.REAR_LEFT_CAMERA_INDEX));
    }

    @Test
    public void visiblePaneRemainsOutlinedWhenNotSelected() {
        assertTrue(ReverseCameraEditorView.shouldDrawElement(
                ReverseCameraLayout.VISIBILITY_REAR_LEFT, ReverseCameraLayout.REAR_LEFT_CAMERA_INDEX,
                ReverseCameraLayout.REAR_CAMERA_INDEX));
    }

    @Test
    public void nonCameraElementsDoNotUseCameraVisibilityBits() {
        assertTrue(ReverseCameraEditorView.shouldDrawElement(
                0, ReverseCameraLayout.BACKGROUND_PANE_ID,
                ReverseCameraLayout.REAR_CAMERA_INDEX));
        assertTrue(ReverseCameraEditorView.shouldDrawElement(
                0, ReverseCameraLayout.WIDGET_PANE_ID,
                ReverseCameraLayout.REAR_CAMERA_INDEX));
    }
}
