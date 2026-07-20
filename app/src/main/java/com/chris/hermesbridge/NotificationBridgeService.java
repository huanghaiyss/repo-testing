package com.chris.hermesbridge;

import android.app.Notification;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import org.json.JSONObject;

public class NotificationBridgeService extends NotificationListenerService {
    private static volatile boolean connected;

    public static boolean isConnected() {
        return connected;
    }

    @Override
    public void onListenerConnected() {
        super.onListenerConnected();
        connected = true;
        EventDispatcher.dispatch(this, "notification_listener_connected", new JSONObject());
    }

    @Override
    public void onListenerDisconnected() {
        connected = false;
        super.onListenerDisconnected();
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        if (shouldIgnore(sbn)) return;
        try {
            Notification n = sbn.getNotification();
            Bundle extras = n == null ? Bundle.EMPTY : n.extras;

            JSONObject data = new JSONObject()
                    .put("package", sbn.getPackageName())
                    .put("id", sbn.getId())
                    .put("tag", sbn.getTag() == null ? "" : sbn.getTag())
                    .put("key", sbn.getKey())
                    .put("post_time_ms", sbn.getPostTime())
                    .put("title", value(extras.getCharSequence(Notification.EXTRA_TITLE)))
                    .put("text", value(extras.getCharSequence(Notification.EXTRA_TEXT)))
                    .put("big_text", value(extras.getCharSequence(Notification.EXTRA_BIG_TEXT)))
                    .put("sub_text", value(extras.getCharSequence(Notification.EXTRA_SUB_TEXT)))
                    .put("summary_text", value(extras.getCharSequence(Notification.EXTRA_SUMMARY_TEXT)))
                    .put("category", n == null || n.category == null ? "" : n.category)
                    .put("channel_id", n == null || n.getChannelId() == null ? "" : n.getChannelId())
                    .put("ongoing", false);

            EventDispatcher.dispatch(this, "notification_posted", data);
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        if (shouldIgnorePackage(sbn == null ? null : sbn.getPackageName())) return;
        if (sbn == null) return;
        try {
            JSONObject data = new JSONObject()
                    .put("package", sbn.getPackageName())
                    .put("id", sbn.getId())
                    .put("tag", sbn.getTag() == null ? "" : sbn.getTag())
                    .put("key", sbn.getKey());
            EventDispatcher.dispatch(this, "notification_removed", data);
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void onDestroy() {
        connected = false;
        super.onDestroy();
    }

    private boolean shouldIgnore(StatusBarNotification sbn) {
        if (sbn == null || shouldIgnorePackage(sbn.getPackageName())) return true;
        Notification notification = sbn.getNotification();
        return notification != null &&
                (notification.flags & Notification.FLAG_ONGOING_EVENT) != 0;
    }

    private boolean shouldIgnorePackage(String packageName) {
        if (packageName == null) return true;
        return packageName.equals(getPackageName()) || packageName.startsWith("com.termux");
    }

    private static String value(CharSequence value) {
        return value == null ? "" : value.toString();
    }
}
