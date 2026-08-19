package com.example.soundtransferlower;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.Message;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import androidx.core.app.NotificationCompat;
import android.util.Log;
import android.widget.Toast;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.UUID;
import java.lang.ref.WeakReference;
import java.util.concurrent.CopyOnWriteArrayList;

public class BluetoothService extends Service {
    private static final String TAG = "BluetoothService";
    private static final String APP_NAME = "SoundTransfer";
    private static final UUID MY_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
    public static final String TEXT_PREFIX = "TXT:";
    public static final byte[] TEXT_PREFIX_BYTES = TEXT_PREFIX.getBytes();

    // 控制消息常量
    public static final String FILE_REQUEST_PREFIX = "FILE_REQUEST:";
    public static final String FILE_ACCEPT = "FILE_ACCEPT";
    public static final String FILE_REJECT = "FILE_REJECT";
    public static final String CALL_PREFIX = "CALL:";
    public static final String CALL_REQUEST = "CALL_REQUEST:";
    public static final String CALL_ACCEPT = "CALL_ACCEPT";
    public static final String CALL_REJECT = "CALL_REJECT";
    public static final String CALL_HANGUP = "CALL_HANGUP";

    private final IBinder binder = new LocalBinder();
    private BluetoothAdapter bluetoothAdapter;
    private AcceptThread acceptThread;
    private ConnectThread connectThread;
    private ConnectedThread connectedThread;
    private int state;
    private String connectedDeviceAddress;
    private String connectedDeviceName;

    private boolean isInitiator = false;
    private String targetDeviceAddress = null;

    public static final int STATE_NONE = 0;
    public static final int STATE_LISTEN = 1;
    public static final int STATE_CONNECTING = 2;
    public static final int STATE_CONNECTED = 3;

    public static final int MODE_CHAT = 0;
    public static final int MODE_TALKBACK = 1;
    private int currentMode = MODE_CHAT;

    private CopyOnWriteArrayList<MessageCallback> messageCallbacks = new CopyOnWriteArrayList<>();

    // ---- 保活相关 ----
    private PowerManager.WakeLock wakeLock;
    private AlarmManagerHelper alarmManagerHelper;
    private BroadcastReceiver screenReceiver;
    private BroadcastReceiver alarmReceiver;
    private static final int NOTIFICATION_ID = 1001;
    private static final long HEARTBEAT_INTERVAL = 30 * 60 * 1000;

    // ---- ★★★ 健康检查 ★★★ ----
    private static class SafeHandler extends Handler {
        private final WeakReference<BluetoothService> serviceRef;

        SafeHandler(BluetoothService service) {
            super(Looper.getMainLooper());
            serviceRef = new WeakReference<>(service);
        }

        @Override
        public void handleMessage(Message msg) {
            BluetoothService service = serviceRef.get();
            if (service == null) return;
        }
    }

    private Handler healthCheckHandler = new SafeHandler(this);
    private Runnable healthCheckRunnable = new Runnable() {
        @Override
        public void run() {
            if (state == STATE_NONE || (state == STATE_LISTEN && acceptThread == null)) {
                Log.w(TAG, "健康检查：服务未监听，重新启动");
                start();
            }
            healthCheckHandler.postDelayed(this, 30000); // 每30秒检查一次
        }
    };

    public interface MessageCallback {
        void onMessageReceived(String message, String deviceAddress);
        void onConnectionStatusChanged(int state, String deviceName);
        void onTalkbackDataReceived(byte[] data, String deviceAddress);
        void onNonTextDataReceived(String deviceAddress);
        void onCallRequest(String callerName, String deviceAddress);
        void onCallAccepted(String deviceAddress);
        void onCallRejected(String deviceAddress);
        void onCallHungUp(String deviceAddress);
    }

