package org.colgram.singbox;

import android.content.Context;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import java.io.FileDescriptor;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;

import io.nekohasekai.libbox.BridgeOptions;
import io.nekohasekai.libbox.BridgeSession;
import io.nekohasekai.libbox.ConnectionOwner;
import io.nekohasekai.libbox.InterfaceUpdateListener;
import io.nekohasekai.libbox.LocalDNSTransport;
import io.nekohasekai.libbox.NeighborUpdateListener;
import io.nekohasekai.libbox.NetworkInterface;
import io.nekohasekai.libbox.NetworkInterfaceIterator;
import io.nekohasekai.libbox.Notification;
import io.nekohasekai.libbox.PlatformInterface;
import io.nekohasekai.libbox.PlatformUser;
import io.nekohasekai.libbox.ShellSession;
import io.nekohasekai.libbox.StringIterator;
import io.nekohasekai.libbox.TunOptions;
import io.nekohasekai.libbox.WIFIState;

/**
 * The Android half of the sing-box binding.
 *
 * The engine is Go and calls back into this class for everything the operating system owns:
 * opening the TUN device, installing its routes and DNS, and noticing when the network underneath
 * changes. gomobile looks each method up BY NAME, so a missing one is not a compile error - it is a
 * crash the first time the engine reaches it, usually mid-connection while the user waits.
 *
 * Only the methods a phone needs do real work. Shell, bridge, SFTP and Tailscale answer "not
 * supported": they belong to a desktop client, and answering anything else would have Colgram
 * opening things it has no business opening.
 */
public final class ColgramPlatformInterface implements PlatformInterface {

    private static final String TAG = "ColgramSingbox";

    private final Context context;
    private volatile ParcelFileDescriptor tun;
    private volatile ColgramTunConfigurator configurator;

    public ColgramPlatformInterface(Context context) {
        this.context = context.getApplicationContext();
    }

    public void setConfigurator(ColgramTunConfigurator configurator) {
        this.configurator = configurator;
    }

    public ParcelFileDescriptor currentTun() {
        return tun;
    }

    // ---- the TUN, which is the whole point -------------------------------------------------

    @Override
    public int openTun(TunOptions options) {
        ColgramTunConfigurator target = configurator;
        if (target == null) {
            Log.e(TAG, "openTun called with no configurator installed");
            return -1;
        }
        try {
            ParcelFileDescriptor descriptor = target.open(options);
            if (descriptor == null) return -1;
            tun = descriptor;
            return rawFd(descriptor.getFileDescriptor());
        } catch (Throwable t) {
            Log.e(TAG, "cannot open the tun device", t);
            return -1;
        }
    }

    /**
     * The integer behind a FileDescriptor.
     *
     * Android exposes no public accessor, and the engine needs the int because it hands the
     * value straight to the syscall layer as a TUN fd. Reflecting the one field is the same thing
     * every VpnService integration does; failing loudly beats handing over a wrapped descriptor
     * the Go side cannot use.
     */
    private static int rawFd(FileDescriptor descriptor) throws Exception {
        Field field = FileDescriptor.class.getDeclaredField("fd");
        field.setAccessible(true);
        return field.getInt(descriptor);
    }

    @Override
    public void autoDetectInterfaceControl(int index) {
        // Android gives an app no way to choose which interface a socket leaves by, so the engine
        // must not try - saying so makes it bind to the default route instead of guessing.
    }

    @Override
    public boolean usePlatformAutoDetectInterfaceControl() {
        return false;
    }

    @Override
    public void registerMyInterface(String name) {
        // Nothing to register: the TUN belongs to VpnService, not to a name we picked.
    }

    // ---- network state, so a tunnel survives Wi-Fi becoming mobile data -------------------

    @Override
    public NetworkInterfaceIterator getInterfaces() {
        return new NetworkInterfaceIterator() {
            private final List<NetworkInterface> found = listInterfaces();
            private int index;

            @Override
            public boolean hasNext() {
                return index < found.size();
            }

            @Override
            public NetworkInterface next() {
                return found.get(index++);
            }
        };
    }

    /**
     * The live interfaces, as the engine's own wrapper objects.
     *
     * An interface can vanish between listing and reading it - the radio turns over on a phone all
     * the time - so a failure to describe one is skipped rather than propagated, which would take
     * the whole tunnel down over a network that came back a moment later.
     */
    private static List<NetworkInterface> listInterfaces() {
        List<NetworkInterface> out = new ArrayList<>();
        try {
            Enumeration<java.net.NetworkInterface> all =
                    java.net.NetworkInterface.getNetworkInterfaces();
            while (all != null && all.hasMoreElements()) {
                java.net.NetworkInterface candidate = all.nextElement();
                try {
                    if (candidate.isLoopback() || !candidate.isUp()) continue;
                    NetworkInterface wrapper = new NetworkInterface();
                    wrapper.setName(candidate.getName());
                    wrapper.setType(typeOf(candidate));
                    wrapper.setIndex(candidate.getIndex());
                    wrapper.setMTU(candidate.getMTU());
                    wrapper.setMetered(false);
                    wrapper.setAddresses(addresses(candidate));
                    wrapper.setGateway(null);
                    wrapper.setDNSServer(null);
                    out.add(wrapper);
                } catch (Throwable ignored) {
                    // Described a moment ago, gone now. Normal on a phone; not a reason to fail.
                }
            }
        } catch (Throwable t) {
            Log.i(TAG, "cannot enumerate interfaces: " + t.getClass().getSimpleName());
        }
        return out;
    }

