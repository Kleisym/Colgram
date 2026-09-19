package org.colgram.core;

import android.app.AlertDialog;
import android.content.Context;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Toast;

import java.lang.reflect.Method;
import java.util.ArrayList;

/**
 * ColgramBotLogin — Enables logging into Telegram as a Bot via Bot Token.
 */
public class ColgramBotLogin {

    public static void showBotLoginDialog(final Context context, final int currentAccount, final Runnable onLoggedIn) {
        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle("🤖 Вход по токену бота");

        final EditText input = new EditText(context);
        input.setHint("123456789:ABCdefGhIJKlmNoPQRsTuVwxyZ");
        input.setSingleLine(true);

        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(48, 24, 48, 24);
        layout.addView(input);
        builder.setView(layout);

        builder.setPositiveButton("Войти", (dialog, which) -> {
            String token = input.getText().toString().trim();
            if (token.isEmpty()) {
                Toast.makeText(context, "Токен не может быть пустым", Toast.LENGTH_SHORT).show();
                return;
            }
            loginWithBotToken(context, currentAccount, token, onLoggedIn);
        });
        builder.setNegativeButton("Отмена", null);
        builder.show();
    }

    public static void loginWithBotToken(final Context context, final int currentAccount, final String token, final Runnable onLoggedIn) {
        try {
            Class<?> bvClass = Class.forName("org.telegram.messenger.BuildVars");
            int apiId = bvClass.getField("APP_ID").getInt(null);
            String apiHash = (String) bvClass.getField("APP_HASH").get(null);

            TL_auth_importBotAuthorization reqObj = new TL_auth_importBotAuthorization();
            reqObj.api_id = apiId;
            reqObj.api_hash = apiHash;
            reqObj.bot_auth_token = token;
            Object req = reqObj.createTLObject();

            if (req == null) {
                Toast.makeText(context, "Ошибка создания запроса авторизации", Toast.LENGTH_SHORT).show();
                return;
            }

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
                                msClass.getMethod("putUsersAndChats", ArrayList.class, ArrayList.class, boolean.class, boolean.class)
                                        .invoke(ms, users, null, true, true);

                                Class<?> auClass = Class.forName("org.telegram.messenger.AndroidUtilities");
                                Method runOnUI = auClass.getMethod("runOnUIThread", Runnable.class);
                                runOnUI.invoke(null, (Runnable) () -> {
                                    Toast.makeText(context, "Успешный вход в аккаунт бота!", Toast.LENGTH_SHORT).show();
                                    if (onLoggedIn != null) {
                                        onLoggedIn.run();
                                    }
                                });
                            } else if (error != null) {
                                String text = (String) error.getClass().getField("text").get(error);
                                Class<?> auClass = Class.forName("org.telegram.messenger.AndroidUtilities");
                                Method runOnUI = auClass.getMethod("runOnUIThread", Runnable.class);
                                runOnUI.invoke(null, (Runnable) () -> {
                                    Toast.makeText(context, "Ошибка входа: " + text, Toast.LENGTH_LONG).show();
                                });
                            }
                        }
                        return null;
                    }
            );

            Method sendReq = null;
            for (Method m : cmClass.getMethods()) {
                if (m.getName().equals("sendRequest") && m.getParameterTypes().length >= 2) {
                    sendReq = m;
                    break;
                }
            }

            if (sendReq != null) {
                sendReq.invoke(cm, req, delegate);
            }

        } catch (Throwable t) {
            t.printStackTrace();
            Toast.makeText(context, "Ошибка: " + t.getMessage(), Toast.LENGTH_LONG).show();
        }
    }
}
