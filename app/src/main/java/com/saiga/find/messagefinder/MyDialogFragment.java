package com.saiga.find.messagefinder;

import android.app.Activity;
import android.app.Dialog;
import android.content.DialogInterface;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
// modified: 【独立复审修正】原 import 是 android.app.AlertDialog（平台实现），
// 却传入 R.style.MyDialogTheme（parent = Theme.AppCompat.*）。平台 AlertDialog 在
// AppCompat 主题下样式不生效，个别 ROM 还会因 LayoutInflater Factory 冲突抛
// IllegalStateException。与 MainActivity 统一改用 androidx 版本。
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.DialogFragment;

/**
 * 自动联想功能的授权说明框。
 *
 * <p>修改要点：原实现对宿主 Activity 做硬类型转换
 * {@code (MainActivity) requireActivity()}，当 Activity 已销毁/重建（旋转屏幕、进程回收后恢复）
 * 时会抛 ClassCastException 或 IllegalStateException。这里统一改为
 * {@code instanceof} 判断 + 安全取用，拿不到宿主就直接关闭对话框。
 */
public class MyDialogFragment extends DialogFragment {

    private static final String TAG = "AutoSuggestionDialog";

    public MyDialogFragment() {
        // 空构造方法：Fragment 重建时系统需要
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        AlertDialog.Builder builder = new AlertDialog.Builder(requireActivity(), R.style.MyDialogTheme);
        LayoutInflater inflater = requireActivity().getLayoutInflater();
        builder.setView(inflater.inflate(R.layout.dialog_layout, null));
        builder.setPositiveButton(R.string.dialog_set, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                MainActivity activity = getMainActivity();
                if (activity != null) {
                    activity.requestContactPermission();
                }
            }
        });
        builder.setNegativeButton(R.string.dialog_dismiss, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                // 用户选择“关闭”：后续改用系统通讯录选择器，不再弹本对话框
                MainActivity activity = getMainActivity();
                if (activity != null) {
                    activity.setPopup(true);
                }
            }
        });
        builder.setNeutralButton(R.string.dialog_dont_show, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                // 用户选择“不再显示”：持久化关闭自动联想
                MainActivity activity = getMainActivity();
                if (activity != null) {
                    activity.setUserConfig(true);
                }
            }
        });
        return builder.create();
    }

    @Override
    public void onCancel(@NonNull DialogInterface dialog) {
        super.onCancel(dialog);
        MainActivity activity = getMainActivity();
        if (activity != null) {
            activity.setPopup(false);
        }
    }

    /**
     * 安全获取宿主 Activity。
     *
     * @return 宿主是 MainActivity 时返回它，否则返回 null（不抛异常）
     */
    @Nullable
    private MainActivity getMainActivity() { // modified: 用 instanceof 取代硬类型转换
        Activity activity = getActivity();
        if (activity instanceof MainActivity) {
            return (MainActivity) activity;
        }
        Log.w(TAG, "host activity is not MainActivity, ignore callback");
        return null;
    }
}
