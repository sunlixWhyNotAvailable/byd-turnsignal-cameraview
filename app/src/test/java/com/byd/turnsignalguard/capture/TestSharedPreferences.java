package com.byd.turnsignalguard.capture;

import android.content.SharedPreferences;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

final class TestSharedPreferences implements SharedPreferences, SharedPreferences.Editor {
    private final Map<String, Object> values = new HashMap<>();

    @Override public Map<String, ?> getAll() { return Collections.unmodifiableMap(values); }
    @Override public String getString(String key, String fallback) {
        return value(key, fallback, String.class);
    }
    @SuppressWarnings("unchecked")
    @Override public Set<String> getStringSet(String key, Set<String> fallback) {
        Object value = values.get(key);
        return value == null ? fallback : new HashSet<>((Set<String>) value);
    }
    @Override public int getInt(String key, int fallback) {
        return value(key, fallback, Integer.class);
    }
    @Override public long getLong(String key, long fallback) {
        return value(key, fallback, Long.class);
    }
    @Override public float getFloat(String key, float fallback) {
        return value(key, fallback, Float.class);
    }
    @Override public boolean getBoolean(String key, boolean fallback) {
        return value(key, fallback, Boolean.class);
    }
    @Override public boolean contains(String key) { return values.containsKey(key); }
    @Override public Editor edit() { return this; }
    @Override public Editor putString(String key, String value) {
        values.put(key, value); return this;
    }
    @Override public Editor putStringSet(String key, Set<String> value) {
        values.put(key, new HashSet<>(value)); return this;
    }
    @Override public Editor putInt(String key, int value) {
        values.put(key, value); return this;
    }
    @Override public Editor putLong(String key, long value) {
        values.put(key, value); return this;
    }
    @Override public Editor putFloat(String key, float value) {
        values.put(key, value); return this;
    }
    @Override public Editor putBoolean(String key, boolean value) {
        values.put(key, value); return this;
    }
    @Override public Editor remove(String key) { values.remove(key); return this; }
    @Override public Editor clear() { values.clear(); return this; }
    @Override public boolean commit() { return true; }
    @Override public void apply() {}
    @Override public void registerOnSharedPreferenceChangeListener(
            OnSharedPreferenceChangeListener listener) {}
    @Override public void unregisterOnSharedPreferenceChangeListener(
            OnSharedPreferenceChangeListener listener) {}

    private <T> T value(String key, T fallback, Class<T> type) {
        Object value = values.get(key);
        if (value == null) return fallback;
        if (!type.isInstance(value)) throw new ClassCastException(key);
        return type.cast(value);
    }
}
