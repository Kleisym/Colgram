package org.colgram.singbox;

import android.app.Notification;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.VpnService;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import java.io.FileDescriptor;
import java.util.List;

import io.nekohasekai.libbox.CommandServer;
import io.nekohasekai.libbox.CommandServerHandler;
import io.nekohasekai.libbox.RoutePrefix;
import io.nekohasekai.libbox.RoutePrefixIterator;
import io.nekohasekai.libbox.StringIterator;
import io.nekohasekai.libbox.TunOptions;

/**
 * Owns the tunnel, so a subscription routes the whole phone and not only Colgram.
 *
 * The engine is handed a file descriptor and does the rest: it opens the TUN, installs the
 * routes and DNS from its own profile, and calls back through ColgramPlatformInterface when the
 * network underneath changes. This class's job is the part the engine cannot do - hold the
 * consent, keep the process alive, and turn a profile the user picked into a running tunnel.
 */
public final class ColgramVpnService extends VpnService implements ColgramTunConfigurator {

    private static final String TAG = "ColgramVpn";
    private static final String ACTION_START = "org.colgram.singbox.START";
    private static final String ACTION_STOP = "org.colgram.singbox.STOP";
    private static final int FOREGROUND_ID = 0x43_01;

    private CommandServer server;
    private ColgramPlatformInterface platform;
    private String profilePath;

    public static void start(android.content.Context context, String profilePath) {
        Intent intent = new Intent(context, ColgramVpnService.class);
        intent.setAction(ACTION_START);
        intent.putExtra("profile", profilePath);
        try {
            context.startService(intent);
        } catch (IllegalStateException e) {
            // Android 8+ refuses a background start; the user has to switch the profile on.
            Log.i(TAG, "background start refused, the profile must be started from the UI");
        }
    }

    public static void stop(android.content.Context context) {
        Intent intent = new Intent(context, ColgramVpnService.class);
        intent.setAction(ACTION_STOP);
        try {
            context.startService(intent);
        } catch (IllegalStateException ignored) {
            // Already gone, which is the state the caller wanted.
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            tearDown();
            return START_NOT_STICKY;
        }
        String path = intent == null ? null : intent.getStringExtra("profile");
        if (path == null || path.isEmpty()) {
            Log.w(TAG, "started with no profile; nothing to do");
            return START_NOT_STICKY;
        }
        profilePath = path;
        try {
            bringUp();
        } catch (Throwable t) {
            // A tunnel that dies silently is the failure mode this whole exercise exists to end,
            // so the reason goes to the log where it can be found rather than into a black hole.
            Log.e(TAG, "the tunnel could not be started", t);
            new ColgramNotification(this).fail(t.getMessage() == null
                    ? t.toString() : t.getMessage());
            tearDown();
            return START_NOT_STICKY;
        }
        // Sticky: Android restarts a VPN service after a reboot or a low-memory kill, and a
        // subscription that silently stops protecting the phone is worse than one that retries.
        return START_STICKY;
    }

    private void bringUp() throws Exception {
        // touch() is the binding's initialiser: it runs Seq.touch() and the native _init(). There
        // is no init() to call, and calling nothing leaves the engine uninitialised.
        io.nekohasekai.libbox.Libbox.touch();
        if (platform == null) {
            platform = new ColgramPlatformInterface(this);
            platform.setConfigurator(this);
        }
        if (server == null) {
            server = new CommandServer(new Handler(), platform);
        }
        startForegroundCompat();
        server.startOrReloadService(profilePath, null);
        Log.i(TAG, "tunnel starting with profile " + profilePath);
    }

    private void startForegroundCompat() {
        Notification notification = buildOngoingNotification();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(FOREGROUND_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(FOREGROUND_ID, notification);
        }
    }

    private Notification buildOngoingNotification() {
        String channel = "colgram_vpn_status";
        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, channel)
                : new Notification.Builder(this);
        builder.setContentTitle("Colgram VPN")
                .setContentText("Трафик идёт через туннель")
                .setSmallIcon(android.R.drawable.ic_lock_lock)
                .setOngoing(true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            android.app.NotificationManager manager =
                    (android.app.NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (manager != null && manager.getNotificationChannel(channel) == null) {
                android.app.NotificationChannel created = new android.app.NotificationChannel(
                        channel, "VPN", android.app.NotificationManager.IMPORTANCE_LOW);
                manager.createNotificationChannel(created);
            }
        }
        return builder.build();
    }

    private void tearDown() {
        if (server != null) {
            try {
                server.closeService();
            } catch (Throwable ignored) {
                // The engine is already gone; closing again would only throw again.
            }
            // closeService() stops the tunnel but does NOT release the Go object behind this
            // CommandServer. The gomobile binding tracks it with a phantom reference, and the
            // GoRefQueue finalizer thread calls Seq.destroyRef on it once Java collects it -
            // by which point the native side has already been torn down, and destroyRef walks
            // freed memory. That is a segfault with no tombstone and no Java stack: measured on
            // the device, the next Libbox.checkConfig() call after a start/stop cycle died with
            // signal 11 in ~24ms where a healthy call takes 1.4s. So the server is closed
            // explicitly here and the field nulled, and the close is ordered after
            // closeService() because the service owns the running tunnel first.
            try {
                server.close();
            } catch (Throwable ignored) {
                // Already closed, or the engine went away with the process. Either way there is
                // nothing left to release.
            }
            server = null;
        }
        stopForeground(true);
        stopSelf();
    }

    @Override
    public void onDestroy() {
        tearDown();
        super.onDestroy();
    }

    @Override
    public void onRevoke() {
        // The user revoked consent in system settings. Leaving a tunnel running after that would
        // be routing traffic the user has just said no to.
        Log.i(TAG, "consent revoked; taking the tunnel down");
        tearDown();
        super.onRevoke();
    }

