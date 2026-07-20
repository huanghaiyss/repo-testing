package com.chris.hermesbridge;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ComponentName;
import android.content.pm.PackageManager;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public class BridgeAccessibilityService extends AccessibilityService {
    private static volatile BridgeAccessibilityService instance;
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile String lastPackage = "";
    private volatile String lastActivity = "";
    private BroadcastReceiver screenReceiver;
    private BridgeHttpServer httpServer;

    interface Task { JSONObject run() throws Exception; }

    public static BridgeAccessibilityService getInstance() { return instance; }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        httpServer = new BridgeHttpServer(this, MainActivity.bridgeToken(this));
        httpServer.start();
        registerDeviceEvents();
        EventDispatcher.dispatch(this, "bridge_connected", new JSONObject());
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || event.getPackageName() == null) return;
        if (event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;

        String current = event.getPackageName().toString();
        String activity = event.getClassName() == null ? "" : event.getClassName().toString();
        if (current.equals(lastPackage) && activity.equals(lastActivity)) return;

        lastPackage = current;
        lastActivity = activity;
        try {
            EventDispatcher.dispatch(this, "foreground_app",
                    new JSONObject()
                            .put("package", current)
                            .put("activity", activity)
                            .put("accessibility_event_type", event.getEventType()));
        } catch (Throwable ignored) {
        }
    }

    @Override public void onInterrupt() { }

    @Override
    public void onDestroy() {
        unregisterDeviceEvents();
        if (httpServer != null) httpServer.stop();
        httpServer = null;
        instance = null;
        super.onDestroy();
    }

    private void registerDeviceEvents() {
        if (screenReceiver != null) return;

        screenReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (intent == null || intent.getAction() == null) return;
                String event;
                switch (intent.getAction()) {
                    case Intent.ACTION_SCREEN_ON:
                        event = "screen_on";
                        break;
                    case Intent.ACTION_SCREEN_OFF:
                        event = "screen_off";
                        break;
                    case Intent.ACTION_USER_PRESENT:
                        event = "user_present";
                        break;
                    default:
                        return;
                }
                EventDispatcher.dispatch(context, event, new JSONObject());
            }
        };

        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_USER_PRESENT);

        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(screenReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(screenReceiver, filter);
        }
    }

    private void unregisterDeviceEvents() {
        if (screenReceiver == null) return;
        try {
            unregisterReceiver(screenReceiver);
        } catch (Throwable ignored) {
        }
        screenReceiver = null;
    }

    public String currentPackage() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root != null && root.getPackageName() != null) return root.getPackageName().toString();
        return lastPackage;
    }

    public String currentActivity() { return lastActivity; }

    public JSONObject execute(JSONObject command) {
        return onMain(() -> executeMain(command));
    }

    private JSONObject executeMain(JSONObject c) throws Exception {
        String action = c.optString("action", "");
        switch (action) {
            case "status":
                return ok()
                        .put("service_connected", true)
                        .put("package", currentPackage())
                        .put("activity", currentActivity())
                        .put("foreground", !currentPackage().isEmpty())
                        .put("bridge_version", "0.3.1")
                        .put("transport", "loopback_http")
                        .put("transport_port", BridgeHttpServer.PORT)
                        .put("termux_run_permission", EventDispatcher.hasRunCommandPermission(this))
                        .put("notification_listener_connected", NotificationBridgeService.isConnected());
            case "dump":
                return dump(c.optInt("max_nodes", 300));
            case "launch_app":
                return launchApp(c.optString("package", ""));
            case "launch_activity":
                return launchActivity(c.optString("package", ""), c.optString("activity", ""));
            case "find":
                return findSelector(c.optJSONObject("selector"));
            case "click_text":
                return clickText(c.optString("text", ""), c.optBoolean("exact", true));
            case "click_id":
                return clickId(c.optString("id", ""));
            case "set_text":
                return setText(c.optString("id", ""), c.optString("text", ""));
            case "global":
                return global(c.optString("name", ""));
            case "tap":
                return gestureTap(c.optInt("x", -1), c.optInt("y", -1));
            case "swipe":
                return gestureSwipe(c.optInt("x1", -1), c.optInt("y1", -1), c.optInt("x2", -1), c.optInt("y2", -1), c.optLong("duration_ms", 350));
            default:
                return error("unknown_action", "Unknown action: " + action);
        }
    }

    private JSONObject dump(int requestedMax) throws Exception {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return error("no_active_window", "No active accessibility window");

        int max = Math.max(1, Math.min(requestedMax, 1000));
        JSONArray nodes = new JSONArray();
        int[] count = {0};
        append(root, "0", 0, nodes, count, max);
        return ok().put("package", text(root.getPackageName())).put("activity", currentActivity()).put("node_count", count[0]).put("truncated", count[0] >= max).put("nodes", nodes);
    }

    private JSONObject launchApp(String packageName) throws Exception {
        if (packageName.isEmpty()) return error("missing_package", "package is required");
        Intent intent = getPackageManager().getLaunchIntentForPackage(packageName);
        if (intent == null) return error("launch_intent_unavailable", "No exported launcher activity for " + packageName);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        startActivity(intent);
        return ok().put("launch_requested", true).put("package", packageName).put("foreground", packageName.equals(currentPackage()));
    }

    private JSONObject launchActivity(String packageName, String activity) throws Exception {
        if (packageName.isEmpty() || activity.isEmpty()) return error("missing_component", "package and activity are required");
        String name = activity.startsWith(".") ? packageName + activity : activity;
        Intent intent = new Intent().setComponent(new ComponentName(packageName, name));
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        try {
            startActivity(intent);
            return ok().put("launch_requested", true).put("package", packageName).put("activity", name).put("foreground", packageName.equals(currentPackage()));
        } catch (SecurityException denied) {
            return error("ACTIVITY_NOT_EXPORTED", "Android denied direct Activity launch; use launcher navigation").put("package", packageName).put("activity", name);
        }
    }

    private JSONObject findSelector(JSONObject selector) throws Exception {
        if (selector == null) return error("missing_selector", "selector is required");
        int max = selector.optInt("_max_nodes", 300);
        JSONObject all = dump(max);
        JSONArray matches = new JSONArray();
        JSONArray nodes = all.optJSONArray("nodes");
        if (nodes != null) for (int i = 0; i < nodes.length(); i++) {
            JSONObject node = nodes.optJSONObject(i);
            if (node != null && selectorMatches(node, selector)) matches.put(node);
        }
        return ok().put("package", currentPackage()).put("activity", currentActivity()).put("count", matches.length()).put("nodes", matches);
    }

    private boolean selectorMatches(JSONObject n, JSONObject s) {
        if (s.has("text") && !s.optString("text").equals(n.optString("text"))) return false;
        if (s.has("text_contains") && !n.optString("text").contains(s.optString("text_contains"))) return false;
        if (s.has("desc") && !s.optString("desc").equals(n.optString("description"))) return false;
        if (s.has("desc_contains") && !n.optString("description").contains(s.optString("desc_contains"))) return false;
        if (s.has("id") && !s.optString("id").equals(n.optString("id"))) return false;
        if (s.has("class") && !s.optString("class").equals(n.optString("class"))) return false;
        if (s.has("package") && !s.optString("package").equals(n.optString("package"))) return false;
        if (s.has("clickable") && s.optBoolean("clickable") != n.optBoolean("clickable")) return false;
        if (s.has("editable") && s.optBoolean("editable") != n.optBoolean("editable")) return false;
        return true;
    }

    private void append(AccessibilityNodeInfo n, String path, int depth, JSONArray out, int[] count, int max) throws Exception {
        if (n == null || count[0] >= max) return;
        Rect r = new Rect();
        n.getBoundsInScreen(r);
        JSONObject item = new JSONObject()
                .put("path", path)
                .put("depth", depth)
                .put("class", text(n.getClassName()))
                .put("text", text(n.getText()))
                .put("description", text(n.getContentDescription()))
                .put("id", text(n.getViewIdResourceName()))
                .put("package", text(n.getPackageName()))
                .put("clickable", n.isClickable())
                .put("editable", n.isEditable())
                .put("enabled", n.isEnabled())
                .put("scrollable", n.isScrollable())
                .put("bounds", rect(r));
        out.put(item);
        count[0]++;
        for (int i = 0; i < n.getChildCount() && count[0] < max; i++) {
            append(n.getChild(i), path + "." + i, depth + 1, out, count, max);
        }
    }

    private JSONObject clickText(String wanted, boolean exact) throws Exception {
        if (wanted.isEmpty()) return error("missing_text", "text is required");
        AccessibilityNodeInfo found = findText(getRootInActiveWindow(), wanted, exact);
        if (found == null) return error("not_found", "No matching node");

        Rect r = new Rect();
        found.getBoundsInScreen(r);
        AccessibilityNodeInfo clickable = clickableAncestor(found);
        boolean done = clickable != null && clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        JSONObject out = done ? ok() : error("click_failed", "Matching node found, but ACTION_CLICK failed");
        return out.put("matched_text", text(found.getText())).put("matched_description", text(found.getContentDescription())).put("matched_id", text(found.getViewIdResourceName())).put("bounds", rect(r));
    }

    private JSONObject clickId(String id) throws Exception {
        if (id.isEmpty()) return error("missing_id", "id is required");
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return error("no_active_window", "No active accessibility window");
        List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByViewId(id);
        if (nodes == null || nodes.isEmpty()) return error("not_found", "No node with id: " + id);
        AccessibilityNodeInfo clickable = clickableAncestor(nodes.get(0));
        return clickable != null && clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                ? ok().put("matched_id", id)
                : error("click_failed", "Node found, but ACTION_CLICK failed").put("matched_id", id);
    }

    private JSONObject setText(String id, String value) throws Exception {
        if (id.isEmpty()) return error("missing_id", "id is required");
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return error("no_active_window", "No active accessibility window");
        List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByViewId(id);
        if (nodes == null || nodes.isEmpty()) return error("not_found", "No node with id: " + id);
        AccessibilityNodeInfo node = nodes.get(0);
        Bundle args = new Bundle();
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value);
        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                ? ok().put("matched_id", id)
                : error("set_text_failed", "ACTION_SET_TEXT failed").put("matched_id", id);
    }

    private JSONObject global(String name) throws Exception {
        int action;
        switch (name) {
            case "back": action = GLOBAL_ACTION_BACK; break;
            case "home": action = GLOBAL_ACTION_HOME; break;
            case "recents": action = GLOBAL_ACTION_RECENTS; break;
            case "notifications": action = GLOBAL_ACTION_NOTIFICATIONS; break;
            default: return error("unknown_global_action", "Unknown global action: " + name);
        }
        return performGlobalAction(action) ? ok().put("global_action", name) : error("global_action_failed", "Failed global action: " + name);
    }

    private JSONObject gestureTap(int x, int y) throws Exception {
        if (x < 0 || y < 0) return error("invalid_coordinates", "x and y must be >= 0");
        Path p = new Path();
        p.moveTo(x, y);
        GestureDescription g = new GestureDescription.Builder().addStroke(new GestureDescription.StrokeDescription(p, 0, 80)).build();
        return dispatchGesture(g, null, null) ? ok().put("scheduled", true) : error("gesture_rejected", "Android rejected tap gesture");
    }

    private JSONObject gestureSwipe(int x1, int y1, int x2, int y2, long duration) throws Exception {
        if (x1 < 0 || y1 < 0 || x2 < 0 || y2 < 0) return error("invalid_coordinates", "all coordinates must be >= 0");
        Path p = new Path();
        p.moveTo(x1, y1);
        p.lineTo(x2, y2);
        long d = Math.max(50, Math.min(duration, 5000));
        GestureDescription g = new GestureDescription.Builder().addStroke(new GestureDescription.StrokeDescription(p, 0, d)).build();
        return dispatchGesture(g, null, null) ? ok().put("scheduled", true) : error("gesture_rejected", "Android rejected swipe gesture");
    }

    private AccessibilityNodeInfo findText(AccessibilityNodeInfo n, String wanted, boolean exact) {
        if (n == null) return null;
        String t = text(n.getText());
        String d = text(n.getContentDescription());
        if ((exact && (wanted.equals(t) || wanted.equals(d))) || (!exact && (t.contains(wanted) || d.contains(wanted)))) return n;
        for (int i = 0; i < n.getChildCount(); i++) {
            AccessibilityNodeInfo found = findText(n.getChild(i), wanted, exact);
            if (found != null) return found;
        }
        return null;
    }

    private AccessibilityNodeInfo clickableAncestor(AccessibilityNodeInfo n) {
        AccessibilityNodeInfo current = n;
        for (int i = 0; i < 8 && current != null; i++) {
            if (current.isClickable()) return current;
            current = current.getParent();
        }
        return null;
    }

    private JSONObject onMain(Task task) {
        try {
            if (Looper.myLooper() == Looper.getMainLooper()) return task.run();
            AtomicReference<JSONObject> result = new AtomicReference<>();
            AtomicReference<Throwable> failure = new AtomicReference<>();
            CountDownLatch latch = new CountDownLatch(1);
            main.post(() -> {
                try { result.set(task.run()); } catch (Throwable e) { failure.set(e); } finally { latch.countDown(); }
            });
            if (!latch.await(25, TimeUnit.SECONDS)) return error("timeout", "Accessibility operation timed out");
            if (failure.get() != null) return error("operation_failed", failure.get().getClass().getSimpleName() + ": " + failure.get().getMessage());
            return result.get();
        } catch (Throwable e) {
            return error("bridge_failure", e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private static JSONObject ok() {
        try { return new JSONObject().put("ok", true); } catch (Exception ignored) { return new JSONObject(); }
    }

    private static JSONObject error(String code, String message) {
        try { return new JSONObject().put("ok", false).put("error", code).put("message", message == null ? "" : message); }
        catch (Exception ignored) { return new JSONObject(); }
    }

    private static JSONArray rect(Rect r) {
        return new JSONArray().put(r.left).put(r.top).put(r.right).put(r.bottom);
    }

    private static String text(CharSequence value) { return value == null ? "" : value.toString(); }
    private static String text(String value) { return value == null ? "" : value; }
}
