package local.screenink;

import android.app.ActivityOptions;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.PopupWindow;
import android.widget.TextView;
import java.text.Collator;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

final class FloatingApps {
    private static final int FREEFORM_MODE = 5;
    private static final String WINDOWING_MODE = "android.activity.windowingMode";
    private static final int TEXT = 0xff182025;

    private final Context context;
    private final WindowManager windows;
    private PopupWindow picker;

    FloatingApps(Context context, WindowManager windows) {
        this.context = context;
        this.windows = windows;
    }

    boolean isShowing() { return picker != null && picker.isShowing(); }
    View pickerView() { return isShowing() ? picker.getContentView() : null; }

    void dismiss() {
        if (picker != null) picker.dismiss();
        picker = null;
    }

    void show(View anchor, String backgroundPackage, Consumer<ComponentName> open) {
        if (isShowing()) {
            dismiss();
            return;
        }
        List<ResolveInfo> apps = listApps(backgroundPackage);
        LinearLayout panel = new LinearLayout(context);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(10), dp(8), dp(10), dp(9));
        panel.setBackground(shape(0xfff9fbfc, 21));
        LinearLayout heading = new LinearLayout(context);
        heading.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = label("在小窗打开", 15);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setPadding(dp(10), 0, 0, 0);
        heading.addView(title, new LinearLayout.LayoutParams(0, dp(46), 1));
        TextView close = label("×", 24);
        close.setContentDescription("关闭应用选择");
        close.setGravity(Gravity.CENTER);
        close.setBackground(ripple());
        close.setOnClickListener(view -> dismiss());
        heading.addView(close, new LinearLayout.LayoutParams(dp(48), dp(46)));
        panel.addView(heading);

        if (apps.isEmpty()) {
            TextView empty = label("没有可打开的应用", 14);
            empty.setGravity(Gravity.CENTER);
            panel.addView(empty, new LinearLayout.LayoutParams(-1, dp(54)));
        } else {
            ListView list = new ListView(context);
            list.setDivider(null);
            list.setAdapter(new AppList(apps));
            list.setOnItemClickListener((parent, item, position, id) -> {
                ResolveInfo chosen = apps.get(position);
                dismiss();
                open.accept(new ComponentName(chosen.activityInfo.packageName, chosen.activityInfo.name));
            });
            panel.addView(list, new LinearLayout.LayoutParams(-1, Math.min(dp(340), apps.size() * dp(52))));
        }
        int width = Math.min(dp(295), windows.getCurrentWindowMetrics().getBounds().width() - dp(28));
        PopupWindow popup = new PopupWindow(panel, width, ViewGroup.LayoutParams.WRAP_CONTENT, false);
        popup.setBackgroundDrawable(shape(Color.TRANSPARENT, 21));
        popup.setElevation(dp(2));
        popup.setOutsideTouchable(true);
        popup.setOnDismissListener(() -> { if (picker == popup) picker = null; });
        picker = popup;
        popup.showAsDropDown(anchor, -dp(14), dp(6), Gravity.END);
    }

    private List<ResolveInfo> listApps(String backgroundPackage) {
        PackageManager pm = context.getPackageManager();
        Intent filter = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        Set<String> seen = new HashSet<>();
        seen.add(context.getPackageName());
        if (backgroundPackage != null) seen.add(backgroundPackage);
        List<ResolveInfo> visible = new ArrayList<>();
        for (ResolveInfo app : pm.queryIntentActivities(filter, 0)) {
            if (app.activityInfo != null && app.activityInfo.exported && seen.add(app.activityInfo.packageName)) {
                visible.add(app);
            }
        }
        Collator names = Collator.getInstance();
        visible.sort((first, second) -> names.compare(first.loadLabel(pm).toString(), second.loadLabel(pm).toString()));
        return visible;
    }

    void launch(ComponentName component) {
        Rect screen = windows.getCurrentWindowMetrics().getBounds();
        Insets safe = windows.getCurrentWindowMetrics().getWindowInsets()
            .getInsetsIgnoringVisibility(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
        int margin = dp(14);
        int right = screen.right - safe.right - margin;
        int top = screen.top + safe.top + dp(76);
        int width = Math.min(dp(350), screen.width() * 52 / 100);
        int height = Math.min(dp(460), screen.bottom - safe.bottom - margin - top);
        Rect bounds = new Rect(right - width, top, right, top + height);
        Bundle choices = ActivityOptions.makeBasic().setLaunchBounds(bounds).toBundle();
        choices.putInt(WINDOWING_MODE, FREEFORM_MODE);
        Intent intent = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            .setComponent(component).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent, choices);
    }

    private TextView label(String text, int size) {
        TextView label = new TextView(context);
        label.setText(text);
        label.setTextSize(size);
        label.setTextColor(TEXT);
        label.setGravity(Gravity.CENTER_VERTICAL);
        label.setSingleLine();
        label.setEllipsize(android.text.TextUtils.TruncateAt.END);
        return label;
    }

    private GradientDrawable shape(int color, int radius) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(dp(radius));
        return shape;
    }

    private RippleDrawable ripple() {
        return new RippleDrawable(ColorStateList.valueOf(0x18203240), null, shape(Color.WHITE, 12));
    }

    private int dp(int size) { return Math.round(size * context.getResources().getDisplayMetrics().density); }

    private final class AppList extends BaseAdapter {
        private final List<ResolveInfo> apps;

        AppList(List<ResolveInfo> apps) { this.apps = apps; }
        @Override public int getCount() { return apps.size(); }
        @Override public Object getItem(int index) { return apps.get(index); }
        @Override public long getItemId(int index) { return index; }

        @Override public View getView(int index, View old, ViewGroup parent) {
            LinearLayout item;
            if (old == null) {
                item = new LinearLayout(context);
                item.setGravity(Gravity.CENTER_VERTICAL);
                item.setPadding(dp(10), 0, dp(10), 0);
                item.setBackground(ripple());
                item.setLayoutParams(new ListView.LayoutParams(-1, dp(52)));
                FrameLayout frame = new FrameLayout(context);
                frame.addView(new ImageView(context), new FrameLayout.LayoutParams(dp(28), dp(28), Gravity.CENTER));
                item.addView(frame, new LinearLayout.LayoutParams(dp(40), -1));
                TextView name = label("", 15);
                name.setPadding(dp(8), 0, 0, 0);
                item.addView(name, new LinearLayout.LayoutParams(0, -1, 1));
            } else {
                item = (LinearLayout) old;
            }
            ResolveInfo app = apps.get(index);
            PackageManager pm = context.getPackageManager();
            ((TextView) item.getChildAt(1)).setText(app.loadLabel(pm));
            ((ImageView) ((FrameLayout) item.getChildAt(0)).getChildAt(0)).setImageDrawable(app.loadIcon(pm));
            return item;
        }
    }
}
