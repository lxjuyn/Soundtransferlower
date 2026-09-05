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
        try {
            // API 31+ 后台 Worker 启动 FGS 会被系统直接拦下（ForegroundServiceStartNotAllowedException），
            // 保活主路径是精确闹钟（闹钟接收器有临时豁免）；这里只做尽力而为的兜底
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent);
            } else {
                context.startService(serviceIntent);
            }
        } catch (Throwable t) {
            Log.w(TAG, "后台重启服务被系统限制，忽略（依赖闹钟保活路径）: " + t.getMessage());
        }
        return Result.success();
    }
}
