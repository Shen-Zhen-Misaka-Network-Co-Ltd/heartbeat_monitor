package com.heartwith.mihealth.lsp;

import android.Manifest;
import android.app.Activity;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

public final class SettingsActivity extends Activity {
    private static final int COLOR_BG = 0xff000000;
    private static final int COLOR_CARD = 0xff242424;
    private static final int COLOR_INPUT = 0xff484848;
    private static final int COLOR_TEXT = 0xfff5f5f5;
    private static final int COLOR_MUTED = 0xffa5a5a5;
    private static final int COLOR_BLUE = 0xff0a84ff;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private EditText serverUrl;
    private EditText displayName;
    private Switch enabled;
    private TextView bpmText;
    private TextView statusText;
    private TextView sourceText;
    private TextView enabledText;
    private final Runnable refreshRunnable = new Runnable() {
        @Override
        public void run() {
            refreshStatus();
            handler.postDelayed(this, 1000L);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestNotificationPermissionIfNeeded();
        HeartwithSettings settings = HeartwithSettings.readLocal(this);

        ScrollView scrollView = new ScrollView(this);
        scrollView.setFillViewport(true);
        scrollView.setBackgroundColor(COLOR_BG);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(24), dp(36), dp(24), dp(28));
        scrollView.addView(root, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.WRAP_CONTENT));

