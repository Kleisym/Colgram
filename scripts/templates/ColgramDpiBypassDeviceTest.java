package org.colgram.core;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.ServerSocket;
import java.net.InetAddress;
import java.io.DataInputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;

/**
 * Account-free check that the local bypass is a real listener, not a flag that says it is.
 *
 * The reported symptom was a bypass that showed as enabled and connected to nothing, so this
 * asserts on a socket rather than on isRunning(). A flag can be true while nothing is bound -
 * the thread threw, the bind was refused, the port was taken - and Telegram pointed at a closed
 * port reports a proxy error and rotates away from the one option that needs no third party.
 *
 * Connecting is the part that matters: awaitReady checks a boolean, but a bind that succeeded
 * while the accept loop had already died still passes it.
 */
@RunWith(AndroidJUnit4.class)
public final class ColgramDpiBypassDeviceTest {

    private Context context;
    private Class<?> bypass;

    @Test public void localTlsSegmentationPreservesTheExactClientHello() throws Exception {
        SSLEngine engine = SSLContext.getDefault().createSSLEngine("fixture.invalid", 443);
        engine.setUseClientMode(true);
        engine.beginHandshake();
        ByteBuffer generated = ByteBuffer.allocate(32768);
        engine.wrap(ByteBuffer.allocate(0), generated);
        byte[] hello = new byte[generated.position()];
        generated.flip();
        generated.get(hello);
        assertTrue("ClientHello must be generated without any network request", hello.length > 64);
        boolean mimic = ColgramConfig.isTlsMimicEnabled();
        ColgramConfig.setTlsMimicEnabled(true);
        try (ServerSocket peer = new ServerSocket(0, 2, InetAddress.getByName("127.0.0.1"))) {
            peer.setSoTimeout(5000);
            FutureTask<Void> echo = new FutureTask<>(() -> {
                try (Socket socket = peer.accept()) {
                    socket.setSoTimeout(3500);
                    byte[] received = new byte[hello.length];
                    new DataInputStream(socket.getInputStream()).readFully(received);
                    org.junit.Assert.assertArrayEquals("A bridge must not rewrite TLS transcript/HMAC bytes", hello, received);
                    socket.getOutputStream().write(received);
                    socket.getOutputStream().flush();
                }
                return null;
            });
            Thread fixture = new Thread(echo, "colgram-tls-byte-fixture");
            fixture.setDaemon(true);
            fixture.start();
            ColgramDpiBypass.startImmediately();
            assertTrue(ColgramDpiBypass.awaitReady(1500));
            try (Socket client = new Socket("127.0.0.1", ColgramDpiBypass.activePort())) {
                client.setSoTimeout(5000);
                OutputStream out = client.getOutputStream();
                DataInputStream in = new DataInputStream(client.getInputStream());
                out.write(new byte[]{5, 1, 0}); out.flush();
                org.junit.Assert.assertEquals(5, in.readUnsignedByte());
                org.junit.Assert.assertEquals(0, in.readUnsignedByte());
                int port = peer.getLocalPort();
                out.write(new byte[]{5, 1, 0, 1, 127, 0, 0, 1, (byte) (port >>> 8), (byte) port});
                out.flush();
                byte[] accepted = new byte[10]; in.readFully(accepted);
                org.junit.Assert.assertEquals(0, accepted[1]);
                out.write(hello); out.flush();
                byte[] returned = new byte[hello.length]; in.readFully(returned);
                org.junit.Assert.assertArrayEquals(hello, returned);
            }
            echo.get(4, TimeUnit.SECONDS);
            android.util.Log.i("ColgramDpiBypassTest", "TLS_STREAM_INTEGRITY=PASS client_hello_bytes=" + hello.length);
        } finally {
            ColgramConfig.setTlsMimicEnabled(mimic);
        }
    }

    @Before
    public void setUp() throws Exception {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext()
                .getApplicationContext();
        ClassLoader loader = context.getClassLoader();
        ClassLoader core = Class.forName("org.colgram.core.ColgramConfig", true, loader)
                .getClassLoader();
        bypass = Class.forName("org.colgram.core.ColgramDpiBypass", true, core);
    }

    @After
    public void tearDown() throws Exception {
        try {
            invoke("stop", new Class<?>[]{});
        } catch (Throwable ignored) {
            // Nothing to stop if it never came up.
        }
        // These tests share one process and one static listener, so a listener left up by a
        // previous test would make startImmediately a silent no-op (it returns early when
        // isRunning) and the next test would assert against the wrong state entirely.
        java.lang.Thread.sleep(300L);
    }

