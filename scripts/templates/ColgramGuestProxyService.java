package org.telegram.tgnet;

import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.Process;
import android.os.RemoteException;
import android.os.SystemClock;
import org.colgram.core.ColgramGuestProxyProbe;
import org.colgram.core.ColgramProcess;
import org.colgram.core.ColgramProxyPolicy;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.NativeLoader;
import java.io.File;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReferenceArray;

/** Bounded anonymous MTProto lanes; each owns a distinct cache, account slot and RPC. */
public final class ColgramGuestProxyService extends Service {
    private static final long RPC_TIMEOUT_MS = 9_500L;
    private static final long IDLE_STOP_MS = 25_000L;
    private static final AtomicInteger TOKENS = new AtomicInteger(600_000);
    private static final boolean[] initialized = new boolean[ColgramGuestProxyProbe.MAX_PARALLEL];
    private static volatile boolean probeProcess;
    private static volatile ColgramGuestProxyService service;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Messenger inbound = new Messenger(new Handler(Looper.getMainLooper(), this::receive));
    private final AtomicReferenceArray<Probe> probes = new AtomicReferenceArray<>(ColgramGuestProxyProbe.MAX_PARALLEL);
    private volatile boolean bound;
    private final Runnable killSelf = () -> {
        if (!bound && !hasActiveProbes() && probeProcess && ColgramProcess.isGuestProxyProcess(this)) {
            Process.killProcess(Process.myPid());
        }
    };
    private final Runnable idleStop = () -> {
        if (bound || hasActiveProbes()) return;
        stopSelf();
        main.postDelayed(killSelf, 1_000L);
    };

    private boolean hasActiveProbes() {
        for (int i = 0; i < probes.length(); i++) {
            if (probes.get(i) != null) return true;
        }
        return false;
    }

    private void cancelIdleStop() {
        main.removeCallbacks(idleStop);
        main.removeCallbacks(killSelf);
    }

    private void scheduleIdleStop() {
        cancelIdleStop();
        if (!bound && !hasActiveProbes()) {
            main.postDelayed(idleStop, IDLE_STOP_MS);
        }
    }

    public static void enterProbeProcess() { probeProcess = true; }
    public static boolean inProbeProcess() { return probeProcess; }
    @Override public IBinder onBind(Intent intent) {
        if (!probeProcess || !ColgramProcess.isGuestProxyProcess(this)) return null;
        bound = true;
        cancelIdleStop();
        return inbound.getBinder();
    }

    @Override public void onRebind(Intent intent) {
        if (!probeProcess || !ColgramProcess.isGuestProxyProcess(this)) return;
        bound = true;
        cancelIdleStop();
    }

    @Override public boolean onUnbind(Intent intent) {
        bound = false;
        scheduleIdleStop();
        // Keep rebind active so multi-wave scans reuse the live process without racing stopSelf/killSelf.
        return true;
    }

    private boolean receive(Message message) {
        if (message.sendingUid != Process.myUid() || !probeProcess) return false;
        Bundle target = message.getData();
        int account = target.getInt("lane", -1);
        long ticket = target.getLong("ticket", 0L);
        if (account < 0 || account >= ColgramGuestProxyProbe.MAX_PARALLEL || ticket == 0L) return false;
        if (message.what == ColgramGuestProxyProbe.CANCEL) {
            Probe existing = probes.get(account);
            if (existing != null && existing.ticket == ticket) existing.finish(-1L, "TG_PROBE_CANCELLED");
            return true;
        }
        if (message.what != ColgramGuestProxyProbe.CHECK || message.replyTo == null) return false;
        cancelIdleStop();
        if (probes.get(account) != null) { deliver(message.replyTo, -1L, "TG_PROBE_BUSY"); return true; }
        Probe probe = new Probe(account, ticket, message.replyTo);
        probes.set(account, probe);
        service = this;
        probe.resolve(target);
        return true;
    }

