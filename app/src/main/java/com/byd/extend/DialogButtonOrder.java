package com.byd.extend;

import android.app.AlertDialog;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;

/** Keep native button handlers/roles while applying the app's confirmation-left layout. */
public final class DialogButtonOrder {
    private DialogButtonOrder() {}

    public static void apply(AlertDialog dialog) {
        Button confirm = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        Button cancel = dialog.getButton(AlertDialog.BUTTON_NEGATIVE);
        if (confirm == null || cancel == null || confirm.getParent() != cancel.getParent()
                || !(confirm.getParent() instanceof ViewGroup)) return;
        ViewGroup parent = (ViewGroup) confirm.getParent();
        int index = Math.min(parent.indexOfChild(confirm), parent.indexOfChild(cancel));
        parent.removeView(confirm);
        parent.removeView(cancel);
        LinearLayout row = new LinearLayout(dialog.getContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setLayoutDirection(android.view.View.LAYOUT_DIRECTION_LTR);
        LinearLayout.LayoutParams left = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        left.rightMargin = Math.round(8 * dialog.getContext().getResources().getDisplayMetrics().density);
        row.addView(confirm, left);
        row.addView(cancel, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        parent.addView(row, index, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }
}
