package com.example.soundtransferlower;

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.support.v4.app.ActivityCompat;
import android.support.v4.app.Fragment;
import android.support.v4.content.ContextCompat;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class TalkbackFragment extends Fragment
        implements AudioRecorderPlayer.AudioDataSender, IMessageCallback.MessageCallback,
        AutoDeviceScanner.DeviceScanListener {

    private static final String TAG = "TalkbackFragment";

    // ---------- 常量 ----------
    private static final int REQUEST_BLUETOOTH_PERMISSIONS = 1;
    private static final long TALK_BUTTON_TIMEOUT = 600;
    private static final long RECEIVE_TIMEOUT = 800;
    private static final long INACTIVITY_THRESHOLD_DISCONNECT = 50000;
    private static final long REFRESH_INTERVAL = 5000;
    private static final long CONNECTION_RETRY_DELAY = 500;

    private static final int STATE_IDLE = 0;
    private static final int STATE_TALKING = 1;
    private static final int STATE_RECEIVING = 2;
    private static final int MAX_STATE_CHANGES = 2;

    // ---------- UI ----------
    private TextView tvStatus;
    private ListView deviceList;
    private Button btnRefresh, btnAudioMode, btnTalk, btnDisconnect, btnPair;
    private Button btnDial;
    private DeviceListAdapter deviceAdapter;
    private final List<BluetoothDevice> pairedDevices = new ArrayList<>();
    private final Set<String> scannedAddresses = new HashSet<>();

    // ---------- 蓝牙 ----------
    private BluetoothAdapter bluetoothAdapter;
    private BluetoothDevice targetDevice;
    private IBluetoothService bluetoothService;
    private boolean serviceBound = false;
    private String connectedDeviceAddress;
    private String connectedDeviceName;

    // ---------- 音频 ----------
    private AudioManager audioManager;
    private AudioRecorderPlayer audioRecorderPlayer;
    private boolean isRecording = false;
    private boolean isSpeakerMode = false;

    // ---------- 状态 ----------
    private boolean isLoading = true;
    private boolean isConnectionActive = false;
    private boolean isConnecting = false;
    private int currentState = STATE_IDLE;
    private int stateChangeCount = 0;
    private boolean isTalkButtonDisabled = false;

    // ---------- Handler ----------
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Handler checkPacketHandler = new Handler(Looper.getMainLooper());
    private int receivedPacketCount = 0;

    // ---------- 字节前缀 ----------
    private final byte[] TEXT_PREFIX_BYTES = IBluetoothService.TEXT_PREFIX.getBytes();

    // ---------- Runnable ----------
    private final Runnable refreshRunnable = new Runnable() {
        @Override
        public void run() {
            refreshPairedDevices();
            mainHandler.postDelayed(this, REFRESH_INTERVAL);
        }
    };

    private final Runnable receiveTimeoutRunnable = new Runnable() {
        @Override
        public void run() {
            if (currentState == STATE_RECEIVING) {
                LogUtil.d(TAG, "接收超时，恢复空闲状态");
                setState(STATE_IDLE);
            }
        }
    };

    private final Runnable checkPacketRunnable = new Runnable() {
        @Override
        public void run() {
            receivedPacketCount = 0;
        }
    };

    private final Runnable inactivityCheckRunnable = new Runnable() {
        @Override
        public void run() {
            checkInactivity();
            mainHandler.postDelayed(this, 1000);
        }
    };
    private long lastActivityTime = 0;

    // ---------- BroadcastReceiver ----------
    private final BroadcastReceiver bluetoothReceiver = new BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);

            if (BluetoothDevice.ACTION_ACL_CONNECTED.equals(action)) {
                LogUtil.d(TAG, "设备已连接: " + device.getName());
                mainHandler.post(() -> tvStatus.setText("已连接: " + device.getName()));
            } else if (BluetoothDevice.ACTION_ACL_DISCONNECTED.equals(action)) {
                LogUtil.d(TAG, "设备已断开: " + device.getName());
                handleConnectionLost();
            } else if (BluetoothAdapter.ACTION_STATE_CHANGED.equals(action)) {
                int state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR);
                if (state == BluetoothAdapter.STATE_ON) {
                    refreshPairedDevices();
                    startServer();
                } else if (state == BluetoothAdapter.STATE_OFF) {
                    mainHandler.post(() -> tvStatus.setText("蓝牙已关闭"));
                }
            }
        }
    };

    // ---------- ServiceConnection ----------
    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            BluetoothService.LocalBinder binder = (BluetoothService.LocalBinder) service;
            bluetoothService = binder.getInterface();
            bluetoothService.registerCallback(TalkbackFragment.this);
            serviceBound = true;
            bluetoothService.setMode(IBluetoothService.MODE_TALKBACK);
            if (targetDevice != null) {
                connectToDevice(targetDevice);
            } else {
                startServer();
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            serviceBound = false;
        }
    };

    // ==================== 生命周期 ====================

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.activity_main, container, false);
        initViews(view);
        setLoadingState();
        checkPermissions();
        initBluetooth();
        initAudio();
        setupListeners();

        Intent serviceIntent = new Intent(getActivity(), BluetoothService.class);
        getActivity().bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE);

        if (btnDial != null) btnDial.setVisibility(View.GONE);

        mainHandler.postDelayed(() -> {
            isLoading = false;
            setInitialState();
        }, 500);

        // 注册扫描监听
        if (getActivity() instanceof MainActivityNew) {
            ((MainActivityNew) getActivity()).registerScanListener(this);
        }

        return view;
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        disconnect();
        releaseAudio();
        mainHandler.removeCallbacksAndMessages(null);
        checkPacketHandler.removeCallbacksAndMessages(null);
        unregisterBluetoothReceiver();
        unbindServiceIfNeeded();
        if (getActivity() instanceof MainActivityNew) {
            ((MainActivityNew) getActivity()).unregisterScanListener(this);
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        refreshPairedDevices();
        startServer();
        mainHandler.post(refreshRunnable);
        if (getActivity() instanceof MainActivityNew) {
            ((MainActivityNew) getActivity()).registerScanListener(this);
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        mainHandler.removeCallbacks(refreshRunnable);
        mainHandler.removeCallbacks(receiveTimeoutRunnable);
        checkPacketHandler.removeCallbacks(checkPacketRunnable);
        if (isRecording) stopTalking();
        if (getActivity() instanceof MainActivityNew) {
            ((MainActivityNew) getActivity()).unregisterScanListener(this);
        }
    }

    // ==================== UI 初始化 ====================

    private void initViews(View view) {
        tvStatus = view.findViewById(R.id.tvStatus);
        deviceList = view.findViewById(R.id.deviceList);
        btnRefresh = view.findViewById(R.id.btnRefresh);
        btnAudioMode = view.findViewById(R.id.btnAudioMode);
        btnTalk = view.findViewById(R.id.btnTalk);
        btnDisconnect = view.findViewById(R.id.btnDisconnect);
        btnPair = view.findViewById(R.id.btnPair);
        btnDial = view.findViewById(R.id.btnDial);

        deviceAdapter = new DeviceListAdapter(getActivity(), R.layout.item_main, pairedDevices, scannedAddresses);
        deviceList.setAdapter(deviceAdapter);

        btnAudioMode.setText("开始外放");
        btnDisconnect.setEnabled(true);
    }

    private void setupListeners() {
        deviceList.setOnItemClickListener((parent, view, position, id) -> {
            targetDevice = pairedDevices.get(position);
            connectToDevice(targetDevice);
        });

        btnRefresh.setOnClickListener(v -> refreshPairedDevices());

        btnAudioMode.setOnClickListener(v -> {
            isSpeakerMode = !isSpeakerMode;
            setSpeakerMode(isSpeakerMode);
            btnAudioMode.setText(isSpeakerMode ? "关闭外放" : "开启外放");
        });

        btnDisconnect.setOnClickListener(v -> disconnect());

        btnPair.setOnClickListener(v -> {
            Intent intent = new Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS);
            startActivity(intent);
        });

        btnTalk.setOnClickListener(v -> {
            if (isTalkButtonDisabled) {
                Toast.makeText(getActivity(), "请稍后再试", Toast.LENGTH_SHORT).show();
                return;
            }
            disableTalkButtonTemporarily();
            if (isRecording) {
                stopTalking();
            } else {
                startTalking();
            }
        });

        if (btnDial != null) {
            btnDial.setOnClickListener(v -> {
                if (getActivity() instanceof MainActivityNew) {
                    ((MainActivityNew) getActivity()).dialCall();
                } else {
                    Toast.makeText(getActivity(), "拨号功能不可用", Toast.LENGTH_SHORT).show();
                }
            });
        }
    }

    // ==================== 权限与蓝牙 ====================

    private void checkPermissions() {
        String[] permissions = {
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN,
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.MODIFY_AUDIO_SETTINGS
        };
        List<String> needed = new ArrayList<>();
        for (String p : permissions) {
            if (ContextCompat.checkSelfPermission(getActivity(), p) != PackageManager.PERMISSION_GRANTED) {
                needed.add(p);
            }
        }
        if (!needed.isEmpty()) {
            ActivityCompat.requestPermissions(getActivity(),
                    needed.toArray(new String[0]), REQUEST_BLUETOOTH_PERMISSIONS);
        }
    }

    @SuppressLint("MissingPermission")
    private void initBluetooth() {
        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        if (bluetoothAdapter == null) {
            tvStatus.setText("蓝牙不可用");
            return;
        }
        if (!bluetoothAdapter.isEnabled()) {
            Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
            startActivity(enableBtIntent);
        }

        IntentFilter filter = new IntentFilter();
        filter.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
        filter.addAction(BluetoothDevice.ACTION_ACL_CONNECTED);
        filter.addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED);
        filter.addAction(BluetoothDevice.ACTION_ACL_DISCONNECT_REQUESTED);
        getActivity().registerReceiver(bluetoothReceiver, filter);
    }

    private void initAudio() {
        audioManager = (AudioManager) getActivity().getSystemService(Context.AUDIO_SERVICE);
        audioRecorderPlayer = new AudioRecorderPlayer(getActivity());
        audioRecorderPlayer.setAudioDataSender(this);
        setSpeakerMode(false);
    }

    // ==================== 设备操作 ====================

    @SuppressLint("MissingPermission")
    private void refreshPairedDevices() {
        if (bluetoothAdapter == null) return;
        Set<BluetoothDevice> devices = bluetoothAdapter.getBondedDevices();
        pairedDevices.clear();
        pairedDevices.addAll(devices);
        deviceAdapter.notifyDataSetChanged();
        LogUtil.d(TAG, "刷新配对设备列表，数量: " + pairedDevices.size());
    }

    private void connectToDevice(BluetoothDevice device) {
        if (isConnectionActive || isConnecting) return;
        tvStatus.setText("连接中");
        isConnecting = true;
        btnTalk.setEnabled(false);

        if (serviceBound && bluetoothService != null) {
            bluetoothService.setConnectionRole(true, device.getAddress());
        } else {
            Toast.makeText(getActivity(), "蓝牙服务未就绪", Toast.LENGTH_SHORT).show();
            isConnecting = false;
        }
    }

    private void startServer() {
        if (serviceBound && bluetoothService != null) {
            bluetoothService.setConnectionRole(false, null);
        }
    }

    private void disconnect() {
        LogUtil.d(TAG, "调用 disconnect()");
        stopInactivityTimer();
        if (serviceBound && bluetoothService != null) {
            bluetoothService.stop();
        }
        mainHandler.post(() -> {
            resetConnectionState();
            tvStatus.setText("已断开连接");
        });
    }

    private void resetConnectionState() {
        if (isRecording) stopTalking();
        isConnectionActive = false;
        isConnecting = false;
        currentState = STATE_IDLE;
        stateChangeCount = 0;
        btnTalk.setEnabled(false);
        btnTalk.setText("按下对讲");
        btnTalk.setBackgroundColor(ContextCompat.getColor(getActivity(), android.R.color.darker_gray));
        btnAudioMode.setEnabled(false);
        deviceList.setEnabled(true);
        btnRefresh.setEnabled(true);
        btnPair.setEnabled(true);
        startServer();
        isTalkButtonDisabled = false;
        btnTalk.setAlpha(1.0f);
        receivedPacketCount = 0;
        checkPacketHandler.removeCallbacks(checkPacketRunnable);
    }

    private void handleConnectionLost() {
        LogUtil.d(TAG, "连接丢失");
        mainHandler.post(() -> {
            resetConnectionState();
            tvStatus.setText("连接断开");
        });
    }

    // ==================== 状态管理 ====================

    @SuppressLint("MissingPermission")
    private void setState(int newState) {
        if (currentState != newState) {
            stateChangeCount++;
            LogUtil.d(TAG, "状态切换计数: " + stateChangeCount);
            if (stateChangeCount >= MAX_STATE_CHANGES) {
                LogUtil.d(TAG, "达到最大状态切换次数，将断开重连");
                stateChangeCount = 0;
                playConnectionSound();
                if (targetDevice != null) {
                    disconnect();
                    mainHandler.postDelayed(() -> connectToDevice(targetDevice),
                            CONNECTION_RETRY_DELAY);
                }
            }
        }
        currentState = newState;
        mainHandler.post(() -> {
            if (getActivity() == null) {
                LogUtil.w(TAG, "Activity 已销毁，跳过 UI 更新");
                return;
            }

            switch (newState) {
                case STATE_IDLE:
                    String addr = bluetoothService != null ? bluetoothService.getConnectedDeviceAddress() : "";
                    tvStatus.setText("已连接: " + addr);
                    btnTalk.setEnabled(true);
                    btnTalk.setBackgroundColor(ContextCompat.getColor(getActivity(),
                            android.R.color.holo_green_light));
                    btnAudioMode.setEnabled(true);
                    if (!isTalkButtonDisabled) {
                        btnTalk.setText("按下对讲");
                    }
                    break;
                case STATE_TALKING:
                    tvStatus.setText("我方说话中");
                    btnAudioMode.setEnabled(false);
                    if (isTalkButtonDisabled) {
                        btnTalk.setText("作用中");
                    } else {
                        btnTalk.setText("对讲中,再次按下停止");
                        btnTalk.setBackgroundColor(ContextCompat.getColor(getActivity(),
                                android.R.color.holo_red_light));
                    }
                    break;
                case STATE_RECEIVING:
                    tvStatus.setText("对方说话中");
                    btnTalk.setEnabled(false);
                    btnTalk.setText("对方说话中");
                    btnTalk.setBackgroundColor(ContextCompat.getColor(getActivity(),
                            android.R.color.darker_gray));
                    btnAudioMode.setEnabled(false);
                    mainHandler.removeCallbacks(receiveTimeoutRunnable);
                    mainHandler.postDelayed(receiveTimeoutRunnable, RECEIVE_TIMEOUT);
                    break;
            }
        });
    }

    // ==================== 音频 ====================

    private void setSpeakerMode(boolean speaker) {
        try {
            if (speaker) {
                audioManager.setMode(AudioManager.MODE_NORMAL);
                audioManager.setSpeakerphoneOn(true);
                int maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL);
                int targetVolume = (int) (maxVolume * 0.15);
                audioManager.setStreamVolume(AudioManager.STREAM_VOICE_CALL, targetVolume, 0);
            } else {
                audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);
                audioManager.setSpeakerphoneOn(false);
                int maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL);
                audioManager.setStreamVolume(AudioManager.STREAM_VOICE_CALL, maxVolume, 0);
            }
        } catch (Exception e) {
            LogUtil.e(TAG, "设置音频模式失败: " + e.getMessage());
            mainHandler.post(() -> Toast.makeText(getActivity(), "音频模式切换失败", Toast.LENGTH_SHORT).show());
        }
    }

    private void startTalking() {
        if (!isConnectionActive || isRecording) return;
        LogUtil.d(TAG, "开始说话");
        setState(STATE_TALKING);
        audioRecorderPlayer.startRecording();
        isRecording = true;
    }

    private void stopTalking() {
        if (!isRecording) return;
        LogUtil.d(TAG, "停止说话");
        audioRecorderPlayer.stopRecording();
        isRecording = false;
        btnTalk.setText("作用中");
        setState(STATE_IDLE);
    }

    private void releaseAudio() {
        if (audioRecorderPlayer != null) {
            audioRecorderPlayer.release();
            audioRecorderPlayer = null;
        }
    }

    // ==================== 活动检测 ====================

    private void startInactivityTimer() {
        lastActivityTime = System.currentTimeMillis();
        mainHandler.postDelayed(inactivityCheckRunnable, 1000);
        LogUtil.d(TAG, "启动活动检测计时器");
    }

    private void stopInactivityTimer() {
        mainHandler.removeCallbacks(inactivityCheckRunnable);
        LogUtil.d(TAG, "停止活动检测计时器");
    }

    private void resetInactivityTimer() {
        lastActivityTime = System.currentTimeMillis();
        LogUtil.d(TAG, "重置活动检测计时器");
    }

    private void checkInactivity() {
        if (!isConnectionActive) return;
        long inactiveDuration = System.currentTimeMillis() - lastActivityTime;
        if (inactiveDuration >= INACTIVITY_THRESHOLD_DISCONNECT) {
            LogUtil.d(TAG, "50秒无活动，断开连接");
            disconnect();
            mainHandler.post(() -> Toast.makeText(getActivity(), "50秒无活动，连接已断开",
                    Toast.LENGTH_LONG).show());
        }
    }

    // ==================== 按钮防抖 ====================

    private void disableTalkButtonTemporarily() {
        isTalkButtonDisabled = true;
        btnTalk.setEnabled(false);
        btnTalk.setAlpha(0.5f);
        mainHandler.postDelayed(() -> {
            isTalkButtonDisabled = false;
            if (isConnectionActive) {
                btnTalk.setEnabled(true);
                btnTalk.setAlpha(1.0f);
                setState(currentState);
            }
        }, TALK_BUTTON_TIMEOUT);
    }

    // ==================== UI 状态 ====================

    private void setLoadingState() {
        mainHandler.post(() -> {
            tvStatus.setText("加载中...");
            deviceList.setEnabled(false);
            btnRefresh.setEnabled(false);
            btnAudioMode.setEnabled(false);
            btnTalk.setEnabled(false);
            btnDisconnect.setEnabled(true);
            btnPair.setEnabled(false);
            if (btnDial != null) btnDial.setEnabled(false);
        });
    }

    private void setInitialState() {
        mainHandler.post(() -> {
            tvStatus.setText("未连接");
            btnTalk.setEnabled(false);
            btnTalk.setText("按下对讲");
            btnTalk.setBackgroundColor(ContextCompat.getColor(getActivity(),
                    android.R.color.darker_gray));
            btnDisconnect.setEnabled(true);
            btnAudioMode.setEnabled(false);
            deviceList.setEnabled(true);
            btnRefresh.setEnabled(true);
            btnPair.setEnabled(true);
            if (btnDial != null) btnDial.setEnabled(true);
            refreshPairedDevices();
            startServer();
        });
    }

    // ==================== 振动 ====================

    private void playConnectionSound() {
        try {
            Vibrator vibrator = (Vibrator) getActivity().getSystemService(Context.VIBRATOR_SERVICE);
            if (vibrator != null && vibrator.hasVibrator()) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    VibrationEffect effect = VibrationEffect.createOneShot(500,
                            VibrationEffect.DEFAULT_AMPLITUDE);
                    vibrator.vibrate(effect);
                } else {
                    vibrator.vibrate(500);
                }
            }
        } catch (Exception e) {
            LogUtil.e(TAG, "振动失败: " + e.getMessage());
        }
    }

    // ==================== 自动扫描回调 ====================

    @Override
    public void onDeviceDetected(BluetoothDevice device) {
        if (device == null || getActivity() == null) return;
        if (pairedDevices.contains(device)) {
            scannedAddresses.add(device.getAddress());
            mainHandler.post(() -> {
                if (deviceAdapter != null) {
                    deviceAdapter.notifyDataSetChanged();
                }
            });
        }
    }

    // ==================== AudioDataSender ====================

    @Override
    public void sendAudioData(byte[] data) {
        if (serviceBound && bluetoothService != null && isConnectionActive) {
            bluetoothService.write(data, IBluetoothService.MODE_TALKBACK);
            resetInactivityTimer();
        }
    }

    // ==================== 蓝牙服务回调 ====================

    @Override
    public void onMessageReceived(String message, String deviceAddress) {
        if (message.startsWith(IBluetoothService.FILE_REQUEST_PREFIX)) {
            String rejectMsg = IBluetoothService.TEXT_PREFIX + IBluetoothService.FILE_REJECT;
            if (serviceBound && bluetoothService != null) {
                bluetoothService.write(rejectMsg.getBytes());
            }
            mainHandler.post(() -> Toast.makeText(getActivity(),
                    "当前处于语音模式，无法接收文件，请切换至文本聊天", Toast.LENGTH_LONG).show());
            return;
        }

        String trimmed = message.trim();
        if (trimmed.startsWith(IBluetoothService.CALL_PREFIX) ||
                trimmed.startsWith(IBluetoothService.CALL_REQUEST) ||
                trimmed.equals(IBluetoothService.CALL_ACCEPT) ||
                trimmed.equals(IBluetoothService.CALL_REJECT) ||
                trimmed.equals(IBluetoothService.CALL_HANGUP)) {
            return;
        }

        mainHandler.post(() -> {
            Toast.makeText(getActivity(), "收到文本消息，自动切换到聊天模式", Toast.LENGTH_SHORT).show();
            if (getActivity() instanceof MainActivityNew) {
                if (bluetoothService != null) {
                    bluetoothService.setMode(IBluetoothService.MODE_CHAT);
                }
                String address = bluetoothService != null ?
                        bluetoothService.getConnectedDeviceAddress() : deviceAddress;
                String name = bluetoothService != null ?
                        bluetoothService.getConnectedDeviceName() : "未知设备";
                ((MainActivityNew) getActivity())
                        .switchToFragment("ChatWorkFragment", address, name);
            }
        });
        LogUtil.d(TAG, "收到文本消息（对讲模式）: " + message);
    }

    @Override
    public void onConnectionStatusChanged(int state, String deviceName) {
        mainHandler.post(() -> {
            switch (state) {
                case IBluetoothService.STATE_CONNECTED:
                    tvStatus.setText("已连接: " + deviceName);
                    isConnectionActive = true;
                    isConnecting = false;
                    playConnectionSound();
                    setState(STATE_IDLE);
                    startInactivityTimer();
                    connectedDeviceAddress = bluetoothService != null ?
                            bluetoothService.getConnectedDeviceAddress() : null;
                    connectedDeviceName = deviceName;
                    btnDisconnect.setEnabled(true);
                    break;
                case IBluetoothService.STATE_CONNECTING:
                    tvStatus.setText("连接中...");
                    isConnecting = true;
                    btnDisconnect.setEnabled(true);
                    break;
                case IBluetoothService.STATE_LISTEN:
                    tvStatus.setText("等待连接...");
                    isConnectionActive = false;
                    isConnecting = false;
                    btnDisconnect.setEnabled(true);
                    break;
                case IBluetoothService.STATE_NONE:
                    tvStatus.setText("未连接");
                    isConnectionActive = false;
                    isConnecting = false;
                    btnDisconnect.setEnabled(true);
                    break;
            }
        });
    }

    @Override
    public void onTalkbackDataReceived(byte[] data, String deviceAddress) {
        if (isTextMessage(data)) {
            String message = new String(data, TEXT_PREFIX_BYTES.length,
                    data.length - TEXT_PREFIX_BYTES.length);
            onMessageReceived(message, deviceAddress);
        } else {
            if (audioRecorderPlayer == null) {
                LogUtil.w(TAG, "audioRecorderPlayer 为空，重新初始化");
                initAudio();
                if (audioRecorderPlayer == null) return;
            }
            audioRecorderPlayer.playAudio(data, data.length);
            setState(STATE_RECEIVING);
            resetInactivityTimer();
        }
        receivedPacketCount++;
        checkPacketHandler.removeCallbacks(checkPacketRunnable);
        checkPacketHandler.postDelayed(checkPacketRunnable, 2000);
    }

    @Override
    public void onNonTextDataReceived(String deviceAddress) {
        // 对讲模式不处理
    }

    @Override
    public void onCallRequest(String callerName, String deviceAddress) {
        // 由主Activity处理
    }

    @Override
    public void onCallAccepted(String deviceAddress) {
        // 由主Activity处理
    }

    @Override
    public void onCallRejected(String deviceAddress) {
        // 由主Activity处理
    }

    @Override
    public void onCallHungUp(String deviceAddress) {
        // 由主Activity处理
    }

    // ==================== 工具方法 ====================

    private boolean isTextMessage(byte[] data) {
        if (data.length < TEXT_PREFIX_BYTES.length) return false;
        for (int i = 0; i < TEXT_PREFIX_BYTES.length; i++) {
            if (data[i] != TEXT_PREFIX_BYTES[i]) return false;
        }
        return true;
    }

    private void unregisterBluetoothReceiver() {
        try {
            getActivity().unregisterReceiver(bluetoothReceiver);
        } catch (Exception e) {
            LogUtil.e(TAG, "取消注册广播接收器失败: " + e.getMessage());
        }
    }

    private void unbindServiceIfNeeded() {
        if (serviceBound) {
            if (bluetoothService != null) {
                bluetoothService.unregisterCallback(this);
            }
            getActivity().unbindService(serviceConnection);
            serviceBound = false;
        }
    }

    // ==================== 内部适配器 ====================

    public static class DeviceListAdapter extends ArrayAdapter<BluetoothDevice> {
        private final LayoutInflater inflater;
        private final int resource;
        private final Context context;
        private final Set<String> scannedAddresses;

        public DeviceListAdapter(Context context, int resource, List<BluetoothDevice> devices,
                                 Set<String> scannedAddresses) {
            super(context, resource, devices);
            this.context = context;
            this.inflater = LayoutInflater.from(context);
            this.resource = resource;
            this.scannedAddresses = scannedAddresses;
        }

        @SuppressLint("MissingPermission")
        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            if (convertView == null) {
                convertView = inflater.inflate(resource, parent, false);
            }
            BluetoothDevice device = getItem(position);
            if (device != null) {
                TextView deviceName = convertView.findViewById(R.id.deviceName);
                TextView deviceAddress = convertView.findViewById(R.id.deviceAddress);
                TextView avatar = convertView.findViewById(R.id.avatar);

                if (deviceName != null) {
                    String name = device.getName();
                    deviceName.setText(name != null && !name.isEmpty() ? name : "未知设备");
                    // 标蓝
                    int color = (scannedAddresses != null && scannedAddresses.contains(device.getAddress())) ?
                            context.getResources().getColor(android.R.color.holo_blue_light) :
                            context.getResources().getColor(android.R.color.black);
                    deviceName.setTextColor(color);
                }
                if (deviceAddress != null) {
                    deviceAddress.setText(device.getAddress());
                }
                if (avatar != null) {
                    avatar.setText("蓝牙");
                }
            }
            return convertView;
        }
    }
    @Override
    public void onMessageConfirmed(long timestamp) {
        if (getActivity() == null) return;
        getActivity().runOnUiThread(() -> {
            Toast.makeText(getActivity(), "对方已收到消息", Toast.LENGTH_SHORT).show();
        });
    }
}