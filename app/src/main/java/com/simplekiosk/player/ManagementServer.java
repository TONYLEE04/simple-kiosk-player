package com.simplekiosk.player;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

final class ManagementServer {
    interface StatusProvider {
        String buildStatusTextForManagement();
    }

    private static final int SOCKET_TIMEOUT_MS = 30000;
    private static final long MAX_UPLOAD_BYTES = 1024L * 1024L * 1024L;
    private static final long MAX_CONFIG_BYTES = 512L * 1024L;

    private final int port;
    private final File baseDir;
    private final File mediaDir;
    private final File configFile;
    private final File logFile;
    private final PlayerLog log;
    private final ConfigLoader configLoader;
    private final StatusProvider statusProvider;
    private final String accessToken;
    private final MediaInspector mediaInspector = new MediaInspector();
    private final Map<String, CachedMediaInfo> mediaInfoCache = new HashMap<String, CachedMediaInfo>();

    private volatile boolean running;
    private volatile boolean accessProtectionEnabled = true;
    private ServerSocket serverSocket;
    private Thread serverThread;

    ManagementServer(int port, File baseDir, File configFile, File logFile,
            PlayerLog log, StatusProvider statusProvider) {
        this.port = port;
        this.baseDir = baseDir;
        this.mediaDir = new File(baseDir, "media");
        this.configFile = configFile;
        this.logFile = logFile;
        this.log = log;
        this.configLoader = new ConfigLoader(baseDir);
        this.statusProvider = statusProvider;
        this.accessToken = makeAccessToken();
    }

    synchronized void start() throws IOException {
        if (running) {
            return;
        }
        if (!mediaDir.exists()) {
            mediaDir.mkdirs();
        }
        serverSocket = new ServerSocket(port);
        running = true;
        serverThread = new Thread(new Runnable() {
            @Override
            public void run() {
                serveLoop();
            }
        }, "SimpleKioskManagementServer");
        serverThread.start();
        log.info("Started LAN management server on port " + port);
    }

    synchronized void stop() {
        running = false;
        if (serverSocket != null) {
            try {
                serverSocket.close();
            } catch (IOException ignored) {
            }
            serverSocket = null;
        }
        log.info("Stopped LAN management server");
    }

    boolean isRunning() {
        return running;
    }

    int getPort() {
        return port;
    }

    String getAccessPath() {
        return accessProtectionEnabled ? "/?token=" + accessToken : "/";
    }

    String getAccessCode() {
        return accessToken;
    }

    boolean isAccessProtectionEnabled() {
        return accessProtectionEnabled;
    }

    private void serveLoop() {
        while (running) {
            try {
                Socket socket = serverSocket.accept();
                socket.setSoTimeout(SOCKET_TIMEOUT_MS);
                handleClient(socket);
            } catch (IOException e) {
                if (running) {
                    log.error("LAN management server error", e);
                }
            }
        }
    }

