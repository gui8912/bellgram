package com.saiga.find.messagefinder;

import android.content.Context;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import static org.junit.Assert.assertEquals;

/**
 * 仪器化测试（需连接设备/模拟器）。
 *
 * @see <a href="http://d.android.com/tools/testing">Testing documentation</a>
 */
@RunWith(AndroidJUnit4.class)
public class ExampleInstrumentedTest {
    @Test
    public void useAppContext() {
        // modified: 改用 getInstrumentation().getTargetContext()，getTargetContext() 静态方法已废弃
        Context appContext = InstrumentationRegistry.getInstrumentation().getTargetContext();
        assertEquals("com.saiga.find.messagefinder", appContext.getPackageName());
    }
}
