package org.colgram.core;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;

/** Account-free pre-login verification; never changes the main application's proxy. */
public final class ColgramGuestProxyProbe {
    public static final long CHECK_TIMEOUT_MS = 11_000L;
    public static final int CHECK = 1;
    public static final int RESULT = 2;
    public static final int CANCEL = 3;
    public static final int MAX_PARALLEL = 5;
    public static final String TIMEOUT = "TG_CONFIG_TIMEOUT";
    private static final String SERVICE = "org.telegram.tgnet.ColgramGuestProxyService";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ColgramProbeLanes LANES = new ColgramProbeLanes(MAX_PARALLEL);

    private ColgramGuestProxyProbe() { }

    public interface Result { void complete(long timeMs, String reason); }

    /** Use the same transparent local base for verification and for the selected live route. */
    public static boolean checkThroughBase(Context context, String host, int port,
                                           String user, String pass, String secret, boolean publicOnly,
                                           Result result) {
        if (result == null || host == null) return false;
        if (!LANES.hasCapacity()) return false;
        // Public callers supply the numeric address pinned by their preflight; no DNS rebinding.
        if (publicOnly) {
            if (!(host.matches("[0-9.]+") || host.indexOf(':') >= 0)) return false;
            try {
                if (!ColgramProxyPolicy.isPublicAddress(java.net.InetAddress.getByName(host))) return false;
            } catch (java.net.UnknownHostException invalid) { return false; }
        }
        final ColgramLocalTcpFront front;
        try { front = new ColgramLocalTcpFront(host, port); }
        catch (java.io.IOException | IllegalArgumentException unavailable) { return false; }
        boolean started = check(context, "127.0.0.1", front.port(), user, pass, secret, false,
                (time, reason) -> {
                    try { result.complete(time, reason); }
                    finally { front.close(); }
                });
        if (!started) front.close();
        return started;
    }

    public static synchronized boolean check(Context context, String host, int port,
                                             String user, String pass, String secret, boolean publicOnly,
                                             Result result) {
        if (context == null || result == null || host == null || host.isEmpty()
                || host.length() > 255 || port < 1 || port > 65535) return false;
        ColgramProbeLanes.Ticket ticket = LANES.acquire();
        if (ticket == null) return false;
        Bundle target = new Bundle();
        target.putInt("lane", ticket.lane());
        target.putLong("ticket", ticket.id());
        target.putString("host", host);
        target.putInt("port", port);
        target.putString("user", user);
        target.putString("pass", pass);
        target.putString("secret", secret);
        target.putBoolean("publicOnly", publicOnly);
        Attempt attempt = new Attempt(context.getApplicationContext(), target, result, ticket);
        MAIN.post(attempt::bind);
        return true;
    }

    private static final class Attempt implements ServiceConnection {
        final Context app;
        final Bundle target;
        final Result result;
        final ColgramProbeLanes.Ticket ticket;
        final Runnable timeout = () -> finish(-1, TIMEOUT, true);
        final Messenger replies = new Messenger(new Handler(Looper.getMainLooper(), message -> {
            if (message.what != RESULT) return false;
            Bundle verdict = message.getData();
            finish(verdict.getLong("time", -1L), verdict.getString("reason", "TG_CONFIG_UNAVAILABLE"), false);
            return true;
        }));
        boolean finished;
        Messenger remoteMessenger;

