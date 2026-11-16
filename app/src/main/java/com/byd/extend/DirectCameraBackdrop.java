package com.byd.extend;

import android.content.Context;
import android.graphics.Rect;
import android.os.Build;
import android.util.Log;
import android.view.SurfaceControl;
import android.view.View;
import android.view.ViewParent;
import android.view.ViewTreeObserver;
import java.lang.reflect.Method;

/** Mirrors the configured black View below SurfaceView holes; owns no input window. */
final class DirectCameraBackdrop extends View {
    private final Rect clip = new Rect();
    private final int[] location = new int[2];
    private SurfaceControl layer;
    private Method setPosition;
    private Method setCrop;
    private Method setRelativeLayer;
    private boolean failed;
    private final ViewTreeObserver.OnPreDrawListener sync = () -> {
        updateLayer();
        return true;
    };

    DirectCameraBackdrop(Context context) { super(context); }

    private void updateLayer() {
        if (Build.VERSION.SDK_INT < 29 || failed || getWidth() == 0 || getHeight() == 0) return;
        try {
            Object viewRoot = View.class.getMethod("getViewRootImpl").invoke(this);
            if (viewRoot == null) return;
            SurfaceControl parent = (SurfaceControl) viewRoot.getClass()
                    .getMethod("getSurfaceControl").invoke(viewRoot);
            if (parent == null || !parent.isValid()) return;
            if (layer == null) {
                SurfaceControl.Builder builder = new SurfaceControl.Builder()
                        .setName("byd-reverse-backdrop").setParent(parent);
                SurfaceControl.Builder.class.getMethod("setColorLayer").invoke(builder);
                layer = builder.build();
                setPosition = SurfaceControl.Transaction.class.getMethod("setPosition",
                        SurfaceControl.class, float.class, float.class);
                setCrop = SurfaceControl.Transaction.class.getMethod("setWindowCrop",
                        SurfaceControl.class, Rect.class);
                setRelativeLayer = SurfaceControl.Transaction.class.getMethod("setRelativeLayer",
                        SurfaceControl.class, SurfaceControl.class, int.class);
                try (SurfaceControl.Transaction transaction = new SurfaceControl.Transaction()) {
                    SurfaceControl.Transaction.class.getMethod("setColor",
                            SurfaceControl.class, float[].class)
                            .invoke(transaction, layer, new float[]{0f, 0f, 0f});
                    transaction.apply();
                }
            }
            float alpha = 1f;
            for (View view = this; view != null;) {
                alpha *= view.getAlpha();
                if (view.getVisibility() != VISIBLE) alpha = 0f;
                ViewParent ancestor = view.getParent();
                view = ancestor instanceof View ? (View) ancestor : null;
            }
            boolean visible = getLocalVisibleRect(clip) && alpha > 0f;
            getLocationInWindow(location);
            try (SurfaceControl.Transaction transaction = new SurfaceControl.Transaction()) {
                // Follow replacement window surfaces; SurfaceView is at -2 relative to this root.
                transaction.reparent(layer, parent);
                setRelativeLayer.invoke(transaction, layer, parent, Integer.MIN_VALUE);
                setPosition.invoke(transaction, layer, (float) location[0], (float) location[1]);
                if (visible) setCrop.invoke(transaction, layer, clip);
                transaction.setAlpha(layer, alpha).setVisibility(layer, visible).apply();
            }
        } catch (ReflectiveOperationException | RuntimeException error) {
            failed = true;
            releaseLayer();
            Log.e("ReverseCameraView", "native backdrop unavailable", error);
        }
    }

    @android.annotation.TargetApi(29) // A non-null layer can only be created after updateLayer's API29 guard.
    private void releaseLayer() {
        if (layer == null) return;
        try (SurfaceControl.Transaction transaction = new SurfaceControl.Transaction()) {
            transaction.setVisibility(layer, false).reparent(layer, null).apply();
        } catch (RuntimeException error) {
            Log.w("ReverseCameraView", "native backdrop detach failed", error);
        } finally {
            layer.release();
            layer = null;
        }
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        failed = false;
        getViewTreeObserver().addOnPreDrawListener(sync);
    }

    @Override protected void onDetachedFromWindow() {
        getViewTreeObserver().removeOnPreDrawListener(sync);
        releaseLayer();
        super.onDetachedFromWindow();
    }
}