    private final class Probe {
        final int account;
        final long ticket;
        final Messenger reply;
        final long started = SystemClock.elapsedRealtime();
        final Runnable timeout = () -> finish(-1L, ColgramGuestProxyProbe.TIMEOUT);
        volatile int token;
        Probe(int account, long ticket, Messenger reply) {
            this.account = account; this.ticket = ticket; this.reply = reply;
        }
        boolean current() { return probes.get(account) == this; }
        void resolve(Bundle target) {
            main.postDelayed(timeout, RPC_TIMEOUT_MS);
            String host = target.getString("host", "");
            int port = target.getInt("port");
            if (host.isEmpty() || host.length() > 255 || port < 1 || port > 65535) {
                finish(-1L, "INVALID_PROXY"); return;
            }
            String user = target.getString("user", ""), pass = target.getString("pass", ""), secret = target.getString("secret", "");
            boolean publicOnly = target.getBoolean("publicOnly");
            Thread resolver = new Thread(() -> {
                try {
                    InetAddress chosen = null;
                    for (InetAddress address : InetAddress.getAllByName(host)) {
                        if (publicOnly && !ColgramProxyPolicy.isPublicAddress(address)) continue;
                        if (chosen == null) chosen = address;
                        if (address instanceof Inet4Address) { chosen = address; break; }
                    }
                    if (chosen == null) { main.post(() -> finish(-1L, "NON_PUBLIC_PROXY")); return; }
                    String address = chosen.getHostAddress();
                    main.post(() -> { if (current()) open(address, port, user, pass, secret); });
                } catch (Exception error) { main.post(() -> finish(-1L, "PROXY_DNS_FAILED")); }
            }, "colgram-probe-resolve-" + account);
            resolver.setDaemon(true); resolver.start();
        }
        void open(String address, int port, String user, String pass, String secret) {
            try {
                android.util.Log.d("ColgramGuestProbe", "open account=" + account + " proxy=" + address + ":" + port + " init=" + initialized[account]);
                if (!initialized[account]) {
                    NativeLoader.loadPackagedForGuestProbe();
                    ConnectionsManager.native_setJava(false);
                    File cache = new File(getCacheDir(), account == 0 ? "guest-route-probe" : "guest-route-probe-" + account);
                    if (!cache.isDirectory() && !cache.mkdirs()) { finish(-1L, "TG_PROBE_UNAVAILABLE"); return; }
                    PackageInfo pkg = getPackageManager().getPackageInfo(getPackageName(), 0);
                    ConnectionsManager.native_init(account, pkg.versionCode, TLRPC.LAYER, BuildVars.APP_ID,
                            "Android", "SDK " + Build.VERSION.SDK_INT, pkg.versionName, "en", "en",
                            cache.getAbsolutePath(), "", "", "", "", getPackageName(), 0,
                            0L, false, false, false, 1, 0);
                    initialized[account] = true;
                }
                ConnectionsManager.native_setProxySettings(account, address, port,
                        user == null ? "" : user, pass == null ? "" : pass, secret == null ? "" : secret);
                ConnectionsManager.native_setNetworkAvailable(account, true, 1, false);
                ConnectionsManager.native_resumeNetwork(account, false);
                TLRPC.TL_help_getConfig request = new TLRPC.TL_help_getConfig();
                NativeByteBuffer buffer = new NativeByteBuffer(request.getObjectSize());
                request.serializeToStream(buffer);
                token = TOKENS.incrementAndGet();
                int flags = ConnectionsManager.RequestFlagEnableUnauthorized | ConnectionsManager.RequestFlagWithoutLogin;
                ConnectionsManager.native_sendRequest(account, buffer.address, flags,
                        ConnectionsManager.DEFAULT_DATACENTER_ID, ConnectionsManager.ConnectionTypeGeneric, true, token);
            } catch (Exception | LinkageError error) { finish(-1L, "TG_PROBE_UNAVAILABLE"); }
        }
        void finish(long time, String reason) {
            if (!probes.compareAndSet(account, this, null)) return;
            android.util.Log.d("ColgramGuestProbe", "finish account=" + account + " time=" + time + " reason=" + reason);
            main.removeCallbacks(timeout);
            int request = token; token = 0;
            if (initialized[account]) {
                ConnectionsManager.native_setNetworkAvailable(account, false, 1, false);
                if (request != 0) ConnectionsManager.native_cancelRequest(account, request, false);
                ConnectionsManager.native_pauseNetwork(account);
            }
            deliver(reply, time, reason);
            scheduleIdleStop();
        }
    }

    /** Called on tgnet's thread while the response buffer is still owned by the engine. */
    public static void onRequestComplete(int account, int request, long pointer) {
        ColgramGuestProxyService active = service;
        if (active == null || account < 0 || account >= ColgramGuestProxyProbe.MAX_PARALLEL) return;
        Probe probe = active.probes.get(account);
        if (probe == null || probe.token != request) return;
        boolean accepted = false;
        try {
            if (pointer != 0) {
                NativeByteBuffer response = NativeByteBuffer.wrap(pointer);
                response.reused = true;
                TLRPC.TL_config config = TLRPC.TL_config.TLdeserialize(response, response.readInt32(true), true);
                accepted = config != null && !config.dc_options.isEmpty();
            }
        } catch (RuntimeException ignored) { }
        boolean verified = accepted;
        active.main.post(() -> {
            if (!probe.current() || probe.token != request) return;
            probe.finish(verified ? Math.max(1L, SystemClock.elapsedRealtime() - probe.started) : -1L,
                    verified ? "TG_CONFIG_OK" : "TG_CONFIG_REJECTED");
        });
    }
    private static void deliver(Messenger messenger, long time, String reason) {
        Message result = Message.obtain(null, ColgramGuestProxyProbe.RESULT);
        Bundle verdict = new Bundle();
        verdict.putLong("time", time); verdict.putString("reason", reason); result.setData(verdict);
        try { messenger.send(result); } catch (RemoteException callerGone) { }
    }
    @Override public void onDestroy() {
        bound = false;
        cancelIdleStop();
        for (int account = 0; account < probes.length(); account++) {
            Probe probe = probes.get(account);
            if (probe != null) probe.finish(-1L, "TG_PROBE_DISCONNECTED");
        }
        if (service == this) service = null;
        super.onDestroy();
    }
}
