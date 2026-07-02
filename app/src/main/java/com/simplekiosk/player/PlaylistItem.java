package com.simplekiosk.player;

import java.io.File;

final class PlaylistItem {
    static final String TYPE_IMAGE = "image";
    static final String TYPE_VIDEO = "video";

    final String type;
    final File file;
    final int durationSeconds;
    final String fitMode;

    PlaylistItem(String type, File file, int durationSeconds, String fitMode) {
        this.type = type;
        this.file = file;
        this.durationSeconds = durationSeconds;
        this.fitMode = fitMode;
    }

    boolean isImage() {
        return TYPE_IMAGE.equals(type);
    }

    boolean isVideo() {
        return TYPE_VIDEO.equals(type);
    }
}
