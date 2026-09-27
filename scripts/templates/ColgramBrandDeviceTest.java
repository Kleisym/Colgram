package org.colgram.core;

import android.content.Context;
import android.util.Log;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.InputStream;
import java.util.Scanner;

/** Reads the branding straight out of the installed APK's compiled resources. */
@RunWith(AndroidJUnit4.class)
public final class ColgramBrandDeviceTest {

    @Test
    public void theInstalledAppNamesItselfColgram() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        String[] names = {"AppName", "AppNameBeta", "Page1Title", "Page2Message",
                "Page3Message", "Page4Message", "Page5Message", "Page6Message",
                "SentAppCode"};
        for (String name : names) {
            int id = context.getResources().getIdentifier(name, "string", context.getPackageName());
            if (id == 0) {
                Log.i("ColgramBrand", name + " -> NOT IN THE INSTALLED APK");
                continue;
            }
            String value = context.getString(id);
            boolean saysColgram = value.contains("Colgram");
            boolean saysTelegram = value.contains("Telegram");
            Log.i("ColgramBrand", name + " -> colgram=" + saysColgram + " telegram=" + saysTelegram);
        }
    }
}
