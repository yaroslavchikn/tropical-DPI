package com.youtube.turbo;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;
import androidx.annotation.NonNull;
import androidx.work.*;
import java.util.concurrent.TimeUnit;

public class TrafficMonitor {
    private static final String TAG = "TrafficMonitor";
    private static final String PREFS_NAME = "traffic_stats";
    private static final String KEY_TOTAL_TRAFFIC = "total_traffic";
    private static final String KEY_LAST_RESET = "last_reset";
    
    private Context context;
    private SharedPreferences prefs;
    private long sessionTraffic = 0;
    
    public TrafficMonitor(Context context) {
        this.context = context.getApplicationContext();
        this.prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        this.sessionTraffic = prefs.getLong(KEY_TOTAL_TRAFFIC, 0);
    }
    
    public void addTraffic(long bytes) {
        sessionTraffic += bytes;
        // Сохраняем каждые 1 MB
        if (sessionTraffic % (1024 * 1024) < 1024) {
            saveTraffic();
        }
    }
    
    public long getSessionTraffic() {
        return sessionTraffic;
    }
    
    public long getTotalTrafficAllTime() {
        return prefs.getLong(KEY_TOTAL_TRAFFIC, 0);
    }
    
    private void saveTraffic() {
        prefs.edit()
                .putLong(KEY_TOTAL_TRAFFIC, sessionTraffic)
                .putLong(KEY_LAST_RESET, System.currentTimeMillis())
                .apply();
        Log.d(TAG, "Трафик сохранён: " + sessionTraffic + " байт");
    }
    
    public void resetTraffic() {
        sessionTraffic = 0;
        saveTraffic();
    }
    
    // Периодическое сохранение через WorkManager
    public void schedulePeriodicSave() {
        Constraints constraints = new Constraints.Builder()
                .setRequiresBatteryNotLow(true)
                .build();
        
        PeriodicWorkRequest saveWork = new PeriodicWorkRequest.Builder(
                SaveTrafficWorker.class,
                15, TimeUnit.MINUTES
        )
                .setConstraints(constraints)
                .build();
        
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                "traffic_save",
                ExistingPeriodicWorkPolicy.KEEP,
                saveWork
        );
    }
    
    public static class SaveTrafficWorker extends Worker {
        public SaveTrafficWorker(@NonNull Context context, @NonNull WorkerParameters params) {
            super(context, params);
        }
        
        @NonNull
        @Override
        public Result doWork() {
            try {
                TrafficMonitor monitor = new TrafficMonitor(getApplicationContext());
                monitor.saveTraffic();
                return Result.success();
            } catch (Exception e) {
                Log.e(TAG, "Ошибка сохранения трафика", e);
                return Result.retry();
            }
        }
    }
}
