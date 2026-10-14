package com.example.autotapper;

import android.app.Service;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

public class FloatingService extends Service {
    private static final int WRAP = -2;

    interface OnVal { void v(int val); }

    static class Point {
        int size = 120, opacity = 70, unit = 1; // unit: 0=ms 1=sec 2=min
        double delay = 1;
        boolean active = false;
        TextView view;
        WindowManager.LayoutParams lp;

        long delayMs() {
            double m = unit == 0 ? 1 : unit == 1 ? 1000 : 60000;
            return Math.max(50, (long) (delay * m));
        }
    }

    private WindowManager wm;
    private DisplayMetrics dm;
    private final Handler h = new Handler(Looper.getMainLooper());
    private final List<Point> points = new ArrayList<>();
    private LinearLayout panel;
    private WindowManager.LayoutParams panelLp;
    private TextView playBtn;
    private View menu;
    private String mode = "multi";
    private boolean running = false;
    private int idx = 0;

    private final Runnable stepRunnable = new Runnable() {
        @Override public void run() { step(); }
    };

    @Override public IBinder onBind(Intent i) { return null; }

    @Override public void onCreate() {
        super.onCreate();
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        dm = getResources().getDisplayMetrics();
    }

    @Override public int onStartCommand(Intent i, int flags, int startId) {
        String m = i == null ? null : i.getStringExtra("mode");
        teardown();
        mode = "single".equals(m) ? "single" : "multi";
        buildPanel();
        addPoint();
        return START_NOT_STICKY;
    }

    @Override public void onDestroy() {
        teardown();
        super.onDestroy();
    }

