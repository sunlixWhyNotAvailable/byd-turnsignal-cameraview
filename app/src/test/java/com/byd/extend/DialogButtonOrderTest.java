package com.byd.extend;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.view.View;
import android.widget.Button;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = android.app.Application.class)
public class DialogButtonOrderTest {
    @Test public void leftConfirmationAndRightCancellationKeepTheirActions() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        AtomicInteger applied = new AtomicInteger(), cancelled = new AtomicInteger();
        for (int role : new int[]{AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE}) {
            AlertDialog dialog = new AlertDialog.Builder(activity).setMessage("Import settings")
                    .setPositiveButton("Apply", (d, which) -> applied.incrementAndGet())
                    .setNegativeButton("Cancel", (d, which) -> cancelled.incrementAndGet()).create();
            dialog.show();
            DialogButtonOrder.apply(dialog);
            View row = (View) dialog.getButton(AlertDialog.BUTTON_POSITIVE).getParent();
            row.measure(View.MeasureSpec.makeMeasureSpec(700, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(200, View.MeasureSpec.AT_MOST));
            row.layout(0, 0, row.getMeasuredWidth(), row.getMeasuredHeight());
            Button confirm = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            Button cancel = dialog.getButton(AlertDialog.BUTTON_NEGATIVE);
            assertTrue(confirm.getWidth() > 0);
            assertTrue(confirm.getRight() <= cancel.getLeft());
            dialog.getButton(role).performClick();
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            assertFalse(dialog.isShowing());
        }
        assertEquals(1, applied.get());
        assertEquals(1, cancelled.get());
        activity.finish();
    }

    @Test public void dateConfirmationStillReturnsSelectedDate() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        AtomicInteger selected = new AtomicInteger();
        DatePickerDialog dialog = new DatePickerDialog(activity,
                (view, year, month, day) -> selected.set(year * 10000 + (month + 1) * 100 + day),
                2026, 10, 17);
        dialog.show();
        DialogButtonOrder.apply(dialog);
        dialog.updateDate(2026, 10, 18);
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
        assertEquals(20261118, selected.get());
        activity.finish();
    }
}
