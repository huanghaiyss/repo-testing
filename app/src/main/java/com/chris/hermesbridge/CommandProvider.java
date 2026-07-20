package com.chris.hermesbridge;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Process;
import android.util.Base64;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;

public class CommandProvider extends ContentProvider {
    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        Bundle out = new Bundle();
        try {
            int caller = Binder.getCallingUid();
            if (caller != 2000 && caller != Process.myUid()) {
                throw new SecurityException("Caller UID " + caller + " is not allowed");
            }
            if (!"execute".equals(method)) {
                out.putString("result", error("unknown_method", "Use method execute").toString());
                return out;
            }
            if (arg == null || arg.trim().isEmpty()) {
                out.putString("result", error("missing_command", "Command argument is required").toString());
                return out;
            }

            String raw = arg.trim();
            if (!raw.startsWith("{")) {
                raw = new String(Base64.decode(raw, Base64.DEFAULT), StandardCharsets.UTF_8);
            }

            BridgeAccessibilityService service = BridgeAccessibilityService.getInstance();
            if (service == null) {
                out.putString("result", error("service_disconnected", "Enable Hermes Bridge in accessibility settings").toString());
                return out;
            }

            out.putString("result", service.execute(new JSONObject(raw)).toString());
            return out;
        } catch (Throwable e) {
            out.putString("result", error("provider_failure", e.getClass().getSimpleName() + ": " + e.getMessage()).toString());
            return out;
        }
    }

    private JSONObject error(String code, String message) {
        try {
            return new JSONObject().put("ok", false).put("error", code).put("message", message == null ? "" : message);
        } catch (Exception ignored) {
            return new JSONObject();
        }
    }

    @Override public Cursor query(Uri uri, String[] p, String s, String[] a, String o) { return null; }
    @Override public String getType(Uri uri) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String selection, String[] args) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { throw new UnsupportedOperationException(); }
}
