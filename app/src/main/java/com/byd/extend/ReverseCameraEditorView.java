package com.byd.extend;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

final class ReverseCameraEditorView extends View {
    interface Listener {
        void onLayoutChanged(ReverseCameraLayout layout, int selectedCamera, boolean finished);
    }

    private static final int[] COLORS = {0xFF42A5F5, 0xFF66BB6A, 0xFFFFCA28};
    private static final String[] LABELS = {"Rear", "Left", "Right"};
    private static final int BACKGROUND_COLOR = 0xFFAB47BC;
    private static final int WIDGET_COLOR = 0xFF26C6DA;
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private ReverseCameraLayout layout = ReverseCameraLayout.defaults();
    private Listener listener;
    private int selectedCamera = ReverseCameraLayout.REAR_CAMERA_INDEX;
    private boolean editable = true;
    /** Runtime visibility supplied by the real Reverse host; hidden selected panes stay outlined. */
    private int visibilityMask = ReverseCameraLayout.VISIBILITY_ALL;
    private boolean widgetVisible = true;
    private float downX;
    private float downY;
    private ReverseCameraLayout.Rect startRect;
    private int resizeCorner;

    ReverseCameraEditorView(Context context) {
        super(context);
        // The real camera host owns pixels; this editor contributes outlines/handles only.
        setBackgroundColor(Color.TRANSPARENT);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(dp(3));
        text.setColor(Color.WHITE);
        text.setTextSize(dp(16));
        text.setFakeBoldText(true);
    }

    void setListener(Listener value) {
        listener = value;
    }

    /** Placement is the only Reverse section allowed to consume editor gestures. */
    void setEditable(boolean value) {
        editable = value;
        if (!value) {
            startRect = null;
            resizeCorner = 0;
        }
        invalidate();
    }

    void setLayoutModel(ReverseCameraLayout value) {
        layout = value;
        invalidate();
    }

    void setVisibilityMask(int value) {
        visibilityMask = ReverseCameraLayout.requireVisibilityMask(value);
        invalidate();
    }

    void setWidgetVisible(boolean value) {
        widgetVisible = value;
        invalidate();
    }

    static boolean shouldDrawElement(int visibilityMask, int paneId, int selectedCamera) {
        if (paneId == ReverseCameraLayout.BACKGROUND_PANE_ID
                || paneId == ReverseCameraLayout.WIDGET_PANE_ID) return true;
        return paneId == selectedCamera || ReverseCameraLayout.isVisible(visibilityMask, paneId);
    }

    void selectCamera(int cameraIndex) {
        if (cameraIndex != ReverseCameraLayout.BACKGROUND_PANE_ID
                && cameraIndex != ReverseCameraLayout.WIDGET_PANE_ID) {
            layout.pane(cameraIndex);
        }
        selectedCamera = cameraIndex;
        invalidate();
        notifyChanged(false);
    }

    int selectedCamera() {
        return selectedCamera;
    }