    // ---------- helpers ----------
    private int dp(int v) { return (int) (v * dm.density); }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }

    private GradientDrawable round(int color, int r) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(r);
        return g;
    }

    private WindowManager.LayoutParams lp(int w, int hh, boolean touchable) {
        int flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS;
        if (!touchable) flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        WindowManager.LayoutParams p = new WindowManager.LayoutParams(w, hh,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, flags, PixelFormat.TRANSLUCENT);
        p.gravity = Gravity.TOP | Gravity.START;
        return p;
    }

    private String fmt(double d) {
        return d == Math.floor(d) ? String.valueOf((long) d) : String.valueOf(d);
    }

    private TextView panelBtn(String s, View.OnClickListener c) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(22);
        t.setTextColor(Color.WHITE);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(14), dp(10), dp(14), dp(10));
        if (c != null) t.setOnClickListener(c);
        return t;
    }

    private TextView btn(String s, View.OnClickListener c) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(15);
        t.setTextColor(Color.WHITE);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(14), dp(8), dp(14), dp(8));
        t.setBackground(round(0xFF37474F, dp(8)));
        t.setOnClickListener(c);
        return t;
    }

    private TextView label(String s, int sp, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(Color.WHITE);
        t.setPadding(0, dp(10), 0, 0);
        if (bold) t.setTypeface(null, android.graphics.Typeface.BOLD);
        return t;
    }

    private void addTo(LinearLayout parent, View v) {
        LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(WRAP, WRAP);
        l.topMargin = dp(8);
        parent.addView(v, l);
    }

    private void addSeek(LinearLayout parent, int max, int progress, final OnVal cb) {
        SeekBar s = new SeekBar(this);
        s.setMax(max);
        s.setProgress(progress);
        s.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int v, boolean user) { if (user) cb.v(v); }
            @Override public void onStartTrackingTouch(SeekBar sb) { }
            @Override public void onStopTrackingTouch(SeekBar sb) { }
        });
        parent.addView(s, new LinearLayout.LayoutParams(-1, WRAP));
    }

    private void refreshUnits(TextView[] us, int sel) {
        for (int i = 0; i < us.length; i++)
            us[i].setBackground(round(i == sel ? 0xFF1E88E5 : 0xFF37474F, dp(8)));
    }

    // ---------- panel ----------
    private void buildPanel() {
        panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.HORIZONTAL);
        panel.setGravity(Gravity.CENTER_VERTICAL);
        panel.setBackground(round(0xE6222222, dp(30)));

        TextView handle = panelBtn("\u22ee\u22ee", null);
        panelLp = lp(WRAP, WRAP, true);
        panelLp.x = dp(12);
        panelLp.y = dp(120);
        final float[] d = new float[4];
        handle.setOnTouchListener((v, e) -> {
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
        panel.addView(handle);

        playBtn = panelBtn("\u25b6", v -> onPlayPressed());
        panel.addView(playBtn);
        if (mode.equals("multi")) {
            panel.addView(panelBtn("+", v -> { if (!running) addPoint(); }));
            panel.addView(panelBtn("\u2212", v -> removePoint()));
        }
        panel.addView(panelBtn("\u2715", v -> stopSelf()));
        wm.addView(panel, panelLp);
    }

    private void onPlayPressed() {
        if (running) { stopRun(); return; }
        if (mode.equals("multi") && !points.isEmpty() && points.get(0).active) {
            toast("Active mode: tap point 1 to start");
            return;
        }
        startRun();
    }

    // ---------- points ----------
    private void styleView(Point p) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(p.active ? 0xFF2E7D32 : 0xFFE53935);
        g.setStroke(4, Color.WHITE);
        p.view.setBackground(g);
        p.view.setAlpha(p.opacity / 100f);
    }

    private void setSize(Point p, int s) {
        float cx = p.lp.x + p.size / 2f, cy = p.lp.y + p.size / 2f;
        p.size = s;
        p.lp.width = s;
        p.lp.height = s;
        p.lp.x = (int) (cx - s / 2f);
        p.lp.y = (int) (cy - s / 2f);
        p.view.setTextSize(TypedValue.COMPLEX_UNIT_PX, s * 0.38f);
        wm.updateViewLayout(p.view, p.lp);
    }

    private void setOpacity(Point p, int o) {
        p.opacity = o;
        p.view.setAlpha(o / 100f);
    }

    private void refreshLabels() {
        for (int i = 0; i < points.size(); i++) points.get(i).view.setText(String.valueOf(i + 1));
    }

    private void addPoint() {
        final Point p = new Point();
        if (!points.isEmpty()) {
            Point l = points.get(points.size() - 1);
            p.delay = l.delay; p.unit = l.unit; p.size = l.size; p.opacity = l.opacity;
        }
        int n = points.size();
        p.view = new TextView(this);
        p.view.setGravity(Gravity.CENTER);
        p.view.setTextColor(Color.WHITE);
        p.view.setTextSize(TypedValue.COMPLEX_UNIT_PX, p.size * 0.38f);
        p.lp = lp(p.size, p.size, true);
        p.lp.x = dm.widthPixels / 2 - p.size / 2 + (n % 5) * dp(20);
        p.lp.y = dm.heightPixels / 3 + (n % 8) * dp(40);
        styleView(p);
        attachTouch(p);
        wm.addView(p.view, p.lp);
        points.add(p);
        refreshLabels();
    }

    private void removePoint() {
        if (running || points.size() <= 1) return;
        closeMenu();
        Point p = points.remove(points.size() - 1);
        try { wm.removeView(p.view); } catch (Exception ignored) { }
        refreshLabels();
    }

    private void attachTouch(final Point p) {
        final float[] d = new float[5];
        final long[] t = new long[1];
        p.view.setOnTouchListener((v, e) -> {
            switch (e.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    d[0] = p.lp.x; d[1] = p.lp.y; d[2] = e.getRawX(); d[3] = e.getRawY(); d[4] = 0;
                    t[0] = System.currentTimeMillis();
                    return true;
                case MotionEvent.ACTION_MOVE:
                    float dx = e.getRawX() - d[2], dy = e.getRawY() - d[3];
                    if (Math.abs(dx) > 12 || Math.abs(dy) > 12) d[4] = 1;
                    if (d[4] == 1) {
                        p.lp.x = (int) (d[0] + dx);
                        p.lp.y = (int) (d[1] + dy);
                        wm.updateViewLayout(p.view, p.lp);
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                    if (d[4] == 0) onPointTap(p, System.currentTimeMillis() - t[0] >= 500);
                    return true;
            }
            return false;
        });
    }

    private void onPointTap(Point p, boolean longPress) {
        if (running) return;
        if (p.active && !longPress) startRun(); // Active button: real click here + start loop
        else showMenu(p);
    }

    // ---------- run loop ----------
    private void setTouchable(boolean touchable) {
        for (Point p : points) {
            if (touchable) p.lp.flags &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
            else p.lp.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
            try { wm.updateViewLayout(p.view, p.lp); } catch (Exception ignored) { }
        }
    }

    private void startRun() {
        if (running) return;
        if (ClickService.instance == null) { toast("Turn on the accessibility service first"); return; }
        if (points.isEmpty()) { toast("Add a point with +"); return; }
        closeMenu();
        running = true;
        idx = 0;
        setTouchable(false);
        if (playBtn != null) playBtn.setText("\u275a\u275a");
        h.postDelayed(stepRunnable, 150);
    }

    private void stopRun() {
        running = false;
        h.removeCallbacks(stepRunnable);
        setTouchable(true);
        if (playBtn != null) playBtn.setText("\u25b6");
    }

    private void step() {
        if (!running || points.isEmpty()) return;
        if (idx >= points.size()) idx = 0;
        Point p = points.get(idx);
        if (!ClickService.tap(p.lp.x + p.size / 2f, p.lp.y + p.size / 2f)) {
            stopRun();
            toast("Accessibility service is off");
            return;
        }
        idx = (idx + 1) % points.size();
        h.postDelayed(stepRunnable, p.delayMs());
    }

    private void teardown() {
        stopRun();
        closeMenu();
        for (Point p : points) {
            try { wm.removeView(p.view); } catch (Exception ignored) { }
        }
        points.clear();
        if (panel != null) {
            try { wm.removeView(panel); } catch (Exception ignored) { }
            panel = null;
            playBtn = null;
        }
    }

    // ---------- edit menu ----------
    private void closeMenu() {
        if (menu != null) {
            try { wm.removeView(menu); } catch (Exception ignored) { }
            menu = null;
        }
    }

    private void showMenu(final Point p) {
        closeMenu();
        final boolean multi = mode.equals("multi");
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(16), dp(16), dp(16), dp(16));

        c.addView(label(multi ? "Point " + (points.indexOf(p) + 1) : "Single click", 20, true));
        c.addView(label(multi ? "Delay before the next click" : "Click every", 14, false));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        final EditText et = new EditText(this);
        et.setTextColor(Color.WHITE);
        et.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        et.setText(fmt(p.delay));
        et.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int n) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int n) { }
            @Override public void afterTextChanged(Editable s) {
                try { p.delay = Double.parseDouble(s.toString()); } catch (Exception ignored) { }
            }
        });
        row.addView(et, new LinearLayout.LayoutParams(0, WRAP, 1f));
        final TextView[] us = new TextView[3];
        String[] names = {"ms", "sec", "min"};
        for (int i = 0; i < 3; i++) {
            final int k = i;
            us[i] = btn(names[i], v -> { p.unit = k; refreshUnits(us, k); });
            LinearLayout.LayoutParams ul = new LinearLayout.LayoutParams(WRAP, WRAP);
            ul.leftMargin = dp(6);
            row.addView(us[i], ul);
        }
        refreshUnits(us, p.unit);
        c.addView(row);
        if (multi) addTo(c, btn("Apply delay to all", v -> {
            for (Point q : points) { q.delay = p.delay; q.unit = p.unit; }
            toast("Delay applied to all");
        }));

        final TextView sl = label("Size: " + p.size + " px", 14, false);
        c.addView(sl);
        addSeek(c, 180, p.size - 60, val -> {
            setSize(p, 60 + val);
            sl.setText("Size: " + p.size + " px");
        });
        if (multi) addTo(c, btn("Apply size to all", v -> {
            for (Point q : points) setSize(q, p.size);
            toast("Size applied to all");
        }));

        final TextView ol = label("Opacity: " + p.opacity + "%", 14, false);
        c.addView(ol);
        addSeek(c, 90, p.opacity - 10, val -> {
            setOpacity(p, 10 + val);
            ol.setText("Opacity: " + p.opacity + "%");
        });
        if (multi) addTo(c, btn("Apply opacity to all", v -> {
            for (Point q : points) setOpacity(q, p.opacity);
            toast("Opacity applied to all");
        }));

        if (multi && points.get(0) == p) {
            Switch sw = new Switch(this);
            sw.setText("Set as Active button");
            sw.setTextColor(Color.WHITE);
            sw.setChecked(p.active);
            sw.setOnCheckedChangeListener((b, on) -> { p.active = on; styleView(p); });
            addTo(c, sw);
            c.addView(label("ON: tapping this point makes a real click here and starts the loop. "
                    + "Long-press it to open this menu.", 12, false));
        }
        addTo(c, btn("Done", v -> closeMenu()));

        ScrollView sv = new ScrollView(this);
        sv.setBackground(round(0xF0212121, dp(16)));
        sv.addView(c);
        int w = (int) (dm.widthPixels * 0.9f);
        sv.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int hh = Math.min(sv.getMeasuredHeight(), (int) (dm.heightPixels * 0.85f));
        WindowManager.LayoutParams m = new WindowManager.LayoutParams(w, hh,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, 0, PixelFormat.TRANSLUCENT);
        m.gravity = Gravity.CENTER;
        m.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN;
        wm.addView(sv, m);
        menu = sv;
    }
}
