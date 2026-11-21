package local.screenink;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.accessibilityservice.TouchInteractionController;
import android.app.KeyguardManager;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Region;
import android.graphics.RenderEffect;
import android.graphics.RenderNode;
import android.graphics.Shader;
import android.hardware.HardwareBuffer;
import android.net.Uri;
import android.os.Environment;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.util.Log;
import android.view.Display;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.widget.FrameLayout;
import android.widget.Toast;
import java.io.IOException;
import java.io.OutputStream;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class InkService extends AccessibilityService {
    private static final String TAG = "ScreenInk";
    private static final DateTimeFormatter FILE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS");
    private static volatile InkService active;
    static volatile long shortcutSession;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService files = Executors.newSingleThreadExecutor();
    private WindowManager windows;
    private FrameLayout layer;
    private InkView canvas;
    private FrameLayout toolLayer;
    private InkToolbar toolbar;
    private View frost;
    private final Paint frostPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private Bitmap frostFrame;
    private Rect frostBounds;
    private LiveScroll scrolling;
    private FingerInput fingerInput;
    private FloatingApps floatingApps;
    private ComponentName startingApp;
    private long launchStarted;
    private int floatingId = -1;
    private int documentId = -1;
    private Rect floatingArea;
    private InkPreview preview;
    private boolean showing;
    private boolean capturing;
    private boolean saving;
    private boolean disposed;
    private boolean saveRequested;
    private View penControl;
    private boolean penOnCanvas;
    private boolean receiverRegistered;
    private boolean listeningForScroll;
    private boolean zoomLocked;
    private boolean routingFingers;
    private int session;
    private final Runnable checkTarget = () -> {
        if (showing) syncAppWindows();
    };
    private final BroadcastReceiver screenOff = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) { closeDocument(); }
    };

    static InkService connected() { return active; }

    void penShortcut(long token, String action) {
        main.post(() -> {
            if (active != this || !showing || token != shortcutSession) return;
            switch (action) {
                case PenShortcuts.NEXT_BRUSH: toolbar.nextBrush(); break;
                case PenShortcuts.TEMPORARY_ERASER: toolbar.armEraser(); break;
                case PenShortcuts.NEXT_COLOR: toolbar.nextColor(); break;
                default: return;
            }
            Log.i(TAG, "Pen shortcut " + action);
        });
    }

    @Override protected void onServiceConnected() {
        super.onServiceConnected();
        windows = getSystemService(WindowManager.class);
        preview = new InkPreview(this, windows, this::closeDocument);
        floatingApps = new FloatingApps(this, windows);
        if (Build.VERSION.SDK_INT >= 34) buildFingerInput();
        active = this;
        if (!receiverRegistered) {
            registerReceiver(screenOff, new IntentFilter(Intent.ACTION_SCREEN_OFF));
            receiverRegistered = true;
        }
        Log.i(TAG, "Accessibility service connected");
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event.getEventType() == AccessibilityEvent.TYPE_VIEW_SCROLLED
            || event.getEventType() == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            if (scrolling != null) scrolling.event(event);
        } else if (showing && !main.hasCallbacks(checkTarget)) {
            main.postDelayed(checkTarget, 100);
        }
    }

    @Override public void onMotionEvent(MotionEvent event) {
        if (!showing || !canvasPassesThrough() || event.getPointerCount() == 0) return;
        int toolType = event.getToolType(0);
        if (toolType != MotionEvent.TOOL_TYPE_STYLUS && toolType != MotionEvent.TOOL_TYPE_ERASER) return;
        int action = event.getActionMasked();
        float x = event.getRawX();
        float y = event.getRawY();
        if (action == MotionEvent.ACTION_HOVER_ENTER || action == MotionEvent.ACTION_HOVER_MOVE ||
            action == MotionEvent.ACTION_HOVER_EXIT) {
            if (penControl == null && !penOnCanvas) {
                if (controlAt(x, y) != null || !canvas.acceptsPenAt(x, y)) canvas.hideHover();
                else canvas.onHoverEvent(event);
            }
            return;
        }
        if (action == MotionEvent.ACTION_DOWN) {
            penControl = controlAt(x, y);
            boolean menuOpen = toolbar.optionsShowing() || floatingApps.isShowing();
            penOnCanvas = penControl == null && !menuOpen && (scrolling == null || scrolling.isDrawingAligned())
                && canvas.acceptsPenAt(x, y);
            if (!penOnCanvas) canvas.hideHover();
            if (menuOpen && penControl == null) {
                toolbar.dismissOptions();
                floatingApps.dismiss();
            }
        }
        if (penControl != null) {
            dispatchToControl(penControl, event, x, y);
        } else if (penOnCanvas) {
            canvas.onTouchEvent(event);
        }
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            penControl = null;
            penOnCanvas = false;
        }
    }

    private View controlAt(float x, float y) {
        View choice = floatingApps.pickerView();
        if (hits(choice, x, y)) return choice;
        View options = toolbar.optionsView();
        if (hits(options, x, y)) return options;
        View thumbnail = preview.touchView();
        if (hits(thumbnail, x, y)) return thumbnail;
        if (!hits(toolbar, x, y)) return null;
        int[] location = new int[2];
        toolbar.getLocationOnScreen(location);
        return toolbar.containsPanel(x - location[0], y - location[1]) ? toolbar : null;
    }

    private static boolean hits(View view, float x, float y) {
        if (view == null || !view.isAttachedToWindow() || !view.isShown()) return false;
        int[] location = new int[2];
        view.getLocationOnScreen(location);
        return x >= location[0] && x < location[0] + view.getWidth()
            && y >= location[1] && y < location[1] + view.getHeight();
    }

    private static void dispatchToControl(View view, MotionEvent original, float x, float y) {
        if (!view.isAttachedToWindow()) return;
        int action = original.getActionMasked();
        if (action != MotionEvent.ACTION_DOWN && action != MotionEvent.ACTION_MOVE
            && action != MotionEvent.ACTION_UP && action != MotionEvent.ACTION_CANCEL) return;
        int[] location = new int[2];
        view.getLocationOnScreen(location);
        MotionEvent touch = MotionEvent.obtain(original.getDownTime(), original.getEventTime(), action,
            x - location[0], y - location[1], 0);
        touch.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        try {
            view.dispatchTouchEvent(touch);
        } finally {
            touch.recycle();
        }
    }
    @Override public void onInterrupt() { }

    @Override public boolean onUnbind(Intent intent) {
        detachService();
        return super.onUnbind(intent);
    }

    @Override public void onDestroy() {
        detachService();
        disposed = true;
        if (receiverRegistered) {
            unregisterReceiver(screenOff);
            receiverRegistered = false;
        }
        main.removeCallbacksAndMessages(null);
        files.shutdown();
        super.onDestroy();
    }

    private void detachService() {
        closeDocument();
        if (active == this) active = null;
    }

    void openAfterLauncher() {
        main.postDelayed(this::openDocument, 170);
    }

    private void openDocument() {
        if (showing || capturing || active != this || windows == null) return;
        KeyguardManager keyguard = getSystemService(KeyguardManager.class);
        if (keyguard != null && keyguard.isKeyguardLocked()) return;
        if (layer == null) buildLayer();
        if (canvas.hasReference()) {
            canvas.discardDocument();
        }
        preview.dismiss();
        configureEvents(true);
        capturing = true;
        final int request = session;
        main.postDelayed(() -> {
            if (active != this || session != request) return;
            captureDocument(request);
        }, 125);
    }

    private void captureDocument(int request) {
        List<AccessibilityWindowInfo> current = getWindows();
        Rect screen = windows.getCurrentWindowMetrics().getBounds();
        AccessibilityWindowInfo mainApp = backWindow(current, screen);
        documentId = mainApp == null ? -1 : mainApp.getId();
        Rect previousFloat = floatingBounds(current, floatingId, screen);
        boolean useWindow = Build.VERSION.SDK_INT >= 34 && mainApp != null && previousFloat != null;
        Rect bounds = new Rect();
        if (useWindow) mainApp.getBoundsInScreen(bounds);
        try {
            TakeScreenshotCallback callback = new TakeScreenshotCallback() {
                @Override public void onSuccess(ScreenshotResult result) {
                    try (HardwareBuffer buffer = result.getHardwareBuffer()) {
                        if (active != InkService.this || request != session) return;
                        Bitmap wrapped = Bitmap.wrapHardwareBuffer(buffer, result.getColorSpace());
                        if (wrapped == null) throw new IOException("No bitmap from display");
                        Bitmap frame;
                        try {
                            frame = wrapped.copy(Bitmap.Config.ARGB_8888, false);
                        } finally {
                            wrapped.recycle();
                        }
                        if (frame == null) throw new IOException("Could not copy bitmap");
                        frame.setDensity(Bitmap.DENSITY_NONE);
                        if (useWindow) {
                            frame = placeInScreen(frame, screen, bounds);
                            floatingArea = previousFloat;
                            canvas.setFloatingArea(floatingArea);
                        }
                        canvas.setReference(frame);
                        showLayer();
                        configureEvents(true);
                    } catch (IOException | RuntimeException failure) {
                        Log.w(TAG, "Could not open a drawing document", failure);
                        configureEvents(false);
                        message("无法打开批注画布");
                    } finally {
                        if (request == session) capturing = false;
                    }
                }

                @Override public void onFailure(int error) {
                    if (request != session) return;
                    capturing = false;
                    configureEvents(false);
                    message(error == ERROR_TAKE_SCREENSHOT_SECURE_WINDOW ? "此页面禁止截屏" : "无法读取当前画面，请重试");
                    Log.w(TAG, "Screenshot rejected: " + error);
                }
            };
            if (useWindow) takeScreenshotOfWindow(documentId, getMainExecutor(), callback);
            else takeScreenshot(Display.DEFAULT_DISPLAY, getMainExecutor(), callback);
        } catch (RuntimeException unavailable) {
            capturing = false;
            configureEvents(false);
            message("无法读取当前画面，请重试");
            Log.w(TAG, "Screenshot request failed", unavailable);
        }
    }

    private static Bitmap placeInScreen(Bitmap frame, Rect screen, Rect bounds) throws IOException {
        if (frame.getWidth() == screen.width() && frame.getHeight() == screen.height()) return frame;
        try {
            if (frame.getWidth() != bounds.width() || frame.getHeight() != bounds.height()) {
                throw new IOException("Window dimensions changed");
            }
            Bitmap full = Bitmap.createBitmap(screen.width(), screen.height(), Bitmap.Config.ARGB_8888);
            full.setDensity(Bitmap.DENSITY_NONE);
            new Canvas(full).drawBitmap(frame, bounds.left - screen.left, bounds.top - screen.top, null);
            return full;
        } finally {
            frame.recycle();
        }
    }

    private void showLayer() {
        if (showing || active != this || !canvas.hasReference()) return;
        try {
            windows.addView(layer, canvasParams(canvasPassesThrough()));
            windows.addView(toolLayer, toolbarParams());
            showing = true;
            PenShortcuts.publish(this, android.os.SystemClock.elapsedRealtimeNanos());
            frost.invalidate();
            Log.i(TAG, "Drawing layer shown");
        } catch (WindowManager.BadTokenException unavailable) {
            if (layer.isAttachedToWindow()) windows.removeViewImmediate(layer);
            if (toolLayer.isAttachedToWindow()) windows.removeViewImmediate(toolLayer);
            message("请重新启用「屏幕批注」无障碍服务");
            Log.w(TAG, "Overlay unavailable", unavailable);
        }
    }

    private void hideLayer() {
        if (!showing) return;
        showing = false;
        PenShortcuts.publish(this, 0);
        if (toolLayer.isAttachedToWindow()) windows.removeViewImmediate(toolLayer);
        if (layer.isAttachedToWindow()) windows.removeViewImmediate(layer);
    }

    private void closeDocument() {
        session++;
        capturing = false;
        saveRequested = false;
        penControl = null;
        penOnCanvas = false;
        if (preview != null) preview.dismiss();
        if (floatingApps != null) floatingApps.dismiss();
        if (scrolling != null) {
            scrolling.close();
            scrolling = null;
        }
        zoomLocked = false;
        startingApp = null;
        documentId = -1;
        floatingArea = null;
        if (listeningForScroll) configureEvents(false);
        main.removeCallbacks(checkTarget);
        hideLayer();
        if (toolbar != null) {
            toolbar.restoreBrush();
            toolbar.showScrolling(false);
        }
        if (canvas != null) canvas.discardDocument();
        if (frostFrame != null) frostFrame.recycle();
        frostFrame = null;
        frostBounds = null;
    }

    private void buildLayer() {
        layer = new FrameLayout(this);
        canvas = new InkView(this);
        canvas.onPenLifted(() -> {
            if (toolbar.restoreBrush()) Log.i(TAG, "Temporary eraser released on pen lift");
        });
        layer.addView(canvas, new FrameLayout.LayoutParams(-1, -1));
        toolbar = new InkToolbar(this, new InkToolbar.Actions() {
            @Override public void color(int argb) { canvas.color(argb); }
            @Override public void width(float width) { canvas.width(width); }
            @Override public void tool(int tool) { canvas.tool(tool); }
            @Override public void undo() { canvas.undo(); }
            @Override public void redo() { canvas.redo(); }
            @Override public void clear() { canvas.clear(); }
            @Override public void resetViewport() {
                if (zoomLocked) canvas.resetViewport();
                else canvas.returnToLiveSize();
            }
            @Override public boolean isZoomLocked() { return zoomLocked; }
            @Override public void toggleZoom() { toggleZoomLock(); }
            @Override public void screenshot() { captureInk(false, false); }
            @Override public void longScreenshot() {
                if (scrolling == null) startScrolling();
                else captureInk(true, false);
            }
            @Override public void openApp() { chooseFloatingApp(); }
            @Override public void finish() { captureInk(scrolling != null, true); }
            @Override public void close() { closeDocument(); }
        });
        toolLayer = new FrameLayout(this);
        frost = new View(this) {
            private final Path mask = new Path();
            private final RenderNode blurred = Build.VERSION.SDK_INT >= 31 ? new RenderNode("Toolbar glass") : null;
            {
                if (blurred != null) blurred.setRenderEffect(RenderEffect.createBlurEffect(dp(18), dp(18),
                    Shader.TileMode.CLAMP));
            }

            @Override protected void onDraw(Canvas output) {
                if (getWidth() <= 0 || getHeight() <= 0) return;
                int[] location = new int[2];
                getLocationOnScreen(location);
                Rect[] panels = toolbar.frostRegions();
                mask.rewind();
                for (int index = 0; index < panels.length; index++) {
                    Rect panel = panels[index];
                    if (panel.isEmpty()) continue;
                    float radius = toolbar.frostCornerRadius(index);
                    mask.addRoundRect(panel.left, panel.top, panel.right, panel.bottom, radius, radius, Path.Direction.CW);
                }
                if (mask.isEmpty()) return;
                output.save();
                output.clipPath(mask);
                if (blurred != null && output.isHardwareAccelerated()) {
                    int padding = dp(44);
                    blurred.setPosition(-padding, -padding, getWidth() + padding, getHeight() + padding);
                    Canvas recording = blurred.beginRecording();
                    recording.translate(padding, padding);
                    paintBackdrop(recording, location);
                    blurred.endRecording();
                    output.drawRenderNode(blurred);
                } else {
                    paintBackdrop(output, location);
                }
                output.restore();
            }

            private void paintBackdrop(Canvas output, int[] location) {
                output.save();
                output.translate(-location[0], -location[1]);
                if (scrolling != null && frostFrame != null) {
                    output.drawBitmap(frostFrame, null, frostBounds, frostPaint);
                } else {
                    canvas.drawBackdrop(output);
                }
                output.restore();
            }
        };
        toolLayer.addView(frost, new FrameLayout.LayoutParams(-1, -1));
        toolLayer.addView(toolbar, new FrameLayout.LayoutParams(-1, -2));
        toolbar.addOnLayoutChangeListener((view, l, t, r, b, ol, ot, or, ob) -> {
            frost.invalidate();
            updateFingerPassthrough();
        });
        canvas.onViewportChanged(frost::invalidate);
    }

    private WindowManager.LayoutParams canvasParams(boolean live) {
        int flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;
        if (live) flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(-1, -1,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, flags, PixelFormat.TRANSLUCENT);
        params.setFitInsetsTypes(0);
        params.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        params.setTitle("ScreenInk");
        return params;
    }

    private WindowManager.LayoutParams toolbarParams() {
        int flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL |
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;
        // The match-parent frost layer would otherwise make the wrap-content window cover the screen.
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(-1, toolbar.overlayHeight(),
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, flags, PixelFormat.TRANSLUCENT);
        int top = windows.getCurrentWindowMetrics().getWindowInsets()
            .getInsetsIgnoringVisibility(WindowInsets.Type.statusBars() | WindowInsets.Type.displayCutout()).top;
        params.setFitInsetsTypes(0);
        params.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        params.y = top + dp(5);
        params.setTitle("ScreenInk toolbar");
        return params;
    }

    private void captureInk(boolean whole, boolean finish) {
        if (saving || saveRequested || !canvas.hasReference()) return;
        if (scrolling == null) {
            publish(canvas.composeImage(), false, finish);
        } else if (scrolling.started()) {
            saveRequested = true;
            LiveScroll current = scrolling;
            current.reconcile(() -> {
                saveRequested = false;
                if (scrolling != current) return;
                if (whole && !current.hasExtension()) {
                    if (finish && current.isDrawingAligned()) publish(current.compose(canvas, false), false, true);
                    else message("还未录到新内容，请先滑动页面");
                    return;
                }
                if (!current.isDrawingAligned() && !whole) {
                    message("当前页尚未对齐，请慢慢滑回上一段");
                    return;
                }
                publish(current.compose(canvas, whole), whole, finish);
            });
        }
    }

    private void publish(final Bitmap bitmap, boolean whole, boolean finish) {
        if (bitmap == null) {
            message("截图处理失败");
            return;
        }
        final boolean marked = canvas.hasInk();
        final int request = session;
        saving = true;
        files.execute(() -> {
            Uri uri = null;
            Bitmap thumbnail = null;
            try {
                uri = saveImage(bitmap, whole);
                float ratio = Math.min(1, 360f / Math.max(bitmap.getWidth(), bitmap.getHeight()));
                thumbnail = Bitmap.createScaledBitmap(bitmap, Math.max(1, Math.round(bitmap.getWidth() * ratio)),
                    Math.max(1, Math.round(bitmap.getHeight() * ratio)), true);
                if (thumbnail == bitmap) thumbnail = bitmap.copy(Bitmap.Config.ARGB_8888, false);
                Log.i(TAG, "Screenshot saved; has ink=" + marked);
            } catch (IOException | RuntimeException failure) {
                Log.w(TAG, "Could not save or preview screenshot", failure);
            } finally {
                bitmap.recycle();
            }
            final Uri saved = uri;
            final Bitmap image = thumbnail;
            main.post(() -> {
                saving = false;
                if (disposed || active != this || session != request || !showing) {
                    if (image != null) image.recycle();
                    return;
                }
                if (finish && saved != null) closeDocument();
                if (saved != null && image != null) preview.show(image, saved);
                else if (saved != null) message("已保存到相册 · 屏幕批注");
                else message("截图保存失败");
            });
        });
    }

    private Uri saveImage(Bitmap bitmap, boolean whole) throws IOException {
        ContentValues values = new ContentValues();
        values.put(MediaStore.Images.Media.DISPLAY_NAME, (whole ? "Ink_Long_" : "Ink_")
            + FILE_TIME.format(LocalDateTime.now()) + ".png");
        values.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
        values.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/屏幕批注");
        values.put(MediaStore.Images.Media.IS_PENDING, 1);
        Uri image = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
        if (image == null) throw new IOException("MediaStore insert failed");
        boolean published = false;
        try {
            try (OutputStream stream = getContentResolver().openOutputStream(image)) {
                if (stream == null || !bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)) {
                    throw new IOException("Unable to write the PNG");
                }
            }
            ContentValues ready = new ContentValues();
            ready.put(MediaStore.Images.Media.IS_PENDING, 0);
            published = getContentResolver().update(image, ready, null, null) == 1;
            if (!published) throw new IOException("MediaStore publish failed");
            return image;
        } finally {
            if (!published) getContentResolver().delete(image, null, null);
        }
    }

    private void startScrolling() {
        if (saving || !showing) return;
        if (Build.VERSION.SDK_INT < 34) {
            message("滚动批注需要 Android 14 或更新版本");
            return;
        }
        configureEvents(true);
        final int request = session;
        main.postDelayed(() -> {
            if (!showing || session != request || scrolling != null) return;
            scrolling = LiveScroll.create(this, main, new LiveScroll.Host() {
                @Override public void ready(Rect area, Bitmap toolbarFrame, Rect frameBounds) {
                    copyFrost(toolbarFrame, frameBounds);
                    canvas.cancelPen();
                    canvas.setScrolling(area, 0);
                    windows.updateViewLayout(layer, canvasParams(canvasPassesThrough()));
                    configureEvents(true);
                    toolbar.showScrolling(true);
                    message("单指滚动，点放大镜切换双指缩放");
                }

                @Override public void moved(Rect area, int offset, Bitmap toolbarFrame, Rect frameBounds) {
                    if (toolbarFrame != null) copyFrost(toolbarFrame, frameBounds);
                    canvas.setScrolling(area, offset);
                }

                @Override public void failed(String text, boolean end) {
                    if (end) {
                        if (scrolling != null && !scrolling.started()) {
                            scrolling.close();
                            scrolling = null;
                            configureEvents(true);
                        } else {
                            closeDocument();
                        }
                    }
                    message(text);
                }
            }, windows.getCurrentWindowMetrics().getBounds(), documentId, floatingArea != null || startingApp != null);
            if (scrolling == null) {
                message("当前页面无法开始滚动批注");
            } else {
                scrolling.start();
            }
        }, 230);
    }

    private boolean isLiveScroll() { return scrolling != null && scrolling.started(); }

    private boolean canvasPassesThrough() { return floatingArea != null || (isLiveScroll() && !zoomLocked); }

    private void toggleZoomLock() {
        if (!isLiveScroll()) return;
        if (zoomLocked) {
            zoomLocked = false;
            canvas.returnToLiveSize();
        } else {
            if (!canvas.hasFrozenScrollFrame() && !scrolling.freezeViewport(canvas)) {
                message("页面正在对齐，等滚动停下后再试");
                return;
            }
            zoomLocked = true;
        }
        windows.updateViewLayout(layer, canvasParams(canvasPassesThrough()));
        configureEvents(true);
        toolbar.syncScrollControl();
        message(zoomLocked ? "双指缩放，单指拖动画布" : "已恢复滚动");
    }

    private void buildFingerInput() {
        TouchInteractionController controller = getTouchInteractionController(Display.DEFAULT_DISPLAY);
        fingerInput = new FingerInput(controller, new FingerInput.Host() {
            @Override public boolean routeToApp(float x, float y) {
                int px = (int) x;
                int py = (int) y;
                return !showing || toolbar.optionsShowing() || floatingApps.isShowing()
                    || fingerPassthrough().contains(px, py) || hits(preview.touchView(), px, py)
                    || !canvas.acceptsPenAt(x, y);
            }

            @Override public void moveViewport(MotionEvent event) {
                canvas.moveViewport(event);
            }

            @Override public void finishViewport() {
                canvas.finishFingerGesture(zoomLocked);
            }
        });
    }

    private boolean anotherServiceExploresTouches() {
        AccessibilityManager manager = getSystemService(AccessibilityManager.class);
        if (manager == null) return false;
        for (AccessibilityServiceInfo service : manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)) {
            if ((service.flags & AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE) != 0
                && service.getResolveInfo() != null
                && !getPackageName().equals(service.getResolveInfo().serviceInfo.packageName)) return true;
        }
        return false;
    }

    private Region fingerPassthrough() {
        Region region = new Region();
        Rect display = windows.getCurrentWindowMetrics().getBounds();
        android.graphics.Insets safe = windows.getCurrentWindowMetrics().getWindowInsets()
            .getInsetsIgnoringVisibility(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
        region.union(new Rect(display.left, display.top, display.right, display.top + safe.top));
        region.union(new Rect(display.left, display.bottom - safe.bottom, display.right, display.bottom));
        if (toolbar != null && toolbar.isAttachedToWindow()) {
            int[] position = new int[2];
            toolbar.getLocationOnScreen(position);
            region.union(new Rect(display.left, position[1], display.right, position[1] + toolbar.getHeight()));
        }
        if (floatingArea != null) region.union(floatingArea);
        return region;
    }

    private void updateFingerPassthrough() {
        if (routingFingers) setTouchExplorationPassthroughRegion(Display.DEFAULT_DISPLAY, fingerPassthrough());
    }

    private void configureEvents(boolean observe) {
        AccessibilityServiceInfo info = getServiceInfo();
        if (info == null) return;
        listeningForScroll = observe;
        info.eventTypes = observe ? AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED | AccessibilityEvent.TYPE_WINDOWS_CHANGED : 0;
        if (observe && isLiveScroll()) {
            info.eventTypes |= AccessibilityEvent.TYPE_VIEW_SCROLLED | AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED;
        }
        info.notificationTimeout = 0;
        if (observe) info.flags |= AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
        else info.flags &= ~AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
        boolean bridge = observe && canvasPassesThrough();
        if (Build.VERSION.SDK_INT >= 34) info.setMotionEventSources(bridge
            ? InputDevice.SOURCE_STYLUS | InputDevice.SOURCE_BLUETOOTH_STYLUS : 0);
        boolean touch = observe && floatingArea != null && (!isLiveScroll() || zoomLocked)
            && fingerInput != null && !anotherServiceExploresTouches();
        boolean changedTouch = routingFingers != touch;
        if (touch) info.flags |= AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE;
        else info.flags &= ~AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE;
        if (routingFingers && !touch) setTouchExplorationPassthroughRegion(Display.DEFAULT_DISPLAY, new Region());
        routingFingers = touch;
        setServiceInfo(info);
        if (fingerInput != null && changedTouch) {
            fingerInput.setEnabled(touch);
            Log.i(TAG, "Finger input bridge " + (touch ? "enabled" : "disabled"));
        }
        updateFingerPassthrough();
        if (touch && changedTouch) main.postDelayed(this::updateFingerPassthrough, 160);
    }

    private void copyFrost(Bitmap frame, Rect bounds) {
        if (frostFrame != null) frostFrame.recycle();
        Bitmap scaled = Bitmap.createScaledBitmap(frame, Math.max(1, frame.getWidth() / 5),
            Math.max(1, frame.getHeight() / 5), true);
        frostFrame = scaled == frame ? frame.copy(Bitmap.Config.ARGB_8888, false) : scaled;
        frostBounds = new Rect(bounds);
        frost.invalidate();
    }

    private void chooseFloatingApp() {
        if (!showing) return;
        if (Build.VERSION.SDK_INT < 34) {
            message("小窗批注需要 Android 14 或更新版本");
            return;
        }
        if (!getPackageManager().hasSystemFeature("android.software.freeform_window_management")) {
            message("当前设备没有开启系统小窗");
            return;
        }
        List<AccessibilityWindowInfo> all = getWindows();
        AccessibilityWindowInfo background = findWindow(all, documentId);
        if (background == null) {
            background = backWindow(all, windows.getCurrentWindowMetrics().getBounds());
            documentId = background == null ? -1 : background.getId();
        }
        floatingApps.show(toolbar.appPickerAnchor(), windowPackage(background), this::launchFloatingApp);
    }

    private void launchFloatingApp(ComponentName app) {
        if (!showing) return;
        startingApp = app;
        launchStarted = SystemClock.uptimeMillis();
        if (scrolling != null) scrolling.allowOccludingApp(true);
        try {
            floatingApps.launch(app);
            main.removeCallbacks(checkTarget);
            main.postDelayed(checkTarget, 180);
        } catch (ActivityNotFoundException | SecurityException unavailable) {
            startingApp = null;
            if (scrolling != null) scrolling.allowOccludingApp(floatingArea != null);
            message("系统无法以小窗打开此应用");
            Log.w(TAG, "Freeform activity launch failed", unavailable);
        }
    }

    private void syncAppWindows() {
        if (floatingApps.isShowing()) return;
        List<AccessibilityWindowInfo> all = getWindows();
        Rect screen = windows.getCurrentWindowMetrics().getBounds();
        if (startingApp != null) {
            AccessibilityWindowInfo launched = null;
            for (AccessibilityWindowInfo entry : all) {
                if (entry.getType() == AccessibilityWindowInfo.TYPE_APPLICATION && entry.getId() != documentId
                    && startingApp.getPackageName().equals(windowPackage(entry))) {
                    launched = entry;
                    break;
                }
            }
            Rect reached = launched == null ? null : floatingBounds(all, launched.getId(), screen);
            if (reached != null) {
                floatingId = launched.getId();
                startingApp = null;
                Log.i(TAG, "Freeform window opened; bounds=" + reached.toShortString());
            } else if (SystemClock.uptimeMillis() - launchStarted < 2200) {
                main.postDelayed(checkTarget, 180);
                return;
            } else {
                startingApp = null;
                if (scrolling != null) scrolling.allowOccludingApp(floatingArea != null);
                if (launched != null && (launched.isActive() || launched.isFocused())) closeDocument();
                message("系统未将此应用打开为小窗");
                return;
            }
        }
        Rect currentArea = floatingBounds(all, floatingId, screen);
        if (!java.util.Objects.equals(currentArea, floatingArea)) setFloatingArea(currentArea);
        AccessibilityWindowInfo foreground = activeApp(all);
        if (documentId >= 0 && foreground != null && foreground.getId() != documentId
            && (currentArea == null || foreground.getId() != floatingId)
            && !getPackageName().equals(windowPackage(foreground))) {
            Rect bounds = new Rect();
            foreground.getBoundsInScreen(bounds);
            if (!isSmallWindow(bounds, screen)) closeDocument();
        }
    }

    private void setFloatingArea(Rect area) {
        floatingArea = area;
        canvas.setFloatingArea(area);
        if (scrolling != null) scrolling.allowOccludingApp(area != null);
        if (showing) windows.updateViewLayout(layer, canvasParams(canvasPassesThrough()));
        configureEvents(true);
        if (frost != null) frost.invalidate();
    }

    private AccessibilityWindowInfo backWindow(List<AccessibilityWindowInfo> list, Rect screen) {
        AccessibilityWindowInfo fallback = null;
        for (AccessibilityWindowInfo entry : list) {
            if (entry.getType() != AccessibilityWindowInfo.TYPE_APPLICATION
                || entry.getDisplayId() != Display.DEFAULT_DISPLAY || entry.getId() == floatingId
                || getPackageName().equals(windowPackage(entry))) continue;
            Rect bounds = new Rect();
            entry.getBoundsInScreen(bounds);
            if (isSmallWindow(bounds, screen)) continue;
            if (entry.isActive() || entry.isFocused()) return entry;
            if (fallback == null || entry.getLayer() > fallback.getLayer()) fallback = entry;
        }
        return fallback;
    }

    private static AccessibilityWindowInfo activeApp(List<AccessibilityWindowInfo> list) {
        for (AccessibilityWindowInfo entry : list) {
            if (entry.getType() == AccessibilityWindowInfo.TYPE_APPLICATION && entry.isActive()) return entry;
        }
        for (AccessibilityWindowInfo entry : list) {
            if (entry.getType() == AccessibilityWindowInfo.TYPE_APPLICATION && entry.isFocused()) return entry;
        }
        return null;
    }

    private static AccessibilityWindowInfo findWindow(List<AccessibilityWindowInfo> list, int id) {
        for (AccessibilityWindowInfo entry : list) {
            if (entry.getType() == AccessibilityWindowInfo.TYPE_APPLICATION && entry.getId() == id) return entry;
        }
        return null;
    }

    private Rect floatingBounds(List<AccessibilityWindowInfo> list, int id, Rect screen) {
        AccessibilityWindowInfo window = findWindow(list, id);
        if (window == null) return null;
        Rect bounds = new Rect();
        window.getBoundsInScreen(bounds);
        if (!isSmallWindow(bounds, screen)) return null;
        bounds.left -= dp(5);
        bounds.right += dp(5);
        bounds.top -= dp(24);
        bounds.bottom += dp(7);
        return bounds.intersect(screen) ? bounds : null;
    }

    private static boolean isSmallWindow(Rect bounds, Rect screen) {
        return !bounds.isEmpty() && bounds.width() < screen.width() * 0.92
            && bounds.height() < screen.height() * 0.94;
    }

    private static String windowPackage(AccessibilityWindowInfo window) {
        AccessibilityNodeInfo root = window == null ? null : window.getRoot();
        CharSequence name = root == null ? null : root.getPackageName();
        return name == null ? null : name.toString();
    }

    private int dp(int value) { return Math.round(getResources().getDisplayMetrics().density * value); }

    private void message(String value) {
        Toast.makeText(this, value, Toast.LENGTH_SHORT).show();
    }
}
