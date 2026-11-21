package local.screenink;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PathMeasure;
import android.graphics.Rect;
import android.graphics.RectF;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.List;

final class InkView extends View {
    private static final int MAX_STROKES = 400;
    private static final int HISTORY_LIMIT = 60;
    private static final float MAX_ZOOM = 6f;

    private static final class Stroke {
        final Path path = new Path();
        final int color;
        final float width;

        Stroke(float x, float y, int color, float width) {
            this.color = color;
            this.width = width;
            path.moveTo(x, y);
            path.lineTo(x + 0.05f, y);
        }
    }

    private final Paint ink = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint image = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint hover = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final List<Stroke> strokes = new ArrayList<>();
    private final ArrayDeque<List<Stroke>> history = new ArrayDeque<>();
    private final ArrayDeque<List<Stroke>> future = new ArrayDeque<>();
    private final float density;
    private Bitmap reference;
    private Bitmap frozenScrollFrame;
    private int frozenScrollOffset;
    private int color = 0xffff626b;
    private float width = 3;
    private float scale = 1;
    private float minimumScale = 1;
    private float fitScale = 1;
    private float left;
    private float top;
    private int pen = -1;
    private Stroke current;
    private float previousX;
    private float previousY;
    private int firstFinger = -1;
    private int secondFinger = -1;
    private float fingerX;
    private float fingerY;
    private float fingerSpan;
    private boolean moved;
    private boolean hovering;
    private float hoverX;
    private float hoverY;
    private Rect scrollArea;
    private Rect floatingArea;
    private int scrollOffset;
    private int tool;
    private boolean erased;
    private boolean penInContent;
    private Runnable viewportChanged = () -> { };
    private Runnable penLifted = () -> { };

    InkView(Context context) {
        super(context);
        density = getResources().getDisplayMetrics().density;
        ink.setStyle(Paint.Style.STROKE);
        ink.setStrokeCap(Paint.Cap.ROUND);
        ink.setStrokeJoin(Paint.Join.ROUND);
        setContentDescription("批注画布");
    }

    void color(int value) { color = value; invalidate(); }
    void width(float value) { width = value; invalidate(); }
    void tool(int value) { tool = value; invalidate(); }
    void onViewportChanged(Runnable repaint) { viewportChanged = repaint; }
    void onPenLifted(Runnable listener) { penLifted = listener; }
    boolean hasInk() { return !strokes.isEmpty(); }
    boolean hasReference() { return reference != null; }
    boolean hasFrozenScrollFrame() { return frozenScrollFrame != null; }

    void setReference(Bitmap frame) {
        if (reference != null) reference.recycle();
        reference = frame;
        resetViewport();
    }

    void discardDocument() {
        clearScrollFrame();
        scrollArea = null;
        floatingArea = null;
        scrollOffset = 0;
        cancelStroke();
        strokes.clear();
        history.clear();
        future.clear();
        setReference(null);
    }

    void setScrolling(Rect area, int offset) {
        scrollArea = area == null ? null : new Rect(area);
        scrollOffset = offset;
        invalidate();
    }

    void freezeScrollFrame(Bitmap frame, int offset) {
        clearScrollFrame();
        frozenScrollFrame = frame;
        frozenScrollOffset = offset;
        resetViewport();
    }

    int viewportScrollOffset(int current) {
        return hasFrozenScrollFrame() ? frozenScrollOffset : current;
    }

    void returnToLiveSize() {
        clearScrollFrame();
        resetViewport();
    }

    void finishFingerGesture(boolean holdViewport) {
        if (moved) Log.i("ScreenInk", "Finger viewport updated; magnification=" + (scale / fitScale));
        resetFingers();
        if (!holdViewport && hasFrozenScrollFrame() && scale <= 1.045f) returnToLiveSize();
    }

    private void clearScrollFrame() {
        if (frozenScrollFrame != null) frozenScrollFrame.recycle();
        frozenScrollFrame = null;
    }

    boolean acceptsPenAt(float x, float y) {
        return (scrollArea == null || scrollArea.contains((int) x, (int) y))
            && (floatingArea == null || !floatingArea.contains((int) x, (int) y));
    }

    void setFloatingArea(Rect area) {
        if (java.util.Objects.equals(area, floatingArea)) return;
        floatingArea = area == null ? null : new Rect(area);
        cancelPen();
    }

    void cancelPen() {
        cancelStroke();
        hideHover();
    }

