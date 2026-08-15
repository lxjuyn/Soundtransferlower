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
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public class TalkbackFragment extends Fragment implements AudioRecorderPlayer.AudioDataSender, BluetoothService.MessageCallback {
    private static final long TALK_BUTTON_TIMEOUT = 600;
    private boolean isTalkButtonDisabled = false;
    private static final String TAG = "TalkbackFragment";
    private static final int REQUEST_BLUETOOTH_PERMISSIONS = 1;
    private static final long RECEIVE_TIMEOUT = 800;
    private boolean isLoading = true;
    private static final long INACTIVITY_THRESHOLD_DISCONNECT = 50000;
    private long lastActivityTime = 0;
    private Runnable inactivityCheckRunnable;

    private int stateChangeCount = 0;
    private static final int MAX_STATE_CHANGES = 2;
    private BluetoothDevice lastConnectedDevice = null;
    private static final int STATE_IDLE = 0;
    private static final int STATE_TALKING = 1;
    private static final int STATE_RECEIVING = 2;

    private TextView tvStatus;
    private ListView deviceList;
    private Button btnRefresh, btnAudioMode, btnTalk, btnDisconnect, btnPair;
    private Button btnDial; // ★★★ 拨号按钮 ★★★
    private DeviceListAdapter deviceAdapter;
    private List<BluetoothDevice> pairedDevices = new ArrayList<>();

    private BluetoothAdapter bluetoothAdapter;
    private BluetoothDevice targetDevice;

    private AudioManager audioManager;
    private AudioRecorderPlayer audioRecorderPlayer;

    private boolean isRecording = false;
    private boolean isSpeakerMode = false;
    private boolean isConnectionActive = false;
    private boolean isConnecting = false;
    private boolean isIncomingConnection = false;
    private int currentState = STATE_IDLE;

    private BluetoothService bluetoothService;
    private boolean serviceBound = false;

    private String connectedDeviceAddress;
    private String connectedDeviceName;

    private int receivedPacketCount = 0;
    private Handler checkPacketHandler = new Handler(Looper.getMainLooper());
    private Runnable checkPacketRunnable = new Runnable() {
        @Override
        public void run() {
            if (receivedPacketCount < 3) {
                // 切换到聊天（不再使用）
            }
            receivedPacketCount = 0;
        }
    };

    private Handler handler = new Handler(Looper.getMainLooper());
    private Runnable refreshRunnable = new Runnable() {
        @Override
        public void run() {
            refreshPairedDevices();
            handler.postDelayed(this, 5000);
        }
    };

    private Runnable receiveTimeoutRunnable = new Runnable() {
        @Override
        public void run() {
            if (currentState == STATE_RECEIVING) {
                Log.d(TAG, "接收超时，恢复空闲状态");
                setState(STATE_IDLE);
            }
        }
    };

    private ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            BluetoothService.LocalBinder binder = (BluetoothService.LocalBinder) service;
            bluetoothService = binder.getService();
            bluetoothService.registerCallback(TalkbackFragment.this);
            serviceBound = true;
            bluetoothService.setMode(BluetoothService.MODE_TALKBACK);
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
        if (btnDial != null) {
            btnDial.setVisibility(View.GONE); // ★★★ 隐藏拨号按钮 ★★★
        }
        handler.postDelayed(() -> {
            isLoading = false;
            setInitialState();
        }, 500);

        inactivityCheckRunnable = new Runnable() {
            @Override
            public void run() {
                checkInactivity();
                handler.postDelayed(this, 1000);
            }
        };

        return view;
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        disconnect();
        if (audioRecorderPlayer != null) {
            audioRecorderPlayer.release();
            audioRecorderPlayer = null;
        }
        handler.removeCallbacksAndMessages(null);
        checkPacketHandler.removeCallbacksAndMessages(null);
        try {
            getActivity().unregisterReceiver(bluetoothReceiver);
        } catch (Exception e) {
            Log.e(TAG, "取消注册广播接收器失败: " + e.getMessage());
        }
        if (serviceBound) {
            if (bluetoothService != null) {
                bluetoothService.unregisterCallback(this);
            }
            getActivity().unbindService(serviceConnection);
            serviceBound = false;
        }
    }

    @Override
    public void sendAudioData(byte[] data) {
        if (serviceBound && bluetoothService != null && isConnectionActive) {
            bluetoothService.write(data, BluetoothService.MODE_TALKBACK);
            resetInactivityTimer();
        }
    }

    // 对讲模式下收到文本消息（包括文件请求）自动拒绝
    @Override
    public void onMessageReceived(String message, String deviceAddress) {
        // 检测是否是文件请求
        if (message.startsWith("FILE_REQUEST:")) {
            String rejectMsg = BluetoothService.TEXT_PREFIX + "FILE_REJECT";
            if (serviceBound && bluetoothService != null) {
                bluetoothService.write(rejectMsg.getBytes());
            }
            handler.post(() -> {
                Toast.makeText(getActivity(), "当前处于语音模式，无法接收文件，请切换至文本聊天", Toast.LENGTH_LONG).show();
            });
            return;
        }

        // 普通文本消息：仅提示
        handler.post(() -> {
            Toast.makeText(getActivity(), "收到文本消息（对讲模式忽略）: " + message, Toast.LENGTH_SHORT).show();
        });

        receivedPacketCount++;
        checkPacketHandler.removeCallbacks(checkPacketRunnable);
        checkPacketHandler.postDelayed(checkPacketRunnable, 2000);
    }

    @Override
    public void onConnectionStatusChanged(int state, String deviceName) {
        handler.post(() -> {
            switch (state) {
                case BluetoothService.STATE_CONNECTED:
                    tvStatus.setText("已连接: " + deviceName);
                    isConnectionActive = true;
                    isConnecting = false;
                    playConnectionSound();
                    setState(STATE_IDLE);
                    startInactivityTimer();
                    connectedDeviceAddress = bluetoothService.getConnectedDeviceAddress();
                    connectedDeviceName = deviceName;
                    btnDisconnect.setEnabled(true);
                    break;
                case BluetoothService.STATE_CONNECTING:
                    tvStatus.setText("连接中...");
                    isConnecting = true;
                    btnDisconnect.setEnabled(true);
                    break;
                case BluetoothService.STATE_LISTEN:
                    tvStatus.setText("等待连接...");
                    isConnectionActive = false;
                    isConnecting = false;
                    btnDisconnect.setEnabled(true);
                    break;
                case BluetoothService.STATE_NONE:
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
            String message = new String(data, BluetoothService.TEXT_PREFIX_BYTES.length,
                    data.length - BluetoothService.TEXT_PREFIX_BYTES.length);
            onMessageReceived(message, deviceAddress);
        } else {
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

    // ==================== 新增：呼叫回调 ====================
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

    private boolean isTextMessage(byte[] data) {
        if (data.length < BluetoothService.TEXT_PREFIX_BYTES.length) return false;
        for (int i = 0; i < BluetoothService.TEXT_PREFIX_BYTES.length; i++) {
            if (data[i] != BluetoothService.TEXT_PREFIX_BYTES[i]) return false;
        }
        return true;
    }

    // ★★★ 修改 initViews：添加 btnDial ★★★
    private void initViews(View view) {
        tvStatus = view.findViewById(R.id.tvStatus);
        deviceList = view.findViewById(R.id.deviceList);
        btnRefresh = view.findViewById(R.id.btnRefresh);
        btnAudioMode = view.findViewById(R.id.btnAudioMode);
        btnTalk = view.findViewById(R.id.btnTalk);
        btnDisconnect = view.findViewById(R.id.btnDisconnect);
        btnPair = view.findViewById(R.id.btnPair);
        btnDial = view.findViewById(R.id.btnDial); // ★★★ 查找拨号按钮 ★★★

        deviceAdapter = new DeviceListAdapter(getActivity(), R.layout.item_main, pairedDevices);
        deviceList.setAdapter(deviceAdapter);

        btnAudioMode.setText("开始外放");
        btnDisconnect.setEnabled(true);

        // 如果布局中没有 btnDial，动态创建并添加到父布局（可选）
        if (btnDial == null) {
            // 如果布局中没有，可以动态创建并添加到某个容器
            // 这里我们假设布局中已存在
        }
    }

    private void checkPermissions() {
        String[] permissions = {
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN,
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.MODIFY_AUDIO_SETTINGS
        };

        List<String> permissionsNeeded = new ArrayList<>();
        for (String permission : permissions) {
            if (ContextCompat.checkSelfPermission(getActivity(), permission) != PackageManager.PERMISSION_GRANTED) {
                permissionsNeeded.add(permission);
            }
        }

        if (!permissionsNeeded.isEmpty()) {
            ActivityCompat.requestPermissions(getActivity(),
                    permissionsNeeded.toArray(new String[0]),
                    REQUEST_BLUETOOTH_PERMISSIONS);
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

    // ★★★ 修改 setupListeners：添加 btnDial 监听 ★★★
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

        // ★★★ 拨号按钮监听 ★★★
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

    private void refreshPairedDevices() {
        if (bluetoothAdapter == null) return;
        @SuppressLint("MissingPermission") Set<BluetoothDevice> devices = bluetoothAdapter.getBondedDevices();
        pairedDevices.clear();
        pairedDevices.addAll(devices);
        deviceAdapter.notifyDataSetChanged();
        Log.d(TAG, "刷新配对设备列表，数量: " + pairedDevices.size());
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

    private void handleConnectionLost() {
        Log.d(TAG, "连接丢失");
        handler.post(() -> {
            resetConnectionState();
            tvStatus.setText("连接断开");
        });
    }

    private void disableTalkButtonTemporarily() {
        isTalkButtonDisabled = true;
        btnTalk.setEnabled(false);
        btnTalk.setAlpha(0.5f);
        handler.postDelayed(() -> {
            isTalkButtonDisabled = false;
            if (isConnectionActive) {
                btnTalk.setEnabled(true);
                btnTalk.setAlpha(1.0f);
                setState(currentState);
            }
        }, TALK_BUTTON_TIMEOUT);
    }

    private void disconnect() {
        Log.d(TAG, "调用 disconnect() 方法");
        stopInactivityTimer();
        if (serviceBound && bluetoothService != null) {
            bluetoothService.stop();
        }
        handler.post(() -> {
            resetConnectionState();
            tvStatus.setText("已断开连接");
        });
    }

    private void resetConnectionState() {
        if (isRecording) {
            stopTalking();
        }
        isConnectionActive = false;
        isConnecting = false;
        isIncomingConnection = false;
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

    private void startInactivityTimer() {
        lastActivityTime = System.currentTimeMillis();
        handler.postDelayed(inactivityCheckRunnable, 1000);
        Log.d(TAG, "启动活动检测计时器");
    }

    private void stopInactivityTimer() {
        handler.removeCallbacks(inactivityCheckRunnable);
        Log.d(TAG, "停止活动检测计时器");
    }

    private void resetInactivityTimer() {
        lastActivityTime = System.currentTimeMillis();
        Log.d(TAG, "重置活动检测计时器");
    }

    private void checkInactivity() {
        if (!isConnectionActive) return;
        long currentTime = System.currentTimeMillis();
        long inactiveDuration = currentTime - lastActivityTime;
        if (inactiveDuration >= INACTIVITY_THRESHOLD_DISCONNECT) {
            Log.d(TAG, "50秒无活动，断开连接");
            disconnect();
            handler.post(() -> Toast.makeText(getActivity(), "50秒无活动，连接已断开", Toast.LENGTH_LONG).show());
        }
    }

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
            Log.e(TAG, "设置音频模式失败: " + e.getMessage());
            handler.post(() -> Toast.makeText(getActivity(), "音频模式切换失败", Toast.LENGTH_SHORT).show());
        }
    }

    @SuppressLint("MissingPermission")
    private void setState(int newState) {
        if (currentState != newState) {
            stateChangeCount++;
            Log.d(TAG, "状态切换计数: " + stateChangeCount);
            if (stateChangeCount >= MAX_STATE_CHANGES) {
                Log.d(TAG, "达到最大状态切换次数，将断开重连");
                stateChangeCount = 0;
                playConnectionSound();
                if (lastConnectedDevice != null) {
                    disconnect();
                    handler.postDelayed(() -> connectToDevice(lastConnectedDevice), 500);
                }
            }
        }
        currentState = newState;
        handler.post(() -> {
            switch (newState) {
                case STATE_IDLE:
                    tvStatus.setText("已连接: " + (bluetoothService != null ? bluetoothService.getConnectedDeviceAddress() : ""));
                    btnTalk.setEnabled(true);
                    btnTalk.setBackgroundColor(ContextCompat.getColor(getActivity(), android.R.color.holo_green_light));
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
                        btnTalk.setBackgroundColor(ContextCompat.getColor(getActivity(), android.R.color.holo_red_light));
                    }
                    break;
                case STATE_RECEIVING:
                    tvStatus.setText("对方说话中");
                    btnTalk.setEnabled(false);
                    btnTalk.setText("对方说话中");
                    btnTalk.setBackgroundColor(ContextCompat.getColor(getActivity(), android.R.color.darker_gray));
                    btnAudioMode.setEnabled(false);
                    handler.removeCallbacks(receiveTimeoutRunnable);
                    handler.postDelayed(receiveTimeoutRunnable, RECEIVE_TIMEOUT);
                    break;
            }
        });
    }

    private void startTalking() {
        if (!isConnectionActive || isRecording) return;
        Log.d(TAG, "开始说话");
        setState(STATE_TALKING);
        audioRecorderPlayer.startRecording();
        isRecording = true;
    }

    private void stopTalking() {
        if (!isRecording) return;
        Log.d(TAG, "停止说话");
        audioRecorderPlayer.stopRecording();
        isRecording = false;
        btnTalk.setText("作用中");
        setState(STATE_IDLE);
    }

    private void setLoadingState() {
        handler.post(() -> {
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
        handler.post(() -> {
            tvStatus.setText("未连接");
            btnTalk.setEnabled(false);
            btnTalk.setText("按下对讲");
            btnTalk.setBackgroundColor(ContextCompat.getColor(getActivity(), android.R.color.darker_gray));
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

    private void playConnectionSound() {
        try {
            Vibrator vibrator = (Vibrator) getActivity().getSystemService(Context.VIBRATOR_SERVICE);
            if (vibrator != null && vibrator.hasVibrator()) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    VibrationEffect effect = VibrationEffect.createOneShot(500, VibrationEffect.DEFAULT_AMPLITUDE);
                    vibrator.vibrate(effect);
                } else {
                    vibrator.vibrate(500);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "振动失败: " + e.getMessage());
        }
    }

    private final BroadcastReceiver bluetoothReceiver = new BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);

            if (BluetoothDevice.ACTION_ACL_CONNECTED.equals(action)) {
                Log.d(TAG, "设备已连接: " + device.getName());
                handler.post(() -> tvStatus.setText("已连接: " + device.getName()));
            } else if (BluetoothDevice.ACTION_ACL_DISCONNECTED.equals(action)) {
                Log.d(TAG, "设备已断开: " + device.getName());
                handleConnectionLost();
            } else if (BluetoothAdapter.ACTION_STATE_CHANGED.equals(action)) {
                int state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR);
                if (state == BluetoothAdapter.STATE_ON) {
                    refreshPairedDevices();
                    startServer();
                } else if (state == BluetoothAdapter.STATE_OFF) {
                    handler.post(() -> tvStatus.setText("蓝牙已关闭"));
                }
            }
        }
    };

    @Override
    public void onResume() {
        super.onResume();
        refreshPairedDevices();
        startServer();
        handler.post(refreshRunnable);
    }

    @Override
    public void onPause() {
        super.onPause();
        handler.removeCallbacks(refreshRunnable);
        handler.removeCallbacks(receiveTimeoutRunnable);
        checkPacketHandler.removeCallbacks(checkPacketRunnable);
        if (isRecording) {
            stopTalking();
        }
    }

    // ★★★ 适配器 ★★★
    public static class DeviceListAdapter extends ArrayAdapter<BluetoothDevice> {
        private final LayoutInflater inflater;
        private final int resource;
        private final Context context;

        public DeviceListAdapter(Context context, int resource, List<BluetoothDevice> devices) {
            super(context, resource, devices);
            this.context = context;
            this.inflater = LayoutInflater.from(context);
            this.resource = resource;
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
}