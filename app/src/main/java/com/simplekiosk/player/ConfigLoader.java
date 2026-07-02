package com.simplekiosk.player;

import android.graphics.Color;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;

final class ConfigLoader {
    private final File baseDir;

    ConfigLoader(File baseDir) {
        this.baseDir = baseDir;
    }

    PlayerConfig load() throws IOException, JSONException, ConfigException {
        File configFile = new File(baseDir, "config.json");
        if (!configFile.exists()) {
            throw new ConfigException("Missing config file: " + configFile.getAbsolutePath());
        }

        JSONObject root = new JSONObject(readText(configFile));
        int version = root.optInt("version", 0);
        if (version != 1) {
            throw new ConfigException("Unsupported config version: " + version);
        }

        PlayerConfig config = new PlayerConfig();
        readSettings(root.optJSONObject("settings"), config);
        readPlaylist(root.optJSONArray("playlist"), config);

        if (config.playlist.isEmpty()) {
            throw new ConfigException("Playlist is empty");
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

    private void readPlaylist(JSONArray playlist, PlayerConfig config) throws JSONException, ConfigException {
        if (playlist == null) {
            throw new ConfigException("Missing playlist");
        }

        for (int i = 0; i < playlist.length(); i++) {
            JSONObject item = playlist.getJSONObject(i);
            String type = item.optString("type", "");
            if (!PlaylistItem.TYPE_IMAGE.equals(type) && !PlaylistItem.TYPE_VIDEO.equals(type)) {
                throw new ConfigException("Invalid playlist item type at index " + i + ": " + type);
            }

            String filePath = item.optString("file", "");
            if (filePath.length() == 0) {
                throw new ConfigException("Missing file for playlist item at index " + i);
            }

            int durationSeconds = item.optInt("duration", 8);
            if (PlaylistItem.TYPE_IMAGE.equals(type) && durationSeconds <= 0) {
                throw new ConfigException("Image duration must be greater than zero at index " + i);
            }

            String fitMode = readFitMode(item.optString("fitMode", config.fitMode), "playlist[" + i + "].fitMode");
            File mediaFile = new File(baseDir, filePath);
            if (!mediaFile.exists()) {
                throw new ConfigException("Missing media file: " + mediaFile.getAbsolutePath());
            }

            config.playlist.add(new PlaylistItem(type, mediaFile, durationSeconds, fitMode));
        }
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
