package com.saiga.find.messagefinder;

// [新增文件 / modified] targetSdk 35(Android 15)+ 强制 edge-to-edge，
// Android 16(targetSdk 36) 已彻底移除 windowOptOutEdgeToEdgeEnforcement 这一临时退出开关。
// 不做适配的话，状态栏/导航栏会盖住标题与底部按钮，尤其在挖孔屏与手势导航下更明显。
//
// 做法参考 Android 官方《Edge-to-edge / Handle insets》：
//   setDecorFitsSystemWindows(false) + OnApplyWindowInsetsListener 给根布局补 padding。
// 只借用官方推荐的通用写法，未复制第三方代码。

import android.app.Activity;
import android.util.Log;
import android.view.View;

import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat; // modified

public final class EdgeToEdgeHelper {

    private static final String TAG = "EdgeToEdgeHelper";

    private EdgeToEdgeHelper() {
        // 工具类不允许实例化
    }

    /**
     * 让内容避开系统栏与屏幕挖孔。
     *
     * @param activity   目标页面
     * @param rootViewId 根布局 id（通常是 setContentView 传入布局的最外层 View）
     */
    public static void apply(Activity activity, int rootViewId) { // modified: 新增
        if (activity == null) {
            return;
        }
        try {
            WindowCompat.setDecorFitsSystemWindows(activity.getWindow(), false);
            // modified: 双主题下同步状态栏/导航栏图标明暗，避免深底上深色图标看不见
            boolean lightBars = activity.getResources().getBoolean(R.bool.light_system_bars); // modified
            WindowInsetsControllerCompat controller = WindowCompat.getInsetsController( // modified
                    activity.getWindow(), activity.getWindow().getDecorView()); // modified
            if (controller != null) { // modified
                controller.setAppearanceLightStatusBars(lightBars); // modified
                controller.setAppearanceLightNavigationBars(lightBars); // modified
            } // modified
            View root = activity.findViewById(rootViewId);
            if (root == null) {
                return;
            }
            // 保留布局里原有的 padding，只把系统栏 insets 叠加上去
            final int left = root.getPaddingLeft();
            final int top = root.getPaddingTop();
            final int right = root.getPaddingRight();
            final int bottom = root.getPaddingBottom();

            ViewCompat.setOnApplyWindowInsetsListener(root, (view, insets) -> {
                Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars()
                        | WindowInsetsCompat.Type.displayCutout());
                view.setPadding(left + bars.left, top + bars.top,
                        right + bars.right, bottom + bars.bottom);
                return insets;
            });
            ViewCompat.requestApplyInsets(root);
        } catch (Exception e) {
            // 个别 ROM 的 WindowCompat 实现可能抛异常，不能因为视觉适配把页面搞崩
            Log.w(TAG, "apply edge-to-edge insets failed", e);
        }
    }
}
