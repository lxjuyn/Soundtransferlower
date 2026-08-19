package com.example.soundtransferlower;

import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.content.Intent;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.CRC32;

public class BluetoothFileTransferService extends Service {
    private static final String TAG = "FileTransferService";
    private static final String APP_NAME = "SoundTransferFile";
    private static final UUID MY_UUID = UUID.fromString("fa87c0d0-afac-11de-8a39-0800200c9a66");

    private final IBinder binder = new LocalBinder();
    private BluetoothAdapter bluetoothAdapter;
    private AcceptThread acceptThread;
    private ConnectThread connectThread;
    private ConnectedThread connectedThread;
    private int state = STATE_NONE;

    public static final int STATE_NONE = 0;
    public static final int STATE_LISTEN = 1;
    public static final int STATE_CONNECTING = 2;
    public static final int STATE_CONNECTED = 3;

    private String action;
    private String deviceAddress;
    private String filePath;
    private String fileName;
    private String saveDir;
    private String receiveFileName;

    private final CopyOnWriteArrayList<FileTransferCallback> callbacks = new CopyOnWriteArrayList<>();
    private Handler mainHandler = new Handler(Looper.getMainLooper());
    // 优化：文件传输使用独立线程池，避免阻塞主蓝牙通道
    private final ExecutorService fileTransferExecutor = Executors.newSingleThreadExecutor();

    public interface FileTransferCallback {
        void onTransferComplete(boolean success, String filePath);
        void onProgressUpdate(long totalBytes, long transferredBytes, int progress);
    }

    public class LocalBinder extends Binder {
        public BluetoothFileTransferService getService() {
            return BluetoothFileTransferService.this;
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        Log.d(TAG, "onBind");
        return binder;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "onStartCommand, intent=" + intent);
        if (intent != null) {
            action = intent.getStringExtra("ACTION");
            deviceAddress = intent.getStringExtra("DEVICE_ADDRESS");
            if ("SEND".equals(action)) {
                filePath = intent.getStringExtra("FILE_PATH");
                fileName = intent.getStringExtra("FILE_NAME");
                Log.d(TAG, "发送文件: " + fileName + ", path=" + filePath);
            } else if ("RECEIVE".equals(action)) {
                saveDir = intent.getStringExtra("SAVE_DIR");
                receiveFileName = intent.getStringExtra("FILE_NAME");
                Log.d(TAG, "接收文件, 保存目录: " + saveDir + ", 文件名: " + receiveFileName);
            } else {
                Log.e(TAG, "未知action: " + action);
            }
            startTransfer();
        } else {
            Log.w(TAG, "onStartCommand with null intent");
        }
        return START_NOT_STICKY;
    }

    public void registerCallback(FileTransferCallback callback) {
        if (!callbacks.contains(callback)) {
            callbacks.add(callback);
            Log.d(TAG, "注册回调, 当前回调数: " + callbacks.size());
        }
    }

    public void unregisterCallback(FileTransferCallback callback) {
        callbacks.remove(callback);
        Log.d(TAG, "注销回调, 当前回调数: " + callbacks.size());
    }

    private void notifyComplete(boolean success, String filePath) {
        Log.d(TAG, "notifyComplete: success=" + success + ", filePath=" + filePath);
        mainHandler.post(() -> {
            for (FileTransferCallback cb : callbacks) {
                cb.onTransferComplete(success, filePath);
            }
        });
    }

    private void notifyProgress(long total, long transferred, int progress) {
        mainHandler.post(() -> {
            for (FileTransferCallback cb : callbacks) {
                cb.onProgressUpdate(total, transferred, progress);
            }
        });
    }

    private synchronized void startTransfer() {
        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        if (bluetoothAdapter == null) {
            Log.e(TAG, "蓝牙不可用");
            notifyComplete(false, null);
            stopSelf();
            return;
        }

        if ("SEND".equals(action)) {
            if (deviceAddress == null || filePath == null || !new File(filePath).exists()) {
                Log.e(TAG, "发送参数无效或文件不存在");
                notifyComplete(false, null);
                stopSelf();
                return;
            }
            BluetoothDevice device = bluetoothAdapter.getRemoteDevice(deviceAddress);
            Log.d(TAG, "开始连接发送设备: " + deviceAddress);
            connect(device);
        } else if ("RECEIVE".equals(action)) {
            Log.d(TAG, "开始监听接收");
            startListening();
        } else {
            Log.e(TAG, "无效action: " + action);
            notifyComplete(false, null);
            stopSelf();
        }
    }

