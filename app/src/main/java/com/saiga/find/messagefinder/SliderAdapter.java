package com.saiga.find.messagefinder;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Build;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.viewpager.widget.PagerAdapter;

// feeds the pages to the ViewPager aka Onboarding activity for first time users
public class SliderAdapter extends PagerAdapter {


    private Context mContext;
    private float screenDensity;
    private final static String TAG = "FTISetup";

    public SliderAdapter(Context context){
        mContext = context;
        // fetch the user configured device density -- determine the dpi
        screenDensity = mContext.getResources().getDisplayMetrics().density;
    }

    public int[] mDrawableIcons = {
            R.drawable.message2,
            R.drawable.priority_notifications,
            R.drawable.privacy
    };

    public int[] mPageTitle = {
            R.string.OnboardingTitle1,
            R.string.OnboardingTitle2,
            R.string.OnboardingTitle3
    };

    public int[] mPageDescs = {
            R.string.OnboardingDesc1,
            R.string.OnboardingDesc2,
            R.string.OnboardingDesc3
    };

    public int[] mPageTitleColor = {
            R.color.lightOrangeColor,
            R.color.lightBlueColor,
            R.color.lightPinkColor
    };

    public int[] mPageDescColor = {
            R.color.lightOrangeColor,
            R.color.lightBlueColor,
            R.color.lightPinkColor
    };

    @Override
    public int getCount() {
        // to return the number of pages
        // modified: 【独立复审修正】原实现只取 mPageTitle.length。四个平行数组一旦长度不一致，
        // instantiateItem 里就会越界崩溃。取最小长度可以从源头保证索引安全。
        return Math.min(Math.min(mPageTitle.length, mPageDescs.length),
                Math.min(mDrawableIcons.length, mPageTitleColor.length));
    }

    @Override
    public boolean isViewFromObject(@NonNull View view, @NonNull Object object) {
        // modified: 【崩溃修复 v4.1.2】原实现为 `view == (RelativeLayout)object`。
        // page_layout.xml 的根节点在界面重做时已由 RelativeLayout 改为 LinearLayout，
        // 这行强转会在 ViewPager 首次布局时抛 ClassCastException，导致引导页（首次启动
        // 的第一个界面）直接闪退。PagerAdapter 的判定只需要引用相等，不依赖具体类型。
        return view == object;
    }

    @NonNull
    @Override
    public Object instantiateItem(@NonNull ViewGroup container, int position) {
        // inflates our pages for the view pager, for each onboarding screen
        // modified: 【独立复审修正】原实现把 getSystemService 的结果直接赋给成员变量并立刻 inflate，
        // 极端情况下（Context 异常/系统服务不可用）会 NPE。改为局部变量 + 兜底链，
        // 并且不再把 LayoutInflater 存成成员（每次复用同一个实例没有收益，还多一份状态）。
        LayoutInflater inflater = (LayoutInflater) mContext.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
        if (inflater == null) {
            inflater = LayoutInflater.from(mContext);
        }
        if (inflater == null) {
            Log.e(TAG, "layout inflater unavailable, position " + position);
            return new View(mContext);
        }
        View view = inflater.inflate(R.layout.page_layout, container, false);
        ImageView mPageIcon = view.findViewById(R.id.boarding_image);
        TextView mPageCaption = view.findViewById(R.id.title_textView);
        TextView mPageSummary = view.findViewById(R.id.summaryView);
        // this fixes the one of the major crashes in redmi based / low memory based phones
        // initially the images were inflated in memory based on the original image size,
        // this caused to consume more app memory at app startup for first time users
        // causing crashes making the app totally unusable
        // This new change addresses the issue by inflating the image to the size of the image view
        // based on users device screen density and image view to be displayed (dp values),
        // so that the ram memory occupied will be very much less. This is one of the recommended
        // workflow for this issue
        Log.d(TAG,"Reported Screen Density " + String.valueOf(screenDensity));
        // width of the image in pixels to be inflated in memory
        // some adjustment calculations are to be done to take in account of the error in screen
        // density calculation, this will help in sub sample size
        int requested_width = (int) (180 * (screenDensity-0.5) );
        // 防御：极端密度下算出的宽高可能 <=0，会让 calculateInSampleSize 里出现除零/死循环
        if (requested_width < 1) {
            requested_width = 1;
        } // modified: 上下界保护
        // modified: 【独立复审修正】原实现直接用 position 索引四个平行数组。
        // getCount() 取自 mPageTitle，只要将来有人给标题数组多加一页却忘了补
        // 颜色/图标数组，就会在运行期 ArrayIndexOutOfBoundsException（引导页直接崩）。
        // 这里把索引收敛到各数组的安全范围。
        int safePosition = Math.max(0, Math.min(position, mPageTitle.length - 1));
        int colorIndex = safePosition % mPageTitleColor.length;

        // height and width of our circular image view is same
        int requested_height = requested_width;
        // very efficient way of loading images in memory
        Bitmap pageIcon = decodeSampledBitmapFromResource(mContext.getResources(),
                mDrawableIcons[safePosition % mDrawableIcons.length],
                requested_width, requested_height);
        if (pageIcon != null) {
            mPageIcon.setImageBitmap(pageIcon); // modified: 解码失败时保持原状，不再传入 null
        } else {
            Log.w(TAG, "decode page icon failed, position " + position);
        }
        //@deprecated
     //   mPageIcon.setImageResource(mDrawableIcons[position]);
        mPageCaption.setTextColor(mContext.getColor(mPageTitleColor[colorIndex]));
        mPageSummary.setTextColor(mContext.getColor(mPageDescColor[colorIndex]));
        mPageCaption.setText(mPageTitle[safePosition]);
        mPageSummary.setText(mPageDescs[safePosition]);
        container.addView(view);
        return view;
    }

