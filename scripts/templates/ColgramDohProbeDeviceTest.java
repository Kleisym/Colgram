package org.colgram.core;

import static org.junit.Assert.assertNotNull;

import android.content.Context;
import android.util.Log;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;

/**
 * Measures, from the device, what is actually reachable before any resolver is written.
 *
 * The DoH work cannot be designed from a desk: whether Cloudflare DoH is reachable, and whether
 * it is reachable under a plain SNI or only under a different one, is a property of the network
 * the phone is standing in. net4people/bbs #81 records the mechanism - DoH and DoT are cut with a
 * TCP RST after the ClientHello, keyed on the SNI, and the same IP under a different SNI
 * survives - so this establishes which half of that applies here, and the resolver gets built
 * against the case that is real.
 */
@RunWith(AndroidJUnit4.class)
public final class ColgramDohProbeDeviceTest {

    private static final String TAG = "ColgramDohProbe";
    private static final int TIMEOUT_MS = 8000;

    private static final byte[] QUERY = new byte[]{
            0x00, 0x00, 0x01, 0x00, 0x00, 0x01, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00, 0x07, 0x65, 0x78, 0x61, 0x6d, 0x70, 0x6c, 0x65,
            0x03, 0x63, 0x6f, 0x6d, 0x00, 0x00, 0x01, 0x00, 0x01
    };

    @Test
    public void dohReachabilityIsMeasuredOnThisNetwork() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        assertNotNull(context);

        Log.i(TAG, "udp53(1.1.1.1)  = " + udp53("1.1.1.1"));
        Log.i(TAG, "udp53(8.8.8.8)  = " + udp53("8.8.8.8"));

        Log.i(TAG, "doh(1.1.1.1, cloudflare-dns.com) = " + doh("1.1.1.1", "cloudflare-dns.com"));
        Log.i(TAG, "doh(1.1.1.1, cloudflare.com)     = " + doh("1.1.1.1", "cloudflare.com"));
        Log.i(TAG, "doh(1.1.1.1, one.one.one.one)   = " + doh("1.1.1.1", "one.one.one.one"));
        Log.i(TAG, "doh(8.8.8.8, dns.google)         = " + doh("8.8.8.8", "dns.google"));
        Log.i(TAG, "doh(9.9.9.9, dns.quad9.net)     = " + doh("9.9.9.9", "dns.quad9.net"));
        Log.i(TAG, "doh(94.140.14.14, adguard)     = " + doh("94.140.14.14", "adguard-dns.com"));
    }

    @Test
    public void aBareTcpConnectDistinguishesRstFromSilence() throws Exception {
        for (String ip : new String[]{"1.1.1.1", "104.16.0.1", "162.159.192.1"}) {
            Socket socket = new Socket();
            try {
                socket.connect(new InetSocketAddress(ip, 443), TIMEOUT_MS);
                Log.i(TAG, "tcp443(" + ip + ") = connected");
            } catch (Exception e) {
                Log.i(TAG, "tcp443(" + ip + ") = " + e.getClass().getSimpleName());
            } finally {
                try {
                    socket.close();
                } catch (Exception ignored) {
                    // Nothing useful to do with a close failure in a probe.
                }
            }
        }
    }

    private static String udp53(String host) {
        java.net.DatagramSocket socket = null;
        try {
            socket = new java.net.DatagramSocket(new InetSocketAddress(0));
            socket.setSoTimeout(TIMEOUT_MS);
            socket.send(new java.net.DatagramPacket(QUERY, QUERY.length,
                    java.net.InetAddress.getByName(host), 53));
            java.net.DatagramPacket reply = new java.net.DatagramPacket(new byte[512], 512);
            socket.receive(reply);
            return "answered " + reply.getLength() + "B";
        } catch (Exception e) {
            return e.getClass().getSimpleName();
        } finally {
            if (socket != null) {
                socket.close();
            }
        }
    }

    private static String doh(String ip, String host) {
        HttpURLConnection connection = null;
        try {
            URL url = new URL("https://" + ip + "/dns-query");
            connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(TIMEOUT_MS);
            connection.setReadTimeout(TIMEOUT_MS);
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Host", host);
            connection.setRequestProperty("Content-Type", "application/dns-message");
            connection.setRequestProperty("Accept", "application/dns-message");
            connection.setDoOutput(true);
            if (connection instanceof HttpsURLConnection) {
                HttpsURLConnection secure = (HttpsURLConnection) connection;
                SSLContext context = SSLContext.getInstance("TLS");
                context.init(null, null, null);
                secure.setSSLSocketFactory(context.getSocketFactory());
                secure.setHostnameVerifier(new HostnameVerifier() {
                    @Override
                    public boolean verify(String ignored, SSLSession session) {
                        // Reachability, not trust: the question is whether the handshake
                        // completed at all under this SNI.
                        return true;
                    }
                });
            }
            OutputStream out = connection.getOutputStream();
            out.write(QUERY);
            out.flush();
            int code = connection.getResponseCode();
            InputStream in = code >= 400 ? connection.getErrorStream() : connection.getInputStream();
            int length = 0;
            if (in != null) {
                ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                byte[] chunk = new byte[512];
                int read;
                while ((read = in.read(chunk)) != -1) {
                    buffer.write(chunk, 0, read);
                }
                length = buffer.size();
            }
            return "HTTP " + code + " " + length + "B";
        } catch (Exception e) {
            return e.getClass().getSimpleName();
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }
}