    void hideHover() {
        hovering = false;
        invalidate();
    }

    void undo() {
        cancelStroke();
        if (history.isEmpty()) return;
        future.addLast(new ArrayList<>(strokes));
        strokes.clear();
        strokes.addAll(history.removeLast());
        invalidate();
    }

    void redo() {
        cancelStroke();
        if (future.isEmpty()) return;
        history.addLast(new ArrayList<>(strokes));
        strokes.clear();
        strokes.addAll(future.removeLast());
        invalidate();
    }

    void clear() {
        cancelStroke();
        if (strokes.isEmpty()) return;
        remember();
        strokes.clear();
        invalidate();
    }

    private void remember() {
        if (history.size() == HISTORY_LIMIT) history.removeFirst();
        history.addLast(new ArrayList<>(strokes));
        future.clear();
    }

    void resetViewport() {
        cancelStroke();
        resetFingers();
        fitScale = scrollArea != null ? 1f : reference != null && getWidth() > 0 && getHeight() > 0
            ? Math.min((float) getWidth() / reference.getWidth(), (float) getHeight() / reference.getHeight())
            : 1;
        minimumScale = scrollArea != null ? fitScale : fitScale * 0.65f;
        scale = fitScale;
        left = 0;
        top = 0;
        constrainViewport();
        invalidate();
        viewportChanged.run();
    }

    Bitmap composeImage() {
        if (reference == null) return null;
        Bitmap output = reference.copy(Bitmap.Config.ARGB_8888, true);
        if (output != null) paintOn(new Canvas(output));
        return output;
    }

    void paintOn(Canvas canvas) {
        for (Stroke stroke : strokes) render(canvas, stroke);
        if (current != null) render(canvas, current);
    }

    private void render(Canvas canvas, Stroke stroke) {
        ink.setColor(stroke.color);
        ink.setStrokeWidth(stroke.width);
        canvas.drawPath(stroke.path, ink);
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        canvas.save();
        if (floatingArea != null) canvas.clipOutRect(floatingArea);
        canvas.save();
        if (scrollArea != null) {
            canvas.clipRect(scrollArea);
            if (hasFrozenScrollFrame()) {
                canvas.translate(left, top);
                canvas.scale(scale, scale);
                canvas.clipRect(scrollArea);
                canvas.drawBitmap(frozenScrollFrame, scrollArea.left, scrollArea.top, image);
            }
            canvas.translate(0, -viewportScrollOffset(scrollOffset));
        } else {
            if (reference != null) canvas.drawColor(0xff142323);
            canvas.translate(left, top);
            canvas.scale(scale, scale);
            if (reference != null) {
                canvas.clipRect(0, 0, reference.getWidth(), reference.getHeight());
                canvas.drawBitmap(reference, 0, 0, image);
            }
        }
        paintOn(canvas);
        canvas.restore();
        if (hovering && current == null) drawHover(canvas);
        canvas.restore();
    }

    void drawBackdrop(Canvas output) {
        if (reference == null) return;
        output.save();
        output.translate(left, top);
        output.scale(scale, scale);
        output.drawBitmap(reference, 0, 0, image);
        output.restore();
    }

    private void drawHover(Canvas canvas) {
        float displayScale = usesViewportTransform() ? scale : 1f;
        float tip = Math.max(density, tool == InkToolbar.ERASER ? density * 13
            : width * density * displayScale * (tool == InkToolbar.MARKER ? 1.25f : 0.5f));
        float ring = Math.min(42 * density, Math.max(8 * density, tip + 4 * density));
        hover.setStyle(Paint.Style.FILL);
        hover.setColor(color);
        hover.setAlpha(tool == InkToolbar.ERASER ? 0 : 160);
        canvas.drawCircle(hoverX, hoverY, Math.min(tip, ring), hover);
        hover.setStyle(Paint.Style.STROKE);
        hover.setColor(0xd9000000);
        hover.setStrokeWidth(3 * density);
        canvas.drawCircle(hoverX, hoverY, ring, hover);
        hover.setColor(0xeaffffff);
        hover.setStrokeWidth(1.25f * density);
        canvas.drawCircle(hoverX, hoverY, ring, hover);
    }

