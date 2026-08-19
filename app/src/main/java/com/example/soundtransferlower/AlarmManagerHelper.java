package com.example.soundtransferlower;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.SystemClock;
import android.util.Log;

public class AlarmManagerHelper {
    private static final String TAG = "AlarmManagerHelper";
    public static final String ACTION_RESTART_SERVICE = "com.example.soundtransferlower.RESTART_SERVICE";

    private Context context;
    private AlarmManager alarmManager;
    private PendingIntent pendingIntent;
    private long interval;

    public AlarmManagerHelper(Context context, long interval) {
        this.context = context;
        this.interval = interval;
        alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        Intent intent = new Intent(ACTION_RESTART_SERVICE);
        pendingIntent = PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    public void startAlarm() {
        if (alarmManager != null) {
            long triggerAt = SystemClock.elapsedRealtime() + interval;

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // Android 12 (API 31)+: 需要 SCHEDULE_EXACT_ALARM 权限才能使用精确闹钟
                if (alarmManager.canScheduleExactAlarms()) {
                    alarmManager.setExactAndAllowWhileIdle(
                            AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pendingIntent);
                    Log.d(TAG, "Alarm set (exact, API 31+), interval=" + interval);
                } else {
                    // 无精确闹钟权限，降级为非精确闹钟
                    alarmManager.setAndAllowWhileIdle(
                            AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pendingIntent);
                    Log.w(TAG, "No SCHEDULE_EXACT_ALARM permission, using inexact alarm");
                }
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                // Android 6.0-11 (API 23-30): 使用 setExactAndAllowWhileIdle 确保 Doze 模式下也能触发
                alarmManager.setExactAndAllowWhileIdle(
                        AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pendingIntent);
                Log.d(TAG, "Alarm set (exact, API 23+), interval=" + interval);
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                // Android 4.4-5.x (API 19-22): 使用 setExact 替代 setRepeating（setRepeating 从 4.4 起不再精确）
                alarmManager.setExact(
                        AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pendingIntent);
                Log.d(TAG, "Alarm set (exact, API 19+), interval=" + interval);
            } else {
                // Android 4.3 及以下: 使用传统 setRepeating
                alarmManager.setRepeating(
                        AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, interval, pendingIntent);
                Log.d(TAG, "Alarm set (repeating, legacy), interval=" + interval);
            }
        }
    }

    public void cancelAlarm() {
        if (alarmManager != null) {
            alarmManager.cancel(pendingIntent);
            Log.d(TAG, "Alarm cancelled");
        }
    }
}
