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
import java.util.Locale;

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
                writeText(output, 200, "text/plain; charset=utf-8", listMediaFiles());
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
        String safeName = sanitizeFileName(queryValue(request.query, "name"));
        if (!isValidMediaRequestName(safeName)) {
            writeText(output, 400, "text/plain; charset=utf-8", "Invalid media file name\n");
            return;
        }
        File target = new File(mediaDir, safeName);
        if (!target.exists() || !target.isFile()) {
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
        log.info("Deleted media file from LAN: " + target.getAbsolutePath());
        writeText(output, 200, "text/plain; charset=utf-8", "Deleted " + safeName + "\n");
    }

    private void handleRenameMedia(HttpRequest request, OutputStream output) throws IOException {
        String fromName = sanitizeFileName(queryValue(request.query, "from"));
        String toName = sanitizeFileName(queryValue(request.query, "to"));
        if (!isValidMediaRequestName(fromName) || !isValidMediaRequestName(toName)) {
            writeText(output, 400, "text/plain; charset=utf-8", "Invalid media file name\n");
            return;
        }
        File fromFile = new File(mediaDir, fromName);
        File toFile = new File(mediaDir, toName);
        if (!fromFile.exists() || !fromFile.isFile()) {
            writeText(output, 404, "text/plain; charset=utf-8", "Source media file not found\n");
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

        log.info("Renamed media file from LAN: " + fromName + " -> " + toName);
        writeText(output, 200, "text/plain; charset=utf-8", "Renamed " + fromName + " to " + toName + "\n");
    }

    private boolean isValidMediaRequestName(String fileName) {
        return fileName.length() > 0 && isSupportedMediaName(fileName);
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
        String safeName = sanitizeFileName(name);
        if (safeName.length() == 0) {
            writeText(output, 400, "text/plain; charset=utf-8", "Missing file name\n");
            return;
        }
        if (!isSupportedMediaName(safeName)) {
            writeText(output, 400, "text/plain; charset=utf-8",
                    "Unsupported file type. Use jpg, jpeg, png, or mp4.\n");
            return;
        }
        if (!mediaDir.exists()) {
            mediaDir.mkdirs();
        }

        File target = uniqueTargetFile(safeName);
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
        String safeName = sanitizeFileName(queryValue(request.query, "name"));
        if (!isValidMediaRequestName(safeName)) {
            writePreviewPlaceholder(output, "media");
            return;
        }
        File target = new File(mediaDir, safeName);
        if (!target.exists() || !target.isFile()) {
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

    private File uniqueTargetFile(String fileName) {
        File target = new File(mediaDir, fileName);
        if (!target.exists()) {
            return target;
        }
        int dot = fileName.lastIndexOf('.');
        String base = dot > 0 ? fileName.substring(0, dot) : fileName;
        String ext = dot > 0 ? fileName.substring(dot) : "";
        for (int i = 1; i < 1000; i++) {
            target = new File(mediaDir, base + "-" + i + ext);
            if (!target.exists()) {
                return target;
            }
        }
        return new File(mediaDir, base + "-" + System.currentTimeMillis() + ext);
    }

    private String sanitizeFileName(String value) {
        if (value == null) {
            return "";
        }
        String name = value.replace('\\', '/');
        int slash = name.lastIndexOf('/');
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '.' || c == '-' || c == '_') {
                builder.append(c);
            } else {
                builder.append('_');
            }
        }
        return builder.toString();
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
        if (!mediaDir.exists()) {
            return "No media directory\n";
        }
        File[] files = mediaDir.listFiles();
        if (files == null || files.length == 0) {
            return "No media files\n";
        }
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < files.length; i++) {
            File file = files[i];
            if (file.isFile()) {
                builder.append(file.getName());
                builder.append("\t");
                builder.append(file.length());
                builder.append(" bytes\n");
            }
        }
        return builder.toString();
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
        html.append(".grid{display:grid;grid-template-columns:minmax(300px,1fr) minmax(420px,1.55fr);gap:12px;}@media(max-width:900px){.grid{grid-template-columns:1fr;}.schedule-row,.schedule-playlist-row{grid-template-columns:1fr!important;}}");
        html.append("section{background:#1a1f26;border:1px solid #303842;padding:14px;margin:0 0 12px;box-shadow:0 1px 0 #0a0c0f;}button,input,select{font-size:14px;margin:2px;padding:7px;border:1px solid #3d4652;background:#11161c;color:#edf2f7;}button{background:#2b6fd6;color:white;border-color:#2b6fd6;}button.secondary{background:#303944;border-color:#46515f;}button.danger{background:#69343a;border-color:#8c454f;}button:disabled{opacity:.45;}button:active{filter:brightness(1.15);}");
        html.append(".media-item,.schedule-playlist-row{display:grid;grid-template-columns:72px 1fr auto;gap:10px;align-items:center;border-top:1px solid #303942;padding:10px 0;}.thumb{width:72px;height:52px;object-fit:cover;background:#11161c;border:1px solid #3d4652;}.file-name{font-weight:bold;color:#fff;word-break:break-all;}");
        html.append(".schedule-playlist-row{grid-template-columns:72px 1.2fr .55fr .65fr .65fr auto;}.schedule-row{display:grid;grid-template-columns:1fr .55fr .55fr .65fr .8fr auto;gap:10px;align-items:end;border-top:1px solid #303942;padding:10px 0;}.schedule-card{border-top:1px solid #303942;padding-top:8px;margin-top:8px;}.schedule-card.active{outline:2px solid #2b6fd6;outline-offset:3px;}");
        html.append(".muted{color:#9aa7b2;font-size:13px;}.pill{display:inline-block;background:#26313b;color:#cbd6df;padding:5px 8px;border:1px solid #3b4652;font-size:13px;}pre{white-space:pre-wrap;overflow:auto;max-height:240px;background:#0d1116;border:1px solid #29313a;padding:10px;}progress{width:100%;height:12px;}.bar{display:flex;gap:8px;flex-wrap:wrap;align-items:center;margin:10px 0;}.toolbar-note{color:#a7b4c0;font-size:13px;margin:4px 0 8px;}.mini-title{font-size:13px;color:#cbd6df;margin:8px 0 4px;}");
        html.append("</style></head><body>");
        html.append("<h1>Simple Kiosk</h1>");
        html.append("<div id='keepAndroidOpenBanner'></div>");
        html.append("<script src='https://keepandroidopen.org/banner.js?size=minimal&id=keepAndroidOpenBanner&animation=off'></script>");
        html.append("<section><h2>Access</h2><div class='toolbar-note'>LAN access is protected by the tablet code by default. Disable it only on a trusted local network.</div><div id='accessState'></div><div class='bar'><button class='secondary' onclick='setAccess(false)'>Disable protection</button><button class='secondary' onclick='setAccess(true)'>Enable protection</button></div></section>");
        html.append("<div class='grid'><div>");
        html.append("<section><h2>Upload media</h2><input id='file' type='file' multiple accept='image/png,image/jpeg,video/mp4'><button onclick='upload()'>Upload selected</button><progress id='progress' max='100' value='0'></progress><pre id='uploadResult'></pre></section>");
        html.append("<section><h2>Media library</h2><button class='secondary' onclick='loadAll()'>Refresh</button><div id='mediaList'></div><pre id='mediaResult'></pre></section>");
        html.append("<section><h2>Status</h2><pre id='status'></pre></section>");
        html.append("</div><div>");
        html.append("<section><h2>Schedules</h2><div class='toolbar-note'>Playback is schedule-first. Use an all-day playlist schedule for normal loop playback.</div><div class='bar'><button onclick='saveConfig()'>Save config</button><button class='secondary' onclick='reloadConfig()'>Reload config</button><button class='secondary' onclick='rollbackConfig()'>Rollback</button></div><div class='bar'><button onclick=\"addSchedule('playlist',true)\">Add all-day playlist</button><button class='secondary' onclick=\"addSchedule('playlist',false)\">Add timed playlist</button><button class='secondary' onclick=\"addSchedule('silent',false)\">Add silent schedule</button></div><div id='schedules'></div><pre id='saveResult'></pre></section>");
        html.append("<section><h2>Playlist presets</h2><div class='toolbar-note'>Presets are reusable playlist templates. Applying a preset copies it into the selected schedule.</div><div class='bar'><button class='secondary' onclick='saveActivePlaylistPreset()'>Save target as preset</button></div><div id='presets'></div><pre id='presetResult'></pre></section>");
        html.append("<section><h2>Config preview</h2><pre id='config'></pre></section><section><h2>Logs</h2><pre id='logs'></pre></section>");
        html.append("</div></div><script>");
        html.append("var ACCESS_TOKEN='").append(accessToken).append("';var ACCESS_ON=").append(accessProtectionEnabled ? "true" : "false").append(";var config=defaultConfig();var mediaFiles=[];var activeSchedule=-1;");
        html.append("function defaultConfig(){return {version:1,settings:{orientation:'landscape',fitMode:'contain',background:'#000000',keepScreenOn:true,hideSystemUi:true,mute:true},schedules:[],playlistPresets:[]};}");
        html.append("function api(u){if(!ACCESS_ON||!ACCESS_TOKEN)return u;return u+(u.indexOf('?')>=0?'&':'?')+'token='+encodeURIComponent(ACCESS_TOKEN);}");
        html.append("function text(u,id){fetch(api(u)).then(r=>r.text()).then(t=>document.getElementById(id).textContent=t).catch(e=>document.getElementById(id).textContent=e);}");
        html.append("function renderAccess(){var el=document.getElementById('accessState');if(el)el.textContent=ACCESS_ON?'Protected. Use the code shown on the tablet.':'Open on the local network.';}");
        html.append("function setAccess(on){fetch(api(on?'/access/enable':'/access/disable'),{method:'POST'}).then(r=>r.text()).then(t=>{ACCESS_ON=on;renderAccess();document.getElementById('saveResult').textContent=t;}).catch(e=>document.getElementById('saveResult').textContent=e);}");
        html.append("function typeFromName(n){var l=String(n||'').toLowerCase();return l.indexOf('.mp4',l.length-4)>=0?'video':'image';}");
        html.append("function mediaNameFromFile(file){var v=String(file||'');return v.indexOf('media/')==0?v.substring(6):v;}");
        html.append("function thumbForFile(file){return api('/media/preview?name='+encodeURIComponent(mediaNameFromFile(file)));}");
        html.append("function fitOptions(v,cls){var a=['contain','cover','stretch','center'];var s=`<select class=${cls}>`;for(var i=0;i<a.length;i++){s+=`<option value='${a[i]}' ${a[i]==v?'selected':''}>${a[i]}</option>`;}return s+'</select>';}");
        html.append("function mediaItemFromFile(f){var type=typeFromName(f.name);var item={type:type,file:'media/'+f.name,fitMode:(config.settings&&config.settings.fitMode)||'contain'};if(type=='image')item.duration=8;return item;}");
        html.append("function copyPlaylist(list){return JSON.parse(JSON.stringify(list||[]));}");
        html.append("function normalizeConfig(){if(!config.schedules)config.schedules=[];if(!config.playlistPresets)config.playlistPresets=[];if(config.playlist&&config.playlist.length&&!config.schedules.length){config.schedules.push({name:'all-day',start:'00:00',end:'00:00',mode:'playlist',playlist:copyPlaylist(config.playlist)});}delete config.playlist;if(activeSchedule>=config.schedules.length)activeSchedule=-1;if(activeSchedule<0){for(var i=0;i<config.schedules.length;i++){if(config.schedules[i].mode!='silent'){activeSchedule=i;break;}}}}");
        html.append("function loadAll(){renderAccess();loadMedia();reloadConfig();text('/status','status');text('/logs','logs');}");
        html.append("function loadMedia(){fetch(api('/media')).then(r=>r.text()).then(t=>{mediaFiles=[];var lines=t.split(String.fromCharCode(10));for(var i=0;i<lines.length;i++){var p=lines[i].split(String.fromCharCode(9));if(p.length>=2)mediaFiles.push({name:p[0],size:p[1]});}renderMedia();});}");
        html.append("function renderMedia(){var el=document.getElementById('mediaList');if(!mediaFiles.length){el.innerHTML='<div class=muted>No media files</div>';return;}var h='';for(var i=0;i<mediaFiles.length;i++){var f=mediaFiles[i];var addSchedule=activeSchedule>=0?`<button class=secondary onclick='addMediaToSchedule(${i})'>Add to target</button>`:'<button class=secondary disabled>Add to target</button>';h+=`<div class=media-item><img class=thumb src='${api('/media/preview?name='+encodeURIComponent(f.name))}'><div><div class=file-name>${esc(f.name)}</div><div class=muted>${esc(f.size)}</div></div><div>${addSchedule}<button class=secondary onclick='renameMedia(${i})'>Rename</button><button class=danger onclick='deleteMedia(${i})'>Delete</button></div></div>`;}el.innerHTML=h;}");
        html.append("function reloadConfig(){fetch(api('/config')).then(r=>r.text()).then(t=>{try{config=JSON.parse(t);}catch(e){config=defaultConfig();document.getElementById('saveResult').textContent='No valid config loaded yet. Create schedules and save config.json.';}normalizeConfig();renderSchedules();renderMedia();preview();});}");
        html.append("function mediaPost(url,msgId){fetch(api(url),{method:'POST'}).then(r=>r.text().then(t=>({ok:r.ok,text:t}))).then(x=>{document.getElementById(msgId||'mediaResult').textContent=x.text;loadMedia();reloadConfig();}).catch(e=>document.getElementById(msgId||'mediaResult').textContent=e);}");
        html.append("function renameMedia(i){var oldName=mediaFiles[i].name;var name=prompt('New file name',oldName);if(!name||name==oldName)return;mediaPost('/media/rename?from='+encodeURIComponent(oldName)+'&to='+encodeURIComponent(name),'mediaResult');}");
        html.append("function deleteMedia(i){var name=mediaFiles[i].name;if(!confirm('Delete '+name+'?'))return;mediaPost('/media/delete?name='+encodeURIComponent(name),'mediaResult');}");
        html.append("function addMediaToSchedule(i){collectSchedules();if(activeSchedule<0||!config.schedules[activeSchedule]||config.schedules[activeSchedule].mode=='silent'){document.getElementById('mediaResult').textContent='Select a playlist schedule first';return;}if(!config.schedules[activeSchedule].playlist)config.schedules[activeSchedule].playlist=[];config.schedules[activeSchedule].playlist.push(mediaItemFromFile(mediaFiles[i]));renderSchedules();preview();}");
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
        html.append("function upload(){var input=document.getElementById('file');var files=input.files;if(!files||!files.length){alert('Choose files');return;}var progress=document.getElementById('progress');var result=document.getElementById('uploadResult');var ok=[];var fail=[];var i=0;progress.max=files.length;progress.value=0;function next(){if(i>=files.length){result.textContent='Uploaded '+ok.length+' / '+files.length+' files\\n'+ok.join('\\n')+(fail.length?'\\n\\nFailed\\n'+fail.join('\\n'):'');loadMedia();input.value='';return;}var f=files[i];result.textContent='Uploading '+(i+1)+' / '+files.length+': '+f.name;var x=new XMLHttpRequest();x.open('POST',api('/upload?name='+encodeURIComponent(f.name)));x.onload=function(){if(x.status>=200&&x.status<300)ok.push(x.responseText.trim());else fail.push(f.name+': '+x.responseText.trim());i++;progress.value=i;next();};x.onerror=function(){fail.push(f.name+': network error');i++;progress.value=i;next();};x.send(f);}next();}");
        html.append("function esc(s){var d=document.createElement('div');d.textContent=String(s);return d.innerHTML;}");
        html.append("loadAll();");
        html.append("</script></body></html>\n");
        return html.toString();
    }
    private static final class HttpRequest {
        String method;
        String path;
        String query;
        long contentLength = -1L;
    }
}