    @Override public boolean onHoverEvent(MotionEvent event) {
        if (event.getPointerCount() == 0 || !isPen(event, 0)) return super.onHoverEvent(event);
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_HOVER_ENTER:
            case MotionEvent.ACTION_HOVER_MOVE:
                hovering = true;
                hoverX = event.getX();
                hoverY = event.getY();
                invalidate();
                return true;
            case MotionEvent.ACTION_HOVER_EXIT:
                hovering = false;
                invalidate();
                return true;
            default:
                return super.onHoverEvent(event);
        }
    }

    @Override protected void onDetachedFromWindow() {
        hovering = false;
        cancelStroke();
        resetFingers();
        super.onDetachedFromWindow();
    }

    @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        resetViewport();
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        int index = event.getActionIndex();
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN:
                if (pen == -1 && isPen(event, index) && acceptsPenAt(event.getX(index), event.getY(index))) {
                    beginStroke(event, index);
                } else if (pen == -1) {
                    trackFingers(event, -1, false);
                }
                return true;
            case MotionEvent.ACTION_MOVE:
                if (pen != -1) {
                    int penIndex = event.findPointerIndex(pen);
                    if (penIndex >= 0) {
                        for (int i = 0; i < event.getHistorySize(); i++) {
                            updatePen(event.getHistoricalX(penIndex, i), event.getHistoricalY(penIndex, i));
                        }
                        updatePen(event.getX(penIndex), event.getY(penIndex));
                        invalidate();
                    }
                } else {
                    trackFingers(event, -1, true);
                }
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP:
                if (event.getPointerId(index) == pen) {
                    if (current != null) {
                        updatePen(event.getX(index), event.getY(index));
                        if (penInContent) current.path.lineTo(previousX, previousY);
                        remember();
                        if (strokes.size() == MAX_STROKES) strokes.remove(0);
                        strokes.add(current);
                        Log.i("ScreenInk", "Stylus stroke added");
                    }
                    cancelStroke();
                    penLifted.run();
                    invalidate();
                }
                if (pen == -1) trackFingers(event, index, false);
                return true;
            case MotionEvent.ACTION_CANCEL:
                cancelStroke();
                resetFingers();
                invalidate();
                return true;
            default:
                return true;
        }
    }

    void moveViewport(MotionEvent event) {
        if (pen != -1 || event.getPointerCount() == 0) return;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN:
                trackFingers(event, -1, false);
                break;
            case MotionEvent.ACTION_MOVE:
                trackFingers(event, -1, true);
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP:
                trackFingers(event, event.getActionIndex(), false);
                break;
            case MotionEvent.ACTION_CANCEL:
                resetFingers();
                break;
            default:
                break;
        }
    }

    private static boolean isPen(MotionEvent event, int index) {
        int tool = event.getToolType(index);
        return tool == MotionEvent.TOOL_TYPE_STYLUS || tool == MotionEvent.TOOL_TYPE_ERASER;
    }

    private void beginStroke(MotionEvent event, int index) {
        hovering = false;
        resetFingers();
        pen = event.getPointerId(index);
        previousX = documentX(event.getX(index));
        previousY = documentY(event.getY(index));
        penInContent = true;
        if (tool == InkToolbar.ERASER) {
            erased = false;
            eraseAt(previousX, previousY);
        } else {
            int strokeColor = tool == InkToolbar.MARKER ? (color & 0x00ffffff) | 0x69000000 : color;
            current = new Stroke(previousX, previousY, strokeColor,
                density * width * (tool == InkToolbar.MARKER ? 2.5f : 1f));
        }
        invalidate();
    }

    private void updatePen(float x, float y) {
        if (!acceptsPenAt(x, y)) {
            if (penInContent && current != null) current.path.lineTo(previousX, previousY);
            penInContent = false;
            return;
        }
        float contentX = documentX(x);
        float contentY = documentY(y);
        if (current == null) {
            eraseAt(contentX, contentY);
        } else if (!penInContent || crossesFloatingWindow(contentX, contentY)) {
            current.path.moveTo(contentX, contentY);
            current.path.lineTo(contentX + 0.05f, contentY);
        } else {
            current.path.quadTo(previousX, previousY, (contentX + previousX) / 2, (contentY + previousY) / 2);
        }
        penInContent = true;
        previousX = contentX;
        previousY = contentY;
    }

    private boolean crossesFloatingWindow(float x, float y) {
        if (floatingArea == null) return false;
        float x1 = documentX(floatingArea.left);
        float x2 = documentX(floatingArea.right);
        float y1 = documentY(floatingArea.top);
        float y2 = documentY(floatingArea.bottom);
        return Math.max(previousX, x) > x1 && Math.min(previousX, x) < x2
            && Math.max(previousY, y) > y1 && Math.min(previousY, y) < y2;
    }

    private void eraseAt(float x, float y) {
        RectF bounds = new RectF();
        PathMeasure measure = new PathMeasure();
        float[] pos = new float[2];
        float eraserRadius = density * 13 / (usesViewportTransform() ? scale : 1f);
        for (int i = strokes.size() - 1; i >= 0; i--) {
            Stroke stroke = strokes.get(i);
            float radius = eraserRadius + stroke.width / 2;
            stroke.path.computeBounds(bounds, true);
            bounds.inset(-radius, -radius);
            if (!bounds.contains(x, y)) continue;
            measure.setPath(stroke.path, false);
            float length = measure.getLength();
            int points = Math.max(1, (int) Math.ceil(length / Math.max(1, radius / 2)));
            for (int j = 0; j <= points; j++) {
                if (!measure.getPosTan(length * j / points, pos, null)) continue;
                float dx = pos[0] - x;
                float dy = pos[1] - y;
                if (dx * dx + dy * dy > radius * radius) continue;
                if (!erased) { remember(); erased = true; }
                strokes.remove(i);
                break;
            }
        }
    }

    private boolean usesViewportTransform() { return scrollArea == null || hasFrozenScrollFrame(); }

    private float documentX(float x) { return usesViewportTransform() ? (x - left) / scale : x; }
    private float documentY(float y) {
        float yInFrame = usesViewportTransform() ? (y - top) / scale : y;
        return yInFrame + (scrollArea == null ? 0 : viewportScrollOffset(scrollOffset));
    }

    private void cancelStroke() {
        pen = -1;
        current = null;
        erased = false;
    }

    private void trackFingers(MotionEvent event, int excluded, boolean changing) {
        if (reference == null || (scrollArea != null && !hasFrozenScrollFrame())) return;
        int first = -1;
        int second = -1;
        for (int i = 0; i < event.getPointerCount(); i++) {
            if (i == excluded || event.getToolType(i) != MotionEvent.TOOL_TYPE_FINGER) continue;
            if (first == -1) first = i;
            else { second = i; break; }
        }
        if (first == -1) {
            finishFingerGesture(true);
            return;
        }
        int id1 = event.getPointerId(first);
        int id2 = second == -1 ? -1 : event.getPointerId(second);
        float x = event.getX(first);
        float y = event.getY(first);
        float span = 0;
        if (second != -1) {
            float dx = event.getX(second) - x;
            float dy = event.getY(second) - y;
            span = (float) Math.hypot(dx, dy);
            x += dx / 2;
            y += dy / 2;
        }
        if (changing && id1 == firstFinger && id2 == secondFinger) {
            float oldScale = scale;
            float oldLeft = left;
            float oldTop = top;
            if (span > density * 10 && fingerSpan > density * 10) {
                scale = Math.max(minimumScale, Math.min(fitScale * MAX_ZOOM, scale * span / fingerSpan));
            }
            float ratio = scale / oldScale;
            left = x - (fingerX - left) * ratio;
            top = y - (fingerY - top) * ratio;
            constrainViewport();
            moved |= scale != oldScale || left != oldLeft || top != oldTop;
            invalidate();
            if (moved) viewportChanged.run();
        }
        firstFinger = id1;
        secondFinger = id2;
        fingerX = x;
        fingerY = y;
        fingerSpan = span;
    }

    private void resetFingers() {
        firstFinger = -1;
        secondFinger = -1;
        fingerSpan = 0;
        moved = false;
    }

    private void constrainViewport() {
        if (reference == null) return;
        if (hasFrozenScrollFrame() && scrollArea != null) {
            left = scrollArea.left + constrain(left + (scale - 1) * scrollArea.left,
                scrollArea.width(), scale * scrollArea.width()) - scrollArea.left * scale;
            top = scrollArea.top + constrain(top + (scale - 1) * scrollArea.top,
                scrollArea.height(), scale * scrollArea.height()) - scrollArea.top * scale;
            return;
        }
        left = constrain(left, getWidth(), reference.getWidth() * scale);
        top = constrain(top, getHeight(), reference.getHeight() * scale);
    }

    private static float constrain(float offset, float frame, float content) {
        return content <= frame ? (frame - content) / 2 : Math.max(frame - content, Math.min(0, offset));
    }
}
