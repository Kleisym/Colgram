package org.colgram.core;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Color;
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
 * Styled entirely using Telegram's custom UI theme engine (AlertDialog.Builder,
 * Theme keys, AndroidUtilities dp scaling, and typography) for a seamless, premium feel.
 */
public class ColgramBotLogin {

    public static void showBotLoginDialog(final Context context, final int currentAccount, final Runnable onLoggedIn) {
        try {
            Class<?> auClass = Class.forName("org.telegram.messenger.AndroidUtilities");
            Class<?> themeClass = Class.forName("org.telegram.ui.ActionBar.Theme");
            Class<?> alertBuilderClass = Class.forName("org.telegram.ui.ActionBar.AlertDialog$Builder");

            Method dpMethod = auClass.getMethod("dp", float.class);
            Method getColorMethod = themeClass.getMethod("getColor", String.class);

            int dp8 = (int) dpMethod.invoke(null, 8f);
            int dp12 = (int) dpMethod.invoke(null, 12f);
            int dp16 = (int) dpMethod.invoke(null, 16f);
            int dp20 = (int) dpMethod.invoke(null, 20f);
            int dp24 = (int) dpMethod.invoke(null, 24f);

            int textColor = (int) getColorMethod.invoke(null, "dialogTextBlack");
            int grayColor = (int) getColorMethod.invoke(null, "dialogTextGray");
            int hintColor = (int) getColorMethod.invoke(null, "dialogTextHint");
            int blueColor = (int) getColorMethod.invoke(null, "dialogTextBlue2");
            int fieldBgColor = (int) getColorMethod.invoke(null, "dialogInputField");

            // Main container
            LinearLayout container = new LinearLayout(context);
            container.setOrientation(LinearLayout.VERTICAL);
            container.setPadding(dp24, dp16, dp24, dp8);

            // Bot Icon Badge
            TextView iconView = new TextView(context);
            iconView.setText("🤖");
            iconView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 36);
            iconView.setGravity(Gravity.CENTER);
            iconView.setPadding(0, 0, 0, dp8);
            container.addView(iconView);

            // Subtitle Description
            TextView subtitleView = new TextView(context);
            subtitleView.setText("Войдите в Telegram от имени бота. Создайте или скопируйте токен в @BotFather и вставьте его ниже.");
            subtitleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
            subtitleView.setTextColor(grayColor);
            subtitleView.setGravity(Gravity.CENTER_HORIZONTAL);
            subtitleView.setPadding(0, 0, 0, dp16);
            container.addView(subtitleView);

            // Input card container
            LinearLayout inputCard = new LinearLayout(context);
            inputCard.setOrientation(LinearLayout.HORIZONTAL);
            inputCard.setGravity(Gravity.CENTER_VERTICAL);
            inputCard.setPadding(dp12, dp8, dp12, dp8);

            GradientDrawable cardBg = new GradientDrawable();
            cardBg.setCornerRadius(dp12);
            cardBg.setColor(fieldBgColor != 0 ? fieldBgColor : Color.parseColor("#15000000"));
            cardBg.setStroke((int) dpMethod.invoke(null, 1f), Color.parseColor("#207F7F7F"));
            inputCard.setBackground(cardBg);

            // EditText for token
            final EditText input = new EditText(context);
            input.setHint("123456789:ABCdefGhIJKlm...");
            input.setHintTextColor(hintColor);
            input.setTextColor(textColor);
            input.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
            input.setTypeface(android.graphics.Typeface.MONOSPACE);
            input.setBackground(null);
            input.setSingleLine(true);
            input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
            LinearLayout.LayoutParams inputParams = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            input.setLayoutParams(inputParams);
            inputCard.addView(input);

            // Quick Paste button
            TextView pasteBtn = new TextView(context);
            pasteBtn.setText("Вставить");
            pasteBtn.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            pasteBtn.setTextColor(blueColor);
            pasteBtn.setTypeface((android.graphics.Typeface) auClass.getMethod("bold").invoke(null));
            pasteBtn.setPadding(dp8, dp8, dp8, dp8);
            pasteBtn.setOnClickListener(v -> {
                ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
                if (clipboard != null && clipboard.hasPrimaryClip()) {
                    ClipData clip = clipboard.getPrimaryClip();
                    if (clip != null && clip.getItemCount() > 0) {
                        CharSequence text = clip.getItemAt(0).getText();
                        if (text != null) {
                            input.setText(text.toString().trim());
                            input.setSelection(input.getText().length());
                        }
                    }
                }
            });
            inputCard.addView(pasteBtn);

            container.addView(inputCard);

            // Telegram AlertDialog.Builder
            Object builder = alertBuilderClass.getConstructor(Context.class).newInstance(context);
            alertBuilderClass.getMethod("setTitle", CharSequence.class).invoke(builder, "Вход по токену бота");
            alertBuilderClass.getMethod("setView", View.class).invoke(builder, container);

            alertBuilderClass.getMethod("setPositiveButton", CharSequence.class, android.content.DialogInterface.OnClickListener.class)
                    .invoke(builder, "Войти", (android.content.DialogInterface.OnClickListener) (dialog, which) -> {
                        String token = input.getText().toString().trim();
                        if (token.isEmpty() || !token.contains(":")) {
                            Toast.makeText(context, "Введите корректный токен (например, 123456:ABC-DEF)", Toast.LENGTH_LONG).show();
                            return;
                        }
                        loginWithBotToken(context, currentAccount, token, onLoggedIn);
                    });

            alertBuilderClass.getMethod("setNegativeButton", CharSequence.class, android.content.DialogInterface.OnClickListener.class)
                    .invoke(builder, "Отмена", null);

            alertBuilderClass.getMethod("show").invoke(builder);

        } catch (Throwable t) {
            t.printStackTrace();
            fallbackDialog(context, currentAccount, onLoggedIn);
        }
    }

    private static void fallbackDialog(final Context context, final int currentAccount, final Runnable onLoggedIn) {
        android.app.AlertDialog.Builder builder = new android.app.AlertDialog.Builder(context);
        builder.setTitle("🤖 Вход по токену бота");
        final EditText input = new EditText(context);
        input.setHint("123456789:ABCdefGhIJKlmNoPQRsTuVwxyZ");
        input.setSingleLine(true);
        builder.setView(input);
        builder.setPositiveButton("Войти", (dialog, which) -> {
            String token = input.getText().toString().trim();
            if (!token.isEmpty()) {
                loginWithBotToken(context, currentAccount, token, onLoggedIn);
            }
        });
        builder.setNegativeButton("Отмена", null);
        builder.show();
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

            TL_auth_importBotAuthorization reqObj = new TL_auth_importBotAuthorization();
            reqObj.api_id = apiId;
            reqObj.api_hash = apiHash;
            reqObj.bot_auth_token = token;
            Object req = reqObj.createTLObject();

            if (req == null) {
                dismissProgress(finalProgress);
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
                                    dismissProgress(finalProgress);
                                    Toast.makeText(context, "Вход выполнен успешно!", Toast.LENGTH_SHORT).show();
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
