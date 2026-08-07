package com.byd.turnsignalguard.capture;

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
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private ReverseCameraLayout layout = ReverseCameraLayout.defaults();
    private Listener listener;
    private int selectedCamera = ReverseCameraLayout.REAR_CAMERA_INDEX;
    private float downX;
    private float downY;
    private ReverseCameraLayout.Rect startRect;
    private int resizeCorner;

    ReverseCameraEditorView(Context context) {
        super(context);
        setBackgroundColor(Color.rgb(20, 20, 20));
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(dp(3));
        text.setColor(Color.WHITE);
        text.setTextSize(dp(16));
        text.setFakeBoldText(true);
    }

    void setListener(Listener value) {
        listener = value;
    }

    void setLayoutModel(ReverseCameraLayout value) {
        layout = value;
        invalidate();
    }

    void selectCamera(int cameraIndex) {
        if (cameraIndex != ReverseCameraLayout.BACKGROUND_PANE_ID) {
            layout.pane(cameraIndex);
        }
        selectedCamera = cameraIndex;
        invalidate();
        notifyChanged(false);
    }

    int selectedCamera() {
        return selectedCamera;
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
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (getWidth() <= 0 || getHeight() <= 0) return false;
        float x = clamp(event.getX() / getWidth());
        float y = clamp(event.getY() / getHeight());
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            int hitCamera = hitTest(x, y);
            if (hitCamera == 0) return false;
            selectedCamera = hitCamera;
            ReverseCameraLayout.Rect rect = selectedCamera
                    == ReverseCameraLayout.BACKGROUND_PANE_ID
                    ? layout.background : layout.pane(selectedCamera).destination;
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
            ReverseCameraLayout.Rect destination = resizeCorner == 0
                    ? ReverseCameraLayout.destination(startRect.left + dx, startRect.top + dy,
                            startRect.width, startRect.height)
                    : resized(startRect, dx, dy, resizeCorner);
            if (selectedCamera == ReverseCameraLayout.BACKGROUND_PANE_ID) {
                layout = ReverseCameraLayout.withBackground(layout, destination);
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
        ReverseCameraLayout.Rect value = pane.destination;
        RectF rect = new RectF(value.left * getWidth(), value.top * getHeight(),
                value.right() * getWidth(), value.bottom() * getHeight());
        int color = COLORS[index];
        fill.setColor((color & 0x00FFFFFF) | 0x33000000);
        stroke.setColor(color);
        stroke.setStrokeWidth(dp(pane.cameraIndex == selectedCamera ? 5 : 3));
        canvas.drawRect(rect, fill);
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
            fill.setColor(color);
            float radius = dp(8);
            canvas.drawCircle(rect.left, rect.top, radius, fill);
            canvas.drawCircle(rect.right, rect.top, radius, fill);
            canvas.drawCircle(rect.left, rect.bottom, radius, fill);
            canvas.drawCircle(rect.right, rect.bottom, radius, fill);
        }
    }

    private void drawBackgroundPane(Canvas canvas) {
        ReverseCameraLayout.Rect value = layout.background;
        RectF rect = new RectF(value.left * getWidth(), value.top * getHeight(),
                value.right() * getWidth(), value.bottom() * getHeight());
        fill.setColor(0x55000000);
        stroke.setColor(BACKGROUND_COLOR);
        stroke.setStrokeWidth(dp(selectedCamera == ReverseCameraLayout.BACKGROUND_PANE_ID
                ? 5 : 3));
        canvas.drawRect(rect, fill);
        canvas.drawRect(rect, stroke);
        text.setTextAlign(Paint.Align.CENTER);
        canvas.drawText("Background", rect.centerX(), rect.top + dp(22), text);
        text.setTextAlign(Paint.Align.LEFT);
        if (selectedCamera == ReverseCameraLayout.BACKGROUND_PANE_ID) {
            fill.setColor(BACKGROUND_COLOR);
            float radius = dp(8);
            canvas.drawCircle(rect.left, rect.top, radius, fill);
            canvas.drawCircle(rect.right, rect.top, radius, fill);
            canvas.drawCircle(rect.left, rect.bottom, radius, fill);
            canvas.drawCircle(rect.right, rect.bottom, radius, fill);
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
            ReverseCameraLayout.Rect rect, float dx, float dy, int corner) {
        float left = rect.left;
        float top = rect.top;
        float right = rect.right();
        float bottom = rect.bottom();
        if (corner == 1 || corner == 3) {
            left = Math.max(0.0f, Math.min(right - ReverseCameraLayout.MIN_DESTINATION_SIZE,
                    left + dx));
        } else {
            right = Math.min(1.0f, Math.max(left + ReverseCameraLayout.MIN_DESTINATION_SIZE,
                    right + dx));
        }
        if (corner == 1 || corner == 2) {
            top = Math.max(0.0f, Math.min(bottom - ReverseCameraLayout.MIN_DESTINATION_SIZE,
                    top + dy));
        } else {
            bottom = Math.min(1.0f, Math.max(top + ReverseCameraLayout.MIN_DESTINATION_SIZE,
                    bottom + dy));
        }
        return ReverseCameraLayout.destination(left, top, right - left, bottom - top);
    }

    private int hitTest(float x, float y) {
        for (int z = 2; z >= 0; z--) {
            for (ReverseCameraLayout.Pane pane : layout.panes()) {
                ReverseCameraLayout.Rect rect = pane.destination;
                if (pane.zOrder == z && x >= rect.left && x <= rect.right()
                        && y >= rect.top && y <= rect.bottom()) return pane.cameraIndex;
            }
        }
        ReverseCameraLayout.Rect background = layout.background;
        return x >= background.left && x <= background.right()
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
