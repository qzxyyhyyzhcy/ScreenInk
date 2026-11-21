package local.screenink;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.SeekBar;
import android.widget.TextView;

final class InkToolbar extends LinearLayout {
    static final int PEN = 0;
    static final int MARKER = 1;
    static final int ERASER = 2;

    interface Actions {
        void color(int argb);
        void width(float dp);
        void tool(int tool);
        void undo();
        void redo();
        void clear();
        void resetViewport();
        boolean isZoomLocked();
        void toggleZoom();
        void screenshot();
        void longScreenshot();
        void openApp();
        void finish();
        void close();
    }

    private static final int WHITE_GLASS = 0xd9ffffff;
    private static final int GRAY_LINE = 0x24839596;
    private static final int INK = 0xff212a29;
    private static final int MUTED = 0xff858f8e;
    private static final int[] COLORS = {0xffff626b, 0xffffc642, 0xff50bfa6, 0xff4d9bdf, Color.WHITE, Color.BLACK};
    private static final String[] COLOR_NAMES = {"红色", "黄色", "薄荷绿", "蓝色", "白色", "黑色"};
    private static final int WIDTH_STEPS = 47;
    private static final int DEFAULT_WIDTH_PROGRESS = 5;
    private static final int TRAY_RADIUS = 18;

    private final Actions actions;
    private final FrameLayout strip;
    private final LinearLayout tray;
    private final GlyphButton[] tools = new GlyphButton[3];
    private final GlyphButton[] colors = new GlyphButton[COLORS.length];
    private final GlyphButton palette;
    private final GlyphButton closeButton;
    private final GlyphButton appButton;
    private final GlyphButton finishButton;
    private final GlyphButton scroll;
    private final GlyphButton capture;
    private int tool = PEN;
    private int lastBrush = PEN;
    private boolean temporaryEraser;
    private int colorIndex;
    private int widthProgress = DEFAULT_WIDTH_PROGRESS;
    private PopupWindow optionsPopup;
    private boolean scrolling;

    InkToolbar(Context context, Actions actions) {
        super(context);
        this.actions = actions;
        setOrientation(VERTICAL);
        setClipChildren(false);
        int available = Math.round(getResources().getDisplayMetrics().widthPixels /
            getResources().getDisplayMetrics().density);
        int sideMargin = available >= 625 ? 20 : 7;
        int sideSize = available >= 625 ? 47 : 36;
        int sideGap = available >= 625 ? 10 : 6;
        int control = available >= 720 ? 36 : available >= 625 ? 29 : 24;
        int swatch = available >= 720 ? 27 : available >= 625 ? 23 : 18;
        int trayHeight = available >= 625 ? 62 : 50;

        strip = new FrameLayout(context);
        strip.setClipChildren(false);
        addView(strip, new LayoutParams(LayoutParams.MATCH_PARENT, dp(trayHeight)));
        tray = new LinearLayout(context);
        tray.setOrientation(HORIZONTAL);
        tray.setGravity(Gravity.CENTER_VERTICAL);
        tray.setPadding(dp(available >= 625 ? 7 : 4), 0, dp(available >= 625 ? 7 : 4), 0);
        tray.setClickable(true);
        tray.setBackground(glass(TRAY_RADIUS));
        strip.addView(tray, new FrameLayout.LayoutParams(
            LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT, Gravity.CENTER));

        closeButton = addSideButton(Icon.CLOSE, "关闭批注", Gravity.LEFT, sideSize, sideMargin, actions::close);
        appButton = addSideButton(Icon.WINDOW, "小窗", Gravity.RIGHT, sideSize,
            sideMargin + sideSize + sideGap, actions::openApp);
        finishButton = addSideButton(Icon.DONE, "保存并完成", Gravity.RIGHT, sideSize, sideMargin, actions::finish);
        addButton(Icon.UNDO, "撤销", MUTED, control, actions::undo);
        addButton(Icon.REDO, "重做", MUTED, control, actions::redo);
        addRule();

        tools[PEN] = addButton(Icon.PEN, "钢笔", INK, control, () -> selectTool(PEN));
        tools[MARKER] = addButton(Icon.MARKER, "荧光笔", INK, control, () -> selectTool(MARKER));
        tools[ERASER] = addButton(Icon.ERASER, "橡皮擦", INK, control, () -> selectTool(ERASER));
        GlyphButton more = addButton(Icon.MORE, "线宽和画布操作", INK, control, null);
        more.setOnClickListener(view -> showOptions(more));
        more.setOnLongClickListener(view -> {
            actions.resetViewport();
            return true;
        });

        addRule();
        if (available >= 680) {
            for (int index = 0; index < COLORS.length; index++) {
                final int choice = index;
                colors[index] = addButton(Icon.SWATCH, COLOR_NAMES[index], COLORS[index], swatch, () -> useColor(choice));
            }
            palette = null;
        } else {
            palette = addButton(Icon.PALETTE, "画笔颜色", INK, control + 8, null);
            palette.setOnClickListener(view -> showColors(view));
        }

        if (available >= 625) addRule();
        scroll = addButton(Icon.SCROLL, "开始滚动批注", INK, control, () -> {
            if (scrolling) actions.toggleZoom();
            else actions.longScreenshot();
        });
        capture = addButton(Icon.CAPTURE, "截图", INK, control, () -> {
            if (scrolling) actions.longScreenshot();
            else actions.screenshot();
        });
        if (available < 625) {
            scroll.setVisibility(GONE);
            capture.setVisibility(GONE);
        }

        tools[PEN].setSelected(true);
        selectColor(0);
        actions.width(widthDp());
    }

