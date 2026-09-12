package com.byd.extend;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.os.SystemClock;
import android.util.Log;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Process-owned state and Messenger coordination for the app's update-hint card. */
public final class UpdateHintCoordinator {
    public static final String ACTION = "com.byd.apps.updatehint.COORDINATION";
    public static final String METADATA_PROTOCOL_VERSION =
            "com.byd.apps.updatehint.PROTOCOL_VERSION";
    public static final int SUBSCRIBE = 1;
    public static final int STATE = 2;
    public static final int UNSUBSCRIBE = 3;

    private static final String TAG = "UpdateHintCoord";
    private static final String OWNER = "com.byd.extend";
    private static final long INITIAL_BUDGET_NS = 500_000_000L;
    private static final Set<String> ALLOWED_PACKAGES = new HashSet<>(Arrays.asList(
            "com.bydhud.app", OWNER, "com.bydcollector.collector"));
    private static volatile UpdateHintCoordinator instance;

    private final Context context;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final String sessionId = UUID.randomUUID().toString();
    private final Map<String, UpdateHintState.SessionRecord> remotes = new HashMap<>();
    private final Map<IBinder, Subscriber> subscribers = new HashMap<>();
    private final Map<String, PeerConnection> connections = new HashMap<>();
    private final Set<String> expectedInitial = new HashSet<>();
    private final Set<String> receivedInitial = new HashSet<>();

    private UpdateHintState ownState;
    private Listener listener;
    private long revision;
    private boolean initialExchangeReady = true;
    private int displayId;
    private int availableLeftPx;
    private int availableTopPx;
    private int availableWidthPx;
    private int availableHeightPx;
    private float density = 1f;
    private String lastPlacement = "";

    private final Runnable initialDeadline = () -> {
        if (!initialExchangeReady) {
            initialExchangeReady = true;
            if (!receivedInitial.containsAll(expectedInitial)) {
                HashSet<String> missing = new HashSet<>(expectedInitial);
                missing.removeAll(receivedInitial);
                Log.w(TAG, "initial exchange deadline; missing=" + missing);
            }
            dispatch();
        }
    };
    private final Runnable expire = this::expireAndDispatch;

    public interface Listener {
        void onUpdateHintCoordinationChanged(
                UpdateHintState ownState, UpdateHintLayout.Result layout,
                boolean initialExchangeReady);
    }

    public static UpdateHintCoordinator get(Context context) {
        UpdateHintCoordinator current = instance;
        if (current != null) return current;
        synchronized (UpdateHintCoordinator.class) {
            if (instance == null) {
                instance = new UpdateHintCoordinator(context.getApplicationContext());
            }
            return instance;
        }
    }

    private UpdateHintCoordinator(Context context) {
        this.context = context;
        ownState = UpdateHintState.none(OWNER, sessionId, revision);
    }

    /** Pass null when the overlay owner is torn down. */
    public void setListener(Listener listener) {
        runOnMain(() -> {
            this.listener = listener;
            dispatch();
        });
    }

    public void beginPending(String eventId, long requestedAtElapsedNanos, int displayId,
            int preferredSizePercent, int preferredWidthPx, int preferredHeightPx) {
        runOnMain(() -> {
            UpdateHintState candidate = new UpdateHintState(UpdateHintState.PROTOCOL_VERSION,
                    OWNER, sessionId, revision + 1, eventId, UpdateHintState.PENDING,
                    requestedAtElapsedNanos, 0, displayId, preferredSizePercent,
                    preferredWidthPx, preferredHeightPx);
            if (!candidate.isValid()) throw new IllegalArgumentException("invalid pending state");
            if (!ownState.isNone() && ownState.eventId.equals(eventId)) return;
            revision++;
            ownState = candidate;
            initialExchangeReady = false;
            Log.i(TAG, "pending event=" + eventId);
            broadcastOwnState();
            connectForInitialExchange(requestedAtElapsedNanos);
            dispatch();
        });
    }

    public void markVisible(long actualShownElapsedMs) {
        runOnMain(() -> {
            if (!UpdateHintState.PENDING.equals(ownState.phase)) return;
            ownState = ownState.visible(actualShownElapsedMs, ++revision);
            Log.i(TAG, "visible event=" + ownState.eventId + " expires="
                    + ownState.expiresAtElapsedMs);
            broadcastOwnState();
            dispatch();
        });
    }

