package org.colgram.core;

import android.content.Context;
import android.content.Intent;
import android.net.VpnService;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;

/**
 * ColgramMasqueVpnService — the MASQUE tunnel as an actual device-wide VPN.
 *
 * What this adds over ColgramWarpMasqueTunnel, which only proves the tunnel carries traffic: a TUN
 * interface, so the phone's own packets are the ones going through WARP. That is the difference
 * between a measurement and a feature, and it is the last thing this project does not have.
 *
 * The shape follows ColgramVpnService, which already solved the parts that are easy to get wrong:
 *
 * -   the endpoint leaves by the physical interface. Everything is routed into the tunnel, so
 *     without excludeRoute for the edge the tunnel swallows its own MASQUE handshake and dies
 *     quietly. ColgramVpnService found this the hard way - the tunnel "started cleanly" and the
 *     endpoint became unreachable the moment there was traffic to carry.
 * -   the platform stub is read reflectively, because VpnService.Builder has no
 *     addDisallowedRoute and compiling against one fails the build rather than misbehaving quietly.
 *
 * What is deliberately not here: a userspace IP stack. The native client speaks Connect-IP over UDP
 * and reads back the whole IP packet, so routing a device through it needs packet forwarding in both
 * directions - and that belongs in the Go client next to the capsules, not bolted on from Java.
 * See MASQUE_FORWARDING.md for what is done and what is not.
 */
public final class ColgramMasqueVpnService extends VpnService {

    private static final String TAG = "ColgramMasqueVpn";
    private static final String ACTION_START = "org.colgram.core.masque.START";
    private static final String ACTION_STOP = "org.colgram.core.masque.STOP";
    private static final String EXTRA_BIND = "bind";
    private static final String EXTRA_EDGE = "edge";
    private static final String EXTRA_SOCKS = "socks";
    private static final int FOREGROUND_ID = 0x43_02;

    /** The tunnel's own address, from the registration. */
    private static final String TUN_ADDRESS = "172.16.0.2";
    private static final int TUN_PREFIX = 24;
    private static final String TUN_MTU = "1280";

    private ParcelFileDescriptor tun;
    private Thread worker;
    private volatile boolean running;
    private volatile long packetsHandled;
    private volatile long repliesWritten;
    private volatile boolean verdictInFlight;
    private volatile String warpVerdict;

    /**
     * Starts the tunnel, or reports that the consent dialog has to be shown first.
     *
     * @return null when the service was started, or the Intent from {@code VpnService.prepare()} that
     *         the caller must put in front of the user and then call this again with the result
     */
    public static android.content.Intent start(Context context, String bind, String edge) {
        return start(context, bind, edge, null);
    }

    /**
     * Starts the tunnel, optionally naming a SOCKS5 front for the tunnel's own UDP.
     *
     * The front is not optional on a network that filters UDP to the edge. Measured on the device
     * this was built for: direct UDP to 162.159.x.x:443 never gets an answer on any port tried,
     * while TCP 443 and UDP 53 both answer, and a SOCKS5 front that supports UDP ASSOCIATE carries
     * the same traffic fine. Without this the pump reads a packet, hands it to a native client that
     * is sending direct UDP into a black hole, waits out its five-second deadline, and returns
     * nothing -- which on screen is a tunnel that is up, with rx pinned at zero, and no log line
     * saying why.
     */
    public static android.content.Intent start(Context context, String bind, String edge, String socks) {
        Intent intent = new Intent(context, ColgramMasqueVpnService.class);
        intent.setAction(ACTION_START);
        intent.putExtra(EXTRA_BIND, bind);
        intent.putExtra(EXTRA_EDGE, edge);
        if (socks != null && !socks.isEmpty()) {
            intent.putExtra(EXTRA_SOCKS, socks);
        }
        try {
            // prepare() first, and it is not optional.
            //
            // Builder.establish() returns null unless the calling app holds a prepared VpnService
            // Intent, and it returns it *silently* - no exception, no log line on the system side.
            // Measured here: with the consent dialog granted and the app in the foreground, a
            // startService() straight into establish() still produced
            //     "establish() returned no descriptor"
            // and no tun interface, with nothing in logcat anywhere. Starting through prepare()'s
            // Intent is what puts the app in the prepared state, and only then does establish()
            // hand back a descriptor.
            //
            // prepare() returns null when consent already exists - that is the success case, not a
            // failure - and a non-null Intent when the user has to be asked. The caller shows that
            // one and starts the service with startService(intent).
            Intent prepared = android.net.VpnService.prepare(context);
            if (prepared != null) {
                // Consent is missing. The caller has to put that Intent in front of the user and
                // come back to start(); starting now would end in a null descriptor again.
                Log.i(TAG, "VPN consent required; the caller must show prepare() and retry");
                return prepared;
            }
            context.startService(intent);
        } catch (IllegalStateException e) {
            // Android 8+ refuses a background start; the switch has to come from the UI.
            Log.i(TAG, "background start refused; start from the UI");
        } catch (Throwable t) {
            Log.e(TAG, "VPN start failed", t);
        }
        return null;
    }

