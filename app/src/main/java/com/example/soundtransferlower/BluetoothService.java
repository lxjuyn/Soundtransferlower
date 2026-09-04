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
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import androidx.core.app.NotificationCompat;
import android.util.Log;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

public class BluetoothService extends Service implements IBluetoothService {
    private static final String TAG = "BluetoothService";
    private static final String APP_NAME = "SoundTransfer";
    private static final UUID MY_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
    public static final String TEXT_PREFIX = "TXT:";
    public static final byte[] TEXT_PREFIX_BYTES = TEXT_PREFIX.getBytes();

    public static final String FILE_REQUEST_PREFIX = "FILE_REQUEST:";
    public static final String FILE_ACCEPT = "FILE_ACCEPT";
    public static final String FILE_REJECT = "FILE_REJECT";
    public static final String CALL_PREFIX = "CALL:";
    public static final String CALL_REQUEST = "CALL_REQUEST:";
    public static final String CALL_ACCEPT = "CALL_ACCEPT";
    public static final String CALL_REJECT = "CALL_REJECT";
    public static final String CALL_HANGUP = "CALL_HANGUP";
    //==========确认============
    // 在 BluetoothService 类中添加
    private long confirmedTimestamp = 0;

    public long getConfirmedTimestamp() {
        return confirmedTimestamp;
    }

    // ---------- Binder ----------
    public class LocalBinder extends Binder {
        public BluetoothService getService() {
            return BluetoothService.this;
        }
        public IBluetoothService getInterface() {
            return BluetoothService.this;
        }
    }

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

    private int currentMode = MODE_CHAT;

    private CopyOnWriteArrayList<IMessageCallback.MessageCallback> messageCallbacks = new CopyOnWriteArrayList<>();

    // ---- 保活相关 ----
    private PowerManager.WakeLock wakeLock;
    private AlarmManagerHelper alarmManagerHelper;
    private BroadcastReceiver screenReceiver;
    private BroadcastReceiver alarmReceiver;
    private static final int NOTIFICATION_ID = 1001;
    private static final long HEARTBEAT_INTERVAL = 30 * 60 * 1000;
    private Handler healthCheckHandler = new Handler(Looper.getMainLooper());
    private Runnable healthCheckRunnable = new Runnable() {
        @Override
        public void run() {
            if (state == STATE_NONE || (state == STATE_LISTEN && acceptThread == null)) {
                LogUtil.w(TAG, "健康检查：服务未监听，重新启动");
                start();
            }
            healthCheckHandler.postDelayed(this, 30000);
        }
    };

    // ---------- 生命周期 ----------
    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        state = STATE_NONE;
        createNotificationChannelIfNeeded();
        initKeepAlive();
        // targetSdk 34：前台服务必须指定 connectedDevice 类型
        if (android.os.Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, createForegroundNotification(),
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
        } else {
            startForeground(NOTIFICATION_ID, createForegroundNotification());
        }
        registerScreenReceiver();
        startHeartbeat();
        healthCheckHandler.post(healthCheckRunnable);
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