    private void handleClient(Socket socket) {
        try {
            BufferedInputStream input = new BufferedInputStream(socket.getInputStream());
            OutputStream output = new BufferedOutputStream(socket.getOutputStream());
            HttpRequest request = readRequest(input);
            if (request == null) {
                return;
            }

            if (accessProtectionEnabled && !hasValidAccessToken(request)) {
                if ("GET".equals(request.method) && "/".equals(request.path)) {
                    writeText(output, 403, "text/html; charset=utf-8", buildUnlockPage());
                } else {
                    writeText(output, 403, "text/plain; charset=utf-8", "Access code required\n");
                }
            } else if ("GET".equals(request.method) && "/".equals(request.path)) {
                writeText(output, 200, "text/html; charset=utf-8", buildHomePage());
            } else if ("GET".equals(request.method) && "/status".equals(request.path)) {
                writeText(output, 200, "text/plain; charset=utf-8",
                        statusProvider.buildStatusTextForManagement());
            } else if ("GET".equals(request.method) && "/logs".equals(request.path)) {
                writeText(output, 200, "text/plain; charset=utf-8", readTextFile(logFile, 200000));
            } else if ("GET".equals(request.method) && "/config".equals(request.path)) {
                writeText(output, 200, "application/json; charset=utf-8", readTextFile(configFile, 200000));
            } else if ("GET".equals(request.method) && "/media".equals(request.path)) {
                writeText(output, 200, "application/json; charset=utf-8", listMediaFiles());
            } else if ("GET".equals(request.method) && "/media/preview".equals(request.path)) {
                handleMediaPreview(request, output);
            } else if ("POST".equals(request.method) && "/config".equals(request.path)) {
                handleSaveConfig(request, input, output);
            } else if ("POST".equals(request.method) && "/config/rollback".equals(request.path)) {
                handleRollbackConfig(output);
            } else if ("POST".equals(request.method) && "/media/delete".equals(request.path)) {
                handleDeleteMedia(request, output);
            } else if ("POST".equals(request.method) && "/media/rename".equals(request.path)) {
                handleRenameMedia(request, output);
            } else if ("POST".equals(request.method) && "/access/disable".equals(request.path)) {
                handleSetAccessProtection(false, output);
            } else if ("POST".equals(request.method) && "/access/enable".equals(request.path)) {
                handleSetAccessProtection(true, output);
            } else if ("POST".equals(request.method) && "/upload".equals(request.path)) {
                handleUpload(request, input, output);
            } else {
                writeText(output, 404, "text/plain; charset=utf-8", "Not found\n");
            }
            output.flush();
        } catch (IOException e) {
            log.error("Could not handle LAN management request", e);
        } finally {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }

    private HttpRequest readRequest(InputStream input) throws IOException {
        String requestLine = readAsciiLine(input);
        if (requestLine == null || requestLine.length() == 0) {
            return null;
        }
        String[] parts = requestLine.split(" ");
        if (parts.length < 2) {
            return null;
        }

        HttpRequest request = new HttpRequest();
        request.method = parts[0];
        String target = parts[1];
        int queryIndex = target.indexOf('?');
        if (queryIndex >= 0) {
            request.path = target.substring(0, queryIndex);
            request.query = target.substring(queryIndex + 1);
        } else {
            request.path = target;
            request.query = "";
        }

        String line;
        while ((line = readAsciiLine(input)) != null && line.length() > 0) {
            int colon = line.indexOf(':');
            if (colon <= 0) {
                continue;
            }
            String name = line.substring(0, colon).trim().toLowerCase(Locale.US);
            String value = line.substring(colon + 1).trim();
            if ("content-length".equals(name)) {
                try {
                    request.contentLength = Long.parseLong(value);
                } catch (NumberFormatException ignored) {
                    request.contentLength = -1L;
                }
            }
        }
        return request;
    }

    private String readAsciiLine(InputStream input) throws IOException {
        StringBuilder builder = new StringBuilder();
        int b;
        boolean seenAny = false;
        while ((b = input.read()) != -1) {
            seenAny = true;
            if (b == '\r') {
                continue;
            }
            if (b == '\n') {
                break;
            }
            builder.append((char) b);
        }
        if (!seenAny && builder.length() == 0) {
            return null;
        }
        return builder.toString();
    }

    private void handleSaveConfig(HttpRequest request, InputStream input, OutputStream output) throws IOException {
        if (request.contentLength <= 0L) {
            writeText(output, 400, "text/plain; charset=utf-8", "Missing config body\n");
            return;
        }
        if (request.contentLength > MAX_CONFIG_BYTES) {
            writeText(output, 413, "text/plain; charset=utf-8", "Config too large\n");
            return;
        }

        String jsonText = readBodyAsText(input, request.contentLength);
        try {
            configLoader.loadFromText(jsonText);
        } catch (Exception e) {
            writeText(output, 400, "text/plain; charset=utf-8",
                    "Invalid config: " + e.getMessage() + "\n");
            return;
        }

        writeConfigFile(jsonText);
        log.info("Saved config from LAN playlist editor");
        writeText(output, 200, "text/plain; charset=utf-8", "Saved config.json\n");
    }

    private String readBodyAsText(InputStream input, long contentLength) throws IOException {
        byte[] bytes = new byte[(int) contentLength];
        int offset = 0;
        while (offset < bytes.length) {
            int read = input.read(bytes, offset, bytes.length - offset);
            if (read == -1) {
                throw new IOException("Request body ended early");
            }
            offset += read;
        }
        return new String(bytes, "UTF-8");
    }

    private void writeConfigFile(String jsonText) throws IOException {
        if (!baseDir.exists()) {
            baseDir.mkdirs();
        }
        File tempFile = new File(baseDir, "config.json.tmp");
        File backupFile = new File(baseDir, "config.json.bak");
        writeTextFile(tempFile, jsonText);

        if (backupFile.exists() && !backupFile.delete()) {
            throw new IOException("Could not replace old config backup");
        }
        if (configFile.exists() && !configFile.renameTo(backupFile)) {
            throw new IOException("Could not back up current config");
        }
        if (!tempFile.renameTo(configFile)) {
            if (backupFile.exists()) {
                backupFile.renameTo(configFile);
            }
            throw new IOException("Could not replace config file");
        }
    }

    private void writeTextFile(File file, String text) throws IOException {
        FileOutputStream output = null;
        try {
            output = new FileOutputStream(file);
            output.write(text.getBytes("UTF-8"));
        } finally {
            if (output != null) {
                output.close();
            }
        }
    }

    private static String makeAccessToken() {
        return String.format(Locale.US, "%06d", new SecureRandom().nextInt(1000000));
    }

    private boolean hasValidAccessToken(HttpRequest request) {
        return accessToken.equals(queryValue(request.query, "token"));
    }

    private void handleSetAccessProtection(boolean enabled, OutputStream output) throws IOException {
        accessProtectionEnabled = enabled;
        log.info("LAN access protection " + (enabled ? "enabled" : "disabled"));
        writeText(output, 200, "text/plain; charset=utf-8",
                "Access protection " + (enabled ? "enabled" : "disabled") + "\n");
    }

    private String buildUnlockPage() {
        return "<!doctype html>\n"
                + "<html><head><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                + "<title>Simple Kiosk Access</title>"
                + "<style>body{font-family:Arial,sans-serif;background:#0f1115;color:#e8edf2;margin:0;padding:24px;}"
                + "form{max-width:360px;margin:10vh auto;background:#1a1f26;border:1px solid #303842;padding:18px;}"
                + "h1{font-size:22px;margin:0 0 12px;}input,button{font-size:18px;padding:9px;margin-top:8px;width:100%;box-sizing:border-box;}"
                + "input{background:#11161c;color:#edf2f7;border:1px solid #3d4652;}button{background:#2b6fd6;color:white;border:1px solid #2b6fd6;}"
                + ".muted{color:#9aa7b2;font-size:13px;line-height:1.4;}</style></head><body>"
                + "<form method=\"GET\" action=\"/\"><h1>Simple Kiosk</h1>"
                + "<div class=\"muted\">Enter the access code shown on the tablet maintenance screen.</div>"
                + "<input name=\"token\" inputmode=\"numeric\" autocomplete=\"off\" autofocus>"
                + "<button type=\"submit\">Open</button></form></body></html>\n";
    }

    private void handleRollbackConfig(OutputStream output) throws IOException {
        File backupFile = new File(baseDir, "config.json.bak");
        if (!backupFile.exists()) {
            writeText(output, 400, "text/plain; charset=utf-8", "No config backup exists\n");
            return;
        }
        String backupText = readFullTextFile(backupFile);
        try {
            configLoader.loadFromText(backupText);
        } catch (Exception e) {
            writeText(output, 400, "text/plain; charset=utf-8",
                    "Backup config is invalid: " + e.getMessage() + "\n");
            return;
        }
        writeConfigFile(backupText);
        log.info("Rolled back config from LAN management page");
        writeText(output, 200, "text/plain; charset=utf-8", "Rolled back config.json\n");
    }

    private void handleDeleteMedia(HttpRequest request, OutputStream output) throws IOException {
        String safeName = sanitizeRelativeMediaPath(queryValue(request.query, "name"));
        if (!isValidMediaRequestName(safeName)) {
            writeText(output, 400, "text/plain; charset=utf-8", "Invalid media file name\n");
            return;
        }
        File target = resolveMediaFile(safeName);
        if (target == null || !target.exists() || !target.isFile()) {
            writeText(output, 404, "text/plain; charset=utf-8", "Media file not found\n");
            return;
        }
        if (configReferencesMedia(safeName)) {
            writeText(output, 400, "text/plain; charset=utf-8",
                    "Media file is still referenced by config.json\n");
            return;
        }
        if (!target.delete()) {
            writeText(output, 400, "text/plain; charset=utf-8", "Could not delete media file\n");
            return;
        }
        mediaInfoCache.remove(safeName);
        log.info("Deleted media file from LAN: " + target.getAbsolutePath());
        writeText(output, 200, "text/plain; charset=utf-8", "Deleted " + safeName + "\n");
    }

    private void handleRenameMedia(HttpRequest request, OutputStream output) throws IOException {
        String fromName = sanitizeRelativeMediaPath(queryValue(request.query, "from"));
        String toName = sanitizeRelativeMediaPath(queryValue(request.query, "to"));
        if (!isValidMediaRequestName(fromName) || !isValidMediaRequestName(toName)) {
            writeText(output, 400, "text/plain; charset=utf-8", "Invalid media file name\n");
            return;
        }
        File fromFile = resolveMediaFile(fromName);
        File toFile = resolveMediaFile(toName);
        if (fromFile == null || !fromFile.exists() || !fromFile.isFile()) {
            writeText(output, 404, "text/plain; charset=utf-8", "Source media file not found\n");
            return;
        }
        if (toFile == null) {
            writeText(output, 400, "text/plain; charset=utf-8", "Invalid target media path\n");
            return;
        }
        File toParent = toFile.getParentFile();
        if (toParent != null && !toParent.exists() && !toParent.mkdirs()) {
            writeText(output, 400, "text/plain; charset=utf-8", "Could not create target folder\n");
            return;
        }
        if (toFile.exists()) {
            writeText(output, 400, "text/plain; charset=utf-8", "Target media file already exists\n");
            return;
        }
        if (!fromFile.renameTo(toFile)) {
            writeText(output, 400, "text/plain; charset=utf-8", "Could not rename media file\n");
            return;
        }

        try {
            if (configFile.exists()) {
                String updatedConfig = replaceMediaReferencesInConfig(fromName, toName);
                if (updatedConfig != null) {
                    configLoader.loadFromText(updatedConfig);
                    writeConfigFile(updatedConfig);
                }
            }
        } catch (Exception e) {
            toFile.renameTo(fromFile);
            writeText(output, 400, "text/plain; charset=utf-8",
                    "Renamed file was rolled back because config update failed: " + e.getMessage() + "\n");
            return;
        }

        mediaInfoCache.remove(fromName);
        mediaInfoCache.remove(toName);
        log.info("Renamed media file from LAN: " + fromName + " -> " + toName);
        writeText(output, 200, "text/plain; charset=utf-8", "Renamed " + fromName + " to " + toName + "\n");
    }


    private boolean configReferencesMedia(String fileName) {
        if (!configFile.exists()) {
            return false;
        }
        try {
            JSONObject root = new JSONObject(readFullTextFile(configFile));
            return jsonContainsFile(root, "media/" + fileName);
        } catch (Exception e) {
            log.error("Could not check config media references", e);
            return true;
        }
    }

    private boolean jsonContainsFile(Object value, String filePath) throws JSONException {
        if (value instanceof JSONObject) {
            JSONObject object = (JSONObject) value;
            JSONArray names = object.names();
            if (names == null) {
                return false;
            }
            for (int i = 0; i < names.length(); i++) {
                String name = names.getString(i);
                Object child = object.get(name);
                if ("file".equals(name) && filePath.equals(child)) {
                    return true;
                }
                if (jsonContainsFile(child, filePath)) {
                    return true;
                }
            }
        } else if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            for (int i = 0; i < array.length(); i++) {
                if (jsonContainsFile(array.get(i), filePath)) {
                    return true;
                }
            }
        }
        return false;
    }

    private String replaceMediaReferencesInConfig(String fromName, String toName) throws IOException, JSONException {
        JSONObject root = new JSONObject(readFullTextFile(configFile));
        boolean changed = replaceFileValue(root, "media/" + fromName, "media/" + toName);
        return changed ? root.toString(2) + "\n" : null;
    }

