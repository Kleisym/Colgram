package org.colgram.singbox;

import android.app.Notification;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.VpnService;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.util.Log;

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
    private static final String EXTRA_GENERATION = "generation";
    private static long controlGeneration;
    private static String controlProfile;
    private static String controlState = "stopped";
    private static AppProfile pendingAppProfile;

    private CommandServer server;
    private ColgramPlatformInterface platform;
    private String profilePath;
    private AppProfile appProfile;
    private static volatile ColgramVpnService runningService;
    private AutoCloseable localBaseUplink;
    private long generation = -1L;
    private volatile boolean tunEstablished;
    private final Handler main = new Handler(Looper.getMainLooper());

    public static synchronized String status(String profilePath) {
        return profilePath != null && profilePath.equals(controlProfile) ? controlState : "stopped";
    }

    public static synchronized String status(String profilePath, long generation) {
        return generation == controlGeneration ? status(profilePath) : "stopped";
    }

    /**
     * Credentials and listener state travel only through this process, not a profile file
     * or Intent extra. The reservation must never displace an existing owned tunnel.
     */
    public static synchronized long startAppProfile(Context context, String profileId, String json,
            java.util.function.Consumer<VpnService> attach, Runnable close) {
        if (!"colgram-call-socks".equals(profileId) && !"colgram-call-dpi".equals(profileId)) {
            throw new IllegalArgumentException("Unknown app-only VPN profile");
        }
        if (json == null || json.isEmpty()) throw new IllegalArgumentException("Missing VPN profile");
        if (!"stopped".equals(controlState) && !"failed".equals(controlState)) {
            throw new IllegalStateException("Another Colgram VPN profile is active");
        }
        return requestStart(context, profileId, new AppProfile(json, attach, close));
    }

    public static synchronized void start(Context context, String profilePath) {
        if (profilePath == null || profilePath.isEmpty()) throw new IllegalArgumentException("Missing VPN profile");
        requestStart(context, profilePath, null);
    }

    private static long requestStart(Context context, String profilePath, AppProfile profile) {
        if (pendingAppProfile != null) pendingAppProfile.close();
        pendingAppProfile = profile;
        Intent intent = new Intent(context, ColgramVpnService.class);
        intent.setAction(ACTION_START);
        intent.putExtra("profile", profilePath);
        intent.putExtra(EXTRA_GENERATION, ++controlGeneration);
        controlProfile = profilePath;
        controlState = "starting";
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent);
            else context.startService(intent);
        } catch (RuntimeException failure) {
            controlState = "failed";
            if (pendingAppProfile != null) {
                pendingAppProfile.close();
                pendingAppProfile = null;
            }
            throw failure;
        }
        return controlGeneration;
    }

    public static synchronized void stop(Context context) {
        ++controlGeneration;
        controlState = "stopping";
        if (pendingAppProfile != null) {
            pendingAppProfile.close();
            pendingAppProfile = null;
        }
        ColgramVpnService target = runningService;
        if (target != null) {
            long requested = controlGeneration;
            new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> target.stopOwnedGeneration(requested));
        } else if (!context.stopService(new Intent(context, ColgramVpnService.class))) controlState = "stopped";
    }

    public static synchronized void stopProfile(Context context, String profilePath) {
        if (profilePath != null && profilePath.equals(controlProfile)) stop(context);
    }

    public static synchronized void stopProfile(Context context, String profilePath, long generation) {
        if (generation == controlGeneration) stopProfile(context, profilePath);
    }

    private void stopOwnedGeneration(long requested) {
        synchronized (ColgramVpnService.class) {
            if (requested != controlGeneration || runningService != this) return;
        }
        // Android keeps the VPN service bound until its TUN closes. Waiting for onDestroy
        // to close that descriptor makes stopService and the binding wait on one another.
        releaseResources();
        synchronized (ColgramVpnService.class) {
            if (requested != controlGeneration) return; // A newer start is already queued.
            controlState = "stopped";
            stopSelf();
        }
    }

    private static final class AppProfile {
        final String json;
        final java.util.function.Consumer<VpnService> attach;
        final Runnable cleanup;

        AppProfile(String json, java.util.function.Consumer<VpnService> attach, Runnable cleanup) {
            this.json = json;
            this.attach = attach;
            this.cleanup = cleanup;
        }

        void close() {
            if (cleanup != null) cleanup.run();
        }
    }

    private boolean ownsControl() {
        synchronized (ColgramVpnService.class) {
            return generation == controlGeneration;
        }
    }

    private void publishState(String state) {
        synchronized (ColgramVpnService.class) {
            if (generation == controlGeneration) controlState = state;
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        runningService = this;
        String action = intent == null ? null : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            // Compatibility with the legacy WARP bridge. A delayed WARP stop must not
            // close a user's selected subscription, which lives at a different path.
            if (profilePath != null && profilePath.endsWith("/colgram-warp.json")) {
                synchronized (ColgramVpnService.class) {
                    if (generation == controlGeneration) stopProfile(this, profilePath);
                }
            } else if (profilePath == null) {
                stopSelf(startId);
            }
            return START_NOT_STICKY;
        }
        if (!ACTION_START.equals(action)) {
            stopSelf(startId);
            return START_NOT_STICKY;
        }
        // Satisfy the foreground start deadline even if a newer request invalidated this one.
        startForegroundCompat();
        long requestedGeneration = intent.getLongExtra(EXTRA_GENERATION, -1L);
        String path = intent.getStringExtra("profile");
        synchronized (ColgramVpnService.class) {
            if (requestedGeneration != controlGeneration || !"starting".equals(controlState)
                    || path == null || !path.equals(controlProfile)) {
                if (server == null) stopSelf(startId);
                return START_NOT_STICKY;
            }
            if (appProfile != null) appProfile.close();
            generation = requestedGeneration;
            profilePath = path;
            appProfile = pendingAppProfile;
            pendingAppProfile = null;
        }
        startProfile(startId);
        // Only an explicit current profile request starts this service; never resurrect an
        // older route after process death or after switching back to Direct.
        return START_NOT_STICKY;
    }

    private void startProfile(int startId) {
        try {
            if (appProfile != null && appProfile.attach != null) appProfile.attach.accept(this);
            bringUp();
            if (!ownsControl()) {
                releaseResources();
                if (startId >= 0) stopSelf(startId);
                return;
            }
            if (!tunEstablished) throw new IllegalStateException("Android did not establish a VPN interface");
            publishState("running");
            startForegroundCompat();
        } catch (Throwable t) {
            // Engine errors may contain profile configuration (provider secrets).
            Log.e(TAG, "tunnel start failed: " + t.getClass().getSimpleName());
            publishState("failed");
            new ColgramNotification(this).fail("Проверьте выбранный узел и разрешение Android VPN.");
            releaseResources();
            if (startId >= 0) stopSelf(startId);
            else stopSelf();
        }
    }

    private void bringUp() throws Exception {
        // The engine has to be set up before it is asked to do anything. Libbox.touch() looks like
        // it does this - it is called from every generated class's static initialiser, which is
        // where that impression comes from - but in this binding it is an EMPTY method:
        //
        //     public static void touch() { }
        //
        // So the service was starting an engine that had never been given a working directory, a
        // temp path or a platform context, and the first real call walked into a nil. Measured on
        // the device, with the Go runtime naming it:
        //
        //   panic: runtime error: invalid memory address or nil pointer dereference
        //   libbox.(*CommandServer).StartOrReloadService ... command_server.go:221
        //
        // setup() is the call that actually initialises it, and it must happen exactly once per
        // process - the engine keeps global state, so a second setup is not merely redundant.
        ensureEngineSetUp(this);
        if (platform == null) {
            platform = new ColgramPlatformInterface(this);
            platform.setConfigurator(this);
        }
        boolean createServer = server == null;
        if (createServer) {
            server = new CommandServer(new EngineHandler(), platform);
        }
        // start() before startOrReloadService(). The Go side keeps the running service behind the
        // server, and startOrReloadService reaches straight for it: with no start() in front, the
        // first call dereferences a nil it never created. Measured on the device, twice, as
        // "panic: invalid memory address or nil pointer dereference" inside
        // CommandServer.StartOrReloadService at command_server.go:221 - which survived both the
        // engine setup and passing real OverrideOptions, so neither was the missing piece.
        if (createServer) server.start();
        // A real OverrideOptions rather than null. The Go side reads fields off the override
        // argument without a nil check - measured on the device, a nil there panics with an
        // invalid memory address inside CommandServer.StartOrReloadService at
        // command_server.go:221, and takes the whole process with it. The Java class has no
        // setters, so a default-constructed instance is the only way to say "no overrides" from
        // this side, and its defaults are exactly that.
        // startOrReloadService takes the profile CONTENT, not a path to it. Handed a path it hands
        // that path straight to the JSON decoder, which names the first character it cannot read:
        //
        //   start or reload service: decode config: invalid character '/' looking for beginning of
        //   comment: row 1, column 2
        //
        // The leading slash of "/data/user/0/..." is exactly that character and exactly that
        // position, so the engine never saw the profile at all - and the tunnel reported itself up
        // anyway, because the flag is set by the caller. Measured on the device against the real
        // engine, on every single start.
        tunEstablished = false;
        String config = appProfile == null ? readProfile(profilePath) : appProfile.json;
        if (appProfile == null) {
            // Reflection avoids reversing the core -> VPN module dependency.
            Class<?> base = Class.forName("org.colgram.core.ColgramLocalBaseUplink");
            if (localBaseUplink != null) localBaseUplink.close();
            localBaseUplink = (AutoCloseable) base.getConstructor(android.net.VpnService.class).newInstance(this);
            config = (String) base.getMethod("route", String.class).invoke(localBaseUplink, config);
        }
        server.startOrReloadService(config, new io.nekohasekai.libbox.OverrideOptions());
    }

    /**
     * The profile's bytes, as text.
     *
     * <p>Read here rather than passed by path, because the engine parses what it is given as the
     * config itself. A missing or unreadable file is a start that fails with a reason, not one that
     * hands a filesystem path to a JSON decoder and reports a character offset in it.
     */
    private static String readProfile(String path) throws java.io.IOException {
        byte[] raw = java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(path));
        return new String(raw, java.nio.charset.StandardCharsets.UTF_8);
    }

    /**
     * Initialise the engine once per process.
     *
     * Guarded rather than done in a static initialiser because it needs a Context for the paths,
     * and because doing it twice is at best wasteful and at worst a second global state machine.
     */
    private static volatile boolean engineSetUp = false;

    private static synchronized void ensureEngineSetUp(android.content.Context context)
            throws Exception {
        if (engineSetUp) return;
        // Inside the app's own storage, not a hand-built path under /data. The engine writes its
        // working state here, and a path the app cannot create is a start that dies one layer deeper
        // - measured on the device as:
        //   could not create the engine working directory /data/org.colgram.messenger/libbox
        //   go.Universe$proxyerror: open .../work/CrashReport-colgram.log: no such file or directory
        // and then the tunnel reported itself up with nothing behind it, because the flag is set by
        // the caller rather than by the engine having started.
        //
        // /data/<something> is writable only by the app that owns <something>. This package is
        // org.colgram.messenger, so its private directory is already that - but /data/org.colgram.
        // messenger is not a path an app may create, and getFilesDir() is both correct by
        // construction and readable by the engine under the same uid.
        java.io.File base = new java.io.File(context.getFilesDir(), "libbox");
        // The engine writes its working state here; a path it cannot create is a start that dies
        // with the same nil dereference one layer deeper, so it is created explicitly.
        if (!base.exists() && !base.mkdirs()) {
            Log.w(TAG, "could not create the engine working directory " + base);
        }
        java.io.File temp = new java.io.File(base, "tmp");
        if (!temp.exists() && !temp.mkdirs()) {
            Log.w(TAG, "could not create the engine temp directory " + temp);
        }
        io.nekohasekai.libbox.SetupOptions options = new io.nekohasekai.libbox.SetupOptions();
        options.setBasePath(base.getAbsolutePath());
        options.setWorkingPath(new java.io.File(base, "work").getAbsolutePath());
        options.setTempPath(temp.getAbsolutePath());
        // A stable identity in the trace, so a bug report says which app sent it.
        options.setCrashReportSource("colgram");
        options.setAppVersion("colgram");
        options.setAppMarketingVersion("colgram");
        options.setDebug(false);
        io.nekohasekai.libbox.Libbox.setup(options);
        engineSetUp = true;
        Log.i(TAG, "the engine is initialised, working in " + base.getAbsolutePath());
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
                .setContentText(tunEstablished ? "Интерфейс туннеля запущен" : "Настройка интерфейса туннеля…")
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

    private void releaseResources() {
        tunEstablished = false;
        if (localBaseUplink != null) {
            try { localBaseUplink.close(); } catch (Exception ignored) {}
            localBaseUplink = null;
        }
        tunEstablished = false;
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
        if (platform != null) {
            platform.closeTun();
            platform = null;
        }
        if (appProfile != null) {
            appProfile.close();
            appProfile = null;
        }
        stopForeground(true);
    }

    @Override
    public void onDestroy() {
        if (runningService == this) runningService = null;
        releaseResources();
        synchronized (ColgramVpnService.class) {
            if ("stopping".equals(controlState)) controlState = "stopped";
            else if (!"failed".equals(controlState)) publishState("stopped");
        }
        super.onDestroy();
    }

    @Override
    public void onRevoke() {
        // The user revoked consent in system settings. Leaving a tunnel running after that would
        // be routing traffic the user has just said no to.
        Log.i(TAG, "consent revoked; taking the tunnel down");
        main.post(this::stopSelf);
        super.onRevoke();
    }

    // ---- what the engine asks for --------------------------------------------------------

    @Override
    public ParcelFileDescriptor open(TunOptions options) {
        if (options == null || !ownsControl()) return null;
        final boolean appOnly = appProfile != null;
        Builder builder = new Builder();
        builder.setSession(appOnly ? "Colgram · звонки" : "Colgram");
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
        // The routes the tunnel must NOT capture, handed to the operating system rather than kept
        // inside the engine.
        //
        // This is what keeps a tunnel from swallowing its own handshake. route_exclude_address on
        // the inbound tells the engine which traffic it should not expect from the interface, but on
        // Android the routing table is built here, from TunOptions - and these two iterators were
        // never read. So the exclusion existed in the profile, was accepted by the engine, and then
        // did nothing: the OS still handed every packet, including the engine's own WireGuard
        // packets to its endpoint, to the interface it was trying to build.
        //
        // Measured on the device: the tunnel started cleanly and the relay was reached by a bare
        // datagram while it was up, and became unreachable again the moment traffic existed to carry
        // (SocketTimeoutException: the device does not reach the relay). The endpoint has to leave
        // by the physical interface, and on Android that is Builder.excludeRoute(IpPrefix) - read
        // off the platform stub, because there is no addDisallowedRoute on VpnService.Builder and
        // compiling against one fails the build rather than misbehaving quietly at runtime.
        for (RoutePrefix prefix : drain(options.getInet4RouteExcludeAddress())) {
            if (prefix == null || prefix.address() == null) continue;
            excludeRoute(builder, prefix);
        }
        for (RoutePrefix prefix : drain(options.getInet6RouteExcludeAddress())) {
            if (prefix == null || prefix.address() == null) continue;
            excludeRoute(builder, prefix);
        }
        for (String server : drainStrings(options.getDNSServerAddress())) {
            if (server != null && !server.isEmpty()) builder.addDnsServer(server);
        }
        try {
            if (appOnly) {
                // Refuse startup if Android does not accept the scope; never fail open
                // into a whole-device VPN or trust includes from the JSON.
                builder.addAllowedApplication(getPackageName());
            } else {
                for (String name : drainStrings(options.getExcludePackage())) applyPackage(builder, name, false);
                for (String name : drainStrings(options.getIncludePackage())) applyPackage(builder, name, true);
            }
            ParcelFileDescriptor descriptor = builder.establish();
            if (descriptor == null) {
                Log.e(TAG, "establish() returned no descriptor");
            } else {
                if (!ownsControl()) {
                    descriptor.close();
                    return null;
                }
                tunEstablished = true;
                // The descriptor existing and the interface existing are different facts, and only
                // the second one is a tunnel. Measured on the device: the tunnel started with no
                // error, and no tun interface existed afterwards - not colgram0, not tun0, nothing.
                // So the outcome is stated here rather than left to be inferred from the absence of
                // a later error, because a silent tun is the whole failure mode this exists to end.
                Log.i(TAG, "establish() returned a descriptor, fd "
                        + descriptor.getFd());
            }
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

    /**
     * One route the tunnel must not capture.
     *
     * <p>{@code Builder.excludeRoute(IpPrefix)} takes an {@code IpPrefix} rather than a
     * (String, int) pair, so the prefix is built here. A failure is logged and skipped rather than
     * fatal: an exclusion the platform refuses costs this tunnel its endpoint, and a tunnel that
     * refuses to start costs the user everything. Either way it is said out loud, because the
     * alternative is a tunnel that looks up and carries nothing.
     */
    private static void excludeRoute(Builder builder, RoutePrefix prefix) {
        try {
            java.net.InetAddress address = java.net.InetAddress.getByName(prefix.address());
            builder.excludeRoute(new android.net.IpPrefix(address, prefix.prefix()));
            Log.i(TAG, "the endpoint " + prefix.address() + "/" + prefix.prefix()
                    + " stays off the tunnel, so the handshake has a way out");
        } catch (Throwable t) {
            Log.e(TAG, "cannot exclude route " + prefix.address() + "/" + prefix.prefix()
                    + "; the tunnel will swallow its own handshake", t);
        }
    }

    @Override
    public boolean hasWholeDeviceConsent() {
        return true;
    }

    @Override
    public boolean protectOutboundSocket(int fd) {
        return protect(fd);
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
    private final class EngineHandler implements CommandServerHandler {
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
            main.post(() -> {
                if (server == null || !ownsControl()) return;
                publishState("starting");
                startProfile(-1);
            });
        }

        @Override
        public void serviceStop() {
            main.post(() -> {
                if (ownsControl()) stopSelf();
            });
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
