package org.colgram.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.util.Log;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.DataInputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

@RunWith(AndroidJUnit4.class)
public final class ColgramGuestProxyDeviceTest {
    private static final String TAG = "ColgramGuestProbe";


    @Test public void anonymousTgnetNegotiatesSocksWithoutChangingMainRoute() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext().getApplicationContext();
        Map<String, Object> before = savedMainRoute(context);
        try (ServerSocket peer = new ServerSocket(0, 2, InetAddress.getByName("127.0.0.1"))) {
            peer.setSoTimeout(7_500);
            AtomicReference<Throwable> serverError = new AtomicReference<>();
            AtomicReference<InetAddress> destination = new AtomicReference<>();
            AtomicLong port = new AtomicLong();
            CountDownLatch connectRead = new CountDownLatch(1);
            Thread server = new Thread(() -> {
                try (Socket socket = peer.accept()) {
                    socket.setSoTimeout(2_000);
                    DataInputStream in = new DataInputStream(socket.getInputStream());
                    OutputStream out = socket.getOutputStream();
                    assertEquals(5, in.readUnsignedByte());
                    int count = in.readUnsignedByte();
                    boolean noAuth = false;
                    for (int i = 0; i < count; i++) noAuth |= in.readUnsignedByte() == 0;
                    assertTrue(noAuth);
                    out.write(new byte[] {5, 0});
                    out.flush();
                    assertEquals(5, in.readUnsignedByte());
                    assertEquals(1, in.readUnsignedByte()); // CONNECT
                    assertEquals(0, in.readUnsignedByte());
                    int type = in.readUnsignedByte();
                    assertTrue("tgnet should request a configured DC IP", type == 1 || type == 4);
                    byte[] address = new byte[type == 1 ? 4 : 16];
                    in.readFully(address);
                    destination.set(InetAddress.getByAddress(address));
                    port.set(in.readUnsignedShort());
                    out.write(new byte[] {5, 5, 0, 1, 0, 0, 0, 0, 0, 0}); // intentionally refused
                    out.flush();
                } catch (Throwable failure) {
                    serverError.set(failure);
                } finally {
                    connectRead.countDown();
                }
            }, "guest-probe-fixture");
            server.setDaemon(true);
            server.start();

            long start = SystemClock.elapsedRealtime();
            ProbeResult result = runProbe(context, peer.getLocalPort(), false);
            assertTrue(connectRead.await(2, TimeUnit.SECONDS));
            Throwable error = serverError.get();
            if (error != null) throw new AssertionError("No SOCKS CONNECT from the guest engine: " + result.reason, error);
            assertNotNull(destination.get());
            assertTrue(ColgramProxyPolicy.isPublicAddress(destination.get()));
            assertTrue(port.get() > 0);
            assertEquals(-1L, result.rtt);
            assertEquals(before, savedMainRoute(context));
            Log.i(TAG, "GUEST_SOCKS_CONNECT=YES; fake peer refused; verdict=" + result.reason
                    + "; elapsed_ms=" + (SystemClock.elapsedRealtime() - start) + "; main_route=UNCHANGED");
        }
    }

    @Test public void catalogProbeRejectsLoopbackBeforeDialing() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext().getApplicationContext();
        try (ServerSocket peer = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            ProbeResult result = runProbe(context, peer.getLocalPort(), true);
            assertEquals("NON_PUBLIC_PROXY", result.reason);
            assertEquals(-1L, result.rtt);
        }
    }

    @Test public void threeAnonymousNativeChecksRunTogetherWithoutTouchingTheMainRoute() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext().getApplicationContext();
        boolean catalog = ColgramConfig.isBuiltinProxyEnabled();
        ColgramConfig.setBuiltinProxyEnabled(false);
        Thread.sleep(11_000L);
        Map<String, Object> before = savedMainRoute(context);
        int lanes = ColgramGuestProxyProbe.MAX_PARALLEL;
        ServerSocket[] peers = new ServerSocket[lanes];
        CountDownLatch accepted = new CountDownLatch(lanes), finished = new CountDownLatch(lanes), release = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        long started = SystemClock.elapsedRealtime();
        try {
            for (int index = 0; index < lanes; index++) {
                ServerSocket peer = new ServerSocket(0, 2, InetAddress.getByName("127.0.0.1"));
                peer.setSoTimeout(8_000); peers[index] = peer;
                Thread server = new Thread(() -> {
                    try (Socket socket = peer.accept()) {
                        socket.setSoTimeout(5_000);
                        DataInputStream input = new DataInputStream(socket.getInputStream());
                        OutputStream output = socket.getOutputStream();
                        assertEquals(5, input.readUnsignedByte());
                        int methods = input.readUnsignedByte();
                        for (int item = 0; item < methods; item++) input.readUnsignedByte();
                        output.write(new byte[]{5, 0}); output.flush();
                        assertEquals(5, input.readUnsignedByte()); assertEquals(1, input.readUnsignedByte()); input.readUnsignedByte();
                        int type = input.readUnsignedByte();
                        assertTrue(type == 1 || type == 4);
                        byte[] address = new byte[type == 1 ? 4 : 16]; input.readFully(address); input.readUnsignedShort();
                        output.write(new byte[]{5, 0, 0, 1, 127, 0, 0, 1, 0, 0}); output.flush();
                        assertTrue("Each lane must emit real native MTProto bytes", input.read() >= 0);
                        accepted.countDown(); release.await(12, TimeUnit.SECONDS);
                    } catch (Throwable error) { failure.compareAndSet(null, error); accepted.countDown(); }
                }, "parallel-guest-negative-control-" + index);
                server.setDaemon(true); server.start();
                assertTrue("The bounded broker rejected a free parallel slot", ColgramGuestProxyProbe.check(context,
                        "127.0.0.1", peer.getLocalPort(), "", "", "", false, (rtt, reason) -> {
                            if (rtt >= 0 || "TG_CONFIG_OK".equals(reason)) failure.compareAndSet(null,
                                    new AssertionError("A silent fixture was marked Telegram-healthy"));
                            finished.countDown();
                        }));
            }
            assertTrue("Native lanes were serialized instead of starting together", accepted.await(6, TimeUnit.SECONDS));
            if (failure.get() != null) throw new AssertionError("Parallel fixture failed", failure.get());
            assertTrue("Parallel checks exceeded one shared timeout window", finished.await(7, TimeUnit.SECONDS));
            assertTrue("Parallel checks took serial-timeout duration", SystemClock.elapsedRealtime() - started < 13_000L);
            if (failure.get() != null) throw new AssertionError("Parallel verdict failed", failure.get());
            assertEquals(before, savedMainRoute(context));
            Log.i(TAG, "GUEST_PARALLEL=PASS lanes=" + lanes + " native_requests=true false_positive=false main_route=UNCHANGED elapsed_ms="
                    + (SystemClock.elapsedRealtime() - started));
        } finally {
            release.countDown();
            for (ServerSocket peer : peers) if (peer != null) peer.close();
            ColgramConfig.setBuiltinProxyEnabled(catalog);
            context.getSharedPreferences("colgram_secure_config", Context.MODE_PRIVATE).edit().putBoolean("builtin_proxy_enabled", catalog).commit();
        }
    }

    private static ProbeResult runProbe(Context context, int port, boolean publicOnly) throws Exception {
        AtomicReference<ProbeResult> answer = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        assertTrue(ColgramGuestProxyProbe.check(context, "127.0.0.1", port, "", "", "", publicOnly,
                (rtt, reason) -> { answer.set(new ProbeResult(rtt, reason)); done.countDown(); }));
        assertTrue("guest probe exceeded its parent deadline", done.await(11_000L, TimeUnit.MILLISECONDS));
        return answer.get();
    }

    private static Map<String, Object> savedMainRoute(Context context) {
        SharedPreferences prefs = context.getSharedPreferences("mainconfig", Context.MODE_PRIVATE);
        Map<String, Object> route = new HashMap<>();
        for (Map.Entry<String, ?> entry : prefs.getAll().entrySet()) {
            if (entry.getKey().startsWith("proxy_")) route.put(entry.getKey(), entry.getValue());
        }
        return route;
    }

    private static final class ProbeResult {
        final long rtt;
        final String reason;
        ProbeResult(long rtt, String reason) { this.rtt = rtt; this.reason = reason; }
    }
}