        private static final Object BIND_LOCK = new Object();
        private static final long SHARED_UNBIND_IDLE_MS = 25_000L;
        private static Context sharedApp;
        private static Messenger sharedRemote;
        private static boolean sharedBound;
        private static boolean sharedConnecting;
        private static int sharedActiveAttempts;
        private static final java.util.List<Attempt> sharedWaiting = new java.util.ArrayList<>();
        private static final ServiceConnection SHARED_CONN = new ServiceConnection() {
            @Override public void onServiceConnected(ComponentName name, IBinder remote) {
                java.util.List<Attempt> ready;
                Messenger m = new Messenger(remote);
                synchronized (BIND_LOCK) {
                    sharedRemote = m;
                    sharedConnecting = false;
                    ready = new java.util.ArrayList<>(sharedWaiting);
                    sharedWaiting.clear();
                }
                for (Attempt a : ready) {
                    a.dispatchTo(m);
                }
            }
            @Override public void onServiceDisconnected(ComponentName name) {
                failWaiting("TG_PROBE_DISCONNECTED");
            }
            @Override public void onBindingDied(ComponentName name) {
                failWaiting("TG_PROBE_DISCONNECTED");
            }
            @Override public void onNullBinding(ComponentName name) {
                failWaiting("TG_PROBE_UNAVAILABLE");
            }
            private void failWaiting(String reason) {
                java.util.List<Attempt> lost;
                synchronized (BIND_LOCK) {
                    sharedRemote = null;
                    sharedConnecting = false;
                    sharedBound = false;
                    lost = new java.util.ArrayList<>(sharedWaiting);
                    sharedWaiting.clear();
                }
                for (Attempt a : lost) {
                    a.finish(-1, reason, false);
                }
            }
        };
        private static final Runnable sharedIdleUnbind = () -> {
            synchronized (BIND_LOCK) {
                if (sharedActiveAttempts > 0 || !sharedWaiting.isEmpty() || !sharedBound) return;
                if (sharedApp != null) {
                    try { sharedApp.unbindService(SHARED_CONN); } catch (Throwable ignored) {}
                }
                sharedBound = false;
                sharedConnecting = false;
                sharedRemote = null;
            }
        };

        Attempt(Context app, Bundle target, Result result, ColgramProbeLanes.Ticket ticket) {
            this.app = app;
            this.target = target;
            this.result = result;
            this.ticket = ticket;
        }

        void bind() {
            MAIN.postDelayed(timeout, CHECK_TIMEOUT_MS);
            MAIN.removeCallbacks(sharedIdleUnbind);
            Messenger existing = null;
            boolean needBind = false;
            synchronized (BIND_LOCK) {
                sharedApp = app.getApplicationContext();
                sharedActiveAttempts++;
                if (sharedRemote != null) {
                    existing = sharedRemote;
                } else {
                    sharedWaiting.add(this);
                    if (!sharedConnecting) {
                        sharedConnecting = true;
                        needBind = true;
                    }
                }
            }
            if (existing != null) {
                dispatchTo(existing);
                return;
            }
            if (needBind) {
                Intent intent = new Intent().setClassName(app, SERVICE);
                try {
                    boolean ok = app.getApplicationContext().bindService(intent, SHARED_CONN, Context.BIND_AUTO_CREATE);
                    synchronized (BIND_LOCK) {
                        sharedBound = ok;
                        if (!ok) sharedConnecting = false;
                    }
                    if (!ok) {
                        SHARED_CONN.onNullBinding(null);
                    }
                } catch (RuntimeException unavailable) {
                    synchronized (BIND_LOCK) {
                        sharedBound = false;
                        sharedConnecting = false;
                    }
                    SHARED_CONN.onNullBinding(null);
                }
            }
        }

        void dispatchTo(Messenger m) {
            if (finished) return;
            Message request = Message.obtain(null, CHECK);
            request.setData(target);
            request.replyTo = replies;
            try {
                remoteMessenger = m;
                m.send(request);
            } catch (RemoteException disconnected) {
                synchronized (BIND_LOCK) {
                    if (sharedRemote == m) {
                        sharedRemote = null;
                        sharedBound = false;
                    }
                }
                finish(-1, "TG_PROBE_DISCONNECTED", false);
            }
        }

        @Override public void onServiceConnected(ComponentName name, IBinder remote) {}
        @Override public void onServiceDisconnected(ComponentName name) {}

        void finish(long timeMs, String reason, boolean sendCancel) {
            if (finished) return;
            finished = true;
            MAIN.removeCallbacks(timeout);
            if (sendCancel && remoteMessenger != null) {
                Message cancel = Message.obtain(null, CANCEL);
                Bundle owned = new Bundle();
                owned.putInt("lane", ticket.lane());
                owned.putLong("ticket", ticket.id());
                cancel.setData(owned);
                try { remoteMessenger.send(cancel); } catch (RemoteException alreadyClosed) { }
            }
            remoteMessenger = null;
            synchronized (BIND_LOCK) {
                sharedWaiting.remove(this);
                sharedActiveAttempts = Math.max(0, sharedActiveAttempts - 1);
                if (sharedActiveAttempts == 0 && sharedBound) {
                    MAIN.removeCallbacks(sharedIdleUnbind);
                    MAIN.postDelayed(sharedIdleUnbind, SHARED_UNBIND_IDLE_MS);
                }
            }
            target.clear();
            LANES.release(ticket);
            result.complete(timeMs, reason);
        }
    }
}