    View appPickerAnchor() { return appButton; }
    int overlayHeight() { return strip.getLayoutParams().height; }
    boolean optionsShowing() { return optionsPopup != null && optionsPopup.isShowing(); }
    View optionsView() { return optionsShowing() ? optionsPopup.getContentView() : null; }
    void dismissOptions() { if (optionsPopup != null) optionsPopup.dismiss(); }

    boolean containsPanel(float x, float y) {
        for (Rect panel : frostRegions()) {
            if (panel.contains((int) x, (int) y)) return true;
        }
        return false;
    }

    Rect[] frostRegions() {
        if (strip.getWidth() == 0) return new Rect[0];
        View[] panels = {closeButton, tray, appButton, finishButton};
        Rect[] bounds = new Rect[panels.length];
        for (int i = 0; i < panels.length; i++) {
            Rect rect = new Rect(0, 0, panels[i].getWidth(), panels[i].getHeight());
            offsetDescendantRectToMyCoords(panels[i], rect);
            bounds[i] = rect;
        }
        return bounds;
    }

    float frostCornerRadius(int regionIndex) {
        if (regionIndex == 1) return dp(TRAY_RADIUS);
        return closeButton.getHeight() / 2f;
    }

    void showScrolling(boolean enabled) {
        scrolling = enabled;
        syncScrollControl();
        capture.appearance(enabled ? Icon.SAVE : Icon.CAPTURE, enabled ? "保存长图" : "截图");
    }

    void syncScrollControl() {
        boolean viewing = scrolling && !actions.isZoomLocked();
        scroll.appearance(viewing ? Icon.ZOOM : Icon.SCROLL, viewing ? "放大查看"
            : scrolling ? "继续滚动" : "开始滚动批注");
        scroll.setSelected(scrolling && actions.isZoomLocked());
    }

    private void selectTool(int choice) {
        temporaryEraser = false;
        if (choice != ERASER) lastBrush = choice;
        setTool(choice);
    }

    void nextBrush() {
        selectTool(lastBrush == PEN ? MARKER : PEN);
    }

    void armEraser() {
        temporaryEraser = true;
        setTool(ERASER);
    }

    boolean restoreBrush() {
        if (!temporaryEraser) return false;
        selectTool(lastBrush);
        return true;
    }

    void nextColor() {
        selectColor((colorIndex + 1) % COLORS.length);
    }

