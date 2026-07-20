package com.chris.hermesbridge;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

public class MainActivity extends Activity {
    private TextView status;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        int pad = dp(18);

        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("Hermes Bridge");
        title.setTextSize(27);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        body.addView(title);

        TextView description = new TextView(this);
        description.setText("\nAndroid 自动化桥接层。任务逻辑放在 Termux/Hermes，APK 负责读取和操作界面。\n");
        description.setTextSize(16);
        body.addView(description);

        status = new TextView(this);
        status.setTextSize(16);
        status.setPadding(0, dp(8), 0, dp(12));
        body.addView(status);

        Button settings = new Button(this);
        settings.setText("打开无障碍设置");
        settings.setAllCaps(false);
        settings.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        body.addView(settings);

        Button refresh = new Button(this);
        refresh.setText("刷新状态");
        refresh.setAllCaps(false);
        refresh.setOnClickListener(v -> refresh());
        body.addView(refresh);

        TextView help = new TextView(this);
        help.setText("\n授权完成后，在 Termux 测试：\n\nha status\nha dump\nha click-text \"确认\"\nha back\n\n若小米提示受限设置，请进入本应用的系统应用信息页，在右上角菜单允许受限设置。");
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

    private void refresh() {
        BridgeAccessibilityService service = BridgeAccessibilityService.getInstance();
        if (service == null) {
            status.setText("无障碍服务：未连接\n前台应用：未知");
        } else {
            status.setText("无障碍服务：已连接\n前台应用：" + service.currentPackage());
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
