package local.screenink;

import android.content.ContentResolver;
import android.content.Context;
import android.database.ContentObserver;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.view.InputDevice;
import android.view.KeyEvent;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public final class PenButtonHook implements IXposedHookLoadPackage {
    private static final String TAG = "ScreenInkPen";
    private static final int PRIMARY = KeyEvent.KEYCODE_PAGE_DOWN;
    private static final int SECONDARY = KeyEvent.KEYCODE_PAGE_UP;
    private static final long HOLD_MS = 400;
    private static final long STATE_CHECK_MS = 1_500;

    private final Press[] presses = new Press[2];
    private final Runnable checkState = this::refreshState;
    private volatile Handler worker;
    private ContentResolver resolver;
    private volatile long session;

    @Override public void handleLoadPackage(XC_LoadPackage.LoadPackageParam load) {
        if (!"android".equals(load.packageName) || !"android".equals(load.processName)) return;
        try {
            XposedHelpers.findAndHookMethod(
                "com.android.server.policy.MiuiPhoneWindowManager", load.classLoader,
                "interceptKeyBeforeQueueing", KeyEvent.class, int.class, new XC_MethodHook(10_000) {
                    @Override protected void beforeHookedMethod(MethodHookParam param) {
                        if (worker != null && handle((KeyEvent) param.args[0])) param.setResult(0);
                    }
                });
        } catch (Throwable failure) {
            Log.w(TAG, "Pen shortcuts unavailable on this system", failure);
            return;
        }
        HandlerThread thread = new HandlerThread(TAG);
        thread.start();
        worker = new Handler(thread.getLooper());
        worker.post(this::watchState);
        Log.i(TAG, "Pen shortcuts hook installed");
    }

    private synchronized boolean handle(KeyEvent event) {
        if (event == null) return false;
        int key = event.getKeyCode();
        if (key != PRIMARY && key != SECONDARY) return false;
        InputDevice device = event.getDevice();
        if (device == null || !"Xiaomi Smart Pen Keyboard".equals(device.getName())) return false;

        int index = key == PRIMARY ? 0 : 1;
        Press press = presses[index];
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            if (event.getRepeatCount() > 0) return press != null && press.matches(event);
            if (press != null) worker.removeCallbacks(press.hold);
            presses[index] = null;
            long active = session;
            if (active == 0) return false;
            Press next = new Press(active, key == PRIMARY, event);
            presses[index] = next;
            if (next.primary) worker.postAtTime(next.hold, next.downTime + HOLD_MS);
            return true;
        }
        if (event.getAction() != KeyEvent.ACTION_UP || press == null || !press.matches(event)) return false;
        presses[index] = null;
        worker.removeCallbacks(press.hold);
        if (!event.isCanceled()) {
            boolean held = event.getEventTime() - press.downTime >= HOLD_MS;
            worker.post(() -> {
                if (held) press.hold.run();
                else if (!press.longSent) send(press.session,
                    press.primary ? PenShortcuts.NEXT_BRUSH : PenShortcuts.NEXT_COLOR);
            });
        }
        return true;
    }

    private void watchState() {
        try {
            Class<?> activityThread = Class.forName("android.app.ActivityThread");
            Object thread = activityThread.getMethod("currentActivityThread").invoke(null);
            Context context = (Context) activityThread.getMethod("getSystemContext").invoke(thread);
            ContentResolver candidate = context.getContentResolver();
            candidate.registerContentObserver(PenShortcuts.URI, false, new ContentObserver(worker) {
                @Override public void onChange(boolean selfChange) { refreshState(); }
            });
            resolver = candidate;
        } catch (Exception startup) {
            worker.postDelayed(this::watchState, STATE_CHECK_MS);
            return;
        }
        refreshState();
    }

    private void refreshState() {
        try {
            Bundle state = resolver.call(PenShortcuts.URI, PenShortcuts.STATE, null, null);
            session = state == null ? 0 : state.getLong(PenShortcuts.SESSION);
        } catch (RuntimeException unavailable) {
            session = 0;
        }
        worker.removeCallbacks(checkState);
        if (session != 0) worker.postDelayed(checkState, STATE_CHECK_MS);
    }

    private void send(long token, String action) {
        if (session != token) return;
        Bundle extras = new Bundle();
        extras.putLong(PenShortcuts.SESSION, token);
        try {
            resolver.call(PenShortcuts.URI, action, null, extras);
        } catch (RuntimeException unavailable) {
            session = 0;
        }
    }

    private final class Press {
        final long session;
        final boolean primary;
        final long downTime;
        final int device;
        final Runnable hold;
        boolean longSent;

        Press(long session, boolean primary, KeyEvent event) {
            this.session = session;
            this.primary = primary;
            downTime = event.getDownTime();
            device = event.getDeviceId();
            hold = () -> {
                if (!this.primary || longSent) return;
                longSent = true;
                send(this.session, PenShortcuts.TEMPORARY_ERASER);
            };
        }

        boolean matches(KeyEvent event) {
            return downTime == event.getDownTime() && device == event.getDeviceId();
        }
    }
}