    public class LocalBinder extends Binder {
        public BluetoothService getService() {
            return BluetoothService.this;
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        state = STATE_NONE;
        createNotificationChannelIfNeeded(); // 统一创建通知渠道
        initKeepAlive();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, createForegroundNotification(),
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
        } else {
            startForeground(NOTIFICATION_ID, createForegroundNotification());
        }
        registerScreenReceiver();
        startHeartbeat();
        // 启动健康检查
        healthCheckHandler.post(healthCheckRunnable);
        // 注意：不自动start，由MainActivity控制
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (state == STATE_NONE) {
            start();
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        healthCheckHandler.removeCallbacks(healthCheckRunnable);
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
        if (alarmManagerHelper != null) {
            alarmManagerHelper.cancelAlarm();
        }
        if (screenReceiver != null) {
            unregisterReceiver(screenReceiver);
        }
        if (alarmReceiver != null) {
            unregisterReceiver(alarmReceiver);
        }
        stopForeground(true);
    }

    // ==================== 通知渠道 ====================
    private void createNotificationChannelIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) {
                // 普通前台服务渠道
                NotificationChannel serviceChannel = nm.getNotificationChannel("bluetooth_service");
                if (serviceChannel == null) {
                    serviceChannel = new NotificationChannel(
                            "bluetooth_service",
                            "蓝牙服务",
                            NotificationManager.IMPORTANCE_LOW
                    );
                    serviceChannel.setDescription("蓝牙服务运行通知");
                    nm.createNotificationChannel(serviceChannel);
                }
                // 召唤通知渠道（高重要性）
                NotificationChannel callChannel = nm.getNotificationChannel("call_channel");
                if (callChannel == null) {
                    callChannel = new NotificationChannel(
                            "call_channel",
                            "召唤提醒",
                            NotificationManager.IMPORTANCE_HIGH
                    );
                    callChannel.setDescription("收到对方召唤时提醒");
                    callChannel.enableVibration(true);
                    callChannel.enableLights(true);
                    nm.createNotificationChannel(callChannel);
                }
            }
        }
    }

    // ==================== 保活机制 ====================
    private void initKeepAlive() {
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BluetoothService:KeepAlive");
        // 优化：使用 try/finally 确保 WakeLock 释放
        wakeLock.acquire(10 * 60 * 1000L);

        alarmManagerHelper = new AlarmManagerHelper(this, HEARTBEAT_INTERVAL);
        alarmReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                Log.d(TAG, "Alarm triggered, restarting service...");
                startService(new Intent(context, BluetoothService.class));
                if (wakeLock != null && !wakeLock.isHeld()) {
                    wakeLock.acquire(10 * 60 * 1000L);
                }
            }
        };
        registerReceiver(alarmReceiver, new IntentFilter(AlarmManagerHelper.ACTION_RESTART_SERVICE));
    }

    private void registerScreenReceiver() {
        screenReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (Intent.ACTION_SCREEN_ON.equals(intent.getAction())) {
                    Log.d(TAG, "Screen on, checking service status...");
                    startService(new Intent(context, BluetoothService.class));
                }
            }
        };
        registerReceiver(screenReceiver, new IntentFilter(Intent.ACTION_SCREEN_ON));
    }

    private void startHeartbeat() {
        if (alarmManagerHelper != null) {
            alarmManagerHelper.startAlarm();
        }
    }

    // ==================== 前台通知 ====================
    private Notification createForegroundNotification() {
        Notification notification;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            notification = new Notification.Builder(this, "bluetooth_service")
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setContentTitle("蓝牙服务运行中")
                    .setContentText("等待连接...")
                    .setPriority(Notification.PRIORITY_LOW)
                    .setOngoing(true)
                    .build();
        } else {
            notification = new NotificationCompat.Builder(this)
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setContentTitle("蓝牙服务运行中")
                    .setContentText("等待连接...")
                    .setPriority(NotificationCompat.PRIORITY_LOW)
                    .setOngoing(true)
                    .build();
        }
        return notification;
    }

    // ==================== 召唤通知 ====================
    private void showCallNotification(String callerName) {
        final String finalCallerName = (callerName == null || callerName.isEmpty()) ? "未知用户" : callerName;
        Log.d(TAG, "显示召唤通知，调用者: " + finalCallerName);

        // Toast（后台可能不显示，但保留）
        new Handler(Looper.getMainLooper()).post(() -> {
            Toast.makeText(BluetoothService.this, finalCallerName + " 召唤您！", Toast.LENGTH_LONG).show();
        });

        Intent intent = new Intent(this, MainActivityNew.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        String targetAddress = connectedDeviceAddress != null ? connectedDeviceAddress : targetDeviceAddress;
        if (targetAddress == null) targetAddress = "";
        intent.putExtra("DEVICE_ADDRESS", targetAddress);
        intent.putExtra("DEVICE_NAME", finalCallerName);
        intent.putExtra("LOAD_FRAGMENT", "ChatWorkFragment");
        intent.putExtra("IS_CALL", true);

        PendingIntent pendingIntent = PendingIntent.getActivity(
                this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification notification;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            notification = new Notification.Builder(this, "call_channel")
                    .setSmallIcon(android.R.drawable.ic_dialog_alert)
                    .setContentTitle("召唤上线")
                    .setContentText(finalCallerName + " 召唤您！")
                    .setAutoCancel(true)
                    .setContentIntent(pendingIntent)
                    .setDefaults(Notification.DEFAULT_ALL)
                    .build();
        } else {
            notification = new NotificationCompat.Builder(this)
                    .setSmallIcon(android.R.drawable.ic_dialog_alert)
                    .setContentTitle("召唤上线")
                    .setContentText(finalCallerName + " 召唤您！")
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setAutoCancel(true)
                    .setContentIntent(pendingIntent)
                    .setDefaults(NotificationCompat.DEFAULT_ALL)
                    .build();
        }

        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.notify(1002, notification);
        }
    }

    // ==================== 公开方法 ====================
    public void setConnectionRole(boolean isInitiator, String targetDeviceAddress) {
        this.isInitiator = isInitiator;
        this.targetDeviceAddress = targetDeviceAddress;
        if (isInitiator && targetDeviceAddress != null) {
            stop();
            BluetoothDevice device = bluetoothAdapter.getRemoteDevice(targetDeviceAddress);
            connect(device);
        } else {
            start();
        }
    }

    public void setMode(int mode) {
        Log.d(TAG, "setMode called: currentMode=" + currentMode + ", newMode=" + mode);
        this.currentMode = mode;
    }

    public int getMode() {
        return currentMode;
    }

    public String getConnectedDeviceAddress() {
        return connectedDeviceAddress;
    }

    public String getConnectedDeviceName() {
        return connectedDeviceName;
    }

    public void registerCallback(MessageCallback callback) {
        if (!messageCallbacks.contains(callback)) {
            messageCallbacks.add(callback);
        }
    }

    public void unregisterCallback(MessageCallback callback) {
        messageCallbacks.remove(callback);
    }

    public synchronized void start() {
        if (state == STATE_LISTEN && acceptThread != null) {
            return;
        }
        if (connectThread != null) {
            connectThread.cancel();
            connectThread = null;
        }
        if (connectedThread != null) {
            connectedThread.cancel();
            connectedThread = null;
        }
        if (acceptThread == null) {
            acceptThread = new AcceptThread();
            if (acceptThread.isFailed()) {
                acceptThread = null;
                setState(STATE_NONE);
                Log.e(TAG, "Failed to start AcceptThread");
                return;
            }
            acceptThread.start();
        }
        setState(STATE_LISTEN);
    }

    public synchronized void connect(BluetoothDevice device) {
        if (state == STATE_CONNECTING) {
            if (connectThread != null) {
                connectThread.cancel();
                connectThread = null;
            }
        }
        if (connectedThread != null) {
            connectedThread.cancel();
            connectedThread = null;
        }
        connectThread = new ConnectThread(device);
        connectThread.start();
        setState(STATE_CONNECTING);
    }

    public synchronized void connected(BluetoothSocket socket, BluetoothDevice device) {
        if (connectThread != null) {
            connectThread.cancel();
            connectThread = null;
        }
        if (connectedThread != null) {
            connectedThread.cancel();
            connectedThread = null;
        }
        if (acceptThread != null) {
            acceptThread.cancel();
            acceptThread = null;
        }

        connectedThread = new ConnectedThread(socket);
        connectedThread.start();

        connectedDeviceAddress = device.getAddress();
        connectedDeviceName = device.getName();
        isInitiator = false;
        targetDeviceAddress = null;

        notifyConnectionStatusChanged(STATE_CONNECTED, device.getName());
        setState(STATE_CONNECTED);
    }

    public synchronized void stop() {
        if (connectThread != null) {
            connectThread.cancel();
            connectThread = null;
        }
        if (connectedThread != null) {
            connectedThread.cancel();
            connectedThread = null;
        }
        if (acceptThread != null) {
            acceptThread.cancel();
            acceptThread = null;
        }
        setState(STATE_NONE);
    }

    public void write(byte[] out) {
        write(out, currentMode);
    }

    public void write(byte[] out, int mode) {
        ConnectedThread r;
        synchronized (this) {
            if (state != STATE_CONNECTED) return;
            r = connectedThread;
        }
        r.write(out, mode);
    }

    public int getState() {
        return state;
    }

    // ==================== 内部通知方法 ====================
    private void notifyMessageReceived(String message, String deviceAddress) {
        new Handler(Looper.getMainLooper()).post(() -> {
            for (MessageCallback callback : messageCallbacks) {
                callback.onMessageReceived(message, deviceAddress);
            }
        });
    }

    private void notifyConnectionStatusChanged(int state, String deviceName) {
        new Handler(Looper.getMainLooper()).post(() -> {
            for (MessageCallback callback : messageCallbacks) {
                callback.onConnectionStatusChanged(state, deviceName);
            }
        });
    }

    private void notifyTalkbackDataReceived(byte[] data, String deviceAddress) {
        new Handler(Looper.getMainLooper()).post(() -> {
            for (MessageCallback callback : messageCallbacks) {
                callback.onTalkbackDataReceived(data, deviceAddress);
            }
        });
    }

    private void notifyNonTextDataReceived(String deviceAddress) {
        new Handler(Looper.getMainLooper()).post(() -> {
            for (MessageCallback callback : messageCallbacks) {
                callback.onNonTextDataReceived(deviceAddress);
            }
        });
    }

    private void notifyCallRequest(String callerName, String deviceAddress) {
        new Handler(Looper.getMainLooper()).post(() -> {
            for (MessageCallback callback : messageCallbacks) {
                callback.onCallRequest(callerName, deviceAddress);
            }
        });
    }

    private void notifyCallAccepted(String deviceAddress) {
        new Handler(Looper.getMainLooper()).post(() -> {
            for (MessageCallback callback : messageCallbacks) {
                callback.onCallAccepted(deviceAddress);
            }
        });
    }

    private void notifyCallRejected(String deviceAddress) {
        new Handler(Looper.getMainLooper()).post(() -> {
            for (MessageCallback callback : messageCallbacks) {
                callback.onCallRejected(deviceAddress);
            }
        });
    }

    private void notifyCallHungUp(String deviceAddress) {
        new Handler(Looper.getMainLooper()).post(() -> {
            for (MessageCallback callback : messageCallbacks) {
                callback.onCallHungUp(deviceAddress);
            }
        });
    }

    private void setState(int newState) {
        state = newState;
    }

    private void connectionFailed() {
        notifyConnectionStatusChanged(STATE_LISTEN, "连接失败");
        if (isInitiator) {
            isInitiator = false;
            targetDeviceAddress = null;
        }
        setState(STATE_LISTEN);
        BluetoothService.this.start();
    }

    private void connectionLost() {
        notifyConnectionStatusChanged(STATE_LISTEN, "连接断开");
        isInitiator = false;
        targetDeviceAddress = null;
        setState(STATE_LISTEN);
        BluetoothService.this.start();
    }

    // ==================== 内部线程 ====================
    private class AcceptThread extends Thread {
        private final BluetoothServerSocket serverSocket;
        private boolean failed = false;

        public AcceptThread() {
            BluetoothServerSocket tmp = null;
            try {
                tmp = bluetoothAdapter.listenUsingRfcommWithServiceRecord(APP_NAME, MY_UUID);
                Log.d(TAG, "AcceptThread: server socket created");
            } catch (SecurityException e) {
                Log.e(TAG, "Bluetooth permission not granted", e);
                failed = true;
            } catch (IOException e) {
                Log.e(TAG, "Socket listen() failed", e);
                failed = true;
            }
            serverSocket = tmp;
        }

        public boolean isFailed() {
            return failed || serverSocket == null;
        }

        public void run() {
            if (isFailed()) {
                Log.e(TAG, "AcceptThread cannot run because serverSocket is null");
                return;
            }
            setName("AcceptThread");
            BluetoothSocket socket;
            while (state != STATE_CONNECTED) {
                try {
                    socket = serverSocket.accept();
                } catch (IOException e) {
                    Log.e(TAG, "Socket accept() failed", e);
                    break;
                }
                if (socket != null) {
                    synchronized (BluetoothService.this) {
                        switch (state) {
                            case STATE_LISTEN:
                            case STATE_CONNECTING:
                                connected(socket, socket.getRemoteDevice());
                                break;
                            case STATE_NONE:
                            case STATE_CONNECTED:
                                try {
                                    socket.close();
                                } catch (IOException e) {
                                    Log.e(TAG, "Could not close unwanted socket", e);
                                }
                                break;
                        }
                    }
                }
            }
        }

        public void cancel() {
            try {
                if (serverSocket != null) serverSocket.close();
            } catch (IOException e) {
                Log.e(TAG, "Socket close() of server failed", e);
            }
        }
    }

    private class ConnectThread extends Thread {
        private final BluetoothSocket socket;
        private final BluetoothDevice device;

        public ConnectThread(BluetoothDevice device) {
            this.device = device;
            BluetoothSocket tmp = null;
            try {
                tmp = device.createRfcommSocketToServiceRecord(MY_UUID);
            } catch (SecurityException e) {
                Log.e(TAG, "Bluetooth permission not granted", e);
            } catch (IOException e) {
                Log.e(TAG, "Socket create() failed", e);
            }
            socket = tmp;
        }

        public void run() {
            setName("ConnectThread");
            try {
                bluetoothAdapter.cancelDiscovery();
            } catch (SecurityException e) {
                Log.e(TAG, "Bluetooth permission not granted for cancelDiscovery", e);
                connectionFailed();
                return;
            }
            try {
                socket.connect();
            } catch (IOException e) {
                connectionFailed();
                try {
                    socket.close();
                } catch (IOException e2) {
                    Log.e(TAG, "unable to close() socket during connection failure", e2);
                }
                return;
            }
            synchronized (BluetoothService.this) {
                connectThread = null;
            }
            connected(socket, device);
        }

        public void cancel() {
            try {
                socket.close();
            } catch (IOException e) {
                Log.e(TAG, "close() of connect socket failed", e);
            }
        }
    }

    private class ConnectedThread extends Thread {
        private final BluetoothSocket socket;
        private final InputStream inputStream;
        private final OutputStream outputStream;
        private boolean isRunning = true;

        public ConnectedThread(BluetoothSocket socket) {
            this.socket = socket;
            InputStream tmpIn = null;
            OutputStream tmpOut = null;
            try {
                tmpIn = socket.getInputStream();
                tmpOut = socket.getOutputStream();
            } catch (IOException e) {
                Log.e(TAG, "temp sockets not created", e);
            }
            // 优化：使用 BufferedInputStream/BufferedOutputStream 减少系统调用次数
            inputStream = (tmpIn != null) ? new BufferedInputStream(tmpIn, 8192) : null;
            outputStream = (tmpOut != null) ? new BufferedOutputStream(tmpOut, 8192) : null;
        }

        public void run() {
            byte[] buffer = new byte[8192]; // 优化：缓冲区从1024提升到8192
            byte[] lenBuffer = new byte[4];

            while (isRunning) {
                try {
                    // 优化：使用长度前缀协议，防止粘包/拆包
                    readFully(lenBuffer, 0, 4);
                    int payloadLength = bytesToInt(lenBuffer);
                    if (payloadLength <= 0 || payloadLength > buffer.length) {
                        Log.e(TAG, "Invalid payload length: " + payloadLength);
                        break;
                    }
                    byte[] payload = new byte[payloadLength];
                    readFully(payload, 0, payloadLength);

                    if (isTextMessage(payload, payloadLength)) {
                        String message = new String(payload, TEXT_PREFIX_BYTES.length,
                                payloadLength - TEXT_PREFIX_BYTES.length, StandardCharsets.UTF_8);
                        handleTextMessage(message);
                    } else {
                        if (currentMode == MODE_TALKBACK) {
                            notifyTalkbackDataReceived(payload, socket.getRemoteDevice().getAddress());
                        } else {
                            Log.w(TAG, "Received non-text data in chat mode");
                            notifyNonTextDataReceived(socket.getRemoteDevice().getAddress());
                        }
                    }
                } catch (IOException e) {
                    connectionLost();
                    break;
                }
            }
        }

        private void handleTextMessage(String message) {
            String deviceAddress = socket.getRemoteDevice().getAddress();
            // 去除首尾空格，防止匹配失败
            String trimmed = message.trim();

            // 呼叫控制消息（优先匹配）
            if (trimmed.startsWith(CALL_REQUEST)) {
                String callerName = trimmed.substring(CALL_REQUEST.length());
                if (callerName.isEmpty()) callerName = "未知用户";
                notifyCallRequest(callerName, deviceAddress);
                return;
            }
            if (trimmed.equals(CALL_ACCEPT)) {
                notifyCallAccepted(deviceAddress);
                return;
            }
            if (trimmed.equals(CALL_REJECT)) {
                notifyCallRejected(deviceAddress);
                return;
            }
            if (trimmed.equals(CALL_HANGUP)) {
                notifyCallHungUp(deviceAddress);
                return;
            }

            // 旧版召唤
            if (trimmed.startsWith(CALL_PREFIX)) {
                String callerName = trimmed.substring(CALL_PREFIX.length());
                if (callerName.isEmpty()) callerName = "未知用户";
                showCallNotification(callerName);
                return;
            }

            // 文件控制消息（透传）
            if (trimmed.startsWith(FILE_REQUEST_PREFIX) ||
                    trimmed.equals(FILE_ACCEPT) ||
                    trimmed.equals(FILE_REJECT)) {
                notifyMessageReceived(message, deviceAddress); // 透传原始消息
                return;
            }

            // 普通文本消息
            saveMessageToFile(message, deviceAddress, false);
            notifyMessageReceived(message, deviceAddress);
        }
        private boolean isTextMessage(byte[] data, int length) {
            if (length < TEXT_PREFIX_BYTES.length) return false;
            for (int i = 0; i < TEXT_PREFIX_BYTES.length; i++) {
                if (data[i] != TEXT_PREFIX_BYTES[i]) return false;
            }
            return true;
        }

        // 优化：确保读取指定长度的数据（防止粘包/拆包）
        private void readFully(byte[] buffer, int offset, int length) throws IOException {
            int totalRead = 0;
            while (totalRead < length) {
                int n = inputStream.read(buffer, offset + totalRead, length - totalRead);
                if (n == -1) throw new IOException("EOF while reading fully");
                totalRead += n;
            }
        }

        private byte[] intToBytes(int value) {
            return new byte[]{
                    (byte) (value >> 24),
                    (byte) (value >> 16),
                    (byte) (value >> 8),
                    (byte) value
            };
        }

        private int bytesToInt(byte[] bytes) {
            return ((bytes[0] & 0xFF) << 24) |
                    ((bytes[1] & 0xFF) << 16) |
                    ((bytes[2] & 0xFF) << 8) |
                    (bytes[3] & 0xFF);
        }

        public void write(byte[] buffer) {
            write(buffer, currentMode);
        }

        public void write(byte[] buffer, int mode) {
            try {
                // 优化：添加4字节长度前缀，防止粘包/拆包
                byte[] lenBytes = intToBytes(buffer.length);
                outputStream.write(lenBytes);
                outputStream.write(buffer);
                outputStream.flush();
                if (mode == MODE_CHAT) {
                    String message = new String(buffer);
                    if (message.startsWith(TEXT_PREFIX)) {
                        message = message.substring(TEXT_PREFIX.length());
                    }
                    // 过滤控制消息
                    if (!message.startsWith(CALL_REQUEST) && !message.startsWith(CALL_PREFIX) &&
                            !message.equals(CALL_ACCEPT) && !message.equals(CALL_REJECT) && !message.equals(CALL_HANGUP) &&
                            !message.startsWith(FILE_REQUEST_PREFIX) && !message.equals(FILE_ACCEPT) && !message.equals(FILE_REJECT)) {
                        saveMessageToFile(message, socket.getRemoteDevice().getAddress(), true);
                    }
                }
            } catch (IOException e) {
                Log.e(TAG, "Exception during write", e);
            }
        }

        public void cancel() {
            isRunning = false;
            try {
                socket.close();
            } catch (IOException e) {
                Log.e(TAG, "close() of connect socket failed", e);
            }
        }
    }

    // ==================== 文件存储 ====================
    private void saveMessageToFile(String message, String deviceAddress, boolean isSent) {
        if (message.startsWith(CALL_PREFIX) || message.startsWith(CALL_REQUEST) ||
                message.equals(CALL_ACCEPT) || message.equals(CALL_REJECT) || message.equals(CALL_HANGUP) ||
                message.startsWith(FILE_REQUEST_PREFIX) || message.equals(FILE_ACCEPT) || message.equals(FILE_REJECT)) {
            return;
        }

        synchronized (BluetoothService.class) {
            try {
                String filename = "chat_" + deviceAddress.replace(":", "_") + ".txt";
                File file = new File(getExternalFilesDir(null), filename);
                SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());
                String timestamp = sdf.format(new Date());
                String sender = isSent ? "我" : "对方";
                String newLine = timestamp + ": " + sender + ": " + message;

                // 优化：使用 try-with-resources 确保资源释放
                if (file.exists()) {
                    try (BufferedReader reader = new BufferedReader(
                            new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
                        String lastLine = null;
                        String line;
                        while ((line = reader.readLine()) != null) {
                            lastLine = line;
                        }
                        if (lastLine != null && lastLine.equals(newLine)) {
                            return;
                        }
                    }
                }
                try (FileOutputStream fos = new FileOutputStream(file, true);
                     OutputStreamWriter osw = new OutputStreamWriter(fos, StandardCharsets.UTF_8)) {
                    osw.write(newLine + "\n");
                }
            } catch (IOException e) {
                Log.e(TAG, "Error saving message to file", e);
            }
        }
    }

    public String loadChatHistory(String deviceAddress) {
        StringBuilder chatHistory = new StringBuilder();
        try {
            String filename = "chat_" + deviceAddress.replace(":", "_") + ".txt";
            File file = new File(getExternalFilesDir(null), filename);
            if (file.exists()) {
                // 优化：使用 try-with-resources 确保资源释放
                try (InputStream inputStream = getContentResolver().openInputStream(android.net.Uri.fromFile(file));
                     BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        chatHistory.append(line).append("\n");
                    }
                }
            }
        } catch (IOException e) {
            Log.e(TAG, "Error loading chat history", e);
        }
        return chatHistory.toString();
    }
}