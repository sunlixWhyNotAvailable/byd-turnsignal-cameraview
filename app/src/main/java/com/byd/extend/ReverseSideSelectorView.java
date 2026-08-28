package com.byd.extend;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.MotionEvent;
import android.view.View;

/**
 * Small, movable reverse-camera side selector.  The car and wheels are visual
 * state only; only the two axle buttons are touch targets.
 */
final class ReverseSideSelectorView extends View {
    static final int MODE_REAR = 0;
    static final int MODE_FRONT = 1;

    interface Listener {
        void onModeChanged(int mode);
    }

    private static final float BUTTON_LEFT = 0.14f;
    private static final float BUTTON_RIGHT = 0.86f;
    private static final float FRONT_BUTTON_TOP = 0.04f;
    private static final float FRONT_BUTTON_BOTTOM = 0.22f;
    private static final float REAR_BUTTON_TOP = 0.78f;
    private static final float REAR_BUTTON_BOTTOM = 0.96f;
    private static final int PANEL_COLOR = 0xA8101826;
    private static final int BODY_COLOR = 0xFF26364C;
    private static final int BODY_STROKE_COLOR = 0xFF9FB7D5;
    private static final int INACTIVE_COLOR = 0xFF77869A;
    private static final int ACTIVE_COLOR = 0xFF29A9FF;
    private static final int ACTIVE_BUTTON_COLOR = 0xFF147CC1;
    private static final int PRESSED_BUTTON_COLOR = 0xFF0D5D97;

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path carPath = new Path();
    private Listener listener;
    private int mode = MODE_REAR;
    private boolean leftVisible;
    private boolean rightVisible;
    private int pressedMode = -1;

    ReverseSideSelectorView(Context context) {
        super(context);
        setWillNotDraw(false);
        setClickable(true);
        setFocusable(false);
        setBackgroundColor(Color.TRANSPARENT);
        stroke.setStyle(Paint.Style.STROKE);
        text.setColor(Color.WHITE);
        text.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        text.setFakeBoldText(true);
        text.setTextAlign(Paint.Align.CENTER);
    }

    void setListener(Listener value) {
        listener = value;
    }

    void setMode(int value) {
        int next = requireMode(value);
        if (mode == next) {
            invalidate();
            return;
        }
        mode = next;
        pressedMode = -1;
        invalidate();
        if (listener != null) listener.onModeChanged(mode);
    }

    int mode() {
        return mode;
    }

    void setEffectiveVisibility(boolean left, boolean right) {
        if (leftVisible == left && rightVisible == right) return;
        leftVisible = left;
        rightVisible = right;
        invalidate();
    }

    boolean isLeftVisible() {
        return leftVisible;
    }

    boolean isRightVisible() {
        return rightVisible;
    }

    /** Returns [left, top, right, bottom] in normalized 0..1 view coordinates. */
    static float[] normalizedButtonRect(int buttonMode) {
        requireMode(buttonMode);
        return buttonMode == MODE_FRONT
                ? new float[]{BUTTON_LEFT, FRONT_BUTTON_TOP, BUTTON_RIGHT, FRONT_BUTTON_BOTTOM}
                : new float[]{BUTTON_LEFT, REAR_BUTTON_TOP, BUTTON_RIGHT, REAR_BUTTON_BOTTOM};
    }

