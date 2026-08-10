package com.byd.turnsignalguard.capture;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

final class CameraCropOverlayView extends View {
    interface CropMutation {
        DirectCameraCrop apply();
    }

    interface Listener {
        void onCropChanged(DirectCameraCrop crop, boolean finished);
    }

    private final Paint shade = new Paint();
    private final Paint border = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint handle = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint grid = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float hitRadius;
    private final float handleRadius;
    private DirectCameraCrop crop = DirectCameraCrop.defaultFor(false);
    private DirectCameraCrop gestureStart;
    private Listener listener;
    private int dragEdges;
    private boolean moving;
    private boolean rotating;
    private float downX;
    private float downY;
    private boolean gridVisible;

    CameraCropOverlayView(Context context) {
        super(context);
        setFocusable(true);
        shade.setColor(0x77000000);
        border.setColor(Color.rgb(0, 230, 118));
        border.setStyle(Paint.Style.STROKE);
        border.setStrokeWidth(dp(3));
        handle.setColor(Color.rgb(0, 230, 118));
        grid.setColor(0x88FFFFFF);
        grid.setStrokeWidth(dp(1));
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

    void setGridVisible(boolean visible) {
        gridVisible = visible;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (gridVisible) {
            for (int i = 1; i < 8; i++) {
                float x = getWidth() * i / 8.0f;
                canvas.drawLine(x, 0, x, getHeight(), grid);
            }
            for (int i = 1; i < 6; i++) {
                float y = getHeight() * i / 6.0f;
                canvas.drawLine(0, y, getWidth(), y, grid);
            }
        }
        RectF rect = screenRect(crop);
        float[] corners = screenCorners(crop, rect);
        Path cropPath = path(corners);
        canvas.save();
        canvas.clipOutPath(cropPath);
        canvas.drawRect(0, 0, getWidth(), getHeight(), shade);
        canvas.restore();
        canvas.drawPath(cropPath, border);
        if (!isEnabled()) return;
        drawHandles(canvas, corners);
        if (crop.rotationMode == CameraRotation.MODE_ALIGNED) {
            float[] rotationHandle = rotationHandle(corners);
            canvas.drawLine(rotationHandle[0], rotationHandle[1],
                    rotationHandle[2], rotationHandle[3], border);
            canvas.drawCircle(rotationHandle[2], rotationHandle[3],
                    handleRadius * 1.35f, handle);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (!isEnabled()) return false;
        if (getWidth() == 0 || getHeight() == 0) return false;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                RectF rect = screenRect(crop);
                rotating = crop.rotationMode == CameraRotation.MODE_ALIGNED
                        && hitRotationHandle(screenCorners(crop, rect),
                        event.getX(), event.getY());
                float[] local = localPoint(rect, event.getX(), event.getY());
                dragEdges = rotating ? 0 : hitEdges(rect, local[0], local[1]);
                moving = !rotating && dragEdges == 0 && rect.contains(local[0], local[1]);
                if (!rotating && !moving && dragEdges == 0) return false;
                gestureStart = crop;
                downX = event.getX();
                downY = event.getY();
                getParent().requestDisallowInterceptTouchEvent(true);
                return true;
            case MotionEvent.ACTION_MOVE:
                if (gestureStart == null) return false;
                DirectCameraCrop previous = crop;
                crop = keepLastValid(previous, () -> {
                    if (rotating) {
                        RectF startRect = screenRect(gestureStart);
                        int degrees = Math.round((float) Math.toDegrees(Math.atan2(
                                event.getY() - startRect.centerY(),
                                event.getX() - startRect.centerX())) + 90.0f);
                        return gestureStart.withRotation(normalizeDegrees(degrees));
                    }
                    if (moving) {
                        return gestureStart.move(
                                (event.getX() - downX) / getWidth(),
                                (event.getY() - downY) / getHeight());
                    }
                    float dxPixels = event.getX() - downX;
                    float dyPixels = event.getY() - downY;
                    double radians = Math.toRadians(-gestureStart.rotationDegrees);
                    float localDx = (float) (Math.cos(radians) * dxPixels
                            - Math.sin(radians) * dyPixels) / getWidth();
                    float localDy = (float) (Math.sin(radians) * dxPixels
                            + Math.cos(radians) * dyPixels) / getHeight();
                    return gestureStart.resize(dragEdges, localDx, localDy);
                });
                if (crop == previous) return true;
                invalidate();
                notifyChanged(false);
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (gestureStart == null) return false;
                gestureStart = null;
                rotating = false;
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

    static DirectCameraCrop keepLastValid(
            DirectCameraCrop previous, CropMutation mutation) {
        if (previous == null || mutation == null) {
            throw new IllegalArgumentException("crop mutation is required");
        }
        try {
            return mutation.apply();
        } catch (IllegalArgumentException invalidAlignedCrop) {
            return previous;
        }
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

    private float[] screenCorners(DirectCameraCrop value, RectF rect) {
        return value.rotationMode == CameraRotation.MODE_ALIGNED
                ? CameraRotation.rotatedCorners(rect, value.rotationDegrees)
                : CameraRotation.rotatedCorners(rect, 0);
    }

    private Path path(float[] corners) {
        Path path = new Path();
        path.moveTo(corners[0], corners[1]);
        for (int i = 2; i < corners.length; i += 2) {
            path.lineTo(corners[i], corners[i + 1]);
        }
        path.close();
        return path;
    }

    private void drawHandles(Canvas canvas, float[] corners) {
        for (int i = 0; i < corners.length; i += 2) {
            canvas.drawCircle(corners[i], corners[i + 1], handleRadius, handle);
            int next = (i + 2) % corners.length;
            canvas.drawCircle((corners[i] + corners[next]) / 2.0f,
                    (corners[i + 1] + corners[next + 1]) / 2.0f,
                    handleRadius, handle);
        }
    }

    private float[] rotationHandle(float[] corners) {
        float topCenterX = (corners[0] + corners[2]) / 2.0f;
        float topCenterY = (corners[1] + corners[3]) / 2.0f;
        float radians = (float) Math.toRadians(crop.rotationDegrees);
        float distance = dp(34);
        return new float[]{topCenterX, topCenterY,
                topCenterX + (float) Math.sin(radians) * distance,
                topCenterY - (float) Math.cos(radians) * distance};
    }

    private boolean hitRotationHandle(float[] corners, float x, float y) {
        float[] point = rotationHandle(corners);
        return Math.hypot(x - point[2], y - point[3]) <= hitRadius;
    }

    private float[] localPoint(RectF rect, float x, float y) {
        if (crop.rotationMode != CameraRotation.MODE_ALIGNED
                || crop.rotationDegrees == 0) return new float[]{x, y};
        float[] point = new float[]{x, y};
        Matrix rotation = new Matrix();
        rotation.setRotate(-crop.rotationDegrees, rect.centerX(), rect.centerY());
        rotation.mapPoints(point);
        return point;
    }

    private static int normalizeDegrees(int degrees) {
        int normalized = degrees % 360;
        if (normalized > 180) normalized -= 360;
        if (normalized < -180) normalized += 360;
        return normalized;
    }

    private void notifyChanged(boolean finished) {
        if (listener != null) listener.onCropChanged(crop, finished);
    }

    private float dp(int value) {
        return value * getResources().getDisplayMetrics().density;
    }
}
