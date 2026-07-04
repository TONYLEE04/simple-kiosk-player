package com.simplekiosk.player;

import android.graphics.Color;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

final class ConfigLoader {
    private final File baseDir;
    private final File configFile;

    ConfigLoader(File baseDir) {
        this.baseDir = baseDir;
        this.configFile = new File(baseDir, "config.json");
    }

    File getConfigFile() {
        return configFile;
    }

    long getConfigLastModified() {
        return configFile.exists() ? configFile.lastModified() : 0L;
    }

    PlayerConfig load() throws IOException, JSONException, ConfigException {
        if (!configFile.exists()) {
            throw new ConfigException("Missing config file: " + configFile.getAbsolutePath());
        }
        return loadFromText(readText(configFile));
    }

    PlayerConfig loadFromText(String jsonText) throws JSONException, ConfigException {
        JSONObject root = new JSONObject(jsonText);
        int version = root.optInt("version", 0);
        if (version != 1) {
            throw new ConfigException("Unsupported config version: " + version);
        }

        PlayerConfig config = new PlayerConfig();
        readSettings(root.optJSONObject("settings"), config);
        readManagement(root.optJSONObject("management"), config);

        JSONArray playlist = root.optJSONArray("playlist");
        if (playlist != null) {
            config.playlist.addAll(readPlaylist(playlist, config.fitMode, "playlist"));
        }

        JSONArray schedules = root.optJSONArray("schedules");
        if (schedules != null) {
            readSchedules(schedules, config);
        }

        if (config.playlist.isEmpty() && config.schedules.isEmpty()) {
            throw new ConfigException("Config must contain playlist or schedules");
        }
        return config;
    }

    private void readSettings(JSONObject settings, PlayerConfig config) throws ConfigException {
        if (settings == null) {
            return;
        }

        config.orientation = settings.optString("orientation", config.orientation);
        config.fitMode = readFitMode(settings.optString("fitMode", config.fitMode), "settings.fitMode");
        config.keepScreenOn = settings.optBoolean("keepScreenOn", config.keepScreenOn);
        config.hideSystemUi = settings.optBoolean("hideSystemUi", config.hideSystemUi);
        config.mute = settings.optBoolean("mute", config.mute);

        String background = settings.optString("background", null);
        if (background != null) {
            try {
                config.backgroundColor = Color.parseColor(background);
            } catch (IllegalArgumentException e) {
                throw new ConfigException("Invalid background color: " + background);
            }
        }
    }


    private void readManagement(JSONObject management, PlayerConfig config) {
        if (management == null) {
            return;
        }
        config.managementAutoStart = management.optBoolean("autoStart", config.managementAutoStart);
        String password = management.optString("password", config.managementPassword);
        config.managementPassword = password == null ? "" : password.trim();
    }

    private void readSchedules(JSONArray schedules, PlayerConfig config) throws JSONException, ConfigException {
        for (int i = 0; i < schedules.length(); i++) {
            JSONObject item = schedules.getJSONObject(i);
            String name = item.optString("name", "schedule-" + i);
            String start = item.optString("start", "");
            String end = item.optString("end", "");
            int startMinute = parseMinuteOfDay(start, "schedules[" + i + "].start");
            int endMinute = parseMinuteOfDay(end, "schedules[" + i + "].end");
            String mode = readScheduleMode(item.optString("mode", ScheduleEntry.MODE_PLAYLIST),
                    "schedules[" + i + "].mode");
            String screen = readScheduleScreen(item.optString("screen", ScheduleEntry.SCREEN_BLACK),
                    "schedules[" + i + "].screen");

            ScheduleEntry schedule = new ScheduleEntry(name, startMinute, endMinute, mode, screen);
            if (schedule.isSilent()) {
                config.schedules.add(schedule);
                continue;
            }

            JSONArray playlist = item.optJSONArray("playlist");
            if (playlist == null) {
                throw new ConfigException("Missing playlist for schedule: " + name);
            }

            schedule.playlist.addAll(readPlaylist(playlist, config.fitMode,
                    "schedules[" + i + "].playlist"));
            if (schedule.playlist.isEmpty()) {
                throw new ConfigException("Empty playlist for schedule: " + name);
            }
            config.schedules.add(schedule);
        }
    }

