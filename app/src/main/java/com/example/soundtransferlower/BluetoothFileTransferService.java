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

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

public class BluetoothFileTransferService extends Service implements IFileTransferService {
    private static final String TAG = "FileTransferService";
    private static final String APP_NAME = "SoundTransferFile";
    private static final UUID MY_UUID = UUID.fromString("fa87c0d0-afac-11de-8a39-0800200c9a66");

    public interface FileTransferCallback {
        void onTransferComplete(boolean success, String filePath);
        void onProgressUpdate(long totalBytes, long transferredBytes, int progress);
    }

    public class LocalBinder extends Binder {
        public BluetoothFileTransferService getService() {
            return BluetoothFileTransferService.this;
        }
        public IFileTransferService getInterface() {
            return BluetoothFileTransferService.this;
        }
    }

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

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            action = intent.getStringExtra("ACTION");
            deviceAddress = intent.getStringExtra("DEVICE_ADDRESS");
            if ("SEND".equals(action)) {
                filePath = intent.getStringExtra("FILE_PATH");
                fileName = intent.getStringExtra("FILE_NAME");
            } else if ("RECEIVE".equals(action)) {
                saveDir = intent.getStringExtra("SAVE_DIR");
                receiveFileName = intent.getStringExtra("FILE_NAME");
            } else {
                LogUtil.e(TAG, "未知action: " + action);
            }
            startTransfer();
        }
        return START_NOT_STICKY;
    }

    // ==================== 实现 IFileTransferService ====================
    @Override
    public void sendFile(String deviceAddress, String filePath, String fileName) {
        this.action = "SEND";
        this.deviceAddress = deviceAddress;
        this.filePath = filePath;
        this.fileName = fileName;
        startTransfer();
    }

    @Override
    public void receiveFile(String saveDir, String fileName) {
        this.action = "RECEIVE";
        this.saveDir = saveDir;
        this.receiveFileName = fileName;
        startTransfer();
    }

    @Override
    public void registerCallback(FileTransferCallback callback) {
        if (!callbacks.contains(callback)) {
            callbacks.add(callback);
        }
    }

    @Override
    public void unregisterCallback(FileTransferCallback callback) {
        callbacks.remove(callback);
    }

    @Override
    public IFileTransferService getInterface() {
        return this;
    }

    // ==================== 原有私有方法 ====================
    private void notifyComplete(boolean success, String filePath) {
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
            notifyComplete(false, null);
            stopSelf();
            return;
        }

        if ("SEND".equals(action)) {
            if (deviceAddress == null || filePath == null || !new File(filePath).exists()) {
                notifyComplete(false, null);
                stopSelf();
                return;
            }
            BluetoothDevice device = bluetoothAdapter.getRemoteDevice(deviceAddress);
            connect(device);
        } else if ("RECEIVE".equals(action)) {
            startListening();
        } else {
            notifyComplete(false, null);
            stopSelf();
        }
    }

    private synchronized void startListening() {
        if (acceptThread == null) {
            acceptThread = new AcceptThread();
            if (acceptThread.isFailed()) {
                notifyComplete(false, null);
                stopSelf();
                return;
            }
            acceptThread.start();
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
    }

    private synchronized void connected(BluetoothSocket socket, BluetoothDevice device) {
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
        connectedThread.start();
        setState(STATE_CONNECTED);
    }

    private synchronized void stopAll() {
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

    private void setState(int state) {
        this.state = state;
    }

    // ==================== 内部线程类（完整） ====================
    private class AcceptThread extends Thread {
        private final BluetoothServerSocket serverSocket;
        private boolean failed = false;

        public AcceptThread() {
            BluetoothServerSocket tmp = null;
            try {
                if (bluetoothAdapter == null) {
                    failed = true;
                } else {
                    tmp = bluetoothAdapter.listenUsingRfcommWithServiceRecord(APP_NAME, MY_UUID);
                }
            } catch (Exception e) {
                LogUtil.e(TAG, "listen failed", e);
                failed = true;
            }
            serverSocket = tmp;
        }

        public boolean isFailed() {
            return failed || serverSocket == null;
        }

        public void run() {
            if (isFailed()) {
                notifyComplete(false, null);
                stopSelf();
                return;
            }
            setName("FileAcceptThread");
            BluetoothSocket socket;
            while (state != STATE_CONNECTED) {
                try {
                    socket = serverSocket.accept();
                } catch (IOException e) {
                    LogUtil.e(TAG, "accept failed", e);
                    break;
                }
                if (socket != null) {
                    connected(socket, socket.getRemoteDevice());
                    break;
                }
            }
            if (state != STATE_CONNECTED) {
                notifyComplete(false, null);
                stopSelf();
            }
        }

        public void cancel() {
            try {
                if (serverSocket != null) serverSocket.close();
            } catch (IOException e) {
                LogUtil.e(TAG, "close server socket failed", e);
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
            } catch (Exception e) {
                LogUtil.e(TAG, "create socket failed", e);
            }
            socket = tmp;
        }

        public void run() {
            setName("FileConnectThread");
            try {
                if (bluetoothAdapter != null) bluetoothAdapter.cancelDiscovery();
                if (socket != null) socket.connect();
                else throw new java.io.IOException("socket 为 null（create 阶段失败）");

                connected(socket, device);
            } catch (Exception e) {
                LogUtil.e(TAG, "connect failed", e);
                try {
                    if (socket != null) socket.close();
                } catch (Exception e2) {
                    LogUtil.e(TAG, "close socket after fail", e2);
                }
                notifyComplete(false, null);
                stopSelf();
            }
        }

        public void cancel() {
            try {
                if (socket != null) socket.close();
            } catch (IOException e) {
                LogUtil.e(TAG, "close connect socket failed", e);
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
            } catch (IOException e) {
                LogUtil.e(TAG, "streams not created", e);
            }
            inputStream = tmpIn;
            outputStream = tmpOut;
        }

        @Override
        public void run() {
            try {
                if ("SEND".equals(action)) {
                    File file = new File(filePath);
                    if (!file.exists()) {
                        notifyComplete(false, null);
                        return;
                    }
                    long fileLen = file.length();
                    outputStream.write(intToBytes((int) fileLen));
                    byte[] nameBytes = fileName.getBytes(StandardCharsets.UTF_8);
                    outputStream.write(shortToBytes((short) nameBytes.length));
                    outputStream.write(nameBytes);

                    FileInputStream fis = new FileInputStream(file);
                    byte[] buffer = new byte[8192];
                    int bytesRead;
                    long totalSent = 0;
                    long lastCallbackTime = System.currentTimeMillis();
                    while ((bytesRead = fis.read(buffer)) != -1) {
                        outputStream.write(buffer, 0, bytesRead);
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
                    fis.close();
                    outputStream.flush();
                    progressHandler.post(() -> notifyProgress(fileLen, fileLen, 100));
                    Thread.sleep(1500);
                    notifyComplete(true, null);
                } else if ("RECEIVE".equals(action)) {
                    byte[] lenBytes = new byte[4];
                    readFully(lenBytes);
                    int fileLen = bytesToInt(lenBytes);
                    byte[] nameLenBytes = new byte[2];
                    readFully(nameLenBytes);
                    short nameLen = bytesToShort(nameLenBytes);
                    byte[] nameBytes = new byte[nameLen];
                    readFully(nameBytes);
                    String originalName = new String(nameBytes, StandardCharsets.UTF_8);

                    File dir = new File(saveDir);
                    if (!dir.exists()) {
                        if (!dir.mkdirs()) {
                            notifyComplete(false, null);
                            return;
                        }
                    }
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
                    FileOutputStream fos = new FileOutputStream(file);
                    byte[] buffer = new byte[8192];
                    int remaining = fileLen;
                    long totalReceived = 0;
                    long lastCallbackTime = System.currentTimeMillis();
                    while (remaining > 0) {
                        int toRead = Math.min(8192, remaining);
                        int bytes = inputStream.read(buffer, 0, toRead);
                        if (bytes == -1) {
                            throw new IOException("连接意外断开");
                        }
                        fos.write(buffer, 0, bytes);
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
                    fos.close();
                    progressHandler.post(() -> notifyProgress(fileLen, fileLen, 100));
                    notifyComplete(true, file.getAbsolutePath());
                } else {
                    notifyComplete(false, null);
                }
            } catch (IOException e) {
                LogUtil.e(TAG, "传输异常", e);
                notifyComplete(false, null);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                notifyComplete(false, null);
            } finally {
                try {
                    if (socket != null) socket.close();
                } catch (IOException e) {
                    LogUtil.e(TAG, "关闭socket失败", e);
                }
            }
        }

        private void readFully(byte[] buffer) throws IOException {
            int offset = 0;
            while (offset < buffer.length) {
                int n = inputStream.read(buffer, offset, buffer.length - offset);
                if (n == -1) throw new IOException("EOF");
                offset += n;
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

        private byte[] shortToBytes(short value) {
            return new byte[]{
                    (byte) (value >> 8),
                    (byte) value
            };
        }

        private short bytesToShort(byte[] bytes) {
            return (short) (((bytes[0] & 0xFF) << 8) | (bytes[1] & 0xFF));
        }

        public void cancel() {
            try {
                if (socket != null) socket.close();
            } catch (IOException e) {
                LogUtil.e(TAG, "close connected socket failed", e);
            }
        }
    }
}