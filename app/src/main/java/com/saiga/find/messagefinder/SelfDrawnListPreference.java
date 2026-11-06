package com.saiga.find.messagefinder;

// [新增文件] v4.5.0：阻止框架默认弹窗，只保留应用自绘的选项弹窗。
//
// 为什么需要这个类（这是实测 + 源码核对出来的，不是推测）：
//   androidx.preference 1.2.1 的 Preference.performClick() 执行顺序是
//       ① onClick()  → DialogPreference 重写为 getPreferenceManager().showDialog(this)
//                      → PreferenceFragmentCompat 提交 ListPreferenceDialogFragmentCompat
//       ② mOnClickListener.onPreferenceClick(this)
//   也就是说：OnPreferenceClickListener 是在①之后才回调的，返回 true 只能拦住
//   「OnPreferenceTreeClick / Intent」，**拦不住已经提交的 DialogFragment**。
//   于是 v4.4.0 的做法（只挂 OnPreferenceClickListener 自绘弹窗）会同时出现两个对话框：
//   先是我们自绘的，随后框架那个「只有提示文字、没有选项」的对话框压在上面。
//
// 解决办法：让 DialogPreference.onClick() 变成空实现，框架不再提交任何 DialogFragment，
// 点击后只剩我们自绘的那一个。setValue / getValue / entries / entryValues /
// SummaryProvider / 持久化行为全部继承自 ListPreference，键名与存储格式一个字节都没变。
import android.content.Context;
import android.util.AttributeSet;

import androidx.preference.ListPreference;

public class SelfDrawnListPreference extends ListPreference { // modified: v4.5.0 新增

    public SelfDrawnListPreference(Context context, AttributeSet attrs,
                                   int defStyleAttr, int defStyleRes) { // modified
        super(context, attrs, defStyleAttr, defStyleRes); // modified
    } // modified

    public SelfDrawnListPreference(Context context, AttributeSet attrs,
                                   int defStyleAttr) { // modified
        super(context, attrs, defStyleAttr); // modified
    } // modified

    public SelfDrawnListPreference(Context context, AttributeSet attrs) { // modified
        super(context, attrs); // modified
    } // modified

    public SelfDrawnListPreference(Context context) { // modified
        super(context); // modified
    } // modified

    /**
     * 故意留空：不调用 super（super 会提交框架的 ListPreferenceDialogFragmentCompat）。
     *
     * <p>弹窗由 {@code SettingsActivity.AppPreferenceFragment#showChoiceDialog} 自绘，
     * 由 ON_PREFERENCE_CLICK 监听器触发。
     */
    @Override
    protected void onClick() { // modified: v4.5.0 拦截框架弹窗
        // 空实现是有意为之，见类注释
    } // modified
}
