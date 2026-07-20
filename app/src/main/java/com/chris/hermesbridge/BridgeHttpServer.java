package com.chris.hermesbridge;

import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/** Loopback-only, authenticated JSON-RPC transport. */
public final class BridgeHttpServer {
    public static final int PORT = 18473;
    private static final String TAG = "HermesBridgeHttp";
    private static final int MAX_BODY = 512 * 1024;
    private final BridgeAccessibilityService service;
    private final String token;
    private final ExecutorService workers = Executors.newFixedThreadPool(2);
    private volatile boolean running;
    private ServerSocket server;
    private Thread acceptThread;

    public BridgeHttpServer(BridgeAccessibilityService service, String token) {
        this.service = service;
        this.token = token;
    }

    public synchronized void start() {
        if (running) return;
        try {
            server = new ServerSocket(PORT, 16, InetAddress.getByName("127.0.0.1"));
            running = true;
            acceptThread = new Thread(() -> {
                while (running) {
                    try {
                        final Socket socket = server.accept();
                        workers.execute(() -> handle(socket));
                    } catch (Exception e) {
                        if (running) Log.e(TAG, "accept failed", e);
                    }
                }
            }, "hermes-bridge-http");
            acceptThread.start();
            Log.i(TAG, "HTTP RPC listening on 127.0.0.1:" + PORT);
        } catch (Exception e) {
            Log.e(TAG, "Unable to start HTTP RPC", e);
            running = false;
        }
    }

    public synchronized void stop() {
        running = false;
        try { if (server != null) server.close(); } catch (Exception ignored) { }
        workers.shutdownNow();
        server = null;
    }

    public boolean isRunning() { return running; }

    private void handle(Socket socket) {
        try (Socket s = socket) {
            s.setSoTimeout(15000);
            BufferedReader reader = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
            String requestLine = reader.readLine();
            if (requestLine == null || !requestLine.startsWith("POST /rpc ")) {
                reply(s, 404, error("HTTP_NOT_FOUND", "POST /rpc required"));
                return;
            }
            int contentLength = 0;
            String auth = "";
            String line;
            while ((line = reader.readLine()) != null && !line.isEmpty()) {
                int colon = line.indexOf(':');
                if (colon < 0) continue;
                String key = line.substring(0, colon).trim();
                String value = line.substring(colon + 1).trim();
                if ("Content-Length".equalsIgnoreCase(key)) contentLength = Integer.parseInt(value);
                if ("X-Hermes-Bridge-Token".equalsIgnoreCase(key)) auth = value;
            }
            if (!token.equals(auth)) {
                reply(s, 401, error("UNAUTHORIZED", "Invalid bridge token"));
                return;
            }
            if (contentLength <= 0 || contentLength > MAX_BODY) {
                reply(s, 413, error("INVALID_BODY", "Content-Length is out of range"));
                return;
            }
            char[] body = new char[contentLength];
            int read = 0;
            while (read < contentLength) {
                int n = reader.read(body, read, contentLength - read);
                if (n < 0) break;
                read += n;
            }
            if (read != contentLength) {
                reply(s, 400, error("TRUNCATED_BODY", "Request body was truncated"));
                return;
            }
            JSONObject request = new JSONObject(new String(body));
            String requestId = request.optString("request_id", "");
            if (requestId.isEmpty()) requestId = UUID.randomUUID().toString();
            if (!"hermes-android-automation".equals(request.optString("protocol", "")) || request.optInt("version", 0) != 1) {
                reply(s, 400, error("PROTOCOL_MISMATCH", "protocol/version must be hermes-android-automation/1").put("request_id", requestId));
                return;
            }
            JSONObject command = request.optJSONObject("payload");
            if (command == null) command = request;
            JSONObject result = service.execute(command);
            result.put("protocol", "hermes-android-automation").put("version", 1).put("request_id", requestId);
            reply(s, result.optBoolean("ok", false) ? 200 : 422, result);
        } catch (Exception e) {
            try { reply(socket, 500, error("RPC_FAILURE", e.getClass().getSimpleName() + ": " + e.getMessage())); }
            catch (Exception ignored) { }
        }
    }

    private static JSONObject error(String code, String message) {
        try { return new JSONObject().put("ok", false).put("error_code", code).put("message", message == null ? "" : message); }
        catch (Exception ignored) { return new JSONObject(); }
    }

    private static void reply(Socket socket, int code, JSONObject body) throws Exception {
        byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
        OutputStream out = socket.getOutputStream();
        String status = code == 200 ? "OK" : (code == 401 ? "Unauthorized" : "Error");
        String headers = "HTTP/1.1 " + code + " " + status + "\r\nContent-Type: application/json; charset=utf-8\r\nContent-Length: " + bytes.length + "\r\nConnection: close\r\n\r\n";
        out.write(headers.getBytes(StandardCharsets.US_ASCII));
        out.write(bytes);
        out.flush();
    }
}
