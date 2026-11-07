package com.saiga.find.messagefinder;

// [新增文件 / v4.5.6] 接收链路诊断日志。
//
// 背景：v4.5.5 用户实测「收到含关键词的短信，应用完全没反应」。通读链路后发现
// SMSReceiver 有 7 处静默 return（总开关关闭、未配置关键词、号码不匹配、关键词不命中、
// 排除词命中、重复投递、正文为空），NotificationHelper 还有「通知总开关被关 /
// POST_NOTIFICATIONS 被拒 / 渠道缺失」等静默放弃分支。任何一处命中，用户和开发者
// 都只能看到「没反应」，无法区分是系统根本没投递广播、规则写错了，还是通知被系统吞掉。
//
// 这里把每一步都落成本机环形日志（只写本地 SharedPreferences，不外传、不申请新权限），
// 并提供一个统一对话框供首页状态条与设置页调用。诊断日志属于旁路能力：所有 IO 都
// 吞异常、用 commit() 同步落盘（广播接收器返回后进程随时可能被回收），
// 任何失败都不得影响提醒主链路。

import android.Manifest; // 新增 v4.5.7
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager; // 新增 v4.5.7
import android.text.TextUtils;
import android.util.Log;
import android.widget.Toast;

import androidx.core.app.NotificationManagerCompat; // 新增 v4.5.7
import androidx.core.content.ContextCompat; // 新增 v4.5.7
import androidx.preference.PreferenceManager;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Set; // 新增 v4.5.7

/** 短信接收全过程的可视化诊断记录（最近 N 条）。 */
public final class SmsTrace {

    private static final String TAG = "SmsTrace";
    /** 独立键名，不复用 hit_log，避免升级后把命中记录冲掉。 */
    private static final String KEY_TRACE = "sms_trace_v456";
    /** 最近一次「被跳过」的原因，命中后清空；首页状态条直接显示它。 */
    private static final String KEY_LAST_SKIP = "sms_trace_last_skip_v456";
    private static final int MAX_ENTRIES = 30;
    private static final String LINE_SEPARATOR = "\n";
    private static final String TIME_PATTERN = "MM-dd HH:mm:ss";

    /** 广播接收器、前台服务、主线程都可能写入，加锁保护「读-改-写」。 */
    private static final Object LOCK = new Object();

    private SmsTrace() {
        // 工具类不允许实例化
    }

    /** 记录一个普通步骤（不改变「最近未触发」状态）。 */
    public static void add(Context context, String stage, String detail) { // 新增 v4.5.6
        append(context, stage, detail, false, false);
    }

    /** 记录一次「短信被跳过」，同时把它作为首页可见的最近未触发原因。 */
    public static void skip(Context context, String reason, String detail) { // 新增 v4.5.6
        append(context, reason, detail, true, false);
    }

    /** 记录一次「规则命中并派发」，清掉旧的未触发提示。 */
    public static void hit(Context context, String detail) { // 新增 v4.5.6
        // 清除与写入放在同一个锁内完成：两条短信并发时，否则可能出现
        // 「A 命中清提示 → B 跳过写提示 → A 的清除被覆盖 / 或反过来」的错序。
        // 【修改 v4.5.7】原文案「已命中并派发」写在派发之前，会让诊断误导排障：
        // 看起来已经派发了，实际后面才调用 AlertDispatcher。改成「规则命中」，
        // 派发成功由 SMSReceiver 另记一条「已交给提醒调度」。
        append(context, "规则命中", detail, false, true);
    }

    private static void append(Context context, String stage, String detail,
                               boolean skipped, boolean clearSkip) {
        if (context == null) {
            return;
        }
        String line;
        try {
            String time = new SimpleDateFormat(TIME_PATTERN, Locale.getDefault()).format(new Date());
            line = time + " " + oneLine(stage, 24) + " · " + oneLine(detail, 90);
        } catch (Exception e) {
            return; // 诊断失败绝不影响主链路
        }
        synchronized (LOCK) {
            try {
                Context app = context.getApplicationContext() != null
                        ? context.getApplicationContext() : context;
                SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(app);
                String stored = prefs.getString(KEY_TRACE, "");
                List<String> lines = new ArrayList<>();
                lines.add(line);
                if (!TextUtils.isEmpty(stored)) {
                    String[] previous = stored.split(LINE_SEPARATOR);
                    for (String old : previous) {
                        if (lines.size() >= MAX_ENTRIES) {
                            break;
                        }
                        if (!TextUtils.isEmpty(old) && !old.trim().isEmpty()) {
                            lines.add(old);
                        }
                    }
                }
                SharedPreferences.Editor editor = prefs.edit()
                        .putString(KEY_TRACE, TextUtils.join(LINE_SEPARATOR, lines));
                if (skipped) {
                    editor.putString(KEY_LAST_SKIP, line);
                } else if (clearSkip) {
                    editor.remove(KEY_LAST_SKIP);
                }
                editor.commit(); // 同步落盘：广播接收器返回后进程可能被回收
            } catch (Exception e) {
                Log.w(TAG, "write trace failed", e);
            }
        }
    }

    /** @return 最近一次短信被跳过的原因；没有则返回空串 */
    public static String lastSkip(Context context) { // 新增 v4.5.6
        if (context == null) {
            return "";
        }
        synchronized (LOCK) {
            try {
                Context app = context.getApplicationContext() != null
                        ? context.getApplicationContext() : context;
                SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(app);
                String value = prefs.getString(KEY_LAST_SKIP, "");
                return value == null ? "" : value;
            } catch (Exception e) {
                return "";
            }
        }
    }

