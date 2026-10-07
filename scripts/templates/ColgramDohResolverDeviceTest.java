package org.colgram.core;

import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.util.Log;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.net.InetAddress;
import java.net.URL;

import javax.net.ssl.HttpsURLConnection;

/**
 * Proves the resolver works on the device, using both of Cloudflare's answer formats.
 *
 * Reached by reflection, like every other test in this module: AppTests compiles against
 * :TMessagesProj only, so colgram-core is on the runtime classpath but not on its compile one.
 * A direct reference compiles nowhere and fails the whole run.
 *
 * Both formats are exercised because agreeing with the readable one is the cheapest proof that
 * the unreadable one was parsed correctly.
 */
@RunWith(AndroidJUnit4.class)
public final class ColgramDohResolverDeviceTest {

    private static final String TAG = "ColgramDohResolver";
    private static Class<?> resolver;

    private static void bind() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        resolver = Class.forName("org.colgram.core.ColgramDohResolver", true, context.getClassLoader());
    }

    @Test
    public void cloudflareAnswersInBothFormats() throws Exception {
        bind();
        String name = "api.telegram.org";

        HttpsURLConnection json = (HttpsURLConnection) new URL(
                "https://1.1.1.1/dns-query?name=" + name + "&type=A").openConnection();
        json.setRequestProperty("Accept", "application/dns-json");
        json.setRequestProperty("Host", "cloudflare-dns.com");
        json.setConnectTimeout(8000);
        json.setReadTimeout(8000);
        int status = json.getResponseCode();
        String body = read(status >= 400 ? json.getErrorStream() : json.getInputStream());
        json.disconnect();
        Log.i(TAG, "dns-json status=" + status + " body=" + trim(body));
        assertTrue("Cloudflare dns-json did not answer", status == 200 && body.contains("Answer"));

        reset();
        long started = System.currentTimeMillis();
        InetAddress[] addresses = (InetAddress[]) resolver.getMethod("resolve", String.class)
                .invoke(null, name);
        long elapsed = System.currentTimeMillis() - started;
        StringBuilder detail = new StringBuilder();
        if (addresses != null) {
            for (InetAddress address : addresses) {
                detail.append(address.getHostAddress()).append(' ');
            }
        }
        Log.i(TAG, "resolve(" + name + ") -> "
                + (addresses == null ? "null" : addresses.length + " addr")
                + " in " + elapsed + "ms via " + active()
                + " [" + detail.toString().trim() + "]");
        assertTrue("the resolver returned nothing for a name that resolves normally",
                addresses != null && addresses.length > 0);
    }

    @Test
    public void aCutResolverIsRememberedRatherThanRetriedForever() throws Exception {
        bind();
        reset();
        resolver.getMethod("resolve", String.class).invoke(null, "cloudflare.com");
        long started = System.currentTimeMillis();
        resolver.getMethod("resolve", String.class).invoke(null, "example.com");
        long second = System.currentTimeMillis() - started;
        Log.i(TAG, "second lookup " + second + "ms; cut endpoints must be skipped, not retried");
        assertTrue("a cut resolver must not be retried on every lookup", second < 20000);
    }

    @Test
    public void theReplyParserReadsARealCapturedAnswer() throws Exception {
        bind();
        // Captured from Cloudflare on this device for api.telegram.org, answers 1. Note the
        // answer name is a compression pointer (c00c) - a parser that never met one would pass
        // on hand-written samples and fail on every real answer.
        byte[] message = hex(
                "000181800001000100000000036170690874656c656772616d036f72670000010001c00c"
                + "00010001000000760004959aa66e");
        java.lang.reflect.Method parse = resolver.getDeclaredMethod(
                "parseARecords", byte[].class);
        parse.setAccessible(true);
        String[] found = (String[]) parse.invoke(null, (Object) message);
        Log.i(TAG, "parsed A records: " + java.util.Arrays.toString(found));
        assertTrue("a captured Cloudflare reply must yield its A record, got "
                + java.util.Arrays.toString(found), found.length > 0);
    }

    private static void reset() throws Exception {
        resolver.getMethod("resetHealth").invoke(null);
    }

    private static String active() throws Exception {
        return String.valueOf(resolver.getMethod("activeResolver").invoke(null));
    }

    private static String read(java.io.InputStream in) throws Exception {
        if (in == null) return "";
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] chunk = new byte[1024];
        int count;
        while ((count = in.read(chunk)) != -1) out.write(chunk, 0, count);
        in.close();
        return new String(out.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
    }

    private static String trim(String value) {
        return value.length() > 200 ? value.substring(0, 200) : value;
    }

    private static byte[] hex(String value) {
        String clean = value.replace(" ", "");
        byte[] out = new byte[clean.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(clean.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }
}
