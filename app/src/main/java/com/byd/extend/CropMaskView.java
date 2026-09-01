package com.byd.extend;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

/** Blackens the destination outside a transformed source-ROI polygon. */
final class CropMaskView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private float[] crop;

    CropMaskView(Context context) {
        super(context);
        paint.setColor(Color.BLACK);
    }

    void setCrop(float[] value) {
        crop = value == null || value.length < 6 ? null : value.clone();
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float[] value = crop;
        if (value == null || getWidth() <= 0 || getHeight() <= 0) return;
        path.reset();
        path.setFillType(Path.FillType.EVEN_ODD);
        path.addRect(0, 0, getWidth(), getHeight(), Path.Direction.CW);
        path.moveTo(value[0], value[1]);
        for (int i = 2; i + 1 < value.length; i += 2) {
            path.lineTo(value[i], value[i + 1]);
        }
        path.close();
        canvas.drawPath(path, paint);
    }
}
