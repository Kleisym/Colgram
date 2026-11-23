package org.colgram.core;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;

/**
 * ColgramBotLogin — Telegram-Native Bot Token Authentication UI and MTProto Importer.
 * 
 * Styled with Telegram's custom UI theme engine (AlertDialog.Builder,
 * Theme keys, AndroidUtilities dp scaling, and typography) for a seamless, premium feel.
 */
public class ColgramBotLogin {

    private static int getThemeColor(Class<?> themeClass, String keyName, int defaultColor) {
        if (themeClass == null) return defaultColor;
        try {
            Field keyField = themeClass.getField(keyName);
            int key = keyField.getInt(null);
            Method getColorMethod = themeClass.getMethod("getColor", int.class);
            return (int) getColorMethod.invoke(null, key);
        } catch (Throwable t) {
            return defaultColor;
        }
    }

    private static boolean isRussian() {
        try {
            Class<?> lcClass = Class.forName("org.telegram.messenger.LocaleController");
            Method getInstance = lcClass.getMethod("getInstance");
            Object lc = getInstance.invoke(null);
            Field currentLocaleInfoField = lcClass.getDeclaredField("currentLocaleInfo");
            currentLocaleInfoField.setAccessible(true);
            Object li = currentLocaleInfoField.get(lc);
            if (li != null) {
                Field shortNameField = li.getClass().getField("shortName");
                String sn = (String) shortNameField.get(li);
                return "ru".equalsIgnoreCase(sn);
            }
        } catch (Throwable ignored) {}
        return "ru".equalsIgnoreCase(java.util.Locale.getDefault().getLanguage());
    }

    public static void showBotLoginDialog(final Context context, final int currentAccount, final Runnable onLoggedIn) {
        if (context == null) return;
        boolean isRu = isRussian();
        String prefill = "";
        try {
            String colgramPrefill = ColgramConfig.getBotTokenPrefill();
            if (colgramPrefill != null && !colgramPrefill.isEmpty()) {
                prefill = colgramPrefill;
            }
        } catch (Throwable ignored) {}

        ColgramBotSync.showThemedInputDialog(
                context,
                isRu ? "Вход по токену бота" : "Log in via Bot Token",
                isRu
                        ? "Войдите в Telegram от имени бота. Скопируйте токен в @BotFather и вставьте его ниже."
                        : "Log in to Telegram as a bot. Copy your token from @BotFather and paste it below.",
                "123456789:ABCdefGhIJKlm...",
                prefill,
                true,
                isRu ? "Войти" : "Log In",
                isRu ? "Отмена" : "Cancel",
                token -> {
                    if (token.isEmpty() || !token.contains(":")) {
                        Toast.makeText(
                                context,
                                isRu ? "Введите корректный токен (например, 123456:ABC-DEF)" : "Enter a valid token (e.g. 123456:ABC-DEF)",
                                Toast.LENGTH_LONG
                        ).show();
                        return false;
                    }
                    loginWithBotToken(context, currentAccount, token, onLoggedIn);
                    return true;
                }
        );
    }