    public static void stop(Context context) {
        Intent intent = new Intent(context, ColgramMasqueVpnService.class);
        intent.setAction(ACTION_STOP);
        try {
            context.startService(intent);
        } catch (IllegalStateException ignored) {
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopTunnel();
            stopSelf();
            return START_NOT_STICKY;
        }
        if (!ACTION_START.equals(action)) {
            return START_STICKY;
        }

        // A start that arrives as a raw startService() never went through prepare(), and
        // Builder.establish() returns null for exactly that case - silently, with no system-side log
        // and nothing on this side either. So the prepared state is checked HERE, where a null from
        // establish() is otherwise reported as if the network had refused:
        //
        //     E ColgramMasqueVpn: establish() returned no descriptor
        //
        // which says nothing about the cause and was read as a VPN-slot problem for a long time.
        // Consent does exist on this device - appops says ACTIVATE_VPN: allow - and establish() still
        // returned null, because consent is not the same thing as being the prepared caller.
        try {
            if (android.net.VpnService.prepare(this) != null) {
                Log.e(TAG, "service started without prepare(); Android would refuse the descriptor");
                stopSelf();
                return START_NOT_STICKY;
            }
        } catch (Throwable t) {
            Log.e(TAG, "cannot query the prepared VPN state", t);
        }

        final String bind = intent.getStringExtra(EXTRA_BIND);
        final String edge = intent.getStringExtra(EXTRA_EDGE);
        final String socks = intent.getStringExtra(EXTRA_SOCKS);

        if (tun != null) {
            return START_STICKY;
        }

        Builder builder = new Builder();
        builder.setSession("Colgram WARP");
        builder.setMtu(Integer.parseInt(TUN_MTU));
        try {
            builder.addAddress(TUN_ADDRESS, TUN_PREFIX);
        } catch (Throwable t) {
            Log.e(TAG, "cannot set the tunnel address", t);
            stopSelf();
            return START_NOT_STICKY;
        }

        // Everything goes into the tunnel.
        builder.addRoute("0.0.0.0", 0);
        // ...except the edge, which has to leave by the physical interface or the tunnel swallows
        // its own handshake. This is the failure ColgramVpnService measured: the tunnel starts,
        // the endpoint is unreachable the moment there is traffic, and nothing in the log says why.
        excludeEdgeRoute(builder, edge);
        // ...and except the SOCKS5 front, for the same reason and with the same symptom.
        //
        // Measured here. With only the edge excluded, every socket the app opened to the front hung in
        // SYN_SENT:
        //
        //     020010AC:A76A -> 0202000A:3B2E state 02 uid 10061
        //
        // which is the app (uid 10061) talking to 10.0.2.2:15150 -- the front -- and never getting a
        // reply. The interface the front lives on, 10.0.2.0/24, is not on any excluded route, so the
        // connection went into the tunnel, into the pump, and from there into the very front that was
        // supposed to carry it. A shell on the device reached the same port fine, because uid 0 is
        // routed by `oif wlan0 uidrange 0-0` and never enters the tunnel at all.
        excludeHostRoute(builder, socks);
        excludeDohResolvers(builder);

        try {
            tun = builder.establish();
        } catch (Throwable t) {
            Log.e(TAG, "establish() threw", t);
            stopSelf();
            return START_NOT_STICKY;
        }
        if (tun == null) {
            // A null descriptor and an existing interface are different facts, and only the second
            // one is a tunnel. ColgramVpnService hit exactly this: establish() returned without an
            // error and no tun interface existed afterwards.
            Log.e(TAG, "establish() returned no descriptor");
            stopSelf();
            return START_NOT_STICKY;
        }

        Log.i(TAG, "tunnel up on " + TUN_ADDRESS + "/" + TUN_PREFIX + ", mtu=" + TUN_MTU);
        // Start the in-process front when the caller did not name one.
        //
        // ColgramWarpMasqueTunnel.bringUp() does this, but the service is also reachable on its own
        // -- from the switch, from a restored state, from an explicit start -- and it used to take
        // the front from the intent and hand it straight to the native client:
        //
        //     I ColgramMasqueVpn: socks front=none
        //
        // With no front, the native client sends its QUIC direct, into a path this network filters,
        // and the interface comes up carrying nothing. Measured here: tx=12, rx=2, and the verdict
        // came back
        //
        //     verdict through the tunnel: warp=off | no trace: tls inside tunnel: use of closed
        //     network connection
        //
        // which is the tunnel's own connection being torn down under a TLS handshake that was never
        // going to complete. The front belongs to the tunnel rather than to whoever asked for it,
        // so the service owns it when nobody else has.
        String front = socks;
        if (front == null || front.trim().isEmpty()) {
            if (!ColgramUdpTunnel.isRunning() && ColgramUdpTunnel.start() <= 0) {
                Log.w(TAG, "in-process UDP front could not bind; trying direct routes only");
            }
            if (ColgramUdpTunnel.isRunning()) {
                front = ColgramUdpTunnel.frontAddress();
            }
        }
        // Set through the library rather than an environment variable: a c-shared library snapshots
        // the environment when it is loaded, so a front exported after the load is invisible to it.
        ColgramMasqueNative.setSocksFront(front);
        Log.i(TAG, "socks front=" + (front == null || front.isEmpty() ? "none" : front));
        // startForeground, before anything that can block.
        //
        // Started with startForegroundService, a service has five seconds to call startForeground
        // and the platform raises an ANR when it does not. This one never did, so every attempt to
        // bring the tunnel up ended with the system's own dialog on screen:
        //
        //     Subject: Context.startForegroundService() did not then call Service.startForeground():
        //     ServiceRecord{... org.colgram.messenger/org.colgram.core.ColgramMasqueVpnService}
        //
        // which reads as "the app hangs" and is indistinguishable, from the outside, from any other
        // freeze. Android 14+ also throws outright on a foreground service of type dataSync started
        // from the background, so on a newer device this was not a dialog but a crash.
        startForegroundNotification();
        running = true;
        worker = new Thread(() -> pump(bind, edge), "colgram-masque-pump");
        worker.setDaemon(true);
        worker.start();
        return START_STICKY;
    }

