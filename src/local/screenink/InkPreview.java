package local.screenink;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Insets;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

final class InkPreview {
    private static final long DISMISS_DELAY_MS = 6000;
    private static final int FRAME = 0xfff6f8f7;
    private static final int TEXT = 0xff202b29;
    private static final int PICTURE_BACKGROUND = 0xffedf1ef;

    private final Context context;
    private final WindowManager manager;
    private final Runnable onOpen;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable timeout = this::dismiss;
    private FrameLayout card;
    private ImageView picture;
    private Bitmap thumbnail;
    private boolean added;

    InkPreview(Context context, WindowManager manager) {
        this(context, manager, () -> { });
    }

    InkPreview(Context context, WindowManager manager, Runnable onOpen) {
        this.context = context;
        this.manager = manager;
        this.onOpen = onOpen;
    }

    View touchView() { return added ? card : null; }

    /** Transfers ownership of the thumbnail. Call on the main thread. */
    void show(Bitmap next, Uri image) {
        dismiss();
        thumbnail = next;
        if (next == null || next.isRecycled() || image == null) {
            dismiss();
            return;
        }

        int width = dp(114);
        int imageHeight = Math.max(dp(61), Math.min(dp(147),
            Math.round((float) width * next.getHeight() / next.getWidth())));
        card = buildCard(image, imageHeight);
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(width,
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.TRANSLUCENT);
        params.setTitle("ScreenInk preview");
        params.gravity = Gravity.TOP | Gravity.END;
        params.setFitInsetsTypes(0);
        WindowInsets insets = manager.getCurrentWindowMetrics().getWindowInsets();
        Insets safe = insets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
        boolean rightToLeft = context.getResources().getConfiguration().getLayoutDirection()
            == FrameLayout.LAYOUT_DIRECTION_RTL;
        params.x = dp(12) + (rightToLeft ? safe.left : safe.right);
        params.y = safe.top + dp(79);
        try {
            manager.addView(card, params);
            added = true;
        } catch (WindowManager.BadTokenException | IllegalStateException unavailable) {
            dismiss();
            return;
        }

        card.setAlpha(0f);
        card.animate().alpha(1f).setDuration(140).start();
        main.postDelayed(timeout, DISMISS_DELAY_MS);
    }

    private FrameLayout buildCard(Uri image, int imageHeight) {
        FrameLayout frame = new FrameLayout(context);
        frame.setBackground(shape(FRAME, 15));
        frame.setClipToOutline(true);

        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setFocusable(true);
        content.setContentDescription("查看已保存的截图");
        content.setOnClickListener(view -> open(image));
        frame.addView(content, new FrameLayout.LayoutParams(-1, -2));

        picture = new ImageView(context);
        picture.setImportantForAccessibility(ImageView.IMPORTANT_FOR_ACCESSIBILITY_NO);
        picture.setBackgroundColor(PICTURE_BACKGROUND);
        picture.setScaleType(ImageView.ScaleType.FIT_CENTER);
        picture.setImageBitmap(thumbnail);
        content.addView(picture, new LinearLayout.LayoutParams(-1, imageHeight));

        TextView label = new TextView(context);
        label.setGravity(Gravity.CENTER);
        label.setText("查看截图");
        label.setTextColor(TEXT);
        label.setTextSize(13);
        label.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        label.setImportantForAccessibility(TextView.IMPORTANT_FOR_ACCESSIBILITY_NO);
        content.addView(label, new LinearLayout.LayoutParams(-1, dp(35)));

        TextView close = new TextView(context);
        close.setGravity(Gravity.CENTER);
        close.setText("×");
        close.setTextSize(23);
        close.setTextColor(TEXT);
        close.setContentDescription("关闭截图预览");
        close.setFocusable(true);
        close.setBackground(shape(0xecf9fbfa, 18));
        close.setOnClickListener(view -> dismiss());
        FrameLayout.LayoutParams closeParams = new FrameLayout.LayoutParams(dp(40), dp(40), Gravity.TOP | Gravity.END);
        closeParams.setMarginEnd(dp(4));
        closeParams.topMargin = dp(4);
        frame.addView(close, closeParams);
        return frame;
    }

    private void open(Uri image) {
        Intent view = new Intent(Intent.ACTION_VIEW)
            .setDataAndType(image, "image/png")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            context.startActivity(view);
        } catch (ActivityNotFoundException | SecurityException unavailable) {
            Toast.makeText(context, "截图已保存，请在相册中打开", Toast.LENGTH_SHORT).show();
            return;
        }
        dismiss();
        onOpen.run();
    }

    void dismiss() {
        main.removeCallbacks(timeout);
        if (card != null) {
            card.animate().cancel();
            if (added) {
                try {
                    manager.removeViewImmediate(card);
                } catch (IllegalArgumentException alreadyRemoved) {
                    // The window can be detached by the system as the service is unbound.
                }
                added = false;
            }
            card = null;
        }
        if (picture != null) {
            picture.setImageDrawable(null);
            picture = null;
        }
        if (thumbnail != null) {
            thumbnail.recycle();
            thumbnail = null;
        }
    }

    private GradientDrawable shape(int color, int radius) {
        GradientDrawable background = new GradientDrawable();
        background.setColor(color);
        background.setCornerRadius(dp(radius));
        return background;
    }

    private int dp(int value) {
        return Math.round(context.getResources().getDisplayMetrics().density * value);
    }
}
