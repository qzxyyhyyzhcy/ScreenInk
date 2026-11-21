package local.screenink;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Toast;

public final class LaunchActivity extends Activity {
    private boolean enabledSettings;
    private AlertDialog setup;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (state != null) enabledSettings = state.getBoolean("opened-settings");
    }

    @Override protected void onResume() {
        super.onResume();
        InkService service = InkService.connected();
        if (service != null) {
            if (setup != null) setup.dismiss();
            finish();
            service.openAfterLauncher();
        } else if (enabledSettings) {
            enabledSettings = false;
            getWindow().getDecorView().postDelayed(() -> {
                if (isFinishing() || isDestroyed()) return;
                InkService bound = InkService.connected();
                if (bound != null) {
                    finish();
                    bound.openAfterLauncher();
                } else {
                    showSetup();
                }
            }, 450);
        } else {
            showSetup();
        }
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putBoolean("opened-settings", enabledSettings);
        super.onSaveInstanceState(state);
    }

    private void showSetup() {
        if (setup != null && setup.isShowing()) return;
        setup = new AlertDialog.Builder(this)
            .setTitle(R.string.app_name)
            .setMessage("在系统无障碍设置中启用「屏幕批注」，即可从焦点笔 Plus 用笔键唤出画笔。")
            .setPositiveButton("打开无障碍设置", (dialog, which) -> {
                try {
                    enabledSettings = true;
                    startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
                } catch (ActivityNotFoundException unavailable) {
                    Toast.makeText(this, "请在系统设置中搜索「无障碍」", Toast.LENGTH_LONG).show();
                    finish();
                }
            })
            .setNegativeButton("关闭", (dialog, which) -> finish())
            .setOnCancelListener(dialog -> finish())
            .show();
    }
}
