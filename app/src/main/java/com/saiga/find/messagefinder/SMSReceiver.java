package com.saiga.find.messagefinder;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.provider.Telephony;
import android.telephony.SmsMessage;
import android.text.TextUtils;
import android.util.Log;

import java.util.Set;

/**
 * 接收短信广播，按用户配置的“号码 + 关键词”规则判断是否需要触发优先提醒。
 *
 * <p>处理链路：号码过滤 → 长短信拼接 → 关键词匹配 → 重复投递去重 → 记录命中日志
 * → 交给 {@link AlertDispatcher} 发高优先级通知（v4 起不再在这里启动前台服务）。
 *
 * <p>本次修改的要点（均以 // modified 标注）：
 * <ul>
 *   <li>整个 onReceive 包 try/catch：广播接收器抛异常会直接弹“应用已停止运行”；</li>
 *   <li>发送方号码、短信正文做空值保护，去掉原来会 NPE 的 Objects.requireNonNull；</li>
 *   <li>长短信（>70 汉字会被拆包）先拼接再匹配，避免关键词跨包时漏报；</li>
 *   <li>同一短信被系统重复投递时去重，避免连续响铃；</li>
 *   <li>【v4·破坏性】移除 startForegroundService：targetSdk 31+ 不允许从短信广播
 *       启动前台服务（官方豁免清单不含 SMS_RECEIVED），改为统一交给 AlertDispatcher。</li>
 * </ul>
 */
public class SMSReceiver extends BroadcastReceiver {

    public static final String TAG = "SMSReceiver";

    /** 重复投递判定窗口：同一发送方 + 同一正文在 3 秒内视为同一条短信。 */
    private static final long DUPLICATE_WINDOW_MS = 3000L;

    /**
     * 上一次处理过的短信指纹与时间戳，用于去重。
     *
     * <p>modified: 【独立复审修正】原注释写「广播接收器为单实例」是不准确的——
     * 系统可能为不同广播创建不同实例，真正起作用的是这两个 **静态** 字段。
     * 既然跨实例共享，就必须考虑可见性：部分 ROM 会在非主线程分发广播，
     * 加上 volatile 保证「写后立即对下一次读取可见」，避免去重偶发失效。
     */
    private static volatile String lastFingerprint = null;
    private static volatile long lastFingerprintAt = 0L;

    @Override
    public void onReceive(Context context, Intent intent) { // modified: 整体异常兜底
        if (context == null || intent == null) {
            Log.w(TAG, "onReceive with null context/intent, ignored");
            return;
        }
        try {
            handleSms(context, intent);
        } catch (Exception e) {
            // 广播接收器内未捕获的异常会直接导致进程崩溃（用户看到“应用已停止运行”）
            Log.e(TAG, "handle sms failed", e);
        }
    }

    private void handleSms(Context context, Intent intent) {
        AppConfig config = AppConfig.get(context);
        Set<String> keywords = config.getKeywords();
        if (keywords.isEmpty()) {
            // 用户没有配置关键词：直接忽略，不做任何处理（与原逻辑一致）
            Log.d(TAG, "no keyword configured, skip");
            return;
        }

        SmsMessage[] messages = Telephony.Sms.Intents.getMessagesFromIntent(intent);
        if (messages == null || messages.length == 0) {
            Log.w(TAG, "no sms message found in intent");
            return;
        }

        String sender = null;
        StringBuilder bodyBuilder = new StringBuilder();
        for (SmsMessage message : messages) {
            if (message == null) {
                continue;
            }
            if (sender == null) {
                sender = readOriginatingAddress(message);
            }
            String part = message.getDisplayMessageBody();
            if (part != null) {
                bodyBuilder.append(part); // modified: 长短信多包拼接，避免关键词跨包漏报
            }
        }
        String body = bodyBuilder.toString();
        if (TextUtils.isEmpty(body)) {
            Log.w(TAG, "empty sms body, skip");
            return;
        }

        // 号码过滤：未配置号码时表示“所有发送方都关注”
        if (!PhoneNumbers.matches(config.getContact(), sender)) {
            Log.d(TAG, "sender not matched, configured=" + config.getContact());
            return;
        }

        // 匹配模式跟着设置走：默认“任意命中”，开启后要求“全部关键词都出现”（借鉴短信铃铛）
        String matchedKeyword = KeywordMatcher.findMatch(
                keywords, body, config.isMatchAllEnabled()); // modified: 支持“全部命中”模式
        if (matchedKeyword == null) {
            Log.d(TAG, "no keyword matched, matchAll=" + config.isMatchAllEnabled());
            return;
        }

        if (isDuplicate(sender, body, matchedKeyword)) {
            Log.d(TAG, "duplicate sms within window, skip");
            return;
        }

        Log.i(TAG, "keyword matched: " + matchedKeyword + ", from " + sender);

        // 记录命中日志，便于用户在设置页自查规则是否生效
        HitLog.add(context, sender, matchedKeyword, body);

        // 通知里展示的姓名优先取用户从通讯录里选中的名字
        String personName = config.getContactName();

        // modified: v4 主链路。广播接收器处于后台，绝不能在这里启动前台服务；
        // 发通知是系统允许的高优先级行为，通知的全屏 Intent 会拉起用户可见的
        // AlertActivity，再由它启动服务播放铃声，全程符合 Android 12~16 的限制。
        AlertDispatcher.onSmsMatched(context, sender, matchedKeyword, personName, body);
    }

    /**
     * 读取发送方号码。部分机型/短信类型下 getDisplayOriginatingAddress 可能返回 null，
     * 原实现直接 Objects.requireNonNull 会抛 NullPointerException。
     */
    private String readOriginatingAddress(SmsMessage message) { // modified: 空值安全
        String address = null;
        try {
            address = message.getDisplayOriginatingAddress();
        } catch (Exception e) {
            Log.w(TAG, "read display address failed", e);
        }
        if (TextUtils.isEmpty(address)) {
            try {
                address = message.getOriginatingAddress();
            } catch (Exception e) {
                Log.w(TAG, "read raw address failed", e);
            }
        }
        return address == null ? "" : address;
    }

    /**
     * 判断是否为短时间内的重复投递。
     *
     * <p>短信广播在部分 ROM 上会被投递两次，原实现会导致同一条短信连续响两次。
     */
    private boolean isDuplicate(String sender, String body, String keyword) { // modified: 新增
        String fingerprint = (sender == null ? "" : sender) + '#' + body.length() + '#'
                + body.hashCode() + '#' + keyword;
        long now = System.currentTimeMillis();
        if (fingerprint.equals(lastFingerprint) && (now - lastFingerprintAt) < DUPLICATE_WINDOW_MS) {
            return true;
        }
        lastFingerprint = fingerprint;
        lastFingerprintAt = now;
        return false;
    }
}