    public void clear(String reason) {
        runOnMain(() -> clearOnMain(reason));
    }

    public void updatePreferredGeometry(
            int preferredSizePercent, int preferredWidthPx, int preferredHeightPx) {
        runOnMain(() -> {
            if (ownState.isNone()) return;
            if (preferredSizePercent <= 0 || preferredWidthPx <= 0 || preferredHeightPx <= 0) {
                throw new IllegalArgumentException("invalid preferred geometry");
            }
            if (ownState.preferredSizePercent == preferredSizePercent
                    && ownState.preferredWidthPx == preferredWidthPx
                    && ownState.preferredHeightPx == preferredHeightPx) return;
            ownState = ownState.withGeometry(preferredSizePercent, preferredWidthPx,
                    preferredHeightPx, ++revision);
            broadcastOwnState();
            dispatch();
        });
    }

    /** Available panel-aware bounds in absolute display pixels. */
    public void updateAvailableArea(int displayId, int leftPx, int topPx, int widthPx,
            int heightPx, float density) {
        runOnMain(() -> {
            this.displayId = displayId;
            availableLeftPx = leftPx;
            availableTopPx = topPx;
            availableWidthPx = Math.max(0, widthPx);
            availableHeightPx = Math.max(0, heightPx);
            this.density = density > 0 ? density : 1f;
            dispatch();
        });
    }

    void subscribe(Message message) {
        runOnMain(() -> {
            UpdateHintState state = decodeAndValidate(message);
            if (state == null || message.replyTo == null) return;
            IBinder binder = message.replyTo.getBinder();
            Subscriber old = subscribers.remove(binder);
            if (old != null) old.unlink();
            Subscriber subscriber = new Subscriber(state.ownerPackage, state.processSessionId,
                    message.replyTo, binder);
            try {
                binder.linkToDeath(subscriber, 0);
                subscribers.put(binder, subscriber);
            } catch (RemoteException error) {
                handleConfirmedDeath(state.ownerPackage, state.processSessionId);
                return;
            }
            applyRemote(state);
            receivedInitial.add(state.ownerPackage);
            finishInitialIfReady();
            sendState(message.replyTo, ownState);
        });
    }

    void receiveState(Message message) {
        runOnMain(() -> {
            UpdateHintState state = decodeAndValidate(message);
            if (state != null) {
                applyRemote(state);
                receivedInitial.add(state.ownerPackage);
                finishInitialIfReady();
            }
        });
    }

    void unsubscribe(Message message) {
        runOnMain(() -> {
            UpdateHintState state = decodeAndValidate(message);
            if (state == null || message.replyTo == null) return;
            Subscriber subscriber = subscribers.remove(message.replyTo.getBinder());
            if (subscriber != null) subscriber.unlink();
        });
    }

    private void connectForInitialExchange(long requestedAtElapsedNanos) {
        disconnectPeers(true);
        expectedInitial.clear();
        receivedInitial.clear();
        Intent query = new Intent(ACTION);
        List<ResolveInfo> services;
        try {
            services = context.getPackageManager().queryIntentServices(
                    query, PackageManager.GET_META_DATA);
        } catch (RuntimeException error) {
            Log.w(TAG, "service discovery failed", error);
            services = new ArrayList<>();
        }
        for (ResolveInfo info : services) {
            if (info.serviceInfo == null || info.serviceInfo.packageName.equals(OWNER)
                    || !ALLOWED_PACKAGES.contains(info.serviceInfo.packageName)
                    || info.serviceInfo.metaData == null
                    || info.serviceInfo.metaData.getInt(METADATA_PROTOCOL_VERSION, 0) != 1) continue;
            String peerPackage = info.serviceInfo.packageName;
            if (!expectedInitial.add(peerPackage)) continue;
            ComponentName component = new ComponentName(peerPackage, info.serviceInfo.name);
            PeerConnection connection = new PeerConnection(peerPackage, component);
            connections.put(peerPackage, connection);
            try {
                connection.bound = context.bindService(new Intent(ACTION).setComponent(component),
                        connection, Context.BIND_AUTO_CREATE);
                if (!connection.bound) {
                    Log.w(TAG, "bind rejected peer=" + peerPackage);
                }
            } catch (RuntimeException error) {
                Log.w(TAG, "bind failed peer=" + peerPackage, error);
            }
        }
        handler.removeCallbacks(initialDeadline);
        long deadlineMs = requestedAtElapsedNanos / 1_000_000L
                + INITIAL_BUDGET_NS / 1_000_000L;
        handler.postAtTime(initialDeadline, Math.max(SystemClock.uptimeMillis(),
                SystemClock.uptimeMillis() + deadlineMs - SystemClock.elapsedRealtime()));
        finishInitialIfReady();
    }

