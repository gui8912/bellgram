package com.saiga.find.messagefinder;

import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.KeyEvent; // modified
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

/**
 * 全屏告警页。
 *
 * <p>设计参考：短信铃铛的「锁屏直接弹出全屏告警，必须手动点停止或稍后提醒」，
 * 以及系统闹钟类应用的通行做法（全屏 Intent + 锁屏可见 + 亮屏）。
 *
 * <p>为什么值得做：普通高优先级通知在锁屏上只是一个横幅，横幅可以被忽略、可以被系统折叠；
 * 全屏页会把当前画面整个盖掉，用户要么点「停止」要么点「稍后提醒」才能继续用手机，
 * 这才是“叫醒力度”的实质提升。仅在**锁屏或息屏**时由系统自动拉起；
 * 手机正在被使用时，系统只会显示为浮动通知，不会强行打断（这是 Android 的既定策略）。
 *
 * <p>【v4 架构重构】本页现在是整条提醒链路的**关键合规节点**：
 * targetSdk 31+ 不允许从后台启动前台服务，但「应用处于用户可见状态」是官方豁免情形。
 * 因此短信广播只发通知，通知把本页拉起后，由本页在 onCreate 里启动前台服务播放铃声。
 *
 * <p>边界处理（这些点最容易做成“卡死”或者“关不掉”）：
 * <ul>
 *   <li>返回键 = 稍后提醒（不会出现“按了返回铃声还在响、又找不到入口”的死局）；</li>
 *   <li>改用 OnBackPressedDispatcher 接管返回键，适配 Android 13+ 预测式返回手势
 *       （manifest 已开启 enableOnBackInvokedCallback）；</li>
 *   <li>服务启动失败时不会崩，通知渠道自带的闹铃音已经响过，不至于完全静默；</li>
 *   <li>页面只是「显示层 + 合法启动点」，不持有定时器；轮次由 AlertScheduler 托管。</li>
 * </ul>
 */
public class AlertActivity extends AppCompatActivity {

    private static final String TAG = "AlertActivity";

