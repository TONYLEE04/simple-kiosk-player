package com.simplekiosk.player;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
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

    private String buildHomePage() {
        StringBuilder html = new StringBuilder();
        html.append("<!doctype html>\n");
        html.append("<html><head><meta charset=\"utf-8\">");
        html.append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">");
        html.append("<title>Simple Kiosk</title>");
        html.append("<style>");
        html.append("body{font-family:Arial,sans-serif;background:#0f1115;color:#e8edf2;margin:0;padding:16px;}");
        html.append("h1{font-size:24px;margin:0 0 14px;color:#fff;}h2{font-size:17px;margin:0 0 10px;color:#f7fafc;}label{display:block;color:#9aa7b2;font-size:12px;margin-bottom:3px;}");
        html.append(".grid{display:grid;grid-template-columns:minmax(280px,1fr) minmax(360px,1.5fr);gap:12px;}@media(max-width:860px){.grid{grid-template-columns:1fr;}.playlist-row,.schedule-row{grid-template-columns:1fr!important;}}");
        html.append("section{background:#1a1f26;border:1px solid #303842;padding:14px;margin:0 0 12px;box-shadow:0 1px 0 #0a0c0f;}");
        html.append("button,input,select{font-size:14px;margin:2px;padding:7px;border:1px solid #3d4652;background:#11161c;color:#edf2f7;}button{background:#2b6fd6;color:white;border-color:#2b6fd6;}button.secondary{background:#303944;border-color:#46515f;}button.danger{background:#69343a;border-color:#8c454f;}button:active{filter:brightness(1.15);}");
        html.append(".media-item,.playlist-row{display:grid;grid-template-columns:1fr auto;gap:10px;align-items:center;border-top:1px solid #303942;padding:10px 0;}.file-name{font-weight:bold;color:#fff;word-break:break-all;}");
        html.append(".playlist-row{grid-template-columns:1.45fr .65fr .75fr .75fr auto;}.schedule-row{display:grid;grid-template-columns:1fr .55fr .55fr .75fr .9fr auto;gap:10px;align-items:end;border-top:1px solid #303942;padding:10px 0;}.muted{color:#9aa7b2;font-size:13px;}.pill{display:inline-block;background:#26313b;color:#cbd6df;padding:5px 8px;border:1px solid #3b4652;font-size:13px;}pre{white-space:pre-wrap;overflow:auto;max-height:240px;background:#0d1116;border:1px solid #29313a;padding:10px;}");
        html.append("progress{width:100%;height:12px;}.bar{display:flex;gap:8px;flex-wrap:wrap;align-items:center;margin:10px 0;}.toolbar-note{color:#a7b4c0;font-size:13px;margin:4px 0 8px;}");
        html.append("</style></head><body>");
        html.append("<h1>Simple Kiosk</h1>");
        html.append("<section><h2>Access</h2><div class=\"toolbar-note\">LAN access is protected by the tablet code by default. Disable it only on a trusted local network.</div><div id=\"accessState\"></div><div class=\"bar\"><button class=\"secondary\" onclick=\"setAccess(false)\">Disable protection</button><button class=\"secondary\" onclick=\"setAccess(true)\">Enable protection</button></div></section>");
        html.append("<div class=\"grid\"><div>");
        html.append("<section><h2>Upload media</h2>");
        html.append("<input id=\"file\" type=\"file\" accept=\"image/png,image/jpeg,video/mp4\">");
        html.append("<button onclick=\"upload()\">Upload</button><progress id=\"progress\" max=\"100\" value=\"0\"></progress>");
        html.append("<pre id=\"uploadResult\"></pre></section>");
        html.append("<section><h2>Media library</h2><button class=\"secondary\" onclick=\"loadAll()\">Refresh</button><div id=\"mediaList\"></div><pre id=\"mediaResult\"></pre></section>");
        html.append("<section><h2>Status</h2><pre id=\"status\"></pre></section>");
        html.append("</div><div>");
        html.append("<section><h2>Playlist editor</h2>");
        html.append("<div class=\"toolbar-note\">Edit the top-level playlist. Videos play to completion; duration applies to images only.</div>");
        html.append("<div class=\"bar\"><button onclick=\"saveConfig()\">Save config</button><button class=\"secondary\" onclick=\"reloadConfig()\">Reload config</button><button class=\"secondary\" onclick=\"rollbackConfig()\">Rollback</button><button class=\"secondary\" onclick=\"clearPlaylist()\">Clear playlist</button></div>");
        html.append("<div id=\"playlist\"></div><pre id=\"saveResult\"></pre></section>");
        html.append("<section><h2>Schedules</h2><div class=\"toolbar-note\">Playlist schedules keep their own playlist if already present; new playlist schedules copy the current top-level playlist.</div><div class=\"bar\"><button onclick=\"addSchedule(\'playlist\')\">Add playlist schedule</button><button class=\"secondary\" onclick=\"addSchedule(\'silent\')\">Add silent schedule</button></div><div id=\"schedules\"></div></section>");
        html.append("<section><h2>Config preview</h2><pre id=\"config\"></pre></section>");
        html.append("<section><h2>Logs</h2><pre id=\"logs\"></pre></section>");
        html.append("</div></div>");
        html.append("<script>");
        html.append("var mediaFiles=[];var ACCESS_TOKEN='").append(accessToken).append("';var ACCESS_ON=").append(accessProtectionEnabled ? "true" : "false").append(";var config={version:1,settings:{orientation:'landscape',fitMode:'contain',background:'#000000',keepScreenOn:true,hideSystemUi:true,mute:true},playlist:[]};");
        html.append("function api(u){if(!ACCESS_ON||!ACCESS_TOKEN)return u;return u+(u.indexOf('?')>=0?'&':'?')+'token='+encodeURIComponent(ACCESS_TOKEN);}");
        html.append("function text(u,id){fetch(api(u)).then(function(r){return r.text();}).then(function(t){document.getElementById(id).textContent=t;}).catch(function(e){document.getElementById(id).textContent=e;});}");
        html.append("function renderAccess(){var el=document.getElementById('accessState');if(el)el.textContent=ACCESS_ON?'Protected. Use the code shown on the tablet.':'Open on the local network.';}");
        html.append("function setAccess(on){fetch(api(on?'/access/enable':'/access/disable'),{method:'POST'}).then(function(r){return r.text();}).then(function(t){ACCESS_ON=on;renderAccess();document.getElementById('saveResult').textContent=t;}).catch(function(e){document.getElementById('saveResult').textContent=e;});}");
        html.append("function typeFromName(n){var l=n.toLowerCase();return l.endsWith('.mp4')?'video':'image';}");
        html.append("function fitOptions(v){var a=['contain','cover','stretch','center'];var s='<select class=fit>';for(var i=0;i<a.length;i++){s+='<option value=\"'+a[i]+'\" '+(a[i]==v?'selected':'')+'>'+a[i]+'</option>';}return s+'</select>';}");
        html.append("function loadAll(){renderAccess();loadMedia();reloadConfig();text('/status','status');text('/logs','logs');}");
        html.append("function loadMedia(){fetch(api('/media')).then(function(r){return r.text();}).then(function(t){mediaFiles=[];var lines=t.split(/\\n/);for(var i=0;i<lines.length;i++){var p=lines[i].split(/\\t/);if(p.length>=2){mediaFiles.push({name:p[0],size:p[1]});}}renderMedia();});}");
        html.append("function renderMedia(){var el=document.getElementById('mediaList');if(!mediaFiles.length){el.innerHTML='<div class=muted>No media files</div>';return;}var h='';for(var i=0;i<mediaFiles.length;i++){var f=mediaFiles[i];h+='<div class=media-item><div><div class=\\\"file-name\\\">'+esc(f.name)+'</div><div class=muted>'+esc(f.size)+'</div></div><div><button onclick=\\\"addMedia('+i+')\\\">Add</button><button class=secondary onclick=\\\"renameMedia('+i+')\\\">Rename</button><button class=danger onclick=\\\"deleteMedia('+i+')\\\">Delete</button></div></div>';}el.innerHTML=h;}");
        html.append("function reloadConfig(){fetch(api('/config')).then(function(r){return r.text();}).then(function(t){try{config=JSON.parse(t);}catch(e){document.getElementById('saveResult').textContent='Could not parse config: '+e;return;}if(!config.playlist){config.playlist=[];}if(!config.schedules){config.schedules=[];}renderPlaylist();renderSchedules();preview();});}");
        html.append("function mediaPost(url,msgId){fetch(api(url),{method:'POST'}).then(function(r){return r.text().then(function(t){return {ok:r.ok,text:t};});}).then(function(x){document.getElementById(msgId||'mediaResult').textContent=x.text;loadMedia();reloadConfig();}).catch(function(e){document.getElementById(msgId||'mediaResult').textContent=e;});}");
        html.append("function renameMedia(i){var oldName=mediaFiles[i].name;var name=prompt('New file name',oldName);if(!name||name==oldName)return;mediaPost('/media/rename?from='+encodeURIComponent(oldName)+'&to='+encodeURIComponent(name),'mediaResult');}");
        html.append("function deleteMedia(i){var name=mediaFiles[i].name;if(!confirm('Delete '+name+'?'))return;mediaPost('/media/delete?name='+encodeURIComponent(name),'mediaResult');}");        html.append("function addMedia(i){var f=mediaFiles[i];var type=typeFromName(f.name);var item={type:type,file:'media/'+f.name,fitMode:(config.settings&&config.settings.fitMode)||'contain'};if(type=='image'){item.duration=8;}config.playlist.push(item);renderPlaylist();preview();}");
        html.append("function renderPlaylist(){var el=document.getElementById('playlist');if(!config.playlist||!config.playlist.length){el.innerHTML='<div class=muted>No playlist items. Add files from the media library.</div>';return;}var h='';for(var i=0;i<config.playlist.length;i++){var it=config.playlist[i];var isVideo=it.type=='video';var durationCell=isVideo?'<span class=\"pill\">Play to end</span>':'<input class=duration type=\"number\" min=\"1\" value=\"'+(it.duration||8)+'\">';h+='<div class=playlist-row'+' data-i=\"'+i+'\"><div><div class=\"file-name\">'+(i+1)+'. '+esc(it.file||'')+'</div><div class=muted>'+esc(it.type||'')+'</div></div><div><label>Type</label><select class=type onchange=\"collect();renderPlaylist();preview();\"><option value=\"image\" '+(it.type=='image'?'selected':'')+'>image</option><option value=\"video\" '+(it.type=='video'?'selected':'')+'>video</option></select></div><div><label>Duration</label>'+durationCell+'</div><div><label>Fit</label>'+fitOptions(it.fitMode||'contain')+'</div><div><button class=secondary onclick=\"moveItem('+i+',-1)\">Up</button><button class=secondary onclick=\"moveItem('+i+',1)\">Down</button><button class=danger onclick=\"removeItem('+i+')\">Remove</button></div></div>';}el.innerHTML=h;}");
        html.append("function collect(){var rows=document.querySelectorAll('.playlist-row');var list=[];for(var i=0;i<rows.length;i++){var idx=parseInt(rows[i].getAttribute('data-i'),10);var old=config.playlist[idx];var item={type:rows[i].querySelector('.type').value,file:old.file,fitMode:rows[i].querySelector('.fit').value};if(item.type=='image'){var d=rows[i].querySelector('.duration');item.duration=d?(parseInt(d.value,10)||8):(old.duration||8);}list.push(item);}config.playlist=list;}");
        html.append("function moveItem(i,d){collect();var j=i+d;if(j<0||j>=config.playlist.length)return;var t=config.playlist[i];config.playlist[i]=config.playlist[j];config.playlist[j]=t;renderPlaylist();preview();}");
        html.append("function removeItem(i){collect();config.playlist.splice(i,1);renderPlaylist();preview();}");
        html.append("function copyPlaylist(list){return JSON.parse(JSON.stringify(list||[]));}");
        html.append("function addSchedule(mode){if(!config.schedules)config.schedules=[];var s={name:mode+'-'+(config.schedules.length+1),start:'08:00',end:'18:00',mode:mode};if(mode=='silent'){s.screen='allowSleep';}else{s.playlist=copyPlaylist(config.playlist);}config.schedules.push(s);renderSchedules();preview();}");
        html.append("function removeSchedule(i){config.schedules.splice(i,1);renderSchedules();preview();}");
        html.append("function useTopPlaylistForSchedule(i){collect();collectSchedules();config.schedules[i].playlist=copyPlaylist(config.playlist);config.schedules[i].mode='playlist';renderSchedules();preview();}");
        html.append("function renderSchedules(){var el=document.getElementById('schedules');var list=config.schedules||[];if(!list.length){el.innerHTML='<div class=muted>No schedules. Default playlist is used all day.</div>';return;}var h='';for(var i=0;i<list.length;i++){var s=list[i];var screen=s.screen||'allowSleep';var mode=s.mode||'playlist';h+='<div class=\"schedule-row\" data-i=\"'+i+'\"><div><label>Name</label><input class=\"sname\" value=\"'+esc(s.name||('schedule-'+(i+1)))+'\" onchange=\"collectSchedules();preview()\"></div><div><label>Start</label><input class=\"sstart\" value=\"'+esc(s.start||'08:00')+'\" onchange=\"collectSchedules();preview()\"></div><div><label>End</label><input class=\"send\" value=\"'+esc(s.end||'18:00')+'\" onchange=\"collectSchedules();preview()\"></div><div><label>Mode</label><select class=\"smode\" onchange=\"collectSchedules();renderSchedules();preview()\"><option value=\"playlist\" '+(mode=='playlist'?'selected':'')+'>playlist</option><option value=\"silent\" '+(mode=='silent'?'selected':'')+'>silent</option></select></div><div><label>Screen</label><select class=\"sscreen\" onchange=\"collectSchedules();preview()\"><option value=\"black\" '+(screen=='black'?'selected':'')+'>black</option><option value=\"allowSleep\" '+(screen=='allowSleep'?'selected':'')+'>allowSleep</option></select></div><div><button class=\"secondary\" onclick=\"useTopPlaylistForSchedule('+i+')\">Use current playlist</button><button class=\"danger\" onclick=\"removeSchedule('+i+')\">Remove</button><div class=muted>'+((s.playlist&&s.playlist.length)||0)+' items</div></div></div>';}el.innerHTML=h;}");
        html.append("function collectSchedules(){var rows=document.querySelectorAll('.schedule-row');if(!rows.length){if(!config.schedules)config.schedules=[];return;}var list=[];for(var i=0;i<rows.length;i++){var idx=parseInt(rows[i].getAttribute('data-i'),10);var old=config.schedules[idx]||{};var mode=rows[i].querySelector('.smode').value;var s={name:rows[i].querySelector('.sname').value||('schedule-'+(i+1)),start:rows[i].querySelector('.sstart').value||'08:00',end:rows[i].querySelector('.send').value||'18:00',mode:mode};if(mode=='silent'){s.screen=rows[i].querySelector('.sscreen').value||'allowSleep';}else{s.playlist=old.playlist||copyPlaylist(config.playlist);}list.push(s);}config.schedules=list;}");
        html.append("function rollbackConfig(){if(!confirm('Rollback to config.json.bak?'))return;fetch(api('/config/rollback'),{method:'POST'}).then(function(r){return r.text();}).then(function(t){document.getElementById('saveResult').textContent=t;reloadConfig();}).catch(function(e){document.getElementById('saveResult').textContent=e;});}");        html.append("function clearPlaylist(){config.playlist=[];renderPlaylist();preview();}");
        html.append("function saveConfig(){collect();collectSchedules();if(!config.version)config.version=1;if(!config.settings)config.settings={orientation:'landscape',fitMode:'contain',background:'#000000',keepScreenOn:true,hideSystemUi:true,mute:true};var body=JSON.stringify(config,null,2);fetch(api('/config'),{method:'POST',headers:{'Content-Type':'application/json'},body:body}).then(function(r){return r.text().then(function(t){return {ok:r.ok,text:t};});}).then(function(x){document.getElementById('saveResult').textContent=x.text;preview();text('/status','status');}).catch(function(e){document.getElementById('saveResult').textContent=e;});}");
        html.append("function preview(){document.getElementById('config').textContent=JSON.stringify(config,null,2);}");
        html.append("function upload(){var f=document.getElementById('file').files[0];if(!f){alert('Choose a file');return;}var x=new XMLHttpRequest();x.open('POST',api('/upload?name='+encodeURIComponent(f.name)));x.upload.onprogress=function(e){if(e.lengthComputable)document.getElementById('progress').value=e.loaded/e.total*100;};x.onload=function(){document.getElementById('uploadResult').textContent=x.responseText;loadMedia();};x.onerror=function(){document.getElementById('uploadResult').textContent='Upload failed';};x.send(f);}");
        html.append("function esc(s){return String(s).replace(/[&<>\"]/g,function(c){return {'&':'&amp;','<':'&lt;','>':'&gt;','\"':'&quot;'}[c];});}");
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
