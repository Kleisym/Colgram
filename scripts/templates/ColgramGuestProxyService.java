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

/** Reuses the pinned MTProto engine with an anonymous cache and no live account state. */
public final class ColgramGuestProxyService extends Service {
    private static final int ACCOUNT = 0;
    private static final long RPC_TIMEOUT_MS = 8_000L;
    private static final AtomicInteger TOKENS = new AtomicInteger(600_000);
    private static volatile boolean probeProcess;
    private static volatile ColgramGuestProxyService service;
    private static boolean initialized;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Messenger inbound = new Messenger(new Handler(Looper.getMainLooper(), this::receive));
    private final Runnable timeout = () -> finish(-1, ColgramGuestProxyProbe.TIMEOUT);
    private Messenger reply;
    private volatile int token;
    private long started;

    /** Set before loading the library so its static callbacks cannot open the real account. */
    public static void enterProbeProcess() { probeProcess = true; }
    public static boolean inProbeProcess() { return probeProcess; }

    @Override public IBinder onBind(Intent intent) {
        return probeProcess && ColgramProcess.isGuestProxyProcess(this) ? inbound.getBinder() : null;
    }

    private boolean receive(Message message) {
        if (message.what != ColgramGuestProxyProbe.CHECK || message.replyTo == null
                || message.sendingUid != Process.myUid() || !probeProcess) return false;
        if (reply != null) {
            deliver(message.replyTo, -1, "TG_PROBE_BUSY");
            return true;
        }
        reply = message.replyTo;
        started = SystemClock.elapsedRealtime();
        service = this;
        main.postDelayed(timeout, RPC_TIMEOUT_MS);
        Bundle target = message.getData();
        final String host = target.getString("host", "");
        final int port = target.getInt("port");
        if (host.isEmpty() || host.length() > 255 || port < 1 || port > 65535) {
            finish(-1, "INVALID_PROXY");
            return true;
        }
        final Messenger request = reply;
        final String user = target.getString("user", "");
        final String pass = target.getString("pass", "");
        final String secret = target.getString("secret", "");
        final boolean publicOnly = target.getBoolean("publicOnly");
        Thread resolve = new Thread(() -> {
            try {
                InetAddress[] addresses = InetAddress.getAllByName(host);
                InetAddress chosen = null;
                for (InetAddress address : addresses) {
                    if (publicOnly && !ColgramProxyPolicy.isPublicAddress(address)) continue;
                    if (chosen == null) chosen = address;
                    if (address instanceof Inet4Address) {
                        chosen = address;
                        break;
                    }
                }
                if (chosen == null) {
                    main.post(() -> {
                        if (reply == request) finish(-1, "NON_PUBLIC_PROXY");
                    });
                    return;
                }
                String address = chosen.getHostAddress();
                main.post(() -> {
                    if (reply == request) open(address, port, user, pass, secret);
                });
            } catch (Exception failure) {
                main.post(() -> {
                    if (reply == request) finish(-1, "PROXY_DNS_FAILED");
                });
            }
        }, "colgram-probe-resolve");
        resolve.setDaemon(true);
        resolve.start();
        return true;
    }

    private void open(String address, int port, String user, String pass, String secret) {
        try {
            if (!initialized) {
                NativeLoader.loadPackagedForGuestProbe();
                ConnectionsManager.native_setJava(false);
                File cache = new File(getCacheDir(), "guest-route-probe");
                if (!cache.isDirectory() && !cache.mkdirs()) {
                    finish(-1, "TG_PROBE_UNAVAILABLE");
                    return;
                }
                PackageInfo pkg = getPackageManager().getPackageInfo(getPackageName(), 0);
                ConnectionsManager.native_init(ACCOUNT, pkg.versionCode, TLRPC.LAYER, BuildVars.APP_ID,
                        "Android", "SDK " + Build.VERSION.SDK_INT, pkg.versionName, "en", "en",
                        cache.getAbsolutePath(), "", "", "", "", getPackageName(), 0,
                        0L, false, false, false, 1, 0);
                initialized = true;
            }
            ConnectionsManager.native_setProxySettings(ACCOUNT, address, port,
                    user == null ? "" : user, pass == null ? "" : pass, secret == null ? "" : secret);
            ConnectionsManager.native_setNetworkAvailable(ACCOUNT, true, 1, false);
            ConnectionsManager.native_resumeNetwork(ACCOUNT, false);
            TLRPC.TL_help_getConfig request = new TLRPC.TL_help_getConfig();
            NativeByteBuffer buffer = new NativeByteBuffer(request.getObjectSize());
            request.serializeToStream(buffer);
            token = TOKENS.incrementAndGet();
            int flags = ConnectionsManager.RequestFlagEnableUnauthorized | ConnectionsManager.RequestFlagWithoutLogin;
            ConnectionsManager.native_sendRequest(ACCOUNT, buffer.address, flags,
                    ConnectionsManager.DEFAULT_DATACENTER_ID, ConnectionsManager.ConnectionTypeGeneric, true, token);
        } catch (Exception | LinkageError failure) {
            finish(-1, "TG_PROBE_UNAVAILABLE");
        }
    }

    /** Called on tgnet's thread while the response buffer still belongs to the engine. */
    public static void onRequestComplete(int account, int request, long pointer) {
        ColgramGuestProxyService active = service;
        if (active == null || account != ACCOUNT || active.token != request) return;
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
            if (active.token != request) return;
            active.finish(verified ? Math.max(1L, SystemClock.elapsedRealtime() - active.started) : -1L,
                    verified ? "TG_CONFIG_OK" : "TG_CONFIG_REJECTED");
        });
    }

    private static void deliver(Messenger messenger, long time, String reason) {
        if (messenger == null) return;
        Message reply = Message.obtain(null, ColgramGuestProxyProbe.RESULT);
        Bundle result = new Bundle();
        result.putLong("time", time);
        result.putString("reason", reason);
        reply.setData(result);
        try { messenger.send(reply); } catch (RemoteException callerGone) { }
    }

    private void finish(long time, String reason) {
        main.removeCallbacks(timeout);
        int request = token;
        token = 0;
        if (service == this) service = null;
        if (initialized) {
            // Leave the previous proxy installed in this *guest* native instance.
            // Disable network before ending the request; never let it retry directly.
            ConnectionsManager.native_setNetworkAvailable(ACCOUNT, false, 1, false);
            if (request != 0) ConnectionsManager.native_cancelRequest(ACCOUNT, request, false);
            ConnectionsManager.native_pauseNetwork(ACCOUNT);
        }
        Messenger caller = reply;
        reply = null;
        deliver(caller, time, reason);
    }

    @Override public void onDestroy() {
        if (reply != null) finish(-1, "TG_PROBE_DISCONNECTED");
        super.onDestroy();
    }
}