    // ---- what the engine asks for --------------------------------------------------------

    @Override
    public ParcelFileDescriptor open(TunOptions options) {
        if (options == null) return null;
        Builder builder = new Builder();
        builder.setSession("Colgram");
        builder.setMtu(options.getMTU() > 0 ? options.getMTU() : 9000);
        builder.setBlocking(false);

        for (RoutePrefix prefix : drain(options.getInet4Address())) {
            if (prefix == null || prefix.address() == null || prefix.prefix() < 0) continue;
            java.net.InetAddress address;
            try {
                address = java.net.InetAddress.getByName(prefix.address());
            } catch (Exception unparseable) {
                // One bad prefix in a profile must not cost the user the whole tunnel.
                Log.i(TAG, "skipping unparseable address " + prefix.address());
                continue;
            }
            int mask = prefix.prefix();
            if (address instanceof java.net.Inet4Address) {
                builder.addAddress(address.getHostAddress(), mask);
            }
        }
        for (RoutePrefix prefix : drain(options.getInet6Address())) {
            if (prefix == null || prefix.address() == null || prefix.prefix() < 0) continue;
            java.net.InetAddress address;
            try {
                address = java.net.InetAddress.getByName(prefix.address());
            } catch (Exception unparseable) {
                Log.i(TAG, "skipping unparseable address " + prefix.address());
                continue;
            }
            if (address instanceof java.net.Inet6Address) {
                builder.addAddress(address.getHostAddress(), prefix.prefix());
            }
        }
        for (RoutePrefix prefix : drain(options.getInet4RouteAddress())) {
            if (prefix == null || prefix.address() == null) continue;
            builder.addRoute(prefix.address(), prefix.prefix());
        }
        for (RoutePrefix prefix : drain(options.getInet6RouteAddress())) {
            if (prefix == null || prefix.address() == null) continue;
            builder.addRoute(prefix.address(), prefix.prefix());
        }
        for (String server : drainStrings(options.getDNSServerAddress())) {
            if (server != null && !server.isEmpty()) builder.addDnsServer(server);
        }
        for (String name : drainStrings(options.getExcludePackage())) {
            applyPackage(builder, name, false);
        }
        for (String name : drainStrings(options.getIncludePackage())) {
            applyPackage(builder, name, true);
        }
        try {
            ParcelFileDescriptor descriptor = builder.establish();
            if (descriptor == null) Log.e(TAG, "establish() returned no descriptor");
            return descriptor;
        } catch (Throwable t) {
            Log.e(TAG, "establish failed", t);
            return null;
        }
    }

    private void applyPackage(Builder builder, String name, boolean include) {
        if (name == null || name.isEmpty()) return;
        try {
            if (include) {
                builder.addAllowedApplication(name);
            } else {
                builder.addDisallowedApplication(name);
            }
        } catch (Throwable t) {
            // A package that was uninstalled between the profile being written and the tunnel
            // starting is not a reason to refuse the whole tunnel.
            Log.i(TAG, "cannot apply " + name + ": " + t.getClass().getSimpleName());
        }
    }

    @Override
    public boolean hasWholeDeviceConsent() {
        return true;
    }

    private static List<RoutePrefix> drain(RoutePrefixIterator iterator) {
        List<RoutePrefix> out = new java.util.ArrayList<>();
        if (iterator == null) return out;
        try {
            // No len() on this one, and it is a live view rather than a copy, so it is drained
            // until hasNext() says stop. A bound is still applied so a broken iterator that never
            // ends cannot hold the tunnel open forever.
            int guard = 0;
            while (iterator.hasNext() && guard++ < 256) {
                out.add(iterator.next());
            }
        } catch (Throwable ignored) {
            // A truncated iterator yields fewer routes, never a broken tunnel.
        }
        return out;
    }

    private static List<String> drainStrings(StringIterator iterator) {
        List<String> out = new java.util.ArrayList<>();
        if (iterator == null) return out;
        try {
            for (int i = 0; i < iterator.len() && iterator.hasNext(); i++) {
                out.add(iterator.next());
            }
        } catch (Throwable ignored) {
            // Same: a partial list is better than refusing to build the tunnel.
        }
        return out;
    }

    /** The engine's side of the conversation: reload requests, stops, and diagnostics. */
    private final class Handler implements CommandServerHandler {
        @Override
        public int connectSSHAgent() {
            // No SSH agent on a phone. Returning zero says "none", which is what the engine
            // expects, rather than handing it a connection that does not exist.
            return 0;
        }

        @Override
        public io.nekohasekai.libbox.SystemProxyStatus getSystemProxyStatus() {
            // Android has no system proxy. The engine asks so a desktop profile can enable one;
            // here the answer is always "off", because the TUN does the routing instead.
            io.nekohasekai.libbox.SystemProxyStatus status =
                    new io.nekohasekai.libbox.SystemProxyStatus();
            status.setAvailable(false);
            status.setEnabled(false);
            return status;
        }

        @Override
        public void serviceReload() {
            try {
                if (server != null) server.startOrReloadService(profilePath, null);
            } catch (Throwable t) {
                Log.e(TAG, "reload failed", t);
            }
        }

        @Override
        public void serviceStop() {
            tearDown();
        }

        @Override
        public void setSystemProxyEnabled(boolean enabled) {
            // A phone has no system proxy to set; Android routes through the TUN instead.
        }

        @Override
        public void writeDebugMessage(String message) {
            Log.i(TAG, "engine: " + message);
        }

        @Override
        public void triggerNativeCrash() {
            // Refused on purpose. The binding exposes it for its own diagnostics, and Colgram
            // has no reason to hand a user's tunnel a way to abort the process.
        }
    }
}