    private void setTool(int choice) {
        tool = choice;
        for (int i = 0; i < tools.length; i++) tools[i].setSelected(i == choice);
        actions.tool(choice);
    }

    private void selectColor(int choice) {
        colorIndex = choice;
        for (int i = 0; i < colors.length; i++) {
            if (colors[i] != null) colors[i].setSelected(i == choice);
        }
        if (palette != null) palette.appearance(Icon.PALETTE, "画笔颜色，当前" + COLOR_NAMES[choice]);
        actions.color(COLORS[choice]);
    }

    private void useColor(int choice) {
        if (tool == ERASER) selectTool(PEN);
        selectColor(choice);
    }

    private float widthDp() { return (widthProgress + 1) * 0.5f; }

    private void showOptions(View anchor) {
        if (optionsPopup != null && optionsPopup.isShowing()) {
            optionsPopup.dismiss();
            return;
        }

        LinearLayout panel = new LinearLayout(getContext());
        panel.setOrientation(VERTICAL);
        panel.setPadding(dp(14), dp(12), dp(14), dp(12));

        LinearLayout heading = new LinearLayout(getContext());
        heading.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = optionText("线宽", 15);
        title.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        heading.addView(title, new LayoutParams(0, dp(32), 1));
        TextView reading = optionText(widthDp() + " dp", 15);
        heading.addView(reading, new LayoutParams(dp(66), dp(32)));
        panel.addView(heading);

        LinePreview preview = new LinePreview(getContext());
        panel.addView(preview, new LayoutParams(LayoutParams.MATCH_PARENT, dp(66)));
        LinearLayout adjustment = new LinearLayout(getContext());
        adjustment.setGravity(Gravity.CENTER_VERTICAL);
        SeekBar slider = new SeekBar(getContext());
        slider.setMax(WIDTH_STEPS);
        slider.setProgress(widthProgress);
        slider.setContentDescription("线宽，0.5 到 24 dp");
        slider.setProgressTintList(ColorStateList.valueOf(0xff4c9487));
        slider.setThumbTintList(ColorStateList.valueOf(0xff4c9487));
        slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int value, boolean fromUser) {
                widthProgress = value;
                reading.setText(widthDp() + " dp");
                preview.invalidate();
                actions.width(widthDp());
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) { }
            @Override public void onStopTrackingTouch(SeekBar seekBar) { }
        });
        TextView narrower = optionButton("−", () -> slider.setProgress(Math.max(0, widthProgress - 1)));
        narrower.setContentDescription("线宽减小 0.5 dp");
        adjustment.addView(narrower, new LayoutParams(dp(40), dp(44)));
        adjustment.addView(slider, new LayoutParams(0, dp(44), 1));
        TextView wider = optionButton("+", () -> slider.setProgress(Math.min(WIDTH_STEPS, widthProgress + 1)));
        wider.setContentDescription("线宽增大 0.5 dp");
        adjustment.addView(wider, new LayoutParams(dp(40), dp(44)));
        panel.addView(adjustment);

        LinearLayout options = new LinearLayout(getContext());
        addOption(options, "复位缩放", () -> {
            actions.resetViewport();
            optionsPopup.dismiss();
        });
        if (scrolling) {
            addOption(options, "截当前页", () -> {
                optionsPopup.dismiss();
                actions.screenshot();
            });
            addOption(options, actions.isZoomLocked() ? "继续滚动" : "放大查看", () -> {
                optionsPopup.dismiss();
                actions.toggleZoom();
            });
        }
        addOption(options, "清空批注", () -> {
            actions.clear();
            optionsPopup.dismiss();
        });
        panel.addView(options);
        if (scroll.getVisibility() == GONE) {
            LinearLayout captureOptions = new LinearLayout(getContext());
            if (!scrolling) addOption(captureOptions, "滚动批注", () -> {
                optionsPopup.dismiss();
                actions.longScreenshot();
            });
            addOption(captureOptions, scrolling ? "保存长图" : "截图", () -> {
                optionsPopup.dismiss();
                if (scrolling) actions.longScreenshot();
                else actions.screenshot();
            });
            LayoutParams captureParams = new LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
            captureParams.topMargin = dp(6);
            panel.addView(captureOptions, captureParams);
        }

        showPanel(anchor, panel, 326);
    }

    private void showColors(View anchor) {
        if (optionsShowing()) { dismissOptions(); return; }
        LinearLayout row = new LinearLayout(getContext());
        row.setPadding(dp(8), dp(5), dp(8), dp(5));
        for (int i = 0; i < COLORS.length; i++) {
            final int choice = i;
            GlyphButton chip = new GlyphButton(getContext(), Icon.SWATCH, COLOR_NAMES[choice], COLORS[choice]);
            chip.setFocusable(true);
            chip.setSelected(i == colorIndex);
            chip.setOnClickListener(view -> { useColor(choice); dismissOptions(); });
            row.addView(chip, new LayoutParams(0, dp(46), 1));
        }
        showPanel(anchor, row, 268);
    }

    private void showPanel(View anchor, View panel, int widthDp) {
        int width = Math.min(dp(widthDp), getRootView().getWidth() - dp(24));
        if (width <= 0) return;
        PopupWindow popup = new PopupWindow(panel, width, LayoutParams.WRAP_CONTENT, true);
        GradientDrawable background = shape(0xfffafcfc, 16);
        background.setStroke(dp(1), GRAY_LINE);
        popup.setBackgroundDrawable(background);
        popup.setElevation(dp(2));
        popup.setOutsideTouchable(true);
        popup.setInputMethodMode(PopupWindow.INPUT_METHOD_NOT_NEEDED);
        popup.setOnDismissListener(() -> {
            if (optionsPopup == popup) optionsPopup = null;
        });
        optionsPopup = popup;
        popup.showAsDropDown(anchor, (anchor.getWidth() - width) / 2, dp(6), Gravity.START);
    }

    @Override protected void onDetachedFromWindow() {
        dismissOptions();
        super.onDetachedFromWindow();
    }

    private TextView optionText(String value, int sp) {
        TextView text = new TextView(getContext());
        text.setText(value);
        text.setTextColor(INK);
        text.setTextSize(sp);
        text.setGravity(Gravity.CENTER);
        text.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return text;
    }

    private TextView optionButton(String label, Runnable action) {
        TextView button = optionText(label, 14);
        button.setFocusable(true);
        button.setOnClickListener(view -> action.run());
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(0x1924423e),
            shape(0xffedf2f1, 10), null));
        return button;
    }

    private void addOption(LinearLayout row, String label, Runnable action) {
        LayoutParams params = new LayoutParams(0, dp(42), 1);
        params.setMarginStart(dp(2));
        params.setMarginEnd(dp(2));
        row.addView(optionButton(label, action), params);
    }

    private final class LinePreview extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        LinePreview(Context context) {
            super(context);
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            paint.setStrokeCap(Paint.Cap.ROUND);
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            int color = COLORS[colorIndex];
            paint.setColor(0xffedf2f1);
            canvas.drawRoundRect(0, 0, getWidth(), getHeight(), dp(10), dp(10), paint);
            boolean marker = tool == MARKER;
            float strokeWidth = Math.max(1, widthDp() * getResources().getDisplayMetrics().density
                * (marker ? 2.5f : 1f));
            float center = getHeight() / 2f;
            if (color == Color.WHITE) {
                paint.setColor(0xffaebcb9);
                paint.setStrokeWidth(strokeWidth + dp(2));
                canvas.drawLine(getWidth() * 0.2f, center, getWidth() * 0.8f, center, paint);
            }
            paint.setColor(marker ? (color & 0x00ffffff) | 0x69000000 : color);
            paint.setStrokeWidth(strokeWidth);
            canvas.drawLine(getWidth() * 0.2f, center, getWidth() * 0.8f, center, paint);
        }
    }

    private GlyphButton addButton(Icon icon, String label, int color, int width, Runnable action) {
        GlyphButton button = new GlyphButton(getContext(), icon, label, color);
        button.setFocusable(true);
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(0x191f433e),
            null, shape(Color.WHITE, 12)));
        if (action != null) button.setOnClickListener(view -> action.run());
        tray.addView(button, new LayoutParams(dp(width), dp(48)));
        return button;
    }

    private GlyphButton addSideButton(Icon icon, String label, int side, int size, int margin, Runnable action) {
        GlyphButton button = new GlyphButton(getContext(), icon, label, Color.BLACK);
        button.setFocusable(true);
        button.setOnClickListener(view -> action.run());
        int radius = size / 2 + 1;
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(0x191f433e),
            glass(radius), shape(Color.WHITE, radius)));
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
            dp(size), dp(size), side | Gravity.CENTER_VERTICAL);
        if (side == Gravity.LEFT) params.leftMargin = dp(margin);
        else params.rightMargin = dp(margin);
        strip.addView(button, params);
        return button;
    }

    private void addRule() {
        View rule = new View(getContext());
        rule.setBackgroundColor(GRAY_LINE);
        LayoutParams params = new LayoutParams(dp(1), dp(26));
        params.setMarginStart(dp(3));
        params.setMarginEnd(dp(3));
        tray.addView(rule, params);
    }

    private GradientDrawable glass(int radius) {
        GradientDrawable background = shape(WHITE_GLASS, radius);
        background.setStroke(dp(1), 0x55ffffff);
        return background;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private GradientDrawable shape(int color, int radius) {
        GradientDrawable result = new GradientDrawable();
        result.setColor(color);
        result.setCornerRadius(dp(radius));
        return result;
    }

    private enum Icon { CLOSE, WINDOW, UNDO, REDO, PEN, MARKER, ERASER, MORE, SWATCH, PALETTE, SCROLL, ZOOM, CAPTURE, SAVE, DONE }

    private final class GlyphButton extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private final int ink;
        private Icon icon;

        GlyphButton(Context context, Icon icon, String label, int ink) {
            super(context);
            this.ink = ink;
            paint.setStrokeJoin(Paint.Join.ROUND);
            paint.setStrokeCap(Paint.Cap.ROUND);
            appearance(icon, label);
        }

        void appearance(Icon icon, String label) {
            this.icon = icon;
            setContentDescription(label);
            setTooltipText(label);
            invalidate();
        }

        @Override public CharSequence getAccessibilityClassName() {
            return android.widget.Button.class.getName();
        }

        @Override protected void drawableStateChanged() {
            super.drawableStateChanged();
            invalidate();
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float size = Math.min(dp(24), getWidth() - dp(3));
            canvas.save();
            canvas.translate((getWidth() - size) / 2f, (getHeight() - size) / 2f);
            canvas.scale(size / 24f, size / 24f);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(1.7f);
            paint.setColor(ink);

            switch (icon) {
                case CLOSE:
                    paint.setStrokeWidth(2.2f);
                    line(canvas, 5.8f, 5.8f, 18.2f, 18.2f);
                    line(canvas, 18.2f, 5.8f, 5.8f, 18.2f);
                    break;
                case WINDOW:
                    path.reset();
                    path.moveTo(8, 6);
                    path.lineTo(8, 5);
                    path.quadTo(8, 3, 10, 3);
                    path.lineTo(19, 3);
                    path.quadTo(21, 3, 21, 5);
                    path.lineTo(21, 12);
                    path.quadTo(21, 14, 19, 14);
                    path.lineTo(18, 14);
                    canvas.drawPath(path, paint);
                    canvas.drawRoundRect(3, 8, 16, 20, 2.5f, 2.5f, paint);
                    break;
                case UNDO:
                case REDO:
                    if (icon == Icon.REDO) {
                        canvas.translate(24, 0);
                        canvas.scale(-1, 1);
                    }
                    line(canvas, 9, 5, 4, 10, 9, 15);
                    path.reset();
                    path.moveTo(4, 10);
                    path.lineTo(13, 10);
                    path.cubicTo(18, 10, 20, 13, 20, 18);
                    canvas.drawPath(path, paint);
                    break;
                case PEN:
                    line(canvas, 5, 19, 6, 14, 16, 4, 20, 8, 10, 18, 5, 19);
                    line(canvas, 14, 6, 18, 10);
                    break;
                case MARKER:
                    line(canvas, 7, 13, 15, 4, 20, 9, 12, 17, 7, 13, 4, 17, 7, 20, 11, 16);
                    line(canvas, 5, 22, 14, 22);
                    break;
                case ERASER:
                    line(canvas, 5, 14, 13, 5, 20, 11, 12, 20, 9, 20, 5, 16, 5, 14);
                    line(canvas, 8, 11, 15, 17);
                    line(canvas, 12, 20, 21, 20);
                    break;
                case MORE:
                    paint.setStyle(Paint.Style.FILL);
                    for (int x = 5; x <= 19; x += 7) canvas.drawCircle(x, 12, 1.45f, paint);
                    break;
                case SWATCH:
                    paint.setStyle(Paint.Style.FILL);
                    canvas.drawRoundRect(4.2f, 4.2f, 19.8f, 19.8f, 4.5f, 4.5f, paint);
                    paint.setStyle(Paint.Style.STROKE);
                    paint.setStrokeWidth(isSelected() ? 1.45f : 0.8f);
                    paint.setColor(isSelected() ? INK : 0x32839596);
                    if (isSelected()) canvas.drawRoundRect(2, 2, 22, 22, 6, 6, paint);
                    else canvas.drawRoundRect(4.2f, 4.2f, 19.8f, 19.8f, 4.5f, 4.5f, paint);
                    break;
                case PALETTE:
                    paint.setStyle(Paint.Style.FILL);
                    paint.setColor(COLORS[colorIndex]);
                    canvas.drawRoundRect(2, 5, 15, 19, 4, 4, paint);
                    paint.setStyle(Paint.Style.STROKE);
                    paint.setColor(INK);
                    canvas.drawRoundRect(2, 5, 15, 19, 4, 4, paint);
                    line(canvas, 17, 10, 20, 14, 23, 10);
                    break;
                case SCROLL:
                    line(canvas, 10, 6, 12, 3, 14, 6);
                    line(canvas, 12, 3, 12, 21);
                    line(canvas, 10, 18, 12, 21, 14, 18);
                    line(canvas, 5, 7, 5, 17);
                    line(canvas, 19, 7, 19, 17);
                    break;
                case CAPTURE:
                    line(canvas, 4, 9, 4, 4, 9, 4);
                    line(canvas, 15, 4, 20, 4, 20, 9);
                    line(canvas, 20, 15, 20, 20, 15, 20);
                    line(canvas, 9, 20, 4, 20, 4, 15);
                    canvas.drawCircle(12, 12, 2.9f, paint);
                    break;
                case ZOOM:
                    canvas.drawCircle(10, 10, 6, paint);
                    line(canvas, 14.5f, 14.5f, 20, 20);
                    line(canvas, 7.5f, 10, 12.5f, 10);
                    line(canvas, 10, 7.5f, 10, 12.5f);
                    break;
                case SAVE:
                    line(canvas, 12, 4, 12, 15);
                    line(canvas, 8, 11, 12, 15, 16, 11);
                    line(canvas, 5, 16, 5, 20, 19, 20, 19, 16);
                    break;
                case DONE:
                    paint.setStrokeWidth(2.3f);
                    line(canvas, 4.8f, 12.5f, 9.9f, 17.4f, 20.1f, 6.9f);
                    break;
            }

            if (isSelected() && (icon == Icon.PEN || icon == Icon.MARKER || icon == Icon.ERASER)) {
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(0xff4c9487);
                canvas.drawRoundRect(9, 22.5f, 15, 24, 0.75f, 0.75f, paint);
            }
            canvas.restore();
        }

        private void line(Canvas canvas, float... points) {
            path.reset();
            path.moveTo(points[0], points[1]);
            for (int i = 2; i < points.length; i += 2) path.lineTo(points[i], points[i + 1]);
            canvas.drawPath(path, paint);
        }
    }
}