    private synchronized void startListening() {
        if (acceptThread == null) {
            acceptThread = new AcceptThread();
            if (acceptThread.isFailed()) {
                Log.e(TAG, "AcceptThread creation failed");
                notifyComplete(false, null);
                stopSelf();
                return;
            }
            acceptThread.start();
            Log.d(TAG, "AcceptThread started");
        }
        setState(STATE_LISTEN);
    }

    private synchronized void connect(BluetoothDevice device) {
        if (connectThread != null) {
            connectThread.cancel();
            connectThread = null;
        }
        if (connectedThread != null) {
            connectedThread.cancel();
            connectedThread = null;
        }
        connectThread = new ConnectThread(device);
        connectThread.start();
        setState(STATE_CONNECTING);
        Log.d(TAG, "ConnectThread started, state=CONNECTING");
    }

    private synchronized void connected(BluetoothSocket socket, BluetoothDevice device) {
        Log.d(TAG, "connected to " + device.getAddress());
        if (connectThread != null) {
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
        // 优化：文件传输使用独立线程池，避免阻塞主蓝牙通道
        fileTransferExecutor.execute(connectedThread);
        setState(STATE_CONNECTED);
        Log.d(TAG, "ConnectedThread started");
    }

    private synchronized void stopAll() {
        Log.d(TAG, "stopAll");
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
    public void onDestroy() {
        super.onDestroy();
        // 优化：释放线程池资源
        fileTransferExecutor.shutdownNow();
    }

    private void setState(int state) {
        this.state = state;
        Log.d(TAG, "setState: " + state);
    }

    // ==================== 协议工具方法 ====================
    private static byte[] intToBytes(int value) {
        return new byte[]{
                (byte) (value >> 24),
                (byte) (value >> 16),
                (byte) (value >> 8),
                (byte) value
        };
    }

    private static int bytesToInt(byte[] bytes) {
        return ((bytes[0] & 0xFF) << 24) |
                ((bytes[1] & 0xFF) << 16) |
                ((bytes[2] & 0xFF) << 8) |
                (bytes[3] & 0xFF);
    }

    private static byte[] shortToBytes(short value) {
        return new byte[]{
                (byte) (value >> 8),
                (byte) value
        };
    }

    private static short bytesToShort(byte[] bytes) {
        return (short) (((bytes[0] & 0xFF) << 8) | (bytes[1] & 0xFF));
    }

    // 优化：支持long(8字节)文件长度，支持>2GB文件
    private static byte[] longToBytes(long value) {
        return new byte[]{
                (byte) (value >> 56),
                (byte) (value >> 48),
                (byte) (value >> 40),
                (byte) (value >> 32),
                (byte) (value >> 24),
                (byte) (value >> 16),
                (byte) (value >> 8),
                (byte) value
        };
    }

    private static long bytesToLong(byte[] bytes) {
        return ((long) (bytes[0] & 0xFF) << 56) |
                ((long) (bytes[1] & 0xFF) << 48) |
                ((long) (bytes[2] & 0xFF) << 40) |
                ((long) (bytes[3] & 0xFF) << 32) |
                ((long) (bytes[4] & 0xFF) << 24) |
                ((long) (bytes[5] & 0xFF) << 16) |
                ((long) (bytes[6] & 0xFF) << 8) |
                ((long) (bytes[7] & 0xFF));
    }

    // ---------- 内部线程 ----------
    private class AcceptThread extends Thread {
        private final BluetoothServerSocket serverSocket;
        private boolean failed = false;

        public AcceptThread() {
            BluetoothServerSocket tmp = null;
            try {
                tmp = bluetoothAdapter.listenUsingRfcommWithServiceRecord(APP_NAME, MY_UUID);
                Log.d(TAG, "AcceptThread: server socket created");
            } catch (IOException e) {
                Log.e(TAG, "listen failed", e);
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
                notifyComplete(false, null);
                stopSelf();
                return;
            }
            setName("FileAcceptThread");
            BluetoothSocket socket;
            Log.d(TAG, "AcceptThread running, waiting for connection...");
            while (state != STATE_CONNECTED) {
                try {
                    socket = serverSocket.accept();
                    Log.d(TAG, "AcceptThread: accepted connection from " + socket.getRemoteDevice().getAddress());
                } catch (IOException e) {
                    Log.e(TAG, "accept failed", e);
                    break;
                }
                if (socket != null) {
                    connected(socket, socket.getRemoteDevice());
                    break;
                }
            }
            if (state != STATE_CONNECTED) {
                Log.w(TAG, "AcceptThread exiting without connection");
                notifyComplete(false, null);
                stopSelf();
            }
        }

        public void cancel() {
            try {
                if (serverSocket != null) serverSocket.close();
                Log.d(TAG, "AcceptThread cancelled");
            } catch (IOException e) {
                Log.e(TAG, "close server socket failed", e);
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
                Log.d(TAG, "ConnectThread: socket created for " + device.getAddress());
            } catch (IOException e) {
                Log.e(TAG, "create socket failed", e);
            }
            socket = tmp;
        }

        public void run() {
            setName("FileConnectThread");
            bluetoothAdapter.cancelDiscovery();
            try {
                Log.d(TAG, "ConnectThread: attempting connect...");
                socket.connect();
                Log.d(TAG, "ConnectThread: connected successfully");
                connected(socket, device);
            } catch (IOException e) {
                Log.e(TAG, "connect failed", e);
                try {
                    socket.close();
                } catch (IOException e2) {
                    Log.e(TAG, "close socket after fail", e2);
                }
                notifyComplete(false, null);
                stopSelf();
            }
        }

        public void cancel() {
            try {
                if (socket != null) socket.close();
                Log.d(TAG, "ConnectThread cancelled");
            } catch (IOException e) {
                Log.e(TAG, "close connect socket failed", e);
            }
        }
    }

    private class ConnectedThread extends Thread {
        private final BluetoothSocket socket;
        private final InputStream inputStream;
        private final OutputStream outputStream;
        private final Handler progressHandler = new Handler(Looper.getMainLooper());

        public ConnectedThread(BluetoothSocket socket) {
            this.socket = socket;
            InputStream tmpIn = null;
            OutputStream tmpOut = null;
            try {
                tmpIn = socket.getInputStream();
                tmpOut = socket.getOutputStream();
                Log.d(TAG, "ConnectedThread: streams obtained");
            } catch (IOException e) {
                Log.e(TAG, "streams not created", e);
            }
            // 优化：使用 BufferedInputStream/BufferedOutputStream 减少系统调用次数
            inputStream = (tmpIn != null) ? new BufferedInputStream(tmpIn, 8192) : null;
            outputStream = (tmpOut != null) ? new BufferedOutputStream(tmpOut, 8192) : null;
        }

        // 优化：确保读取指定长度的数据（防止粘包/拆包）
        private void readFully(byte[] buffer) throws IOException {
            int offset = 0;
            while (offset < buffer.length) {
                int n = inputStream.read(buffer, offset, buffer.length - offset);
                if (n == -1) throw new IOException("EOF");
                offset += n;
            }
        }

        @Override
        public void run() {
            Log.d(TAG, "ConnectedThread running, action=" + action);
            try {
                if ("SEND".equals(action)) {
                    File file = new File(filePath);
                    if (!file.exists()) {
                        Log.e(TAG, "文件不存在: " + filePath);
                        notifyComplete(false, null);
                        return;
                    }
                    long fileLen = file.length();
                    // 优化：文件长度使用long(8字节)，支持>2GB文件
                    outputStream.write(longToBytes(fileLen));
                    // 发送文件名长度和文件名
                    byte[] nameBytes = fileName.getBytes(StandardCharsets.UTF_8);
                    outputStream.write(shortToBytes((short) nameBytes.length));
                    outputStream.write(nameBytes);
                    // 优化：计算CRC32校验码
                    CRC32 crc32 = new CRC32();
                    // 优化：文件传输使用64KB缓冲区
                    // 优化：使用 try-with-resources 确保资源释放
                    try (FileInputStream fis = new FileInputStream(file)) {
                        byte[] buffer = new byte[65536]; // 64KB
                        int bytesRead;
                        long totalSent = 0;
                        long lastCallbackTime = System.currentTimeMillis();
                        while ((bytesRead = fis.read(buffer)) != -1) {
                            outputStream.write(buffer, 0, bytesRead);
                            crc32.update(buffer, 0, bytesRead);
                            totalSent += bytesRead;
                            long now = System.currentTimeMillis();
                            if (now - lastCallbackTime >= 3000) {
                                final long transferred = totalSent;
                                final long total = fileLen;
                                final int progress = (int)(transferred * 100 / total);
                                progressHandler.post(() -> notifyProgress(total, transferred, progress));
                                lastCallbackTime = now;
                            }
                        }
                    }
                    // 发送CRC32校验码（4字节）
                    outputStream.write(intToBytes((int) crc32.getValue()));
                    outputStream.flush();
                    Log.d(TAG, "文件发送完成，长度=" + fileLen + ", CRC32=" + Long.toHexString(crc32.getValue()));
                    // 确保最后一次进度回调（100%）
                    progressHandler.post(() -> notifyProgress(fileLen, fileLen, 100));
                    notifyComplete(true, null);
                } else if ("RECEIVE".equals(action)) {
                    // 优化：文件长度使用long(8字节)
                    byte[] lenBytes = new byte[8];
                    readFully(lenBytes);
                    long fileLen = bytesToLong(lenBytes);
                    // 读取文件名长度
                    byte[] nameLenBytes = new byte[2];
                    readFully(nameLenBytes);
                    short nameLen = bytesToShort(nameLenBytes);
                    // 读取文件名
                    byte[] nameBytes = new byte[nameLen];
                    readFully(nameBytes);
                    String originalName = new String(nameBytes, StandardCharsets.UTF_8);
                    Log.d(TAG, "接收文件，原始名=" + originalName + ", 大小=" + fileLen);

                    File dir = new File(saveDir);
                    if (!dir.exists()) {
                        if (!dir.mkdirs()) {
                            Log.e(TAG, "创建目录失败: " + saveDir);
                            notifyComplete(false, null);
                            return;
                        }
                    }
                    // 生成不重复文件名
                    File file = new File(dir, originalName);
                    int count = 1;
                    while (file.exists()) {
                        String baseName = originalName;
                        String ext = "";
                        int dot = originalName.lastIndexOf('.');
                        if (dot > 0) {
                            baseName = originalName.substring(0, dot);
                            ext = originalName.substring(dot);
                        }
                        String newName = baseName + "_" + count + ext;
                        file = new File(dir, newName);
                        count++;
                    }
                    // 优化：计算接收数据的CRC32
                    CRC32 crc32 = new CRC32();
                    // 优化：使用 try-with-resources 确保资源释放
                    try (FileOutputStream fos = new FileOutputStream(file)) {
                        // 优化：文件传输使用64KB缓冲区
                        byte[] buffer = new byte[65536]; // 64KB
                        long remaining = fileLen;
                        long totalReceived = 0;
                        long lastCallbackTime = System.currentTimeMillis();
                        while (remaining > 0) {
                            int toRead = (int) Math.min(buffer.length, remaining);
                            int bytes = inputStream.read(buffer, 0, toRead);
                            if (bytes == -1) {
                                throw new IOException("连接意外断开");
                            }
                            fos.write(buffer, 0, bytes);
                            crc32.update(buffer, 0, bytes);
                            totalReceived += bytes;
                            remaining -= bytes;
                            long now = System.currentTimeMillis();
                            if (now - lastCallbackTime >= 3000) {
                                final long transferred = totalReceived;
                                final long total = fileLen;
                                final int progress = (int)(transferred * 100 / total);
                                progressHandler.post(() -> notifyProgress(total, transferred, progress));
                                lastCallbackTime = now;
                            }
                        }
                    }
                    // 读取并验证CRC32校验码
                    byte[] crcBytes = new byte[4];
                    readFully(crcBytes);
                    int receivedCrc = bytesToInt(crcBytes);
                    int computedCrc = (int) crc32.getValue();
                    if (receivedCrc != computedCrc) {
                        Log.e(TAG, "CRC32校验失败: 期望=" + Integer.toHexString(receivedCrc) +
                                ", 实际=" + Integer.toHexString(computedCrc));
                        file.delete();
                        notifyComplete(false, null);
                        return;
                    }
                    Log.d(TAG, "文件接收完成，CRC32校验通过，保存到: " + file.getAbsolutePath());
                    // 确保最后一次进度回调
                    progressHandler.post(() -> notifyProgress(fileLen, fileLen, 100));
                    notifyComplete(true, file.getAbsolutePath());
                } else {
                    Log.e(TAG, "Unknown action");
                    notifyComplete(false, null);
                }
            } catch (IOException e) {
                Log.e(TAG, "传输异常", e);
                notifyComplete(false, null);
            } finally {
                try {
                    if (socket != null) {
                        socket.close();
                    }
                } catch (IOException e) {
                    Log.e(TAG, "关闭socket失败", e);
                }
            }
        }

        public void cancel() {
            try {
                if (socket != null) {
                    socket.close();
                }
                Log.d(TAG, "ConnectedThread cancelled");
            } catch (IOException e) {
                Log.e(TAG, "close connected socket failed", e);
            }
        }
    }
}
