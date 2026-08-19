package com.example.soundtransferlower;

import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

/**
 * WorkManager 备用保活 Worker。
 * 当 AlarmManager 闹钟失败时，周期性检查并重启 BluetoothService。
 * 最小周期为 15 分钟（WorkManager 限制）。
 */
public class ServiceRestartWorker extends Worker {
    private static final String TAG = "ServiceRestartWorker";

    public ServiceRestartWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        Log.d(TAG, "WorkManager 保活触发，检查服务状态...");
        Context context = getApplicationContext();
        Intent serviceIntent = new Intent(context, BluetoothService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent);
        } else {
            context.startService(serviceIntent);
        }
        return Result.success();
    }
}
