package org.colgram.core;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.net.VpnService;
import android.os.SystemClock;
import android.util.Log;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.colgram.singbox.ColgramVpnService;
import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.DataInputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

/** On-device VLESS peer for one synthetic request; the endpoint never forwards to the Internet. */
@RunWith(AndroidJUnit4.class)
public final class ColgramSubscriptionRouteDeviceTest {
    private static final byte[] PING = "colgram-vless-fixture".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] PONG = "only-vless-peer-answers".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] TEST_IP = {(byte) 192, 0, 2, 23}; // RFC 5737: not a real Internet service

    @Test public void chosenVlessProfileCarriesTrafficWithoutDirectFallback() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext().getApplicationContext();
        Assume.assumeTrue("Explicitly grant Android VPN permission on an idle disposable emulator first", VpnService.prepare(context) == null);
        Assume.assumeFalse("Keep an existing subscription untouched", ColgramSubscriptionStore.hasSubscription(context));
        Assume.assumeFalse("No other VPN may be displaced", activeVpn(context));
        Assume.assumeFalse("No active Telegram proxy may be displaced", ColgramProxyManager.hasActiveRouteForUi());
        final String profile = ColgramSubscriptionStore.profileLocation(context);
        try (ServerSocket peer = new ServerSocket(0, 2, InetAddress.getByName("127.0.0.1"))) {
            peer.setSoTimeout(12_000);
            FutureTask<Void> exchange = new FutureTask<>(() -> { accept(peer); return null; });
            Thread fixture = new Thread(exchange, "colgram-vless-fixture");
            fixture.setDaemon(true);
            fixture.start();

            String first = key("22222222-2222-4222-8222-222222222222", peer.getLocalPort(), "Fixture-one");
            String second = key("11111111-1111-4111-8111-111111111111", peer.getLocalPort(), "Fixture-two");
            assertEquals(2, ColgramSubscriptionStore.save(context, first + "\n" + second).total);
            assertEquals("Fixture-two", ColgramSubscriptionStore.select(context, 1).name);
            assertEquals("stopped", ColgramVpnService.status(profile));
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> ColgramVpnService.start(context, profile));
            long deadline = SystemClock.elapsedRealtime() + 9_000;
            while (SystemClock.elapsedRealtime() < deadline
                    && (!activeVpn(context) || !"running".equals(ColgramVpnService.status(profile)))) Thread.sleep(100);
            assertTrue("Android did not install the TUN", activeVpn(context));
            assertEquals("running", ColgramVpnService.status(profile));
            try (Socket test = new Socket(Proxy.NO_PROXY)) {
                test.connect(new InetSocketAddress(InetAddress.getByAddress(TEST_IP), 41337), 4_000);
                test.setSoTimeout(4_000);
                test.getOutputStream().write(PING);
                test.getOutputStream().flush();
                byte[] response = new byte[PONG.length];
                new DataInputStream(test.getInputStream()).readFully(response);
                assertArrayEquals("Only the selected test VLESS peer can provide this response", PONG, response);
            }
            exchange.get(3, TimeUnit.SECONDS);
            Log.i("ColgramSubRoute", "VLESS_TUNNEL=PASS nodes=2 selected=2 request_and_response=true external_forwarding=false");
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> ColgramVpnService.stopProfile(context, profile));
            long end = SystemClock.elapsedRealtime() + 5_000;
            while (SystemClock.elapsedRealtime() < end && activeVpn(context)) Thread.sleep(100);
            ColgramSubscriptionStore.clear(context);
        }
        assertFalse("Fixture VPN is still routing", activeVpn(context));
        assertFalse("Fixture subscription survived cleanup", ColgramSubscriptionStore.hasSubscription(context));
        Log.i("ColgramSubRoute", "VLESS_RESTORE=PASS vpn=off fixture_removed=true");
    }

    private static String key(String fixtureId, int port, String label) {
        return "vless://" + fixtureId + "@127.0.0.1:" + port + "?type=tcp&security=none#" + label;
    }

    private static void accept(ServerSocket peer) throws Exception {
        try (Socket socket = peer.accept()) {
            socket.setSoTimeout(4_000);
            DataInputStream input = new DataInputStream(socket.getInputStream());
            OutputStream output = socket.getOutputStream();
            assertEquals(0, input.readUnsignedByte()); // VLESS version
            byte[] fixtureUuid = new byte[16];
            input.readFully(fixtureUuid);
            assertEquals(0x11, fixtureUuid[0] & 255);
            assertEquals(0x41, fixtureUuid[6] & 255);
            assertEquals(0x81, fixtureUuid[8] & 255);
            assertEquals(0, input.readUnsignedByte()); // no addon
            assertEquals(1, input.readUnsignedByte()); // TCP
            assertEquals(41337, input.readUnsignedShort());
            assertEquals(1, input.readUnsignedByte()); // IPv4
            byte[] address = new byte[4];
            input.readFully(address);
            assertArrayEquals(TEST_IP, address);
            byte[] body = new byte[PING.length];
            input.readFully(body);
            assertArrayEquals(PING, body);
            output.write(new byte[]{0, 0});
            output.write(PONG);
            output.flush();
        }
    }

    private static boolean activeVpn(Context context) {
        ConnectivityManager manager = context.getSystemService(ConnectivityManager.class);
        assertNotNull(manager);
        NetworkCapabilities caps = manager.getNetworkCapabilities(manager.getActiveNetwork());
        return caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN);
    }
}
