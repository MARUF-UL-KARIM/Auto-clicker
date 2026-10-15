package com.example.autotapper;

import android.app.Service;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
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
        boolean active = false, startOnRelease = false, stop = false, locked = false;
        TextView view;
        WindowManager.LayoutParams lp;

        long delayMs() {
            double m = unit == 0 ? 1 : unit == 1 ? 1000 : 60000;
            return Math.max(1, (long) (delay * m));
        }
    }

    private WindowManager wm;
    private DisplayMetrics dm;
    private int slop;
    private final Handler h = new Handler(Looper.getMainLooper());
    private final List<Point> points = new ArrayList<>();
    private LinearLayout panel;
    private WindowManager.LayoutParams panelLp;
    private TextView playBtn, handle;
    private View menu, lockPopup, detector;
    private String mode = "multi";
    private boolean running = false, collapsed = false;
    private int idx = 0, runId = 0;
    private long yieldUntil = 0, lastDispatch = 0, lastComplete = 0, popupClosedAt = 0;

    private final Runnable stepRunnable = new Runnable() {
        @Override public void run() { step(); }
    };
    // safety net: if the system never reports the gesture result, keep the loop alive
    private final Runnable watchdog = new Runnable() {
        @Override public void run() { step(); }
    };

    @Override public IBinder onBind(Intent i) { return null; }

    @Override public void onCreate() {
        super.onCreate();
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        dm = getResources().getDisplayMetrics();
        slop = ViewConfiguration.get(this).getScaledTouchSlop();
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

    private void userTouched(long ms) {
        yieldUntil = Math.max(yieldUntil, SystemClock.uptimeMillis() + ms);
    }

    private GradientDrawable round(int color, int r) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(r);
        return g;
    }

    private WindowManager.LayoutParams lp(int w, int hh, boolean touchable) {
        int flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;
        if (!touchable) flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        WindowManager.LayoutParams p = new WindowManager.LayoutParams(w, hh,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, flags, PixelFormat.TRANSLUCENT);
        p.gravity = Gravity.TOP | Gravity.START;
        if (Build.VERSION.SDK_INT >= 28)
            p.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        return p;
    }

    private String fmt(double d) {
        return d == Math.floor(d) ? String.valueOf((long) d) : String.valueOf(d);
    }

    private TextView makeBtn(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(22);
        t.setTextColor(Color.WHITE);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(14), dp(10), dp(14), dp(10));
        return t;
    }

    /** Panel button: pauses the loop the moment it is touched; acts on DOWN while running (instant stop). */
    private TextView panelBtn(String s, final Runnable click, final boolean actOnDownWhenRunning) {
        TextView t = makeBtn(s);
        final boolean[] fired = new boolean[1];
        t.setOnTouchListener((v, e) -> {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    userTouched(900);
                    fired[0] = false;
                    v.setAlpha(0.6f);
                    if (actOnDownWhenRunning && running) { fired[0] = true; click.run(); }
                    return true;
                case MotionEvent.ACTION_UP:
                    v.setAlpha(1f);
                    if (!fired[0] && e.getX() >= 0 && e.getX() <= v.getWidth()
                            && e.getY() >= 0 && e.getY() <= v.getHeight()) click.run();
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    v.setAlpha(1f);
                    return true;
            }
            return false;
        });
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

    // ---------- main floating panel ----------
    private void buildPanel() {
        collapsed = false;
        panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.HORIZONTAL);
        panel.setGravity(Gravity.CENTER_VERTICAL);
        panel.setBackground(round(0xE6222222, dp(30)));
        panelLp = lp(WRAP, WRAP, true);
        panelLp.x = dp(12);
        panelLp.y = dp(120);

        // handle: drag to move, tap to fold / unfold
        handle = makeBtn("\u22ee");
        final float[] d = new float[5];
        handle.setOnTouchListener((v, e) -> {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    userTouched(900);
                    d[0] = panelLp.x; d[1] = panelLp.y; d[2] = e.getRawX(); d[3] = e.getRawY(); d[4] = 0;
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    float dx = e.getRawX() - d[2], dy = e.getRawY() - d[3];
                    if (d[4] == 0 && (Math.abs(dx) > slop || Math.abs(dy) > slop)) { d[4] = 1; closeLockPopup(); }
                    panelLp.x = (int) (d[0] + dx);
                    panelLp.y = (int) (d[1] + dy);
                    wm.updateViewLayout(panel, panelLp);
                    return true;
                }
                case MotionEvent.ACTION_UP:
                    if (d[4] == 0) {
                        panelLp.x = (int) d[0]; panelLp.y = (int) d[1];
                        wm.updateViewLayout(panel, panelLp);
                        setCollapsed(!collapsed);
                    }
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    if (d[4] == 0) {
                        panelLp.x = (int) d[0]; panelLp.y = (int) d[1];
                        wm.updateViewLayout(panel, panelLp);
                    }
                    return true;
            }
            return false;
        });
        panel.addView(handle);

        playBtn = panelBtn("\u25b6", () -> onPlayPressed(), true);
        panel.addView(playBtn);
        if (mode.equals("multi")) {
            panel.addView(panelBtn("+", () -> { if (!running) addPoint(); }, false));
            panel.addView(panelBtn("\u2212", () -> removePoint(), false));
            panel.addView(panelBtn("\uD83D\uDD12", () -> toggleLockPopup(), false));
        }
        panel.addView(panelBtn("\u2715", () -> stopSelf(), true));
        wm.addView(panel, panelLp);
    }

    private void setCollapsed(boolean c) {
        collapsed = c;
        closeLockPopup();
        for (int i = 1; i < panel.getChildCount(); i++)
            panel.getChildAt(i).setVisibility(c ? View.GONE : View.VISIBLE);
        handle.setText(c ? "\u2261" : "\u22ee");
        try { wm.updateViewLayout(panel, panelLp); } catch (Exception ignored) { }
    }

    private void onPlayPressed() {
        if (running) { stopRun(); return; }
        if (mode.equals("multi") && !points.isEmpty() && points.get(0).active) {
            toast("Active mode: tap point 1 to start");
            return;
        }
        startRun();
    }

    // ---------- lock popup (Lock all / Unlock all) ----------
    private void toggleLockPopup() {
        if (lockPopup != null) { closeLockPopup(); return; }
        if (SystemClock.uptimeMillis() - popupClosedAt < 300) return; // closed by the same touch
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(dp(8), dp(8), dp(8), dp(8));
        l.setBackground(round(0xF0222222, dp(14)));
        addTo(l, btn("Lock all", v -> {
            for (Point q : points) { q.locked = true; styleView(q); }
            closeLockPopup();
            toast("All points locked");
        }));
        addTo(l, btn("Unlock all", v -> {
            for (Point q : points) { q.locked = false; styleView(q); }
            closeLockPopup();
            toast("All points unlocked");
        }));
        l.setOnTouchListener((v, e) -> {
            if (e.getActionMasked() == MotionEvent.ACTION_OUTSIDE) closeLockPopup();
            return false;
        });
        WindowManager.LayoutParams m = new WindowManager.LayoutParams(WRAP, WRAP,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        m.gravity = Gravity.TOP | Gravity.START;
        m.x = panelLp.x;
        m.y = panelLp.y + Math.max(panel.getHeight(), dp(48)) + dp(8);
        wm.addView(l, m);
        lockPopup = l;
    }

    private void closeLockPopup() {
        if (lockPopup != null) {
            try { wm.removeView(lockPopup); } catch (Exception ignored) { }
            lockPopup = null;
            popupClosedAt = SystemClock.uptimeMillis();
        }
    }

    // ---------- points ----------
    private void styleView(Point p) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(p.stop ? 0xFF212121 : p.active ? 0xFF2E7D32 : 0xFFE53935);
        g.setStroke(p.locked ? 8 : 4, p.locked ? 0xFFFFC107 : Color.WHITE);
        p.view.setBackground(g);
        applyAlpha(p);
    }

    // While running the exact opacity is used (0% = invisible).
    // While editing, points below 15% show a faint ghost so you can still find and edit them.
    private void applyAlpha(Point p) {
        float a = p.opacity / 100f;
        if (!running && a < 0.15f) a = 0.15f;
        p.view.setAlpha(a);
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
        applyAlpha(p);
    }

    private void refreshLabels() {
        for (int i = 0; i < points.size(); i++) {
            Point p = points.get(i);
            p.view.setText(p.stop ? "\u25a0" : String.valueOf(i + 1));
        }
    }

    private void addPoint() {
        final Point p = new Point();
        if (!points.isEmpty()) {
            Point l = points.get(points.size() - 1);
            p.delay = l.delay; p.unit = l.unit; p.size = l.size; p.opacity = l.opacity;
            if (l.stop) { l.stop = false; styleView(l); toast("Stop button removed: set it again on the new last point"); }
        }
        int n = points.size();
        // no offscreen layer when alpha < 1 -> smoother dragging
        p.view = new TextView(this) {
            @Override public boolean hasOverlappingRendering() { return false; }
        };
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

    private void restore(Point p, float[] d) {
        p.lp.x = (int) d[0];
        p.lp.y = (int) d[1];
        wm.updateViewLayout(p.view, p.lp);
    }

    private void attachTouch(final Point p) {
        // d: 0 startX, 1 startY, 2 downRawX, 3 downRawY, 4 moved, 5 consumed
        final float[] d = new float[6];
        final Runnable longPress = new Runnable() {
            @Override public void run() {
                d[5] = 1;
                if (p.locked) toast("Locked"); else showMenu(p);
            }
        };
        p.view.setOnTouchListener((v, e) -> {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    d[0] = p.lp.x; d[1] = p.lp.y; d[2] = e.getRawX(); d[3] = e.getRawY(); d[4] = 0; d[5] = 0;
                    if (running) {
                        if (p.stop) { d[5] = 1; stopRun(); } // stop bubble: stops the loop, no click is applied
                        return true;
                    }
                    if (p.active) h.postDelayed(longPress, p.startOnRelease ? 3000 : 500);
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    if (d[5] == 1) return true;
                    float dx = e.getRawX() - d[2], dy = e.getRawY() - d[3];
                    if (d[4] == 0 && (Math.abs(dx) > slop || Math.abs(dy) > slop)) {
                        d[4] = 1;
                        h.removeCallbacks(longPress);
                    }
                    if (!p.locked) { // follows the finger from the first pixel
                        p.lp.x = (int) (d[0] + dx);
                        p.lp.y = (int) (d[1] + dy);
                        wm.updateViewLayout(p.view, p.lp);
                    }
                    return true;
                }
                case MotionEvent.ACTION_UP:
                    h.removeCallbacks(longPress);
                    if (d[5] == 1) return true;
                    if (d[4] == 0) {
                        if (!p.locked) restore(p, d);
                        if (p.active) startRun();          // Active button: real click here + start loop
                        else if (p.locked) toast("Locked");
                        else showMenu(p);
                    }
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    h.removeCallbacks(longPress);
                    if (d[5] != 1 && d[4] == 0 && !p.locked) restore(p, d);
                    return true;
            }
            return false;
        });
    }

    // ---------- run loop ----------
    private void setTouchable(boolean touchable) {
        for (Point p : points) {
            boolean t = touchable || p.stop; // stop bubble stays touchable while running
            if (t) p.lp.flags &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
            else p.lp.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
            try { wm.updateViewLayout(p.view, p.lp); } catch (Exception ignored) { }
        }
    }

    private void addDetector() {
        if (detector != null) return;
        View v = new View(this);
        v.setOnTouchListener((x, e) -> {
            if (e.getActionMasked() == MotionEvent.ACTION_OUTSIDE) onOutsideTouch();
            return false;
        });
        WindowManager.LayoutParams m = new WindowManager.LayoutParams(1, 1,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        m.gravity = Gravity.TOP | Gravity.START;
        try { wm.addView(v, m); detector = v; } catch (Exception ignored) { }
    }

    private void removeDetector() {
        if (detector != null) {
            try { wm.removeView(detector); } catch (Exception ignored) { }
            detector = null;
        }
    }

    // A touch somewhere else on screen (not caused by our own tap): let the user work normally.
    private void onOutsideTouch() {
        long now = SystemClock.uptimeMillis();
        if (now - lastDispatch < 120 || now - lastComplete < 80) return; // our own tap
        yieldUntil = Math.max(yieldUntil, now + 1500);
    }

    private void startRun() {
        if (running) return;
        if (ClickService.instance == null) { toast("Turn on the accessibility service first"); return; }
        if (points.isEmpty()) { toast("Add a point with +"); return; }
        closeMenu();
        closeLockPopup();
        running = true;
        runId++;
        idx = 0;
        yieldUntil = 0;
        setTouchable(false);
        for (Point q : points) applyAlpha(q);
        addDetector();
        if (playBtn != null) playBtn.setText("\u275a\u275a");
        h.postDelayed(stepRunnable, 150);
    }

    private void stopRun() {
        running = false;
        runId++;
        h.removeCallbacks(stepRunnable);
        h.removeCallbacks(watchdog);
        removeDetector();
        setTouchable(true);
        for (Point q : points) applyAlpha(q);
        if (playBtn != null) playBtn.setText("\u25b6");
    }

    private void step() {
        if (!running || points.isEmpty()) return;
        h.removeCallbacks(stepRunnable);
        h.removeCallbacks(watchdog);
        long now = SystemClock.uptimeMillis();
        long wait = now < yieldUntil ? yieldUntil - now : 0;
        if (lockPopup != null) wait = Math.max(wait, 300);
        if (wait > 0) { h.postDelayed(stepRunnable, wait); return; }

        int n = points.size();
        Point p = null;
        for (int k = 0; k < n && p == null; k++) {
            Point c = points.get(idx % n);
            idx = (idx + 1) % n;
            if (!c.stop) p = c; // the stop bubble never clicks
        }
        if (p == null) { stopRun(); return; }

        // real on-screen position of the point (accurate with status bar, cutout, keyboard, etc.)
        int[] loc = new int[2];
        p.view.getLocationOnScreen(loc);
        final long start = now;
        final long delay = p.delayMs();
        final int id = runId;
        lastDispatch = now;
        boolean ok = ClickService.tap(loc[0] + p.size / 2f, loc[1] + p.size / 2f, new ClickService.Done() {
            @Override public void run(boolean completed) {
                if (!running || id != runId) return;
                h.removeCallbacks(watchdog);
                long t = SystemClock.uptimeMillis();
                lastComplete = t;
                if (!completed) yieldUntil = Math.max(yieldUntil, t + 700); // someone touched the screen
                h.postDelayed(stepRunnable, Math.max(0, start + delay - t));
            }
        });
        if (!ok) {
            stopRun();
            toast("Accessibility service is off");
            return;
        }
        h.postDelayed(watchdog, 1500);
    }

    private void teardown() {
        stopRun();
        closeMenu();
        closeLockPopup();
        for (Point p : points) {
            try { wm.removeView(p.view); } catch (Exception ignored) { }
        }
        points.clear();
        if (panel != null) {
            try { wm.removeView(panel); } catch (Exception ignored) { }
            panel = null;
            playBtn = null;
            handle = null;
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
        closeLockPopup();
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
        addSeek(c, 100, p.opacity, val -> {
            setOpacity(p, val);
            ol.setText("Opacity: " + p.opacity + "%");
        });
        c.addView(label("0% = fully invisible while running (a faint ghost is shown here for editing).", 11, false));
        if (multi) addTo(c, btn("Apply opacity to all", v -> {
            for (Point q : points) setOpacity(q, p.opacity);
            toast("Opacity applied to all");
        }));

        if (multi) {
            final Switch lk = new Switch(this);
            lk.setText("Lock this point");
            lk.setTextColor(Color.WHITE);
            lk.setChecked(p.locked);
            lk.setOnCheckedChangeListener((b, on) -> { p.locked = on; styleView(p); });
            addTo(c, lk);
            c.addView(label("Locked: can't be moved and its menu won't open. Unlock from the \uD83D\uDD12 button on the main panel.", 11, false));
            addTo(c, btn("Lock all", v -> {
                for (Point q : points) { q.locked = true; styleView(q); }
                lk.setChecked(true);
                toast("All points locked");
            }));
        }

        Switch sw = null, sr = null;
        if (multi && points.get(0) == p) {
            sw = new Switch(this);
            sw.setText("Set as Active button");
            sw.setTextColor(Color.WHITE);
            sw.setChecked(p.active);
            addTo(c, sw);
            c.addView(label("ON: tap this point = real click here + loop starts (it does not auto-click the first time). "
                    + "Hold 0.5s = open this menu.", 11, false));
            sr = new Switch(this);
            sr.setText("Start when release");
            sr.setTextColor(Color.WHITE);
            sr.setChecked(p.startOnRelease);
            sr.setOnCheckedChangeListener((b, on) -> p.startOnRelease = on);
            addTo(c, sr);
            final TextView srHint = label("Hold does nothing, the loop starts when you let go. Hold 3s = open this menu.", 11, false);
            c.addView(srHint);
            final Switch fsr = sr;
            sw.setOnCheckedChangeListener((b, on) -> {
                p.active = on;
                styleView(p);
                fsr.setVisibility(on ? View.VISIBLE : View.GONE);
                srHint.setVisibility(on ? View.VISIBLE : View.GONE);
            });
            if (!p.active) { sr.setVisibility(View.GONE); srHint.setVisibility(View.GONE); }
        }

        if (multi && points.size() >= 2 && points.get(points.size() - 1) == p) {
            Switch st = new Switch(this);
            st.setText("Set as Stop button");
            st.setTextColor(Color.WHITE);
            st.setChecked(p.stop);
            st.setOnCheckedChangeListener((b, on) -> { p.stop = on; styleView(p); refreshLabels(); });
            addTo(c, st);
            c.addView(label("While the loop runs, tap this bubble to stop it. It never clicks on the screen.", 11, false));
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
