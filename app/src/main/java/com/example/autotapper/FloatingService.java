package com.example.autotapper;

import android.app.Service;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

public class FloatingService extends Service {
    private static final int TARGET = 120;
    private WindowManager wm;
    private LinearLayout panel;
    private WindowManager.LayoutParams panelLp;
    private final List<TextView> targets = new ArrayList<>();
    private final Handler h = new Handler(Looper.getMainLooper());
    private boolean running = false;
    private int idx = 0;
    private long interval = 1000;
    private TextView playBtn;

    private final Runnable loop = new Runnable() {
        @Override public void run() {
            if (!running || targets.isEmpty()) return;
            TextView t = targets.get(idx % targets.size());
            WindowManager.LayoutParams p = (WindowManager.LayoutParams) t.getLayoutParams();
            ClickService.tap(p.x + TARGET / 2f, p.y + TARGET / 2f);
            idx++;
            h.postDelayed(this, interval);
        }
    };

    @Override public IBinder onBind(Intent i) { return null; }

    @Override public void onCreate() {
        super.onCreate();
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        buildPanel();
    }

    private WindowManager.LayoutParams lp(int w, int h, boolean touchable) {
        int flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS;
        if (!touchable) flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        WindowManager.LayoutParams p = new WindowManager.LayoutParams(w, h,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, flags, PixelFormat.TRANSLUCENT);
        p.gravity = Gravity.TOP | Gravity.START;
        return p;
    }

    private TextView button(String s, View.OnClickListener c) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(22);
        t.setTextColor(Color.WHITE);
        t.setGravity(Gravity.CENTER);
        t.setPadding(36, 24, 36, 24);
        t.setOnClickListener(c);
        return t;
    }

    private void buildPanel() {
        panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.HORIZONTAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xE6222222);
        bg.setCornerRadius(60);
        panel.setBackground(bg);

        playBtn = button("▶", v -> toggle());
        panel.addView(playBtn);
        panel.addView(button("+", v -> addTarget()));
        panel.addView(button("−", v -> removeTarget()));
        panel.addView(button("✕", v -> stopSelf()));

        panelLp = lp(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, true);
        panelLp.x = 40;
        panelLp.y = 300;

        // drag panel by touching its background area (buttons consume taps; drag via panel)
        final float[] d = new float[4];
        panel.setOnTouchListener((v, e) -> {
            switch (e.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    d[0] = panelLp.x; d[1] = panelLp.y; d[2] = e.getRawX(); d[3] = e.getRawY();
                    return true;
                case MotionEvent.ACTION_MOVE:
                    panelLp.x = (int) (d[0] + e.getRawX() - d[2]);
                    panelLp.y = (int) (d[1] + e.getRawY() - d[3]);
                    wm.updateViewLayout(panel, panelLp);
                    return true;
            }
            return false;
        });
        wm.addView(panel, panelLp);
    }

    private void addTarget() {
        if (running) return;
        final TextView t = new TextView(this);
        t.setText(String.valueOf(targets.size() + 1));
        t.setTextColor(Color.WHITE);
        t.setTextSize(20);
        t.setGravity(Gravity.CENTER);
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(0xAAE53935);
        g.setStroke(4, Color.WHITE);
        t.setBackground(g);

        final WindowManager.LayoutParams p = lp(TARGET, TARGET, true);
        p.x = 300; p.y = 600;
        final float[] d = new float[4];
        t.setOnTouchListener((v, e) -> {
            switch (e.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    d[0] = p.x; d[1] = p.y; d[2] = e.getRawX(); d[3] = e.getRawY();
                    return true;
                case MotionEvent.ACTION_MOVE:
                    p.x = (int) (d[0] + e.getRawX() - d[2]);
                    p.y = (int) (d[1] + e.getRawY() - d[3]);
                    wm.updateViewLayout(t, p);
                    return true;
            }
            return false;
        });
        wm.addView(t, p);
        targets.add(t);
    }

    private void removeTarget() {
        if (running || targets.isEmpty()) return;
        wm.removeView(targets.remove(targets.size() - 1));
    }

    private void toggle() {
        if (!running) {
            if (ClickService.instance == null) {
                Toast.makeText(this, "Enable the accessibility service first", Toast.LENGTH_LONG).show();
                return;
            }
            if (targets.isEmpty()) {
                Toast.makeText(this, "Add a target with +", Toast.LENGTH_SHORT).show();
                return;
            }
            interval = getSharedPreferences("p", MODE_PRIVATE).getLong("interval", 1000);
            running = true;
            idx = 0;
            setTouchable(false);
            playBtn.setText("❚❚");
            h.post(loop);
        } else {
            running = false;
            h.removeCallbacks(loop);
            setTouchable(true);
            playBtn.setText("▶");
        }
    }

    private void setTouchable(boolean touchable) {
        for (TextView t : targets) {
            WindowManager.LayoutParams p = (WindowManager.LayoutParams) t.getLayoutParams();
            if (touchable) p.flags &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
            else p.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
            wm.updateViewLayout(t, p);
        }
    }

    @Override public void onDestroy() {
        running = false;
        h.removeCallbacks(loop);
        for (TextView t : targets) wm.removeView(t);
        targets.clear();
        if (panel != null) wm.removeView(panel);
        super.onDestroy();
    }
}
