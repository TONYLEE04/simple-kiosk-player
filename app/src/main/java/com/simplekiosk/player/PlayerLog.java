package com.simplekiosk.player;

import android.util.Log;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

final class PlayerLog {
    private static final String TAG = "SimpleKiosk";
    private static final long MAX_LOG_BYTES = 1024L * 1024L;
    private static final SimpleDateFormat DATE_FORMAT =
            new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);

    private final File logFile;

    PlayerLog(File baseDir) {
        File logsDir = new File(baseDir, "logs");
        if (!logsDir.exists()) {
            logsDir.mkdirs();
        }
        logFile = new File(logsDir, "player.log");
    }

    void info(String message) {
        Log.i(TAG, message);
        write("INFO", message, null);
    }

    void error(String message) {
        Log.e(TAG, message);
        write("ERROR", message, null);
    }

    void error(String message, Throwable throwable) {
        Log.e(TAG, message, throwable);
        write("ERROR", message, throwable);
    }

    File getLogFile() {
        return logFile;
    }

    private synchronized void write(String level, String message, Throwable throwable) {
        FileWriter writer = null;
        try {
            rotateIfNeeded();
            writer = new FileWriter(logFile, true);
            writer.write(DATE_FORMAT.format(new Date()));
            writer.write(" ");
            writer.write(level);
            writer.write(" ");
            writer.write(message);
            writer.write("\n");
            if (throwable != null) {
                writer.write(throwable.toString());
                writer.write("\n");
            }
        } catch (IOException ignored) {
            Log.e(TAG, "Could not write log file", ignored);
        } finally {
            if (writer != null) {
                try {
                    writer.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    private void rotateIfNeeded() {
        if (!logFile.exists() || logFile.length() < MAX_LOG_BYTES) {
            return;
        }
        File backupFile = new File(logFile.getParentFile(), "player.log.1");
        if (backupFile.exists() && !backupFile.delete()) {
            Log.e(TAG, "Could not delete old log backup");
            return;
        }
        if (!logFile.renameTo(backupFile)) {
            Log.e(TAG, "Could not rotate log file");
        }
    }
}