    /**
     * Moves packets between the TUN and the tunnel.
     *
     * Both directions go through the native client, which already speaks Connect-IP capsules: what
     * goes in is a whole IP packet and what comes back is a whole IP packet, so no IP stack is
     * needed in Java to frame or unframe anything.
     */
    private void pump(String bind, String edge) {
        FileInputStream in = null;
        FileOutputStream out = null;
        try {
            in = new FileInputStream(tun.getFileDescriptor());
            out = new FileOutputStream(tun.getFileDescriptor());

            byte[] buffer = new byte[32767];
            while (running) {
                int read = in.read(buffer);
                if (read <= 0) {
                    continue;
                }
                // The pump is the one place where "the tunnel is up but nothing flows" becomes
                // visible. Every earlier symptom was identical: tun0 exists, routing is correct,
                // tx climbs, rx stays at 0, and nothing is logged anywhere. Whether the native
                // client loaded, and whether a reply came back, were both invisible -- the wrapper
                // returns an empty array on failure, so a load failure and a dead tunnel produced
                // exactly the same silence.
                packetsHandled++;
                if (packetsHandled <= 3 || packetsHandled % 50 == 0) {
                    Log.i(TAG, "pump packet " + packetsHandled + " len=" + read
                            + " native=" + ColgramMasqueNative.isAvailable()
                            + " nativeError=" + ColgramMasqueNative.unavailableReason()
                            + " lastError=" + ColgramMasqueNative.lastError()
                            + " stage=" + ColgramMasqueNative.sessionProgress()
                            + " " + ColgramMasqueNative.capsuleStats());
                }
                // No gate here. The first packet is what BUILDS the session - exchangeIpPacket() calls
                // open_session when there is none - so a check that refused to hand it over was
                // refusing the only thing that could start the tunnel:
                //
                //   ColgramMasqueVpn: held 3140 packet(s) while the carrier builds (stage=none)
                //
                // stage stayed "none" forever because nothing was ever passed to the client, and the
                // interface sat there taking packets off the phone and discarding every one. The
                // original problem was different and smaller: those packets died because the tunnel
                // was not up yet, which is a cost of a ten-second carrier search, not a reason to
                // refuse to dial it.
                byte[] packet = new byte[read];
                System.arraycopy(buffer, 0, packet, 0, read);
                byte[] reply = ColgramMasqueNative.exchangeIpPacket(packet, bind, edge);
                if (reply != null && reply.length > 0) {
                    out.write(reply, 0, reply.length);
                    out.flush();
                    repliesWritten++;
                    if (repliesWritten <= 3) {
                        Log.i(TAG, "reply written, " + reply.length + " bytes");
                    }
                    // The verdict, asked of the tunnel rather than of a host that has an
                    // unfiltered connection of its own. Done on a worker thread because the trace
                    // read waits on the network, and this is the pump's only job.
                    if (warpVerdict == null && !verdictInFlight) {
                        verdictInFlight = true;
                        final Thread probe = new Thread(() -> {
                            try {
                                String trace = ColgramMasqueNative.trace();
                                warpVerdict = ColgramMasqueNative.traceIsWarpOn(trace)
                                        ? "warp=on" : "warp=off";
                                // The whole trace is logged either way. A verdict of warp=off with the
                                // body in hand says which fields are wrong -- an ip= that is the
                                // device's own address means the response came from somewhere other
                                // than the tunnel, and that is a different fault from the tunnel
                                // itself not carrying traffic.
                                Log.i(TAG, "verdict through the tunnel: " + warpVerdict
                                        + " | " + (trace == null || trace.isEmpty()
                                                ? "no trace: " + ColgramMasqueNative.lastError()
                                                : trace.replace('\n', ' ').trim()));
                            } catch (Throwable t) {
                                Log.w(TAG, "verdict probe failed: " + t);
                            } finally {
                                verdictInFlight = false;
                            }
                        }, "colgram-warp-verdict");
                        probe.setDaemon(true);
                        probe.start();
                    }
                }
            }
        } catch (Throwable t) {
            if (running) {
                Log.w(TAG, "pump stopped: " + t);
            }
        } finally {
            closeQuietly(in);
            closeQuietly(out);
        }
    }

