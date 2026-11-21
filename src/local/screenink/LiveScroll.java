package local.screenink;

import android.accessibilityservice.AccessibilityService;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.hardware.HardwareBuffer;
import android.os.Handler;
import android.os.SystemClock;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import java.util.ArrayDeque;

final class LiveScroll implements AutoCloseable {
    interface Host {
        void ready(Rect area, Bitmap toolbarFrame, Rect frameBounds);
        void moved(Rect area, int offset, Bitmap toolbarFrame, Rect frameBounds);
        void failed(String message, boolean end);
    }

    private static final int CAPTURE_INTERVAL = 410;
    private static final int IDLE_DELAY = 145;
    private final AccessibilityService service;
    private final Handler main;
    private final Host host;
    private final int windowId;
    private final Rect screen;
    private final Rect window;
    private final Rect area;
    private final Runnable capture = this::capture;
    private ScrollPage page;
    private boolean closed;
    private boolean pending;
    private boolean dirty;
    private boolean representable = true;
    private boolean allowOccludingApp;
    private int prediction;
    private int previousAbsolute = -1;
    private long lastRequest;
    private Runnable whenReady;

    static LiveScroll create(AccessibilityService service, Handler main, Host host, Rect screen,
                             int backgroundId, boolean occluded) {
        AccessibilityWindowInfo target = null;
        for (AccessibilityWindowInfo window : service.getWindows()) {
            if (window.getType() != AccessibilityWindowInfo.TYPE_APPLICATION
                || window.getDisplayId() != 0) continue;
            if ((backgroundId >= 0 && window.getId() == backgroundId)
                || (backgroundId < 0 && (window.isActive() || window.isFocused()))) {
                target = window;
                break;
            }
        }
        if (target == null) return null;
        Rect window = new Rect();
        target.getBoundsInScreen(window);
        if (!window.intersect(screen)) return null;
        Rect area = getScrollArea(target.getRoot(), window);
        LiveScroll result = new LiveScroll(service, main, host, target.getId(), screen, window, area);
        result.allowOccludingApp = occluded;
        return result;
    }

    private LiveScroll(AccessibilityService service, Handler main, Host host, int windowId,
                       Rect screen, Rect window, Rect area) {
        this.service = service;
        this.main = main;
        this.host = host;
        this.windowId = windowId;
        this.screen = new Rect(screen);
        this.window = window;
        this.area = area;
    }

    private static Rect getScrollArea(AccessibilityNodeInfo root, Rect window) {
        Rect best = new Rect();
        if (root != null) {
            ArrayDeque<AccessibilityNodeInfo> queue = new ArrayDeque<>();
            queue.add(root);
            for (int visited = 0; visited < 160 && !queue.isEmpty(); visited++) {
                AccessibilityNodeInfo node = queue.removeFirst();
                if (node.isScrollable() && node.isVisibleToUser()) {
                    Rect bounds = new Rect();
                    node.getBoundsInScreen(bounds);
                    int actions = node.getActions();
                    boolean vertical = node.getActionList().contains(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP)
                        || node.getActionList().contains(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN)
                        || (actions & (AccessibilityNodeInfo.ACTION_SCROLL_FORWARD | AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)) != 0;
                    if (vertical && bounds.intersect(window) && bounds.height() >= window.height() / 4
                        && bounds.width() >= window.width() / 4
                        && (long) bounds.width() * bounds.height() > (long) best.width() * best.height()) {
                        best.set(bounds);
                    }
                }
                for (int i = 0; i < node.getChildCount() && queue.size() < 160; i++) {
                    AccessibilityNodeInfo child = node.getChild(i);
                    if (child != null) queue.addLast(child);
                }
            }
        }
        if (!best.isEmpty()) return best;
        Rect fallback = new Rect(window);
        fallback.top += fallback.height() / 12;
        fallback.bottom -= fallback.height() / 25;
        return fallback;
    }

    void start() { capture(); }
    boolean started() { return page != null; }
    boolean isDrawingAligned() { return representable; }
    boolean hasExtension() { return page != null && page.hasExtension(); }
    int windowId() { return windowId; }
    void allowOccludingApp(boolean value) { allowOccludingApp = value; }

    void event(AccessibilityEvent event) {
        if (closed || page == null || event.getWindowId() != windowId) return;
        if (event.getEventType() == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            scheduleFrame();
            return;
        }
        if (event.getEventType() != AccessibilityEvent.TYPE_VIEW_SCROLLED) return;
        AccessibilityNodeInfo node = event.getSource();
        if (node != null) {
            Rect scroll = new Rect();
            node.getBoundsInScreen(scroll);
            if (!scroll.intersect(area) || scroll.height() < area.height() / 3) return;
        }
        int absolute = event.getScrollY();
        int reported = event.getScrollDeltaY();
        int delta = previousAbsolute >= 0 && absolute >= 0 && absolute != previousAbsolute
            ? absolute - previousAbsolute : (reported == -1 ? 0 : reported);
        previousAbsolute = absolute;
        if (Math.abs((long) delta) < (long) area.height() * 5) prediction += delta;
        if (representable) host.moved(area, page.offset() + prediction, null, null);
        scheduleFrame();
    }

