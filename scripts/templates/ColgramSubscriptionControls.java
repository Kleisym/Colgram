package org.telegram.ui;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.VpnService;
import android.os.Build;
import android.text.InputType;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.Toast;

import org.colgram.core.ColgramConfig;
import org.colgram.core.ColgramDpiBypass;
import org.colgram.core.ColgramProxyManager;
import org.colgram.core.ColgramRouteControl;
import org.colgram.core.ColgramRouteEpoch;
import org.colgram.core.ColgramSubscription;
import org.colgram.core.ColgramSubscriptionStore;
import org.colgram.core.ColgramWarpTunnel;
import org.colgram.singbox.ColgramVpnService;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** One subscription workflow for Connection Settings and the legacy Colgram entry. */
final class ColgramSubscriptionControls {
    private static final ExecutorService PROFILE_WORK = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "colgram-subscription-io");
        thread.setDaemon(true);
        return thread;
    });
    private static final AtomicBoolean EDITING = new AtomicBoolean();
    private final BaseFragment screen;
    private final int requestCode;
    private final Runnable refresh;
    private long awaitingConsent = -1L;
    private long starting = -1L;
    private String failure;

    ColgramSubscriptionControls(BaseFragment screen, int requestCode, Runnable refresh) {
        this.screen = screen;
        this.requestCode = requestCode;
        this.refresh = refresh;
    }

    static String label(String russian, String english) {
        LocaleController.LocaleInfo locale = LocaleController.getInstance().getCurrentLocaleInfo();
        return locale != null && "ru".equalsIgnoreCase(locale.shortName) ? russian : english;
    }

    private Context context() {
        Context context = screen.getContext();
        return context == null ? null : context.getApplicationContext();
    }

    private static String serviceStatus(Context context) {
        return context == null ? "stopped" : ColgramVpnService.status(ColgramSubscriptionStore.profileLocation(context));
    }

    String rawStatus() {
        if (awaitingConsent >= 0 && ColgramRouteEpoch.isCurrent(awaitingConsent)) return "consent";
        if (starting >= 0 && ColgramRouteEpoch.isCurrent(starting)) return "starting";
        return serviceStatus(context());
    }

    boolean canDisconnect() {
        String status = rawStatus();
        return "consent".equals(status) || "starting".equals(status) || "running".equals(status);
    }

    String statusLine() {
        if (EDITING.get()) return label("читаю подписку…", "Reading subscription…");
        switch (rawStatus()) {
            case "consent": return label("ожидаю разрешение Android VPN", "Waiting for Android VPN permission");
            case "starting": return label("запускается…", "Starting…");
            case "running": return label("VPN-туннель запущен", "VPN tunnel started");
            case "stopping": return label("выключается…", "Stopping…");
            case "failed": return label("ошибка запуска · проверьте узел", "Startup failed · check the node");
            default:
                if (failure != null) return failure;
                Context context = context();
                return context != null && ColgramSubscriptionStore.hasSubscription(context)
                        ? label("выключен", "Off") : label("добавьте подписку или ключ", "Add a subscription or key");
        }
    }

    String nodeLabel() {
        Context context = context();
        ColgramSubscriptionStore.State state = context == null ? null : ColgramSubscriptionStore.current(context);
        return state == null ? label("не выбран", "None selected") : safeLabel(state.name, state.selected, state.protocol);
    }

    private static String safeLabel(String name, int index, String protocol) {
        String label = name == null ? "" : name.replaceAll("[\\p{Cntrl}\\p{Cf}]", " ").trim();
        if (label.isEmpty() || label.contains("://")) label = label("Узел ", "Node ") + (index + 1);
        if (label.length() > 52) label = label.substring(0, 52) + "…";
        return protocol == null || protocol.isEmpty() ? label : label + " · " + protocol;
    }

    void showMenu() {
        Activity activity = screen.getParentActivity();
        Context context = context();
        if (activity == null || context == null) return;
        if (!ColgramSubscriptionStore.hasSubscription(context)) { importLink(); return; }
        screen.showDialog(new AlertDialog.Builder(activity).setTitle(label("VPN-подписка", "VPN subscription"))
                .setItems(new String[]{toggleLabel(), label("Выбрать узел", "Choose node"),
                        label("Импорт VPN-подписки или ключа", "Import VPN subscription or key"),
                        label("Удалить подписку", "Remove subscription")}, (dialog, which) -> {
                    if (which == 0) toggle();
                    else if (which == 1) showNodes();
                    else if (which == 2) importLink();
                    else confirmRemove();
                }).setNegativeButton(label("Отмена", "Cancel"), null).create());
    }

    void importLink() {
        Activity activity = screen.getParentActivity();
        if (activity == null) return;
        EditText input = new EditText(activity);
        input.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
        input.setHintTextColor(Theme.getColor(Theme.key_dialogTextHint));
        input.setHint("https://…   vless://…   vmess://…   trojan://…");
        input.setContentDescription(label("Ссылка подписки или ключ VPN", "VPN subscription link or key"));
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI
                | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        input.setSingleLine(false);
        input.setMinLines(2);
        input.setMaxLines(4);
        input.setHorizontallyScrolling(false);
        input.setSaveEnabled(false);
        input.setImeOptions(EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);
        if (Build.VERSION.SDK_INT >= 26) input.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        AlertDialog dialog = new AlertDialog.Builder(activity).setTitle(label("Импорт VPN", "Import VPN"))
                .setMessage(label("Вставьте URL подписки или ключ: VLESS TCP/WS, VMess, Trojan, Shadowsocks. Сохранение не включает VPN. Если VPN подписки уже запущен, он остановится после успешного импорта.",
                        "Paste a subscription URL or key: VLESS over TCP/WS, VMess, Trojan, or Shadowsocks. Saving does not connect the VPN. An existing subscription VPN stops after a successful import."))
                .setView(input)
                .setPositiveButton(label("Сохранить", "Save"), (d, w) -> saveSubscription(input.getText().toString().trim()))
                .setNegativeButton(label("Отмена", "Cancel"), null).create();
        if (dialog.getWindow() != null) dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        dialog.setOnDismissListener(d -> input.setText(""));
        screen.showDialog(dialog, d -> input.setText(""));
    }

    private boolean beginEdit() {
        if (EDITING.compareAndSet(false, true)) {
            failure = null;
            refresh.run();
            return true;
        }
        say(label("Подписка уже обрабатывается", "Subscription is already being processed"));
        return false;
    }

    private void saveSubscription(String text) {
        Context context = context();
        if (context == null) return;
        if (text.isEmpty()) { say(label("Пустая ссылка или ключ", "Link or key is empty")); return; }
        if (!beginEdit()) return;
        PROFILE_WORK.execute(() -> {
            try {
                ColgramSubscriptionStore.State state = ColgramSubscriptionStore.save(context, text);
                finishEdit(context, label("Сохранено узлов: ", "Nodes saved: ") + state.total, false);
            } catch (RuntimeException invalid) {
                finishEdit(null, label("Подписка не принята · проверьте адрес или формат ключа",
                        "Import failed · check the address or key format"), true);
            }
        });
    }

    void showNodes() {
        Context context = context();
        if (context == null || screen.getParentActivity() == null) return;
        if (!ColgramSubscriptionStore.hasSubscription(context)) { importLink(); return; }
        if (EDITING.get()) { say(label("Подписка ещё обрабатывается", "Subscription is still being processed")); return; }
        PROFILE_WORK.execute(() -> {
            final List<ColgramSubscription.Node> nodes;
            try {
                nodes = ColgramSubscriptionStore.nodes(context);
            } catch (RuntimeException invalid) {
                AndroidUtilities.runOnUIThread(() -> say(label("Не удалось прочитать узлы", "Could not read the nodes")));
                return;
            }
            AndroidUtilities.runOnUIThread(() -> {
                Activity activity = screen.getParentActivity();
                if (activity == null) return;
                if (nodes.isEmpty()) { say(label("В подписке нет узлов", "No nodes in the subscription")); return; }
                ColgramSubscriptionStore.State state = ColgramSubscriptionStore.current(context);
                String[] labels = new String[nodes.size()];
                for (int i = 0; i < labels.length; i++) {
                    ColgramSubscription.Node node = nodes.get(i);
                    labels[i] = (state != null && state.selected == i ? "✓  " : "") + safeLabel(node.name, i, node.protocol);
                }
                screen.showDialog(new AlertDialog.Builder(activity).setTitle(label("Узел VPN / страна", "VPN node / country"))
                        .setMessage(label("Названия указаны провайдером. Смена узла останавливает текущий VPN подписки.",
                                "Names are provided by the service. Changing the node stops the current subscription VPN."))
                        .setItems(labels, (d, index) -> selectNode(index))
                        .setNegativeButton(label("Отмена", "Cancel"), null).create());
            });
        });
    }

    private void selectNode(int index) {
        Context context = context();
        if (context == null) return;
        ColgramSubscriptionStore.State old = ColgramSubscriptionStore.current(context);
        if ((old != null && old.selected == index) || !beginEdit()) return;
        PROFILE_WORK.execute(() -> {
            try {
                ColgramSubscriptionStore.select(context, index);
                finishEdit(context, label("Узел выбран", "Node selected"), false);
            } catch (RuntimeException invalid) {
                finishEdit(null, label("Не удалось выбрать узел", "Could not select the node"), true);
            }
        });
    }

    private void finishEdit(Context edited, String message, boolean failed) {
        AndroidUtilities.runOnUIThread(() -> {
            EDITING.set(false);
            if (edited != null) stopForAlternativeRoute(edited);
            if (failed) failure = message;
            say(message);
            refresh.run();
        });
    }

    void confirmRemove() {
        Activity activity = screen.getParentActivity();
        Context context = context();
        if (activity == null || context == null) return;
        if (!ColgramSubscriptionStore.hasSubscription(context)) { say(label("Подписка не сохранена", "No subscription saved")); return; }
        screen.showDialog(new AlertDialog.Builder(activity)
                .setTitle(label("Удалить подписку?", "Remove subscription?"))
                .setMessage(label("Сохранённые ключи и выбор узла будут удалены.", "The saved keys and node selection will be removed."))
                .setPositiveButton(label("Удалить", "Remove"), (d, w) -> {
                    if (!beginEdit()) return;
                    stopForAlternativeRoute(context);
                    PROFILE_WORK.execute(() -> {
                        try {
                            ColgramSubscriptionStore.clear(context);
                            finishEdit(null, label("Подписка удалена", "Subscription removed"), false);
                        } catch (RuntimeException invalid) {
                            finishEdit(null, label("Не удалось удалить подписку", "Could not remove the subscription"), true);
                        }
                    });
                }).setNegativeButton(label("Отмена", "Cancel"), null).create());
    }

    String toggleLabel() {
        return canDisconnect() ? label("Отключить VPN", "Disconnect VPN") : label("Подключить VPN", "Connect VPN");
    }

    void toggle() {
        Context context = context();
        if (context == null) return;
        if (canDisconnect()) { stopForAlternativeRoute(context); failure = null; refresh.run(); return; }
        if ("stopping".equals(rawStatus())) { say(label("VPN ещё выключается", "VPN is still stopping")); return; }
        if (EDITING.get()) { say(label("Подписка ещё обрабатывается", "Subscription is still being processed")); return; }
        if (!ColgramSubscriptionStore.hasSubscription(context)) { importLink(); return; }
        prepareSelectedRoute(context, true);
    }

    private void prepareSelectedRoute(Context context, boolean allowPrompt) {
        Activity activity = screen.getParentActivity();
        if (activity == null) return;
        try {
            Intent consent = VpnService.prepare(activity);
            if (consent == null) startSubscriptionTunnel(context);
            else if (allowPrompt) {
                awaitingConsent = ColgramRouteEpoch.snapshot();
                screen.startActivityForResult(consent, requestCode);
                refresh.run();
            } else report(label("Разрешение Android VPN не подтверждено", "Android VPN permission not confirmed"));
        } catch (RuntimeException denied) {
            awaitingConsent = -1L;
            report(label("Android не выдал разрешение на VPN", "Android VPN permission unavailable"));
        }
    }

    boolean onActivityResult(int code, int result) {
        if (code != requestCode) return false;
        long requested = awaitingConsent;
        awaitingConsent = -1L;
        Context context = context();
        if (requested < 0 || !ColgramRouteEpoch.isCurrent(requested) || context == null) return true;
        if (result == Activity.RESULT_OK) prepareSelectedRoute(context, false);
        else report(label("Разрешение Android VPN не получено", "Android VPN permission denied"));
        return true;
    }

    void abandonConsent() { awaitingConsent = -1L; }

    void stopForAlternativeRoute(Context context) {
        abandonConsent();
        if (starting >= 0 && ColgramRouteEpoch.isCurrent(starting)) ColgramRouteEpoch.next();
        starting = -1L;
        stopStoredProfile(context);
    }

    static void stopStoredProfile(Context context) {
        String status = serviceStatus(context);
        if ("starting".equals(status) || "running".equals(status))
            ColgramVpnService.stopProfile(context, ColgramSubscriptionStore.profileLocation(context));
    }

    private void startSubscriptionTunnel(Context context) {
        String profile = ColgramSubscriptionStore.profilePath(context);
        if (profile == null) { report(label("Профиль VPN не найден", "VPN profile is missing")); return; }
        if (EDITING.get()) { report(label("Подписка ещё обрабатывается", "Subscription is still being processed")); return; }
        final long token = ColgramRouteEpoch.next();
        starting = token;
        failure = null;
        ColgramProxyManager.cancelProxySelection();
        boolean replaceWarp = ColgramConfig.isWarpEnabled() || ColgramRouteControl.WARP_GATE.isPending() || ColgramWarpTunnel.isUp();
        if (replaceWarp) {
            ColgramRouteControl.WARP_GATE.cancel();
            ColgramConfig.setWarpEnabled(false);
            ColgramWarpTunnel.cancelPendingStart();
        }
        refresh.run();
        ColgramRouteControl.OPERATIONS.execute(() -> {
            if (!ColgramRouteEpoch.isCurrent(token)) return;
            try {
                if (replaceWarp) ColgramWarpTunnel.bringDown(context);
                AndroidUtilities.runOnUIThread(() -> {
                    if (!ColgramRouteEpoch.isCurrent(token)) return;
                    try {
                        ColgramRouteEpoch.runIfCurrent(token, () -> {
                            ColgramConfig.setAutoProxyEnabled(false);
                            if (ColgramConfig.isDpiBypassEnabled() || ColgramDpiBypass.isRunning())
                                ColgramProxyManager.setDpiBypassEnabled(context, false);
                            ColgramConfig.setDcRemapEnabled(false);
                            ColgramConfig.setIpv6BypassEnabled(false);
                            if (ColgramProxyManager.isDcRemapActive()) ColgramProxyManager.applyDcRemap(false);
                            ColgramProxyManager.disableProxy(context);
                            ColgramVpnService.start(context, profile);
                        });
                    } catch (RuntimeException failed) {
                        report(label("Не удалось запустить VPN подписки", "Could not start the subscription VPN"));
                    } finally {
                        if (starting == token) starting = -1L;
                        refresh.run();
                    }
                });
            } catch (RuntimeException failed) {
                AndroidUtilities.runOnUIThread(() -> {
                    if (starting == token) starting = -1L;
                    if (ColgramRouteEpoch.isCurrent(token))
                        report(label("Не удалось переключить VPN", "Could not switch the VPN"));
                });
            }
        });
    }

    private void report(String message) {
        failure = message;
        say(message);
        refresh.run();
    }

    private void say(String message) {
        Activity activity = screen.getParentActivity();
        if (activity != null) Toast.makeText(activity, message, Toast.LENGTH_SHORT).show();
    }
}
