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
    public static final long CHECK_TIMEOUT_MS = 10_000L;
    public static final int CHECK = 1;
    public static final int RESULT = 2;
    public static final String TIMEOUT = "TG_CONFIG_TIMEOUT";
    private static final String SERVICE = "org.telegram.tgnet.ColgramGuestProxyService";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static Attempt active;

    private ColgramGuestProxyProbe() { }

    public interface Result { void complete(long timeMs, String reason); }

    public static synchronized boolean check(Context context, String host, int port,
                                             String user, String pass, String secret, boolean publicOnly,
                                             Result result) {
        if (active != null || context == null || result == null || host == null || host.isEmpty()
                || host.length() > 255 || port < 1 || port > 65535) return false;
        Bundle target = new Bundle();
        target.putString("host", host);
        target.putInt("port", port);
        target.putString("user", user);
        target.putString("pass", pass);
        target.putString("secret", secret);
        target.putBoolean("publicOnly", publicOnly);
        Attempt attempt = new Attempt(context.getApplicationContext(), target, result);
        active = attempt;
        MAIN.post(attempt::bind);
        return true;
    }

    private static final class Attempt implements ServiceConnection {
        final Context app;
        final Bundle target;
        final Result result;
        final Runnable timeout = () -> finish(-1, TIMEOUT);
        final Messenger replies = new Messenger(new Handler(Looper.getMainLooper(), message -> {
            if (message.what != RESULT) return false;
            Bundle verdict = message.getData();
            finish(verdict.getLong("time", -1L), verdict.getString("reason", "TG_CONFIG_UNAVAILABLE"));
            return true;
        }));
        boolean bound;
        boolean finished;

        Attempt(Context app, Bundle target, Result result) {
            this.app = app;
            this.target = target;
            this.result = result;
        }

        void bind() {
            MAIN.postDelayed(timeout, CHECK_TIMEOUT_MS);
            Intent intent = new Intent().setClassName(app, SERVICE);
            try {
                bound = app.bindService(intent, this, Context.BIND_AUTO_CREATE);
                if (!bound) finish(-1, "TG_PROBE_UNAVAILABLE");
            } catch (RuntimeException unavailable) {
                finish(-1, "TG_PROBE_UNAVAILABLE");
            }
        }

        @Override public void onServiceConnected(ComponentName name, IBinder remote) {
            if (finished) return;
            Message request = Message.obtain(null, CHECK);
            request.setData(target);
            request.replyTo = replies;
            try {
                new Messenger(remote).send(request);
            } catch (RemoteException disconnected) {
                finish(-1, "TG_PROBE_DISCONNECTED");
            }
        }

        @Override public void onServiceDisconnected(ComponentName name) { finish(-1, "TG_PROBE_DISCONNECTED"); }
        @Override public void onBindingDied(ComponentName name) { finish(-1, "TG_PROBE_DISCONNECTED"); }
        @Override public void onNullBinding(ComponentName name) { finish(-1, "TG_PROBE_UNAVAILABLE"); }

        void finish(long timeMs, String reason) {
            if (finished) return;
            finished = true;
            MAIN.removeCallbacks(timeout);
            if (bound) {
                try { app.unbindService(this); } catch (IllegalArgumentException alreadyUnbound) { }
                bound = false;
            }
            target.clear();
            synchronized (ColgramGuestProxyProbe.class) {
                if (active == this) active = null;
            }
            result.complete(timeMs, reason);
        }
    }
}