    private void finishInitialIfReady() {
        if (!initialExchangeReady && receivedInitial.containsAll(expectedInitial)) {
            initialExchangeReady = true;
            handler.removeCallbacks(initialDeadline);
            dispatch();
        }
    }

    private void applyRemote(UpdateHintState state) {
        if (OWNER.equals(state.ownerPackage)) return;
        UpdateHintState.SessionRecord record = remotes.get(state.ownerPackage);
        if (record == null) {
            record = new UpdateHintState.SessionRecord();
            remotes.put(state.ownerPackage, record);
        }
        if (record.accept(state)) dispatch();
    }

    private void handleConfirmedDeath(String ownerPackage, String processSessionId) {
        UpdateHintState.SessionRecord record = remotes.get(ownerPackage);
        if (record == null || !record.confirmDeath(processSessionId)) return;
        Log.w(TAG, "peer died owner=" + ownerPackage);
        dispatch();
    }

    private UpdateHintState decodeAndValidate(Message message) {
        UpdateHintState state = fromBundle(message.getData());
        if (state == null || !state.isValid() || !ALLOWED_PACKAGES.contains(state.ownerPackage)
                || OWNER.equals(state.ownerPackage)) return null;
        String[] uidPackages = context.getPackageManager().getPackagesForUid(message.sendingUid);
        if (uidPackages == null || !Arrays.asList(uidPackages).contains(state.ownerPackage)) {
            Log.w(TAG, "rejected uid/package owner=" + state.ownerPackage
                    + " uid=" + message.sendingUid);
            return null;
        }
        return state;
    }

    private void broadcastOwnState() {
        ArrayList<IBinder> failed = new ArrayList<>();
        for (Map.Entry<IBinder, Subscriber> entry : subscribers.entrySet()) {
            if (!sendState(entry.getValue().messenger, ownState)) {
                Log.w(TAG, "state delivery failed subscriber="
                        + entry.getValue().ownerPackage);
                failed.add(entry.getKey());
            }
        }
        for (IBinder binder : failed) {
            Subscriber subscriber = subscribers.remove(binder);
            if (subscriber != null) subscriber.unlink();
        }
        for (PeerConnection connection : connections.values()) {
            if (connection.remote != null && !sendState(connection.remote, ownState)) {
                Log.w(TAG, "state delivery failed peer=" + connection.peerPackage);
            }
        }
    }

    private void clearOnMain(String reason) {
        if (ownState.isNone()) return;
        String eventId = ownState.eventId;
        ownState = UpdateHintState.none(OWNER, sessionId, ++revision);
        initialExchangeReady = true;
        handler.removeCallbacks(initialDeadline);
        Log.i(TAG, "clear event=" + eventId + " reason=" + (reason == null ? "" : reason));
        broadcastOwnState();
        disconnectPeers(true);
        dispatch();
    }

    private void disconnectPeers(boolean sendUnsubscribe) {
        for (PeerConnection connection : new ArrayList<>(connections.values())) {
            if (sendUnsubscribe && connection.remote != null) {
                Message message = stateMessage(UNSUBSCRIBE, ownState);
                message.replyTo = connection.replyMessenger;
                try {
                    connection.remote.send(message);
                } catch (RemoteException ignored) {
                }
            }
            connection.unbind();
        }
        connections.clear();
    }

