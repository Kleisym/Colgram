package org.colgram.singbox;

import android.content.Context;
import android.os.ParcelFileDescriptor;
import android.util.Log;

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
            if (descriptor == null) {
                Log.e(TAG, "openTun: the platform returned no descriptor");
                return -1;
            }
            tun = descriptor;
            int fd = rawFd(descriptor);
            Log.i(TAG, "openTun: descriptor held here, fd " + fd);
            return fd;
        } catch (Throwable t) {
            Log.e(TAG, "cannot open the tun device", t);
            return -1;
        }
    }

    /**
     * The integer behind a FileDescriptor.
     *
     * <p>ParcelFileDescriptor.getFd(), not a reflected FileDescriptor.fd. The reflection is a
     * long-standing habit from the days when the field was the only way in, and it is simply
     * wrong now: on Android 15 there is no such field to find, and the failure is not a clean one.
     * openTun() returned -1, the engine was handed a bad file descriptor, and the tunnel died with
     *
     * <pre>configure tun interface: query tun name: failed to get name of TUN device:
     * bad file descriptor</pre>
     *
     * <p>which names the TUN device and says nothing at all about the missing field that caused it.
     * Measured on the device (API 35), after the VPN consent was granted and establish() finally
     * returned a real descriptor:
     *
     * <pre>java.lang.NoSuchFieldException: No field fd in class java.io.FileDescriptor
     * at ColgramPlatformInterface.rawFd(ColgramPlatformInterface.java:90)</pre>
     *
     * <p>getFd() has been public since API 1, so there was never a reason to reach past it.
     */
    private static int rawFd(ParcelFileDescriptor descriptor) {
        return descriptor.getFd();
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
        // A real owner, not null, and the distinction is not a style preference.
        //
        // Per-app routing needs a process table, and without one there is no honest answer about
        // WHICH app a connection belongs to - so the fields that name a process stay empty, and the
        // uid is carried through because Android does hand that to us. The engine then has a valid
        // object to read and no per-app rule matches, which is the correct outcome for a phone.
        //
        // Returning null was a crash, measured on the device with a real packet in the TUN:
        //   panic: runtime error: invalid memory address or nil pointer dereference
        //   libbox.(*platformInterfaceWrapper).FindConnectionOwner service.go:226
        //   route.(*platformSearcher).FindProcessInfo platform_searcher.go:44
        //   route.(*Router).prepareMatchMetadata route.go:546
        // The Go side dereferences the return value without a nil check, so "no answer" here has to
        // be a valid object, not an absent one. This is the first time the engine got far enough to
        // route a packet and then died on us - everything before it failed earlier and more visibly.
        ConnectionOwner owner = new ConnectionOwner();
        try {
            owner.setUserId(uid);
            if (processName != null && !processName.isEmpty()) {
                owner.setProcessPath(processName);
            }
            // An empty package-name iterator, not a null one: the same wrapper dereferences this too.
            owner.setAndroidPackageNames(new StringIterator() {
                @Override
                public boolean hasNext() {
                    return false;
                }

                @Override
                public int len() {
                    return 0;
                }

                @Override
                public String next() {
                    throw new java.util.NoSuchElementException("no package names on a phone");
                }
            });
        } catch (Throwable t) {
            Log.w(TAG, "cannot describe the connection owner for uid " + uid + ": " + t);
        }
        return owner;
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
