package com.chris.hermesbridge;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.util.Base64;
import android.util.Log;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;

public final class EventDispatcher {
    private static final String TAG = "HermesBridge";
    private static final String RUN_PERMISSION = "com.termux.permission.RUN_COMMAND";
    private static final String TERMUX_PACKAGE = "com.termux";
    private static final String TERMUX_SERVICE = "com.termux.app.RunCommandService";
    private static final String EVENT_SCRIPT = "/data/data/com.termux/files/home/bin/ha-event";
    private static final long DUPLICATE_WINDOW_MS = 1000;
    private static final ConcurrentHashMap<String, Long> LAST_DISPATCH = new ConcurrentHashMap<>();

    private EventDispatcher() { }

    public static boolean hasRunCommandPermission(Context context) {
        return context.checkSelfPermission(RUN_PERMISSION) == PackageManager.PERMISSION_GRANTED;
    }

    public static boolean dispatch(Context context, String type, JSONObject data) {
        try {
            JSONObject safeData = data == null ? new JSONObject() : data;
            String fingerprint = type + "|" + safeData.toString();
            long now = System.currentTimeMillis();
            Long previous = LAST_DISPATCH.put(fingerprint, now);
            if (previous != null && now - previous < DUPLICATE_WINDOW_MS) {
                return false;
            }
            if (LAST_DISPATCH.size() > 512) {
                LAST_DISPATCH.clear();
                LAST_DISPATCH.put(fingerprint, now);
            }

            JSONObject event = new JSONObject()
                    .put("event", type)
                    .put("timestamp_ms", now)
                    .put("data", safeData);

            String encoded = Base64.encodeToString(
                    event.toString().getBytes(StandardCharsets.UTF_8),
                    Base64.NO_WRAP);

            Intent intent = new Intent("com.termux.RUN_COMMAND");
            intent.setClassName(TERMUX_PACKAGE, TERMUX_SERVICE);
            intent.putExtra("com.termux.RUN_COMMAND_PATH", EVENT_SCRIPT);
            intent.putExtra("com.termux.RUN_COMMAND_ARGUMENTS", new String[]{encoded});
            intent.putExtra("com.termux.RUN_COMMAND_WORKDIR", "/data/data/com.termux/files/home");
            intent.putExtra("com.termux.RUN_COMMAND_BACKGROUND", true);
            context.startService(intent);
            return true;
        } catch (Throwable error) {
            Log.e(TAG, "Unable to dispatch event " + type, error);
            return false;
        }
    }
}
