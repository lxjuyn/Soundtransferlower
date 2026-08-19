package com.example.soundtransferlower;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.util.Log;

import java.util.ArrayList;
import java.util.Set;

public class BluetoothFinder {
    private static final String TAG = "BluetoothFinder";
    private final Context context;
    private final BluetoothAdapter bluetoothAdapter;
    private final ArrayList<BluetoothDevice> scannedDevices = new ArrayList<>();
    private final ArrayList<BluetoothDevice> pairedDevices = new ArrayList<>();
    private final ArrayList<BluetoothDevice> duplicateDevices = new ArrayList<>();
    private boolean isReceiverRegistered = false;
    private long lastScanStartTime = 0;
    private static final long MIN_SCAN_INTERVAL_MS = 10000; // 最小扫描间隔10秒

    private final BroadcastReceiver scanReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (BluetoothDevice.ACTION_FOUND.equals(action)) {
                BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                if (device != null && !scannedDevices.contains(device)) {
                    scannedDevices.add(device);
                    checkForDuplicates(device);
                    Log.d(TAG, "发现设备: " + device.getName() + " (" + device.getAddress() + ")");
                }
            }
        }
    };

    public BluetoothFinder(Context context) {
        this.context = context;
        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
    }

    public void startScan() {
        if (isReceiverRegistered) {
            Log.w(TAG, "扫描已开始，忽略重复调用");
            return;
        }

        // 优化：扫描节流，防止频繁扫描消耗电量
        long currentTime = System.currentTimeMillis();
        if (currentTime - lastScanStartTime < MIN_SCAN_INTERVAL_MS) {
            Log.d(TAG, "扫描间隔过短，跳过本次扫描");
            return;
        }

        scannedDevices.clear();
        duplicateDevices.clear();

        IntentFilter filter = new IntentFilter(BluetoothDevice.ACTION_FOUND);
        try {
            context.registerReceiver(scanReceiver, filter);
            isReceiverRegistered = true;
            lastScanStartTime = currentTime;
            Log.d(TAG, "扫描接收器已注册");
        } catch (Exception e) {
            Log.e(TAG, "注册扫描接收器失败: " + e.getMessage());
            return;
        }

        if (bluetoothAdapter != null) {
            bluetoothAdapter.startDiscovery();
            Log.d(TAG, "蓝牙扫描已启动");
        }
    }

    public void stopScan() {
        if (!isReceiverRegistered) {
            Log.d(TAG, "扫描接收器未注册，无需取消");
            return;
        }
        if (bluetoothAdapter != null && bluetoothAdapter.isDiscovering()) {
            bluetoothAdapter.cancelDiscovery();
        }
        try {
            context.unregisterReceiver(scanReceiver);
            isReceiverRegistered = false;
            Log.d(TAG, "扫描接收器已取消注册");
        } catch (IllegalArgumentException e) {
            Log.e(TAG, "取消注册扫描接收器失败: " + e.getMessage());
        }
    }

    public void fetchPairedDevices() {
        pairedDevices.clear();
        if (bluetoothAdapter != null) {
            Set<BluetoothDevice> bondedDevices = bluetoothAdapter.getBondedDevices();
            pairedDevices.addAll(bondedDevices);
        }
        updateDuplicates();
    }

    private void checkForDuplicates(BluetoothDevice device) {
        if (pairedDevices.contains(device) && !duplicateDevices.contains(device)) {
            duplicateDevices.add(device);
        }
    }

    private void updateDuplicates() {
        duplicateDevices.clear();
        for (BluetoothDevice device : scannedDevices) {
            if (pairedDevices.contains(device)) {
                duplicateDevices.add(device);
            }
        }
    }

    public ArrayList<BluetoothDevice> getScannedDevices() {
        return new ArrayList<>(scannedDevices);
    }

    public ArrayList<BluetoothDevice> getPairedDevices() {
        return new ArrayList<>(pairedDevices);
    }

    public ArrayList<BluetoothDevice> getDuplicateDevices() {
        return new ArrayList<>(duplicateDevices);
    }
}