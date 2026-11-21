package local.screenink;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Stores contiguous, non-overlapping strips in the document's vertical coordinate space. */
final class ScrollPage implements AutoCloseable {
    private static final int MAX_PIXELS = 20_000_000;
    private static final int COLUMNS = 28;

    private static final class Tile {
        final int y;
        final Bitmap bitmap;

        Tile(int y, Bitmap bitmap) {
            this.y = y;
            this.bitmap = bitmap;
        }
    }

    private final List<Tile> tiles = new ArrayList<>();
    private final int width;
    private final int height;
    private int first;
    private int last;
    private int[] previous;
    private int offset;

    ScrollPage(Bitmap firstFrame) {
        width = firstFrame.getWidth();
        height = firstFrame.getHeight();
        previous = sample(firstFrame);
        tiles.add(new Tile(0, firstFrame));
        last = height;
    }

    int offset() { return offset; }
    boolean hasExtension() { return last - first > height; }

    /** Resolves displacement from overlap, with the accessibility delta as a search hint. */
    Integer displacement(int[] next, int expected) {
        if (Arrays.equals(previous, next)) return 0;
        int limit = Math.max(0, height - Math.max(64, height / 5));
        int best = 0;
        int bestScore = cost(previous, next, 0);
        int hint = Math.max(-limit, Math.min(limit, expected));
        int hintScore = cost(previous, next, hint);
        if (hintScore < bestScore) {
            best = hint;
            bestScore = hintScore;
        }
        for (int delta = -limit; delta <= limit; delta += 4) {
            int score = cost(previous, next, delta);
            if (score < bestScore) {
                best = delta;
                bestScore = score;
            }
        }
        int coarse = best;
        for (int delta = Math.max(-limit, coarse - 4); delta <= Math.min(limit, coarse + 4); delta++) {
            int score = cost(previous, next, delta);
            if (score < bestScore) {
                best = delta;
                bestScore = score;
            }
        }
        if (bestScore == Integer.MAX_VALUE) return null;
        if (Math.abs(best - expected) > 18 && bestScore > 33) return null;
        return best;
    }

    private int cost(int[] before, int[] after, int delta) {
        int start = Math.max(0, -delta);
        int end = Math.min(height, height - delta);
        int step = Math.max(2, (end - start) / 82);
        int error = 0;
        int count = 0;
        for (int y = start + step / 2; y < end; y += step) {
            int a = (y + delta) * COLUMNS;
            int b = y * COLUMNS;
            for (int x = 0; x < COLUMNS; x++) {
                int old = before[a + x];
                int now = after[b + x];
                if (old == 0 && now == 0) continue;
                error += Math.min(180, Math.abs(old - now));
                count++;
            }
        }
        if (count < 24) return Integer.MAX_VALUE;
        return 100 * error / (255 * count);
    }

    int[] sample(Bitmap frame) {
        Bitmap reduced = Bitmap.createScaledBitmap(frame, COLUMNS * 3, height, false);
        int[] pixels = new int[reduced.getWidth() * height];
        reduced.getPixels(pixels, 0, reduced.getWidth(), 0, 0, reduced.getWidth(), height);
        if (reduced != frame) reduced.recycle();
        int[] result = new int[COLUMNS * height];
        for (int y = 1; y < height - 1; y++) {
            int row = y * COLUMNS * 3;
            for (int x = 0; x < COLUMNS; x++) {
                int at = row + x * 3 + 1;
                int gx = gray(pixels[at + 1]) - gray(pixels[at - 1]);
                int gy = gray(pixels[at + COLUMNS * 3]) - gray(pixels[at - COLUMNS * 3]);
                if (Math.abs(gx) + Math.abs(gy) >= 14) {
                    result[y * COLUMNS + x] = Math.max(-255, Math.min(255, gx + gy));
                }
            }
        }
        return result;
    }

    private static int gray(int argb) {
        return (54 * ((argb >> 16) & 255) + 183 * ((argb >> 8) & 255) + 19 * (argb & 255)) >> 8;
    }

    boolean append(Bitmap frame, int[] samples, int delta) {
        int nextOffset = offset + delta;
        int nextEnd = nextOffset + height;
        int nextFirst = Math.min(first, nextOffset);
        int nextLast = Math.max(last, nextEnd);
        if ((long) width * (nextLast - nextFirst) > MAX_PIXELS) return false;
        if (nextOffset > last || nextEnd < first) return false;
        if (nextOffset < first) tiles.add(new Tile(nextOffset, copy(frame, 0, first - nextOffset)));
        if (nextEnd > last) tiles.add(new Tile(last, copy(frame, last - nextOffset, nextEnd - last)));
        first = nextFirst;
        last = nextLast;
        offset = nextOffset;
        previous = samples;
        return true;
    }

    private Bitmap copy(Bitmap frame, int top, int rows) {
        Bitmap strip = Bitmap.createBitmap(width, rows, Bitmap.Config.ARGB_8888);
        strip.setDensity(Bitmap.DENSITY_NONE);
        new Canvas(strip).drawBitmap(frame, 0, -top, null);
        return strip;
    }

    Bitmap compose(InkView ink, Rect screenArea, boolean whole) {
        return compose(ink, screenArea, whole, offset);
    }

    Bitmap compose(InkView ink, Rect screenArea, boolean whole, int viewportOffset) {
        int top = whole ? first : viewportOffset;
        int bottom = whole ? last : viewportOffset + height;
        Bitmap output = copyRange(top, bottom);
        Canvas canvas = new Canvas(output);
        canvas.translate(-screenArea.left, -screenArea.top - top);
        ink.paintOn(canvas);
        return output;
    }

    Bitmap copyViewport() { return copyRange(offset, offset + height); }

    private Bitmap copyRange(int top, int bottom) {
        Bitmap output = Bitmap.createBitmap(width, bottom - top, Bitmap.Config.ARGB_8888);
        output.setDensity(Bitmap.DENSITY_NONE);
        Canvas canvas = new Canvas(output);
        Paint paint = new Paint();
        for (Tile tile : tiles) canvas.drawBitmap(tile.bitmap, 0, tile.y - top, paint);
        return output;
    }

    @Override public void close() {
        for (Tile tile : tiles) tile.bitmap.recycle();
        tiles.clear();
    }
}
