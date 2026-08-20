package com.example.soundtransferlower;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
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
        pendingIntent = PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT);
    }

    public void startAlarm() {
        if (alarmManager != null) {
            // 使用 ELAPSED_REALTIME_WAKEUP 唤醒设备
            long triggerAt = SystemClock.elapsedRealtime() + interval;
            alarmManager.setRepeating(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, interval, pendingIntent);
            LogUtil.d(TAG, "Alarm set, interval=" + interval);
        }
    }

    public void cancelAlarm() {
        if (alarmManager != null) {
            alarmManager.cancel(pendingIntent);
            LogUtil.d(TAG, "Alarm cancelled");
        }
    }
}