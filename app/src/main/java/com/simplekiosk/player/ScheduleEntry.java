package com.simplekiosk.player;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

final class ScheduleEntry {
    final String name;
    final int startMinute;
    final int endMinute;
    final List<PlaylistItem> playlist = new ArrayList<PlaylistItem>();

    ScheduleEntry(String name, int startMinute, int endMinute) {
        this.name = name;
        this.startMinute = startMinute;
        this.endMinute = endMinute;
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
}
