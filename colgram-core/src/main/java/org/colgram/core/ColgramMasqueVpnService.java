package org.colgram.core;

import android.content.Context;
import android.content.Intent;
import android.net.VpnService;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.ResultReceiver;
import android.util.Log;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;

/** Owns the only MASQUE runtime, its encrypted carrier and both TUN directions. */
public final class ColgramMasqueVpnService extends VpnService {
    private static final String TAG = "ColgramMasqueVpn";
    private static final String ACTION_START = "org.colgram.core.masque.START";
    private static final String ACTION_STOP = "org.colgram.core.masque.STOP";
    private static final String CHANNEL = "colgram_warp";
    private static final int FOREGROUND_ID = 0x4302;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private static final java.util.concurrent.atomic.AtomicLong OWNERS = new java.util.concurrent.atomic.AtomicLong();
    private final long nativeOwner = OWNERS.incrementAndGet();
    private final java.util.concurrent.atomic.AtomicBoolean cleanupScheduled = new java.util.concurrent.atomic.AtomicBoolean();
    private volatile ParcelFileDescriptor tun;
    private volatile Thread worker;
    private volatile Thread receiver;
    private volatile boolean running;
    private volatile long generation;
    private volatile long packetsHandled, repliesWritten;
    private volatile String warpVerdict;
    private volatile String lastTrace = "";
    private volatile String startupStage;
    private volatile ResultReceiver statusReceiver;
    private final Runnable heartbeat = new Runnable() {
        @Override public void run() {
            if (!running) return;
            String stage = ColgramMasqueNative.sessionProgress();
            if (stage == null || stage.startsWith("failed") || stage.startsWith("none")) {
                fail("Соединение WARP прервано: " + stage);
                return;
            }
            report(ColgramWarpState.UP, null);
            handler.postDelayed(this, 10_000L);
        }
    };

    public static Intent start(Context context, String bind, String edge) {
        return start(context, bind, edge, null);
    }

    public static Intent start(Context context, String bind, String edge, String socks) {
        return start(context, bind, edge, socks, null);
    }

    public static Intent start(Context context, String bind, String edge, String socks, ResultReceiver receiver) {
        Intent consent = VpnService.prepare(context);
        if (consent != null) return consent;
        Intent intent = new Intent(context, ColgramMasqueVpnService.class).setAction(ACTION_START);
        intent.putExtra("bind", bind).putExtra("edge", edge).putExtra("socks", socks);
        if (receiver != null) intent.putExtra("status_receiver", receiver);
        android.content.ComponentName component;
        if (android.os.Build.VERSION.SDK_INT >= 26) component = context.startForegroundService(intent);
        else component = context.startService(intent);
        if (component == null) throw new IllegalStateException("Сервис WARP отсутствует в сборке");
        return null;
    }

