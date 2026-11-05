package com.saiga.find.messagefinder;

// [新增文件 / modified] v4 架构重构：一次「提醒会话」的共享状态。
// 为什么需要它：targetSdk 36 下短信广播不能再启动前台服务，主链路改为
// 「通知 -> 全屏页 -> 单轮服务 -> 精确闹钟 -> 再发通知」。这些组件可能分属不同进程、
// 也可能在闹钟到来时进程已被回收，轮次与短信内容必须持久化，不能放在服务内存里。
//
// 设计参考：Android 官方《Background work / Schedule alarms》中「把任务状态与
// 触发者解耦」的做法，以及系统闹钟应用 Clock 用 SharedPreferences 记录下一次响铃状态的思路。
// 本项目只借用思想，未复制任何第三方代码。

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

/**
 * 提醒会话状态。使用独立的 SharedPreferences 文件，避免与用户配置混在一起。
 *
 * <p>写入统一使用 {@code commit()}（而不是 {@code apply()}）：闹钟广播接收器可能在
 * 另一个进程被立刻拉起，{@code apply()} 的异步落盘会造成「读到的还是上一轮」的竞态。
 */
public final class AlertSession {

    private static final String PREF_NAME = "alert_session_v4"; // modified: 新增
    private static final String KEY_ACTIVE = "active";
    private static final String KEY_ROUND = "round";
    private static final String KEY_TOTAL = "total_rounds";
    private static final String KEY_FROM = "from";
    private static final String KEY_KEYWORD = "keyword";
    private static final String KEY_PERSON = "person";
    private static final String KEY_BODY = "body";

    private final SharedPreferences prefs;

    private AlertSession(SharedPreferences prefs) {
        this.prefs = prefs;
    }

    /** 获取会话门面。ApplicationContext 避免持有 Activity。 */
    public static AlertSession get(Context context) { // modified: 新增
        Context app = context.getApplicationContext() != null
                ? context.getApplicationContext() : context;
        return new AlertSession(app.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE));
    }

    /**
     * 开启一次新会话（收到一条新的命中短信）。
     *
     * @param totalRounds 计划总轮次；{@code <=0} 表示不限次数
     */
    public void start(String from, String keyword, String person, String body,
                      int totalRounds) { // modified: 新增
        prefs.edit()
                .putBoolean(KEY_ACTIVE, true)
                .putInt(KEY_ROUND, 1)
                .putInt(KEY_TOTAL, Math.max(0, totalRounds))
                .putString(KEY_FROM, safe(from))
                .putString(KEY_KEYWORD, safe(keyword))
                .putString(KEY_PERSON, safe(person))
                .putString(KEY_BODY, safe(body))
                .commit();
    }

    /** @return 会话是否仍在等待用户处理 */
    public boolean isActive() { // modified: 新增
        return prefs.getBoolean(KEY_ACTIVE, false);
    }

    /** 用户点了「停止」：只把 active 置 false，内容保留给通知展示。 */
    public void stop() { // modified: 新增
        prefs.edit().putBoolean(KEY_ACTIVE, false).commit();
    }

    /** 「稍后提醒」时重新激活会话（用户明确表示这件事还没处理完）。 */
    public void setActive(boolean active) { // modified: 新增
        prefs.edit().putBoolean(KEY_ACTIVE, active).commit();
    }

    /** @return 当前（或最近一次）提醒轮次，从 1 开始 */
    public int getRound() { // modified: 新增
        return Math.max(1, prefs.getInt(KEY_ROUND, 1));
    }

    public void setRound(int round) { // modified: 新增
        prefs.edit().putInt(KEY_ROUND, Math.max(1, round)).commit();
    }

    /** @return 计划总轮次；0 表示不限次数 */
    public int getTotalRounds() { // modified: 新增
        return Math.max(0, prefs.getInt(KEY_TOTAL, 1));
    }

    /**
     * 保证第 {@code round} 轮一定会被执行。
     * 「稍后提醒」即使用户已经用完重复次数，也至少要再响一次。
     */
    public void ensureRound(int round) { // modified: 新增
        int safeRound = Math.max(1, round);
        int total = getTotalRounds();
        if (total > 0 && safeRound > total) {
            prefs.edit().putInt(KEY_TOTAL, safeRound).commit();
        }
    }

    public String getFrom() { // modified: 新增
        return safe(prefs.getString(KEY_FROM, ""));
    }

    public String getKeyword() { // modified: 新增
        return safe(prefs.getString(KEY_KEYWORD, ""));
    }

    public String getPerson() { // modified: 新增
        return safe(prefs.getString(KEY_PERSON, ""));
    }

    public String getBody() { // modified: 新增
        return safe(prefs.getString(KEY_BODY, ""));
    }

    private static String safe(String value) {
        return TextUtils.isEmpty(value) ? "" : value;
    }
}
