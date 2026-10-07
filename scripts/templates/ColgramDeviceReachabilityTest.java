package org.colgram.core;

import android.util.Log;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;

/**
 * What can this device actually reach? The relay path depends on the answer and nothing here
 * assumes it.
 *
 * <b>Why this needed asking separately.</b>
 *
 * The relay chain is proven on the host and the app's own half is proven on the device, but the
 * hop between a phone and a relay <i>elsewhere</i> is a third thing, and on this emulator it turned
 * out to be unavailable rather than merely unmeasured: ICMP to the host gateway answers in 11 ms
 * while every TCP and UDP connection to it is refused.
 *
 * <p>So the question here is not "does the relay work" - that is answered - it is "can this device
 * open a datagram socket to anything off-device at all", which decides whether the missing link is
 * a gap in the evidence or a property of the harness.
 *
 * <p><b>Why busybox nc was not used to find out.</b> It hangs. Probing 1.1.1.1:53, 10.0.2.2 and
 * the relay port all ended in a shell-level Terminated with no answer and no distinction between
 * "refused", "filtered" and "the tool is broken". A probe that cannot produce those three apart is
 * the same shape of problem as a probe that cannot tell a reset from a service, and this is the
 * fourth time in this project that a probe's own limitation has been read as a network result.
 * A DatagramSocket has a real timeout and a real exception, so each destination is classified
 * rather than guessed.
 *
 * <p>Reported, never asserted: the answer may legitimately be that this emulator reaches nothing,
 * and that is a fact about the harness rather than a defect to fail a build over.
 */
@RunWith(AndroidJUnit4.class)
public final class ColgramDeviceReachabilityTest {

    private static final String TAG = "ColgramReach";
    private static final int TIMEOUT_MS = 3000;

    /**
     * A well-known resolver, the host's gateway by both names, the relay's port, and a UDP echo
     * that is known to be listening on the host.
     *
     * The last row is the control, and it is the reason this file exists. Without a destination
     * that is provably alive, every row reading "silent" is ambiguous: the device may be filtered,
     * or the port may be closed, or the probe may be at fault. With one, a silent row means the
     * path is filtered and a non-silent row means it is not - and the whole question is answered by
     * a difference rather than by an absence.
     */
    private static final String[][] TARGETS = {
            {"1.1.1.1", "53", "a public resolver"},
            {"10.0.2.2", "51823", "the host's relay port"},
            {"192.168.0.4", "51823", "the host's LAN address"},
            {"10.0.2.2", "51827", "a known-live UDP echo on the host - the control"},
            {"8.8.8.8", "53", "a second public resolver"},
    };

    @Test
    public void everyDestinationIsClassifiedRatherThanGuessed() {
        for (String[] target : TARGETS) {
            String verdict = probe(target[0], Integer.parseInt(target[1]));
            Log.i(TAG, target[0] + ":" + target[1] + "  " + verdict + "   (" + target[2] + ")");
        }
        Log.i(TAG, "VERDICT: read the rows above. 'answered' means a datagram came back, 'refused'"
                + " means the host actively rejected it, and 'silent' means nothing came back and"
                + " nothing objected - which is what a filter looks like. Only the first of those"
                + " says a service is running there.");
    }

    /**
     * One of: answered, refused, silent, unreachable - and never a guess.
     *
     * A refused connection arrives as an exception on the *next* send or receive on Windows and
     * Linux alike, not on the send that triggered it, so the socket is used once more after a
     * successful send before concluding silence. Getting that wrong turns a refusal into a
     * timeout, which is how a blocked path gets reported as an unreachable one.
     */
    private static String probe(String host, int port) {
        DatagramSocket socket = null;
        try {
            socket = new DatagramSocket(new InetSocketAddress(0));
            socket.setSoTimeout(TIMEOUT_MS);
            byte[] payload = new byte[1200];
            payload[0] = 1;
            InetAddress address = InetAddress.getByName(host);
            socket.send(new DatagramPacket(payload, payload.length, address, port));
            DatagramPacket reply = new DatagramPacket(new byte[2048], 2048);
            socket.receive(reply);
            return "answered " + reply.getLength() + "B";
        } catch (java.net.PortUnreachableException e) {
            return "REFUSED - the host actively rejected it";
        } catch (java.net.SocketException e) {
            String message = String.valueOf(e.getMessage()).toLowerCase();
            if (message.contains("unreachable") || message.contains("refused")) {
                return "REFUSED/UNREACHABLE - " + e.getMessage();
            }
            return "ERROR - " + e.getClass().getSimpleName() + ": " + e.getMessage();
        } catch (java.net.SocketTimeoutException e) {
            return "silent - nothing came back and nothing objected";
        } catch (Exception e) {
            return "ERROR - " + e.getClass().getSimpleName() + ": " + e.getMessage();
        } finally {
            if (socket != null) socket.close();
        }
    }
}