    private boolean replaceFileValue(Object value, String fromPath, String toPath) throws JSONException {
        boolean changed = false;
        if (value instanceof JSONObject) {
            JSONObject object = (JSONObject) value;
            JSONArray names = object.names();
            if (names == null) {
                return false;
            }
            for (int i = 0; i < names.length(); i++) {
                String name = names.getString(i);
                Object child = object.get(name);
                if ("file".equals(name) && fromPath.equals(child)) {
                    object.put(name, toPath);
                    changed = true;
                } else if (replaceFileValue(child, fromPath, toPath)) {
                    changed = true;
                }
            }
        } else if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            for (int i = 0; i < array.length(); i++) {
                if (replaceFileValue(array.get(i), fromPath, toPath)) {
                    changed = true;
                }
            }
        }
        return changed;
    }

    private String readFullTextFile(File file) throws IOException {
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
    private void handleUpload(HttpRequest request, InputStream input, OutputStream output) throws IOException {
        if (request.contentLength <= 0L) {
            writeText(output, 400, "text/plain; charset=utf-8", "Missing request body\n");
            return;
        }
        if (request.contentLength > MAX_UPLOAD_BYTES) {
            writeText(output, 413, "text/plain; charset=utf-8", "File too large\n");
            return;
        }

        String name = queryValue(request.query, "name");
        String folder = sanitizeFolderPath(queryValue(request.query, "folder"));
        String safeName = sanitizeUploadFileName(name);
        String relativeName = folder.length() > 0 ? folder + "/" + safeName : safeName;
        if (safeName.length() == 0) {
            writeText(output, 400, "text/plain; charset=utf-8", "Missing file name\n");
            return;
        }
        if (!isSupportedMediaName(relativeName)) {
            writeText(output, 400, "text/plain; charset=utf-8",
                    "Unsupported file type. Use jpg, jpeg, png, or mp4.\n");
            return;
        }
        if (!mediaDir.exists()) {
            mediaDir.mkdirs();
        }

        File target = uniqueTargetFile(relativeName);
        FileOutputStream fileOutput = null;
        long remaining = request.contentLength;
        byte[] buffer = new byte[8192];
        try {
            fileOutput = new FileOutputStream(target);
            while (remaining > 0L) {
                int max = (int) Math.min(buffer.length, remaining);
                int read = input.read(buffer, 0, max);
                if (read == -1) {
                    throw new IOException("Upload ended early");
                }
                fileOutput.write(buffer, 0, read);
                remaining -= read;
            }
        } finally {
            if (fileOutput != null) {
                fileOutput.close();
            }
        }

        log.info("Uploaded media file from LAN: " + target.getAbsolutePath());
        writeText(output, 200, "text/plain; charset=utf-8",
                "Uploaded " + target.getName() + " (" + target.length() + " bytes)\n");
    }


    private void handleMediaPreview(HttpRequest request, OutputStream output) throws IOException {
        String safeName = sanitizeRelativeMediaPath(queryValue(request.query, "name"));
        if (!isValidMediaRequestName(safeName)) {
            writePreviewPlaceholder(output, "media");
            return;
        }
        File target = resolveMediaFile(safeName);
        if (target == null || !target.exists() || !target.isFile()) {
            writePreviewPlaceholder(output, "missing");
            return;
        }

        Bitmap bitmap = null;
        String lower = safeName.toLowerCase(Locale.US);
        try {
            if (lower.endsWith(".mp4")) {
                bitmap = decodeVideoPreview(target);
            } else {
                bitmap = decodeImagePreview(target);
            }
            if (bitmap == null) {
                writePreviewPlaceholder(output, lower.endsWith(".mp4") ? "video" : "image");
                return;
            }
            writeBitmapPreview(output, bitmap);
        } catch (RuntimeException e) {
            log.error("Could not create media preview: " + safeName, e);
            writePreviewPlaceholder(output, lower.endsWith(".mp4") ? "video" : "image");
        } finally {
            if (bitmap != null && !bitmap.isRecycled()) {
                bitmap.recycle();
            }
        }
    }

    private Bitmap decodeImagePreview(File file) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            return null;
        }
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = previewSampleSize(bounds.outWidth, bounds.outHeight);
        return scalePreview(BitmapFactory.decodeFile(file.getAbsolutePath(), options));
    }

    private Bitmap decodeVideoPreview(File file) {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(file.getAbsolutePath());
            return scalePreview(retriever.getFrameAtTime(0));
        } finally {
            try {
                retriever.release();
            } catch (RuntimeException ignored) {
            }
        }
    }

    private int previewSampleSize(int width, int height) {
        int sampleSize = 1;
        while ((width / sampleSize) > 360 || (height / sampleSize) > 240) {
            sampleSize *= 2;
        }
        return sampleSize;
    }

    private Bitmap scalePreview(Bitmap bitmap) {
        if (bitmap == null) {
            return null;
        }
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        if (width <= 0 || height <= 0 || (width <= 360 && height <= 240)) {
            return bitmap;
        }
        float scale = Math.min(360f / width, 240f / height);
        int targetWidth = Math.max(1, Math.round(width * scale));
        int targetHeight = Math.max(1, Math.round(height * scale));
        Bitmap scaled = Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true);
        if (scaled != bitmap) {
            bitmap.recycle();
        }
        return scaled;
    }

    private void writeBitmapPreview(OutputStream output, Bitmap bitmap) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.JPEG, 78, buffer);
        writeBytes(output, 200, "image/jpeg", buffer.toByteArray());
    }

    private void writePreviewPlaceholder(OutputStream output, String label) throws IOException {
        String body = "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"180\" height=\"120\" viewBox=\"0 0 180 120\">"
                + "<rect width=\"180\" height=\"120\" fill=\"#11161c\"/>"
                + "<rect x=\"0.5\" y=\"0.5\" width=\"179\" height=\"119\" fill=\"none\" stroke=\"#3d4652\"/>"
                + "<text x=\"90\" y=\"64\" text-anchor=\"middle\" font-family=\"Arial,sans-serif\" font-size=\"18\" fill=\"#9aa7b2\">"
                + escapeXml(label) + "</text></svg>";
        writeText(output, 200, "image/svg+xml; charset=utf-8", body);
    }

    private String escapeXml(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private File uniqueTargetFile(String relativePath) throws IOException {
        File target = resolveMediaFile(relativePath);
        if (target == null) {
            throw new IOException("Invalid media path");
        }
        File parent = target.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("Could not create media folder");
        }
        if (!target.exists()) {
            return target;
        }

        int slash = relativePath.lastIndexOf('/');
        String folder = slash >= 0 ? relativePath.substring(0, slash + 1) : "";
        String fileName = slash >= 0 ? relativePath.substring(slash + 1) : relativePath;
        int dot = fileName.lastIndexOf('.');
        String base = dot > 0 ? fileName.substring(0, dot) : fileName;
        String ext = dot > 0 ? fileName.substring(dot) : "";
        for (int i = 1; i < 1000; i++) {
            target = resolveMediaFile(folder + base + "-" + i + ext);
            if (target != null && !target.exists()) {
                return target;
            }
        }
        target = resolveMediaFile(folder + base + "-" + System.currentTimeMillis() + ext);
        if (target == null) {
            throw new IOException("Invalid media path");
        }
        return target;
    }

    private String sanitizeRelativeMediaPath(String value) {
        if (value == null) {
            return "";
        }
        String normalized = value.replace('\\', '/').trim();
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        if (normalized.length() == 0 || normalized.indexOf('\0') >= 0 || normalized.indexOf(':') >= 0) {
            return "";
        }
        String[] parts = normalized.split("/");
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            String part = parts[i].trim();
            if (part.length() == 0 || ".".equals(part) || "..".equals(part) || hasIllegalPathChar(part)) {
                return "";
            }
            if (builder.length() > 0) {
                builder.append('/');
            }
            builder.append(part);
        }
        return builder.toString();
    }

    private boolean hasIllegalPathChar(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < 32 || c == '*' || c == '?' || c == '"' || c == '<' || c == '>' || c == '|') {
                return true;
            }
        }
        return false;
    }

    private String sanitizeFolderPath(String value) {
        if (value == null) {
            return "";
        }
        String normalized = value.replace('\\', '/').trim();
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        if (normalized.length() == 0) {
            return "";
        }
        String[] parts = normalized.split("/");
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            String segment = sanitizePathSegment(parts[i]);
            if (segment.length() == 0) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append('/');
            }
            builder.append(segment);
        }
        return builder.toString();
    }

    private String sanitizeUploadFileName(String value) {
        if (value == null) {
            return "";
        }
        String name = value.replace('\\', '/');
        int slash = name.lastIndexOf('/');
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        return sanitizePathSegment(name);
    }

    private String sanitizePathSegment(String value) {
        String trimmed = value == null ? "" : value.trim();
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            if (c == '/' || c == '\\' || c == ':' || c == '*' || c == '?' || c == '"'
                    || c == '<' || c == '>' || c == '|' || c < 32) {
                builder.append('_');
            } else {
                builder.append(c);
            }
        }
        String result = builder.toString();
        if (".".equals(result) || "..".equals(result)) {
            return "_";
        }
        return result;
    }

    private File resolveMediaFile(String relativePath) throws IOException {
        if (relativePath == null || relativePath.length() == 0) {
            return null;
        }
        File root = mediaDir.getCanonicalFile();
        File target = new File(root, relativePath).getCanonicalFile();
        String rootPath = root.getPath();
        String targetPath = target.getPath();
        if (!targetPath.equals(rootPath) && !targetPath.startsWith(rootPath + File.separator)) {
            return null;
        }
        return target;
    }

    private String relativeMediaPath(File file) throws IOException {
        File root = mediaDir.getCanonicalFile();
        File target = file.getCanonicalFile();
        String rootPath = root.getPath();
        String targetPath = target.getPath();
        if (targetPath.startsWith(rootPath + File.separator)) {
            return targetPath.substring(rootPath.length() + 1).replace(File.separatorChar, '/');
        }
        return file.getName();
    }

    private boolean isValidMediaRequestName(String fileName) {
        if (fileName.length() == 0 || !isSupportedMediaName(fileName)) {
            return false;
        }
        try {
            return resolveMediaFile(fileName) != null;
        } catch (IOException e) {
            return false;
        }
    }

    private boolean isSupportedMediaName(String fileName) {
        String lower = fileName.toLowerCase(Locale.US);
        return lower.endsWith(".jpg") || lower.endsWith(".jpeg")
                || lower.endsWith(".png") || lower.endsWith(".mp4");
    }

    private String queryValue(String query, String name) {
        String[] pairs = query.split("&");
        for (int i = 0; i < pairs.length; i++) {
            int equals = pairs[i].indexOf('=');
            if (equals <= 0) {
                continue;
            }
            String key = decode(pairs[i].substring(0, equals));
            if (name.equals(key)) {
                return decode(pairs[i].substring(equals + 1));
            }
        }
        return "";
    }

    private String decode(String value) {
        try {
            return URLDecoder.decode(value, "UTF-8");
        } catch (IOException e) {
            return value;
        }
    }

    private String listMediaFiles() {
        JSONArray array = new JSONArray();
        if (!mediaDir.exists()) {
            return array.toString() + "\n";
        }
        List<File> files = new ArrayList<File>();
        collectMediaFiles(mediaDir, files);
        Collections.sort(files, new Comparator<File>() {
            @Override
            public int compare(File left, File right) {
                try {
                    return relativeMediaPath(left).compareToIgnoreCase(relativeMediaPath(right));
                } catch (IOException e) {
                    return left.getName().compareToIgnoreCase(right.getName());
                }
            }
        });
        List<String> references = readReferencedMediaPaths();
        for (int i = 0; i < files.size(); i++) {
            File file = files.get(i);
            try {
                String path = relativeMediaPath(file);
                if (!isSupportedMediaName(path)) {
                    continue;
                }
                array.put(mediaFileJson(file, path, references.contains("media/" + path)));
            } catch (Exception e) {
                log.error("Could not describe media file: " + file.getAbsolutePath(), e);
            }
        }
        return array.toString() + "\n";
    }

    private void collectMediaFiles(File dir, List<File> files) {
        File[] children = dir.listFiles();
        if (children == null) {
            return;
        }
        for (int i = 0; i < children.length; i++) {
            File child = children[i];
            if (child.isDirectory()) {
                collectMediaFiles(child, files);
            } else if (child.isFile() && isSupportedMediaName(child.getName())) {
                files.add(child);
            }
        }
    }

    private JSONObject mediaFileJson(File file, String path, boolean referenced) throws JSONException {
        JSONObject object = new JSONObject();
        int slash = path.lastIndexOf('/');
        String folder = slash >= 0 ? path.substring(0, slash) : "";
        String name = slash >= 0 ? path.substring(slash + 1) : path;
        boolean video = path.toLowerCase(Locale.US).endsWith(".mp4");
        CachedMediaInfo cached = cachedMediaInfo(path, file, video);
        object.put("path", path);
        object.put("folder", folder);
        object.put("name", name);
        object.put("type", video ? "video" : "image");
        object.put("sizeBytes", file.length());
        object.put("size", formatBytes(file.length()));
        object.put("modified", file.lastModified());
        object.put("referenced", referenced);
        object.put("metadata", mediaMetadataJson(cached.result));
        JSONArray warnings = new JSONArray();
        for (int i = 0; i < cached.result.warnings.size(); i++) {
            warnings.put(cached.result.warnings.get(i));
        }
        object.put("warnings", warnings);
        return object;
    }

    private JSONObject mediaMetadataJson(MediaInspector.Result result) throws JSONException {
        JSONObject metadata = new JSONObject();
        metadata.put("width", result.width);
        metadata.put("height", result.height);
        metadata.put("durationMs", result.durationMs);
        metadata.put("fps", Math.round(result.fps * 100f) / 100f);
        metadata.put("codec", result.codec);
        metadata.put("profile", result.profile);
        metadata.put("level", result.level);
        metadata.put("mime", result.mime);
        return metadata;
    }

    private CachedMediaInfo cachedMediaInfo(String path, File file, boolean video) {
        CachedMediaInfo cached = mediaInfoCache.get(path);
        if (cached != null && cached.length == file.length() && cached.lastModified == file.lastModified()) {
            return cached;
        }
        cached = new CachedMediaInfo();
        cached.length = file.length();
        cached.lastModified = file.lastModified();
        cached.result = mediaInspector.inspect(file, video);
        mediaInfoCache.put(path, cached);
        return cached;
    }

    private String formatBytes(long bytes) {
        if (bytes >= 1024L * 1024L * 1024L) {
            return String.format(Locale.US, "%.1f GB", bytes / 1024f / 1024f / 1024f);
        }
        if (bytes >= 1024L * 1024L) {
            return String.format(Locale.US, "%.1f MB", bytes / 1024f / 1024f);
        }
        if (bytes >= 1024L) {
            return String.format(Locale.US, "%.1f KB", bytes / 1024f);
        }
        return bytes + " bytes";
    }

    private List<String> readReferencedMediaPaths() {
        List<String> paths = new ArrayList<String>();
        if (!configFile.exists()) {
            return paths;
        }
        try {
            collectFileValues(new JSONObject(readFullTextFile(configFile)), paths);
        } catch (Exception e) {
            log.error("Could not read config media references", e);
        }
        return paths;
    }

    private void collectFileValues(Object value, List<String> paths) throws JSONException {
        if (value instanceof JSONObject) {
            JSONObject object = (JSONObject) value;
            JSONArray names = object.names();
            if (names == null) {
                return;
            }
            for (int i = 0; i < names.length(); i++) {
                String name = names.getString(i);
                Object child = object.get(name);
                if ("file".equals(name) && child instanceof String) {
                    paths.add((String) child);
                }
                collectFileValues(child, paths);
            }
        } else if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            for (int i = 0; i < array.length(); i++) {
                collectFileValues(array.get(i), paths);
            }
        }
    }
    private String readTextFile(File file, int maxChars) {
        if (!file.exists()) {
            return "Missing file: " + file.getAbsolutePath() + "\n";
        }
        StringBuilder builder = new StringBuilder();
        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new FileReader(file));
            String line;
            while ((line = reader.readLine()) != null) {
                builder.append(line);
                builder.append('\n');
                if (builder.length() > maxChars) {
                    builder.delete(0, builder.length() - maxChars);
                }
            }
        } catch (IOException e) {
            return "Could not read file: " + e.getMessage() + "\n";
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (IOException ignored) {
                }
            }
        }
        return builder.toString();
    }

    private void writeText(OutputStream output, int status, String contentType, String body) throws IOException {
        byte[] bytes = body.getBytes("UTF-8");
        String reason = status == 200 ? "OK" : status == 400 ? "Bad Request"
                : status == 403 ? "Forbidden" : status == 404 ? "Not Found"
                : status == 413 ? "Payload Too Large" : "Error";
        String headers = "HTTP/1.1 " + status + " " + reason + "\r\n"
                + "Content-Type: " + contentType + "\r\n"
                + "Content-Length: " + bytes.length + "\r\n"
                + "Connection: close\r\n"
                + "Cache-Control: no-store\r\n"
                + "\r\n";
        output.write(headers.getBytes("UTF-8"));
        output.write(bytes);
    }


    private void writeBytes(OutputStream output, int status, String contentType, byte[] bytes) throws IOException {
        String reason = status == 200 ? "OK" : status == 400 ? "Bad Request"
                : status == 403 ? "Forbidden" : status == 404 ? "Not Found"
                : status == 413 ? "Payload Too Large" : "Error";
        String headers = "HTTP/1.1 " + status + " " + reason + "\r\n"
                + "Content-Type: " + contentType + "\r\n"
                + "Content-Length: " + bytes.length + "\r\n"
                + "Connection: close\r\n"
                + "Cache-Control: no-store\r\n"
                + "\r\n";
        output.write(headers.getBytes("UTF-8"));
        output.write(bytes);
    }

    private String buildHomePage() {
        StringBuilder html = new StringBuilder();
        html.append("<!doctype html>\n");
        html.append("<html><head><meta charset='utf-8'>");
        html.append("<meta name='viewport' content='width=device-width,initial-scale=1'>");
        html.append("<title>Simple Kiosk</title>");
        html.append("<style>");
        html.append("body{font-family:Arial,sans-serif;background:#0f1115;color:#e8edf2;margin:0;padding:16px;}h1{font-size:24px;margin:0 0 14px;color:#fff;}h2{font-size:17px;margin:0 0 10px;color:#f7fafc;}label{display:block;color:#9aa7b2;font-size:12px;margin-bottom:3px;}");
        html.append(".grid{display:grid;grid-template-columns:minmax(380px,1fr) minmax(430px,1.25fr);gap:12px;}@media(max-width:980px){.grid{grid-template-columns:1fr;}.schedule-row,.schedule-playlist-row{grid-template-columns:1fr!important;}.media-tools{grid-template-columns:1fr 1fr!important;}.media-browser{grid-template-columns:1fr!important;}.folder-pane{max-height:220px;}}");
        html.append("section{background:#1a1f26;border:1px solid #303842;padding:14px;margin:0 0 12px;box-shadow:0 1px 0 #0a0c0f;}button,input,select{font-size:14px;margin:2px;padding:7px;border:1px solid #3d4652;background:#11161c;color:#edf2f7;}button{background:#2b6fd6;color:white;border-color:#2b6fd6;}button.secondary{background:#303944;border-color:#46515f;}button.danger{background:#69343a;border-color:#8c454f;}button:disabled{opacity:.45;}button:active{filter:brightness(1.15);}");
        html.append(".media-tools{display:grid;grid-template-columns:1fr .55fr .65fr auto;gap:8px;align-items:end;margin:8px 0;}.media-actions{display:flex;flex-wrap:wrap;gap:6px;margin:8px 0;}.media-table-wrap{max-width:100%;overflow-x:auto;border:1px solid #303942;}table{width:100%;border-collapse:collapse;font-size:13px;}table.media-table{table-layout:fixed;min-width:920px;}th,td{border-top:1px solid #303942;padding:7px;vertical-align:middle;text-align:left;min-width:0;}th{color:#aeb9c5;font-weight:normal;background:#151a20;position:sticky;top:0;}tr.risk-row{background:#25171a;}.thumb{width:64px;height:44px;object-fit:cover;background:#11161c;border:1px solid #3d4652;}.file-name{font-weight:bold;color:#fff;overflow-wrap:anywhere;word-break:break-word;}.file-path,.folder-cell,.metadata-cell,.status-cell{overflow-wrap:anywhere;word-break:break-word;}.folder-cell{color:#b8c7d6;}.actions-cell button{margin:2px;}.risk{color:#ffb3b3;font-weight:bold;}.ok{color:#9fe3b1;}.pager{display:flex;gap:8px;align-items:center;justify-content:flex-end;margin-top:8px;}");
        html.append(".media-browser{display:grid;grid-template-columns:220px minmax(0,1fr);gap:12px;margin-top:8px;}.folder-pane{background:#11161c;border:1px solid #303842;max-height:520px;overflow:auto;padding:6px;}.folder-button{display:grid;grid-template-columns:1fr auto;gap:8px;width:100%;margin:0 0 3px;padding:8px;border:0;background:transparent;color:#cdd7e0;text-align:left;}.folder-button:hover{background:#202832;}.folder-button.active{background:#244a7a;color:#fff;}.folder-name{overflow:hidden;text-overflow:ellipsis;white-space:nowrap;}.folder-count{color:#9aa7b2;font-size:12px;}.folder-button.active .folder-count{color:#d7e8ff;}.folder-indent{padding-left:calc(var(--depth)*14px);}.breadcrumb{display:flex;flex-wrap:wrap;gap:5px;align-items:center;margin:4px 0 8px;}.crumb{background:#26313b;border:1px solid #3b4652;color:#dbe7f0;padding:5px 8px;}.crumb.active{background:#2b6fd6;border-color:#2b6fd6;color:#fff;}.current-folder{color:#dbe7f0;font-size:13px;margin:3px 0 0;}");
        html.append(".media-item,.schedule-playlist-row{display:grid;grid-template-columns:72px 1fr auto;gap:10px;align-items:center;border-top:1px solid #303942;padding:10px 0;}.schedule-playlist-row{grid-template-columns:72px 1.2fr .55fr .65fr .65fr auto;}.schedule-row{display:grid;grid-template-columns:1fr .55fr .55fr .65fr .8fr auto;gap:10px;align-items:end;border-top:1px solid #303942;padding:10px 0;}.schedule-card{border-top:1px solid #303942;padding-top:8px;margin-top:8px;}.schedule-card.active{outline:2px solid #2b6fd6;outline-offset:3px;}");
        html.append(".muted{color:#9aa7b2;font-size:13px;}.pill{display:inline-block;background:#26313b;color:#cbd6df;padding:5px 8px;border:1px solid #3b4652;font-size:13px;}pre{white-space:pre-wrap;overflow:auto;max-height:240px;background:#0d1116;border:1px solid #29313a;padding:10px;}progress{width:100%;height:12px;}.bar{display:flex;gap:8px;flex-wrap:wrap;align-items:center;margin:10px 0;}.toolbar-note{color:#a7b4c0;font-size:13px;margin:4px 0 8px;}.mini-title{font-size:13px;color:#cbd6df;margin:8px 0 4px;}");
        html.append("</style></head><body>");
        html.append("<h1>Simple Kiosk</h1>");
        html.append("<div id='keepAndroidOpenBanner'></div>");
        html.append("<script src='https://keepandroidopen.org/banner.js?size=minimal&id=keepAndroidOpenBanner&animation=off'></script>");
        html.append("<section><h2>Access</h2><div class='toolbar-note'>LAN access is protected by the tablet code by default. Disable it only on a trusted local network.</div><div id='accessState'></div><div class='bar'><button class='secondary' onclick='setAccess(false)'>Disable protection</button><button class='secondary' onclick='setAccess(true)'>Enable protection</button></div></section>");
        html.append("<div class='grid'><div>");
        html.append("<section><h2>Upload media</h2><div class='toolbar-note'>Leave target folder empty to upload into the current folder shown in the media library.</div><label>Target folder</label><input id='uploadFolder' placeholder='Use current folder or type A-role/subset'><div id='uploadFolderHint' class='current-folder'></div><input id='file' type='file' multiple accept='image/png,image/jpeg,video/mp4'><button onclick='upload()'>Upload selected</button><progress id='progress' max='100' value='0'></progress><pre id='uploadResult'></pre></section>");
        html.append("<section><h2>Media library</h2><div class='toolbar-note'>Browse folders like a file manager. High-risk videos are warned but can still be used.</div><div class='media-tools'><div><label>Search</label><input id='mediaSearch' oninput='mediaPage=0;renderMedia()' placeholder='name or folder'></div><div><label>Type</label><select id='mediaType' onchange='mediaPage=0;renderMedia()'><option value='all'>all</option><option value='image'>image</option><option value='video'>video</option></select></div><div><label>Risk</label><select id='mediaRisk' onchange='mediaPage=0;renderMedia()'><option value='all'>all</option><option value='risk'>risk only</option><option value='ok'>ok only</option></select></div><button class='secondary' onclick='loadMedia()'>Refresh</button></div><div class='media-browser'><div><div class='mini-title'>Folders</div><div id='folderTree' class='folder-pane'></div></div><div><div id='mediaBreadcrumb' class='breadcrumb'></div><div class='media-actions'><button class='secondary' onclick='batchAddSelected()'>Add selected</button><button class='secondary' onclick='addCurrentFolderToSchedule()'>Add current folder</button><button class='secondary' onclick='setUploadFolderToCurrent()'>Upload here</button><button class='danger' onclick='batchDeleteSelected()'>Delete selected</button></div><div id='mediaList'></div><div class='pager'><button class='secondary' onclick='mediaPage--;renderMedia()'>Prev</button><span id='mediaPageInfo'></span><button class='secondary' onclick='mediaPage++;renderMedia()'>Next</button></div></div></div><pre id='mediaResult'></pre></section>");
        html.append("<section><h2>Status</h2><pre id='status'></pre></section>");
        html.append("</div><div>");
        html.append("<section><h2>Schedules</h2><div class='toolbar-note'>Playback is schedule-first. Use an all-day playlist schedule for normal loop playback.</div><div class='bar'><button onclick='saveConfig()'>Save config</button><button class='secondary' onclick='reloadConfig()'>Reload config</button><button class='secondary' onclick='rollbackConfig()'>Rollback</button></div><div class='bar'><button onclick=\"addSchedule('playlist',true)\">Add all-day playlist</button><button class='secondary' onclick=\"addSchedule('playlist',false)\">Add timed playlist</button><button class='secondary' onclick=\"addSchedule('silent',false)\">Add silent schedule</button></div><div id='schedules'></div><pre id='saveResult'></pre></section>");
        html.append("<section><h2>Playlist presets</h2><div class='toolbar-note'>Presets are reusable playlist templates. Applying a preset copies it into the selected schedule.</div><div class='bar'><button class='secondary' onclick='saveActivePlaylistPreset()'>Save target as preset</button></div><div id='presets'></div><pre id='presetResult'></pre></section>");
        html.append("<section><h2>Config preview</h2><pre id='config'></pre></section><section><h2>Logs</h2><pre id='logs'></pre></section>");
        html.append("</div></div><script>");
        html.append("var ACCESS_TOKEN='").append(accessToken).append("';var ACCESS_ON=").append(accessProtectionEnabled ? "true" : "false").append(";var config=defaultConfig();var mediaFiles=[];var mediaPage=0;var pageSize=25;var activeSchedule=-1;var currentFolder='all';");
        html.append("function defaultConfig(){return {version:1,settings:{orientation:'landscape',fitMode:'contain',background:'#000000',keepScreenOn:true,hideSystemUi:true,mute:true},schedules:[],playlistPresets:[]};}");
        html.append("function api(u){if(!ACCESS_ON||!ACCESS_TOKEN)return u;return u+(u.indexOf('?')>=0?'&':'?')+'token='+encodeURIComponent(ACCESS_TOKEN);}");
        html.append("function text(u,id){fetch(api(u)).then(r=>r.text()).then(t=>document.getElementById(id).textContent=t).catch(e=>document.getElementById(id).textContent=e);}");
        html.append("function renderAccess(){var el=document.getElementById('accessState');if(el)el.textContent=ACCESS_ON?'Protected. Use the code shown on the tablet.':'Open on the local network.';}");
        html.append("function setAccess(on){fetch(api(on?'/access/enable':'/access/disable'),{method:'POST'}).then(r=>r.text()).then(t=>{ACCESS_ON=on;renderAccess();document.getElementById('saveResult').textContent=t;}).catch(e=>document.getElementById('saveResult').textContent=e);}");
        html.append("function typeFromName(n){var l=String(n||'').toLowerCase();return l.indexOf('.mp4',l.length-4)>=0?'video':'image';}");
        html.append("function mediaNameFromFile(file){var v=String(file||'');return v.indexOf('media/')==0?v.substring(6):v;}");
        html.append("function thumbForFile(file){return api('/media/preview?name='+encodeURIComponent(mediaNameFromFile(file)));}");
        html.append("function fitOptions(v,cls){var a=['contain','cover','stretch','center'];var s=`<select class=${cls}>`;for(var i=0;i<a.length;i++){s+=`<option value='${a[i]}' ${a[i]==v?'selected':''}>${a[i]}</option>`;}return s+'</select>';}");
        html.append("function mediaItemFromFile(f){var type=f.type||typeFromName(f.path||f.name);var item={type:type,file:'media/'+(f.path||f.name),fitMode:(config.settings&&config.settings.fitMode)||'contain'};if(type=='image')item.duration=8;return item;}");
        html.append("function copyPlaylist(list){return JSON.parse(JSON.stringify(list||[]));}");
        html.append("function normalizeConfig(){if(!config.schedules)config.schedules=[];if(!config.playlistPresets)config.playlistPresets=[];if(config.playlist&&config.playlist.length&&!config.schedules.length){config.schedules.push({name:'all-day',start:'00:00',end:'00:00',mode:'playlist',playlist:copyPlaylist(config.playlist)});}delete config.playlist;if(activeSchedule>=config.schedules.length)activeSchedule=-1;if(activeSchedule<0){for(var i=0;i<config.schedules.length;i++){if(config.schedules[i].mode!='silent'){activeSchedule=i;break;}}}}");
        html.append("function loadAll(){renderAccess();loadMedia();reloadConfig();text('/status','status');text('/logs','logs');}");
        html.append("function loadMedia(){fetch(api('/media')).then(r=>r.json()).then(j=>{mediaFiles=j||[];mediaPage=0;renderMedia();}).catch(e=>document.getElementById('mediaResult').textContent=e);}");
        html.append("function folderStats(){var stats={};function ensure(path){if(stats[path])return;var depth=path?path.split('/').length:0;var name=path?path.split('/').pop():'Root';stats[path]={path:path,name:name,count:0,depth:depth};}ensure('');for(var i=0;i<mediaFiles.length;i++){var folder=mediaFiles[i].folder||'';ensure(folder);stats[folder].count++;if(folder){var parts=folder.split('/');var p='';for(var j=0;j<parts.length;j++){p=p?p+'/'+parts[j]:parts[j];ensure(p);}}}var a=[];for(var k in stats)a.push(stats[k]);a.sort(function(x,y){if(x.path=='')return -1;if(y.path=='')return 1;return x.path.localeCompare(y.path);});return a;}");
        html.append("function setFolder(path){currentFolder=path;mediaPage=0;renderMedia();}");
        html.append("function renderFolderTree(){var el=document.getElementById('folderTree');if(!el)return;var fs=folderStats();var activeAll=currentFolder=='all'?' active':'';var h=`<button class='folder-button${activeAll}' onclick=\"setFolder('all')\"><span class=folder-name>All media</span><span class=folder-count>${mediaFiles.length}</span></button>`;for(var i=0;i<fs.length;i++){var f=fs[i];var active=currentFolder==f.path?' active':'';var label=f.path?f.name:'Root';var encoded=encodeURIComponent(f.path);h+=`<button class='folder-button${active} folder-indent' style='--depth:${f.depth}' title='${esc(f.path||'Root')}' onclick=\"setFolder(decodeURIComponent('${encoded}'))\"><span class=folder-name>${esc(label)}</span><span class=folder-count>${f.count}</span></button>`;}el.innerHTML=h;}");
        html.append("function renderBreadcrumb(){var el=document.getElementById('mediaBreadcrumb');if(!el)return;if(currentFolder=='all'){el.innerHTML='<span class=\"crumb active\">All media</span>';return;}var h=`<button class=\"crumb\" onclick=\"setFolder('all')\">All media</button>`;if(!currentFolder){el.innerHTML=h+' <span class=\"crumb active\">Root</span>';return;}h+=` <button class=\"crumb\" onclick=\"setFolder('')\">Root</button>`;var parts=currentFolder.split('/');var p='';for(var i=0;i<parts.length;i++){p=p?p+'/'+parts[i]:parts[i];var active=i==parts.length-1?' active':'';var encoded=encodeURIComponent(p);h+=` <button class='crumb${active}' onclick=\"setFolder(decodeURIComponent('${encoded}'))\">${esc(parts[i])}</button>`;}el.innerHTML=h;}");
        html.append("function syncUploadFolderHint(){var el=document.getElementById('uploadFolderHint');if(!el)return;var target=currentFolder=='all'?'':currentFolder;el.textContent=target?('Current folder: /media/'+target):'Current folder: /media root';}");
        html.append("function setUploadFolderToCurrent(){var input=document.getElementById('uploadFolder');if(input)input.value=currentFolder=='all'?'':currentFolder;syncUploadFolderHint();}");
        html.append("function filteredMedia(){var q=(document.getElementById('mediaSearch').value||'').toLowerCase();var type=document.getElementById('mediaType').value;var risk=document.getElementById('mediaRisk').value;var out=[];for(var i=0;i<mediaFiles.length;i++){var f=mediaFiles[i];var hay=((f.path||'')+' '+(f.folder||'')+' '+(f.name||'')).toLowerCase();if(q&&hay.indexOf(q)<0)continue;if(currentFolder!='all'&&(f.folder||'')!=currentFolder)continue;if(type!='all'&&f.type!=type)continue;var hasRisk=f.warnings&&f.warnings.length;if(risk=='risk'&&!hasRisk)continue;if(risk=='ok'&&hasRisk)continue;out.push({file:f,index:i});}return out;}");
        html.append("function metadataText(f){var m=f.metadata||{};var parts=[];if(m.width&&m.height)parts.push(m.width+'x'+m.height);if(m.durationMs)parts.push(Math.round(m.durationMs/1000)+'s');if(m.fps)parts.push(m.fps+'fps');if(m.codec)parts.push(m.codec);if(m.profile)parts.push(m.profile);if(m.level)parts.push('L'+m.level);return parts.join(' / ');}");
        html.append("function renderMedia(){renderFolderTree();renderBreadcrumb();syncUploadFolderHint();var el=document.getElementById('mediaList');var list=filteredMedia();var pages=Math.max(1,Math.ceil(list.length/pageSize));if(mediaPage<0)mediaPage=0;if(mediaPage>=pages)mediaPage=pages-1;var start=mediaPage*pageSize;var page=list.slice(start,start+pageSize);document.getElementById('mediaPageInfo').textContent=(list.length?((start+1)+'-'+(start+page.length)):'0')+' / '+list.length;if(!mediaFiles.length){el.innerHTML='<div class=muted>No media files</div>';return;}if(!page.length){el.innerHTML='<div class=muted>No matching media in this folder</div>';return;}var h='<div class=media-table-wrap><table class=media-table><colgroup><col style=width:34px><col style=width:78px><col style=width:120px><col><col style=width:70px><col style=width:82px><col style=width:135px><col style=width:150px><col style=width:135px></colgroup><thead><tr><th></th><th>Preview</th><th>Folder</th><th>Name</th><th>Type</th><th>Size</th><th>Metadata</th><th>Status</th><th>Actions</th></tr></thead><tbody>';for(var p=0;p<page.length;p++){var f=page[p].file;var i=page[p].index;var warn=(f.warnings||[]);var addSchedule=activeSchedule>=0?`<button class=secondary onclick='addMediaToSchedule(${i})'>Add</button>`:'<button class=secondary disabled>Add</button>';var rowClass=warn.length?'risk-row':'';var status=warn.length?'<span class=risk>'+esc(warn.join('; '))+'</span>':'<span class=ok>OK</span>';var used=f.referenced?' <span class=pill>used</span>':'';h+=`<tr class='${rowClass}'><td><input class=media-check type=checkbox value='${i}'></td><td><img class=thumb loading=lazy src='${thumbForFile(f.path)}'></td><td class=folder-cell>${esc(f.folder||'(root)')}</td><td><div class=file-name>${esc(f.name||'')}</div><div class='muted file-path'>${esc(f.path||'')}</div></td><td>${esc(f.type||'')}</td><td>${esc(f.size||'')}</td><td class=metadata-cell>${esc(metadataText(f))}</td><td class=status-cell>${status}${used}</td><td class=actions-cell>${addSchedule}<button class=secondary onclick='renameMedia(${i})'>Rename</button><button class=danger onclick='deleteMedia(${i})'>Delete</button></td></tr>`;}el.innerHTML=h+'</tbody></table></div>';}");
        html.append("function selectedMediaIndexes(){var boxes=document.querySelectorAll('.media-check:checked');var a=[];for(var i=0;i<boxes.length;i++)a.push(parseInt(boxes[i].value,10));return a;}");
        html.append("function reloadConfig(){fetch(api('/config')).then(r=>r.text()).then(t=>{try{config=JSON.parse(t);}catch(e){config=defaultConfig();document.getElementById('saveResult').textContent='No valid config loaded yet. Create schedules and save config.json.';}normalizeConfig();renderSchedules();renderMedia();preview();});}");
        html.append("function mediaPostPromise(url){return fetch(api(url),{method:'POST'}).then(r=>r.text().then(t=>({ok:r.ok,text:t})));}");
        html.append("function mediaPost(url,msgId){mediaPostPromise(url).then(x=>{document.getElementById(msgId||'mediaResult').textContent=x.text;loadMedia();reloadConfig();}).catch(e=>document.getElementById(msgId||'mediaResult').textContent=e);}");
        html.append("function renameMedia(i){var oldName=mediaFiles[i].path||mediaFiles[i].name;var name=prompt('New relative path',oldName);if(!name||name==oldName)return;mediaPost('/media/rename?from='+encodeURIComponent(oldName)+'&to='+encodeURIComponent(name),'mediaResult');}");
        html.append("function deleteMedia(i){var name=mediaFiles[i].path||mediaFiles[i].name;if(!confirm('Delete '+name+'?'))return;mediaPost('/media/delete?name='+encodeURIComponent(name),'mediaResult');}");
        html.append("function addMediaToSchedule(i){collectSchedules();if(activeSchedule<0||!config.schedules[activeSchedule]||config.schedules[activeSchedule].mode=='silent'){document.getElementById('mediaResult').textContent='Select a playlist schedule first';return;}if(!config.schedules[activeSchedule].playlist)config.schedules[activeSchedule].playlist=[];config.schedules[activeSchedule].playlist.push(mediaItemFromFile(mediaFiles[i]));renderSchedules();preview();}");
        html.append("function batchAddSelected(){var ids=selectedMediaIndexes();if(!ids.length){document.getElementById('mediaResult').textContent='Select media first';return;}for(var i=0;i<ids.length;i++)addMediaToSchedule(ids[i]);document.getElementById('mediaResult').textContent='Added '+ids.length+' items to target schedule';}");
        html.append("function addCurrentFolderToSchedule(){if(currentFolder=='all'){document.getElementById('mediaResult').textContent='Open a specific folder first';return;}var list=filteredMedia();var count=0;for(var i=0;i<list.length;i++){addMediaToSchedule(list[i].index);count++;}document.getElementById('mediaResult').textContent='Added '+count+' items from '+(currentFolder||'root');}");
        html.append("function batchDeleteSelected(){var ids=selectedMediaIndexes();if(!ids.length){document.getElementById('mediaResult').textContent='Select media first';return;}if(!confirm('Delete '+ids.length+' selected files? Referenced files will be refused.'))return;var out=[];function next(k){if(k>=ids.length){document.getElementById('mediaResult').textContent=out.join('\\n');loadMedia();reloadConfig();return;}var f=mediaFiles[ids[k]];mediaPostPromise('/media/delete?name='+encodeURIComponent(f.path||f.name)).then(x=>{out.push((x.ok?'OK ':'FAIL ')+(f.path||f.name)+': '+x.text.trim());next(k+1);}).catch(e=>{out.push('FAIL '+(f.path||f.name)+': '+e);next(k+1);});}next(0);}");
        html.append("function addSchedule(mode,allDay){collectSchedules();if(!config.schedules)config.schedules=[];var s={name:allDay?'all-day':mode+'-'+(config.schedules.length+1),start:allDay?'00:00':'08:00',end:allDay?'00:00':'18:00',mode:mode};if(mode=='silent')s.screen='allowSleep';else s.playlist=[];config.schedules.push(s);activeSchedule=mode=='playlist'?config.schedules.length-1:activeSchedule;renderSchedules();renderMedia();preview();}");
        html.append("function removeSchedule(i){collectSchedules();config.schedules.splice(i,1);if(activeSchedule==i)activeSchedule=-1;else if(activeSchedule>i)activeSchedule--;normalizeConfig();renderSchedules();renderMedia();preview();}");
        html.append("function selectSchedule(i){collectSchedules();activeSchedule=i;renderSchedules();renderMedia();preview();}");
        html.append("function duplicateSchedule(i){collectSchedules();var c=JSON.parse(JSON.stringify(config.schedules[i]));c.name=(c.name||'schedule')+' copy';config.schedules.splice(i+1,0,c);activeSchedule=i+1;renderSchedules();renderMedia();preview();}");
        html.append("function clearSchedulePlaylist(i){collectSchedules();config.schedules[i].playlist=[];activeSchedule=i;renderSchedules();preview();}");
        html.append("function renderSchedulePlaylist(si,s){if((s.mode||'playlist')=='silent')return '<div class=muted>Silent schedule</div>';var list=s.playlist||[];var h='<div class=mini-title>Playlist</div>';if(!list.length)return h+'<div class=muted>Empty playlist</div>';for(var j=0;j<list.length;j++){var it=list[j];var durationCell=it.type=='video'?'<span class=pill>Play to end</span>':`<input class=sp-duration type=number min=1 value='${it.duration||8}'>`;h+=`<div class=schedule-playlist-row data-j='${j}'><img class=thumb src='${thumbForFile(it.file)}'><div><div class=file-name>${j+1}. ${esc(it.file||'')}</div><div class=muted>${esc(it.type||'')}</div></div><div><label>Type</label><select class=sp-type onchange='collectSchedules();renderSchedules();preview();'><option value=image ${it.type=='image'?'selected':''}>image</option><option value=video ${it.type=='video'?'selected':''}>video</option></select></div><div><label>Duration</label>${durationCell}</div><div><label>Fit</label>${fitOptions(it.fitMode||'contain','sp-fit')}</div><div><button class=secondary onclick='moveScheduleItem(${si},${j},-1)'>Up</button><button class=secondary onclick='moveScheduleItem(${si},${j},1)'>Down</button><button class=danger onclick='removeScheduleItem(${si},${j})'>Remove</button></div></div>`;}return h;}");
        html.append("function renderSchedules(){var el=document.getElementById('schedules');var list=config.schedules||[];if(!list.length){el.innerHTML='<div class=muted>No schedules. Add an all-day playlist schedule for normal playback.</div>';renderPresets();return;}var h='';for(var i=0;i<list.length;i++){var s=list[i];var screen=s.screen||'allowSleep';var mode=s.mode||'playlist';var active=i==activeSchedule?' active':'';h+=`<div class='schedule-card${active}' data-i='${i}'><div class=schedule-row><div><label>Name</label><input class=sname value='${esc(s.name||('schedule-'+(i+1)))}' onchange='collectSchedules();preview()'></div><div><label>Start</label><input class=sstart value='${esc(s.start||'00:00')}' onchange='collectSchedules();preview()'></div><div><label>End</label><input class=send value='${esc(s.end||'00:00')}' onchange='collectSchedules();preview()'></div><div><label>Mode</label><select class=smode onchange='collectSchedules();renderSchedules();renderMedia();preview();'><option value=playlist ${mode=='playlist'?'selected':''}>playlist</option><option value=silent ${mode=='silent'?'selected':''}>silent</option></select></div><div><label>Screen</label><select class=sscreen onchange='collectSchedules();preview()'><option value=black ${screen=='black'?'selected':''}>black</option><option value=allowSleep ${screen=='allowSleep'?'selected':''}>allowSleep</option></select></div><div><button class=secondary onclick='selectSchedule(${i})'>Target</button><button class=secondary onclick='duplicateSchedule(${i})'>Duplicate</button><button class=secondary onclick='clearSchedulePlaylist(${i})'>Clear</button><button class=danger onclick='removeSchedule(${i})'>Remove</button><div class=muted>${((s.playlist&&s.playlist.length)||0)} items</div></div></div>${renderSchedulePlaylist(i,s)}</div>`;}el.innerHTML=h;renderPresets();}");
        html.append("function collectSchedules(){var cards=document.querySelectorAll('.schedule-card');if(!cards.length){if(!config.schedules)config.schedules=[];return;}var list=[];for(var i=0;i<cards.length;i++){var idx=parseInt(cards[i].getAttribute('data-i'),10);var old=config.schedules[idx]||{};var mode=cards[i].querySelector('.smode').value;var s={name:cards[i].querySelector('.sname').value||('schedule-'+(i+1)),start:cards[i].querySelector('.sstart').value||'00:00',end:cards[i].querySelector('.send').value||'00:00',mode:mode};if(mode=='silent'){s.screen=cards[i].querySelector('.sscreen').value||'allowSleep';}else{var oldList=old.playlist||[];var rows=cards[i].querySelectorAll('.schedule-playlist-row');var plist=[];for(var j=0;j<rows.length;j++){var oldItem=oldList[j]||{};var item={type:rows[j].querySelector('.sp-type').value,file:oldItem.file,fitMode:rows[j].querySelector('.sp-fit').value};if(item.type=='image'){var d=rows[j].querySelector('.sp-duration');item.duration=d?(parseInt(d.value,10)||8):(oldItem.duration||8);}plist.push(item);}s.playlist=plist;}list.push(s);}config.schedules=list;if(activeSchedule>=list.length)activeSchedule=-1;}");
        html.append("function renderPresets(){var el=document.getElementById('presets');if(!el)return;var list=config.playlistPresets||[];if(!list.length){el.innerHTML='<div class=muted>No playlist presets</div>';return;}var h='';for(var i=0;i<list.length;i++){var p=list[i];var count=(p.playlist&&p.playlist.length)||0;h+=`<div class=media-item><div></div><div><div class=file-name>${esc(p.name||('preset-'+(i+1)))}</div><div class=muted>${count} items</div></div><div><button class=secondary onclick='applyPreset(${i})'>Apply</button><button class=secondary onclick='renamePreset(${i})'>Rename</button><button class=danger onclick='deletePreset(${i})'>Delete</button></div></div>`;}el.innerHTML=h;}");
        html.append("function saveActivePlaylistPreset(){collectSchedules();if(activeSchedule<0||!config.schedules[activeSchedule]||config.schedules[activeSchedule].mode=='silent'){document.getElementById('presetResult').textContent='Select a playlist schedule first';return;}var list=config.schedules[activeSchedule].playlist||[];if(!list.length){document.getElementById('presetResult').textContent='Target playlist is empty';return;}var name=prompt('Preset name',config.schedules[activeSchedule].name||'playlist');if(!name)return;if(!config.playlistPresets)config.playlistPresets=[];var preset={name:name,playlist:copyPlaylist(list)};var replaced=false;for(var i=0;i<config.playlistPresets.length;i++){if(config.playlistPresets[i].name==name){if(!confirm('Replace preset '+name+'?'))return;config.playlistPresets[i]=preset;replaced=true;break;}}if(!replaced)config.playlistPresets.push(preset);document.getElementById('presetResult').textContent='Saved preset '+name;renderPresets();preview();}");
        html.append("function applyPreset(i){collectSchedules();if(activeSchedule<0||!config.schedules[activeSchedule]||config.schedules[activeSchedule].mode=='silent'){document.getElementById('presetResult').textContent='Select a playlist schedule first';return;}var p=(config.playlistPresets||[])[i];if(!p)return;config.schedules[activeSchedule].playlist=copyPlaylist(p.playlist||[]);document.getElementById('presetResult').textContent='Applied preset '+(p.name||'');renderSchedules();renderMedia();preview();}");
        html.append("function renamePreset(i){if(!config.playlistPresets||!config.playlistPresets[i])return;var oldName=config.playlistPresets[i].name||('preset-'+(i+1));var name=prompt('New preset name',oldName);if(!name||name==oldName)return;config.playlistPresets[i].name=name;renderPresets();preview();}");
        html.append("function deletePreset(i){if(!config.playlistPresets||!config.playlistPresets[i])return;var name=config.playlistPresets[i].name||('preset-'+(i+1));if(!confirm('Delete preset '+name+'?'))return;config.playlistPresets.splice(i,1);renderPresets();preview();}");
        html.append("function moveScheduleItem(si,j,d){collectSchedules();var list=config.schedules[si].playlist||[];var k=j+d;if(k<0||k>=list.length)return;var t=list[j];list[j]=list[k];list[k]=t;activeSchedule=si;renderSchedules();preview();}");
        html.append("function removeScheduleItem(si,j){collectSchedules();config.schedules[si].playlist.splice(j,1);activeSchedule=si;renderSchedules();preview();}");
        html.append("function rollbackConfig(){if(!confirm('Rollback to config.json.bak?'))return;fetch(api('/config/rollback'),{method:'POST'}).then(r=>r.text()).then(t=>{document.getElementById('saveResult').textContent=t;reloadConfig();}).catch(e=>document.getElementById('saveResult').textContent=e);}");
        html.append("function saveConfig(){collectSchedules();delete config.playlist;if(!config.version)config.version=1;if(!config.settings)config.settings={orientation:'landscape',fitMode:'contain',background:'#000000',keepScreenOn:true,hideSystemUi:true,mute:true};if(!config.playlistPresets)config.playlistPresets=[];if(!config.schedules||!config.schedules.length){document.getElementById('saveResult').textContent='Add at least one schedule';return;}var body=JSON.stringify(config,null,2);fetch(api('/config'),{method:'POST',headers:{'Content-Type':'application/json'},body:body}).then(r=>r.text().then(t=>({ok:r.ok,text:t}))).then(x=>{document.getElementById('saveResult').textContent=x.text;preview();text('/status','status');}).catch(e=>document.getElementById('saveResult').textContent=e);}");
        html.append("function preview(){var c=JSON.parse(JSON.stringify(config));delete c.playlist;document.getElementById('config').textContent=JSON.stringify(c,null,2);}");
        html.append("function upload(){var input=document.getElementById('file');var files=input.files;if(!files||!files.length){alert('Choose files');return;}var folder=(document.getElementById('uploadFolder').value||'').trim();if(!folder&&currentFolder!='all')folder=currentFolder;var progress=document.getElementById('progress');var result=document.getElementById('uploadResult');var ok=[];var fail=[];var i=0;progress.max=files.length;progress.value=0;function next(){if(i>=files.length){result.textContent='Uploaded '+ok.length+' / '+files.length+' files\\n'+ok.join('\\n')+(fail.length?'\\n\\nFailed\\n'+fail.join('\\n'):'');if(folder)currentFolder=folder;loadMedia();input.value='';return;}var f=files[i];result.textContent='Uploading '+(i+1)+' / '+files.length+': '+f.name;var x=new XMLHttpRequest();x.open('POST',api('/upload?name='+encodeURIComponent(f.name)+'&folder='+encodeURIComponent(folder)));x.onload=function(){if(x.status>=200&&x.status<300)ok.push(x.responseText.trim());else fail.push(f.name+': '+x.responseText.trim());i++;progress.value=i;next();};x.onerror=function(){fail.push(f.name+': network error');i++;progress.value=i;next();};x.send(f);}next();}");
        html.append("function esc(s){var d=document.createElement('div');d.textContent=String(s);return d.innerHTML;}");
        html.append("loadAll();");
        html.append("</script></body></html>\n");
        return html.toString();
    }

    private static final class CachedMediaInfo {
        long length;
        long lastModified;
        MediaInspector.Result result;
    }
    private static final class HttpRequest {
        String method;
        String path;
        String query;
        long contentLength = -1L;
    }
}