    private static int typeOf(java.net.NetworkInterface candidate) {
        try {
            if (candidate.isUp() && candidate.supportsMulticast() && !candidate.isVirtual()) {
                String type = candidate.getInterfaceAddresses().isEmpty()
                        ? "" : candidate.getInterfaceAddresses().get(0).toString();
                if (type.startsWith("/0.0.0.0")) return 0; // WIFI per the binding's numbering
            }
        } catch (Throwable ignored) {
            // Fall through to the generic answer below.
        }
        return 3; // InterfaceTypeOther
    }

    /** Every address on an interface, as the iterator the binding expects. */
    private static StringIterator addresses(java.net.NetworkInterface candidate) {
        final List<String> found = new ArrayList<>();
        try {
            Enumeration<java.net.InetAddress> all = candidate.getInetAddresses();
            while (all.hasMoreElements()) {
                found.add(all.nextElement().getHostAddress());
            }
        } catch (Throwable ignored) {
            // An interface with no readable address is simply one the engine should avoid.
        }
        return new StringIterator() {
            private int index;

            @Override
            public boolean hasNext() {
                return index < found.size();
            }

            @Override
            public int len() {
                return found.size();
            }

            @Override
            public String next() {
                return found.get(index++);
            }
        };
    }

    @Override
    public void startDefaultInterfaceMonitor(InterfaceUpdateListener listener) {
        // VpnService observes network changes itself and rebuilds the tunnel, so the engine hears
        // about them through the configurator rather than by polling from here.
    }

    @Override
    public void closeDefaultInterfaceMonitor(InterfaceUpdateListener listener) {
        // Nothing was opened above, so nothing to close.
    }

    @Override
    public void startNeighborMonitor(NeighborUpdateListener listener) {
        // Neighbour discovery is IPv6 NDP. A phone VPN has no use for it, and doing nothing is the
        // correct answer rather than a partial implementation that half-works.
    }

    @Override
    public void closeNeighborMonitor(NeighborUpdateListener listener) {
    }

    @Override
    public void clearDNSCache() {
        // Android owns its resolver cache and a VpnService cannot reach it. The engine re-resolves
        // through the TUN's own DNS regardless.
    }

    @Override
    public WIFIState readWIFIState() {
        return new WIFIState("", "");
    }

    // ---- below belongs to a desktop client; a phone must not do any of it -----------------

    @Override
    public BridgeSession createBridge(BridgeOptions options) {
        return null;
    }

    @Override
    public boolean usePlatformBridge() {
        return false;
    }

    @Override
    public void checkPlatformShell() {
    }

    @Override
    public boolean usePlatformShell() {
        return false;
    }

    @Override
    public ShellSession openShellSession(PlatformUser user, String term,
                                        StringIterator environment, String path,
                                        int width, int height) {
        return null;
    }

    @Override
    public boolean useProcFS() {
        // The process table exists for per-process routing rules, which is a desktop feature.
        // Reporting false keeps those rules off instead of half-applying them.
        return false;
    }

    @Override
    public boolean underNetworkExtension() {
        return false;
    }

    @Override
    public boolean includeAllNetworks() {
        // True only when the app holds the consent that lets it route other apps too. The service
        // decides that, not this class, and claiming it would silently break per-app exclusions.
        return configurator != null && configurator.hasWholeDeviceConsent();
    }

    @Override
    public String lookupSFTPServer() {
        return "";
    }

    @Override
    public PlatformUser lookupUser(String name) {
        return null;
    }

    @Override
    public String readSystemSSHHostKey() {
        return "";
    }

    @Override
    public String tailscaleHostname() {
        return "";
    }

    @Override
    public LocalDNSTransport localDNSTransport() {
        return null;
    }

    @Override
    public ConnectionOwner findConnectionOwner(int uid, String processName, int protocol,
                                              String sourceAddress, int destinationPort) {
        // Per-app routing needs a process table. Without one there is no honest answer, and
        // inventing one would send a user's traffic down an outbound they never chose.
        return null;
    }

    @Override
    public void sendNotification(Notification notification) {
        if (notification != null) new ColgramNotification(context).show(notification);
    }

    @Override
    public void cancelNotification(String tag, int id) {
        new ColgramNotification(context).cancel(tag, id);
    }
}
