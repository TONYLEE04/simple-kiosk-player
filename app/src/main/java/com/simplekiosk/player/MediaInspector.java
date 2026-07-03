package com.simplekiosk.player;

import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class MediaInspector {
    static final class Result {
        int width;
        int height;
        long durationMs;
        float fps;
        String codec = "";
        String profile = "";
        String level = "";
        String mime = "";
        final List<String> warnings = new ArrayList<String>();
    }

    Result inspect(File file, boolean video) {
        Result result = new Result();
        if (video) {
            inspectVideo(file, result);
            addVideoWarnings(result);
        } else {
            inspectImage(file, result);
            if (result.width <= 0 || result.height <= 0) {
                result.warnings.add("Cannot read image dimensions");
            }
        }
        return result;
    }

    private void inspectImage(File file, Result result) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
        result.width = bounds.outWidth;
        result.height = bounds.outHeight;
        result.codec = "image";
    }

    private void inspectVideo(File file, Result result) {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(file.getAbsolutePath());
            result.width = parseInt(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH));
            result.height = parseInt(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT));
            result.durationMs = parseLong(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION));
            String mime = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE);
            result.mime = mime != null ? mime : "";
        } catch (RuntimeException ignored) {
            result.warnings.add("Cannot read Android video metadata");
        } finally {
            try {
                retriever.release();
            } catch (RuntimeException ignored) {
            }
        }

        try {
            Mp4Info mp4 = parseMp4(file);
            if (mp4.codec.length() > 0) {
                result.codec = mp4.codec;
            }
            if (mp4.width > 0 && result.width <= 0) {
                result.width = mp4.width;
            }
            if (mp4.height > 0 && result.height <= 0) {
                result.height = mp4.height;
            }
            if (mp4.durationMs > 0 && result.durationMs <= 0) {
                result.durationMs = mp4.durationMs;
            }
            result.fps = mp4.fps;
            result.profile = mp4.profile;
            result.level = mp4.level;
        } catch (IOException ignored) {
        }
    }

    private void addVideoWarnings(Result result) {
        String codecLower = result.codec.toLowerCase(Locale.US);
        if (result.width <= 0 || result.height <= 0) {
            result.warnings.add("No readable video dimensions");
        }
        long pixels = (long) result.width * (long) result.height;
        if (result.width >= 3840 || result.height >= 2160 || pixels > 1920L * 1080L * 2L) {
            result.warnings.add("4K-class resolution");
        }
        if (result.fps >= 59.5f) {
            result.warnings.add("60fps video");
        }
        if (codecLower.indexOf("hvc1") >= 0 || codecLower.indexOf("hev1") >= 0
                || codecLower.indexOf("hevc") >= 0 || codecLower.indexOf("h265") >= 0) {
            result.warnings.add("HEVC/H.265 video");
        }
        if (codecLower.indexOf("av01") >= 0 || codecLower.indexOf("av1") >= 0) {
            result.warnings.add("AV1 video");
        }
        if (codecLower.indexOf("avc1") >= 0 && parseFloat(result.level) >= 5.2f) {
            result.warnings.add("H.264 level " + result.level + " is high for legacy devices");
        }
    }

    private int parseInt(String value) {
        try {
            return value == null ? 0 : Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private long parseLong(String value) {
        try {
            return value == null ? 0L : Long.parseLong(value);
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private float parseFloat(String value) {
        try {
            return value == null || value.length() == 0 ? 0f : Float.parseFloat(value);
        } catch (NumberFormatException e) {
            return 0f;
        }
    }

    private Mp4Info parseMp4(File file) throws IOException {
        RandomAccessFile input = new RandomAccessFile(file, "r");
        try {
            Mp4Info info = new Mp4Info();
            parseBoxes(input, 0L, input.length(), info, null);
            return info;
        } finally {
            input.close();
        }
    }

    private void parseBoxes(RandomAccessFile input, long start, long end, Mp4Info info, Track track)
            throws IOException {
        long position = start;
        while (position + 8L <= end) {
            input.seek(position);
            long size = readUInt32(input);
            String type = readType(input);
            long header = 8L;
            if (size == 1L) {
                size = input.readLong();
                header = 16L;
            } else if (size == 0L) {
                size = end - position;
            }
            if (size < header || position + size > end) {
                return;
            }

            long payload = position + header;
            long boxEnd = position + size;
            if ("trak".equals(type)) {
                Track child = new Track();
                parseBoxes(input, payload, boxEnd, info, child);
                if ("vide".equals(child.handler) && info.codec.length() == 0) {
                    child.applyTo(info);
                }
            } else {
                parseLeaf(input, payload, boxEnd, type, track);
                if (isContainer(type)) {
                    parseBoxes(input, payload + ("meta".equals(type) ? 4L : 0L), boxEnd, info, track);
                }
            }
            position += size;
        }
    }

    private void parseLeaf(RandomAccessFile input, long payload, long boxEnd, String type, Track track)
            throws IOException {
        if (track == null) {
            return;
        }
        if ("hdlr".equals(type) && payload + 12L <= boxEnd) {
            input.seek(payload + 8L);
            track.handler = readType(input);
        } else if ("mdhd".equals(type) && payload + 20L <= boxEnd) {
            input.seek(payload);
            int version = input.readUnsignedByte();
            if (version == 1 && payload + 32L <= boxEnd) {
                input.seek(payload + 20L);
                track.timescale = readUInt32(input);
                track.durationUnits = input.readLong();
            } else {
                input.seek(payload + 12L);
                track.timescale = readUInt32(input);
                track.durationUnits = readUInt32(input);
            }
        } else if ("stsz".equals(type) && payload + 12L <= boxEnd) {
            input.seek(payload + 8L);
            track.sampleCount = readUInt32(input);
        } else if ("stts".equals(type) && payload + 8L <= boxEnd) {
            input.seek(payload + 4L);
            long entries = readUInt32(input);
            long total = 0L;
            for (long i = 0L; i < entries && input.getFilePointer() + 8L <= boxEnd; i++) {
                total += readUInt32(input);
                readUInt32(input);
            }
            track.timingSampleCount = total;
        } else if ("stsd".equals(type) && payload + 16L <= boxEnd) {
            parseSampleDescription(input, payload, boxEnd, track);
        }
    }

    private void parseSampleDescription(RandomAccessFile input, long payload, long boxEnd, Track track)
            throws IOException {
        input.seek(payload + 4L);
        long count = readUInt32(input);
        long position = payload + 8L;
        for (long i = 0L; i < count && position + 8L <= boxEnd; i++) {
            input.seek(position);
            long size = readUInt32(input);
            String codec = readType(input);
            long entryEnd = position + size;
            if (size < 8L || entryEnd > boxEnd) {
                return;
            }
            track.codec = codec;
            if (isVideoSample(codec) && position + 86L <= entryEnd) {
                input.seek(position + 32L);
                track.width = input.readUnsignedShort();
                track.height = input.readUnsignedShort();
                parseVideoSampleChildren(input, position + 86L, entryEnd, track);
            }
            position = entryEnd;
        }
    }

    private void parseVideoSampleChildren(RandomAccessFile input, long start, long end, Track track)
            throws IOException {
        long position = start;
        while (position + 8L <= end) {
            input.seek(position);
            long size = readUInt32(input);
            String type = readType(input);
            if (size < 8L || position + size > end) {
                return;
            }
            if ("avcC".equals(type) && size >= 12L) {
                input.seek(position + 8L);
                input.readUnsignedByte();
                int profile = input.readUnsignedByte();
                int compatibility = input.readUnsignedByte();
                int level = input.readUnsignedByte();
                track.profile = h264ProfileName(profile);
                track.level = String.format(Locale.US, "%.1f", level / 10f);
                track.codec = String.format(Locale.US, "avc1.%02X%02X%02X",
                        profile, compatibility, level);
                return;
            }
            position += size;
        }
    }

    private boolean isContainer(String type) {
        return "moov".equals(type) || "trak".equals(type) || "mdia".equals(type)
                || "minf".equals(type) || "stbl".equals(type) || "edts".equals(type)
                || "udta".equals(type) || "meta".equals(type);
    }

    private boolean isVideoSample(String codec) {
        return "avc1".equals(codec) || "avc3".equals(codec)
                || "hvc1".equals(codec) || "hev1".equals(codec)
                || "av01".equals(codec) || "mp4v".equals(codec);
    }

    private String h264ProfileName(int profile) {
        if (profile == 66) {
            return "Baseline";
        }
        if (profile == 77) {
            return "Main";
        }
        if (profile == 100) {
            return "High";
        }
        return String.valueOf(profile);
    }

    private long readUInt32(RandomAccessFile input) throws IOException {
        return input.readInt() & 0xffffffffL;
    }

    private String readType(RandomAccessFile input) throws IOException {
        byte[] bytes = new byte[4];
        input.readFully(bytes);
        return new String(bytes, "ISO-8859-1");
    }

    private static final class Mp4Info {
        int width;
        int height;
        long durationMs;
        float fps;
        String codec = "";
        String profile = "";
        String level = "";
    }

    private static final class Track {
        String handler = "";
        String codec = "";
        String profile = "";
        String level = "";
        int width;
        int height;
        long timescale;
        long durationUnits;
        long sampleCount;
        long timingSampleCount;

        void applyTo(Mp4Info info) {
            info.width = width;
            info.height = height;
            info.codec = codec;
            info.profile = profile;
            info.level = level;
            if (timescale > 0L && durationUnits > 0L) {
                info.durationMs = durationUnits * 1000L / timescale;
                long frames = timingSampleCount > 0L ? timingSampleCount : sampleCount;
                if (frames > 0L) {
                    info.fps = (float) frames * (float) timescale / (float) durationUnits;
                }
            }
        }
    }
}