    /** @return 全部诊断记录，最新一条在最上面；无记录返回空串 */
    public static String dump(Context context) { // 新增 v4.5.6
        if (context == null) {
            return "";
        }
        synchronized (LOCK) {
            try {
                Context app = context.getApplicationContext() != null
                        ? context.getApplicationContext() : context;
                String stored = PreferenceManager.getDefaultSharedPreferences(app)
                        .getString(KEY_TRACE, "");
                return stored == null ? "" : stored;
            } catch (Exception e) {
                return "";
            }
        }
    }

    /** 清空诊断记录与最近未触发提示（不动命中记录）。 */
    public static void clear(Context context) { // 新增 v4.5.6
        if (context == null) {
            return;
        }
        synchronized (LOCK) {
            try {
                Context app = context.getApplicationContext() != null
                        ? context.getApplicationContext() : context;
                PreferenceManager.getDefaultSharedPreferences(app).edit()
                        .remove(KEY_TRACE)
                        .remove(KEY_LAST_SKIP)
                        .commit();
            } catch (Exception e) {
                Log.w(TAG, "clear trace failed", e);
            }
        }
    }

    /**
     * 【新增 v4.5.7】当前规则与权限快照。
     *
     * <p>广播根本没投递时，接收轨迹是空的，光看轨迹只知道「没收到」，仍然无法判断
     * 是权限没给、关键词没配、号码写错还是 ROM 拦了。这张快照把「应用此刻实际读到
     * 的配置」和两项关键权限一起摊开，一张截图即可定位，不必再来回追问用户。
     * 与轨迹里的中文文案保持同一风格，全部只读、不写任何配置。
     */
    private static String snapshot(Context context) { // 新增 v4.5.7
        StringBuilder b = new StringBuilder();
        try {
            Context app = context.getApplicationContext() != null
                    ? context.getApplicationContext() : context;
            AppConfig config = AppConfig.get(app);
            Set<String> keywords = config.getKeywords();
            b.append("关键词：").append(keywords.isEmpty()
                    ? "【未配置】" : TextUtils.join(" ", keywords)).append('\n');
            String contact = config.getContact();
            b.append("关注号码：").append(contact.isEmpty()
                    ? "不限（所有发送方）" : contact).append('\n');
            b.append("匹配模式：").append(config.isMatchAllEnabled()
                    ? "全部命中（需每条词都出现）" : "任意命中").append('\n');
            b.append("提醒总开关：").append(config.isAlertEnabled()
                    ? "开" : "【已关闭】").append('\n');
            Set<String> excluded = config.getExcludeKeywords();
            b.append("排除词：").append(excluded.isEmpty()
                    ? "无" : TextUtils.join(" ", excluded)).append('\n');
            boolean sms = ContextCompat.checkSelfPermission(app, Manifest.permission.RECEIVE_SMS)
                    == PackageManager.PERMISSION_GRANTED;
            b.append("接收短信权限：").append(sms ? "已授予" : "【未授予】").append('\n');
            boolean notif;
            try {
                notif = NotificationManagerCompat.from(app).areNotificationsEnabled();
            } catch (Exception e) {
                notif = false;
            }
            b.append("应用通知总开关：").append(notif ? "已开启" : "【已关闭】");
        } catch (Exception e) {
            Log.w(TAG, "build config snapshot failed", e);
            b.append("（读取配置失败，不影响提醒）");
        }
        return b.toString();
    }

    /**
     * 统一的「接收诊断」对话框：首页状态条、设置页「接收诊断」与「最近命中记录」
     * 共用，避免多处各写一份。同时展示接收轨迹、当前规则权限快照与命中记录，
     * 用户实测一条短信就能看出卡在哪一步。
     */
    public static void showDialog(Context context) { // 新增 v4.5.6
        if (context == null) {
            return;
        }
        try {
            String trace = dump(context);
            String hits = HitLog.dump(context);
            String body = context.getString(R.string.sms_trace_dialog_body,
                    TextUtils.isEmpty(trace)
                            ? context.getString(R.string.sms_trace_empty) : trace,
                    snapshot(context), // 新增 v4.5.7
                    TextUtils.isEmpty(hits)
                            ? context.getString(R.string.hit_log_empty) : hits);
            new MaterialAlertDialogBuilder(context)
                    .setTitle(R.string.sms_trace_title)
                    .setMessage(body)
                    .setNegativeButton(android.R.string.cancel, null)
                    .setNeutralButton(R.string.sms_trace_clear, (dialog, which) -> {
                        clear(context);
                        Toast.makeText(context, R.string.sms_trace_cleared,
                                Toast.LENGTH_SHORT).show();
                    })
                    .show();
        } catch (Exception e) {
            // Activity 正在销毁 / 部分 ROM 的窗口异常都不能让应用崩溃
            Log.w(TAG, "show trace dialog failed", e);
        }
    }

    /** 单行化 + 码点安全截断，防止畸形号码或正文把环形缓冲挤坏。 */
    private static String oneLine(String value, int maxLength) { // 新增 v4.5.6
        if (value == null) {
            return "";
        }
        String text = value.replace('\n', ' ').replace('\r', ' ')
                .replace('\t', ' ').trim();
        if (text.codePointCount(0, text.length()) <= maxLength) {
            return text;
        }
        return text.substring(0, text.offsetByCodePoints(0, maxLength)) + "…";
    }
}
