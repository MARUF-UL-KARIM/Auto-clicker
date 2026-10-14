package com.example.autotapper;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {
    private TextView status;

    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density); }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFFF5F5F5);
        root.setPadding(dp(20), dp(48), dp(20), dp(20));

        TextView title = new TextView(this);
        title.setText("Auto Tapper");
        title.setTextSize(30);
        title.setTextColor(0xFF212121);
        root.addView(title);

        status = new TextView(this);
        status.setPadding(0, dp(8), 0, dp(16));
        root.addView(status);

        root.addView(card("Single Click", "One point \u00b7 set click interval (ms / sec / min)", 0xFF1E88E5,
                v -> launch("single")));
        root.addView(card("Multiple Click", "Many points \u00b7 delay per point \u00b7 Active button", 0xFF43A047,
                v -> launch("multi")));

        Button acc = new Button(this);
        acc.setText("Accessibility settings");
        acc.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        root.addView(acc);

        Button hide = new Button(this);
        hide.setText("Hide floating panel");
        hide.setOnClickListener(v -> stopService(new Intent(this, FloatingService.class)));
        root.addView(hide);

        setContentView(root);
    }

    @Override protected void onResume() {
        super.onResume();
        status.setText("Accessibility: " + (ClickService.instance != null ? "ON" : "OFF")
                + "    Overlay: " + (Settings.canDrawOverlays(this) ? "ON" : "OFF"));
    }

    private View card(String t, String s, int color, View.OnClickListener c) {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(dp(20), dp(24), dp(20), dp(24));
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(16));
        l.setBackground(g);
        l.setOnClickListener(c);
        TextView a = new TextView(this);
        a.setText(t);
        a.setTextSize(22);
        a.setTextColor(Color.WHITE);
        TextView d = new TextView(this);
        d.setText(s);
        d.setTextSize(14);
        d.setTextColor(0xFFEEEEEE);
        l.addView(a);
        l.addView(d);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = dp(16);
        l.setLayoutParams(lp);
        return l;
    }

    private void launch(String mode) {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Allow 'Display over other apps'", Toast.LENGTH_LONG).show();
            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName())));
            return;
        }
        if (ClickService.instance == null) {
            Toast.makeText(this, "Turn on Auto Tapper in Accessibility", Toast.LENGTH_LONG).show();
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            return;
        }
        startService(new Intent(this, FloatingService.class).putExtra("mode", mode));
        moveTaskToBack(true);
    }
}
