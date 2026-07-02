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
import java.util.Locale;

final class ManagementServer {
    interface StatusProvider {
        String buildStatusTextForManagement();
    }

    private static final int SOCKET_TIMEOUT_MS = 30000;
    private static final long MAX_UPLOAD_BYTES = 1024L * 1024L * 1024L;

    private final int port;
    private final File baseDir;
    private final File mediaDir;
    private final File configFile;
    private final File logFile;
    private final PlayerLog log;
    private final StatusProvider statusProvider;

    private volatile boolean running;
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
        this.statusProvider = statusProvider;
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

            if ("GET".equals(request.method) && "/".equals(request.path)) {
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
                : status == 404 ? "Not Found" : status == 413 ? "Payload Too Large" : "Error";
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
        return "<!doctype html>\n"
                + "<html><head><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                + "<title>Simple Kiosk</title>"
                + "<style>body{font-family:sans-serif;background:#101214;color:#eee;margin:0;padding:16px;}"
                + "section{margin:0 0 16px;padding:12px;background:#1d2227;border:1px solid #333;}"
                + "button,input{font-size:16px;margin:4px 0;}pre{white-space:pre-wrap;overflow:auto;}"
                + "progress{width:100%;}</style></head><body>"
                + "<h1>Simple Kiosk</h1>"
                + "<section><h2>Upload media</h2>"
                + "<input id=\"file\" type=\"file\" accept=\"image/png,image/jpeg,video/mp4\">"
                + "<button onclick=\"upload()\">Upload</button><progress id=\"progress\" max=\"100\" value=\"0\"></progress>"
                + "<pre id=\"uploadResult\"></pre></section>"
                + "<section><h2>Status</h2><button onclick=\"loadAll()\">Refresh</button><pre id=\"status\"></pre></section>"
                + "<section><h2>Media</h2><pre id=\"media\"></pre></section>"
                + "<section><h2>Config</h2><pre id=\"config\"></pre></section>"
                + "<section><h2>Logs</h2><pre id=\"logs\"></pre></section>"
                + "<script>function text(u,id){fetch(u).then(r=>r.text()).then(t=>document.getElementById(id).textContent=t).catch(e=>document.getElementById(id).textContent=e);}"
                + "function loadAll(){text('/status','status');text('/media','media');text('/config','config');text('/logs','logs');}"
                + "function upload(){var f=document.getElementById('file').files[0];if(!f){alert('Choose a file');return;}"
                + "var x=new XMLHttpRequest();x.open('POST','/upload?name='+encodeURIComponent(f.name));"
                + "x.upload.onprogress=function(e){if(e.lengthComputable)document.getElementById('progress').value=e.loaded/e.total*100;};"
                + "x.onload=function(){document.getElementById('uploadResult').textContent=x.responseText;loadAll();};"
                + "x.onerror=function(){document.getElementById('uploadResult').textContent='Upload failed';};x.send(f);}loadAll();</script>"
                + "</body></html>\n";
    }

    private static final class HttpRequest {
        String method;
        String path;
        String query;
        long contentLength = -1L;
    }
}
