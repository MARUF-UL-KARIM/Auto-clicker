package com.example.autotapper;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.accessibility.AccessibilityEvent;

public class ClickService extends AccessibilityService {
    static ClickService instance;
    private static Handler main;

    @Override protected void onServiceConnected() {
        instance = this;
        main = new Handler(Looper.getMainLooper());
    }
    @Override public void onAccessibilityEvent(AccessibilityEvent e) { }
    @Override public void onInterrupt() { }
    @Override public boolean onUnbind(android.content.Intent i) { instance = null; return super.onUnbind(i); }

    /** Sends one tap. 'done' runs when the system finished (or cancelled) the gesture. */
    static boolean tap(float x, float y, final Runnable done) {
        ClickService s = instance;
        if (s == null) return false;
        try {
            DisplayMetrics dm = s.getResources().getDisplayMetrics();
            x = Math.max(0, Math.min(x, dm.widthPixels - 1));
            y = Math.max(0, Math.min(y, dm.heightPixels - 1));
            Path p = new Path();
            p.moveTo(x, y);
            GestureDescription g = new GestureDescription.Builder()
                    .addStroke(new GestureDescription.StrokeDescription(p, 0, 5)).build();
            AccessibilityService.GestureResultCallback cb = new AccessibilityService.GestureResultCallback() {
                @Override public void onCompleted(GestureDescription d) { if (done != null) done.run(); }
                @Override public void onCancelled(GestureDescription d) { if (done != null) done.run(); }
            };
            return s.dispatchGesture(g, cb, main);
        } catch (Exception e) {
            return false;
        }
    }
}
