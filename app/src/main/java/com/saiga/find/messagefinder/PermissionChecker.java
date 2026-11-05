package com.saiga.find.messagefinder;

// [新增文件 / modified] v4 合规改造：「权限体检」。
// targetSdk 36 之后，能否正常提醒取决于一组分散在系统各处的开关：
//   运行时权限(RECEIVE_SMS / POST_NOTIFICATIONS) + 通知总开关 + Android 14 的全屏 Intent 特殊权限
//   + 厂商电池优化白名单（红米 HyperOS 上这一项实际上最关键）。
// 缺任意一项都会表现为「短信到了却不响」，而用户完全没有线索。这里把状态集中展示并给出跳转。
//
// 跳转方式参考 Android 官方 Settings 常量与《Full-screen intent limits》(AOSP) 文档，
// 未复制第三方代码。

import android.Manifest;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.Log;

import androidx.appcompat.app.AlertDialog;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.List;

public final class PermissionChecker {

    private static final String TAG = "PermissionChecker";
    /** Android 14 的「管理全屏 Intent」设置页 action（用字面量以兼容低于 34 的编译/运行环境）。 */
    private static final String ACTION_MANAGE_FSI = "android.settings.MANAGE_APP_USE_FULL_SCREEN_INTENT"; // modified: 新增

    private PermissionChecker() {
        // 工具类不允许实例化
    }

    /** 一项权限检查结果。 */
    public static final class Item {
        public final String title;
        public final boolean ok;
        public final String detail;
        public final Intent fix;

        private Item(String title, boolean ok, String detail, Intent fix) {
            this.title = title;
            this.ok = ok;
            this.detail = detail;
            this.fix = fix;
        }
    }

    /** 汇总所有会影响提醒是否生效的系统开关。 */
    public static List<Item> check(Context context) { // modified: 新增
        List<Item> items = new ArrayList<>();
        if (context == null) {
            return items;
        }
        Context app = context.getApplicationContext();
        String pkg = app.getPackageName();
        Intent appDetails = appDetailsIntent(pkg);

        // 1) 接收短信（dangerous 权限）
        boolean sms = ContextCompat.checkSelfPermission(app, Manifest.permission.RECEIVE_SMS)
                == PackageManager.PERMISSION_GRANTED;
        items.add(new Item(app.getString(R.string.perm_item_sms), sms,
                app.getString(R.string.perm_item_sms_detail), appDetails));

        // 2) 通知：Android 13+ 的运行时权限 + 应用通知总开关
        boolean notif = true;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notif = ContextCompat.checkSelfPermission(app, Manifest.permission.POST_NOTIFICATIONS)
                    == PackageManager.PERMISSION_GRANTED;
        }
        try {
            notif = notif && NotificationManagerCompat.from(app).areNotificationsEnabled();
        } catch (Exception e) {
            Log.w(TAG, "check notification enabled failed", e);
        }
        items.add(new Item(app.getString(R.string.perm_item_notification), notif,
                app.getString(R.string.perm_item_notification_detail),
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                        ? notificationSettingsIntent(pkg) : appDetails));

        // 3) 全屏 Intent（Android 14+ 不再默认授予）
        boolean fsi = true;
        Intent fsiFix = appDetails;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            try {
                NotificationManager nm =
                        (NotificationManager) app.getSystemService(Context.NOTIFICATION_SERVICE);
                fsi = nm != null && nm.canUseFullScreenIntent();
            } catch (Exception e) {
                Log.w(TAG, "canUseFullScreenIntent failed", e);
            }
            fsiFix = fsiSettingsIntent(pkg);
        }
        items.add(new Item(app.getString(R.string.perm_item_fsi), fsi,
                app.getString(R.string.perm_item_fsi_detail), fsiFix));

        // 4) 电池优化白名单：红米 / HyperOS 上不设「无限制」会在后台被杀
        boolean battery = true;
        try {
            PowerManager pm = (PowerManager) app.getSystemService(Context.POWER_SERVICE);
            battery = pm == null || pm.isIgnoringBatteryOptimizations(pkg);
        } catch (Exception e) {
            Log.w(TAG, "check battery optimization failed", e);
        }
        items.add(new Item(app.getString(R.string.perm_item_battery), battery,
                app.getString(R.string.perm_item_battery_detail),
                new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)));

        // 5) 精确闹钟：用 setAlarmClock（用户可见闹钟），无需额外授权，仅作说明
        items.add(new Item(app.getString(R.string.perm_item_alarm), true,
                app.getString(R.string.perm_item_alarm_detail), null));

        return items;
    }

    /** @return 未就绪的项目数，用于主界面状态条提示 */
    public static int missingCount(Context context) { // modified: 新增
        int count = 0;
        for (Item item : check(context)) {
            if (!item.ok) {
                count++;
            }
        }
        return count;
    }

    /**
     * 弹出权限体检对话框。点击未就绪的条目直接跳到对应的系统设置页。
     */
    public static void showDialog(Context context) { // modified: 新增
        if (context == null) {
            return;
        }
        final List<Item> items = check(context);
        CharSequence[] labels = new CharSequence[items.size()];
        for (int i = 0; i < items.size(); i++) {
            Item item = items.get(i);
            labels[i] = (item.ok ? "✓ " : "! ") + item.title + "\n" + item.detail;
        }
        try {
            new AlertDialog.Builder(context)
                    .setTitle(R.string.permission_check_title)
                    .setItems(labels, (dialog, which) -> {
                        if (which < 0 || which >= items.size()) {
                            return;
                        }
                        Item item = items.get(which);
                        if (item.ok || item.fix == null) {
                            return;
                        }
                        try {
                            item.fix.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                            context.startActivity(item.fix);
                        } catch (Exception e) {
                            Log.w(TAG, "open permission settings failed", e);
                        }
                    })
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
        } catch (Exception e) {
            // Activity 正在销毁时弹窗会抛 WindowManager$BadTokenException
            Log.w(TAG, "show permission dialog failed", e);
        }
    }

    private static Intent appDetailsIntent(String pkg) { // modified: 新增
        Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
        intent.setData(Uri.parse("package:" + pkg));
        return intent;
    }

    private static Intent notificationSettingsIntent(String pkg) { // modified: 新增
        Intent intent = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS);
        intent.putExtra(Settings.EXTRA_APP_PACKAGE, pkg);
        return intent;
    }

    private static Intent fsiSettingsIntent(String pkg) { // modified: 新增
        Intent intent = new Intent(ACTION_MANAGE_FSI);
        intent.setData(Uri.parse("package:" + pkg));
        return intent;
    }
}
