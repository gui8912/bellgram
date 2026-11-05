package com.saiga.find.messagefinder;

import android.app.ActivityOptions;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.text.HtmlCompat;
import androidx.viewpager.widget.ViewPager;

/**
 * 首次安装时的引导页（3 屏）。完成后把自身组件禁用，之后不再出现。
 *
 * <p>修改要点：
 * <ul>
 *   <li>指示点颜色取模，避免页数变化后数组越界（原实现 pageCount 与颜色数强绑定）；</li>
 *   <li>初始状态下“上一步”按钮隐藏（原来第一屏会显示一个不可用的“上一步”）；</li>
 *   <li>“完成”时即使组件禁用失败也不影响进入主界面。</li>
 * </ul>
 */
public class Onboarding extends AppCompatActivity {

    private static final String TAG = "Onboarding";

    private ViewPager slideView;
    private LinearLayout dotLayout;
    private SliderAdapter pageAdapter;
    private Button prevButton;
    private Button nextButton;
    private TextView[] mDots;
    /** 当前页索引。 */
    private int mCurrentPage;

    /** 每屏对应的强调色。 */
    private final int[] mDotColor = {
            R.color.lightOrangeColor, R.color.lightBlueColor, R.color.lightPinkColor
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        setTheme(R.style.OnboardingTheme);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.screen_onboarding);

        slideView = findViewById(R.id.slideView);
        dotLayout = findViewById(R.id.navigator_menu);
        prevButton = findViewById(R.id.PrevButton);
        nextButton = findViewById(R.id.NextButton);

        pageAdapter = new SliderAdapter(this);
        slideView.setAdapter(pageAdapter);
        addDotsIndicator();
        // 初始状态：第一屏，隐藏“上一步”
        prevButton.setVisibility(View.INVISIBLE); // modified: 修复首屏显示无效“上一步”
        nextButton.setVisibility(View.VISIBLE);
        setPosition(0);
        slideView.addOnPageChangeListener(pageListener);

        nextButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (mCurrentPage != mDots.length - 1) {
                    slideView.setCurrentItem(mCurrentPage + 1, true);
                } else {
                    finishOnboarding(v);
                }
            }
        });

        prevButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (mCurrentPage != 0) {
                    slideView.setCurrentItem(mCurrentPage - 1);
                }
            }
        });
    }

    /**
     * 结束引导：进入主界面并禁用引导页组件。
     *
     * @param sourceView 触发按钮，用于共享元素动画
     */
    private void finishOnboarding(View sourceView) { // modified: 从匿名内部类抽出
        try {
            Intent launchMain = new Intent()
                    .setComponent(new ComponentName(sourceView.getContext(), MainActivity.class));
            ActivityOptions options = ActivityOptions.makeSceneTransitionAnimation(Onboarding.this);
            startActivity(launchMain, options.toBundle());
        } catch (Exception e) {
            // 动画资源异常时退化为普通跳转，不能因此卡在引导页
            Log.e(TAG, "launch main activity with transition failed", e);
            startActivity(new Intent(this, MainActivity.class));
        }
        try {
            // 禁用自身，避免用户再次进入引导页
            getPackageManager().setComponentEnabledSetting(
                    new ComponentName(this, Onboarding.class),
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP);
        } catch (Exception e) {
            Log.e(TAG, "disable onboarding component failed", e);
        }
        finish();
    }

    /** 在页面底部生成与页数一致的指示点。 */
    private void addDotsIndicator() {
        mDots = new TextView[pageAdapter.getCount()];
        for (int i = 0; i < mDots.length; i++) {
            mDots[i] = new TextView(this);
            mDots[i].setId(1000 + i);
            mDots[i].setText(HtmlCompat.fromHtml("&#9679;", HtmlCompat.FROM_HTML_MODE_COMPACT));
            mDots[i].setTextColor(getColor(R.color.primaryTextColor));
            dotLayout.addView(mDots[i]);
        }
    }

    /**
     * 更新指示点与按钮颜色。
     *
     * @param position 当前页索引
     */
    public void setPosition(int position) {
        if (mDots == null || mDots.length == 0) {
            return;
        }
        for (TextView dot : mDots) {
            dot.setTextColor(getColor(R.color.primaryTextColor));
        }
        int safePosition = Math.max(0, Math.min(position, mDots.length - 1));
        // 取模：即使未来新增页面而忘记补颜色，也不会数组越界
        int colorRes = mDotColor[safePosition % mDotColor.length]; // modified: 防数组越界
        mDots[safePosition].setTextColor(getColor(colorRes));
        nextButton.setTextColor(getColor(colorRes));
    }

    private final ViewPager.OnPageChangeListener pageListener = new ViewPager.OnPageChangeListener() {
        @Override
        public void onPageScrolled(int position, float positionOffset, int positionOffsetPixels) {
            // 无需处理
        }

        @Override
        public void onPageSelected(int position) {
            mCurrentPage = position;
            setPosition(position);
            if (position == 0) {
                prevButton.setVisibility(View.INVISIBLE);
                nextButton.setVisibility(View.VISIBLE);
                nextButton.setText(R.string.next); // modified: 回到首屏时恢复“下一步”文案
            } else if (position == mDots.length - 1) {
                prevButton.setVisibility(View.VISIBLE);
                nextButton.setText(R.string.finish);
            } else {
                prevButton.setVisibility(View.VISIBLE);
                nextButton.setVisibility(View.VISIBLE);
                prevButton.setText(R.string.prev);
                nextButton.setText(R.string.next);
            }
        }

        @Override
        public void onPageScrollStateChanged(int state) {
            // 无需处理
        }
    };
}
