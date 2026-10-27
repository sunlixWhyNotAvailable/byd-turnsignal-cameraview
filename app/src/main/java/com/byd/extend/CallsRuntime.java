package com.byd.extend;

import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Live rescue-button, contact-confirmation and direct HFP call runtime. */
public final class CallsRuntime {
    private static final String TAG = "CallsRuntime";
    private static final String STOCK_CONTACTS_PACKAGE = "com.byd.bluetoothcall";
    private static final String STOCK_CONTACTS_ACTIVITY =
            "com.byd.bluetoothcall.BluetoothCallActivity";
    private static final String XCALL_PACKAGE = "com.byd.xcall";
    private static final String XCALL_CLOSE_HINT = XCALL_PACKAGE + ":id/close_hint";
    private static final String XCALL_APP_BUTTON2 = XCALL_PACKAGE + ":id/button2";
    private static final String XCALL_ANDROID_BUTTON2 = "android:id/button2";
    private static final String XCALL_HANGUP = XCALL_PACKAGE + ":id/dialog_hangup";
    private static final String XCALL_FLOAT_HANGUP = XCALL_PACKAGE + ":id/float_end_call";
    private static final String XCALL_CANCEL = XCALL_PACKAGE + ":id/dialog_cancel";
    private static final String XCALL_REDIAL = XCALL_PACKAGE + ":id/dialog_redial";
    private static final int PROFILE_HEADSET_CLIENT = 16;
    private static final int MAX_ACCESSIBILITY_NODES = 600;
    private static final int MAX_ACCESSIBILITY_DEPTH = 120;
    private static final long RESCUE_EVENT_MAX_AGE_MS = 5_000L;
    private static final long TARGET_WAIT_MS = 2_000L;
    private static final long DISMISS_CONFIRM_TIMEOUT_MS = 3_000L;
    private static final long DISMISS_CONFIRM_INTERVAL_MS = 1_000L;
    private static final long PROFILE_CONNECT_TIMEOUT_MS = 7_000L;
    private static final long HINT_EVENT_WINDOW_MS = 800L;
    private static final String PICKER_POSITION_X = "calls_picker_position_x";
    private static final String PICKER_POSITION_Y = "calls_picker_position_y";

    private static volatile CallsRuntime active;

    private final WeatherRefreshAccessibilityService service;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final RescueButtonGesturePolicy gestures = new RescueButtonGesturePolicy();
    private final Runnable gestureDeadline = this::advanceGestures;
    private final Runnable callTargetDeadline = this::onCallTargetDeadline;
    private final Runnable callConfirmPoll = this::confirmCallDismissal;
    private final Runnable profileConnectDeadline = this::onProfileConnectDeadline;
    private SharedPreferences preferences;
    private boolean closed;
    private boolean helperDemandEnabled;

    private boolean rescueConnected;
    private String rescueSession = "";
    private long lastRescueSequence;
    private long callGeneration;
    private CallOperation callOperation;
    private HintDismissRequest hintDismissRequest;
    private long hintGeneration;

    private boolean selectingContact;
    private CallsSettings.Profile selectionProfile;
    private ContactCard currentCard;
    private boolean contactRootAvailable;
    private View confirmationPanel;
    private TextView confirmationSelection;
    private TextView confirmationStatus;
    private Button confirmationUse;
    private WindowManager windowManager;
    private WindowManager.LayoutParams confirmationParams;
    private AlertDialog numberChoiceDialog;

    private CallsRuntime(WeatherRefreshAccessibilityService owner) {
        service = owner;
        preferences = owner.getSharedPreferences("settings", Context.MODE_PRIVATE);
    }

    static CallsRuntime attachAndGet(WeatherRefreshAccessibilityService owner) {
        if (owner == null) return null;
        CallsRuntime previous = active;
        if (previous != null && previous.service != owner) previous.closeForLifecycle("replaced");
        CallsRuntime next = new CallsRuntime(owner);
        active = next;
        next.refreshHelperDemand();
        return next;
    }

    static void detach(WeatherRefreshAccessibilityService owner) {
        CallsRuntime current = active;
        if (current == null || current.service != owner) return;
        active = null;
        current.closeForLifecycle("service_disconnected");
    }

    public static boolean beginContactSelection(Context context, CallsSettings.Profile profile) {
        CallsRuntime current = active;
        if (current == null || current.closed || profile == null || current.isBlocked()) return false;
        if (Looper.myLooper() == Looper.getMainLooper()) return current.beginContactSelectionOnMain(profile);
        return current.handler.post(() -> current.beginContactSelectionOnMain(profile));
    }

    public static void acceptRescueEvent(String line) {
        CallsRuntime current = active;
        if (current == null || line == null) return;
        current.handler.post(() -> current.acceptRescueEventOnMain(line));
    }

