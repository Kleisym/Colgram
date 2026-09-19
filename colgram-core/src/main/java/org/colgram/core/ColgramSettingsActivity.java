package org.colgram.core;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

/**
 * ColgramSettingsActivity — Complete native settings panel for Colgram features:
 * Hardware cloaking, Ghost Mode, Storage Sandboxing, MTProto Proxies, and OTA Updates.
 */
public class ColgramSettingsActivity extends AppCompatActivity {

    public static void start(Context context) {
        Intent intent = new Intent(context, ColgramSettingsActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle("Настройки Colgram");

        ColgramConfig.init(this);

        ScrollView scrollView = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(48, 48, 48, 48);

        // Section: Cloaking
        addSectionHeader(root, "Маскировка устройства (Cloaking)");
        addSwitch(root, "Включить маскировку железа", ColgramConfig.isCloakEnabled(), (btn, isChecked) -> {
            ColgramConfig.setCloakEnabled(isChecked);
        });

        final Button deviceBtn = new Button(this);
        deviceBtn.setText("Профиль: " + ColgramConfig.getSpoofDeviceModel());
        deviceBtn.setOnClickListener(v -> showProfileSelector(deviceBtn));
        root.addView(deviceBtn);

        // Section: Message Vault
        addSectionHeader(root, "Сейф сообщений (Message Vault)");
        addSwitch(root, "Анти-удаление сообщений", ColgramConfig.isAntiDeleteEnabled(), (btn, isChecked) -> {
            ColgramConfig.setAntiDeleteEnabled(isChecked);
        });
        addSwitch(root, "Защита медиа от удаления (Media Lock)", ColgramConfig.isPreserveMediaEnabled(), (btn, isChecked) -> {
            ColgramConfig.setPreserveMediaEnabled(isChecked);
        });
        addSwitch(root, "История правок текста", ColgramConfig.isEditHistoryEnabled(), (btn, isChecked) -> {
            ColgramConfig.setEditHistoryEnabled(isChecked);
        });

        // Section: Ghost Mode
        addSectionHeader(root, "Режим призрака (Ghost Mode)");
        addSwitch(root, "Невидимка (не слать отчет о прочтении)", ColgramConfig.isGhostReadEnabled(), (btn, isChecked) -> {
            ColgramConfig.setGhostReadEnabled(isChecked);
        });
        addSwitch(root, "Скрывать «печатает...» и запись аудио", ColgramConfig.isGhostTypingEnabled(), (btn, isChecked) -> {
            ColgramConfig.setGhostTypingEnabled(isChecked);
        });
        addSwitch(root, "Скрывать статус онлайн (оффлайн режим)", ColgramConfig.isGhostOnlineEnabled(), (btn, isChecked) -> {
            ColgramConfig.setGhostOnlineEnabled(isChecked);
        });
        addSwitch(root, "Разрешить скриншоты везде (Обход FLAG_SECURE)", ColgramConfig.isBypassFlagSecureEnabled(), (btn, isChecked) -> {
            ColgramConfig.setBypassFlagSecureEnabled(isChecked);
        });

        // Section: Storage Sandbox
        addSectionHeader(root, "Песочница файлов (Sandbox)");
        addSwitch(root, "Изолировать хранилище в Documents/Colgram", ColgramConfig.isSandboxStorageEnabled(), (btn, isChecked) -> {
            ColgramConfig.setSandboxStorageEnabled(isChecked);
        });

        // Section: Network & Censorship Bypass
        addSectionHeader(root, "Сеть и обход блокировок");
        addSwitch(root, "Встроенный Fake-TLS MTProto пул", ColgramConfig.isBuiltinProxyEnabled(), (btn, isChecked) -> {
            ColgramConfig.setBuiltinProxyEnabled(isChecked);
        });
        addSwitch(root, "DNS-over-HTTPS (DoH Cloudflare/Google)", ColgramConfig.isDohEnabled(), (btn, isChecked) -> {
            ColgramConfig.setDohEnabled(isChecked);
        });

        // Section: Updates
        addSectionHeader(root, "Обновления Colgram");
        Button updateBtn = new Button(this);
        updateBtn.setText("Проверить обновления");
        updateBtn.setOnClickListener(v -> checkUpdates());
        root.addView(updateBtn);

        scrollView.addView(root);
        setContentView(scrollView);
    }

    private void addSectionHeader(LinearLayout root, String title) {
        TextView tv = new TextView(this);
        tv.setText(title);
        tv.setTextSize(16);
        tv.setTypeface(null, android.graphics.Typeface.BOLD);
        tv.setTextColor(0xFF33B5E5);
        tv.setPadding(0, 32, 0, 16);
        root.addView(tv);
    }

    private void addSwitch(LinearLayout root, String title, boolean initialValue, Switch.OnCheckedChangeListener listener) {
        Switch sw = new Switch(this);
        sw.setText(title);
        sw.setChecked(initialValue);
        sw.setPadding(0, 16, 0, 16);
        sw.setOnCheckedChangeListener(listener);
        root.addView(sw);
    }

    private void showProfileSelector(final Button button) {
        final String[] models = new String[] {
            "Google Pixel 8 Pro (Android 14)",
            "Samsung Galaxy S24 Ultra (Android 14)",
            "Google Pixel 7a (Android 13)",
            "Motorola Edge 40 Pro (Android 13)",
            "Sony Xperia 1 V (Android 14)"
        };

        new AlertDialog.Builder(this)
            .setTitle("Выберите модель устройства")
            .setItems(models, (dialog, which) -> {
                String selected = models[which];
                ColgramConfig.setSpoofDeviceModel(selected.split(" \\(")[0]);
                button.setText("Профиль: " + ColgramConfig.getSpoofDeviceModel());
                Toast.makeText(ColgramSettingsActivity.this, "Профиль сохранен", Toast.LENGTH_SHORT).show();
            })
            .show();
    }

    private void checkUpdates() {
        Toast.makeText(this, "Проверка обновлений...", Toast.LENGTH_SHORT).show();
        ColgramUpdater.checkForUpdates("1.0.0", new ColgramUpdater.UpdateCheckCallback() {
            @Override
            public void onUpdateAvailable(String newVersion, String releaseNotes, String downloadUrl) {
                new AlertDialog.Builder(ColgramSettingsActivity.this)
                    .setTitle("Доступно обновление: " + newVersion)
                    .setMessage(releaseNotes)
                    .setPositiveButton("Скачать и установить", (d, w) -> {
                        ColgramUpdater.downloadAndInstall(ColgramSettingsActivity.this, downloadUrl, newVersion);
                        Toast.makeText(ColgramSettingsActivity.this, "Загрузка началась в фоне...", Toast.LENGTH_LONG).show();
                    })
                    .setNegativeButton("Позже", null)
                    .show();
            }

            @Override
            public void onUpToDate() {
                Toast.makeText(ColgramSettingsActivity.this, "У вас установлена последняя версия!", Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onError(String error) {
                Toast.makeText(ColgramSettingsActivity.this, "Ошибка проверки: " + error, Toast.LENGTH_LONG).show();
            }
        });
    }
}
