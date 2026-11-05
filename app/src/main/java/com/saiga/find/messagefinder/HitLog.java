package com.saiga.find.messagefinder;

// [新增文件] 命中记录（最近 N 条）。
// 需求来源：这类“关键词触发提醒”的应用一旦不响，用户无法判断是“没收到短信 / 关键词没匹配 / 服务没起来”，
// 属于最典型的排障痛点。参考 pppscn/SmsForwarder 的“转发日志”设计思路，
// 这里做一个极简的环形缓冲（上限 20 条），只落本地 SharedPreferences，不外传、不申请新权限。
// 实现上不引入 Room / 数据库依赖，避免为一个日志功能增加 APK 体积与迁移成本。

import android.content.Context;
import android.content.SharedPreferences;
import androidx.preference.PreferenceManager; // modified: 清除平台废弃 API，默认文件名不变
import android.text.TextUtils;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 短信命中记录，用于在设置页自查“规则是否生效”。
 */
public final class HitLog {

    private static final String KEY_HIT_LOG = "hit_log";
    private static final int MAX_ENTRIES = 20;
    private static final String LINE_SEPARATOR = "\n";
    private static final String FIELD_SEPARATOR = " | ";
    private static final int SNIPPET_MAX_LENGTH = 40;
    private static final String TIME_PATTERN = "MM-dd HH:mm:ss";

    /** 广播接收器与主线程都可能写入，加锁避免“读-改-写”竞争导致记录丢失。 */
    private static final Object LOCK = new Object();

    private HitLog() {
        // 工具类不允许实例化
    }

    /**
     * 追加一条命中记录。
     *
     * @param context 任意 Context
     * @param sender  发送方号码
     * @param keyword 命中的关键词
     * @param body    短信正文
     */
    public static void add(Context context, String sender, String keyword, String body) { // modified: 新增
        if (context == null) {
            return;
        }
        String record = format(sender, keyword, body);
        synchronized (LOCK) {
            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
            String stored = prefs.getString(KEY_HIT_LOG, "");
            List<String> lines = new ArrayList<>();
            lines.add(record);
            if (!TextUtils.isEmpty(stored)) {
                String[] previous = stored.split(LINE_SEPARATOR);
                for (String line : previous) {
                    if (lines.size() >= MAX_ENTRIES) {
                        break;
                    }
                    if (!TextUtils.isEmpty(line) && !line.trim().isEmpty()) {
                        lines.add(line);
                    }
                }
            }
            // modified: 【v4.2】apply() 只更新内存、把磁盘写入排进异步队列；
            // AOSP 只在 Activity/Service stop 时等待落盘，BroadcastReceiver 返回后
            // 进程随时可被回收。这里是唯一落在短信广播链路上的写操作，
            // 改 commit() 同步落盘（20 条短文本，开销可忽略）。
            prefs.edit().putString(KEY_HIT_LOG, TextUtils.join(LINE_SEPARATOR, lines)).commit(); // modified
        }
    }

    /**
     * @param context 任意 Context
     * @return 命中记录文本，每条一行；无记录时返回空串
     */
    public static String dump(Context context) { // modified: 新增，供设置页展示
        if (context == null) {
            return "";
        }
        // modified: 【v4.2】原实现只有 add() 加锁，clear()/dump() 裸读写；
        // 「点清空」与「来短信」并发时，add() 可能把清空前的旧记录写回，清空失效。
        synchronized (LOCK) { // modified
            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context); // modified
            String stored = prefs.getString(KEY_HIT_LOG, ""); // modified
            return stored == null ? "" : stored; // modified
        }
    }

    /**
     * 清空命中记录。
     *
     * @param context 任意 Context
     */
    public static void clear(Context context) { // modified: 新增
        if (context == null) {
            return;
        }
        synchronized (LOCK) { // modified: 【v4.2】与 add()/dump() 共用同一把锁
            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context); // modified
            prefs.edit().remove(KEY_HIT_LOG).commit(); // modified: 同步落盘
        }
    }

    private static String format(String sender, String keyword, String body) {
        String time = new SimpleDateFormat(TIME_PATTERN, Locale.getDefault()).format(new Date());
        // modified: 【v4.2】原实现只对 body 做单行化，sender / keyword 仅 trim()。
        // 畸形 SP 号或历史关键词里含换行时，一条记录会被 stored.split("\n")
        // 拆成多行，20 条环形缓冲被迅速挤空，设置页里字段也错位。
        String safeSender = singleLine(sender, 24); // modified
        String safeKeyword = singleLine(keyword, 30); // modified
        if (safeSender.isEmpty()) { // modified
            safeSender = "未知号码"; // modified
        }
        if (safeKeyword.isEmpty()) { // modified
            safeKeyword = "-"; // modified
        }
        return time + FIELD_SEPARATOR + safeSender + FIELD_SEPARATOR + "[" + safeKeyword + "] "
                + singleLine(body, SNIPPET_MAX_LENGTH); // modified
    }

    /** 单行化 + 码点安全截断。所有写入记录的字段都必须经过这里。 */
    private static String singleLine(String value, int maxLength) { // modified: v4.2 重写
        if (value == null) { // modified
            return "";
        }
        String text = value.replace('\n', ' ').replace('\r', ' ').replace('\t', ' ').trim(); // modified
        if (text.codePointCount(0, text.length()) <= maxLength) { // modified
            return text; // modified
        }
        // modified: 按 code point 截，避免切断 emoji 的 surrogate pair 写入非法 UTF-16
        return text.substring(0, text.offsetByCodePoints(0, maxLength)) + "…"; // modified
    }
}