    private static void closeQuietly(java.io.Closeable c) {
        if (c != null) {
            try {
                c.close();
            } catch (Exception ignored) {
            }
        }
    }

    private static final String CHANNEL_ID = "colgram_warp";

    /**
     * Puts the service in the foreground, which a startForegroundService caller owes within five
     * seconds or the platform raises an ANR against the whole process.
     *
     * <p>The typed overload is tried first and the untyped one is the fallback, because
     * {@code startForeground(id, notification, FOREGROUND_SERVICE_TYPE_DATA_SYNC)} throws
     * IllegalArgumentException when the manifest declares no matching type, and an exception here
     * would abort the tunnel that the notification exists to keep alive.
     */
    private void startForegroundNotification() {
        try {
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                android.app.NotificationManager nm =
                        (android.app.NotificationManager) getSystemService(NOTIFICATION_SERVICE);
                if (nm != null && nm.getNotificationChannel(CHANNEL_ID) == null) {
                    nm.createNotificationChannel(new android.app.NotificationChannel(
                            CHANNEL_ID, "Colgram WARP", android.app.NotificationManager.IMPORTANCE_LOW));
                }
            }
            android.app.Notification.Builder b;
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                b = new android.app.Notification.Builder(this, CHANNEL_ID);
            } else {
                b = new android.app.Notification.Builder(this);
            }
            b.setContentTitle("Colgram");
            b.setContentText("WARP работает, трафик идет через туннель");
            b.setSmallIcon(android.R.drawable.stat_notify_sync);
            b.setOngoing(true);
            b.setShowWhen(false);
            android.app.Notification n = b.build();
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                // The manifest declares this service specialUse, so DATA_SYNC is refused - and a
                // refused startForeground on a running foreground service is what gets the process
                // killed a few seconds later:
                //
                //   startForeground refused: IllegalArgumentException:
                //     foregroundServiceType 0x00000001 is not a subset of the manifest
                //
                //   ColgramMasqueVpn: tunnel up on 172.16.0.2/24     <- interface appears
                //   ... ten seconds of carrier search ...
                //   Got obituary of 60901:org.colgram.messenger.web:colgram_masque
                //   ip addr show tun0 -> No such device
                //
                // So the tunnel lived exactly as long as the search and then took the interface with
                // it. Asked for the type it is declared as, which is what the manifest authorises.
                startForeground(FOREGROUND_ID, n,
                        android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            } else {
                startForeground(FOREGROUND_ID, n);
            }
        } catch (Throwable t) {
            // The tunnel still works without the notification, so this is a warning and not a
            // reason to tear the interface down.
            Log.w(TAG, "startForeground refused: " + t);
        }
    }

    /**
     * Keeps the edge address off the tunnel.
     *
     * A host:port pair, a bare address, or nothing - all three are accepted because the caller may
     * have a relay, an address, or nothing to say.
     */
    private void excludeEdgeRoute(Builder builder, String edge) {
        if (edge == null || edge.trim().isEmpty()) {
            return;
        }
        String host = edge.trim();
        int colon = host.lastIndexOf(':');
        if (colon > 0 && host.indexOf(':') == colon) {
            host = host.substring(0, colon);
        }
        try {
            InetAddress address = InetAddress.getByName(host);
            if (!(address instanceof Inet4Address)) {
                return;
            }
            // Reflected because VpnService.Builder has no addDisallowedRoute, and compiling against
            // one fails the build rather than misbehaving quietly at runtime.
            builder.excludeRoute(new android.net.IpPrefix(address, 32));
            Log.i(TAG, "edge " + host + " stays off the tunnel, so the handshake has a way out");
        } catch (Throwable t) {
            Log.e(TAG, "cannot exclude " + host + "; the tunnel will swallow its own handshake", t);
        }
    }

    /**
     * Keeps a host:port pair off the tunnel, so the connection to it leaves by the physical
     * interface.
     *
     * The same treatment {@link #excludeEdgeRoute} gives the edge, for the same reason, but the port
     * is ignored: the route is per-address and the port does not enter into it.
     */
    private void excludeHostRoute(Builder builder, String hostAndPort) {
        if (hostAndPort == null || hostAndPort.trim().isEmpty()) {
            return;
        }
        String host = hostAndPort.trim();
        int colon = host.lastIndexOf(':');
        if (colon > 0 && host.indexOf(':') == colon) {
            host = host.substring(0, colon);
        }
        try {
            InetAddress address = InetAddress.getByName(host);
            if (!(address instanceof Inet4Address)) {
                return;
            }
            builder.excludeRoute(new android.net.IpPrefix(address, 32));
            Log.i(TAG, "socks front " + host + " stays off the tunnel, so the client can reach it");
        } catch (Throwable t) {
            Log.e(TAG, "cannot exclude " + host + "; the front will be inside the tunnel", t);
        }
    }

    /**
     * Keeps the DoH resolvers out of the tunnel, which is the one case where the tunnel's own
     * bootstrap traffic must not be routed into itself.
     *
     * <p>The order is the whole point. The client resolves the API address and registers the device
     * over HTTPS before a single packet of tunnel traffic exists, and on this network that HTTPS
     * only completes through the in-process front on loopback. So the front's socket belongs to the
     * app, which means its packets are routed by uid into whatever table that uid is in -- which,
     * once the interface exists, is the tunnel:
     *
     *     1.1.1.1 dev tun0 table tun0 src 172.16.0.2 uid 10061
     *
     * So the front asks the tunnel to reach a resolver, the tunnel is not up yet, and nothing comes
     * back. Measured:
     *
     *     connect to 1.1.1.1:443 failed: SocketTimeoutException
     *     connect to 8.8.8.8:443 failed: SocketTimeoutException
     *
     * for every resolver, on every attempt, with the front itself up and answering. A chicken-and-
     * egg that only the routing table can break.
     *
     * <p>These four addresses are the resolvers the native client already races, so excluding them
     * costs nothing: the client only uses them before the tunnel exists.
     */
    private void excludeDohResolvers(Builder builder) {
        // Every address the native client races, not just the first four.
        //
        // The list grew once already: excluding 1.1.1.1, 1.0.0.1, 8.8.8.8 and 8.8.4.4 left the rest
        // inside the tunnel, and the log showed the survivors by name --
        //
        //     connect to 94.140.14.14:443 failed: SocketTimeoutException
        //     connect to 104.16.192.82:443 failed: SocketTimeoutException
        //     connect to 104.16.24.84:443 failed: SocketTimeoutException
        //
        // Any address that is dialled before the tunnel exists has to be excluded, and the set is
        // the resolver list in the native client rather than a guess about which of them works.
        String[] resolvers = {
                "1.1.1.1", "1.0.0.1", "8.8.8.8", "8.8.4.4", "94.140.14.14",
                "104.16.0.0/13",
        };
        for (String r : resolvers) {
            try {
                if (r.contains("/")) {
                    // IpPrefix.parse is API 21+, but this app compiles against a platform that does
                    // not expose it, so the prefix is built by hand. /13 is 104.16.0.0, the block the
                    // resolver list lands in as its addresses rotate.
                    builder.excludeRoute(new android.net.IpPrefix(
                            InetAddress.getByName("104.16.0.0"), 13));
                } else {
                    builder.excludeRoute(new android.net.IpPrefix(InetAddress.getByName(r), 32));
                }
            } catch (Throwable t) {
                Log.w(TAG, "cannot exclude resolver " + r + "; the front may loop into the tunnel", t);
            }
        }
        Log.i(TAG, "DoH resolvers stay off the tunnel, so the front can reach them to build it");
    }

    private void stopTunnel() {
        running = false;
        if (worker != null) {
            worker.interrupt();
            worker = null;
        }
        if (tun != null) {
            try {
                tun.close();
            } catch (Exception ignored) {
            }
            tun = null;
            Log.i(TAG, "tunnel down");
        }
    }

    @Override
    public void onDestroy() {
        stopTunnel();
        super.onDestroy();
    }

    @Override
    public void onRevoke() {
        stopTunnel();
        super.onRevoke();
    }
}