    public static void stop(Context context) {
        if (context != null) context.stopService(new Intent(context, ColgramMasqueVpnService.class));
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || ACTION_STOP.equals(intent.getAction())) {
            stopTunnel(); stopSelf(); return START_NOT_STICKY;
        }
        if (!ACTION_START.equals(intent.getAction())) return START_NOT_STICKY;
        statusReceiver = intent.getParcelableExtra("status_receiver");
        if (cleanupScheduled.get()) { report(ColgramWarpState.FAILED, "Предыдущий WARP ещё завершает работу; повторите подключение"); stopSelf(startId); return START_NOT_STICKY; }
        if (running && tun != null) { report(ColgramWarpState.UP, null); return START_NOT_STICKY; }
        if (worker != null && worker.isAlive()) return START_NOT_STICKY;
        final long attempt = ++generation;
        final String bind = intent.getStringExtra("bind");
        final String edge = intent.getStringExtra("edge");
        final String socks = intent.getStringExtra("socks");
        try {
            foreground("WARP: подключение и проверка защищённого канала");
        } catch (RuntimeException error) {
            fail("Запуск сервиса WARP: " + error.getMessage());
            return START_NOT_STICKY;
        }
        report(ColgramWarpState.STARTING, null, "enrolling");
        worker = new Thread(() -> bootstrap(attempt, bind, edge, socks), "colgram-masque-start");
        worker.setDaemon(true); worker.start();
        // Native starts on its worker. Relay only progress from this attempt, and
        // stop polling as soon as the service owns an established TUN.
        handler.postDelayed(new Runnable() {
            @Override public void run() {
                if (generation != attempt || running || worker == null || !worker.isAlive()) return;
                if ("enrolling".equals(startupStage) && ColgramMasqueNative.isLoadedInThisProcess()) {
                    String progress = ColgramMasqueNative.sessionProgress();
                    if (progress != null && progress.startsWith("dialing")) {
                        report(ColgramWarpState.STARTING, null, "dialing");
                    }
                }
                if ("enrolling".equals(startupStage)) handler.postDelayed(this, 350L);
            }
        }, 350L);
        return START_NOT_STICKY; // A killed process must not silently restore a user-disabled route.
    }

    private boolean current(long attempt) { return generation == attempt && !Thread.currentThread().isInterrupted(); }

    private void bootstrap(long attempt, String bind, String edge, String socks) {
        try {
            if (VpnService.prepare(this) != null) throw new IllegalStateException("Нужно разрешение Android на VPN");
            if (!ColgramMasqueNative.isAvailable()) throw new IllegalStateException(ColgramMasqueNative.unavailableReason());
            // Bootstrap happens BEFORE installing any routes. The carrier's existing
            // socket stays on the physical network, without broad resolver exclusions.
            ColgramMasqueNative.setVpnService(this);
            ColgramMasqueNative.setSocksFront(socks);
            ColgramMasqueNative.openSessionOwned(bind, edge, nativeOwner);
            if (!current(attempt)) return;
            String address = ColgramMasqueNative.tunnelAddress();
            if (address == null || address.isEmpty()) throw new IllegalStateException(nativeFailure());
            InetAddress ip = InetAddress.getByName(address);
            if (!(ip instanceof Inet4Address)) throw new IllegalStateException("WARP не выдал IPv4-адрес");
            report(ColgramWarpState.STARTING, null, "trace");
            String trace = ColgramMasqueNative.trace(); // The SAME session, not a second measurement.
            if (!current(attempt)) return;
            if (!ColgramMasqueNative.traceIsWarpOn(trace)) {
                String detail = ColgramMasqueNative.lastError();
                throw new IllegalStateException(detail == null
                        ? "Cloudflare не подтвердил, что трафик идёт через WARP"
                        : describeStartupFailure(detail));
            }
            lastTrace = trace;
            Builder builder = new Builder().setSession("Colgram WARP").setMtu(1280)
                    .addAddress(ip, 32).addRoute("0.0.0.0", 0).addDnsServer("1.1.1.1").setBlocking(true);
            // No IPv6 address/allowFamily: Android blocks IPv6 for this IPv4-only
            // transport rather than leaking it over the physical network.
            ParcelFileDescriptor descriptor = builder.establish();
            if (descriptor == null) throw new IllegalStateException("Android не создал интерфейс VPN");
            synchronized (this) {
                if (!current(attempt)) { descriptor.close(); return; }
                tun = descriptor;
                running = true;
                warpVerdict = "warp=on";
            }
            receiver = new Thread(() -> receive(attempt, descriptor), "colgram-masque-receive");
            receiver.setDaemon(true); receiver.start();
            report(ColgramWarpState.UP, null);
            handler.post(() -> {
                if (generation == attempt && running) {
                    foreground("WARP: защищённый канал подключён");
                    handler.removeCallbacks(heartbeat); handler.postDelayed(heartbeat, 10_000L);
                }
            });
            pump(attempt, descriptor);
        } catch (Throwable error) {
            if (current(attempt)) fail(error.getMessage() == null ? "Ошибка запуска WARP" : error.getMessage());
        } finally {
            if (generation == attempt) {
                stopTunnel();
                handler.post(this::stopSelf);
            }
        }
    }

    private static String nativeFailure() {
        String detail = ColgramMasqueNative.lastError();
        return detail == null ? "Транспорт WARP не запущен" : describeStartupFailure(detail);
    }

    static String describeStartupFailure(String detail) {
        String[] parts = detail.split(":", 3);
        if (parts.length != 3) return "Транспорт WARP не запущен";
        String stage;
        switch (parts[0]) {
            case "dns": stage = "DNS для API Cloudflare"; break;
            case "register": stage = "регистрация в Cloudflare"; break;
            case "enrol": stage = "регистрация ключа WARP"; break;
            case "edge": stage = "подключение к шлюзу Cloudflare"; break;
            case "connect": stage = "открытие туннеля WARP"; break;
            case "trace-dns": stage = "DNS для проверки WARP"; break;
            case "trace": stage = "проверка трафика WARP"; break;
            default: return "Транспорт WARP не запущен";
        }
        String reason;
        switch (parts[1]) {
            case "timeout": reason = "нет ответа, время ожидания истекло"; break;
            case "tls": reason = "сертификат не прошёл проверку"; break;
            case "http":
                reason = parts[2].matches("[1-5][0-9][0-9]") ? "HTTP " + parts[2] : "ошибка HTTP";
                break;
            case "invalid": reason = "некорректный ответ"; break;
            case "closed": reason = "соединение прервано"; break;
            case "cancelled": reason = "отменено"; break;
            default: reason = "ошибка сети";
        }
        return stage + " — " + reason;
    }

    private void pump(long attempt, ParcelFileDescriptor descriptor) throws Exception {
        try (FileInputStream in = new FileInputStream(descriptor.getFileDescriptor())) {
            byte[] buffer = new byte[32767];
            while (running && current(attempt)) {
                int read = in.read(buffer);
                if (read < 0) break;
                if (read == 0) { Thread.sleep(10L); continue; }
                byte[] packet = java.util.Arrays.copyOf(buffer, read);
                ColgramMasqueNative.exchangeIpPacket(packet, null, null);
                packetsHandled++;
            }
        }
    }

    private void receive(long attempt, ParcelFileDescriptor descriptor) {
        try (FileOutputStream out = new FileOutputStream(descriptor.getFileDescriptor())) {
            while (running && current(attempt)) {
                byte[] packet = ColgramMasqueNative.receiveIpPacket();
                if (packet == null || packet.length == 0) continue; // Native wait is bounded, not a busy loop.
                out.write(packet);
                repliesWritten++;
            }
        } catch (Throwable error) {
            if (running && current(attempt)) fail("Приём WARP: " + error.getMessage());
        }
    }

    private void report(int code, String error) {
        report(code, error, null);
    }

    private synchronized void report(int code, String error, String stage) {
        // The main-thread poll can finish just after the worker enters trace
        // or fails. Never let that observation overwrite the newer stage.
        if ("dialing".equals(stage) && !"enrolling".equals(startupStage)) return;
        startupStage = code == ColgramWarpState.STARTING ? stage : null;
        ResultReceiver callback = statusReceiver;
        if (callback == null) return;
        Bundle data = new Bundle();
        data.putString("trace", lastTrace);
        data.putString("error", error);
        if (stage != null) data.putString("stage", stage);
        callback.send(code, data);
    }

    private void fail(String error) {
        Log.e(TAG, error);
        report(ColgramWarpState.FAILED, error);
        stopTunnel(); handler.post(this::stopSelf);
    }

    private synchronized void stopTunnel() {
        if (!cleanupScheduled.compareAndSet(false, true)) return;
        ++generation; running = false;
        handler.removeCallbacks(heartbeat);
        ParcelFileDescriptor descriptor = tun; tun = null;
        if (descriptor != null) try { descriptor.close(); } catch (Exception ignored) { }
        if (worker != null) worker.interrupt();
        if (receiver != null) receiver.interrupt();
        worker = null; receiver = null;
        report(ColgramWarpState.STOPPED, null);
        // Only this isolated process owns the native library. Teardown never loads
        // a Go runtime into Telegram's UI and never blocks Android's lifecycle thread.
        if (ColgramMasqueNative.isLoadedInThisProcess()) {
            Thread cleanup = new Thread(() -> ColgramMasqueNative.closeSessionOwned(nativeOwner), "colgram-masque-close");
            cleanup.setDaemon(true); cleanup.start();
        }
        stopForeground(true);
    }

    private void foreground(String text) {
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            android.app.NotificationManager manager = (android.app.NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (manager != null && manager.getNotificationChannel(CHANNEL) == null) {
                manager.createNotificationChannel(new android.app.NotificationChannel(CHANNEL, "Colgram WARP", android.app.NotificationManager.IMPORTANCE_LOW));
            }
        }
        android.app.Notification.Builder builder = android.os.Build.VERSION.SDK_INT >= 26
                ? new android.app.Notification.Builder(this, CHANNEL) : new android.app.Notification.Builder(this);
        android.app.Notification notification = builder.setContentTitle("Colgram").setContentText(text)
                .setSmallIcon(android.R.drawable.stat_notify_sync).setOngoing(true).setShowWhen(false).build();
        if (android.os.Build.VERSION.SDK_INT >= 34) startForeground(FOREGROUND_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        else startForeground(FOREGROUND_ID, notification);
    }

    @Override public void onDestroy() { stopTunnel(); super.onDestroy(); }
    @Override public void onRevoke() { stopTunnel(); stopSelf(); super.onRevoke(); }
}