    private List<PlaylistItem> readPlaylist(JSONArray playlist, String defaultFitMode, String fieldName)
            throws JSONException, ConfigException {
        List<PlaylistItem> items = new ArrayList<PlaylistItem>();
        for (int i = 0; i < playlist.length(); i++) {
            JSONObject item = playlist.getJSONObject(i);
            String type = item.optString("type", "");
            if (!PlaylistItem.TYPE_IMAGE.equals(type) && !PlaylistItem.TYPE_VIDEO.equals(type)) {
                throw new ConfigException("Invalid item type at " + fieldName + "[" + i + "]: " + type);
            }

            String filePath = item.optString("file", "");
            if (filePath.length() == 0) {
                throw new ConfigException("Missing file at " + fieldName + "[" + i + "]");
            }

            int durationSeconds = item.optInt("duration", 8);
            if (PlaylistItem.TYPE_IMAGE.equals(type) && durationSeconds <= 0) {
                throw new ConfigException("Image duration must be greater than zero at "
                        + fieldName + "[" + i + "]");
            }

            String fitMode = readFitMode(item.optString("fitMode", defaultFitMode),
                    fieldName + "[" + i + "].fitMode");
            File mediaFile = new File(baseDir, filePath);
            if (!mediaFile.exists()) {
                throw new ConfigException("Missing media file: " + mediaFile.getAbsolutePath());
            }

            items.add(new PlaylistItem(type, mediaFile, durationSeconds, fitMode));
        }
        return items;
    }

    private int parseMinuteOfDay(String value, String fieldName) throws ConfigException {
        if (value == null || value.length() != 5 || value.charAt(2) != ':') {
            throw new ConfigException("Invalid time at " + fieldName + ": " + value);
        }
        try {
            int hour = Integer.parseInt(value.substring(0, 2));
            int minute = Integer.parseInt(value.substring(3, 5));
            if (hour < 0 || hour > 23 || minute < 0 || minute > 59) {
                throw new ConfigException("Invalid time at " + fieldName + ": " + value);
            }
            return hour * 60 + minute;
        } catch (NumberFormatException e) {
            throw new ConfigException("Invalid time at " + fieldName + ": " + value);
        }
    }


    private String readScheduleMode(String value, String fieldName) throws ConfigException {
        if (ScheduleEntry.MODE_PLAYLIST.equals(value) || ScheduleEntry.MODE_SILENT.equals(value)) {
            return value;
        }
        throw new ConfigException("Invalid " + fieldName + ": " + value);
    }

    private String readScheduleScreen(String value, String fieldName) throws ConfigException {
        if (ScheduleEntry.SCREEN_BLACK.equals(value) || ScheduleEntry.SCREEN_ALLOW_SLEEP.equals(value)) {
            return value;
        }
        throw new ConfigException("Invalid " + fieldName + ": " + value);
    }
    private String readFitMode(String value, String fieldName) throws ConfigException {
        if (!PlayerConfig.isValidFitMode(value)) {
            throw new ConfigException("Invalid " + fieldName + ": " + value);
        }
        return value;
    }

    private static String readText(File file) throws IOException {
        StringBuilder builder = new StringBuilder();
        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new FileReader(file));
            String line;
            while ((line = reader.readLine()) != null) {
                builder.append(line);
                builder.append('\n');
            }
        } finally {
            if (reader != null) {
                reader.close();
            }
        }
        return builder.toString();
    }

    static final class ConfigException extends Exception {
        ConfigException(String message) {
            super(message);
        }
    }
}