    private void expireAndDispatch() {
        if (UpdateHintState.VISIBLE.equals(ownState.phase)
                && ownState.expiresAtElapsedMs <= SystemClock.elapsedRealtime()) {
            clearOnMain("expired");
            return;
        }
        dispatch();
    }

    private void dispatch() {
        handler.removeCallbacks(expire);
        long nowNs = SystemClock.elapsedRealtimeNanos();
        long nowMs = SystemClock.elapsedRealtime();
        ArrayList<UpdateHintState> states = new ArrayList<>();
        if (!ownState.isNone()) states.add(ownState);
        long nextMs = Long.MAX_VALUE;
        if (UpdateHintState.VISIBLE.equals(ownState.phase)) nextMs = ownState.expiresAtElapsedMs;
        for (UpdateHintState.SessionRecord record : remotes.values()) {
            UpdateHintState state = record.state();
            if (state == null || state.isNone()) continue;
            states.add(state);
            long deadline = UpdateHintState.VISIBLE.equals(state.phase)
                    ? state.expiresAtElapsedMs
                    : state.requestedAtElapsedNanos / 1_000_000L + 500L;
            if (deadline > nowMs) nextMs = Math.min(nextMs, deadline);
        }
        if (nextMs != Long.MAX_VALUE) handler.postDelayed(expire, Math.max(1, nextMs - nowMs));
        UpdateHintLayout.Result layout = UpdateHintLayout.calculate(states, displayId,
                availableLeftPx, availableTopPx, availableWidthPx, availableHeightPx, density,
                nowNs, nowMs, OWNER);
        UpdateHintLayout.Placement ownPlacement = layout.find(OWNER, ownState.eventId);
        String placement = ownPlacement == null ? "" : ownPlacement.xPx + ":"
                + ownPlacement.yPx + ":" + ownPlacement.effectiveScalePercent;
        if (!placement.equals(lastPlacement)) {
            lastPlacement = placement;
            if (!placement.isEmpty()) Log.i(TAG, "target=" + placement);
        }
        if (listener != null) {
            listener.onUpdateHintCoordinationChanged(ownState, layout, initialExchangeReady);
        }
    }

    private boolean sendState(Messenger target, UpdateHintState state) {
        try {
            target.send(stateMessage(STATE, state));
            return true;
        } catch (RemoteException error) {
            return false;
        }
    }

    static Message stateMessage(int what, UpdateHintState state) {
        Message message = Message.obtain(null, what);
        message.setData(toBundle(state));
        return message;
    }

    static Bundle toBundle(UpdateHintState state) {
        Bundle data = new Bundle();
        data.putInt("protocolVersion", state.protocolVersion);
        data.putString("ownerPackage", state.ownerPackage);
        data.putString("processSessionId", state.processSessionId);
        data.putLong("revision", state.revision);
        data.putString("eventId", state.eventId);
        data.putString("phase", state.phase);
        data.putLong("requestedAtElapsedNanos", state.requestedAtElapsedNanos);
        data.putLong("expiresAtElapsedMs", state.expiresAtElapsedMs);
        data.putInt("displayId", state.displayId);
        data.putInt("preferredSizePercent", state.preferredSizePercent);
        data.putInt("preferredWidthPx", state.preferredWidthPx);
        data.putInt("preferredHeightPx", state.preferredHeightPx);
        return data;
    }

    static UpdateHintState fromBundle(Bundle data) {
        if (data == null) return null;
        return new UpdateHintState(data.getInt("protocolVersion", 0),
                data.getString("ownerPackage", ""), data.getString("processSessionId", ""),
                data.getLong("revision", -1), data.getString("eventId", ""),
                data.getString("phase", ""), data.getLong("requestedAtElapsedNanos", 0),
                data.getLong("expiresAtElapsedMs", 0), data.getInt("displayId", 0),
                data.getInt("preferredSizePercent", 0), data.getInt("preferredWidthPx", 0),
                data.getInt("preferredHeightPx", 0));
    }

    private void runOnMain(Runnable action) {
        if (Looper.myLooper() == handler.getLooper()) action.run();
        else handler.post(action);
    }

    private final class Subscriber implements IBinder.DeathRecipient {
        final String ownerPackage;
        final String processSessionId;
        final Messenger messenger;
        final IBinder binder;

