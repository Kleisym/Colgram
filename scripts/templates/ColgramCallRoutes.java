package org.telegram.ui;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.VpnService;
import android.os.SystemClock;
import android.widget.Toast;

import org.colgram.core.ColgramCallRoutePolicy;
import org.colgram.core.ColgramCallRoutePolicy.Mode;
import org.colgram.core.ColgramCallSocksTunnel;
import org.colgram.core.ColgramConfig;
import org.colgram.core.ColgramProxyManager;
import org.colgram.core.ColgramRouteControl;
import org.colgram.core.ColgramRouteEpoch;
import org.colgram.core.ColgramSubscriptionStore;
import org.colgram.core.ColgramWarpTunnel;
import org.colgram.singbox.ColgramVpnService;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.voip.VoIPService;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.utils.proxy.ProxySettings;

import java.lang.ref.WeakReference;

/** One explicit media route for each private call; never silently falls back. */
public final class ColgramCallRoutes {
    private static final String MODE_KEY = "colgram_private_call_route";
    private static final String SOCKS_KEY = "colgram_private_call_socks";
    public static final int CONSENT = 9186;
    public static final String EXTRA_TICKET = "colgram_private_call_route_ticket";
    public static final String EXTRA_MODE = "colgram_private_call_route_mode";
    private static volatile Pending pending;
    private static volatile Pending session;
    private static final android.os.Handler callRouteHandler =
            new android.os.Handler(android.os.Looper.getMainLooper());

    private ColgramCallRoutes() {}

    private static SharedPreferences prefs() {
        return MessagesController.getGlobalMainSettings();
    }

    public static Mode mode() {
        return ColgramCallRoutePolicy.fromPreference(prefs().getString(MODE_KEY, null),
                prefs().getBoolean("proxy_enabled_calls", true));
    }

    public static String summary() {
        return mode().labelRu;
    }

    public static void showPicker(Activity activity, Runnable onChange) {
        if (activity == null) return;
        Mode[] modes = Mode.values();
        CharSequence[] labels = new CharSequence[modes.length];
        for (int i = 0; i < modes.length; i++) {
            labels[i] = (modes[i] == mode() ? "✓  " : "") + modes[i].labelRu;
        }
        new AlertDialog.Builder(activity).setTitle("Использовать для звонков")
                .setItems(labels, (dialog, index) -> {
                    Mode selected = modes[index];
                    if (selected == Mode.SOCKS5) chooseSocks(activity, onChange);
                    else save(selected, onChange);
                }).setNegativeButton("Отмена", null).show();
    }

    private static void save(Mode mode, Runnable onChange) {
        prefs().edit().putString(MODE_KEY, mode.name()).apply();
        if (onChange != null) onChange.run();
    }

    private static String identity(ProxySettings settings) {
        // Store only host and port here. The proxy editor already owns the credentials.
        return settings.getAddress() + ":" + settings.getPort();
    }

    private static void chooseSocks(Activity activity, Runnable onChange) {
        SharedConfig.loadProxyList();
        java.util.ArrayList<ProxySettings> choices = new java.util.ArrayList<>();
        java.util.ArrayList<String> labels = new java.util.ArrayList<>();
        for (SharedConfig.ProxyInfo info : new java.util.ArrayList<>(SharedConfig.proxyList)) {
            if (info == null || !ColgramProxyManager.isProxyCatalogVisible(info)) continue;
            ProxySettings settings = info.settings;
            if (!isSocks(settings)) continue;
            choices.add(settings);
            labels.add(identity(settings));
        }
        if (choices.isEmpty()) {
            new AlertDialog.Builder(activity).setTitle("Прокси для звонков")
                    .setMessage("Нет сохранённого SOCKS5. Добавьте свой прокси в меню щита. MTProto не передаёт голосовой трафик.")
                    .setPositiveButton("ОК", null).show();
            return;
        }
        new AlertDialog.Builder(activity).setTitle("Выберите SOCKS5 для звонков")
                .setItems(labels.toArray(new String[0]), (dialog, index) -> {
                    prefs().edit().putString(SOCKS_KEY, identity(choices.get(index))).apply();
                    save(Mode.SOCKS5, onChange);
                }).setNegativeButton("Отмена", null).show();
    }

    private static boolean isSocks(ProxySettings settings) {
        return settings != null && settings.getType() == ProxySettings.Type.SOCKS5 && settings.isValid()
                && !settings.getAddress().equals("127.0.0.1") && !settings.getAddress().equals("::1")
                && !settings.getAddress().equalsIgnoreCase("localhost");
    }

    private static ProxySettings selectedSocks() {
        String selected = prefs().getString(SOCKS_KEY, "");
        for (SharedConfig.ProxyInfo info : new java.util.ArrayList<>(SharedConfig.proxyList)) {
            if (info != null && isSocks(info.settings) && selected.equals(identity(info.settings))
                    && ColgramProxyManager.isProxyCatalogVisible(info)) return info.settings;
        }
        return null;
    }