    public static void loginWithBotToken(final Context context, final int currentAccount, final String token, final Runnable onLoggedIn) {
        Object progressDialog = null;
        try {
            // Show Telegram spinner progress dialog
            Class<?> alertDialogClass = Class.forName("org.telegram.ui.ActionBar.AlertDialog");
            Constructor<?> alertCtor = alertDialogClass.getConstructor(Context.class, int.class);
            progressDialog = alertCtor.newInstance(context, 3); // 3 = ALERT_TYPE_SPINNER
            alertDialogClass.getMethod("setMessage", CharSequence.class).invoke(progressDialog, "Авторизация бота в MTProto...");
            alertDialogClass.getMethod("setCanceledOnTouchOutside", boolean.class).invoke(progressDialog, false);
            alertDialogClass.getMethod("show").invoke(progressDialog);
        } catch (Throwable ignored) {}

        final Object finalProgress = progressDialog;

        try {
            Class<?> bvClass = Class.forName("org.telegram.messenger.BuildVars");
            int apiId = bvClass.getField("APP_ID").getInt(null);
            String apiHash = (String) bvClass.getField("APP_HASH").get(null);

            Class<?> reqClass = Class.forName("org.telegram.tgnet.TLRPC$TL_auth_importBotAuthorization");
            Object req = reqClass.getConstructor().newInstance();
            reqClass.getField("flags").setInt(req, 0);
            reqClass.getField("api_id").setInt(req, apiId);
            reqClass.getField("api_hash").set(req, apiHash);
            reqClass.getField("bot_auth_token").set(req, token);

            Class<?> cmClass = Class.forName("org.telegram.tgnet.ConnectionsManager");
            Method getInstance = cmClass.getMethod("getInstance", int.class);
            Object cm = getInstance.invoke(null, currentAccount);

            Class<?> rdClass = Class.forName("org.telegram.tgnet.RequestDelegate");
            Object delegate = java.lang.reflect.Proxy.newProxyInstance(
                    rdClass.getClassLoader(),
                    new Class[]{rdClass},
                    (proxy, method, args) -> {
                        if ("run".equals(method.getName()) && args.length >= 2) {
                            Object response = args[0];
                            Object error = args[1];

                            Class<?> authClass = Class.forName("org.telegram.tgnet.TLRPC$TL_auth_authorization");

                            if (response != null && authClass.isInstance(response)) {
                                Object user = authClass.getField("user").get(response);

                                Class<?> ucClass = Class.forName("org.telegram.messenger.UserConfig");
                                Object uc = ucClass.getMethod("getInstance", int.class).invoke(null, currentAccount);
                                ucClass.getMethod("setCurrentUser", Class.forName("org.telegram.tgnet.TLRPC$User")).invoke(uc, user);
                                ucClass.getMethod("saveConfig", boolean.class).invoke(uc, true);

                                Class<?> mcClass = Class.forName("org.telegram.messenger.MessagesController");
                                Object mc = mcClass.getMethod("getInstance", int.class).invoke(null, currentAccount);
                                mcClass.getMethod("putUser", Class.forName("org.telegram.tgnet.TLRPC$User"), boolean.class).invoke(mc, user, false);

                                Class<?> msClass = Class.forName("org.telegram.messenger.MessagesStorage");
                                Object ms = msClass.getMethod("getInstance", int.class).invoke(null, currentAccount);
                                ArrayList users = new ArrayList();
                                users.add(user);
                                // getMethod matches DECLARED parameter types exactly: an
                                // ArrayList argument says nothing about the signature, and
                                // MessagesStorage declares List,List. Asking for ArrayList.class
                                // threw NoSuchMethodException here, which — because it sits
                                // mid-block in one big try — silently skipped everything after
                                // it: the token was never saved, the dialog sync never ran, and
                                // the progress dialog was never dismissed. Login looked like it
                                // worked because setCurrentUser had already happened.
                                msClass.getMethod("putUsersAndChats", java.util.List.class, java.util.List.class, boolean.class, boolean.class)
                                        .invoke(ms, users, null, true, true);

                                ColgramBotSync.saveBotToken(context, currentAccount, token);
                                ColgramConfig.setBotTokenPrefill(token);
                                Class<?> auClass = Class.forName("org.telegram.messenger.AndroidUtilities");
                                Method runOnUI = auClass.getMethod("runOnUIThread", Runnable.class);
                                runOnUI.invoke(null, (Runnable) () -> {
                                    dismissProgress(finalProgress);
                                    Toast.makeText(context, "Успешный вход в аккаунт бота!", Toast.LENGTH_SHORT).show();
                                    ColgramBotSync.syncBotDialogs(context, currentAccount);
                                    if (onLoggedIn != null) {
                                        onLoggedIn.run();
                                    }
                                });
                            } else if (error != null) {
                                String text = (String) error.getClass().getField("text").get(error);
                                Class<?> auClass = Class.forName("org.telegram.messenger.AndroidUtilities");
                                Method runOnUI = auClass.getMethod("runOnUIThread", Runnable.class);
                                runOnUI.invoke(null, (Runnable) () -> {
                                    dismissProgress(finalProgress);
                                    Toast.makeText(context, "Ошибка входа: " + text, Toast.LENGTH_LONG).show();
                                });
                            }
                        }
                        return null;
                    }
            );

            Method sendReq = null;
            for (Method m : cmClass.getMethods()) {
                if (m.getName().equals("sendRequest") && m.getParameterTypes().length == 3 && m.getParameterTypes()[2] == int.class) {
                    sendReq = m;
                    break;
                }
            }

            int reqFlags = 1 | 2 | 8 | 16; // RequestFlagEnableUnauthorized | RequestFlagFailOnServerErrors | RequestFlagWithoutLogin | RequestFlagTryDifferentDc
            if (sendReq != null) {
                sendReq.invoke(cm, req, delegate, reqFlags);
            }

        } catch (Throwable t) {
            t.printStackTrace();
            dismissProgress(finalProgress);
            Toast.makeText(context, "Ошибка: " + t.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private static void dismissProgress(Object progressDialog) {
        if (progressDialog == null) return;
        try {
            progressDialog.getClass().getMethod("dismiss").invoke(progressDialog);
        } catch (Throwable ignored) {}
    }
}