    private String sender = "";
    private String keyword = "";
    private String person = "";
    private String body = "";
    /** modified: 【v4.2】记录本实例已经为哪一輪启动过服务，用于 onNewIntent 去重。 */
    private int lastStartedRound = -1; // modified
    /** modified: 【v4.3.1】配置变更重建时把「已启动轮次」一起带过去。 */
    private static final String STATE_LAST_STARTED_ROUND = "last_started_round"; // modified

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // 必须在 setContentView 之前处理锁屏展示，否则首帧会闪一下黑色
        showOverLockScreen();
        setContentView(R.layout.activity_alert);
        EdgeToEdgeHelper.apply(this, R.id.alert_root); // modified: targetSdk 35+ 强制边到边
        bindActions();
        bindBackHandling(); // modified: 预测式返回手势
        bindContent(getIntent());
        // modified: 【独立复审修正】原实现无条件调用。屏幕旋转等配置变更会重建本页，
        // 重建后再次 startForegroundService 会让服务进入新一轮 handleStartAlert，
        // 表现就是「手机一转，正在响的闹铃从头开始」。只有首次创建才需要启动。
        if (savedInstanceState == null) {
            lastStartedRound = AlertSession.get(this).getRound(); // modified: 登记已启动轮次
            startAlertService(); // modified: 用户可见状态下启动前台服务，属于系统豁免
        } else {
            // modified: 【v4.3.1 独立复审】manifest 的 configChanges 已移除 uiMode，
            // 主题切换会重建本页，lastStartedRound 随之归零。重建后若再收到「同一轮」
            // 的通知点击，onNewIntent 会把同轮误判成新轮次，重播一个已经结束的提醒。
            // 从 savedInstanceState 恢复，保证重建前后对「这一轮是否启动过」认知一致。
            lastStartedRound = savedInstanceState.getInt( // modified
                    STATE_LAST_STARTED_ROUND, -1); // modified
        }
    }

    @Override // modified
    protected void onSaveInstanceState(Bundle outState) { // modified
        super.onSaveInstanceState(outState); // modified
        outState.putInt(STATE_LAST_STARTED_ROUND, lastStartedRound); // modified
    } // modified

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // singleTop + 全屏 Intent 重复触发时复用本实例，只需刷新内容
        setIntent(intent);
        bindContent(intent);
        // modified: 【v4.2 独立复审】原实现无条件 startAlertService()。
        // 全屏告警页已显示、铃声正在响时，用户点通知横幅/正文会走 onNewIntent，
        // 服务因此重新 handleStartAlert：取消本轮定时器、reset MediaPlayer、从头播放，
        // 反复点通知就能让这一轮永远响不完。只有轮次真的变了才需要重新启动。
        int incomingRound = AlertSession.get(this).getRound(); // modified
        if (incomingRound != lastStartedRound) { // modified
            lastStartedRound = incomingRound; // modified
            startAlertService(); // modified: 仅新一轮重启
        } else { // modified
            Log.d(TAG, "same round re-delivered, keep current playback"); // modified
        }
    }

    /**
     * 让页面能盖在锁屏之上、并把屏幕点亮。
     *
     * <p>Android 8.1 起提供了官方 API（{@code setShowWhenLocked}/{@code setTurnScreenOn}），
     * 低版本退回 Window flag。两套都必须写，否则旧机型上锁屏里根本看不到这个页面。
     */
    private void showOverLockScreen() { // modified: 新增
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                setShowWhenLocked(true);
                setTurnScreenOn(true);
                // modified: 【独立复审修正】原实现还调用了
                // keyguardManager.requestDismissKeyguard(this, null)。
                // 该 API 会弹出「解锁以继续」的凭据界面，与「必须点停止才能继续用手机」
                // 的设计目标正好相反，AOSP 系统闹钟也不这么做——它只用
                // setShowWhenLocked + setTurnScreenOn，把页面盖在锁屏之上而不解绑锁屏。
                // 因此这里移除该调用（manifest 同时补了 showWhenLocked 属性兜底部分 ROM）。
            } else {
                getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                        | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                        | WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD);
            }
            // 页面停留期间保持亮屏：用户可能需要对着短信内容操作
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } catch (Exception e) {
            Log.w(TAG, "show over lock screen failed", e);
        }
    }

    private void bindActions() { // modified: 新增
        View stopButton = findViewById(R.id.alert_stop);
        if (stopButton != null) {
            stopButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    AlertDispatcher.stop(AlertActivity.this); // modified: 统一走调度器
                    finishSafely();
                }
            });
        }
        View snoozeButton = findViewById(R.id.alert_snooze);
        if (snoozeButton != null) {
            snoozeButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    snoozeAndFinish(); // modified: 统一走调度器
                    finishSafely();
                }
            });
        }
    }

    /**
     * 把内容填进界面。所有字段都做空值兜底，避免“短信正文为空”时整页显示 null。
     *
     * @param intent 携带提醒数据的 Intent
     */
    private void bindContent(Intent intent) { // modified: 新增
        if (intent != null) {
            sender = NotificationHelper.getFrom(intent);
            keyword = NotificationHelper.getKeyword(intent);
            person = NotificationHelper.getPerson(intent);
            body = NotificationHelper.getPayload(intent);
        }
        String displaySender = TextUtils.isEmpty(person)
                ? (TextUtils.isEmpty(sender) ? getString(R.string.app_name) : sender)
                : person + " (" + sender + ")";

        TextView senderView = findViewById(R.id.alert_sender);
        if (senderView != null) {
            senderView.setText(getString(R.string.alert_from, displaySender));
        }
        TextView keywordView = findViewById(R.id.alert_keyword);
        if (keywordView != null) {
            if (TextUtils.isEmpty(keyword)) {
                keywordView.setVisibility(View.GONE);
            } else {
                keywordView.setVisibility(View.VISIBLE);
                keywordView.setText(getString(R.string.alert_keyword, keyword));
            }
        }
        TextView bodyView = findViewById(R.id.alert_body);
        if (bodyView != null) {
            bodyView.setText(TextUtils.isEmpty(body) ? getString(R.string.notif_summary) : body);
        }
    }

    /**
     * 在本页可见时启动前台服务，播放这一轮铃声与震动。
     *
     * <p>这是 v4 合规链路的核心：Android 12+ 的后台启动限制有「应用从用户可见状态转换」
     * 这一豁免项，而短信广播不在豁免清单里。把启动点放到这里，主链路才成立。
     * 即使启动失败，通知渠道自带的闹铃音也已经在通知到达时响过，不会完全静默。
     */
    private void startAlertService() { // modified: 新增
        if (!AlertSession.get(this).isActive()) {
            Log.d(TAG, "session inactive, skip starting service");
            return;
        }
        try {
            Intent intent = new Intent(this, SMSForegroundService.class); // 显式 Intent
            intent.setAction(SMSForegroundService.ACTION_START_ALERT);
            NotificationHelper.putAlertExtras(intent, sender, keyword, person, body);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent);
            } else {
                startService(intent);
            }
            // 【新增 v4.5.9】诊断里明确「告警页是否真的拉起」：这一页没起来，
            // 播放服务就不会启动，用户只会听到通知渠道那一声。
            SmsTrace.add(AlertActivity.this, "告警页已拉起", // 新增 v4.5.9
                    "本轮持续响铃交由播放服务接管"); // 新增 v4.5.9
        } catch (Exception e) {
            // 例如用户刚点过「停止」、或系统临时拒绝：不能让全屏页因此崩掉
            Log.e(TAG, "start foreground service failed", e);
        }
    }

    /** 稍后提醒的间隔与通知按钮保持一致。 */
    private void snoozeAndFinish() { // modified: 新增
        // modified: 稍后提醒时长改为独立可配（参考系统时钟的贪睡时长）
        int minutes = AppConfig.get(this).getSnoozeMinutes(); // modified
        AlertDispatcher.snooze(this, minutes);
    }

    /**
     * modified: v4.3 参考 Google Clock / MIUI 时钟的「音量按钮」设置。
     * 默认不拦截（音量键仍正常调音量），也可设为「稍后提醒」或「停止提醒」。
     * 只在全屏告警页生效，避免影响用户其它场景。
     */
    @Override // modified
    public boolean onKeyDown(int keyCode, KeyEvent event) { // modified
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP // modified
                || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) { // modified
            // modified: 【v4.3 独立复审】长按音量键会连续回调 onKeyDown，
            // 原实现每次都跑一遍 snooze/stop + finish，造成重复调度与多次结束页面。
            if (event != null && event.getRepeatCount() > 0) { // modified
                return true; // modified
            } // modified
            String action = AppConfig.get(this).getVolumeKeyAction(); // modified
            if (AppConfig.ACTION_SNOOZE.equals(action)) { // modified
                snoozeAndFinish(); // modified
                finishSafely(); // modified
                return true; // modified
            } // modified
            // modified: 只把明确的 "stop" 当停止。原实现用 else 兜底，
            // 任何新增/未知动作都会被当成「结束提醒」，属于静默的状态机误判。
            if (AppConfig.ACTION_STOP.equals(action)) { // modified
                AlertDispatcher.stop(this); // modified
                finishSafely(); // modified
                return true; // modified
            } // modified
            return super.onKeyDown(keyCode, event); // modified
        } // modified
        return super.onKeyDown(keyCode, event); // modified
    } // modified

    /** 返回键 = 稍后提醒。用 OnBackPressedDispatcher 兼容预测式返回手势。 */
    private void bindBackHandling() { // modified: 新增
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                snoozeAndFinish();
                finishSafely();
            }
        });
    }

    /** 关闭本页。用 finishAndRemoveTask 兜底，避免异常状态下回到空白任务栈。 */
    private void finishSafely() { // modified: 新增
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                finishAndRemoveTask();
            } else {
                finish();
            }
        } catch (Exception e) {
            Log.w(TAG, "finish alert activity failed", e);
            finish();
        }
    }

    // modified: 删除 onBackPressed() 重写。
    // manifest 开启 enableOnBackInvokedCallback 后，Android 13+ 不再回调该方法；
    // 统一由 bindBackHandling() 注册的 OnBackPressedCallback 处理，避免双重触发。
}
