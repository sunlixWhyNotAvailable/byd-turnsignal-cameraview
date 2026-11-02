package com.byd.extend;

import android.content.Context;
import android.hardware.display.DisplayManager;
import android.os.Bundle;
import android.os.Process;
import android.view.Display;

/** Read-only snapshot taken on the existing helper thread under its shell identity. */
final class UpdateDisplayState {
    static boolean eligible(boolean powerKnown, boolean powerOff, boolean visible) {
        return powerKnown && !powerOff && visible;
    }

    static Bundle read(Context context) {
        Bundle result = new Bundle();
        try {
            if (Process.myUid() != 2000 || android.os.Binder.getCallingUid() != 2000)
                throw new SecurityException("Update display read requires executing shell identity");
            DisplayManager manager = context.getSystemService(DisplayManager.class);
            Display display = manager == null ? null : manager.getDisplay(Display.DEFAULT_DISPLAY);
            if (display == null) throw new IllegalStateException("Primary display unavailable");
            int backlight = -1;
            try { backlight = MotionScreenControl.backlightRaw(); }
            catch (ClassNotFoundException | NoSuchMethodException unsupported) {
                result.putString("capability", "android_display_fallback");
                result.putString("capability_detail", CellularRecovery.diagnosticDetail(
                        unsupported, "operation=optional_oem_backlight_read"));
            }
            String cover = MotionScreenControl.animationStatus();
            result.putInt("display", display.getState());
            result.putInt("backlight", backlight);
            result.putString("cover", cover);
            result.putBoolean("visible", display.getState() == Display.STATE_ON
                    && backlight != 0 && !"1".equals(cover));
            result.putBoolean("known", true);
        } catch (Exception error) {
            result.putString("error", CellularRecovery.diagnosticDetail(error, "operation=update_display_read"));
        }
        return result;
    }
}
