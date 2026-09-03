package com.example.soundtransferlower;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.Looper;

import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * 自动探测扫描工具类
 * 定时扫描附近蓝牙设备，并通过回调通知监听器
 */
public class AutoDeviceScanner {
    private static final String TAG = "AutoDeviceScanner";

    private Context context;
    private BluetoothAdapter bluetoothAdapter;
    private Handler handler = new Handler(Looper.getMainLooper());
    private Runnable scanRunnable;
    private int scanIntervalSeconds = 60; // 默认60秒
    private boolean isEnabled = false;
    private boolean isScanning = false;
    private Set<DeviceScanListener> listeners = new CopyOnWriteArraySet<>();

    private BroadcastReceiver discoveryReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (BluetoothDevice.ACTION_FOUND.equals(action)) {
                BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                if (device != null) {
                    LogUtil.d(TAG, "扫描到设备: " + device.getName() + " (" + device.getAddress() + ")");
                    notifyDeviceFound(device);
                }
            } else if (BluetoothAdapter.ACTION_DISCOVERY_FINISHED.equals(action)) {
                LogUtil.d(TAG, "扫描完成");
                isScanning = false;
                scheduleNextScan();
            }
        }
    };

    public AutoDeviceScanner(Context context) {
        this.context = context.getApplicationContext();
        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        IntentFilter filter = new IntentFilter();
        filter.addAction(BluetoothDevice.ACTION_FOUND);
        filter.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED);
        context.registerReceiver(discoveryReceiver, filter);
    }

    public void setScanInterval(int seconds) {
        this.scanIntervalSeconds = seconds;
        if (isEnabled) {
            stopScanning();
            startScanning();
        }
    }

    public void setEnabled(boolean enabled) {
        if (isEnabled == enabled) return;
        isEnabled = enabled;
        if (enabled) {
            startScanning();
        } else {
            stopScanning();
        }
    }

    private void startScanning() {
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled()) {
            LogUtil.w(TAG, "蓝牙未开启，无法扫描");
            return;
        }
        if (isScanning) return;
        handler.removeCallbacks(scanRunnable);
        performScan();
    }

    private void stopScanning() {
        handler.removeCallbacks(scanRunnable);
        if (isScanning) {
            bluetoothAdapter.cancelDiscovery();
            isScanning = false;
        }
    }

    private void performScan() {
        if (!isEnabled) return;
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled()) {
            LogUtil.w(TAG, "蓝牙不可用，跳过扫描");
            scheduleNextScan();
            return;
        }
        if (isScanning) return;
        LogUtil.d(TAG, "开始扫描附近设备...");
        isScanning = true;
        bluetoothAdapter.startDiscovery();
        // 超时处理：12秒后强制结束
        handler.postDelayed(() -> {
            if (isScanning) {
                LogUtil.d(TAG, "扫描超时，强制取消");
                bluetoothAdapter.cancelDiscovery();
                isScanning = false;
                scheduleNextScan();
            }
        }, 12000);
    }

    private void scheduleNextScan() {
        if (!isEnabled) return;
        handler.removeCallbacks(scanRunnable);
        scanRunnable = new Runnable() {
            @Override
            public void run() {
                performScan();
            }
        };
        handler.postDelayed(scanRunnable, scanIntervalSeconds * 1000L);
    }

    private void notifyDeviceFound(BluetoothDevice device) {
        for (DeviceScanListener listener : listeners) {
            listener.onDeviceDetected(device);
        }
    }

    public void addListener(DeviceScanListener listener) {
        listeners.add(listener);
    }

    public void removeListener(DeviceScanListener listener) {
        listeners.remove(listener);
    }

    public void release() {
        stopScanning();
        try {
            context.unregisterReceiver(discoveryReceiver);
        } catch (Exception e) {
            LogUtil.e(TAG, "取消注册接收器失败", e);
        }
        listeners.clear();
    }

    public interface DeviceScanListener {
        void onDeviceDetected(BluetoothDevice device);
    }
}