    public static boolean requiresRescueSubscription(Context context) {
        if (context == null || GuardRecovery.isUserShutdownActive(context)
                || LegacySettingsImporter.blocksRuntime(context)) return false;
        SharedPreferences prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE);
        if (configured(CallsSettings.read(prefs, CallsSettings.Profile.Sos))
                || configured(CallsSettings.read(prefs, CallsSettings.Profile.Help))) return true;
        return hasActiveRescueBinding(prefs, CameraButtonBindings.Source.Sos)
                || hasActiveRescueBinding(prefs, CameraButtonBindings.Source.Help);
    }

    public static boolean requiresBluetoothConnect(Context context) {
        if (context == null) return false;
        SharedPreferences prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE);
        return configured(CallsSettings.read(prefs, CallsSettings.Profile.Sos))
                || configured(CallsSettings.read(prefs, CallsSettings.Profile.Help));
    }

    void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || closed) return;
        if (isBlocked()) {
            stopLiveWork("runtime_blocked", false);
            return;
        }
        if (selectingContact) refreshContactCard();
        if (callOperation != null && callOperation.stage == CallStage.WAITING_FOR_TARGET
                && isXCallTargetEvent(event.getEventType(), event.getPackageName()))
            tryFindCallTarget(callOperation);
        if (hintDismissRequest != null && hintDismissRequest.dismissAllowed
                && isXCallTargetEvent(event.getEventType(), event.getPackageName()))
            tryDismissAssignedHint(hintDismissRequest);
    }

    void onPreferencesChanged(SharedPreferences prefs, String key) {
        if (!isRuntimePreference(key)) return;
        handler.post(() -> {
            if (closed) return;
            preferences = prefs;
            if (selectingContact && selectionProfile != null
                    && !CallsSettings.read(preferences, selectionProfile).enabled) {
                cancelContactSelection();
            }
            gestures.reset();
            handler.removeCallbacks(gestureDeadline);
            cancelHintDismiss("assignment_changed");
            cancelPendingCall("settings_changed", false);
            if (isBlocked()) {
                stopLiveWork("runtime_blocked", false);
                return;
            }
            refreshHelperDemand();
        });
    }

    void onInterrupt() {
        gestures.reset();
        handler.removeCallbacks(gestureDeadline);
        cancelHintDismiss("accessibility_interrupted");
        cancelPendingCall("accessibility_interrupted", false);
        cancelContactSelection();
    }

    private boolean beginContactSelectionOnMain(CallsSettings.Profile profile) {
        if (closed || active != this || isBlocked()
                || !CallsSettings.read(preferences, profile).enabled) return false;
        Intent intent = new Intent("android.intent.action.BLUETOOTH_CONTACTS")
                .setComponent(new ComponentName(STOCK_CONTACTS_PACKAGE, STOCK_CONTACTS_ACTIVITY))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        cancelContactSelection();
        selectingContact = true;
        selectionProfile = profile;
        currentCard = null;
        contactRootAvailable = false;
        try {
            showConfirmationPanel();
            service.startActivity(intent);
            refreshContactCard();
            logStage("contact_selection_started", "profile=" + profile.name());
            return true;
        } catch (RuntimeException failure) {
            cancelContactSelection();
            logStage("contact_selection_open_failed", failure.getClass().getSimpleName());
            return false;
        }
    }

    private void refreshContactCard() {
        if (!selectingContact || isBlocked()) return;
        ContactRead read = readCurrentContact();
        contactRootAvailable = read.rootAvailable && !read.incomplete;
        currentCard = contactRootAvailable ? read.card : null;
        updateConfirmationPanel();
    }

    private void readAndConfirmContact() {
        if (!selectingContact || selectionProfile == null || isBlocked()) return;
        ContactRead freshRead = readCurrentContact();
        ContactCard fresh = freshRead.rootAvailable && !freshRead.incomplete
                ? freshRead.card : null;
        if (!contactRootAvailable || !sameCard(currentCard, fresh)) {
            contactRootAvailable = freshRead.rootAvailable && !freshRead.incomplete;
            currentCard = fresh;
            updateConfirmationPanel();
            setConfirmationStatus(contactRootAvailable
                    ? R.string.calls_picker_changed : R.string.calls_picker_root_lost);
            return;
        }
        if (fresh == null) {
            setConfirmationStatus(R.string.calls_picker_no_contact);
            updateConfirmationPanel();
            return;
        }
        if (fresh.numbers.isEmpty()) {
            updateConfirmationPanel();
            setConfirmationStatus(R.string.calls_picker_no_phone_numbers);
            return;
        }
        if (fresh.numbers.size() == 1) {
            saveContactSelection(fresh, fresh.numbers.get(0));
            return;
        }
        showNumberChoiceDialog(fresh);
    }

    private void showNumberChoiceDialog(ContactCard snapshot) {
        if (numberChoiceDialog != null) numberChoiceDialog.dismiss();
        String[] choices = snapshot.numbers.toArray(new String[0]);
        try {
            Context localized = AppLanguage.localizedContext(
                    service, AppLanguage.read(preferences));
            numberChoiceDialog = new AlertDialog.Builder(localized)
                    .setTitle(R.string.calls_picker_number_choice_title)
                    .setItems(choices, (dialog, which) -> {
                        numberChoiceDialog = null;
                        if (which < 0 || which >= snapshot.numbers.size()) return;
                        ContactRead read = readCurrentContact();
                        ContactCard fresh = read.rootAvailable && !read.incomplete ? read.card : null;
                        if (!sameCard(snapshot, fresh)) {
                            currentCard = fresh;
                            contactRootAvailable = read.rootAvailable && !read.incomplete;
                            updateConfirmationPanel();
                            setConfirmationStatus(contactRootAvailable
                                    ? R.string.calls_picker_changed : R.string.calls_picker_root_lost);
                            return;
                        }
                        saveContactSelection(fresh, fresh.numbers.get(which));
                    })
                    .setNegativeButton(R.string.calls_picker_number_choice_cancel, null)
                    .create();
            numberChoiceDialog.setOnDismissListener(dialog -> {
                if (numberChoiceDialog == dialog) numberChoiceDialog = null;
                updateConfirmationPanel();
            });
            if (confirmationUse != null) confirmationUse.setEnabled(false);
            if (numberChoiceDialog.getWindow() == null)
                throw new IllegalStateException("number choice window unavailable");
            if (confirmationParams == null || confirmationParams.token == null)
                throw new IllegalStateException("accessibility window token unavailable");
            WindowManager.LayoutParams attributes = numberChoiceDialog.getWindow().getAttributes();
            attributes.token = confirmationParams.token;
            numberChoiceDialog.getWindow().setAttributes(attributes);
            numberChoiceDialog.getWindow().setType(
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY);
            numberChoiceDialog.show();
        } catch (RuntimeException failure) {
            numberChoiceDialog = null;
            updateConfirmationPanel();
            setConfirmationStatus(R.string.calls_picker_number_choice_failed);
            logStage("number_choice_unavailable", failure.getClass().getSimpleName());
        }
    }

    private void saveContactSelection(ContactCard card, String number) {
        if (card == null || selectionProfile == null || isBlocked()) return;
        if (!CallsSettings.read(preferences, selectionProfile).enabled) {
            cancelContactSelection();
            return;
        }
        String normalized = CallsSettings.normalizeNumber(number);
        if (normalized == null || !CallsSettings.saveDestination(
                preferences, selectionProfile, card.name, normalized)) {
            setConfirmationStatus(R.string.calls_save_failed);
            showLongToast(R.string.calls_save_failed);
            return;
        }
        logStage("contact_selection_saved", "number_count=" + card.numbers.size());
        cancelContactSelection();
        returnToCameraProbeActivity("contact_selection_saved");
    }

    private ContactRead readCurrentContact() {
        ContactRead result = new ContactRead();
        if (!selectingContact || selectionProfile == null
                || !CallsSettings.read(preferences, selectionProfile).enabled) return result;
        List<AccessibilityWindowInfo> windows = null;
        try {
            windows = service.getWindows();
            int cardsSeen = 0;
            ContactCard selected = null;
            boolean rootAvailable = false;
            boolean incomplete = false;
            for (AccessibilityWindowInfo window : windows) {
                if (window == null) continue;
                int type = window.getType();
                if (type != AccessibilityWindowInfo.TYPE_APPLICATION
                        && type != AccessibilityWindowInfo.TYPE_SYSTEM) continue;
                AccessibilityNodeInfo root = null;
                try {
                    root = window.getRoot();
                    if (root == null) {
                        if (window.isFocused()) incomplete = true;
                        continue;
                    }
                    if (!STOCK_CONTACTS_PACKAGE.contentEquals(packageName(root))) continue;
                    rootAvailable = true;
                    List<AccessibilityNodeInfo> cards = root.findAccessibilityNodeInfosByViewId(
                            STOCK_CONTACTS_PACKAGE + ":id/contacts_detail_dialog");
                    try {
                        for (AccessibilityNodeInfo node : cards) {
                            if (node == null || !node.isVisibleToUser()) continue;
                            cardsSeen++;
                            if (cardsSeen > 1) continue;
                            selected = readContactCard(node);
                        }
                    } finally {
                        recycleNodes(cards);
                    }
                } catch (RuntimeException readFailure) {
                    incomplete = true;
                } finally {
                    if (root != null) root.recycle();
                }
            }
            result.rootAvailable = rootAvailable;
            result.incomplete = incomplete;
            result.card = !incomplete && cardsSeen == 1 ? selected : null;
        } catch (RuntimeException failure) {
            result.incomplete = true;
        } finally {
            if (windows != null) recycleWindows(windows);
        }
        return result;
    }

    private ContactCard readContactCard(AccessibilityNodeInfo card) {
        return ContactCard.fromFields(
                visibleTexts(card, "contacts_detail_name"),
                visibleTexts(card, "contacts_detail_number"));
    }

    private List<String> visibleTexts(AccessibilityNodeInfo root, String id) {
        List<String> values = new ArrayList<>();
        List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByViewId(
                STOCK_CONTACTS_PACKAGE + ":id/" + id);
        try {
            for (AccessibilityNodeInfo node : nodes) {
                if (node == null || !node.isVisibleToUser()) continue;
                CharSequence text = node.getText();
                if (text != null && text.length() > 0) values.add(text.toString());
            }
        } finally {
            recycleNodes(nodes);
        }
        return values;
    }

    private void showConfirmationPanel() {
        if (confirmationPanel != null) return;
        windowManager = (WindowManager) service.getSystemService(Context.WINDOW_SERVICE);
        if (windowManager == null) throw new IllegalStateException("window manager unavailable");
        Context localized = AppLanguage.localizedContext(
                service, AppLanguage.read(preferences));
        View panel = LayoutInflater.from(localized).inflate(
                R.layout.calls_contact_confirmation, null, false);
        confirmationPanel = panel;
        confirmationSelection = panel.findViewById(R.id.calls_picker_selection);
        confirmationStatus = panel.findViewById(R.id.calls_picker_status);
        confirmationUse = panel.findViewById(R.id.calls_picker_use);
        Button cancel = panel.findViewById(R.id.calls_picker_cancel);
        confirmationUse.setOnClickListener(view -> readAndConfirmContact());
        cancel.setOnClickListener(view -> {
            cancelContactSelection();
            returnToCameraProbeActivity("contact_selection_cancelled");
        });
        installPanelDrag(panel);

        int width = Math.max(1, Math.min(dp(340), service.getResources()
                .getDisplayMetrics().widthPixels - dp(24)));
        panel.setLayoutParams(new ViewGroup.LayoutParams(width,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        confirmationParams = new WindowManager.LayoutParams(
                width, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT);
        confirmationParams.gravity = Gravity.BOTTOM | Gravity.RIGHT;
        confirmationParams.x = dp(preferences.getInt(PICKER_POSITION_X, 16));
        confirmationParams.y = dp(preferences.getInt(PICKER_POSITION_Y, 16));
        clampConfirmationPosition();
        windowManager.addView(confirmationPanel, confirmationParams);
        confirmationPanel.post(this::clampConfirmationPosition);
        updateConfirmationPanel();
    }

    private void installPanelDrag(View panel) {
        final float[] down = new float[2];
        final int[] start = new int[2];
        // Non-clickable labels bubble touches to the panel; the two buttons keep their clicks.
        panel.setOnTouchListener((view, event) -> {
            if (confirmationParams == null || confirmationPanel == null) return false;
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    down[0] = event.getRawX();
                    down[1] = event.getRawY();
                    start[0] = confirmationParams.x;
                    start[1] = confirmationParams.y;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    confirmationParams.x = Math.round(start[0] - (event.getRawX() - down[0]));
                    confirmationParams.y = Math.round(start[1] - (event.getRawY() - down[1]));
                    clampConfirmationPosition();
                    updateConfirmationWindow();
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    saveConfirmationPosition();
                    return true;
                default:
                    return true;
            }
        });
    }

    private void clampConfirmationPosition() {
        if (confirmationParams == null || confirmationPanel == null) return;
        int screenWidth = service.getResources().getDisplayMetrics().widthPixels;
        int screenHeight = service.getResources().getDisplayMetrics().heightPixels;
        int panelWidth = confirmationPanel.getWidth();
        int panelHeight = confirmationPanel.getHeight();
        if (panelWidth <= 0 || panelHeight <= 0) {
            confirmationPanel.measure(
                    View.MeasureSpec.makeMeasureSpec(screenWidth, View.MeasureSpec.AT_MOST),
                    View.MeasureSpec.makeMeasureSpec(screenHeight, View.MeasureSpec.AT_MOST));
            panelWidth = confirmationPanel.getMeasuredWidth();
            panelHeight = confirmationPanel.getMeasuredHeight();
        }
        confirmationParams.x = clamp(confirmationParams.x, 0, Math.max(0, screenWidth - panelWidth));
        confirmationParams.y = clamp(confirmationParams.y, 0, Math.max(0, screenHeight - panelHeight));
    }

    private void updateConfirmationWindow() {
        if (windowManager == null || confirmationPanel == null || confirmationParams == null) return;
        try {
            windowManager.updateViewLayout(confirmationPanel, confirmationParams);
        } catch (RuntimeException ignored) { }
    }

    private void saveConfirmationPosition() {
        if (confirmationParams != null) {
            preferences.edit().putInt(PICKER_POSITION_X,
                    Math.round(confirmationParams.x / service.getResources().getDisplayMetrics().density))
                    .putInt(PICKER_POSITION_Y,
                            Math.round(confirmationParams.y / service.getResources().getDisplayMetrics().density))
                    .apply();
        }
    }

    private void updateConfirmationPanel() {
        if (confirmationPanel == null || confirmationSelection == null || confirmationUse == null)
            return;
        if (currentCard == null) {
            confirmationSelection.setText("");
            confirmationUse.setEnabled(false);
            setConfirmationStatus(contactRootAvailable
                    ? R.string.calls_picker_no_contact : R.string.calls_picker_root_lost);
            return;
        }
        if (currentCard.numbers.isEmpty()) {
            confirmationSelection.setText(currentCard.name);
            confirmationUse.setEnabled(false);
            setConfirmationStatus(R.string.calls_picker_no_phone_numbers);
            return;
        }
        String numberSummary;
        if (currentCard.numbers.size() == 1) {
            numberSummary = currentCard.numbers.get(0);
        } else {
            numberSummary = confirmationSelection.getContext().getResources().getQuantityString(
                    R.plurals.calls_picker_number_count, currentCard.numbers.size(),
                    currentCard.numbers.size(), currentCard.name);
        }
        confirmationSelection.setText(confirmationSelection.getContext().getString(
                R.string.calls_picker_selection, currentCard.name, numberSummary));
        confirmationUse.setEnabled(contactRootAvailable && !isBlocked()
                && numberChoiceDialog == null);
        setConfirmationStatus(0);
    }

    private void setConfirmationStatus(int resourceId) {
        if (confirmationStatus == null) return;
        if (resourceId == 0) {
            confirmationStatus.setText("");
            confirmationStatus.setVisibility(View.GONE);
        } else {
            confirmationStatus.setText(resourceId);
            confirmationStatus.setVisibility(View.VISIBLE);
        }
    }

    private void cancelContactSelection() {
        selectingContact = false;
        currentCard = null;
        selectionProfile = null;
        if (numberChoiceDialog != null) {
            try { numberChoiceDialog.dismiss(); } catch (RuntimeException ignored) { }
            numberChoiceDialog = null;
        }
        if (windowManager != null && confirmationPanel != null) {
            try { windowManager.removeView(confirmationPanel); }
            catch (RuntimeException ignored) { }
        }
        confirmationPanel = null;
        confirmationSelection = null;
        confirmationStatus = null;
        confirmationUse = null;
        confirmationParams = null;
        windowManager = null;
    }

    private void returnToCameraProbeActivity(String outcome) {
        try {
            Intent intent = new Intent(service, CameraProbeActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                            | Intent.FLAG_ACTIVITY_CLEAR_TOP
                            | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            service.startActivity(intent);
            logStage("contact_selection_returned", outcome);
        } catch (RuntimeException failure) {
            logStage("contact_selection_return_failed", failure.getClass().getSimpleName());
        }
    }

    private void acceptRescueEventOnMain(String line) {
        if (closed) return;
        if (isBlocked()) {
            stopLiveWork("runtime_blocked", false);
            return;
        }
        JSONObject event;
        try {
            event = new JSONObject(line);
        } catch (Exception malformed) {
            return;
        }
        String kind = event.optString("kind", "");
        if ("rescue_transport".equals(kind)) {
            acceptRescueTransport(event);
        } else if ("rescue_button".equals(kind)) {
            acceptRescueButton(event);
        } else if ("helper_death".equals(kind) || "helper_ping_failed".equals(kind)) {
            invalidateRescueTransport(kind);
        }
    }

    private void acceptRescueTransport(JSONObject event) {
        String session = event.optString("session_id", "");
        boolean connected = event.optBoolean("connected", false);
        if (connected) {
            if (session.isEmpty()) return;
            if (!session.equals(rescueSession) || !rescueConnected) {
                gestures.reset();
                handler.removeCallbacks(gestureDeadline);
                cancelHintDismiss("transport_session_changed");
                cancelPendingCall("transport_session_changed", false);
                rescueSession = session;
                lastRescueSequence = 0;
                rescueConnected = true;
            }
            return;
        }
        if (session.isEmpty()) {
            invalidateRescueTransport("transport_disconnected_without_session");
            return;
        }
        if (!session.equals(rescueSession)) return;
        invalidateRescueTransport("transport_disconnected");
    }

    private void invalidateRescueTransport(String reason) {
        rescueConnected = false;
        rescueSession = "";
        lastRescueSequence = 0;
        gestures.reset();
        handler.removeCallbacks(gestureDeadline);
        cancelHintDismiss(reason);
        cancelPendingCall(reason, false);
    }

    private void acceptRescueButton(JSONObject event) {
        if (isBlocked()) {
            stopLiveWork("runtime_blocked", false);
            return;
        }
        String session = event.optString("session_id", "");
        int value = event.optInt("value", Integer.MIN_VALUE);
        long receivedMs = event.optLong("received_ms", -1L);
        long sequence = event.optLong("sequence", -1L);
        long now = SystemClock.elapsedRealtime();
        if (!rescueConnected || !rescueSession.equals(session) || sequence <= lastRescueSequence
                || receivedMs <= 0 || receivedMs > now
                || now - receivedMs > RESCUE_EVENT_MAX_AGE_MS) return;
        lastRescueSequence = sequence;
        if (callOperation != null)
            cancelPendingCall("rescue_event_context_changed", false);
        List<RescueButtonGesturePolicy.Assignment> assignments = currentRescueAssignments(preferences);
        CameraButtonBindings.Source source = sourceForValue(value);
        if (source == null) {
            cancelHintDismiss("ambiguous_rescue_value");
            cancelPendingCall("ambiguous_rescue_value", false);
            gestures.onEvent(value, receivedMs, assignments);
            syncGestureDeadline();
            return;
        }
        if ((value == 3 || value == 4) && hasSourceAssignment(assignments, source)) {
            boolean hazardAssignment = hasActionAssignment(assignments, source,
                    CameraButtonBindings.Action.HazardThanks);
            hintDismissRequest = new HintDismissRequest(++hintGeneration, source,
                    rescueSession, sequence, now + HINT_EVENT_WINDOW_MS, !hazardAssignment);
            if (!hazardAssignment) tryDismissAssignedHint(hintDismissRequest);
        } else if (value == 1 || value == 2) {
            cancelHintDismiss("hold_received");
        }

        RescueButtonGesturePolicy.Result result = gestures.onEvent(value, receivedMs, assignments);
        handleGestureResult(result);
        syncGestureDeadline();
    }

    private void advanceGestures() {
        if (isBlocked() || !rescueConnected) {
            gestures.reset();
            return;
        }
        RescueButtonGesturePolicy.Result result = gestures.advance(
                SystemClock.elapsedRealtime(), currentRescueAssignments(preferences));
        handleGestureResult(result);
        if (result != null && result.press != null && result.action == null
                && hintDismissRequest != null
                && !hintDismissRequest.dismissAllowed
                && hintDismissRequest.source == result.source) {
            cancelHintDismiss("unassigned_gesture");
        }
        syncGestureDeadline();
    }

    private void syncGestureDeadline() {
        handler.removeCallbacks(gestureDeadline);
        long deadline = gestures.nextDeadline();
        if (deadline != Long.MAX_VALUE)
            handler.postDelayed(gestureDeadline,
                    Math.max(0L, deadline - SystemClock.elapsedRealtime()));
    }

    private void handleGestureResult(RescueButtonGesturePolicy.Result result) {
        if (result == null || result.source == null) return;
        if (result.hold) {
            CallsSettings.Profile profile = profileForSource(result.source);
            if (profile != null) beginCallReplacement(profile, result.source);
            return;
        }
        if (result.action == null || result.press == null) return;
        if (result.action == CameraButtonBindings.Action.HazardThanks) {
            service.dispatchRescueButtonAction(result.action, result.source, result.press);
            allowHintDismissAfterGesture(result.source, true);
        } else {
            allowHintDismissAfterGesture(result.source, false);
            service.dispatchRescueButtonAction(result.action, result.source, result.press);
        }
    }

    private void allowHintDismissAfterGesture(CameraButtonBindings.Source source,
            boolean retryAfterAcceptedDismiss) {
        HintDismissRequest request = hintDismissRequest;
        if (request == null || request.source != source || request.dismissAllowed) return;
        request.dismissAllowed = true;
        request.retryAfterAcceptedDismiss = retryAfterAcceptedDismiss;
        request.expiresAt = SystemClock.elapsedRealtime() + HINT_EVENT_WINDOW_MS;
        tryDismissAssignedHint(request);
    }

    private void beginCallReplacement(CallsSettings.Profile profile,
            CameraButtonBindings.Source source) {
        if (callOperation != null || isBlocked()) return;
        CallsSettings.Destination destination = CallsSettings.read(preferences, profile);
        if (!configured(destination)) return;
        long startedAt = SystemClock.elapsedRealtime();
        String session = rescueSession;
        long sequence = lastRescueSequence;
        logStage("manual_hold_received", "profile=" + profile.name()
                + " value=" + (source == CameraButtonBindings.Source.Sos ? 1 : 2)
                + " sequence=" + sequence);

        if (!AppPermissionProvisioner.hasBluetoothCallAccess(service)) {
            logStage("call_replacement_declined", "reason=bluetooth_connect_permission_missing");
            showLongToast(R.string.calls_picker_dial_failed);
            return;
        }

        XCallScan initialScan = null;
        try {
            initialScan = scanXCallTargets();
            if (initialScan.incomplete || initialScan.hasCallStageControl) {
                logStage("call_replacement_declined", "reason="
                        + (initialScan.incomplete
                                ? "xcall_initial_scan_incomplete"
                                : "xcall_call_stage_already_present"));
                showLongToast(R.string.calls_picker_oem_cancel_failed);
                return;
            }
        } catch (RuntimeException failure) {
            logStage("call_replacement_declined", "reason=xcall_initial_scan_error_"
                    + failure.getClass().getSimpleName());
            showLongToast(R.string.calls_picker_oem_cancel_failed);
            return;
        } finally {
            if (initialScan != null) initialScan.recycleTargets();
        }

        if (targetSearchExpired(startedAt, SystemClock.elapsedRealtime())) {
            logStage("call_replacement_declined", "reason=xcall_initial_scan_deadline");
            showLongToast(R.string.calls_picker_oem_cancel_failed);
            return;
        }

        CallOperation operation = new CallOperation(++callGeneration, profile,
                destination.number, session, sequence, startedAt);
        operation.stage = CallStage.WAITING_FOR_TARGET;
        callOperation = operation;
        if (!mayContinue(operation)) return;
        handler.postDelayed(callTargetDeadline,
                Math.max(0L, operation.startedAt + TARGET_WAIT_MS
                        - SystemClock.elapsedRealtime()));
        tryFindCallTarget(operation);
    }

    private void tryFindCallTarget(CallOperation operation) {
        if (operation == null || callOperation != operation
                || operation.stage != CallStage.WAITING_FOR_TARGET) return;
        if (targetSearchExpired(operation.startedAt, SystemClock.elapsedRealtime())) {
            expireCallTargetSearch(operation);
            return;
        }
        if (!mayContinue(operation)) return;
        XCallScan scan = scanXCallTargets();
        try {
            if (callOperation != operation || operation.stage != CallStage.WAITING_FOR_TARGET)
                return;
            operation.targetTreeIncomplete = scan.incomplete;
            if (targetSearchExpired(operation.startedAt, SystemClock.elapsedRealtime())) {
                expireCallTargetSearch(operation);
                return;
            }
            if (scan.incomplete) {
                return;
            }
            List<TargetNode> candidates = targetsForCall(scan);
            if (candidates.isEmpty()) return;
            if (candidates.size() != 1) {
                failCall(operation, R.string.calls_picker_oem_cancel_failed,
                        "xcall_target_ambiguous");
                return;
            }
            TargetNode target = candidates.get(0);
            if (!target.node.refresh() || !target.node.isVisibleToUser()
                    || !target.node.isEnabled()
                    || !XCALL_PACKAGE.equals(packageName(target.node))
                    || !isCallTarget(target.id)) {
                failCall(operation, R.string.calls_picker_oem_cancel_failed,
                        "xcall_target_changed");
                return;
            }
            if (!mayContinue(operation)) return;
            if (!AppPermissionProvisioner.hasBluetoothCallAccess(service)) {
                failCall(operation, R.string.calls_picker_dial_failed,
                        "bluetooth_connect_permission_missing");
                return;
            }
            if (targetSearchExpired(operation.startedAt, SystemClock.elapsedRealtime())) {
                expireCallTargetSearch(operation);
                return;
            }
            boolean clicked = target.node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            if (!clicked) {
                logStage("oem_cancel_action", "target=" + simpleId(target.id)
                        + " accepted=false");
                failCall(operation, R.string.calls_picker_oem_cancel_failed,
                        "xcall_action_rejected");
                return;
            }
            operation.stage = CallStage.CHECKING_DISMISSAL;
            operation.clickedAt = SystemClock.elapsedRealtime();
            operation.lastConfirmSample = 0;
            operation.absentSamples = 0;
            logStage("oem_cancel_action", "target=" + simpleId(target.id)
                    + " accepted=true");
            handler.removeCallbacks(callTargetDeadline);
            handler.postDelayed(callConfirmPoll, 500L);
        } catch (RuntimeException failure) {
            failCall(operation, R.string.calls_picker_oem_cancel_failed,
                    "xcall_target_error_" + failure.getClass().getSimpleName());
        } finally {
            scan.recycleTargets();
        }
    }

    private void onCallTargetDeadline() {
        CallOperation operation = callOperation;
        if (operation == null || operation.stage != CallStage.WAITING_FOR_TARGET) return;
        long now = SystemClock.elapsedRealtime();
        long deadline = operation.startedAt + TARGET_WAIT_MS;
        if (now < deadline) {
            handler.postDelayed(callTargetDeadline, deadline - now);
            return;
        }
        expireCallTargetSearch(operation);
    }

    private void expireCallTargetSearch(CallOperation operation) {
        failCall(operation, R.string.calls_picker_oem_cancel_failed,
                operation.targetTreeIncomplete
                        ? "xcall_target_search_deadline_tree_incomplete"
                        : "xcall_target_search_deadline_no_target");
    }

    private void confirmCallDismissal() {
        CallOperation operation = callOperation;
        if (operation == null || operation.stage != CallStage.CHECKING_DISMISSAL) return;
        if (!mayContinue(operation)) return;
        long now = SystemClock.elapsedRealtime();
        XCallWindowState state = scanXCallWindowState();
        if (!state.complete) {
            operation.absentSamples = 0;
        } else if (state.xcallWindows == 0) {
            if (operation.lastConfirmSample == 0
                    || now - operation.lastConfirmSample >= DISMISS_CONFIRM_INTERVAL_MS) {
                operation.absentSamples++;
                operation.lastConfirmSample = now;
            }
        } else {
            operation.absentSamples = 0;
            operation.lastConfirmSample = 0;
        }
        logStage("oem_cancel_confirmation", "xcall_windows=" + state.xcallWindows
                + " complete=" + state.complete + " absent_samples=" + operation.absentSamples);
        if (operation.absentSamples >= 2) {
            operation.stage = CallStage.CONNECTING_HFP;
            requestHfpProfile(operation);
            return;
        }
        if (now - operation.clickedAt >= DISMISS_CONFIRM_TIMEOUT_MS) {
            failCall(operation, R.string.calls_picker_oem_cancel_failed,
                    "xcall_dismissal_unconfirmed");
            return;
        }
        handler.postDelayed(callConfirmPoll, DISMISS_CONFIRM_INTERVAL_MS);
    }

    private void requestHfpProfile(CallOperation operation) {
        if (!mayContinue(operation)) return;
        if (!AppPermissionProvisioner.hasBluetoothCallAccess(service)) {
            failCall(operation, R.string.calls_picker_dial_failed,
                    "bluetooth_connect_permission_missing");
            return;
        }
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null) {
            failCall(operation, R.string.calls_picker_dial_failed, "bluetooth_adapter_missing");
            return;
        }
        operation.adapter = adapter;
        boolean requested;
        try {
            requested = adapter.getProfileProxy(service, new BluetoothProfile.ServiceListener() {
                @Override public void onServiceConnected(int profile, BluetoothProfile proxy) {
                    handler.post(() -> onHfpProfileConnected(operation, profile, proxy));
                }
                @Override public void onServiceDisconnected(int profile) {
                    handler.post(() -> {
                        if (callOperation == operation
                                && operation.stage == CallStage.CONNECTING_HFP)
                            failCall(operation, R.string.calls_picker_dial_failed,
                                    "hfp_profile_disconnected");
                    });
                }
            }, PROFILE_HEADSET_CLIENT);
        } catch (RuntimeException failure) {
            failCall(operation, R.string.calls_picker_dial_failed,
                    "hfp_profile_request_" + failure.getClass().getSimpleName());
            return;
        }
        if (!requested) {
            failCall(operation, R.string.calls_picker_dial_failed, "hfp_profile_request_rejected");
            return;
        }
        handler.postDelayed(profileConnectDeadline, PROFILE_CONNECT_TIMEOUT_MS);
    }

    private void onHfpProfileConnected(CallOperation operation, int profile,
            BluetoothProfile proxy) {
        if (callOperation != operation || operation.stage != CallStage.CONNECTING_HFP
                || !mayContinue(operation)) {
            closeProfile(operation, proxy);
            return;
        }
        handler.removeCallbacks(profileConnectDeadline);
        if (profile != PROFILE_HEADSET_CLIENT || proxy == null) {
            closeProfile(operation, proxy);
            failCall(operation, R.string.calls_picker_dial_failed, "hfp_client_profile_unavailable");
            return;
        }
        operation.proxy = proxy;
        try {
            List<BluetoothDevice> devices = proxy.getConnectedDevices();
            if (devices == null || devices.size() != 1) {
                failCall(operation, R.string.calls_picker_dial_failed,
                        devices == null || devices.isEmpty()
                                ? "hfp_phone_not_connected" : "hfp_phone_ambiguous");
                return;
            }
            BluetoothDevice device = devices.get(0);
            if (device == null) {
                failCall(operation, R.string.calls_picker_dial_failed, "hfp_device_missing");
                return;
            }
            if (!hasNoActiveHfpCalls(proxy, device)) {
                failCall(operation, R.string.calls_picker_dial_failed,
                        "hfp_call_already_active_or_state_unknown");
                return;
            }
            if (!mayContinue(operation)) return;
            Method dial = proxy.getClass().getMethod("dial", BluetoothDevice.class, String.class);
            operation.dialInvoked = true;
            operation.stage = CallStage.DIAL_INVOKED;
            Object call = dial.invoke(proxy, device, operation.number);
            if (!dialResultAccepted(call)) {
                failCall(operation, R.string.calls_picker_dial_failed, "hfp_dial_rejected");
                return;
            }
            logStage("hfp_dial_dispatched", "profile=" + operation.profile.name());
            finishCall(operation);
        } catch (SecurityException denied) {
            failCall(operation, R.string.calls_picker_dial_failed, "hfp_permission_denied");
        } catch (ReflectiveOperationException | RuntimeException failure) {
            failCall(operation, R.string.calls_picker_dial_failed,
                    "hfp_dial_error_" + failure.getClass().getSimpleName());
        }
    }

    static boolean dialResultAccepted(Object result) {
        // BYD returns a boolean; the standard HFP API returns a call object.
        return result instanceof Boolean ? (Boolean) result : result != null;
    }

    private boolean hasNoActiveHfpCalls(BluetoothProfile proxy, BluetoothDevice device) {
        try {
            Method currentCalls = proxy.getClass().getMethod("getCurrentCalls", BluetoothDevice.class);
            Object result = currentCalls.invoke(proxy, device);
            return result instanceof List && ((List<?>) result).isEmpty();
        } catch (ReflectiveOperationException | RuntimeException failure) {
            CallOperation operation = callOperation;
            logStage("hfp_current_calls_failed", "operation=getCurrentCalls\n"
                    + com.byd.extend.remote.CameraViewingDiagnostics.describe(failure,
                            operation == null ? null : operation.number,
                            currentCard == null ? null : currentCard.name));
            return false;
        }
    }

    private void onProfileConnectDeadline() {
        CallOperation operation = callOperation;
        if (operation != null && operation.stage == CallStage.CONNECTING_HFP)
            failCall(operation, R.string.calls_picker_dial_failed, "hfp_profile_timeout");
    }

    private void tryDismissAssignedHint(HintDismissRequest request) {
        if (request == null || request != hintDismissRequest || !request.dismissAllowed
                || isBlocked()
                || !rescueConnected || !request.session.equals(rescueSession)) return;
        if (deadlineReached(SystemClock.elapsedRealtime(), request.expiresAt)) {
            cancelHintDismiss("hint_window_expired");
            return;
        }
        if (!hasSourceAssignment(currentRescueAssignments(preferences), request.source)) {
            cancelHintDismiss("binding_removed");
            return;
        }
        XCallScan scan = scanXCallTargets();
        try {
            if (deadlineReached(SystemClock.elapsedRealtime(), request.expiresAt)) {
                cancelHintDismiss("hint_window_expired");
                return;
            }
            if (scan.incomplete || scan.hasCallStageControl) return;
            List<TargetNode> candidates = targetsForHint(scan);
            if (candidates.size() != 1) return;
            TargetNode target = candidates.get(0);
            boolean validTarget = target.node.refresh() && target.node.isVisibleToUser()
                    && target.node.isEnabled()
                    && XCALL_PACKAGE.equals(packageName(target.node))
                    && isHintTarget(target.id);
            if (request != hintDismissRequest) return;
            if (isBlocked() || !rescueConnected || !request.session.equals(rescueSession)) {
                cancelHintDismiss("hint_context_changed");
                return;
            }
            if (!hasSourceAssignment(currentRescueAssignments(preferences), request.source)) {
                cancelHintDismiss("binding_removed");
                return;
            }
            if (deadlineReached(SystemClock.elapsedRealtime(), request.expiresAt)) {
                cancelHintDismiss("hint_window_expired");
                return;
            }
            if (!validTarget) return;
            boolean clicked = target.node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            logStage("assigned_hint_dismiss", "source=" + request.source.name()
                    + " target=" + simpleId(target.id) + " accepted=" + clicked);
            if (clicked && (!request.retryAfterAcceptedDismiss
                    || ++request.acceptedDismissals >= 2)) cancelHintDismiss("hint_dismissed");
        } catch (RuntimeException failure) {
            logStage("assigned_hint_dismiss_failed", Log.getStackTraceString(failure));
            Log.e(TAG, "assigned_hint_dismiss_failed", failure);
        } finally {
            scan.recycleTargets();
        }
    }

    private XCallScan scanXCallTargets() {
        XCallScan scan = new XCallScan();
        List<AccessibilityWindowInfo> windows = null;
        try {
            windows = service.getWindows();
            for (AccessibilityWindowInfo window : windows) {
                if (window == null) continue;
                AccessibilityNodeInfo root = null;
                try {
                    root = window.getRoot();
                    if (root == null) {
                        scan.incomplete = true;
                        continue;
                    }
                    if (!XCALL_PACKAGE.equals(packageName(root))) continue;
                    scan.xcallWindows++;
                    boolean rootRetained = walkXCallTree(root, 0, scan);
                    if (!rootRetained) root.recycle();
                    root = null;
                } catch (RuntimeException failure) {
                    scan.incomplete = true;
                } finally {
                    if (root != null) root.recycle();
                }
            }
        } catch (RuntimeException failure) {
            scan.incomplete = true;
        } finally {
            recycleWindows(windows);
        }
        return scan;
    }

    private boolean walkXCallTree(AccessibilityNodeInfo node, int depth, XCallScan scan) {
        if (depth > MAX_ACCESSIBILITY_DEPTH || ++scan.visited > MAX_ACCESSIBILITY_NODES) {
            scan.incomplete = true;
            return false;
        }
        boolean retained = false;
        try {
            String id = node.getViewIdResourceName();
            if (isKnownXCallTarget(id)) {
                if (isCallTarget(id) || XCALL_REDIAL.equals(id)) scan.hasCallStageControl = true;
                if (node.isVisibleToUser() && node.isEnabled()) {
                    scan.targets.add(new TargetNode(id, node));
                    retained = true;
                }
            }
            int childCount = node.getChildCount();
            for (int index = 0; index < childCount; index++) {
                AccessibilityNodeInfo child = node.getChild(index);
                if (child == null) continue;
                boolean childRetained = walkXCallTree(child, depth + 1, scan);
                if (!childRetained) child.recycle();
                if (scan.incomplete) break;
            }
        } catch (RuntimeException failure) {
            scan.incomplete = true;
        }
        return retained;
    }

    private XCallWindowState scanXCallWindowState() {
        XCallWindowState state = new XCallWindowState();
        List<AccessibilityWindowInfo> windows = null;
        try {
            windows = service.getWindows();
            for (AccessibilityWindowInfo window : windows) {
                if (window == null) continue;
                AccessibilityNodeInfo root = null;
                try {
                    root = window.getRoot();
                    if (root == null) state.complete = false;
                    else if (XCALL_PACKAGE.equals(packageName(root))) state.xcallWindows++;
                } finally {
                    if (root != null) root.recycle();
                }
            }
        } catch (RuntimeException failure) {
            state.complete = false;
        } finally {
            recycleWindows(windows);
        }
        return state;
    }

    private List<TargetNode> targetsForCall(XCallScan scan) {
        List<TargetNode> result = new ArrayList<>();
        for (TargetNode target : scan.targets) if (isCallTarget(target.id)) result.add(target);
        return result;
    }

    private List<TargetNode> targetsForHint(XCallScan scan) {
        List<TargetNode> result = new ArrayList<>();
        for (TargetNode target : scan.targets) if (isHintTarget(target.id)) result.add(target);
        return result;
    }

    private void closeProfile(CallOperation operation, BluetoothProfile profile) {
        if (operation == null || profile == null || operation.adapter == null) return;
        try { operation.adapter.closeProfileProxy(PROFILE_HEADSET_CLIENT, profile); }
        catch (RuntimeException ignored) { }
        if (operation.proxy == profile) operation.proxy = null;
    }

    private boolean mayContinue(CallOperation operation) {
        if (callOperation != operation || operation == null || isBlocked()
                || !rescueConnected || !operation.session.equals(rescueSession)
                || operation.sequence != lastRescueSequence) {
            cancelPendingCall("call_context_changed", false);
            return false;
        }
        CallsSettings.Destination current = CallsSettings.read(preferences, operation.profile);
        if (!configured(current) || !operation.number.equals(current.number)) {
            cancelPendingCall("destination_changed", false);
            return false;
        }
        return true;
    }

    private void failCall(CallOperation operation, int toastResource, String reason) {
        if (callOperation != operation) return;
        logStage("call_replacement_failed", "stage=" + operation.stage.name()
                + " reason=" + reason);
        finishCall(operation);
        if (!isBlocked()) showLongToast(toastResource);
    }

    private void finishCall(CallOperation operation) {
        if (callOperation != operation) return;
        handler.removeCallbacks(callTargetDeadline);
        handler.removeCallbacks(callConfirmPoll);
        handler.removeCallbacks(profileConnectDeadline);
        callOperation = null;
        if (operation.proxy != null) closeProfile(operation, operation.proxy);
    }

    private void cancelPendingCall(String reason, boolean toast) {
        CallOperation operation = callOperation;
        if (operation == null) return;
        boolean replacementIncomplete = operation.stage != CallStage.WAITING_FOR_TARGET;
        logStage("call_replacement_cancelled", "stage=" + operation.stage.name()
                + " reason=" + reason + " dial_invoked=" + operation.dialInvoked);
        finishCall(operation);
        if ((toast || replacementIncomplete) && !GuardRecovery.isUserShutdownActive(service))
            showLongToast(R.string.calls_picker_oem_cancel_failed);
    }

    private void cancelHintDismiss(String reason) {
        if (hintDismissRequest == null) return;
        hintDismissRequest = null;
        hintGeneration++;
    }

    private void stopLiveWork(String reason, boolean toast) {
        gestures.reset();
        handler.removeCallbacks(gestureDeadline);
        cancelHintDismiss(reason);
        cancelPendingCall(reason, toast);
        cancelContactSelection();
        if (!rescueSession.isEmpty()) {
            rescueConnected = false;
            rescueSession = "";
            lastRescueSequence = 0;
        }
        if (!closed) refreshHelperDemand();
    }

    private void closeForLifecycle(String reason) {
        closed = true;
        handler.removeCallbacks(gestureDeadline);
        handler.removeCallbacks(callTargetDeadline);
        handler.removeCallbacks(callConfirmPoll);
        handler.removeCallbacks(profileConnectDeadline);
        cancelHintDismiss(reason);
        cancelPendingCall(reason, false);
        cancelContactSelection();
        gestures.reset();
        rescueConnected = false;
        rescueSession = "";
        lastRescueSequence = 0;
        CameraHelperService.configureCalls(service, false);
        helperDemandEnabled = false;
    }

    private void refreshHelperDemand() {
        if (closed) return;
        boolean needed = !isBlocked() && requiresRescueSubscription(service);
        if (needed == helperDemandEnabled) return;
        helperDemandEnabled = needed;
        CameraHelperService.configureCalls(service, needed);
    }

    private boolean isBlocked() {
        return GuardRecovery.isUserShutdownActive(service)
                || LegacySettingsImporter.blocksRuntime(service);
    }

    private static boolean configured(CallsSettings.Destination destination) {
        return destination != null && destination.enabled
                && CallsSettings.normalizeNumber(destination.number) != null;
    }

    private static boolean hasActiveRescueBinding(SharedPreferences prefs,
            CameraButtonBindings.Source source) {
        if (prefs == null || source == null) return false;
        for (CameraButtonBindings.Action action : rescueActions()) {
            if (!featureEnabled(prefs, action)) continue;
            CameraButtonBindings.Binding binding = CameraButtonBindings.load(prefs, action);
            if (binding.isAssigned() && binding.source == source
                    && CameraButtonBindings.supports(action, source, binding.press)) return true;
        }
        return false;
    }

    private static List<RescueButtonGesturePolicy.Assignment> currentRescueAssignments(
            SharedPreferences prefs) {
        List<RescueButtonGesturePolicy.Assignment> result = new ArrayList<>(5);
        if (prefs == null) return result;
        for (CameraButtonBindings.Action action : rescueActions()) {
            if (!featureEnabled(prefs, action)) continue;
            CameraButtonBindings.Binding binding = CameraButtonBindings.load(prefs, action);
            if (binding.isAssigned() && binding.source != CameraButtonBindings.Source.AndroidKey
                    && CameraButtonBindings.supports(action, binding.source, binding.press)) {
                result.add(new RescueButtonGesturePolicy.Assignment(action, binding));
            }
        }
        return result;
    }

    private static List<CameraButtonBindings.Action> rescueActions() {
        return Arrays.asList(CameraButtonBindings.Action.AvasMicrophone,
                CameraButtonBindings.Action.MirrorSource,
                CameraButtonBindings.Action.MirrorVisibility,
                CameraButtonBindings.Action.HazardThanks);
    }

    private static boolean featureEnabled(SharedPreferences prefs,
            CameraButtonBindings.Action action) {
        if (action == CameraButtonBindings.Action.AvasMicrophone)
            return AvasMicrophoneSettings.enabled(prefs);
        if (action == CameraButtonBindings.Action.MirrorSource)
            return RearviewMirrorSettings.enabled(prefs)
                    && RearviewMirrorSettings.frontIntegrated(prefs);
        if (action == CameraButtonBindings.Action.MirrorVisibility)
            return RearviewMirrorSettings.enabled(prefs);
        if (action == CameraButtonBindings.Action.HazardThanks)
            return HazardThanksSettings.enabled(prefs);
        return false;
    }

    private static boolean hasActionAssignment(
            List<RescueButtonGesturePolicy.Assignment> assignments,
            CameraButtonBindings.Source source, CameraButtonBindings.Action action) {
        for (RescueButtonGesturePolicy.Assignment assignment : assignments) {
            if (assignment != null && assignment.action == action && assignment.binding != null
                    && assignment.binding.source == source && assignment.binding.isAssigned())
                return true;
        }
        return false;
    }

    private static boolean hasSourceAssignment(List<RescueButtonGesturePolicy.Assignment> assignments,
            CameraButtonBindings.Source source) {
        for (RescueButtonGesturePolicy.Assignment assignment : assignments) {
            if (assignment != null && assignment.binding != null
                    && assignment.binding.source == source && assignment.binding.isAssigned()) return true;
        }
        return false;
    }

    private static CameraButtonBindings.Source sourceForValue(int value) {
        if (value == 1 || value == 3) return CameraButtonBindings.Source.Sos;
        if (value == 2 || value == 4) return CameraButtonBindings.Source.Help;
        return null;
    }

    private static CallsSettings.Profile profileForSource(CameraButtonBindings.Source source) {
        if (source == CameraButtonBindings.Source.Sos) return CallsSettings.Profile.Sos;
        if (source == CameraButtonBindings.Source.Help) return CallsSettings.Profile.Help;
        return null;
    }

    private static boolean isRuntimePreference(String key) {
        if (key == null) return true;
        if (key.startsWith("calls_sos_") || key.startsWith("calls_help_")) return true;
        if (key.equals(CameraButtonBindings.MICROPHONE_KEY_CODE)
                || key.equals(CameraButtonBindings.MICROPHONE_PRESS)
                || key.equals(CameraButtonBindings.MICROPHONE_SOURCE)
                || key.equals(CameraButtonBindings.MIRROR_SOURCE_KEY_CODE)
                || key.equals(CameraButtonBindings.MIRROR_SOURCE_PRESS)
                || key.equals(CameraButtonBindings.MIRROR_SOURCE_SOURCE)
                || key.equals(CameraButtonBindings.MIRROR_VISIBILITY_KEY_CODE)
                || key.equals(CameraButtonBindings.MIRROR_VISIBILITY_PRESS)
                || key.equals(CameraButtonBindings.MIRROR_VISIBILITY_SOURCE)
                || key.equals(CameraButtonBindings.HAZARD_THANKS_KEY_CODE)
                || key.equals(CameraButtonBindings.HAZARD_THANKS_PRESS)
                || key.equals(CameraButtonBindings.HAZARD_THANKS_SOURCE)
                || key.equals(CameraButtonBindings.HAZARD_THANKS_PRESS + "_android")
                || key.equals(AvasMicrophoneSettings.ENABLED)
                || key.equals(HazardThanksSettings.ENABLED)
                || key.equals(RearviewMirrorSettings.PREF_ENABLED)
                || key.equals(RearviewMirrorSettings.PREF_FRONT_INTEGRATED)
                || key.equals(GuardRecovery.KEY_USER_SHUTDOWN)
                || key.equals(LegacySettingsImporter.PREF_HANDOVER_BLOCKED)
                || key.equals(LegacySettingsImporter.PREF_HANDOVER_COMPLETE)) return true;
        return false;
    }

    private static boolean isKnownXCallTarget(String id) {
        return isHintTarget(id) || isCallTarget(id) || XCALL_REDIAL.equals(id);
    }

    private static boolean isHintTarget(String id) {
        return XCALL_CLOSE_HINT.equals(id) || XCALL_APP_BUTTON2.equals(id)
                || XCALL_ANDROID_BUTTON2.equals(id);
    }

    private static boolean isCallTarget(String id) {
        return XCALL_HANGUP.equals(id) || XCALL_FLOAT_HANGUP.equals(id)
                || XCALL_CANCEL.equals(id);
    }

    static boolean isXCallTargetEvent(int eventType, CharSequence packageName) {
        return eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED
                || packageName != null && XCALL_PACKAGE.contentEquals(packageName);
    }

    static boolean targetSearchExpired(long startedAt, long now) {
        return deadlineReached(now, startedAt + TARGET_WAIT_MS);
    }

    static boolean deadlineReached(long now, long deadline) {
        return now >= deadline;
    }

    private static String simpleId(String id) {
        if (XCALL_HANGUP.equals(id)) return "dialog_hangup";
        if (XCALL_FLOAT_HANGUP.equals(id)) return "float_end_call";
        if (XCALL_CANCEL.equals(id)) return "dialog_cancel";
        if (XCALL_CLOSE_HINT.equals(id)) return "close_hint";
        if (XCALL_APP_BUTTON2.equals(id)) return "xcall_button2";
        if (XCALL_ANDROID_BUTTON2.equals(id)) return "android_button2";
        return "unknown";
    }

    private void showLongToast(int resourceId) {
        try {
            Context localized = AppLanguage.localizedContext(
                    service, AppLanguage.read(preferences));
            Toast.makeText(localized, resourceId, Toast.LENGTH_LONG).show();
        }
        catch (RuntimeException ignored) { }
    }

    private static String packageName(AccessibilityNodeInfo node) {
        CharSequence value = node == null ? null : node.getPackageName();
        return value == null ? "" : value.toString();
    }

    private static boolean sameCard(ContactCard left, ContactCard right) {
        return left == null ? right == null : left.sameAs(right);
    }

    private static void recycleNodes(List<AccessibilityNodeInfo> nodes) {
        if (nodes == null) return;
        for (AccessibilityNodeInfo node : nodes) if (node != null) node.recycle();
    }

    private static void recycleWindows(List<AccessibilityWindowInfo> windows) {
        if (windows == null) return;
        for (AccessibilityWindowInfo window : windows) if (window != null) window.recycle();
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private int dp(int value) {
        return Math.round(value * service.getResources().getDisplayMetrics().density);
    }

    private void logStage(String stage, String details) {
        CameraHelperService.logCallsEvent(stage, details);
        Log.i(TAG, stage + " " + details);
    }

    private static final class ContactRead {
        boolean rootAvailable;
        boolean incomplete;
        ContactCard card;
    }

    static final class ContactCard {
        final String name;
        final List<String> numbers;

        ContactCard(String name, List<String> numbers) {
            this.name = name;
            this.numbers = numbers;
        }

        static ContactCard fromFields(List<String> names, List<String> rawNumbers) {
            if (names == null || names.size() != 1 || names.get(0) == null) return null;
            String name = names.get(0).trim();
            if (name.isEmpty()) return null;
            Set<String> unique = new LinkedHashSet<>();
            if (rawNumbers != null) {
                for (String raw : rawNumbers) {
                    String normalized = CallsSettings.normalizeNumber(raw);
                    if (normalized == null) return null;
                    unique.add(normalized);
                }
            }
            return new ContactCard(name, new ArrayList<>(unique));
        }

        boolean sameAs(ContactCard other) {
            return other != null && name.equals(other.name) && numbers.equals(other.numbers);
        }
    }

    private static final class XCallScan {
        final List<TargetNode> targets = new ArrayList<>();
        int xcallWindows;
        int visited;
        boolean incomplete;
        boolean hasCallStageControl;

        void recycleTargets() {
            for (TargetNode target : targets) target.node.recycle();
            targets.clear();
        }
    }

    private static final class TargetNode {
        final String id;
        final AccessibilityNodeInfo node;

        TargetNode(String id, AccessibilityNodeInfo node) {
            this.id = id;
            this.node = node;
        }
    }

    private static final class XCallWindowState {
        int xcallWindows;
        boolean complete = true;
    }

    private static final class HintDismissRequest {
        final long generation;
        final CameraButtonBindings.Source source;
        final String session;
        final long sequence;
        long expiresAt;
        boolean dismissAllowed;
        boolean retryAfterAcceptedDismiss;
        int acceptedDismissals;

        HintDismissRequest(long generation, CameraButtonBindings.Source source,
                String session, long sequence, long expiresAt, boolean dismissAllowed) {
            this.generation = generation;
            this.source = source;
            this.session = session;
            this.sequence = sequence;
            this.expiresAt = expiresAt;
            this.dismissAllowed = dismissAllowed;
        }
    }

    private enum CallStage { WAITING_FOR_TARGET, CHECKING_DISMISSAL, CONNECTING_HFP, DIAL_INVOKED }

    private static final class CallOperation {
        final long token;
        final CallsSettings.Profile profile;
        final String number;
        final String session;
        final long sequence;
        final long startedAt;
        CallStage stage;
        boolean targetTreeIncomplete;
        long clickedAt;
        long lastConfirmSample;
        int absentSamples;
        boolean dialInvoked;
        BluetoothAdapter adapter;
        BluetoothProfile proxy;

        CallOperation(long token, CallsSettings.Profile profile, String number,
                String session, long sequence, long startedAt) {
            this.token = token;
            this.profile = profile;
            this.number = number;
            this.session = session;
            this.sequence = sequence;
            this.startedAt = startedAt;
        }
    }
}
