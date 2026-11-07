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
    /** modified: 【v4.2】volatile 只保证可见性，不保证「比较 + 两次写」原子。并发投递时去重会失效。 */
    private static final Object DEDUP_LOCK = new Object();

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
            // 【新增 v4.5.6】异常原本只进 logcat，用户手机上表现为「完全没反应」。
            SmsTrace.skip(context, "处理短信时异常", e.toString());
        }
    }

    private void handleSms(Context context, Intent intent) {
        // 【新增 v4.5.6】先记「广播到达」。诊断日志里没有这一行，就说明系统压根没把
        // SMS_RECEIVED 投递给本应用（短信权限未授予 / ROM 后台管控 / 组件被禁用），
        // 而不是关键词或号码规则写错。以下每个静默 return 都补一条可查看的原因。
        SmsTrace.add(context, "收到短信广播", "action=" + String.valueOf(intent.getAction()));
        AppConfig config = AppConfig.get(context);
        // modified: 借鉴 SmsForwarder「转发功能总开关」，关闭后所有短信直接忽略
        if (!config.isAlertEnabled()) { // modified
            SmsTrace.skip(context, "总开关已关闭", "设置页 → 提醒方式 → 提醒总开关"); // 新增 v4.5.6
            Log.d(TAG, "alerts paused by master switch, skip"); // modified
            return; // modified
        } // modified
        Set<String> keywords = config.getKeywords();
        if (keywords.isEmpty()) {
            // 用户没有配置关键词：直接忽略，不做任何处理（与原逻辑一致）
            SmsTrace.skip(context, "未配置关键词", "请在首页输入关键词并点「启用提醒」保存"); // 新增 v4.5.6
            Log.d(TAG, "no keyword configured, skip");
            return;
        }

        SmsMessage[] messages = Telephony.Sms.Intents.getMessagesFromIntent(intent);
        if (messages == null || messages.length == 0) {
            SmsTrace.skip(context, "广播里没有短信内容", "系统未附 PDU，多为 ROM 限制"); // 新增 v4.5.6
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
            SmsTrace.skip(context, "短信正文为空", "来源=" + (sender == null ? "" : sender)); // 新增 v4.5.6
            Log.w(TAG, "empty sms body, skip");
            return;
        }

        // 号码过滤：未配置号码时表示“所有发送方都关注”
        if (!PhoneNumbers.matches(config.getContact(), sender)) {
            SmsTrace.skip(context, "号码不匹配", // 新增 v4.5.6
                    "已配置=" + config.getContact() + "；实际来源=" + sender + "；正文=" + body); // 新增
            Log.d(TAG, "sender not matched, configured=" + config.getContact());
            return;
        }

        // 匹配模式跟着设置走：默认“任意命中”，开启后要求“全部关键词都出现”（借鉴短信铃铛）
        String matchedKeyword = KeywordMatcher.findMatch(
                keywords, body, config.isMatchAllEnabled()); // modified: 支持“全部命中”模式
        if (matchedKeyword == null) {
            SmsTrace.skip(context, "关键词未命中", // 新增 v4.5.6
                    "模式=" + (config.isMatchAllEnabled() ? "全部命中" : "任意命中")
                            + "；已配置=" + keywords + "；正文=" + body); // 新增
            Log.d(TAG, "no keyword matched, matchAll=" + config.isMatchAllEnabled());
            return;
        }

        // modified: 借鉴 SmsForwarder 的过滤规则：先命中再排除，营销/验证码短信不会吵醒用户
        String excludedBy = KeywordMatcher.findExclusion( // modified
                config.getExcludeKeywords(), body); // modified
        if (excludedBy != null) { // modified
            SmsTrace.skip(context, "被排除词拦下", "排除词=" + excludedBy + "；正文=" + body); // 新增 v4.5.6
            Log.d(TAG, "excluded by keyword: " + excludedBy); // modified
            return; // modified
        } // modified

        if (isDuplicate(sender, body, matchedKeyword)) {
            SmsTrace.skip(context, "3 秒内重复投递", "关键词=" + matchedKeyword); // 新增 v4.5.6
            Log.d(TAG, "duplicate sms within window, skip");
            return;
        }

        Log.i(TAG, "keyword matched: " + matchedKeyword + ", from " + sender);
        SmsTrace.hit(context, "命中「" + matchedKeyword + "」，来源 " + sender + "，正文=" + body); // 新增 v4.5.6

        // 记录命中日志，便于用户在设置页自查规则是否生效
        HitLog.add(context, sender, matchedKeyword, body);

        // 通知里展示的姓名优先取用户从通讯录里选中的名字
        String personName = config.getContactName();

        // modified: v4 主链路。广播接收器处于后台，绝不能在这里启动前台服务；
        // 发通知是系统允许的高优先级行为，通知的全屏 Intent 会拉起用户可见的
        // AlertActivity，再由它启动服务播放铃声，全程符合 Android 12~16 的限制。
        AlertDispatcher.onSmsMatched(context, sender, matchedKeyword, personName, body);
        SmsTrace.add(context, "已交给提醒调度", "通知/全屏页/服务由 AlertDispatcher 接管"); // 新增 v4.5.6
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
        // modified: 【v4.2 独立复审】原指纹用 body.length() + body.hashCode()。
        // String.hashCode() 是可构造碰撞的 32 位哈希，3 秒窗口内两条内容不同、
        // 长度相同且哈希相同的短信会被误判为「重复投递」而静默吞掉（漏报）。
        // 改用完整正文参与比较，'\0' 作分隔避免字段拼接歧义。
        String fingerprint = (sender == null ? "" : sender) + '\0' + body + '\0' + keyword;
        long now = System.currentTimeMillis();
        // modified: 原实现把「比较」和「两个字段的写入」拆成三步，且两个字段分两次写。
        // 部分 ROM 在线程池里并发投递广播时，两个线程可能都读到旧值而双双放行，
        // 或读到「新指纹 + 旧时间戳」的错误组合。这里用一把锁把整段收成原子操作。
        synchronized (DEDUP_LOCK) {
            if (fingerprint.equals(lastFingerprint)
                    && (now - lastFingerprintAt) < DUPLICATE_WINDOW_MS) {
                return true;
            }
            lastFingerprint = fingerprint;
            lastFingerprintAt = now;
            return false;
        }
    }
}