    /** Returns the button mode at normalized coordinates, or -1 outside both buttons. */
    static int modeAtNormalized(float x, float y) {
        if (!Float.isFinite(x) || !Float.isFinite(y)) return -1;
        if (contains(normalizedButtonRect(MODE_FRONT), x, y)) return MODE_FRONT;
        if (contains(normalizedButtonRect(MODE_REAR), x, y)) return MODE_REAR;
        return -1;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (getWidth() <= 0 || getHeight() <= 0) return;
        float width = getWidth();
        float height = getHeight();
        float unit = Math.min(width, height);
        float panelRadius = unit * 0.06f;
        fill.setColor(PANEL_COLOR);
        canvas.drawRoundRect(0, 0, width, height, panelRadius, panelRadius, fill);

        drawButton(canvas, MODE_FRONT, normalizedButtonRect(MODE_FRONT), width, height);
        drawButton(canvas, MODE_REAR, normalizedButtonRect(MODE_REAR), width, height);
        drawCar(canvas, width, height, unit);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (getWidth() <= 0 || getHeight() <= 0) return false;
        float x = event.getX() / getWidth();
        float y = event.getY() / getHeight();
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                pressedMode = modeAtNormalized(x, y);
                if (pressedMode < 0) return false;
                invalidate();
                return true;
            case MotionEvent.ACTION_MOVE:
                if (pressedMode < 0) return false;
                invalidate();
                return true;
            case MotionEvent.ACTION_UP:
                int selected = pressedMode;
                pressedMode = -1;
                if (selected >= 0 && modeAtNormalized(x, y) == selected) {
                    setMode(selected);
                    performClick();
                }
                invalidate();
                return selected >= 0;
            case MotionEvent.ACTION_CANCEL:
                pressedMode = -1;
                invalidate();
                return true;
            default:
                return pressedMode >= 0;
        }
    }

    @Override
    public boolean performClick() {
        super.performClick();
        return true;
    }

    private void drawButton(
            Canvas canvas, int buttonMode, float[] normalized, float width, float height) {
        RectF rect = new RectF(
                normalized[0] * width, normalized[1] * height,
                normalized[2] * width, normalized[3] * height);
        boolean selected = mode == buttonMode;
        boolean active = selected && (leftVisible || rightVisible);
        boolean pressed = pressedMode == buttonMode;
        fill.setColor(pressed ? PRESSED_BUTTON_COLOR
                : active ? ACTIVE_BUTTON_COLOR : 0x99314257);
        canvas.drawRoundRect(rect, rect.height() * 0.28f, rect.height() * 0.28f, fill);
        stroke.setStrokeWidth(Math.max(1.0f, dp(1.5f)));
        stroke.setColor(active ? ACTIVE_COLOR : 0x887F92A9);
        canvas.drawRoundRect(rect, rect.height() * 0.28f, rect.height() * 0.28f, stroke);
        text.setTextSize(Math.max(dp(12), rect.height() * 0.46f));
        canvas.drawText(buttonMode == MODE_FRONT ? "Перед" : "Зад",
                rect.centerX(), rect.centerY() - (text.ascent() + text.descent()) / 2.0f, text);
    }

    private void drawCar(Canvas canvas, float width, float height, float unit) {
        float centerX = width / 2.0f;
        float bodyWidth = unit * 0.25f;
        float bodyTop = height * 0.26f;
        float bodyBottom = height * 0.74f;
        float bodyRadius = unit * 0.08f;
        RectF body = new RectF(
                centerX - bodyWidth / 2.0f, bodyTop,
                centerX + bodyWidth / 2.0f, bodyBottom);
        fill.setColor(BODY_COLOR);
        canvas.drawRoundRect(body, bodyRadius, bodyRadius, fill);
        stroke.setColor(BODY_STROKE_COLOR);
        stroke.setStrokeWidth(Math.max(1.0f, dp(1.5f)));
        canvas.drawRoundRect(body, bodyRadius, bodyRadius, stroke);

        carPath.reset();
        carPath.moveTo(body.left + bodyWidth * 0.13f, bodyTop + bodyWidth * 0.22f);
        carPath.lineTo(body.right - bodyWidth * 0.13f, bodyTop + bodyWidth * 0.22f);
        carPath.lineTo(body.right - bodyWidth * 0.2f, bodyTop + bodyWidth * 0.41f);
        carPath.lineTo(body.left + bodyWidth * 0.2f, bodyTop + bodyWidth * 0.41f);
        carPath.close();
        fill.setColor(0xFF6C849F);
        canvas.drawPath(carPath, fill);
        stroke.setColor(0xFFB8C9DC);
        canvas.drawPath(carPath, stroke);
        canvas.drawLine(body.left + bodyWidth * 0.16f, bodyTop + bodyWidth * 0.48f,
                body.right - bodyWidth * 0.16f, bodyTop + bodyWidth * 0.48f, stroke);

        float wheelWidth = unit * 0.11f;
        float wheelHeight = unit * 0.18f;
        float wheelInset = unit * 0.015f;
        float leftWheel = centerX - bodyWidth / 2.0f - wheelWidth + wheelInset;
        float rightWheel = centerX + bodyWidth / 2.0f - wheelInset;
        float frontWheelTop = height * 0.31f;
        float rearWheelTop = height * 0.59f;
        drawWheel(canvas, leftWheel, frontWheelTop, wheelWidth, wheelHeight,
                MODE_FRONT, leftVisible, unit);
        drawWheel(canvas, rightWheel, frontWheelTop, wheelWidth, wheelHeight,
                MODE_FRONT, rightVisible, unit);
        drawWheel(canvas, leftWheel, rearWheelTop, wheelWidth, wheelHeight,
                MODE_REAR, leftVisible, unit);
        drawWheel(canvas, rightWheel, rearWheelTop, wheelWidth, wheelHeight,
                MODE_REAR, rightVisible, unit);
    }

    private void drawWheel(
            Canvas canvas, float left, float top, float width, float height,
            int axleMode, boolean sideVisible, float unit) {
        boolean active = axleMode == mode && sideVisible;
        RectF wheel = new RectF(left, top, left + width, top + height);
        fill.setColor(active ? ACTIVE_COLOR : 0x8837475D);
        canvas.drawRoundRect(wheel, width * 0.28f, width * 0.28f, fill);
        stroke.setStrokeWidth(Math.max(1.0f, dp(active ? 2 : 1)));
        stroke.setColor(active ? Color.WHITE : INACTIVE_COLOR);
        canvas.drawRoundRect(wheel, width * 0.28f, width * 0.28f, stroke);
        fill.setColor(active ? 0xFFDAF3FF : 0x556D7D91);
        canvas.drawCircle(wheel.centerX(), wheel.centerY(), Math.max(dp(2), unit * 0.018f), fill);
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }

    private static boolean contains(float[] rect, float x, float y) {
        return x >= rect[0] && x <= rect[2] && y >= rect[1] && y <= rect[3];
    }

    private static int requireMode(int value) {
        if (value != MODE_REAR && value != MODE_FRONT) {
            throw new IllegalArgumentException("unsupported reverse selector mode: " + value);
        }
        return value;
    }
}