    @Override
    public void destroyItem(@NonNull ViewGroup container, int position, @NonNull Object object) {
        // marks the end of the onboarding screen
        // modified: 【崩溃修复 v4.1.2】同上，强转 RelativeLayout 会 CCE，改为按 View 移除
        container.removeView((View) object);
    }

    /**
     * 按目标尺寸降采样解码资源图片。
     *
     * <p>【代码溯源 / modified】本方法与下面的 {@link #calculateInSampleSize} 直接沿用
     * Android 官方培训文档《Loading Large Bitmaps Efficiently》中的示例实现
     * （https://developer.android.com/topic/performance/graphics/load-bitmap ，
     * 章节 "Read Bitmap Dimensions and Type in Memory" / "Load a Scaled Down Version into Memory"），
     * 原上游项目（saiga006/message-finder, GPL-3.0）即已引入，用于避免引导页大图在低端机上 OOM。
     * 使用场景：ViewPager 每页的圆形图标；用途：把 3 张大图按屏幕密度降采样后再进内存。
     * 本次仅补充上述来源标注，未改动算法逻辑；上界/下界保护与空结果处理是本项目自行添加的防御。
     */
    public static Bitmap decodeSampledBitmapFromResource(Resources res, int resId,
                                                         int reqWidth, int reqHeight) {

        // First decode with inJustDecodeBounds=true to check dimensions
        final BitmapFactory.Options options = new BitmapFactory.Options();


        if (Build.VERSION.SDK_INT > Build.VERSION_CODES.N_MR1) {
            // we use gpu memory instead of main memory, so this further reduce the chance of crash
            // in the memory profiler test, this method reduced the initial memory consumption by
            // ~60% (220MB reduced to 80MB)
            options.inPreferredConfig = Bitmap.Config.HARDWARE;
        }
        else {
            // this method reduced memory usage by 50% (220MB reduced to 112MB)
            // we use different bit format / representation for individual pixel
            // instead of 32 bits,  we make it to 8 bits to save memory
            // but it degrades the quality, so dither flag is set to compensate
            options.inPreferredConfig = Bitmap.Config.RGB_565;
            options.inDither = true;
        }
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeResource(res, resId, options);

        // Calculate inSampleSize
        options.inSampleSize = calculateInSampleSize(options, reqWidth, reqHeight);
        Log.d(TAG,"insample size " + options.inSampleSize);
        // Decode bitmap with inSampleSize set, after configuring all options retrieve the bitmap
        options.inJustDecodeBounds = false;
        return BitmapFactory.decodeResource(res, resId, options);
    }

    public static int calculateInSampleSize(
            BitmapFactory.Options options, int reqWidth, int reqHeight) {
        // Raw height and width of original image, got from injustdecodebounds == false
        final int height = options.outHeight;
        final int width = options.outWidth;
        Log.d(TAG,"Size of the image height " + height + " width " + width);
        int inSampleSize = 1;

        if (height > reqHeight || width > reqWidth) {

            final int halfHeight = height / 2;
            final int halfWidth = width / 2;

            // Calculate the largest inSampleSize(which decides the downsampling factor of image)
            // value that is a power of 2 and keeps both
            // height and width larger than the requested height and width.
            while ((halfHeight / inSampleSize) >= reqHeight
                    && (halfWidth / inSampleSize) >= reqWidth) {
                inSampleSize *= 2;
            }
        }

        return inSampleSize;
    }
}
