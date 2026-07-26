package com.byd.turnsignalguard.capture;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

final class CameraCropOverlayView extends View {
    interface Listener {
        void onCropChanged(DirectCameraCrop crop, boolean finished);
    }

    private final Paint shade = new Paint();
    private final Paint border = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint handle = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float hitRadius;
    private final float handleRadius;
    private DirectCameraCrop crop = DirectCameraCrop.defaultFor(false);
    private DirectCameraCrop gestureStart;
    private Listener listener;
    private int dragEdges;
    private boolean moving;
    private float downX;
    private float downY;

    CameraCropOverlayView(Context context) {
        super(context);
        setFocusable(true);
        shade.setColor(0x77000000);
        border.setColor(Color.rgb(0, 230, 118));
        border.setStyle(Paint.Style.STROKE);
        border.setStrokeWidth(dp(3));
        handle.setColor(Color.rgb(0, 230, 118));
        hitRadius = dp(26);
        handleRadius = dp(6);
    }

    void setListener(Listener listener) {
        this.listener = listener;
    }

    void setCrop(DirectCameraCrop crop) {
        this.crop = crop;
        invalidate();
    }

    DirectCameraCrop getCrop() {
        return crop;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        RectF rect = screenRect(crop);
        canvas.drawRect(0, 0, getWidth(), rect.top, shade);
        canvas.drawRect(0, rect.bottom, getWidth(), getHeight(), shade);
        canvas.drawRect(0, rect.top, rect.left, rect.bottom, shade);
        canvas.drawRect(rect.right, rect.top, getWidth(), rect.bottom, shade);
        canvas.drawRect(rect, border);
        canvas.drawCircle(rect.left, rect.top, handleRadius, handle);
        canvas.drawCircle(rect.right, rect.top, handleRadius, handle);
        canvas.drawCircle(rect.left, rect.bottom, handleRadius, handle);
        canvas.drawCircle(rect.right, rect.bottom, handleRadius, handle);
        canvas.drawCircle(rect.centerX(), rect.top, handleRadius, handle);
        canvas.drawCircle(rect.centerX(), rect.bottom, handleRadius, handle);
        canvas.drawCircle(rect.left, rect.centerY(), handleRadius, handle);
        canvas.drawCircle(rect.right, rect.centerY(), handleRadius, handle);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (getWidth() == 0 || getHeight() == 0) return false;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                RectF rect = screenRect(crop);
                dragEdges = hitEdges(rect, event.getX(), event.getY());
                moving = dragEdges == 0 && rect.contains(event.getX(), event.getY());
                if (!moving && dragEdges == 0) return false;
                gestureStart = crop;
                downX = event.getX();
                downY = event.getY();
                getParent().requestDisallowInterceptTouchEvent(true);
                return true;
            case MotionEvent.ACTION_MOVE:
                if (gestureStart == null) return false;
                float dx = (event.getX() - downX) / getWidth();
                float dy = (event.getY() - downY) / getHeight();
                crop = moving ? gestureStart.move(dx, dy)
                        : gestureStart.resize(dragEdges, dx, dy);
                invalidate();
                notifyChanged(false);
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (gestureStart == null) return false;
                gestureStart = null;
                getParent().requestDisallowInterceptTouchEvent(false);
                notifyChanged(true);
                performClick();
                return true;
            default:
                return false;
        }
    }

    @Override
    public boolean performClick() {
        super.performClick();
        return true;
    }

    private int hitEdges(RectF rect, float x, float y) {
        boolean inHorizontalRange = x >= rect.left - hitRadius && x <= rect.right + hitRadius;
        boolean inVerticalRange = y >= rect.top - hitRadius && y <= rect.bottom + hitRadius;
        int edges = 0;
        if (inVerticalRange && Math.abs(x - rect.left) <= hitRadius) {
            edges |= DirectCameraCrop.EDGE_LEFT;
        } else if (inVerticalRange && Math.abs(x - rect.right) <= hitRadius) {
            edges |= DirectCameraCrop.EDGE_RIGHT;
        }
        if (inHorizontalRange && Math.abs(y - rect.top) <= hitRadius) {
            edges |= DirectCameraCrop.EDGE_TOP;
        } else if (inHorizontalRange && Math.abs(y - rect.bottom) <= hitRadius) {
            edges |= DirectCameraCrop.EDGE_BOTTOM;
        }
        return edges;
    }

    private RectF screenRect(DirectCameraCrop value) {
        return new RectF(value.left * getWidth(), value.top * getHeight(),
                value.right() * getWidth(), value.bottom() * getHeight());
    }

    private void notifyChanged(boolean finished) {
        if (listener != null) listener.onCropChanged(crop, finished);
    }

    private float dp(int value) {
        return value * getResources().getDisplayMetrics().density;
    }
}