    // ==================== 实现 IBluetoothService 接口 ====================
    @Override
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
                LogUtil.e(TAG, "Failed to start AcceptThread");
                return;
            }
            acceptThread.start();
        }
        setState(STATE_LISTEN);
    }

    @Override
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

    @Override
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

    @Override
    public void connect(String deviceAddress) {
        if (bluetoothAdapter == null) return;
        BluetoothDevice device = bluetoothAdapter.getRemoteDevice(deviceAddress);
        connect(device);
    }

    @Override
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

    @Override
    public void setMode(int mode) {
        this.currentMode = mode;
    }

    @Override
    public int getMode() {
        return currentMode;
    }

    @Override
    public String getConnectedDeviceAddress() {
        return connectedDeviceAddress;
    }

    @Override
    public String getConnectedDeviceName() {
        return connectedDeviceName;
    }

    @Override
    public int getState() {
        return state;
    }

    @Override
    public void write(byte[] out) {
        write(out, currentMode);
    }

    @Override
    public void write(byte[] out, int mode) {
        ConnectedThread r;
        synchronized (this) {
            if (state != STATE_CONNECTED) return;
            r = connectedThread;
        }
        r.write(out, mode);
    }

    @Override
    public void registerCallback(IMessageCallback.MessageCallback callback) {
        if (!messageCallbacks.contains(callback)) {
            messageCallbacks.add(callback);
        }
    }

    @Override
    public void unregisterCallback(IMessageCallback.MessageCallback callback) {
        messageCallbacks.remove(callback);
    }

    @Override
    public String loadChatHistory(String deviceAddress) {
        StringBuilder chatHistory = new StringBuilder();
        try {
            String filename = "chat_" + deviceAddress.replace(":", "_") + ".txt";
            File file = new File(getExternalFilesDir(null), filename);
            if (file.exists()) {
                InputStream inputStream = getContentResolver().openInputStream(android.net.Uri.fromFile(file));
                BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream));
                String line;
                while ((line = reader.readLine()) != null) {
                    chatHistory.append(line).append("\n");
                }
                reader.close();
            }
        } catch (IOException e) {
            LogUtil.e(TAG, "Error loading chat history", e);
        }
        return chatHistory.toString();
    }

    @Override
    public IBluetoothService getInterface() {
        return this;
    }

    // ==================== 原有私有方法（完整保留） ====================
    private void createNotificationChannelIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) {
                NotificationChannel serviceChannel = nm.getNotificationChannel("bluetooth_channel");
                if (serviceChannel == null) {
                    serviceChannel = new NotificationChannel(
                            "bluetooth_channel",
                            "蓝牙服务",
                            NotificationManager.IMPORTANCE_LOW
                    );
                    serviceChannel.setDescription("蓝牙服务运行通知");
                    nm.createNotificationChannel(serviceChannel);
                }
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

    private void initKeepAlive() {
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BluetoothService:KeepAlive");
        wakeLock.acquire(10 * 60 * 1000L);

        alarmManagerHelper = new AlarmManagerHelper(this, HEARTBEAT_INTERVAL);
        alarmReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                LogUtil.d(TAG, "Alarm triggered, restarting service...");
                startService(new Intent(context, BluetoothService.class));
                if (wakeLock != null && !wakeLock.isHeld()) {
                    wakeLock.acquire(10 * 60 * 1000L);
                }
            }
        };
        // API 33+：非系统广播必须显式声明导出标志，否则 registerReceiver 崩溃
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            registerReceiver(alarmReceiver, new IntentFilter(AlarmManagerHelper.ACTION_RESTART_SERVICE),
                    android.content.Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(alarmReceiver, new IntentFilter(AlarmManagerHelper.ACTION_RESTART_SERVICE));
        }
    }

    private void registerScreenReceiver() {
        screenReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (Intent.ACTION_SCREEN_ON.equals(intent.getAction())) {
                    LogUtil.d(TAG, "Screen on, checking service status...");
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

    private Notification createForegroundNotification() {
        Notification notification;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            notification = new Notification.Builder(this, "bluetooth_channel")
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

    private void showCallNotification(String callerName) {
        final String finalCallerName = (callerName == null || callerName.isEmpty()) ? "未知用户" : callerName;
        LogUtil.d(TAG, "显示召唤通知，调用者: " + finalCallerName);

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
                this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT);

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

    private void notifyMessageReceived(String message, String deviceAddress) {
        new Handler(Looper.getMainLooper()).post(() -> {
            for (IMessageCallback.MessageCallback callback : messageCallbacks) {
                callback.onMessageReceived(message, deviceAddress);
            }
        });
    }

    private void notifyConnectionStatusChanged(int state, String deviceName) {
        new Handler(Looper.getMainLooper()).post(() -> {
            for (IMessageCallback.MessageCallback callback : messageCallbacks) {
                callback.onConnectionStatusChanged(state, deviceName);
            }
        });
    }

    private void notifyTalkbackDataReceived(byte[] data, String deviceAddress) {
        new Handler(Looper.getMainLooper()).post(() -> {
            for (IMessageCallback.MessageCallback callback : messageCallbacks) {
                callback.onTalkbackDataReceived(data, deviceAddress);
            }
        });
    }

    private void notifyNonTextDataReceived(String deviceAddress) {
        new Handler(Looper.getMainLooper()).post(() -> {
            for (IMessageCallback.MessageCallback callback : messageCallbacks) {
                callback.onNonTextDataReceived(deviceAddress);
            }
        });
    }

    private void notifyCallRequest(String callerName, String deviceAddress) {
        new Handler(Looper.getMainLooper()).post(() -> {
            for (IMessageCallback.MessageCallback callback : messageCallbacks) {
                callback.onCallRequest(callerName, deviceAddress);
            }
        });
    }

    private void notifyCallAccepted(String deviceAddress) {
        new Handler(Looper.getMainLooper()).post(() -> {
            for (IMessageCallback.MessageCallback callback : messageCallbacks) {
                callback.onCallAccepted(deviceAddress);
            }
        });
    }

    private void notifyCallRejected(String deviceAddress) {
        new Handler(Looper.getMainLooper()).post(() -> {
            for (IMessageCallback.MessageCallback callback : messageCallbacks) {
                callback.onCallRejected(deviceAddress);
            }
        });
    }

    private void notifyCallHungUp(String deviceAddress) {
        new Handler(Looper.getMainLooper()).post(() -> {
            for (IMessageCallback.MessageCallback callback : messageCallbacks) {
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

                if (file.exists()) {
                    BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(file), "UTF-8"));
                    String lastLine = null;
                    String line;
                    while ((line = reader.readLine()) != null) {
                        lastLine = line;
                    }
                    reader.close();
                    if (lastLine != null && lastLine.equals(newLine)) {
                        return;
                    }
                }
                FileOutputStream fos = new FileOutputStream(file, true);
                OutputStreamWriter osw = new OutputStreamWriter(fos, "UTF-8");
                osw.write(newLine + "\n");
                osw.close();
            } catch (IOException e) {
                LogUtil.e(TAG, "Error saving message to file", e);
            }
        }
    }

    // ==================== 内部线程类（完整实现） ====================
    private class AcceptThread extends Thread {
        private final BluetoothServerSocket serverSocket;
        private boolean failed = false;

        public AcceptThread() {
            BluetoothServerSocket tmp = null;
            try {
                tmp = bluetoothAdapter.listenUsingRfcommWithServiceRecord(APP_NAME, MY_UUID);
                LogUtil.d(TAG, "AcceptThread: server socket created");
            } catch (IOException e) {
                LogUtil.e(TAG, "Socket listen() failed", e);
                failed = true;
            }
            serverSocket = tmp;
        }

        public boolean isFailed() {
            return failed || serverSocket == null;
        }

        public void run() {
            if (isFailed()) {
                LogUtil.e(TAG, "AcceptThread cannot run because serverSocket is null");
                return;
            }
            setName("AcceptThread");
            BluetoothSocket socket;
            while (state != STATE_CONNECTED) {
                try {
                    socket = serverSocket.accept();
                } catch (IOException e) {
                    LogUtil.e(TAG, "Socket accept() failed", e);
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
                                    LogUtil.e(TAG, "Could not close unwanted socket", e);
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
                LogUtil.e(TAG, "Socket close() of server failed", e);
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
            } catch (IOException e) {
                LogUtil.e(TAG, "Socket create() failed", e);
            }
            socket = tmp;
        }

        public void run() {
            setName("ConnectThread");
            bluetoothAdapter.cancelDiscovery();
            try {
                socket.connect();
            } catch (IOException e) {
                connectionFailed();
                try {
                    socket.close();
                } catch (IOException e2) {
                    LogUtil.e(TAG, "unable to close() socket during connection failure", e2);
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
                LogUtil.e(TAG, "close() of connect socket failed", e);
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
                LogUtil.e(TAG, "temp sockets not created", e);
            }
            inputStream = tmpIn;
            outputStream = tmpOut;
        }

        public void run() {
            byte[] buffer = new byte[1024];
            int bytes;

            while (isRunning) {
                try {
                    bytes = inputStream.read(buffer);
                    if (bytes > 0) {
                        if (isTextMessage(buffer, bytes)) {
                            String message = new String(buffer, TEXT_PREFIX_BYTES.length, bytes - TEXT_PREFIX_BYTES.length);
                            handleTextMessage(message);
                        } else {
                            // 非文本数据
                            if (currentMode == MODE_TALKBACK) {
                                byte[] audioData = new byte[bytes];
                                System.arraycopy(buffer, 0, audioData, 0, bytes);
                                notifyTalkbackDataReceived(audioData, socket.getRemoteDevice().getAddress());

                                // ★★★ 发送语音确认消息 ★★★
                                sendConfirmMessage(System.currentTimeMillis());
                            } else {
                                LogUtil.w(TAG, "Received non-text data in chat mode");
                                notifyNonTextDataReceived(socket.getRemoteDevice().getAddress());
                            }
                        }
                    }
                } catch (IOException e) {
                    connectionLost();
                    break;
                }
            }
        }

        // ★★★ 新增方法：发送确认消息 ★★★
        private void sendConfirmMessage(long timestamp) {
            String confirmMsg = TEXT_PREFIX + "CONFIRM:" + timestamp;
            write(confirmMsg.getBytes(), MODE_CHAT);
        }
        private void handleTextMessage(String message) {
            String deviceAddress = socket.getRemoteDevice().getAddress();
            String trimmed = message.trim();

            // ★★★ 1. 检测是否为确认消息 ★★★
            if (trimmed.startsWith("CONFIRM:")) {
                String tsStr = trimmed.substring("CONFIRM:".length());
                try {
                    long ts = Long.parseLong(tsStr);
                    confirmedTimestamp = ts;
                    notifyMessageConfirmed(ts);
                } catch (NumberFormatException e) {
                    LogUtil.e(TAG, "解析确认时间戳失败", e);
                }
                return; // 确认消息不进入后续处理
            }

            // 2. 呼叫控制消息
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

            // 3. 召唤消息
            if (trimmed.startsWith(CALL_PREFIX)) {
                String callerName = trimmed.substring(CALL_PREFIX.length());
                if (callerName.isEmpty()) callerName = "未知用户";
                showCallNotification(callerName);
                return;
            }

            // 4. 文件控制消息
            if (trimmed.startsWith(FILE_REQUEST_PREFIX) ||
                    trimmed.equals(FILE_ACCEPT) ||
                    trimmed.equals(FILE_REJECT)) {
                notifyMessageReceived(message, deviceAddress);
                return;
            }

            // 5. ★★★ 普通文本消息（需要发送确认） ★★★
            saveMessageToFile(message, deviceAddress, false);
            notifyMessageReceived(message, deviceAddress);

            // 6. ★★★ 发送确认消息（携带当前时间戳） ★★★
            String confirmMsg = TEXT_PREFIX + "CONFIRM:" + System.currentTimeMillis();
            write(confirmMsg.getBytes());
        }

        private boolean isTextMessage(byte[] data, int length) {
            if (length < TEXT_PREFIX_BYTES.length) return false;
            for (int i = 0; i < TEXT_PREFIX_BYTES.length; i++) {
                if (data[i] != TEXT_PREFIX_BYTES[i]) return false;
            }
            return true;
        }

        public void write(byte[] buffer) {
            write(buffer, currentMode);
        }

        public void write(byte[] buffer, int mode) {
            try {
                outputStream.write(buffer);
                outputStream.flush();
                if (mode == MODE_CHAT) {
                    String message = new String(buffer);
                    if (message.startsWith(TEXT_PREFIX)) {
                        message = message.substring(TEXT_PREFIX.length());
                    }
                    // ★★★ 过滤确认消息，不保存 ★★★
                    if (!message.startsWith("CONFIRM:") &&
                            !message.startsWith(CALL_REQUEST) && !message.startsWith(CALL_PREFIX) &&
                            !message.equals(CALL_ACCEPT) && !message.equals(CALL_REJECT) && !message.equals(CALL_HANGUP) &&
                            !message.startsWith(FILE_REQUEST_PREFIX) && !message.equals(FILE_ACCEPT) && !message.equals(FILE_REJECT)) {
                        saveMessageToFile(message, socket.getRemoteDevice().getAddress(), true);
                    }
                }
            } catch (IOException e) {
                LogUtil.e(TAG, "Exception during write", e);
            }
        }
        public void cancel() {
            isRunning = false;
            try {
                socket.close();
            } catch (IOException e) {
                LogUtil.e(TAG, "close() of connect socket failed", e);
            }
        }
    }
    //===========确认==============
    private void notifyMessageConfirmed(long timestamp) {
        new Handler(Looper.getMainLooper()).post(() -> {
            for (IMessageCallback.MessageCallback callback : messageCallbacks) {
                callback.onMessageConfirmed(timestamp);
            }
        });
    }
}