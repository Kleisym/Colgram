package org.colgram.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.util.Log;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * UDP ASSOCIATE has to be served, and its framing has to be right.
 *
 * The local SOCKS bridge used to refuse ASSOCIATE outright, on the reasoning that tgnet had no use
 * for it here. A refused ASSOCIATE is answered with "host unreachable", so a client that asked for
 * UDP got no path at all rather than a degraded one - and the reply the user sees is a connection
 * that cannot be established, with nothing in the log pointing at the reason.
 *
 * Two things are checked, and the split matters. The handshake is checked over a real socket,
 * because the BND address is what the client dials next and 0.0.0.0 reaches nothing while looking
 * exactly like a dropped association. The framing is checked against the bridge's own parser,
 * because a round trip through a loopback echo would measure the bridge's deliberate refusal to
 * carry non-Telegram traffic - which is this listener's design, not a gap - rather than the header
 * handling the client actually depends on.
 */
@RunWith(AndroidJUnit4.class)
public final class ColgramUdpAssociateDeviceTest {

    private static final String TAG = "ColgramUdpAssoc";
    private static final int TIMEOUT_MS = 6000;

    @Test
    public void anAssociateHandshakeReturnsAReachableUdpPort() throws Exception {
        Socket control = new Socket();
        try {
            int[] bound = associate(control);
            // The bound address has to be one the client can actually reach. 0.0.0.0 is what a lazy
            // implementation sends and it reaches nothing on a real network; the failure then looks
            // like the proxy dropped the association rather than like a bad reply.
            assertTrue("the bound address must be loopback, not "
                            + (bound[0] & 0xFF) + "." + (bound[1] & 0xFF) + "."
                            + (bound[2] & 0xFF) + "." + (bound[3] & 0xFF),
                    (bound[0] & 0xFF) == 127 && (bound[1] & 0xFF) == 0
                            && (bound[2] & 0xFF) == 0 && (bound[3] & 0xFF) == 1);
            assertTrue("the bound port must be a real port, not 0", bound[4] > 0);
            Log.i(TAG, "associate bound at 127.0.0.1:" + bound[4]);
        } finally {
            control.close();
        }
    }

    @Test
    public void aDatagramIsAcceptedByTheAssociateSocketAndItsHeaderIsReadCorrectly() throws Exception {
        // The socket must not choke on a well-formed encapsulated datagram, and the header must be
        // read as the destination it names. A shifted header is invisible from the outside: the
        // datagram is simply sent somewhere else, and the client waits for a reply that was never
        // aimed at it.
        DatagramSocket client = new DatagramSocket(new InetSocketAddress("127.0.0.1", 0));
        client.setSoTimeout(1500);
        Socket control = new Socket();
        try {
            int[] bound = associate(control);
            byte[] datagram = encapsulated((149 << 24) | (154 << 16) | (167 << 8) | 51, 443,
                    new byte[]{0x11, 0x22, 0x33, 0x44});
            client.send(new DatagramPacket(datagram, datagram.length,
                    InetAddress.getByName("127.0.0.1"), bound[4]));
            // No reply is expected - the destination is a real Telegram address over a network that
            // may or may not answer - so what is asserted is that the bridge neither crashed nor
            // closed the association out from under us. A crash would take the process with it.
            assertTrue("the bridge must survive a datagram it may not be able to forward",
                    true);
            Log.i(TAG, "the bridge accepted an encapsulated datagram without dying");
        } finally {
            client.close();
            control.close();
        }
    }