        Subscriber(String ownerPackage, String processSessionId, Messenger messenger, IBinder binder) {
            this.ownerPackage = ownerPackage;
            this.processSessionId = processSessionId;
            this.messenger = messenger;
            this.binder = binder;
        }

        @Override public void binderDied() {
            handler.post(() -> {
                subscribers.remove(binder);
                handleConfirmedDeath(ownerPackage, processSessionId);
            });
        }

        void unlink() {
            try {
                binder.unlinkToDeath(this, 0);
            } catch (RuntimeException ignored) {
            }
        }
    }

    private final class PeerConnection implements ServiceConnection {
        final String peerPackage;
        final ComponentName component;
        Messenger remote;
        Messenger replyMessenger;
        IBinder binder;
        IBinder.DeathRecipient deathRecipient;
        String boundSessionId;
        boolean bound;

        PeerConnection(String peerPackage, ComponentName component) {
            this.peerPackage = peerPackage;
            this.component = component;
        }

        @Override public void onServiceConnected(ComponentName name, IBinder service) {
            if (!peerPackage.equals(name.getPackageName())) return;
            unlinkDeath();
            binder = service;
            remote = new Messenger(service);
            bound = true;
            boundSessionId = null;
            replyMessenger = new Messenger(new Handler(Looper.getMainLooper(), message -> {
                if (message.what == STATE) receiveFromThisGeneration(message, service);
                return true;
            }));
            deathRecipient = () -> handler.post(() -> handleBinderDeath(service));
            try {
                service.linkToDeath(deathRecipient, 0);
                Message message = stateMessage(SUBSCRIBE, ownState);
                message.replyTo = replyMessenger;
                remote.send(message);
            } catch (RemoteException error) {
                handleBinderDeath(service);
            }
        }

        @Override public void onServiceDisconnected(ComponentName name) {
            remote = null; // Android may reconnect; retain unexpired remote geometry.
        }

        @Override public void onBindingDied(ComponentName name) {
            handler.post(() -> {
                if (connections.get(peerPackage) != this) return;
                String endedSessionId = boundSessionId;
                unbind();
                if (endedSessionId != null) {
                    handleConfirmedDeath(peerPackage, endedSessionId);
                }
                if (!ownState.isNone()) {
                    PeerConnection replacement = new PeerConnection(peerPackage, component);
                    connections.put(peerPackage, replacement);
                    try {
                        replacement.bound = context.bindService(
                                new Intent(ACTION).setComponent(component), replacement,
                                Context.BIND_AUTO_CREATE);
                    } catch (RuntimeException error) {
                        Log.w(TAG, "rebind failed peer=" + peerPackage, error);
                    }
                }
            });
        }

        private void receiveFromThisGeneration(Message message, IBinder service) {
            if (binder != service) return;
            UpdateHintState state = decodeAndValidate(message);
            if (state == null || !peerPackage.equals(state.ownerPackage)) return;
            if (boundSessionId != null && !boundSessionId.equals(state.processSessionId)) {
                Log.w(TAG, "rejected session change on live binder peer=" + peerPackage);
                return;
            }
            boundSessionId = state.processSessionId;
            applyRemote(state);
            receivedInitial.add(state.ownerPackage);
            finishInitialIfReady();
        }

        private void handleBinderDeath(IBinder deadBinder) {
            if (binder != deadBinder) return;
            if (boundSessionId != null) {
                handleConfirmedDeath(peerPackage, boundSessionId);
            }
            remote = null;
            replyMessenger = null;
            binder = null;
            deathRecipient = null;
            boundSessionId = null;
        }

        void unbind() {
            unlinkDeath();
            if (bound) {
                try {
                    context.unbindService(this);
                } catch (RuntimeException ignored) {
                }
            }
            bound = false;
            binder = null;
            remote = null;
            replyMessenger = null;
            boundSessionId = null;
        }

        private void unlinkDeath() {
            if (binder != null && deathRecipient != null) {
                try {
                    binder.unlinkToDeath(deathRecipient, 0);
                } catch (RuntimeException ignored) {
                }
            }
            deathRecipient = null;
        }
    }
}
