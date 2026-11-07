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
        /**
         * modified: v4.5.0 是否「必需」。
         *
         * <p>原实现把「电池优化白名单」也当成必需项，于是绝大多数用户第一次打开应用就看到
         * 主界面状态条变红、写着「N 项权限未就绪」，而实际上提醒链路（短信广播 → 通知 →
         * 全屏页 → 前台服务 → 精确闹钟）完全不依赖它。这类「狼来了」告警会让真正缺权限
         * 时的那条提示失去意义。现在只有「缺了就一定不响」的项才计入告警。
         */
        public final boolean required;

        private Item(String title, boolean ok, String detail, Intent fix) {
            this(title, ok, detail, fix, true);
        }

        private Item(String title, boolean ok, String detail, Intent fix, boolean required) { // modified
            this.title = title;
            this.ok = ok;
            this.detail = detail;
            this.fix = fix;
            this.required = required; // modified
        }
    }

    /** 汇总所有会影响提醒是否生效的系统开关。 */
    public static List<Item> check(Context context) { // modified: 新增
        List<Item> items = new ArrayList<>();
        if (context == null) {
            return items;
        }
        // modified: v4.6.1 与项目其它门面保持一致：ApplicationContext 理论上可能返回 null
        // （测试桩 / 极端 Context 包装），此处直接调用 app.getPackageName() 会 NPE，
        // 而这条路径正是「权限体检」的入口，崩在这里等于用户彻底失去自查手段。
        Context app = context.getApplicationContext() != null // modified
                ? context.getApplicationContext() : context; // modified
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
                new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
                false)); // modified: v4.5.0 电池优化只影响「长期存活」，不影响能否响铃，降级为建议项

        // 5) 精确闹钟：用 setAlarmClock（用户可见闹钟），无需额外授权，仅作说明
        items.add(new Item(app.getString(R.string.perm_item_alarm), true,
                app.getString(R.string.perm_item_alarm_detail), null));

        return items;
    }

    /** @return 未就绪的**必需**项目数，用于主界面状态条提示 */
    public static int missingCount(Context context) { // modified: 新增
        int count = 0;
        for (Item item : check(context)) {
            if (!item.ok && item.required) { // modified: v4.5.0 建议项不计入告警
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
            // modified: v4.5.0 未就绪的「建议项」用「·」而不是「!」，并标注非必需，
            // 让用户一眼分清「不修就收不到提醒」和「修了更省心」。
            String prefix = item.ok ? "✓ " : (item.required ? "! " : "· "); // modified
            String suffix = item.required // modified
                    ? "" : context.getString(R.string.perm_item_optional_hint); // modified
            labels[i] = prefix + item.title + suffix + "\n" + item.detail; // modified
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
