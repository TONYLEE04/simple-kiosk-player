package com.simplekiosk.player;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

final class PlayerConfig {
    static final String FIT_CONTAIN = "contain";
    static final String FIT_COVER = "cover";
    static final String FIT_STRETCH = "stretch";
    static final String FIT_CENTER = "center";

    String orientation = "landscape";
    String fitMode = FIT_CONTAIN;
    int backgroundColor = 0xff000000;
    boolean keepScreenOn = true;
    boolean hideSystemUi = true;
    boolean mute = true;
    final List<PlaylistItem> playlist = new ArrayList<PlaylistItem>();
    final List<ScheduleEntry> schedules = new ArrayList<ScheduleEntry>();

    static boolean isValidFitMode(String value) {
        return FIT_CONTAIN.equals(value)
                || FIT_COVER.equals(value)
                || FIT_STRETCH.equals(value)
                || FIT_CENTER.equals(value);
    }

    ScheduleEntry getActiveSchedule(Calendar calendar) {
        for (int i = 0; i < schedules.size(); i++) {
            ScheduleEntry schedule = schedules.get(i);
            if (schedule.isActive(calendar)) {
                return schedule;
            }
        }
        return null;
    }

    List<PlaylistItem> getActivePlaylist(Calendar calendar) {
        ScheduleEntry schedule = getActiveSchedule(calendar);
        if (schedule != null) {
            return schedule.playlist;
        }
        return playlist;
    }

    String getActivePlaylistName(Calendar calendar) {
        ScheduleEntry schedule = getActiveSchedule(calendar);
        if (schedule != null) {
            return schedule.name;
        }
        return "default";
    }
}