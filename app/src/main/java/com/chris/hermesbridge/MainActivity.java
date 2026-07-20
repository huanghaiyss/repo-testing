package com.chris.hermesbridge;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import java.util.UUID;

public class MainActivity extends Activity {
    private static final String RUN_PERMISSION = "com.termux.permission.RUN_COMMAND";
    private static final int RUN_PERMISSION_REQUEST = 1001;

    private TextView status;

    public static String bridgeToken(Context context) {
        android.content.SharedPreferences p = context.getSharedPreferences("bridge", MODE_PRIVATE);
        String token = p.getString("token", "");
        if (token.isEmpty()) {
            token = UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", "");
            p.edit().putString("token", token).apply();
        }
        return token;
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        int pad = dp(18);

        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("Hermes Bridge 0.3");
        title.setTextSize(27);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        body.addView(title);

        TextView description = new TextView(this);
        description.setText("\nAndroid 自动化桥接层。任务、触发器和日志由 Termux/Hermes 负责。\n");
        description.setTextSize(16);
        body.addView(description);

        status = new TextView(this);
        status.setTextSize(16);
        status.setPadding(0, dp(8), 0, dp(12));
        body.addView(status);

        TextView rpc = new TextView(this);
        rpc.setText("\n本地 RPC：127.0.0.1:18473\nToken（复制到 Termux ~/.hauto/bridge-token）：\n" + bridgeToken(this));
        rpc.setTextIsSelectable(true);
        body.addView(rpc);
        body.addView(button("复制 RPC Token", () -> {
            ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("Hermes Bridge token", bridgeToken(this)));
        }));

        body.addView(button("1. 打开无障碍设置", () ->
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))));

        body.addView(button("2. 授予 Termux 命令权限", () ->
                requestPermissions(new String[]{RUN_PERMISSION}, RUN_PERMISSION_REQUEST)));

        body.addView(button("3. 打开通知使用权设置", () ->
                startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))));

        body.addView(button("打开本应用系统信息", () -> {
            Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            intent.setData(Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        }));

        body.addView(button("刷新状态", this::refresh));

        TextView help = new TextView(this);
        help.setText("\n全部授权完成后，在 Termux 测试：\n\nha status\nha events\nha run demo-back\n\n若小米阻止无障碍，请在本应用系统信息页右上角允许受限设置。\n若第二项没有弹窗，请在系统信息页的权限/其他权限中允许“在 Termux 环境中运行命令”。");
        help.setTextSize(15);
        help.setTypeface(Typeface.MONOSPACE);
        help.setTextIsSelectable(true);
        body.addView(help);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(body);
        setContentView(scroll);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private Button button(String text, Runnable action) {
        Button button = new Button(this);
        button.setText(text);
        button.setAllCaps(false);
        button.setOnClickListener(v -> action.run());
        return button;
    }

    private void refresh() {
        BridgeAccessibilityService service = BridgeAccessibilityService.getInstance();
        boolean runGranted = checkSelfPermission(RUN_PERMISSION) == PackageManager.PERMISSION_GRANTED;
        boolean notificationGranted = notificationAccessEnabled();

        status.setText(
                "无障碍服务：" + (service == null ? "未连接" : "已连接") +
                "\nTermux 命令权限：" + (runGranted ? "已授权" : "未授权") +
                "\n通知使用权：" + (notificationGranted ? "已授权" : "未授权") +
                "\n通知监听连接：" + (NotificationBridgeService.isConnected() ? "已连接" : "未连接") +
                "\n前台应用：" + (service == null ? "未知" : service.currentPackage()));
    }

    private boolean notificationAccessEnabled() {
        String enabled = Settings.Secure.getString(
                getContentResolver(), "enabled_notification_listeners");
        return enabled != null && enabled.contains(getPackageName());
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