        TextView title = new TextView(this);
        title.setText("Heartwith");
        title.setTextColor(COLOR_TEXT);
        title.setTextSize(34);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title, matchWrap());

        TextView subtitle = new TextView(this);
        subtitle.setText("小米健康 Hook 采集端");
        subtitle.setTextColor(COLOR_MUTED);
        subtitle.setTextSize(17);
        subtitle.setTypeface(Typeface.DEFAULT_BOLD);
        subtitle.setPadding(0, dp(4), 0, dp(28));
        root.addView(subtitle, matchWrap());

        LinearLayout statusCard = card();
        TextView chip = chip("LSPosed");
        statusCard.addView(chip, wrapWrap());

        bpmText = new TextView(this);
        bpmText.setText("等待心率");
        bpmText.setTextColor(COLOR_TEXT);
        bpmText.setTextSize(34);
        bpmText.setTypeface(Typeface.DEFAULT_BOLD);
        bpmText.setPadding(0, dp(14), 0, 0);
        statusCard.addView(bpmText, matchWrap());

        statusText = new TextView(this);
        statusText.setText("等待小米运动健康实时心率事件");
        statusText.setTextColor(COLOR_MUTED);
        statusText.setTextSize(16);
        statusText.setTypeface(Typeface.DEFAULT_BOLD);
        statusText.setPadding(0, dp(10), 0, 0);
        statusCard.addView(statusText, matchWrap());

        sourceText = new TextView(this);
        sourceText.setText("来源：尚未采集");
        sourceText.setTextColor(COLOR_MUTED);
        sourceText.setTextSize(13);
        sourceText.setPadding(0, dp(8), 0, 0);
        statusCard.addView(sourceText, matchWrap());
        root.addView(statusCard, matchWrapWithTop(0));

        LinearLayout configCard = card();
        TextView configTitle = sectionTitle("采集端");
        configCard.addView(configTitle, matchWrap());

        serverUrl = input("服务器地址", settings.serverUrl);
        configCard.addView(serverUrl, matchWrapWithTop(16));

        displayName = input("显示名称", settings.displayName);
        configCard.addView(displayName, matchWrapWithTop(12));

        LinearLayout switchRow = new LinearLayout(this);
        switchRow.setOrientation(LinearLayout.HORIZONTAL);
        switchRow.setGravity(Gravity.CENTER_VERTICAL);
        switchRow.setPadding(0, dp(18), 0, 0);

        LinearLayout switchText = new LinearLayout(this);
        switchText.setOrientation(LinearLayout.VERTICAL);
        enabledText = sectionTitle(settings.enabled ? "Hook 上传已启用" : "Hook 上传已关闭");
        TextView switchDesc = muted("关闭后仍可打开配置页，但小米健康心率不会上传。");
        switchText.addView(enabledText, matchWrap());
        switchText.addView(switchDesc, matchWrapWithTop(3));
        switchRow.addView(switchText, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        enabled = new Switch(this);
        enabled.setChecked(settings.enabled);
        enabled.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                enabledText.setText(isChecked ? "Hook 上传已启用" : "Hook 上传已关闭");
                save(false);
            }
        });
        switchRow.addView(enabled, wrapWrap());
        configCard.addView(switchRow, matchWrap());

        Button save = new Button(this);
        save.setText("保存配置");
        save.setAllCaps(false);
        save.setTextSize(16);
        save.setTextColor(Color.WHITE);
        save.setBackground(rounded(COLOR_BLUE, 18));
        save.setPadding(dp(18), dp(10), dp(18), dp(10));
        save.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                save(true);
            }
        });
        configCard.addView(save, matchWrapWithTop(18));
        root.addView(configCard, matchWrapWithTop(18));

        setContentView(scrollView);
    }

    @Override
    protected void onResume() {
        super.onResume();
        sendConfigBroadcast(HeartwithSettings.readLocal(this));
        handler.removeCallbacks(refreshRunnable);
        handler.post(refreshRunnable);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(refreshRunnable);
        super.onPause();
    }

    private void refreshStatus() {
        Cursor cursor = null;
        try {
            cursor = getContentResolver().query(SettingsProvider.STATUS_URI, null, null, null, null);
            HeartwithStatus status = null;
            if (cursor != null && cursor.moveToFirst()) {
                int bpm = cursor.getInt(cursor.getColumnIndexOrThrow(HeartwithStatus.KEY_LAST_BPM));
                String source = cursor.getString(cursor.getColumnIndexOrThrow(HeartwithStatus.KEY_LAST_SOURCE));
                long seenMs = cursor.getLong(cursor.getColumnIndexOrThrow(HeartwithStatus.KEY_LAST_SEEN_MS));
                status = new HeartwithStatus(bpm, source, seenMs);
            }
            bindStatus(status != null ? status : HeartwithStatus.readLocal(this));
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
    }

    private void bindStatus(HeartwithStatus status) {
        if (status.bpm > 0) {
            bpmText.setText(status.bpm + " BPM");
            statusText.setText("已采集心率 · " + HeartwithStatus.relativeTime(System.currentTimeMillis(), status.seenMs));
            sourceText.setText("来源：" + (status.source.isEmpty() ? "小米健康 Hook" : status.source));
        } else {
            bpmText.setText("等待心率");
            statusText.setText("打开小米运动健康的运动页后开始采集");
            sourceText.setText("来源：尚未采集");
        }
    }

    private EditText input(String hint, String text) {
        EditText editText = new EditText(this);
        editText.setHint(hint);
        editText.setText(text);
        editText.setSingleLine(true);
        editText.setTextSize(18);
        editText.setTextColor(COLOR_TEXT);
        editText.setHintTextColor(0xff858585);
        editText.setBackground(rounded(COLOR_INPUT, 18));
        editText.setPadding(dp(16), dp(8), dp(16), dp(8));
        return editText;
    }

    private void save(boolean toast) {
        HeartwithSettings settings = new HeartwithSettings(
                enabled.isChecked(),
                serverUrl.getText().toString(),
                displayName.getText().toString());
        HeartwithSettings.writeLocal(this, settings);
        sendConfigBroadcast(settings);
        if (toast) {
            Toast.makeText(this, "已永久保存，小米健康下次启动会自动同步", Toast.LENGTH_SHORT).show();
        }
    }

    private void sendConfigBroadcast(HeartwithSettings settings) {
        Intent intent = new Intent(HeartwithSettings.ACTION_CONFIG_CHANGED);
        intent.setPackage("com.mi.health");
        intent.putExtra(HeartwithSettings.EXTRA_ENABLED, settings.enabled);
        intent.putExtra(HeartwithSettings.EXTRA_SERVER_URL, settings.serverUrl);
        intent.putExtra(HeartwithSettings.EXTRA_DISPLAY_NAME, settings.displayName);
        sendBroadcast(intent);
    }

    private LinearLayout card() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(18), dp(18), dp(18), dp(18));
        card.setBackground(rounded(COLOR_CARD, 24));
        return card;
    }

    private TextView chip(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextColor(COLOR_BLUE);
        view.setTextSize(12);
        view.setTypeface(Typeface.DEFAULT_BOLD);
        view.setPadding(dp(8), dp(3), dp(8), dp(3));
        view.setBackground(rounded(0x33248cff, 7));
        return view;
    }

    private TextView sectionTitle(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextColor(COLOR_TEXT);
        view.setTextSize(18);
        view.setTypeface(Typeface.DEFAULT_BOLD);
        return view;
    }

    private TextView muted(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextColor(COLOR_MUTED);
        view.setTextSize(14);
        view.setLineSpacing(dp(2), 1f);
        return view;
    }

    private GradientDrawable rounded(int color, int radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radiusDp));
        return drawable;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams wrapWrap() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams matchWrapWithTop(int topDp) {
        LinearLayout.LayoutParams params = matchWrap();
        params.topMargin = dp(topDp);
        return params;
    }

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 23014);
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
