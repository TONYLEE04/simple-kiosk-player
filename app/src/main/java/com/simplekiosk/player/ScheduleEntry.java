package com.simplekiosk.player;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

final class ScheduleEntry {
    static final String MODE_PLAYLIST = "playlist";
    static final String MODE_SILENT = "silent";
    static final String SCREEN_BLACK = "black";
    static final String SCREEN_ALLOW_SLEEP = "allowSleep";

    final String name;
    final int startMinute;
    final int endMinute;
    final String mode;
    final String screen;
    final List<PlaylistItem> playlist = new ArrayList<PlaylistItem>();

    ScheduleEntry(String name, int startMinute, int endMinute, String mode, String screen) {
        this.name = name;
        this.startMinute = startMinute;
        this.endMinute = endMinute;
        this.mode = mode;
        this.screen = screen;
    }

    boolean isActive(Calendar calendar) {
        int now = calendar.get(Calendar.HOUR_OF_DAY) * 60 + calendar.get(Calendar.MINUTE);
        if (startMinute == endMinute) {
            return true;
        }
        if (startMinute < endMinute) {
            return now >= startMinute && now < endMinute;
        }
        return now >= startMinute || now < endMinute;
    }

    boolean isSilent() {
        return MODE_SILENT.equals(mode);
    }

    boolean shouldAllowSleep() {
        return SCREEN_ALLOW_SLEEP.equals(screen);
    }
}