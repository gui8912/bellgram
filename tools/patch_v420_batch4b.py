# -*- coding: utf-8 -*-
"""v4.2.0 批次 4b：修复上一步引入的回归——实时配置复核不能覆盖用户主动 snooze。"""
import io, os

HERE = os.path.dirname(os.path.abspath(__file__))
JAVA = os.path.join(HERE, "..", "app", "src", "main", "java", "com", "saiga", "find", "messagefinder")


def read(p):
    with io.open(os.path.join(JAVA, p), encoding="utf-8") as f:
        return f.read()


def write(p, s):
    with io.open(os.path.join(JAVA, p), "w", encoding="utf-8", newline="\n") as f:
        f.write(s)


def rep(name, s, old, new, tag):
    assert s.count(old) == 1, "%s :: %s 未唯一匹配" % (name, tag)
    return s.replace(old, new, 1)


n = "AlertSession.java"
s = read(n)
s = rep(n, s,
'''    private static final String KEY_BODY = "body";''',
'''    private static final String KEY_BODY = "body";
    /** modified: 【v4.2】标记下一轮由用户主动「稍后提醒」触发，不参与配置复核。 */
    private static final String KEY_SNOOZED = "snoozed_round";''', "snooze 标记键")

s = rep(n, s,
'''                .putString(KEY_BODY, safe(body))
                .commit();''',
'''                .putString(KEY_BODY, safe(body))
                .remove(KEY_SNOOZED) // modified: 新会话清掉上一轮的 snooze 标记
                .commit();''', "start 清标记")

s = rep(n, s,
'''    public String getFrom() { // modified: 新增''',
'''    /** 标记「下一轮是用户主动稍后提醒要的」，即使配置改成不重复也必须响。 */
    public void markSnoozed() { // modified: v4.2 新增
        prefs.edit().putBoolean(KEY_SNOOZED, true).commit();
    }

    /** 读取并一次性消费该标记。 */
    public boolean consumeSnoozed() { // modified: v4.2 新增
        boolean value = prefs.getBoolean(KEY_SNOOZED, false);
        if (value) {
            prefs.edit().remove(KEY_SNOOZED).commit();
        }
        return value;
    }

    public String getFrom() { // modified: 新增''', "snooze 标记读写")
write(n, s)

n = "AlertDispatcher.java"
s = read(n)
s = rep(n, s,
'''            // modified: 【v4.2】用户在这轮等待期间把「重复间隔」改成 0 或关掉响铃时，
            // 已注册进 AlarmManager 的闹钟不会自动撤销，到点仍会触发。原实现不复核配置，
            // 于是「我明明关了重复，它又响了一轮」。这里用实时配置再判一次。
            AppConfig config = AppConfig.get(app);
            if (!RoundPolicy.hasNextRound(Math.max(1, round - 1), total,
                    config.getRingDurationSec(), config.getRepeatIntervalMin())) {
                Log.i(TAG, "repeat disabled by current config, finish session");
                finish(app, Math.max(1, round - 1));
                return;
            }''',
'''            // modified: 【v4.2】用户在这轮等待期间把「重复间隔」改成 0 或关掉响铃时，
            // 已注册进 AlarmManager 的闹钟不会自动撤销，到点仍会触发。原实现不复核配置，
            // 于是「我明明关了重复，它又响了一轮」。这里用实时配置再判一次。
            //
            // 但有一类例外必须放行：用户主动点的「稍后提醒」。AppConfig 里 interval=0 时
            // snooze 会用兜底间隔挂闹钟，若无条件复核就会把用户明确要求的这一轮也拦掉，
            // 属于本次修复自己引入的回归。故用一次性标记区分「自动重复」与「用户主动」。
            boolean userSnoozed = session.consumeSnoozed(); // modified: 区分来源
            if (!userSnoozed) {
                AppConfig config = AppConfig.get(app);
                if (!RoundPolicy.hasNextRound(Math.max(1, round - 1), total,
                        config.getRingDurationSec(), config.getRepeatIntervalMin())) {
                    Log.i(TAG, "repeat disabled by current config, finish session");
                    finish(app, Math.max(1, round - 1));
                    return;
                }
            }''', "复核排除主动 snooze")

s = rep(n, s,
'''            AlertScheduler.scheduleNext(app, safeDelay, nextRound);
            if (stopService) {''',
'''            AlertScheduler.scheduleNext(app, safeDelay, nextRound);
            session.markSnoozed(); // modified: 【v4.2】标记下一轮不受配置复核影响
            if (stopService) {''', "snooze 写标记")
write(n, s)

print("批次 4b 完成：修复主动稍后提醒被配置复核误拦的回归")
