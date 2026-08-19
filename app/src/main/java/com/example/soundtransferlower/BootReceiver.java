package com.example.soundtransferlower;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

public class BootReceiver extends BroadcastReceiver {
    private static final String TAG = "BootReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (Intent.ACTION_BOOT_COMPLETED.equals(action)
                || AlarmManagerHelper.ACTION_RESTART_SERVICE.equals(action)) {
            Log.d(TAG, "Received action: " + action + ", starting BluetoothService...");
            Intent service = new Intent(context, BluetoothService.class);
            // Android 8.0 (API 26) 起后台限制：使用 startForegroundService() 替代 startService()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(service);
            } else {
                context.startService(service);
            }
        }
    }
}
