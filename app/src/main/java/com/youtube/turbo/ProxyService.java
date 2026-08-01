package com.youtube.turbo;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import androidx.core.app.NotificationCompat;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.Iterator;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public class ProxyService extends Service {

    private static final String TAG = "ProxyService";
    private static final String CHANNEL_ID = "ProxyChannel";
    private static final int NOTIFICATION_ID = 1001;
    
    // Статистика
    private AtomicLong totalTraffic = new AtomicLong(0);
    private AtomicLong currentSpeed = new AtomicLong(0);
    private AtomicLong startTime = new AtomicLong(0);
    private AtomicBoolean isRunning = new AtomicBoolean(false);
    
    // Прокси компоненты
    private Selector selector;
    private ServerSocketChannel serverChannel;
    private HandlerThread handlerThread;
    private Handler backgroundHandler;
    private ConcurrentLinkedQueue<SocketChannel> clientChannels = new ConcurrentLinkedQueue<>();
    
    // Binder для активности
    private final IBinder binder = new LocalBinder();

    public class LocalBinder extends Binder {
        public ProxyService getService() {
            return ProxyService.this;
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        startForeground(NOTIFICATION_ID, buildNotification("Инициализация..."));
        
        handlerThread = new HandlerThread("ProxyThread");
        handlerThread.start();
        backgroundHandler = new Handler(handlerThread.getLooper());
        
        startTime.set(System.currentTimeMillis());
        Log.d(TAG, "ProxyService создан");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    public void startProxy() {
        if (isRunning.get()) return;
        isRunning.set(true);
        startTime.set(System.currentTimeMillis());
        
        backgroundHandler.post(() -> {
            try {
                // Инициализация прокси-сервера
                selector = Selector.open();
                serverChannel = ServerSocketChannel.open();
                serverChannel.bind(new InetSocketAddress("127.0.0.1", 8080));
                serverChannel.configureBlocking(false);
                serverChannel.register(selector, SelectionKey.OP_ACCEPT);
                
                updateNotification("🛡️ Защита активна");
                
                // Запускаем основной цикл
                proxyLoop();
                
            } catch (IOException e) {
                Log.e(TAG, "Ошибка запуска прокси", e);
                isRunning.set(false);
            }
        });
    }

    private void proxyLoop() {
        while (isRunning.get()) {
            try {
                selector.select(100);
                
                Iterator<SelectionKey> keys = selector.selectedKeys().iterator();
                while (keys.hasNext()) {
                    SelectionKey key = keys.next();
                    keys.remove();
                    
                    if (!key.isValid()) continue;
                    
                    if (key.isAcceptable()) {
                        handleAccept(key);
                    } else if (key.isReadable()) {
                        handleRead(key);
                    }
                }
                
                // Обновляем скорость
                calculateSpeed();
                
            } catch (IOException e) {
                Log.e(TAG, "Ошибка в цикле прокси", e);
            }
        }
    }

    private void handleAccept(SelectionKey key) throws IOException {
        ServerSocketChannel server = (ServerSocketChannel) key.channel();
        SocketChannel client = server.accept();
        client.configureBlocking(false);
        client.register(selector, SelectionKey.OP_READ);
        clientChannels.add(client);
        
        // Используем DpiInterceptor для обработки подключения
        DpiInterceptor.interceptConnection(client);
        totalTraffic.addAndGet(1024);
    }

    private void handleRead(SelectionKey key) throws IOException {
        SocketChannel channel = (SocketChannel) key.channel();
        try {
            // Читаем данные и пропускаем через фильтр
            long bytesRead = DpiInterceptor.processPacket(channel);
            if (bytesRead > 0) {
                totalTraffic.addAndGet(bytesRead);
                currentSpeed.addAndGet(bytesRead);
            }
        } catch (IOException e) {
            channel.close();
            key.cancel();
            clientChannels.remove(channel);
        }
    }

    private void calculateSpeed() {
        long currentTime = System.currentTimeMillis();
        long speed = currentSpeed.getAndSet(0);
        // Храним скорость за последнюю секунду
        // Используем для отображения в UI
    }

    public void stopProxy() {
        isRunning.set(false);
        updateNotification("⏸ Приостановлено");
        
        backgroundHandler.post(() -> {
            try {
                if (selector != null && selector.isOpen()) {
                    selector.close();
                }
                if (serverChannel != null && serverChannel.isOpen()) {
                    serverChannel.close();
                }
                // Закрываем все клиентские соединения
                for (SocketChannel client : clientChannels) {
                    if (client.isOpen()) {
                        client.close();
                    }
                }
                clientChannels.clear();
            } catch (IOException e) {
                Log.e(TAG, "Ошибка остановки прокси", e);
            }
        });
    }

    private void updateNotification(String text) {
        Notification notification = buildNotification(text);
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager != null) {
            manager.notify(NOTIFICATION_ID, notification);
        }
    }

    private Notification buildNotification(String text) {
        Intent intent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this, 0, intent,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ?
                        PendingIntent.FLAG_IMMUTABLE : 0
        );
        
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("YouTube Turbo")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_menu_share)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "YouTube Turbo Service",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Показывает статус обхода DPI");
            NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    // Геттеры для UI
    public long getTotalTraffic() {
        return totalTraffic.get();
    }

    public long getUptime() {
        if (!isRunning.get()) return 0;
        return System.currentTimeMillis() - startTime.get();
    }

    public long getCurrentSpeed() {
        return currentSpeed.get();
    }

    public boolean isRunning() {
        return isRunning.get();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        isRunning.set(false);
        if (handlerThread != null) {
            handlerThread.quitSafely();
        }
        Log.d(TAG, "ProxyService уничтожен");
    }
}
