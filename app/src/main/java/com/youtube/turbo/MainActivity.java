package com.youtube.turbo;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.view.Menu;
import android.view.MenuItem;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.widget.CompoundButton;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;
import androidx.cardview.widget.CardView;
import androidx.core.content.ContextCompat;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.progressindicator.CircularProgressIndicator;
import com.google.android.material.snackbar.Snackbar;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends AppCompatActivity implements ServiceConnection {

    // UI Components
    private SwitchCompat toggleSwitch;
    private TextView statusText, trafficText, uptimeText, speedText;
    private MaterialCardView mainCard;
    private CircularProgressIndicator progressIndicator;
    private CardView statsCard;
    
    // Service binding
    private ProxyService proxyService;
    private boolean isBound = false;
    private boolean isRunning = false;
    
    // Handlers
    private Handler mainHandler = new Handler(Looper.getMainLooper());
    private Runnable updateRunnable;
    
    // Animations
    private Animation pulseAnimation;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        
        // Инициализация UI
        initViews();
        
        // Загрузка анимаций
        pulseAnimation = AnimationUtils.loadAnimation(this, R.anim.pulse);
        
        // Привязка к сервису
        Intent intent = new Intent(this, ProxyService.class);
        bindService(intent, this, Context.BIND_AUTO_CREATE);
        startService(intent);
        
        // Настройка переключателя
        setupToggle();
        
        // Запуск обновления статистики
        startTrafficUpdates();
        
        // Приветствие
        showWelcomeMessage();
    }

    private void initViews() {
        toggleSwitch = findViewById(R.id.toggle_switch);
        statusText = findViewById(R.id.status_text);
        trafficText = findViewById(R.id.traffic_text);
        uptimeText = findViewById(R.id.uptime_text);
        speedText = findViewById(R.id.speed_text);
        mainCard = findViewById(R.id.main_card);
        progressIndicator = findViewById(R.id.progress_indicator);
        statsCard = findViewById(R.id.stats_card);
        
        // Анимация появления
        mainCard.setAlpha(0f);
        mainCard.animate()
                .alpha(1f)
                .setDuration(800)
                .start();
        
        // Устанавливаем градиентный фон
        mainCard.setBackground(ContextCompat.getDrawable(this, R.drawable.background_gradient));
    }

    private void setupToggle() {
        toggleSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isChecked) {
                startTurbo();
            } else {
                stopTurbo();
            }
        });
    }

    private void startTurbo() {
        if (isRunning) return;
        isRunning = true;
        
        // Изменяем цвет статуса с анимацией
        animateStatusColor(Color.parseColor("#4CAF50"));
        statusText.setText("🛡️ ЗАЩИТА АКТИВНА");
        statusText.setTextColor(Color.parseColor("#4CAF50"));
        
        // Анимация кнопки
        toggleSwitch.animate()
                .scaleX(1.1f)
                .scaleY(1.1f)
                .setDuration(200)
                .withEndAction(() -> {
                    toggleSwitch.animate()
                            .scaleX(1f)
                            .scaleY(1f)
                            .setDuration(200)
                            .start();
                })
                .start();
        
        // Запускаем сервис
        if (isBound && proxyService != null) {
            proxyService.startProxy();
            Toast.makeText(this, "🚀 Обход DPI активирован!", Toast.LENGTH_SHORT).show();
            showSnackbar("YouTube теперь без VPN и лагов!", true);
        }
        
        // Анимация индикатора
        progressIndicator.setVisibility(android.view.View.VISIBLE);
        progressIndicator.animate()
                .alpha(1f)
                .setDuration(300)
                .start();
        
        // Показываем карточку статистики
        statsCard.setVisibility(android.view.View.VISIBLE);
        statsCard.animate()
                .translationY(0f)
                .alpha(1f)
                .setDuration(400)
                .start();
    }

    private void stopTurbo() {
        if (!isRunning) return;
        isRunning = false;
        
        animateStatusColor(Color.parseColor("#757575"));
        statusText.setText("⏸ ОБХОД ОТКЛЮЧЁН");
        statusText.setTextColor(Color.parseColor("#757575"));
        
        if (isBound && proxyService != null) {
            proxyService.stopProxy();
            Toast.makeText(this, "⏸ Обход остановлен", Toast.LENGTH_SHORT).show();
            showSnackbar("Обход отключён. YouTube может работать медленнее.", false);
        }
        
        progressIndicator.animate()
                .alpha(0f)
                .setDuration(300)
                .withEndAction(() -> progressIndicator.setVisibility(android.view.View.GONE))
                .start();
        
        statsCard.animate()
                .translationY(100f)
                .alpha(0f)
                .setDuration(400)
                .start();
    }

    private void animateStatusColor(int targetColor) {
        int startColor = statusText.getCurrentTextColor();
        ValueAnimator colorAnim = ValueAnimator.ofObject(
                new ArgbEvaluator(),
                startColor,
                targetColor
        );
        colorAnim.setDuration(500);
        colorAnim.addUpdateListener(animator -> 
                statusText.setTextColor((int) animator.getAnimatedValue())
        );
        colorAnim.start();
    }

    private void startTrafficUpdates() {
        updateRunnable = new Runnable() {
            @Override
            public void run() {
                if (isBound && proxyService != null) {
                    // Обновляем статистику
                    long traffic = proxyService.getTotalTraffic();
                    String trafficStr = formatTraffic(traffic);
                    trafficText.setText("📊 Трафик: " + trafficStr);
                    
                    long uptime = proxyService.getUptime();
                    uptimeText.setText("⏱ Время работы: " + formatUptime(uptime));
                    
                    long speed = proxyService.getCurrentSpeed();
                    speedText.setText("⚡ Скорость: " + formatSpeed(speed));
                }
                mainHandler.postDelayed(this, 1000);
            }
        };
        mainHandler.post(updateRunnable);
    }

    private String formatTraffic(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.2f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format("%.2f MB", bytes / (1024.0 * 1024));
        return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }

    private String formatUptime(long millis) {
        long seconds = millis / 1000;
        long minutes = seconds / 60;
        long hours = minutes / 60;
        if (hours > 0) return hours + "ч " + (minutes % 60) + "м";
        if (minutes > 0) return minutes + "м " + (seconds % 60) + "с";
        return seconds + "с";
    }

    private String formatSpeed(long bytesPerSecond) {
        if (bytesPerSecond < 1024) return bytesPerSecond + " B/s";
        if (bytesPerSecond < 1024 * 1024) return String.format("%.1f KB/s", bytesPerSecond / 1024.0);
        return String.format("%.1f MB/s", bytesPerSecond / (1024.0 * 1024));
    }

    private void showSnackbar(String message, boolean success) {
        Snackbar snackbar = Snackbar.make(
                findViewById(android.R.id.content),
                message,
                Snackbar.LENGTH_LONG
        );
        snackbar.setAction("Понятно", v -> snackbar.dismiss());
        if (success) {
            snackbar.setBackgroundTint(Color.parseColor("#2E7D32"));
        } else {
            snackbar.setBackgroundTint(Color.parseColor("#C62828"));
        }
        snackbar.show();
    }

    private void showWelcomeMessage() {
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(this);
            builder.setTitle("🚀 Добро пожаловать!");
            builder.setMessage("Просто включи тумблер — и YouTube начнёт летать без VPN и буферизации.\n\n" +
                    "🛡️ Работает на уровне DPI (подмена SNI)\n" +
                    "⚡ Не требует root-прав\n" +
                    "📊 Считает трафик в реальном времени");
            builder.setPositiveButton("Погнали!", (dialog, which) -> {
                toggleSwitch.setChecked(true);
            });
            builder.setNegativeButton("Позже", null);
            builder.show();
        }, 500);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.main_menu, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == R.id.action_about) {
            new AlertDialog.Builder(this)
                    .setTitle("О приложении")
                    .setMessage("YouTube Turbo v2.0\n\n" +
                            "Создано для обхода DPI без VPN\n" +
                            "Использует технологию подмены SNI\n\n" +
                            "Разработано в России ❤️")
                    .setPositiveButton("OK", null)
                    .show();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public void onServiceConnected(ComponentName name, IBinder service) {
        ProxyService.LocalBinder binder = (ProxyService.LocalBinder) service;
        proxyService = binder.getService();
        isBound = true;
        
        // Если сервис уже был запущен, синхронизируем состояние
        if (proxyService.isRunning()) {
            toggleSwitch.setChecked(true);
            startTurbo();
        }
    }

    @Override
    public void onServiceDisconnected(ComponentName name) {
        isBound = false;
        proxyService = null;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (isBound) {
            unbindService(this);
            isBound = false;
        }
        mainHandler.removeCallbacks(updateRunnable);
        if (isRunning) {
            stopTurbo();
        }
    }
}