    private void scheduleFrame() {
        dirty = true;
        long delay = Math.max(IDLE_DELAY, CAPTURE_INTERVAL - (SystemClock.uptimeMillis() - lastRequest));
        if (!pending && !main.hasCallbacks(capture)) main.postDelayed(capture, delay);
    }

    void reconcile(Runnable completion) {
        if (closed || page == null) return;
        whenReady = completion;
        if (!pending) {
            main.removeCallbacks(capture);
            main.postDelayed(capture, Math.max(0, CAPTURE_INTERVAL - (SystemClock.uptimeMillis() - lastRequest)));
        }
    }

    Bitmap compose(InkView canvas, boolean whole) {
        return page == null ? null : page.compose(canvas, area, whole, canvas.viewportScrollOffset(page.offset()));
    }

    boolean freezeViewport(InkView canvas) {
        if (page == null || !representable) return false;
        canvas.freezeScrollFrame(page.copyViewport(), page.offset());
        return true;
    }

    boolean stillFocused() {
        for (AccessibilityWindowInfo candidate : service.getWindows()) {
            if (candidate.getType() == AccessibilityWindowInfo.TYPE_APPLICATION
                && candidate.getId() == windowId && (candidate.isActive() || candidate.isFocused())) return true;
            if (allowOccludingApp && candidate.getType() == AccessibilityWindowInfo.TYPE_APPLICATION
                && candidate.getId() == windowId) return true;
        }
        return false;
    }

    private void capture() {
        if (closed || pending) return;
        if (!stillFocused()) {
            host.failed("当前页面已切换", true);
            return;
        }
        dirty = false;
        pending = true;
        lastRequest = SystemClock.uptimeMillis();
        service.takeScreenshotOfWindow(windowId, service.getMainExecutor(), new AccessibilityService.TakeScreenshotCallback() {
            @Override public void onSuccess(AccessibilityService.ScreenshotResult result) {
                accept(result);
            }

            @Override public void onFailure(int error) {
                if (closed) return;
                pending = false;
                if (error == AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT) {
                    main.postDelayed(capture, CAPTURE_INTERVAL);
                } else {
                    Log.w("ScreenInk", "Scrolling capture denied: " + error);
                    host.failed(error == AccessibilityService.ERROR_TAKE_SCREENSHOT_SECURE_WINDOW
                        ? "此页面禁止截屏" : "当前页面无法滚动批注", true);
                }
            }
        });
    }

    private void accept(AccessibilityService.ScreenshotResult result) {
        Bitmap image = null;
        Bitmap clipped = null;
        try (HardwareBuffer buffer = result.getHardwareBuffer()) {
            if (closed) return;
            Bitmap hardware = Bitmap.wrapHardwareBuffer(buffer, result.getColorSpace());
            if (hardware == null) throw new IllegalStateException("No window bitmap");
            try {
                image = hardware.copy(Bitmap.Config.ARGB_8888, false);
            } finally {
                hardware.recycle();
            }
            if (image == null) throw new IllegalStateException("No software bitmap");
            image.setDensity(Bitmap.DENSITY_NONE);
            Rect source;
            if (image.getWidth() == window.width() && image.getHeight() == window.height()) source = window;
            else if (image.getWidth() == screen.width() && image.getHeight() == screen.height()) source = screen;
            else throw new IllegalStateException("Window geometry changed");
            clipped = Bitmap.createBitmap(area.width(), area.height(), Bitmap.Config.ARGB_8888);
            clipped.setDensity(Bitmap.DENSITY_NONE);
            new Canvas(clipped).drawBitmap(image, source.left - area.left, source.top - area.top, null);
            if (page == null) {
                page = new ScrollPage(clipped);
                clipped = null;
                host.ready(area, image, source);
                Log.i("ScreenInk", "Live scroll layer ready; viewport=" + area.toShortString());
            } else {
                int[] samples = page.sample(clipped);
                Integer delta = page.displacement(samples, prediction);
                boolean aligned = delta != null && page.append(clipped, samples, delta);
                if (!aligned && representable) {
                    host.failed(delta == null ? "页面变化过大，慢慢滑回上一段再继续" : "已达到长图上限，可以保存", false);
                }
                representable = aligned;
                if (aligned) {
                    prediction = 0;
                    host.moved(area, page.offset(), image, source);
                    if (delta != 0) Log.i("ScreenInk", "Scroll alignment delta=" + delta + "; offset=" + page.offset());
                }
            }
        } catch (RuntimeException failure) {
            Log.w("ScreenInk", "Could not read scroll frame", failure);
            if (!closed) host.failed("当前页面无法滚动批注", true);
        } finally {
            if (clipped != null) clipped.recycle();
            if (image != null) image.recycle();
            pending = false;
        }
        if (closed) return;
        if (whenReady != null) {
            Runnable done = whenReady;
            whenReady = null;
            done.run();
        }
        if (!closed && dirty) main.postDelayed(capture, CAPTURE_INTERVAL);
    }

    @Override public void close() {
        closed = true;
        main.removeCallbacks(capture);
        if (page != null) page.close();
    }
}