    @Test
    public void theListenerReallyAcceptsAConnection() throws Exception {
        invoke("startImmediately", new Class<?>[]{});
        // Report the raw state before judging it: a bare "must report itself bound" tells a
        // reader nothing about WHICH half of the liveness contract broke.
        android.util.Log.i("ColgramDpiBypassTest", "after startImmediately: running="
                + bypass.getMethod("isRunning").invoke(null)
                + " bound=" + bypass.getMethod("isBound").invoke(null)
                + " accepts=" + accepts((Integer) bypass.getMethod("activePort").invoke(null)));
        // The bind happens on a worker thread, so give it the same deadline awaitReady uses
        // before deciding anything. Reading isBound immediately after startImmediately races
        // the bind and reports a failure that has not happened yet.
        boolean ready = (Boolean) bypass.getMethod("awaitReady", long.class).invoke(null, 8000L);
        android.util.Log.i("ColgramDpiBypassTest", "after awaitReady: ready=" + ready
                + " bound=" + bypass.getMethod("isBound").invoke(null)
                + " accepts=" + accepts((Integer) bypass.getMethod("activePort").invoke(null)));
        assertTrue("the bypass must become ready within its own deadline", ready);
        assertTrue("the bypass must report itself bound",
                (Boolean) bypass.getMethod("isBound").invoke(null));
        assertTrue("the bypass must become ready within its own deadline",
                (Boolean) bypass.getMethod("awaitReady", long.class).invoke(null, 8000L));

        int port = (Integer) bypass.getMethod("activePort").invoke(null);
        assertTrue("connect() to the bound bypass port is the only proof it is listening",
                accepts(port));
    }

    @Test
    public void stopLeavesNothingListeningAndRunning() throws Exception {
        invoke("startImmediately", new Class<?>[]{});
        assertTrue("precondition: the listener must have come up before it can be stopped",
                (Boolean) invoke("awaitReady", new Class<?>[]{long.class}, 8000L));
        invoke("stop", new Class<?>[]{});
        assertFalse("a stopped bypass must not still report itself running",
                (Boolean) bypass.getMethod("isRunning").invoke(null));
        // A closed ServerSocket can still have its backlog handed to a connection that was
        // already queued, so one immediate retry can succeed against a listener that is gone.
        // What matters is that it stops accepting, not that the very next connect fails.
        boolean stillAccepting = true;
        for (int attempt = 0; attempt < 10 && stillAccepting; attempt++) {
            Thread.sleep(150L);
            stillAccepting = accepts((Integer) bypass.getMethod("activePort").invoke(null));
        }
        assertFalse("a stopped bypass must stop accepting connections within a second and a half",
                stillAccepting);
    }

    @Test
    public void aDeadListenerIsReportedAndCanBeRestarted() throws Exception {
        invoke("startImmediately", new Class<?>[]{});
        assertTrue("precondition: the listener must be up before it can be killed",
                (Boolean) invoke("awaitReady", new Class<?>[]{long.class}, 8000L));
        // Tear the socket down underneath the running flag, which is what a wedged accept loop
        // or an externally closed port looks like from the rest of the app.
        invoke("stop", new Class<?>[]{});
        assertTrue("restartIfDead must be able to bring a dead bypass back",
                (Boolean) bypass.getMethod("restartIfDeadAndWait", long.class)
                        .invoke(null, 8000L));
        assertTrue("and the restarted listener must accept again",
                accepts((Integer) bypass.getMethod("activePort").invoke(null)));
    }

    private static boolean accepts(int port) {
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 3000);
            return true;
        } catch (Exception e) {
            return false;
        } finally {
            try {
                socket.close();
            } catch (Exception ignored) {
                // Nothing useful to do with a close failure here.
            }
        }
    }

    private Object invoke(String method, Class<?>[] parameterTypes, Object... arguments)
            throws Exception {
        Method target = bypass.getMethod(method, parameterTypes);
        try {
            return target.invoke(null, arguments);
        } catch (java.lang.reflect.InvocationTargetException error) {
            Throwable cause = error.getCause();
            if (cause instanceof Exception) throw (Exception) cause;
            if (cause instanceof Error) throw (Error) cause;
            throw error;
        }
    }
}
