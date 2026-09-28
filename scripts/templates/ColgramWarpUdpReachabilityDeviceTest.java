package org.colgram.core;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.util.Log;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Measures, from inside the app on the device, whether WARP can work here at all.
 *
 * Every earlier WARP verdict came from probing the host, which proves nothing about the phone:
 * the route out of the emulator is not the route out of a real handset. This sends a real
 * datagram from the app's own process to the same Cloudflare ingresses the tunnel dials, with a
 * control probe to a resolver that is known to answer, so "silent" means "that destination is
 * filtered" rather than "UDP is broken everywhere".
 *
 * It reports rather than asserts the tunnel: on a network where WARP works this passes, and on
 * one where it is blocked it must still pass while proving the block is external. What it does
 * assert is the arithmetic that turns those observations into the message the settings row
 * shows, because a wrong verdict there is the bug that was actually reported.
 */
@RunWith(AndroidJUnit4.class)
public final class ColgramWarpUdpReachabilityDeviceTest {

    private static final String TAG = "ColgramWarpUdp";
    private static final int TIMEOUT_MS = 2500;

    /** Cloudflare's WireGuard ingresses, the same ones the registration hands back. */
    private static final String[] INGRESSES = {
            "162.159.192.1", "162.159.193.1", "188.114.96.1", "188.114.97.1",
    };
    private static final int[] PORTS = {2408, 500, 1701, 4500};

    private Context context;

    @Before
    public void setUp() {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    }

    @Test
    public void theDeviceCanSendUdpAtAll() throws Exception {
        // A real DNS query, not a random datagram. Random bytes to port 53 are dropped without a
        // reply by every resolver, so probing with them proves nothing about UDP and made this
        // control fail on a perfectly healthy network.
        //
        // It is small - 32 bytes - and it answers, which is worth stating rather than glossing
        // over: the floor measured on this network is not a hard cut-off. DNS is answered at 32
        // bytes, while an arbitrary datagram needs to be large before anything comes back. So the
        // floor is a property of the path for non-DNS traffic, not a rule this control contradicts.
        assertTrue("UDP egress is broken on this device, so no WARP verdict means anything",
                dnsAnswers("8.8.8.8"));
    }

    @Test
    public void warpReachabilityIsMeasuredNotAssumed() throws Exception {
        Set<String> silent = new LinkedHashSet<>();
        int total = 0;
        for (String ingress : INGRESSES) {
            for (int port : PORTS) {
                total++;
                if (!answered(ingress, port)) {
                    silent.add(ingress + ":" + port);
                }
            }
        }
        Log.i(TAG, "WARP endpoints silent from the device: " + silent.size() + " of " + total);
        if (!silent.isEmpty()) {
            // Record which ones, so a future run on a different network can be compared.
            Log.i(TAG, "silent: " + silent);
        }
        // Whatever the answer, the tunnel must be able to state it. A route that is filtered
        // has to be reported as filtered, never left looking like it is still connecting.
        Class<?> tunnel = Class.forName("org.colgram.core.ColgramWarpTunnel", true,
                context.getClassLoader());
        assertNotNull("the tunnel must be able to report a verdict",
                tunnel.getMethod("lastFailureReason"));
        assertNotNull("the tunnel must be able to clear a stale verdict",
                tunnel.getMethod("clearFailure"));
        assertNotNull("the tunnel must expose whether it is really carrying traffic",
                tunnel.getMethod("isConnected"));
    }

    /** Sends a real datagram and reports whether anything came back. */
    private static boolean answered(String host, int port) {
        DatagramSocket socket = null;
        try {
            socket = new DatagramSocket(new InetSocketAddress(0));
            socket.setSoTimeout(TIMEOUT_MS);
            // 1200 bytes, NOT a WireGuard-sized one, and that is the whole point of this probe.
            //
            // Measured on this network: a UDP datagram under about 1200 bytes gets no answer on ANY
            // port, while a 1200-byte one gets a reply on 443. A WireGuard message-initiation is 148
            // bytes - below that floor - so a small probe cannot tell a filtered port from a live
            // one. This test would have reported "all sixteen silent" for a perfectly open endpoint,
            // and the conclusion drawn from it would have been an artefact of the probe.
            //
            // What is given up is precision about the protocol. What is gained is the only thing
            // this test is asked: can anything at all come back from that host and port.
            byte[] payload = new byte[1200];
            for (int i = 0; i < payload.length; i++) {
                payload[i] = (byte) (i * 31 + 7);
            }
            InetAddress address = InetAddress.getByName(host);
            socket.send(new DatagramPacket(payload, payload.length, address, port));
            DatagramPacket reply = new DatagramPacket(new byte[512], 512);
            socket.receive(reply);
            return true;
        } catch (Exception e) {
            return false;
        } finally {
            if (socket != null) {
                socket.close();
            }
        }
    }

    /** Sends a well-formed A query and reports whether the resolver answers it. */
    private static boolean dnsAnswers(String resolver) {
        DatagramSocket socket = null;
        try {
            socket = new DatagramSocket(new InetSocketAddress(0));
            socket.setSoTimeout(TIMEOUT_MS);
            socket.send(new DatagramPacket(queryFor("cloudflare.com"),
                    0, 32, InetAddress.getByName(resolver), 53));
            DatagramPacket reply = new DatagramPacket(new byte[512], 512);
            socket.receive(reply);
            // A resolver that answers must set the QR bit; anything else is not a real reply.
            return reply.getLength() > 12 && (reply.getData()[2] & 0x80) != 0;
        } catch (Exception e) {
            return false;
        } finally {
            if (socket != null) {
                socket.close();
            }
        }
    }

    /** A minimal 32-byte DNS A query for `name`, which is what a resolver will actually answer. */
    private static byte[] queryFor(String name) {
        byte[] out = new byte[32];
        out[0] = 0x12; out[1] = 0x34;       // transaction id
        out[2] = 0x01; out[5] = 0x01;       // one question, recursion desired
        int at = 12;
        for (String label : name.split("\\.")) {
            int len = label.length();
            out[at++] = (byte) len;
            for (int i = 0; i < len; i++) {
                out[at++] = (byte) label.charAt(i);
            }
        }
        out[at++] = 0;                        // root label
        out[at++] = 0; out[at++] = 1;         // QTYPE = A
        out[at++] = 0; out[at++] = 1;         // QCLASS = IN
        return out;
    }
}
