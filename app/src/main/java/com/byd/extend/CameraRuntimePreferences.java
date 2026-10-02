package com.byd.extend;

import android.content.SharedPreferences;
import android.os.Bundle;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/** Camera settings replica owned by the surviving helper, never the app's private XML. */
final class CameraRuntimePreferences implements SharedPreferences {
    private final Map<String, Object> values = new HashMap<>();
    private final Map<String, Object> pending = new HashMap<>();
    private final Set<OnSharedPreferenceChangeListener> listeners = new HashSet<>();
    private Consumer<Bundle> changed;

    synchronized void setChangeListener(Consumer<Bundle> listener) { changed = listener; }

    synchronized Bundle reconcile(Bundle snapshot) {
        Map<String, Object> incoming = new HashMap<>();
        for (String key : snapshot.keySet()) incoming.put(key, normalize(snapshot.get(key)));
        return encode(reconcile(incoming));
    }

    synchronized Map<String, Object> reconcile(Map<String, ?> snapshot) {
        values.clear();
        values.putAll(snapshot);
        // Changes made while the UI was absent win over the stale disk snapshot on reattach.
        for (Map.Entry<String, Object> entry : pending.entrySet()) {
            if (entry.getValue() == null) values.remove(entry.getKey());
            else values.put(entry.getKey(), entry.getValue());
        }
        return new HashMap<>(pending);
    }

    synchronized Bundle updateFromClient(Bundle delta) {
        for (String key : delta.keySet()) {
            Object value = normalize(delta.get(key));
            if (value == null) values.remove(key); else values.put(key, value);
            pending.remove(key);
        }
        return encode(pending);
    }

    synchronized void acknowledge(Bundle applied) {
        for (String key : applied.keySet()) {
            acknowledge(key, normalize(applied.get(key)));
        }
    }

    synchronized void acknowledge(String key, Object applied) {
        if (java.util.Objects.equals(pending.get(key), applied)) pending.remove(key);
    }

    static Object normalize(Object value) {
        return value instanceof ArrayList ? new HashSet<>((ArrayList<String>) value) : value;
    }

    static Bundle snapshot(SharedPreferences settings) { return encode(settings.getAll()); }

    static Bundle encode(Map<String, ?> map) {
        Bundle result = new Bundle();
        for (Map.Entry<String, ?> entry : map.entrySet()) {
            String key = entry.getKey(); Object value = entry.getValue();
            if (value instanceof Boolean) result.putBoolean(key, (Boolean) value);
            else if (value instanceof Integer) result.putInt(key, (Integer) value);
            else if (value instanceof Long) result.putLong(key, (Long) value);
            else if (value instanceof Float) result.putFloat(key, (Float) value);
            else if (value instanceof String || value == null) result.putString(key, (String) value);
            else if (value instanceof Set) result.putStringArrayList(key,
                    new ArrayList<>((Set<String>) value));
        }
        return result;
    }

    static boolean apply(SharedPreferences settings, Bundle delta) {
        Editor editor = settings.edit();
        for (String key : delta.keySet()) {
            Object value = delta.get(key);
            if (value == null) editor.remove(key);
            else if (value instanceof Boolean) editor.putBoolean(key, (Boolean) value);
            else if (value instanceof Integer) editor.putInt(key, (Integer) value);
            else if (value instanceof Long) editor.putLong(key, (Long) value);
            else if (value instanceof Float) editor.putFloat(key, (Float) value);
            else if (value instanceof String) editor.putString(key, (String) value);
            else if (value instanceof ArrayList) editor.putStringSet(key,
                    new HashSet<>((ArrayList<String>) value));
            else throw new IllegalArgumentException("unsupported preference type");
        }
        // Acknowledge only durable writes; app death must not lose a widget's last position.
        return editor.commit();
    }

    @Override public synchronized Map<String, ?> getAll() { return new HashMap<>(values); }
    @Override public synchronized String getString(String key, String fallback) { return (String) values.getOrDefault(key, fallback); }
    @Override public synchronized Set<String> getStringSet(String key, Set<String> fallback) {
        Object value = values.get(key);
        return value instanceof ArrayList ? new HashSet<>((ArrayList<String>) value)
                : value instanceof Set ? new HashSet<>((Set<String>) value) : fallback;
    }
    @Override public synchronized int getInt(String key, int fallback) { return (Integer) values.getOrDefault(key, fallback); }
    @Override public synchronized long getLong(String key, long fallback) { return (Long) values.getOrDefault(key, fallback); }
    @Override public synchronized float getFloat(String key, float fallback) { return (Float) values.getOrDefault(key, fallback); }
    @Override public synchronized boolean getBoolean(String key, boolean fallback) { return (Boolean) values.getOrDefault(key, fallback); }
    @Override public synchronized boolean contains(String key) { return values.containsKey(key); }
    @Override public synchronized void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) { listeners.add(listener); }
    @Override public synchronized void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) { listeners.remove(listener); }
    @Override public Editor edit() { return new Edit(); }

    private final class Edit implements Editor {
        final Map<String, Object> edits = new HashMap<>();
        boolean clear;
        @Override public Editor putString(String key, String value) { edits.put(key, value); return this; }
        @Override public Editor putStringSet(String key, Set<String> value) { edits.put(key, value == null ? null : new HashSet<>(value)); return this; }
        @Override public Editor putInt(String key, int value) { edits.put(key, value); return this; }
        @Override public Editor putLong(String key, long value) { edits.put(key, value); return this; }
        @Override public Editor putFloat(String key, float value) { edits.put(key, value); return this; }
        @Override public Editor putBoolean(String key, boolean value) { edits.put(key, value); return this; }
        @Override public Editor remove(String key) { edits.put(key, null); return this; }
        @Override public Editor clear() { clear = true; return this; }
        @Override public boolean commit() { apply(); return true; }
        @Override public void apply() {
            Consumer<Bundle> sink; Set<OnSharedPreferenceChangeListener> observers;
            synchronized (CameraRuntimePreferences.this) {
                if (clear) for (String key : values.keySet()) edits.putIfAbsent(key, null);
                edits.entrySet().removeIf(e -> java.util.Objects.equals(values.get(e.getKey()), e.getValue()));
                for (Map.Entry<String, Object> e : edits.entrySet()) {
                    if (e.getValue() == null) values.remove(e.getKey());
                    else values.put(e.getKey(), e.getValue());
                    pending.put(e.getKey(), e.getValue());
                }
                sink = changed; observers = new HashSet<>(listeners);
            }
            for (String key : edits.keySet()) for (OnSharedPreferenceChangeListener observer : observers)
                observer.onSharedPreferenceChanged(CameraRuntimePreferences.this, key);
            if (sink != null && !edits.isEmpty()) sink.accept(encode(edits));
        }
    }
}
