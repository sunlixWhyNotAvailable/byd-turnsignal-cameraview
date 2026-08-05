package com.byd.turnsignalguard.capture;

import android.graphics.Color;
import android.content.SharedPreferences;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayDeque;
final class CameraProbeMusicPanel {
    private final CameraProbeActivity activity;
    private final SharedPreferences preferences;
    private final LinearLayout root;
    private final Switch musicSwitch;
    private final TextView musicStatus;
    private final TextView musicJournalText;
    private final ArrayDeque<String> musicJournal = new ArrayDeque<>();

    CameraProbeMusicPanel(CameraProbeActivity activity, SharedPreferences preferences) {
        this.activity = activity;
        this.preferences = preferences;

        root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(0, activity.dp(12), 0, 0);

        musicSwitch = new Switch(activity);
        musicSwitch.setText("Підсвітка та метадані музики");
        musicSwitch.setTextColor(Color.WHITE);
        musicSwitch.setTextSize(20);
        musicSwitch.setChecked(preferences.getBoolean("music_visualizer_enabled", false));
        root.addView(musicSwitch, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, activity.dp(58)));

        musicStatus = activity.statusText(musicSwitch.isChecked()
                ? "Очікування helper..." : "Вимкнено");
        root.addView(musicStatus);

        TextView journalTitle = activity.label("Журнал");
        journalTitle.setPadding(0, activity.dp(24), 0, activity.dp(8));
        root.addView(journalTitle);

        ScrollView journalScroll = new ScrollView(activity);
        musicJournalText = new TextView(activity);
        musicJournalText.setTextColor(Color.LTGRAY);
        musicJournalText.setTextSize(15);
        musicJournalText.setPadding(
                activity.dp(12), activity.dp(12), activity.dp(12), activity.dp(12));
        musicJournalText.setText("Подій ще немає");
        journalScroll.addView(musicJournalText, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));
        root.addView(journalScroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));

        musicSwitch.setOnCheckedChangeListener((button, checked) -> {
            preferences.edit().putBoolean("music_visualizer_enabled", checked).apply();
            musicStatus.setText(checked ? "Очікування helper..." : "Вимкнено");
            activity.onMusicEnabledChanged(checked);
        });
    }

    View view() {
        return root;
    }

    boolean isChecked() {
        return musicSwitch.isChecked();
    }

    void setControlEnabled(boolean enabled) {
        musicSwitch.setEnabled(enabled);
    }

    void setStatus(String status) {
        musicStatus.setText(status);
    }

    void acceptEvent(JSONObject event) {
        String kind = event.optString("kind");
        if ("music_journal_snapshot".equals(kind)) {
            musicJournal.clear();
            JSONArray events = event.optJSONArray("events");
            if (events != null) {
                for (int i = 0; i < events.length(); i++) {
                    try {
                        appendMusicJournal(formatMusicEvent(
                                new JSONObject(events.optString(i))));
                    } catch (Throwable ignored) {
                    }
                }
            }
            renderMusicJournal();
            return;
        }
        if (!kind.startsWith("music_")) return;
        if (CameraHelperMain.HelperBinder.isMusicJournalEvent(kind)) {
            appendMusicJournal(formatMusicEvent(event));
            renderMusicJournal();
        }

        if ("music_runtime_status".equals(kind)) {
            boolean enabled = event.optBoolean("enabled");
            String error = event.optString("error");
            if (!enabled) musicStatus.setText("Вимкнено");
            else if (!error.isEmpty()) musicStatus.setText("Помилка: " + error);
            else if (event.optBoolean("stop_pending")) {
                musicStatus.setText("Завершення через 3 с");
            } else if (event.optBoolean("session_active")) {
                musicStatus.setText("Синхронізація активна");
            } else {
                musicStatus.setText("Очікування музики");
            }
        } else if ("music_visualizer_start".equals(kind)) {
            musicStatus.setText(event.optBoolean("ok", true)
                    ? "Синхронізація активна"
                    : "Помилка: " + event.optString("error"));
        } else if ("music_visualizer_stop_pending".equals(kind)) {
            musicStatus.setText("Завершення через 3 с");
        } else if ("music_visualizer_stop".equals(kind)) {
            musicStatus.setText(event.optBoolean("ok", true)
                    ? "Очікування музики"
                    : "Помилка: " + event.optString("error"));
        } else if ("music_runtime_config".equals(kind)) {
            if (!event.optBoolean("enabled")) musicStatus.setText("Вимкнено");
            else if (!event.optString("error").isEmpty()) {
                musicStatus.setText("Помилка: " + event.optString("error"));
            } else if (event.optBoolean("session_active")) {
                musicStatus.setText("Синхронізація активна");
            } else {
                musicStatus.setText("Очікування музики");
            }
        } else if ("music_runtime_error".equals(kind)) {
            musicStatus.setText("Помилка: " + event.optString("error"));
        }
    }

    private void appendMusicJournal(String line) {
        if (line == null || line.isEmpty()) return;
        while (musicJournal.size() >= 20) musicJournal.removeFirst();
        musicJournal.addLast(line);
    }

    private void renderMusicJournal() {
        if (musicJournal.isEmpty()) {
            musicJournalText.setText("Подій ще немає");
            return;
        }
        StringBuilder text = new StringBuilder();
        for (String line : musicJournal) {
            if (text.length() > 0) text.append('\n');
            text.append(line);
        }
        musicJournalText.setText(text);
    }

    private static String formatMusicEvent(JSONObject event) {
        String kind = event.optString("kind");
        String wallTime = event.optString("wall_time");
        String time = wallTime.length() >= 19 ? wallTime.substring(11, 19) : "--:--:--";
        String message;
        if ("music_runtime_config".equals(kind)) {
            message = event.optBoolean("enabled") ? "Функцію увімкнено" : "Функцію вимкнено";
        } else if ("music_playback_state".equals(kind)) {
            message = event.optBoolean("active") ? "Виявлено відтворення" : "Відтворення зупинено";
        } else if ("music_visualizer_start".equals(kind)) {
            message = event.optBoolean("ok", true)
                    ? "Синхронізацію запущено" : "Помилка запуску";
        } else if ("music_visualizer_stop_pending".equals(kind)) {
            message = "Зупинка через 3 секунди";
        } else if ("music_visualizer_stop".equals(kind)) {
            message = event.optBoolean("ok", true)
                    ? "Синхронізацію зупинено" : "Помилка зупинки";
        } else if ("music_metadata_focus".equals(kind)) {
            message = "Аудіофокус: " + event.optString("to");
        } else if ("music_metadata_publish".equals(kind)) {
            message = event.optBoolean("ok", true)
                    ? "Метадані передано: " + event.optString("package")
                    : "Помилка передачі метаданих";
        } else if ("music_metadata_cleanup".equals(kind)) {
            message = "Метадані очищено";
        } else if ("music_metadata_relinquished".equals(kind)) {
            message = "Метадані передано штатній системі";
        } else if ("music_metadata_error".equals(kind)) {
            message = "Помилка метаданих: " + event.optString("error");
        } else if ("music_runtime_error".equals(kind)) {
            message = "Помилка: " + event.optString("error");
        } else if ("music_runtime_status".equals(kind)) {
            message = "Стан оновлено";
        } else {
            message = kind;
        }
        return time + "  " + message;
    }
}