    /** Synchronizes the Compose-selected pane without dispatching a duplicate change event. */
    void setSelectedCameraSilently(int cameraIndex) {
        if (cameraIndex != ReverseCameraLayout.BACKGROUND_PANE_ID
                && cameraIndex != ReverseCameraLayout.WIDGET_PANE_ID) {
            layout.pane(cameraIndex);
        }
        selectedCamera = cameraIndex;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        drawBackgroundPane(canvas);
        ReverseCameraLayout.Pane[] panes = layout.panes();
        for (int z = 0; z < panes.length; z++) {
            for (int i = 0; i < panes.length; i++) {
                if (panes[i].zOrder == z) drawPane(canvas, panes[i], i);
            }
        }
        drawWidgetPane(canvas);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (!editable) return false;
        if (getWidth() <= 0 || getHeight() <= 0) return false;
        float x = clamp(event.getX() / getWidth());
        float y = clamp(event.getY() / getHeight());
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            int hitCamera = hitTest(x, y);
            if (hitCamera == 0) return false;
            selectedCamera = hitCamera;
            ReverseCameraLayout.Rect rect = selectedCamera
                    == ReverseCameraLayout.BACKGROUND_PANE_ID
                    ? layout.background : selectedCamera == ReverseCameraLayout.WIDGET_PANE_ID
                            ? layout.widget : layout.pane(selectedCamera).destination;
            downX = x;
            downY = y;
            startRect = rect;
            float thresholdX = dp(28) / getWidth();
            float thresholdY = dp(28) / getHeight();
            resizeCorner = cornerAt(rect, x, y, thresholdX, thresholdY);
            notifyChanged(false);
            invalidate();
            return true;
        }
        if (event.getActionMasked() == MotionEvent.ACTION_MOVE && startRect != null) {
            float dx = x - downX;
            float dy = y - downY;
            boolean widget = selectedCamera == ReverseCameraLayout.WIDGET_PANE_ID;
            ReverseCameraLayout.Rect destination = resizeCorner == 0
                    ? bounded(startRect.left + dx, startRect.top + dy,
                            startRect.width, startRect.height, widget)
                    : resized(startRect, dx, dy, resizeCorner, widget);
            if (selectedCamera == ReverseCameraLayout.BACKGROUND_PANE_ID) {
                layout = ReverseCameraLayout.withBackground(layout, destination);
            } else if (widget) {
                layout = ReverseCameraLayout.withWidget(layout, destination);
            } else {
                ReverseCameraLayout.Pane pane = layout.pane(selectedCamera);
                layout = ReverseCameraLayout.withPane(
                        layout, selectedCamera, destination, pane.sourceCrop);
            }
            notifyChanged(false);
            invalidate();
            return true;
        }
        if ((event.getActionMasked() == MotionEvent.ACTION_UP
                || event.getActionMasked() == MotionEvent.ACTION_CANCEL) && startRect != null) {
            startRect = null;
            resizeCorner = 0;
            notifyChanged(true);
            return true;
        }
        return true;
    }

    private void drawPane(Canvas canvas, ReverseCameraLayout.Pane pane, int index) {
        if (!shouldDrawElement(visibilityMask, pane.cameraIndex, selectedCamera)) return;
        ReverseCameraLayout.Rect value = pane.destination;
        RectF rect = new RectF(value.left * getWidth(), value.top * getHeight(),
                value.right() * getWidth(), value.bottom() * getHeight());
        int color = COLORS[index];
        stroke.setColor(color);
        stroke.setStrokeWidth(dp(pane.cameraIndex == selectedCamera ? 5 : 3));
        canvas.drawRect(rect, stroke);
        if (index == 0) {
            text.setTextAlign(Paint.Align.CENTER);
            Paint.FontMetrics metrics = text.getFontMetrics();
            canvas.drawText(LABELS[index], rect.centerX(),
                    rect.centerY() - (metrics.ascent + metrics.descent) / 2.0f, text);
        } else if (index == 1) {
            text.setTextAlign(Paint.Align.LEFT);
            canvas.drawText(LABELS[index], rect.left + dp(8), rect.bottom - dp(8), text);
        } else {
            text.setTextAlign(Paint.Align.RIGHT);
            canvas.drawText(LABELS[index], rect.right - dp(8), rect.bottom - dp(8), text);
        }
        text.setTextAlign(Paint.Align.LEFT);
        if (pane.cameraIndex == selectedCamera) {
            float radius = dp(8);
            canvas.drawCircle(rect.left, rect.top, radius, stroke);
            canvas.drawCircle(rect.right, rect.top, radius, stroke);
            canvas.drawCircle(rect.left, rect.bottom, radius, stroke);
            canvas.drawCircle(rect.right, rect.bottom, radius, stroke);
        }
    }

    private void drawBackgroundPane(Canvas canvas) {
        if (!shouldDrawElement(visibilityMask, ReverseCameraLayout.BACKGROUND_PANE_ID,
                selectedCamera)) return;
        ReverseCameraLayout.Rect value = layout.background;
        RectF rect = new RectF(value.left * getWidth(), value.top * getHeight(),
                value.right() * getWidth(), value.bottom() * getHeight());
        stroke.setColor(BACKGROUND_COLOR);
        stroke.setStrokeWidth(dp(selectedCamera == ReverseCameraLayout.BACKGROUND_PANE_ID
                ? 5 : 3));
        canvas.drawRect(rect, stroke);
        text.setTextAlign(Paint.Align.CENTER);
        canvas.drawText("Background", rect.centerX(), rect.top + dp(22), text);
        text.setTextAlign(Paint.Align.LEFT);
        if (selectedCamera == ReverseCameraLayout.BACKGROUND_PANE_ID) {
            float radius = dp(8);
            canvas.drawCircle(rect.left, rect.top, radius, stroke);
            canvas.drawCircle(rect.right, rect.top, radius, stroke);
            canvas.drawCircle(rect.left, rect.bottom, radius, stroke);
            canvas.drawCircle(rect.right, rect.bottom, radius, stroke);
        }
    }

    private void drawWidgetPane(Canvas canvas) {
        if (!widgetVisible && selectedCamera != ReverseCameraLayout.WIDGET_PANE_ID) return;
        if (!shouldDrawElement(visibilityMask, ReverseCameraLayout.WIDGET_PANE_ID,
                selectedCamera)) return;
        drawFixedPane(canvas, layout.widget, ReverseCameraLayout.WIDGET_PANE_ID,
                WIDGET_COLOR, "Widget");
    }

    private void drawFixedPane(
            Canvas canvas, ReverseCameraLayout.Rect value, int paneId,
            int color, String label) {
        RectF rect = new RectF(value.left * getWidth(), value.top * getHeight(),
                value.right() * getWidth(), value.bottom() * getHeight());
        stroke.setColor(color);
        stroke.setStrokeWidth(dp(selectedCamera == paneId ? 5 : 3));
        canvas.drawRect(rect, stroke);
        text.setTextAlign(Paint.Align.CENTER);
        Paint.FontMetrics metrics = text.getFontMetrics();
        canvas.drawText(label, rect.centerX(),
                rect.centerY() - (metrics.ascent + metrics.descent) / 2.0f, text);
        text.setTextAlign(Paint.Align.LEFT);
        if (selectedCamera == paneId) {
            float radius = dp(8);
            canvas.drawCircle(rect.left, rect.top, radius, stroke);
            canvas.drawCircle(rect.right, rect.top, radius, stroke);
            canvas.drawCircle(rect.left, rect.bottom, radius, stroke);
            canvas.drawCircle(rect.right, rect.bottom, radius, stroke);
        }
    }

    private static int cornerAt(
            ReverseCameraLayout.Rect rect, float x, float y,
            float thresholdX, float thresholdY) {
        boolean left = Math.abs(x - rect.left) <= thresholdX;
        boolean right = Math.abs(x - rect.right()) <= thresholdX;
        boolean top = Math.abs(y - rect.top) <= thresholdY;
        boolean bottom = Math.abs(y - rect.bottom()) <= thresholdY;
        if (left && top) return 1;
        if (right && top) return 2;
        if (left && bottom) return 3;
        if (right && bottom) return 4;
        return 0;
    }

    private static ReverseCameraLayout.Rect resized(
            ReverseCameraLayout.Rect rect, float dx, float dy, int corner, boolean widget) {
        float minimumWidth = widget
                ? ReverseCameraLayout.MIN_WIDGET_WIDTH
                : ReverseCameraLayout.MIN_DESTINATION_SIZE;
        float minimumHeight = widget
                ? ReverseCameraLayout.MIN_WIDGET_HEIGHT
                : ReverseCameraLayout.MIN_DESTINATION_SIZE;
        float left = rect.left;
        float top = rect.top;
        float right = rect.right();
        float bottom = rect.bottom();
        if (corner == 1 || corner == 3) {
            left = Math.max(0.0f, Math.min(right - minimumWidth,
                    left + dx));
        } else {
            right = Math.min(1.0f, Math.max(left + minimumWidth,
                    right + dx));
        }
        if (corner == 1 || corner == 2) {
            top = Math.max(0.0f, Math.min(bottom - minimumHeight,
                    top + dy));
        } else {
            bottom = Math.min(1.0f, Math.max(top + minimumHeight,
                    bottom + dy));
        }
        return bounded(left, top, right - left, bottom - top, widget);
    }

    private static ReverseCameraLayout.Rect bounded(
            float left, float top, float width, float height, boolean widget) {
        return widget
                ? ReverseCameraLayout.widgetDestination(left, top, width, height)
                : ReverseCameraLayout.destination(left, top, width, height);
    }

    private int hitTest(float x, float y) {
        ReverseCameraLayout.Rect widget = layout.widget;
        if (widgetVisible && shouldDrawElement(visibilityMask,
                ReverseCameraLayout.WIDGET_PANE_ID, selectedCamera)
                && x >= widget.left && x <= widget.right()
                && y >= widget.top && y <= widget.bottom()) {
            return ReverseCameraLayout.WIDGET_PANE_ID;
        }
        for (int z = 2; z >= 0; z--) {
            for (ReverseCameraLayout.Pane pane : layout.panes()) {
                ReverseCameraLayout.Rect rect = pane.destination;
                if (pane.zOrder == z && shouldDrawElement(visibilityMask, pane.cameraIndex, selectedCamera)
                        && x >= rect.left && x <= rect.right()
                        && y >= rect.top && y <= rect.bottom()) return pane.cameraIndex;
            }
        }
        ReverseCameraLayout.Rect background = layout.background;
        return shouldDrawElement(visibilityMask, ReverseCameraLayout.BACKGROUND_PANE_ID, selectedCamera)
                && x >= background.left && x <= background.right()
                && y >= background.top && y <= background.bottom()
                ? ReverseCameraLayout.BACKGROUND_PANE_ID : 0;
    }

    private void notifyChanged(boolean finished) {
        if (listener != null) listener.onLayoutChanged(layout, selectedCamera, finished);
    }

    private float dp(int value) {
        return value * getResources().getDisplayMetrics().density;
    }

    private static float clamp(float value) {
        return Math.max(0.0f, Math.min(1.0f, value));
    }
}
