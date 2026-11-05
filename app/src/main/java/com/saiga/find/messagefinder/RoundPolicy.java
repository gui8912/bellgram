package com.saiga.find.messagefinder;

// [新增文件 / modified] v4 架构重构：提醒轮次的纯计算规则。
//
// 轮次判断原先散落在 SMSForegroundService 内部（依赖 Android Context，无法在 JVM 上测试），
// 重构后「收到短信」「闹钟到点」「单轮播完」「用户稍后提醒」四个组件都要独立判断
// 「还有没有下一轮」。规则一旦出现分歧，最典型的后果就是重复提醒多响一次或干脆不响。
// 因此抽成无 Android 依赖的纯 Java 类，集中约束 + 可用 JUnit 直接验证。

/** 提醒轮次与间隔的统一计算规则。 */
public final class RoundPolicy {

    private RoundPolicy() {
        // 工具类不允许实例化
    }

    /**
     * 计算一次会话的计划总轮次。
     *
     * @param durationSec 单轮响铃时长（秒）；{@code <=0} 表示一直响到用户停止
     * @param intervalMin 重复间隔（分钟）；{@code <=0} 表示不重复
     * @param maxRepeat   最多重复次数；{@code <=0} 表示不限
     * @return 总轮次；{@code 0} 表示不限次数
     */
    public static int totalRounds(int durationSec, int intervalMin,
                                  int maxRepeat) { // modified: 新增
        if (durationSec <= 0 || intervalMin <= 0) {
            return 1;
        }
        return maxRepeat <= 0 ? 0 : Math.max(1, maxRepeat + 1);
    }

    /**
     * 当前这轮结束后是否还有下一轮。
     *
     * @param currentRound 刚结束/刚发出的轮次，从 1 开始
     * @param totalRounds  {@link #totalRounds(int, int, int)} 的结果，{@code <=0} 表示不限
     */
    public static boolean hasNextRound(int currentRound, int totalRounds,
                                       int durationSec, int intervalMin) { // modified: 新增
        if (durationSec <= 0 || intervalMin <= 0) {
            return false;
        }
        if (totalRounds <= 0) {
            return true;
        }
        return Math.max(1, currentRound) < totalRounds;
    }

    /**
     * 规范化「稍后提醒」的间隔。用户把重复间隔设为 0（不重复）时，
     * 主动点「稍后提醒」仍应给一个合理的兜底间隔，否则会变成永远不响。
     */
    public static int safeDelayMinutes(int configured, int fallback) { // modified: 新增
        if (configured > 0) {
            return configured;
        }
        return Math.max(1, fallback);
    }
}