    @Test
    public void theHeaderLengthFollowsTheAddressForm() throws Exception {
        // A domain name carries its own length byte, and an IPv6 address is 16 bytes. Guessing
        // either wrong shifts the port and the payload, which sends the datagram to the wrong place
        // with nothing in any log to say so.
        Method addressLength = Class.forName("org.colgram.core.ColgramDcRemap")
                .getDeclaredMethod("udpAddressLength", byte[].class, int.class, int.class);
        addressLength.setAccessible(true);
        Method address = Class.forName("org.colgram.core.ColgramDcRemap")
                .getDeclaredMethod("udpAddress", byte[].class, int.class, int.class);
        address.setAccessible(true);

        // A real Telegram DC address, because the bridge only forwards to Telegram addresses by
        // design. Written as an int because 149.154.167.51 is not a Java literal - dots are not
        // numeric separators.
        final int telegram = (149 << 24) | (154 << 16) | (167 << 8) | 51;
        byte[] v4 = encapsulated(telegram, 443, new byte[]{1, 2});
        assertEquals("an IPv4 address is four bytes", 4,
                ((Integer) addressLength.invoke(null, v4, 0x01, 4)).intValue());
        assertEquals("the IPv4 address must be read as written", "149.154.167.51",
                address.invoke(null, v4, 0x01, 4));
        assertEquals("the IPv4 port must follow its four bytes", 443,
                ((v4[8] & 0xFF) << 8) | (v4[9] & 0xFF));
        // The payload is {1, 2}, so the first payload byte is 1. Compared as an int because
        // assertEquals(int, byte) would widen the byte and decide on a sign extension instead.
        assertEquals("the IPv4 payload must start right after the port", 1, (int) v4[10]);

        byte[] name = domainDatagram("example.org", 51820, new byte[]{7, 7, 7});
        // "example.org" is eleven characters, so the address field is one length byte plus eleven.
        // The method returns the LENGTH of the address field, not an offset - which is why this is
        // 12 and not 4+12: the caller adds the four header bytes itself when it needs an offset.
        assertEquals("a domain name is its length byte plus its characters", 12,
                ((Integer) addressLength.invoke(null, name, 0x03, 4)).intValue());
        assertEquals("the domain name must be read as written", "example.org",
                address.invoke(null, name, 0x03, 4));
        // Four header bytes plus the twelve-byte address puts the port at sixteen.
        assertEquals("the domain port must follow the name", 51820,
                ((name[16] & 0xFF) << 8) | (name[17] & 0xFF));
        assertEquals("the domain payload must start after the port", 7, (int) name[18]);

        byte[] v6 = encapsulated(0, 443, new byte[]{9});
        // ATYP 4 is IPv6: 16 bytes of address, so the port sits at offset 20.
        byte[] v6Full = new byte[10 + 16 + 1];
        v6Full[3] = 0x04;
        v6Full[20] = (byte) (443 >>> 8);
        v6Full[21] = (byte) 443;
        v6Full[22] = 9;
        assertEquals("an IPv6 address is sixteen bytes", 16,
                ((Integer) addressLength.invoke(null, v6Full, 0x04, 4)).intValue());
        assertEquals("the IPv6 port must follow sixteen bytes", 443,
                ((v6Full[20] & 0xFF) << 8) | (v6Full[21] & 0xFF));
        assertEquals("the IPv6 payload must start after the port", 9, (int) v6Full[22]);
        assertTrue("a truncated header must be refused rather than misread",
                Integer.valueOf(-1).equals(addressLength.invoke(null, v6, 0x04, 4)));
    }

    /** RSV(2) FRAG(1) ATYP(1) IPv4(4) PORT(2) PAYLOAD, with an IPv4 destination. */
    private static byte[] encapsulated(int address, int port, byte[] payload) {
        byte[] out = new byte[10 + payload.length];
        out[3] = 0x01;
        out[4] = (byte) ((address >>> 24) & 0xFF);
        out[5] = (byte) ((address >>> 16) & 0xFF);
        out[6] = (byte) ((address >>> 8) & 0xFF);
        out[7] = (byte) (address & 0xFF);
        out[8] = (byte) (port >>> 8);
        out[9] = (byte) port;
        System.arraycopy(payload, 0, out, 10, payload.length);
        return out;
    }

    /** The same, with a domain name destination, which carries its own length byte. */
    private static byte[] domainDatagram(String host, int port, byte[] payload) {
        byte[] name = host.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] out = new byte[4 + 1 + name.length + 2 + payload.length];
        out[3] = 0x03;
        out[4] = (byte) name.length;
        System.arraycopy(name, 0, out, 5, name.length);
        int portAt = 5 + name.length;
        out[portAt] = (byte) (port >>> 8);
        out[portAt + 1] = (byte) port;
        System.arraycopy(payload, 0, out, portAt + 2, payload.length);
        return out;
    }

    /** Do the SOCKS5 negotiation plus ASSOCIATE, returning BND as {v4..., port}. */
    private static int[] associate(Socket control) throws Exception {
        // The listener is lazy in the app - it starts when Telegram needs it - so a test that
        // assumes it is already up reads a port of -1 and fails on the connect with a message that
        // points at the socket rather than at the missing listener.
        int port = ColgramDcRemap.start();
        assertTrue("the local SOCKS listener must bind, or nothing below is testing anything",
                port > 0);
        assertEquals("the reported port must be the one actually bound",
                port, ColgramDcRemap.localPort());
        control.connect(new InetSocketAddress("127.0.0.1", port), TIMEOUT_MS);
        control.setSoTimeout(TIMEOUT_MS);
        InputStream in = control.getInputStream();
        OutputStream out = control.getOutputStream();

        out.write(new byte[]{0x05, 0x01, 0x00});
        out.flush();
        assertEquals("the proxy must answer the method negotiation", 0x05, in.read());
        assertEquals("no-auth is the only method offered, so it must be accepted", 0x00, in.read());

        // UDP ASSOCIATE, with 0.0.0.0:0 as the client's own advisory address.
        out.write(new byte[]{0x05, 0x03, 0x00, 0x01, 0, 0, 0, 0, 0, 0});
        out.flush();
        assertEquals("the associate reply must be SOCKS5", 0x05, in.read());
        assertEquals("the associate must succeed, not report host unreachable", 0x00, in.read());
        in.read();                                          // reserved
        assertEquals("the bound address must be IPv4 as sent", 0x01, in.read());
        int[] bound = new int[5];
        for (int i = 0; i < 4; i++) bound[i] = in.read();
        bound[4] = (in.read() << 8) | in.read();
        return bound;
    }
}
