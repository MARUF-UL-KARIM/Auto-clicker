package com.example.autotapper;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {
    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        final SharedPreferences sp = getSharedPreferences("p", MODE_PRIVATE);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(48, 96, 48, 48);
        root.setGravity(Gravity.CENTER_HORIZONTAL);

        TextView title = new TextView(this);
        title.setText("Auto Tapper");
        title.setTextSize(28);
        root.addView(title);

        TextView lbl = new TextView(this);
        lbl.setText("Interval between taps (ms)");
        lbl.setPadding(0, 48, 0, 0);
        root.addView(lbl);

        final EditText interval = new EditText(this);
        interval.setInputType(InputType.TYPE_CLASS_NUMBER);
        interval.setText(String.valueOf(sp.getLong("interval", 1000)));
        root.addView(interval);

        Button a11y = new Button(this);
        a11y.setText("1. Enable accessibility service");
        a11y.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        root.addView(a11y);

        Button start = new Button(this);
        start.setText("2. Show floating panel");
        start.setOnClickListener(v -> {
            if (!Settings.canDrawOverlays(this)) {
                startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName())));
                return;
            }
            long ms = 1000;
            try { ms = Math.max(10, Long.parseLong(interval.getText().toString())); } catch (Exception ignored) { }
            sp.edit().putLong("interval", ms).apply();
            startService(new Intent(this, FloatingService.class));
            Toast.makeText(this, "Panel shown. Open any app.", Toast.LENGTH_SHORT).show();
        });
        root.addView(start);

        Button stop = new Button(this);
        stop.setText("Hide floating panel");
        stop.setOnClickListener(v -> stopService(new Intent(this, FloatingService.class)));
        root.addView(stop);

        setContentView(root);
    }
}