    private static boolean hasVpn(Context context) {
        ConnectivityManager manager = context.getSystemService(ConnectivityManager.class);
        Network network = manager == null ? null : manager.getActiveNetwork();
        NetworkCapabilities caps = network == null ? null : manager.getNetworkCapabilities(network);
        return caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN);
    }

    public static ColgramCallRoutePolicy.Decision verdict(Context context, Mode mode) {
        return ColgramCallRoutePolicy.evaluate(mode, hasVpn(context), ColgramWarpTunnel.isConnected(),
                "running".equals(ColgramVpnService.status(ColgramSubscriptionStore.profileLocation(context))),
                "running".equals(ColgramCallSocksTunnel.status(context, false)),
                "running".equals(ColgramCallSocksTunnel.status(context, true)));
    }

    /** Track explicit routes after start, not just while constructing the media engine. */
    public static Runnable watch(Context context, Mode selected, Runnable onLost) {
        if (selected == Mode.CURRENT) return () -> {};
        final class Watch implements Runnable {
            boolean stopped;
            @Override public void run() {
                if (stopped) return;
                if (!verdict(context, selected).allowed) {
                    stopped = true;
                    onLost.run();
                } else {
                    callRouteHandler.postDelayed(this, 750L);
                }
            }
        }
        Watch watch = new Watch();
        callRouteHandler.post(watch);
        return () -> {
            watch.stopped = true;
            callRouteHandler.removeCallbacks(watch);
        };
    }

    public interface Ready {
        void run(Mode mode, long ticket);
    }

    private static final class Pending {
        final WeakReference<Activity> activity;
        final Context app;
        final Mode mode;
        final Ready ready;
        final Runnable onFailure;
        final long epoch;
        final ProxySettings socks;
        final String subscription;
        final boolean oldWarp;
        volatile boolean started;
        volatile long engineGeneration;

        Pending(Activity activity, Mode mode, Ready ready, Runnable onFailure, ProxySettings socks, String subscription) {
            this.activity = new WeakReference<>(activity);
            app = activity.getApplicationContext();
            this.mode = mode;
            this.ready = ready;
            this.onFailure = onFailure;
            this.socks = socks;
            this.subscription = subscription;
            oldWarp = ColgramConfig.isWarpEnabled();
            epoch = ColgramRouteEpoch.next();
        }
    }

    /**
     * Starts a foreground tunnel only when no unrelated VPN already owns Android's slot.
     * If a selected WARP/VPN is already running, the call borrows it and does not stop it.
     */
    public static void prepare(Activity activity, Ready ready) {
        prepare(activity, ready, () -> {});
    }

    public static void prepare(Activity activity, Ready ready, Runnable onFailure) {
        if (activity == null || activity.isFinishing()) {
            onFailure.run();
            return;
        }
        if (pending != null || session != null) {
            onFailure.run();
            showError(activity, "Маршрут предыдущего звонка ещё освобождается.");
            return;
        }
        Mode selected = mode();
        ColgramCallRoutePolicy.Decision current = verdict(activity, selected);
        if (current.allowed) {
            ready.run(selected, 0);
            return;
        }
        if (selected == Mode.DIRECT || hasVpn(activity)) {
            onFailure.run();
            showError(activity, current.failure);
            return;
        }
        final ProxySettings socks = selected == Mode.SOCKS5 ? selectedSocks() : null;
        if (selected == Mode.SOCKS5 && socks == null) {
            onFailure.run();
            showError(activity, "Выберите доступный SOCKS5 в настройке «Использовать для звонков».");
            return;
        }
        final String subscription = selected == Mode.VPN ? ColgramSubscriptionStore.profilePath(activity) : null;
        if (selected == Mode.VPN && subscription == null) {
            onFailure.run();
            showError(activity, "Сначала импортируйте VPN-подписку и выберите узел.");
            return;
        }
        Pending work = new Pending(activity, selected, ready, onFailure, socks, subscription);
        pending = work;
        try {
            Intent consent = VpnService.prepare(activity);
            if (consent == null) start(work);
            else if (activity instanceof LaunchActivity) activity.startActivityForResult(consent, CONSENT);
            else complete(work, "Откройте Colgram, чтобы подтвердить разрешение Android VPN.");
        } catch (RuntimeException failure) {
            complete(work, "Android не выдал разрешение на VPN.");
        }
    }

    public static boolean onActivityResult(Activity activity, int request, int result) {
        if (request != CONSENT) return false;
        Pending work = pending;
        if (work != null) {
            if (result == Activity.RESULT_OK && work.activity.get() == activity) start(work);
            else complete(work, "Разрешение Android VPN не получено.");
        }
        return true;
    }

    public static void cancelForActivity(Activity activity) {
        Pending work = pending;
        if (work == null || work.activity.get() != activity) return;
        if (work.mode == Mode.WARP && work.started && ColgramRouteEpoch.isCurrent(work.epoch))
            ColgramWarpTunnel.cancelPendingStart();
        complete(work, "Запуск маршрута звонка отменён.");
    }

    private static void start(Pending work) {
        Activity activity = work.activity.get();
        if (activity == null || activity.isFinishing() || activity.isDestroyed() || !ColgramRouteEpoch.isCurrent(work.epoch)) {
            complete(work, "Запуск маршрута звонка отменён.");
            return;
        }
        Toast.makeText(activity, "Готовлю маршрут: " + work.mode.labelRu, Toast.LENGTH_SHORT).show();
        ColgramRouteControl.OPERATIONS.execute(() -> {
            String failure = null;
            try {
                if (pending != work || !ColgramRouteEpoch.isCurrent(work.epoch))
                    throw new IllegalStateException("Запуск отменён.");
                if (hasVpn(work.app)) throw new IllegalStateException("Активен другой Android VPN.");
                work.started = true;
                switch (work.mode) {
                    case WARP:
                        ColgramConfig.setWarpEnabled(true);
                        ColgramWarpTunnel.clearFailure();
                        ColgramWarpTunnel.bringUp(work.app, work.epoch);
                        break;
                    case VPN:
                        ColgramVpnService.start(work.app, work.subscription);
                        break;
                    case SOCKS5:
                        work.engineGeneration = ColgramCallSocksTunnel.start(work.app, work.socks.getAddress(),
                                work.socks.getPort(), work.socks.getUser(), work.socks.getPassword());
                        break;
                    case LOCAL_DPI:
                        work.engineGeneration = ColgramCallSocksTunnel.startLocalDpi(work.app);
                        break;
                    default:
                        throw new IllegalStateException("Неизвестный маршрут звонка.");
                }
                long deadline = SystemClock.elapsedRealtime() + 12_000L;
                while (pending == work && ColgramRouteEpoch.isCurrent(work.epoch)
                        && SystemClock.elapsedRealtime() < deadline
                        && !isReady(work)) {
                    Thread.sleep(200);
                }
                if (!isReady(work))
                    failure = "Маршрут «" + work.mode.labelRu + "» для звонка не подключился.";
            } catch (Exception error) {
                failure = "Маршрут «" + work.mode.labelRu + "» не запустился. Проверьте настройки подключения.";
            }
            String result = failure;
            AndroidUtilities.runOnUIThread(() -> complete(work, result));
        });
    }

    private static boolean isReady(Pending work) {
        if (!verdict(work.app, work.mode).allowed) return false;
        if (work.mode != Mode.SOCKS5 && work.mode != Mode.LOCAL_DPI) return true;
        return "running".equals(ColgramCallSocksTunnel.status(work.app, work.mode == Mode.LOCAL_DPI,
                work.engineGeneration));
    }

    private static void complete(Pending work, String failure) {
        if (pending != work) return;
        pending = null;
        Activity activity = work.activity.get();
        if (failure == null && ColgramRouteEpoch.isCurrent(work.epoch)
                && activity != null && !activity.isFinishing() && !activity.isDestroyed()) {
            session = work;
            try {
                work.ready.run(work.mode, work.epoch);
            } catch (RuntimeException error) {
                release(work.epoch);
                showError(activity, "Не удалось запустить звонок.");
                return;
            }
            // An abandoned start must not leave the call tunnel alive.
            AndroidUtilities.runOnUIThread(() -> {
                if (VoIPService.getSharedInstance() == null) release(work.epoch);
            }, 10_000L);
        } else {
            stopOwned(work);
            work.onFailure.run();
            if (activity != null && !activity.isFinishing() && !activity.isDestroyed() && failure != null)
                showError(activity, failure);
        }
    }

    public static void release(long ticket) {
        Pending work = session;
        if (ticket == 0 || work == null || work.epoch != ticket) return;
        session = null;
        stopOwned(work);
    }

    private static void stopOwned(Pending work) {
        if (!work.started) return;
        ColgramRouteControl.OPERATIONS.execute(() -> {
            switch (work.mode) {
                case WARP:
                    if (!ColgramRouteEpoch.isCurrent(work.epoch)) break;
                    ColgramWarpTunnel.bringDown(work.app);
                    ColgramConfig.setWarpEnabled(work.oldWarp);
                    break;
                case VPN:
                    if (!ColgramRouteEpoch.isCurrent(work.epoch)) break;
                    ColgramVpnService.stopProfile(work.app, work.subscription);
                    break;
                case SOCKS5:
                    ColgramCallSocksTunnel.stop(work.app, false, work.engineGeneration);
                    break;
                case LOCAL_DPI:
                    ColgramCallSocksTunnel.stop(work.app, true, work.engineGeneration);
                    break;
                default:
                    break;
            }
        });
    }

    public static void reportFailure(Context context, String reason) {
        AndroidUtilities.runOnUIThread(() -> Toast.makeText(context, reason, Toast.LENGTH_LONG).show());
    }

    private static void showError(Activity activity, String reason) {
        new AlertDialog.Builder(activity).setTitle("Маршрут звонка").setMessage(reason)
                .setPositiveButton("ОК", null).show();
    }
}
