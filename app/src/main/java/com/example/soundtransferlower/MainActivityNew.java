package com.example.soundtransferlower;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.support.v4.app.Fragment;
import android.support.v4.app.FragmentActivity;
import android.support.v4.app.FragmentManager;
import android.support.v4.app.FragmentTransaction;
import android.support.v7.app.AppCompatDelegate;
import android.support.v7.widget.PopupMenu;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.lang.reflect.Method;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import android.support.v7.app.AppCompatDelegate;

public class MainActivityNew extends FragmentActivity implements BluetoothService.MessageCallback {
    private ImageButton btnBack;
    private ImageButton btnMenu;
    private BluetoothFinder bluetoothFinder;
    private boolean isFirstLaunch = true;
    private Handler handler = new Handler();
    private AlertDialog deviceSelectionDialog;
    private ArrayAdapter<String> deviceAdapter;
    private List<BluetoothDevice> availableDevices = new ArrayList<>();
    private List<String> deviceNames = new ArrayList<>();
    private TextView mainStatus;
    private TextView emptyHint;

    private static final String TAG = "MainActivityNew";
    private BluetoothAdapter bluetoothAdapter;
    private List<BluetoothDevice> pairedDevices = new ArrayList<>();
    private boolean isSpeakerMode = false;

    private BluetoothService bluetoothService;
    private boolean serviceBound = false;

    private int currentConnectionState = BluetoothService.STATE_NONE;
    private String connectedDeviceName = "";
    private int currentMode = BluetoothService.MODE_CHAT;
    private String connectedDeviceAddress = "";
    private boolean isReceiverRegistered = false;

    private boolean isFileTransferring = false;
    private boolean isFromNotification = false;
    private String pendingFragmentType;
    private String pendingDeviceAddress;
    private String pendingDeviceName;
    private boolean discoverableRequested = false;

    // 通话相关
    private boolean isInCall = false;
    private String callTargetAddress;
    private String callTargetName;
    private long callStartTime;
    private Handler callTimerHandler = new Handler();
    private Runnable callTimerRunnable;
    private Fragment callFragment;
    private AudioRecorderPlayer callAudioRecorder;

    // 召唤相关
    private boolean isCallNotification = false;
    private String callDeviceAddress;
    private String callDeviceName;
    private String callCallerName;

    public void setFileTransferring(boolean transferring) {
        this.isFileTransferring = transferring;
    }

    public void setFileTransferStatus(String status) {
        runOnUiThread(() -> {
            if (mainStatus != null) {
                mainStatus.setText(status);
            }
        });
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        AppCompatDelegate.setCompatVectorFromResourcesEnabled(true);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main_new);

        Intent intent = getIntent();
        if (intent != null) {
            if (intent.hasExtra("LOAD_FRAGMENT")) {
                isFromNotification = true;
                isFirstLaunch = false;
                pendingFragmentType = intent.getStringExtra("LOAD_FRAGMENT");
                pendingDeviceAddress = intent.getStringExtra("DEVICE_ADDRESS");
                pendingDeviceName = intent.getStringExtra("DEVICE_NAME");
                if (intent.getBooleanExtra("IS_CALL", false)) {
                    isCallNotification = true;
                    callDeviceAddress = pendingDeviceAddress;
                    callDeviceName = pendingDeviceName;
                    callCallerName = pendingDeviceName;
                }
            }
        }

        mainStatus = findViewById(R.id.mainStatus);
        emptyHint = findViewById(R.id.emptyHint);
        updateStatusDisplay();
        updateEmptyHintVisibility();

        bluetoothFinder = new BluetoothFinder(this);

        btnBack = findViewById(R.id.btnBack);
        btnMenu = findViewById(R.id.btnMenu);

        btnBack.setOnClickListener(v -> {
            if (getSupportFragmentManager().getBackStackEntryCount() > 0) {
                getSupportFragmentManager().popBackStack();
                updateEmptyHintVisibility();
            } else {
                finish();
            }
        });

        btnMenu.setOnClickListener(v -> showPopupMenu(v));

        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        if (bluetoothAdapter == null) {
            Toast.makeText(this, "蓝牙不可用", Toast.LENGTH_SHORT).show();
            finish();
        }

        Intent serviceIntent = new Intent(this, BluetoothService.class);
        startService(serviceIntent);
        bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE);

        if (isFromNotification) {
            // 等待服务绑定
        } else {
            handler.postDelayed(() -> {
                if (isFirstLaunch && serviceBound) {
                    // ★★★ 取消设备选择弹窗，直接进入聊天界面 ★★★
                    // 但检查是否有已连接设备，如果有则跳转至对应 Fragment
                    if (bluetoothService != null && bluetoothService.getState() == BluetoothService.STATE_CONNECTED) {
                        String address = bluetoothService.getConnectedDeviceAddress();
                        String name = bluetoothService.getConnectedDeviceName();
                        if (address != null && name != null) {
                            switchToFragment("ChatWorkFragment", address, name);
                            isFirstLaunch = false;
                            return;
                        }
                    }
                    // 未连接则直接加载聊天界面，不弹窗
                    loadFragment(new ChatWorkFragment());
                    currentConnectionState = BluetoothService.STATE_NONE;
                    updateStatusDisplay();
                    isFirstLaunch = false;
                }
            }, 1000);
        }

        // 底部按钮
        Button btnTalkback = findViewById(R.id.btnTalkback);
        Button btnChat = findViewById(R.id.btnChat);
        Button btnMine = findViewById(R.id.btnMine);

        btnTalkback.setOnClickListener(v -> {
            clearBackStack();
            loadFragment(new TalkbackFragment());
            currentMode = BluetoothService.MODE_TALKBACK;
            updateStatusDisplay();
        });

        btnChat.setOnClickListener(v -> {
            clearBackStack();
            loadFragment(new ChatFragment());
            currentMode = BluetoothService.MODE_CHAT;
            updateStatusDisplay();
        });

        btnMine.setOnClickListener(v -> {
            clearBackStack();
            loadFragment(new MineFragment());
            updateStatusDisplay();
        });

        getSupportFragmentManager().addOnBackStackChangedListener(() -> {
            updateEmptyHintVisibility();
        });
    }

    private void updateEmptyHintVisibility() {
        if (getSupportFragmentManager().getBackStackEntryCount() == 0) {
            emptyHint.setVisibility(View.VISIBLE);
        } else {
            emptyHint.setVisibility(View.GONE);
        }
    }

    public void updateStatusDisplay() {
        runOnUiThread(() -> {
            if (mainStatus == null) return;
            String statusText = "";
            if (isInCall) {
                statusText = "通话中: " + callTargetName;
            } else {
                switch (currentConnectionState) {
                    case BluetoothService.STATE_NONE:
                        statusText = "未连接";
                        break;
                    case BluetoothService.STATE_LISTEN:
                        statusText = "等待连接...";
                        break;
                    case BluetoothService.STATE_CONNECTING:
                        statusText = "连接中...";
                        break;
                    case BluetoothService.STATE_CONNECTED:
                        statusText = "已连接: " + connectedDeviceName;
                        break;
                }
            }
            mainStatus.setText(statusText);
        });
    }

    // ==================== 设备选择对话框 ====================
    private void showDeviceSelectionDialog() {
        if (!discoverableRequested) {
            Intent discoverableIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE);
            discoverableIntent.putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300);
            startActivity(discoverableIntent);
            discoverableRequested = true;
        }

        availableDevices.clear();
        deviceNames.clear();
        deviceAdapter = new ArrayAdapter<>(
                this,
                android.R.layout.simple_list_item_1,
                deviceNames
        );

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("请先稍等，选择文本连接目标设备");
        builder.setAdapter(deviceAdapter, (dialog, which) -> {
            BluetoothDevice selectedDevice = availableDevices.get(which);
            connectToDeviceForChatAndNavigate(selectedDevice);
        });

        builder.setNeutralButton("直接进入", (dialog, which) -> {
            stopScanSafely();
            safeUnregisterReceiver();
            handler.removeCallbacksAndMessages(null);
            clearBackStack();
            loadFragment(new ChatWorkFragment());
            currentConnectionState = BluetoothService.STATE_NONE;
            updateStatusDisplay();
            isFirstLaunch = false;
        });

        builder.setNegativeButton("取消", null);
        builder.setCancelable(false);
        builder.setOnDismissListener(dialog -> {
            deviceSelectionDialog = null;
            stopScanSafely();
            safeUnregisterReceiver();
            handler.removeCallbacksAndMessages(null);
        });

        deviceSelectionDialog = builder.create();
        deviceSelectionDialog.show();
        deviceSelectionDialog.getButton(DialogInterface.BUTTON_NEGATIVE).setEnabled(false);
        startDeviceScanning();
    }

    private void stopScanSafely() {
        if (bluetoothFinder != null) {
            bluetoothFinder.stopScan();
        }
    }

    private void safeUnregisterReceiver() {
        if (isReceiverRegistered) {
            try {
                unregisterReceiver(deviceDiscoveryReceiver);
                isReceiverRegistered = false;
            } catch (IllegalArgumentException e) {
                Log.w(TAG, "接收器未注册: " + e.getMessage());
            }
        }
    }

    private void connectToDeviceForChatAndNavigate(BluetoothDevice device) {
        if (!serviceBound || bluetoothService == null) {
            Toast.makeText(this, "蓝牙服务未就绪", Toast.LENGTH_SHORT).show();
            return;
        }

        bluetoothService.setMode(BluetoothService.MODE_CHAT);
        currentMode = BluetoothService.MODE_CHAT;

        String localAddress = bluetoothAdapter.getAddress();
        String remoteAddress = device.getAddress();
        boolean isInitiator = localAddress.compareTo(remoteAddress) > 0;

        bluetoothService.setConnectionRole(isInitiator, remoteAddress);

        if (isInitiator) {
            Toast.makeText(this, "正在连接 " + device.getName(), Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(this, "等待 " + device.getName() + " 连接", Toast.LENGTH_SHORT).show();
        }

        connectedDeviceName = device.getName();
        connectedDeviceAddress = device.getAddress();
        currentConnectionState = BluetoothService.STATE_CONNECTING;
        updateStatusDisplay();

        ChatWorkFragment chatWorkFragment = new ChatWorkFragment();
        Bundle args = new Bundle();
        args.putString("DEVICE_ADDRESS", device.getAddress());
        args.putString("DEVICE_NAME", device.getName());
        chatWorkFragment.setArguments(args);

        clearBackStack();
        loadFragment(chatWorkFragment);
    }

    private void connectToDeviceForChat(BluetoothDevice device) {
        if (!serviceBound || bluetoothService == null) {
            Toast.makeText(this, "蓝牙服务未就绪", Toast.LENGTH_SHORT).show();
            return;
        }

        bluetoothService.setMode(BluetoothService.MODE_CHAT);
        currentMode = BluetoothService.MODE_CHAT;

        String localAddress = bluetoothAdapter.getAddress();
        String remoteAddress = device.getAddress();
        boolean isInitiator = localAddress.compareTo(remoteAddress) > 0;

        bluetoothService.setConnectionRole(isInitiator, remoteAddress);

        if (isInitiator) {
            Toast.makeText(this, "正在连接 " + device.getName(), Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(this, "等待 " + device.getName() + " 连接", Toast.LENGTH_SHORT).show();
        }

        connectedDeviceName = device.getName();
        connectedDeviceAddress = device.getAddress();
        currentConnectionState = BluetoothService.STATE_CONNECTING;
        updateStatusDisplay();

        ChatWorkFragment chatWorkFragment = new ChatWorkFragment();
        Bundle args = new Bundle();
        args.putString("DEVICE_ADDRESS", device.getAddress());
        args.putString("DEVICE_NAME", device.getName());
        chatWorkFragment.setArguments(args);

        clearBackStack();
        loadFragment(chatWorkFragment);
    }

    // ==================== 服务连接 ====================
    private ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            BluetoothService.LocalBinder binder = (BluetoothService.LocalBinder) service;
            bluetoothService = binder.getService();
            bluetoothService.registerCallback(MainActivityNew.this);
            serviceBound = true;

            if (isFromNotification) {
                // 直接跳转
                switchToFragment(pendingFragmentType, pendingDeviceAddress, pendingDeviceName);
                // ★★★ 如果已连接，强制刷新状态 ★★★
                if (bluetoothService != null && bluetoothService.getState() == BluetoothService.STATE_CONNECTED) {
                    currentConnectionState = BluetoothService.STATE_CONNECTED;
                    String connectedName = bluetoothService.getConnectedDeviceName();
                    if (connectedName != null && !connectedName.isEmpty()) {
                        connectedDeviceName = connectedName;
                    } else {
                        connectedDeviceName = pendingDeviceName;
                    }
                    updateStatusDisplay();
                }
                pendingFragmentType = null;
                pendingDeviceAddress = null;
                pendingDeviceName = null;
                isFromNotification = false;
                return;
            }

            bluetoothService.start();
            int state = bluetoothService.getState();
            onConnectionStatusChanged(state, "");
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            serviceBound = false;
        }
    };

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // 释放音频
        if (callAudioRecorder != null) {
            callAudioRecorder.release();
            callAudioRecorder = null;
        }
        // 解除服务绑定
        if (serviceBound) {
            if (bluetoothService != null) {
                bluetoothService.unregisterCallback(this);
            }
            unbindService(serviceConnection);
            serviceBound = false;
        }

        // 停止蓝牙扫描（已安全处理）
        if (bluetoothFinder != null) {
            bluetoothFinder.stopScan();
        }

        // 取消注册广播接收器
        safeUnregisterReceiver();

        handler.removeCallbacksAndMessages(null);
        callTimerHandler.removeCallbacks(callTimerRunnable);
        if (deviceSelectionDialog != null && deviceSelectionDialog.isShowing()) {
            deviceSelectionDialog.dismiss();
        }
    }

    // ==================== 蓝牙服务回调 ====================
    @Override
    public void onMessageReceived(String message, String deviceAddress) {
        final String displayMessage = message.startsWith("TXT:") ? message.substring(4) : message;
        runOnUiThread(() -> {
            Toast.makeText(this, "收到新消息: " + displayMessage, Toast.LENGTH_SHORT).show();
            Fragment currentFragment = getSupportFragmentManager().findFragmentById(R.id.fragment_container);
            if (currentFragment instanceof ChatFragment) {
                ((ChatFragment) currentFragment).refreshDeviceList();
            }
            saveMessageToChatHistory(displayMessage, deviceAddress);
        });
    }

    private void saveMessageToChatHistory(String message, String deviceAddress) {
        try {
            String filename = "chat_" + deviceAddress.replace(":", "_") + ".txt";
            File file = new File(getExternalFilesDir(null), filename);
            if (!file.exists()) {
                file.createNewFile();
            }
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());
            String timestamp = sdf.format(new Date());
            FileOutputStream fos = new FileOutputStream(file, true);
            OutputStreamWriter osw = new OutputStreamWriter(fos, "UTF-8");
            osw.write(timestamp + ": " + message + "\n");
            osw.close();
            fos.close();
        } catch (IOException e) {
            Log.e(TAG, "Error saving message to file", e);
        }
    }

    @Override
    public void onConnectionStatusChanged(int state, String deviceName) {
        runOnUiThread(() -> {
            currentConnectionState = state;
            if (deviceName != null && !deviceName.isEmpty()) {
                connectedDeviceName = deviceName;
            }
            updateStatusDisplay();

            if (state == BluetoothService.STATE_CONNECTED && !isFileTransferring && !isInCall) {
                if (bluetoothService == null) return;
                int mode = bluetoothService.getMode();
                String address = bluetoothService.getConnectedDeviceAddress();
                String name = bluetoothService.getConnectedDeviceName();
                if (address == null || name == null) return;

                Fragment currentFragment = getSupportFragmentManager().findFragmentById(R.id.fragment_container);
                boolean isChat = currentFragment instanceof ChatWorkFragment;
                boolean isTalkback = currentFragment instanceof TalkbackFragment;

                if (mode == BluetoothService.MODE_CHAT && !isChat) {
                    switchToFragment("ChatWorkFragment", address, name);
                } else if (mode == BluetoothService.MODE_TALKBACK && !isTalkback) {
                    switchToFragment("TalkbackFragment", address, name);
                }
            }
        });
    }

    @Override
    public void onTalkbackDataReceived(byte[] data, String deviceAddress) {
        if (isInCall) {
            if (callAudioRecorder != null && data != null && data.length > 10) {
                callAudioRecorder.playAudio(data, data.length);
            }
        } else {
            Fragment currentFragment = getSupportFragmentManager().findFragmentById(R.id.fragment_container);
            if (currentFragment instanceof TalkbackFragment) {
                ((TalkbackFragment) currentFragment).onTalkbackDataReceived(data, deviceAddress);
            }
        }
    }

    @Override
    public void onNonTextDataReceived(String deviceAddress) {
        // 忽略
    }

    // ==================== 呼叫回调 ====================
    @Override
    public void onCallRequest(String callerName, String deviceAddress) {
        runOnUiThread(() -> {
            if (isInCall) {
                if (bluetoothService != null) {
                    bluetoothService.write((BluetoothService.TEXT_PREFIX + BluetoothService.CALL_REJECT).getBytes());
                }
                return;
            }
            AlertDialog.Builder builder = new AlertDialog.Builder(this);
            builder.setTitle("来电");
            builder.setMessage(callerName + " 邀请您通话");
            builder.setPositiveButton("接听", (dialog, which) -> {
                if (bluetoothService != null) {
                    bluetoothService.write((BluetoothService.TEXT_PREFIX + BluetoothService.CALL_ACCEPT).getBytes());
                    startCall(deviceAddress, callerName);
                }
            });
            builder.setNegativeButton("拒绝", (dialog, which) -> {
                if (bluetoothService != null) {
                    bluetoothService.write((BluetoothService.TEXT_PREFIX + BluetoothService.CALL_REJECT).getBytes());
                }
            });
            builder.setCancelable(false);
            AlertDialog dialog = builder.create();
            dialog.show();

            handler.postDelayed(() -> {
                if (dialog.isShowing()) {
                    dialog.dismiss();
                    if (bluetoothService != null) {
                        bluetoothService.write((BluetoothService.TEXT_PREFIX + BluetoothService.CALL_HANGUP).getBytes());
                    }
                    Toast.makeText(MainActivityNew.this, "对方未响应，通话取消", Toast.LENGTH_SHORT).show();
                }
            }, 10000);
        });
    }

    @Override
    public void onCallAccepted(String deviceAddress) {
        runOnUiThread(() -> {
            if (!isInCall) {
                startCall(deviceAddress, connectedDeviceName);
            }
        });
    }

    @Override
    public void onCallRejected(String deviceAddress) {
        runOnUiThread(() -> {
            Toast.makeText(this, "对方拒绝通话", Toast.LENGTH_SHORT).show();
            endCall();
        });
    }

    @Override
    public void onCallHungUp(String deviceAddress) {
        runOnUiThread(() -> {
            Toast.makeText(this, "对方挂断", Toast.LENGTH_SHORT).show();
            endCall();
        });
    }

    // ==================== 通话管理 ====================
    public void startCall(String targetAddress, String targetName) {
        if (isInCall) return;
        isInCall = true;
        callTargetAddress = targetAddress;
        callTargetName = targetName;
        callStartTime = System.currentTimeMillis();

        if (bluetoothService != null) {
            bluetoothService.setMode(BluetoothService.MODE_TALKBACK);
            onConnectionStatusChanged(bluetoothService.getState(), bluetoothService.getConnectedDeviceName());
        }

        if (callAudioRecorder == null) {
            callAudioRecorder = new AudioRecorderPlayer(this);
            callAudioRecorder.setAudioDataSender(data -> {
                if (isInCall && bluetoothService != null && bluetoothService.getState() == BluetoothService.STATE_CONNECTED) {
                    bluetoothService.write(data, BluetoothService.MODE_TALKBACK);
                }
            });
        }
        callAudioRecorder.startRecording();

        CallFragment fragment = new CallFragment();
        Bundle args = new Bundle();
        args.putString("TARGET_NAME", targetName);
        args.putString("TARGET_ADDRESS", targetAddress);
        fragment.setArguments(args);
        callFragment = fragment;

        clearBackStack();
        getSupportFragmentManager().beginTransaction()
                .replace(R.id.fragment_container, fragment)
                .addToBackStack(null)
                .commitAllowingStateLoss();

        updateStatusDisplay();

        callTimerRunnable = () -> {
            if (isInCall) {
                updateCallDuration();
                callTimerHandler.postDelayed(callTimerRunnable, 1000);
            }
        };
        callTimerHandler.post(callTimerRunnable);
    }

    private void updateCallDuration() {
        Fragment currentFragment = getSupportFragmentManager().findFragmentById(R.id.fragment_container);
        if (currentFragment instanceof CallFragment) {
            long elapsed = System.currentTimeMillis() - callStartTime;
            ((CallFragment) currentFragment).updateDuration(elapsed);
        }
    }

    public void endCall() {
        if (!isInCall) return;
        isInCall = false;
        callTimerHandler.removeCallbacks(callTimerRunnable);

        if (callAudioRecorder != null) {
            callAudioRecorder.stopRecording();
            callAudioRecorder.release();
            callAudioRecorder = null;
        }

        if (bluetoothService != null) {
            Fragment currentFragment = getSupportFragmentManager().findFragmentById(R.id.fragment_container);
            int mode = (currentFragment instanceof TalkbackFragment) ? BluetoothService.MODE_TALKBACK : BluetoothService.MODE_CHAT;
            bluetoothService.setMode(mode);
            onConnectionStatusChanged(bluetoothService.getState(), bluetoothService.getConnectedDeviceName());
            if (bluetoothService.getState() == BluetoothService.STATE_CONNECTED) {
                bluetoothService.write((BluetoothService.TEXT_PREFIX + BluetoothService.CALL_HANGUP).getBytes());
            }
        }

        getSupportFragmentManager().popBackStack();
        updateStatusDisplay();
        callFragment = null;
        callTargetAddress = null;
        callTargetName = null;
    }

    // ==================== 拨号功能 ====================
    public void dialCall() {
        if (isInCall) {
            Toast.makeText(this, "通话中，无法拨号", Toast.LENGTH_SHORT).show();
            return;
        }
        if (bluetoothService == null || bluetoothService.getState() != BluetoothService.STATE_CONNECTED) {
            Toast.makeText(this, "未连接，无法拨号", Toast.LENGTH_SHORT).show();
            return;
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("选择通话设备");
        Set<BluetoothDevice> bonded = bluetoothAdapter.getBondedDevices();
        final List<BluetoothDevice> deviceList = new ArrayList<>(bonded);
        final List<String> deviceNames = new ArrayList<>();
        for (BluetoothDevice d : deviceList) {
            String name = d.getName();
            if (name == null || name.isEmpty()) name = "未知设备";
            deviceNames.add(name);
        }
        if (deviceList.isEmpty()) {
            Toast.makeText(this, "没有已配对的设备", Toast.LENGTH_SHORT).show();
            return;
        }
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, deviceNames);
        builder.setAdapter(adapter, (dialog, which) -> {
            BluetoothDevice selected = deviceList.get(which);
            confirmAndCall(selected);
        });
        builder.setNegativeButton("取消", null);
        builder.show();
    }

    private void confirmAndCall(BluetoothDevice device) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("确认呼叫");
        builder.setMessage("呼叫 " + device.getName() + " ?");
        builder.setPositiveButton("呼叫", (dialog, which) -> {
            if (bluetoothService == null || bluetoothService.getState() != BluetoothService.STATE_CONNECTED) {
                Toast.makeText(this, "未连接，无法呼叫", Toast.LENGTH_SHORT).show();
                return;
            }
            String currentAddr = bluetoothService.getConnectedDeviceAddress();
            if (currentAddr == null || !currentAddr.equals(device.getAddress())) {
                bluetoothService.stop();
                bluetoothService.setConnectionRole(true, device.getAddress());
                handler.postDelayed(() -> {
                    if (bluetoothService.getState() == BluetoothService.STATE_CONNECTED) {
                        String myName = bluetoothAdapter.getName();
                        if (myName == null) myName = "我";
                        bluetoothService.write((BluetoothService.TEXT_PREFIX +
                                BluetoothService.CALL_REQUEST + myName).getBytes());
                        Toast.makeText(MainActivityNew.this, "正在呼叫 " + device.getName(), Toast.LENGTH_LONG).show();
                    } else {
                        Toast.makeText(MainActivityNew.this, "连接失败", Toast.LENGTH_SHORT).show();
                    }
                }, 2000);
            } else {
                String myName = bluetoothAdapter.getName();
                if (myName == null) myName = "我";
                bluetoothService.write((BluetoothService.TEXT_PREFIX +
                        BluetoothService.CALL_REQUEST + myName).getBytes());
                Toast.makeText(this, "正在呼叫 " + device.getName(), Toast.LENGTH_LONG).show();
            }
        });
        builder.setNegativeButton("取消", null);
        builder.show();
    }

    // ==================== 召唤对话框 ====================
    private void showCallDialog() {
        runOnUiThread(() -> {
            if (bluetoothService == null || !serviceBound) {
                Toast.makeText(MainActivityNew.this, "服务未就绪，请稍后", Toast.LENGTH_SHORT).show();
                isCallNotification = false;
                clearBackStack();
                loadFragment(new ChatFragment());
                updateStatusDisplay();
                return;
            }

            AlertDialog.Builder builder = new AlertDialog.Builder(MainActivityNew.this);
            builder.setTitle("召唤上线");
            builder.setMessage(callCallerName + " 召唤您！是否接受？");
            builder.setPositiveButton("接受", (dialog, which) -> {
                if (bluetoothService != null && bluetoothService.getState() == BluetoothService.STATE_CONNECTED) {
                    String address = bluetoothService.getConnectedDeviceAddress();
                    String name = bluetoothService.getConnectedDeviceName();
                    if (address != null && name != null) {
                        switchToFragment("ChatWorkFragment", address, name);
                        return;
                    }
                }
                if (callDeviceAddress != null && !callDeviceAddress.isEmpty()) {
                    BluetoothDevice device = bluetoothAdapter.getRemoteDevice(callDeviceAddress);
                    connectToDeviceForChatAndNavigate(device);
                } else {
                    Toast.makeText(MainActivityNew.this, "无法连接", Toast.LENGTH_SHORT).show();
                }
            });
            builder.setNegativeButton("拒绝", (dialog, which) -> {
                clearBackStack();
                loadFragment(new ChatFragment());
                updateStatusDisplay();
            });
            builder.setCancelable(false);
            builder.show();
            isCallNotification = false;
        });
    }

    // ==================== Fragment 管理 ====================
    private void loadFragment(Fragment fragment) {
        FragmentManager fragmentManager = getSupportFragmentManager();
        FragmentTransaction transaction = fragmentManager.beginTransaction();
        transaction.replace(R.id.fragment_container, fragment);
        transaction.addToBackStack(null);
        transaction.commitAllowingStateLoss();
        updateEmptyHintVisibility();
    }

    private void clearBackStack() {
        FragmentManager fragmentManager = getSupportFragmentManager();
        if (fragmentManager.getBackStackEntryCount() > 0) {
            FragmentManager.BackStackEntry first = fragmentManager.getBackStackEntryAt(0);
            fragmentManager.popBackStack(first.getId(), FragmentManager.POP_BACK_STACK_INCLUSIVE);
        }
        updateEmptyHintVisibility();
    }

    public void switchToFragment(String fragmentType, String deviceAddress, String deviceName) {
        Fragment fragment = null;
        if ("TalkbackFragment".equals(fragmentType)) {
            fragment = new TalkbackFragment();
            currentMode = BluetoothService.MODE_TALKBACK;
        } else if ("ChatWorkFragment".equals(fragmentType)) {
            fragment = new ChatWorkFragment();
            currentMode = BluetoothService.MODE_CHAT;
        }

        if (fragment != null) {
            Bundle args = new Bundle();
            args.putString("DEVICE_ADDRESS", deviceAddress);
            args.putString("DEVICE_NAME", deviceName);
            fragment.setArguments(args);

            clearBackStack();
            loadFragment(fragment);

            // ★★★ 强制更新连接状态 ★★★
            if (bluetoothService != null && bluetoothService.getState() == BluetoothService.STATE_CONNECTED) {
                currentConnectionState = BluetoothService.STATE_CONNECTED;
                String connectedName = bluetoothService.getConnectedDeviceName();
                if (connectedName != null && !connectedName.isEmpty()) {
                    connectedDeviceName = connectedName;
                } else {
                    connectedDeviceName = deviceName;
                }
            } else {
                // 如果未连接，但传入设备信息，设为连接中
                currentConnectionState = BluetoothService.STATE_CONNECTING;
                connectedDeviceName = deviceName;
            }
            updateStatusDisplay();
        }
    }

    // ==================== 菜单 ====================
    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.main_menu, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.menu_pair) {
            // 配对功能
        } else if (id == R.id.menu_refresh) {
            refreshPairedDevices();
            return true;
        } else if (id == R.id.menu_select_device) {
            showDeviceSelectionDialog();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @SuppressLint("MissingPermission")
    public void refreshPairedDevices() {
        if (bluetoothAdapter == null) return;
        Set<BluetoothDevice> devices = bluetoothAdapter.getBondedDevices();
        pairedDevices.clear();
        pairedDevices.addAll(devices);
        if (deviceAdapter != null) {
            deviceAdapter.notifyDataSetChanged();
        }
        Log.d(TAG, "刷新配对设备列表，数量: " + pairedDevices.size());
    }

    public List<BluetoothDevice> getPairedDevices() {
        return pairedDevices;
    }

    // ==================== 内部Fragment ====================
    public static class ChatFragment extends Fragment {
        private DeviceListAdapter deviceAdapter;
        private MainActivityNew mainActivity;

        @Override
        public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
            View view = inflater.inflate(R.layout.fragment_chat, container, false);
            ListView listView = view.findViewById(R.id.deviceListView);

            mainActivity = (MainActivityNew) getActivity();
            if (mainActivity == null) return view;

            deviceAdapter = new DeviceListAdapter(getActivity(), R.layout.item_main, mainActivity.getPairedDevices());
            listView.setAdapter(deviceAdapter);
            mainActivity.refreshPairedDevices();

            listView.setOnItemClickListener((parent, view1, position, id) -> {
                BluetoothDevice device = mainActivity.getPairedDevices().get(position);
                if (device.getBondState() == BluetoothDevice.BOND_BONDED) {
                    mainActivity.connectToDeviceForChat(device);
                } else {
                    pairDevice(device);
                }
            });

            return view;
        }

        public void refreshDeviceList() {
            if (deviceAdapter != null && mainActivity != null) {
                deviceAdapter.notifyDataSetChanged();
            }
        }

        @SuppressLint("MissingPermission")
        private void pairDevice(BluetoothDevice device) {
            try {
                Method method = device.getClass().getMethod("createBond");
                method.invoke(device);
                Toast.makeText(getActivity(), "正在配对: " + device.getName(), Toast.LENGTH_SHORT).show();
            } catch (Exception e) {
                Log.e(TAG, "配对失败: " + e.getMessage());
                Toast.makeText(getActivity(), "配对失败", Toast.LENGTH_SHORT).show();
            }
        }
    }

    public static class MineFragment extends Fragment {
        @Override
        public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
            View view = inflater.inflate(R.layout.fragment_mine, container, false);
            Button btnName = view.findViewById(R.id.btnName);
            Button btnAbout = view.findViewById(R.id.btnAbout);
            btnName.setOnClickListener(v -> showNameDialog());
            btnAbout.setOnClickListener(v -> showAboutDialog());
            return view;
        }

        private void showNameDialog() {
            AlertDialog.Builder builder = new AlertDialog.Builder(getActivity(), R.style.CustomDialogTheme);
            builder.setTitle("修改蓝牙名称");
            MainActivityNew mainActivity = (MainActivityNew) getActivity();
            String currentName = "";
            if (mainActivity != null && mainActivity.bluetoothAdapter != null) {
                currentName = mainActivity.bluetoothAdapter.getName();
            }
            LinearLayout layout = new LinearLayout(getActivity());
            layout.setOrientation(LinearLayout.VERTICAL);
            layout.setPadding(50, 30, 50, 10);
            TextView tvCurrentName = new TextView(getActivity());
            tvCurrentName.setText("当前名称: " + currentName);
            tvCurrentName.setTextSize(16);
            tvCurrentName.setTextIsSelectable(true);
            layout.addView(tvCurrentName);
            View space = new View(getActivity());
            space.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 20));
            layout.addView(space);
            final EditText input = new EditText(getActivity());
            input.setText(currentName);
            input.setHint("输入新名称");
            input.setSelectAllOnFocus(true);
            layout.addView(input);
            builder.setView(layout);
            builder.setPositiveButton("确认", (dialog, which) -> {
                String newName = input.getText().toString();
                if (!newName.isEmpty()) {
                    setBluetoothName(newName);
                }
            });
            builder.setNegativeButton("取消", (dialog, which) -> dialog.cancel());
            builder.show();
        }

        @SuppressLint("MissingPermission")
        private void setBluetoothName(String name) {
            MainActivityNew mainActivity = (MainActivityNew) getActivity();
            if (mainActivity != null && mainActivity.bluetoothAdapter != null) {
                mainActivity.bluetoothAdapter.setName(name);
                Toast.makeText(getActivity(), "蓝牙名称已修改", Toast.LENGTH_SHORT).show();
            }
        }

        private void showAboutDialog() {
            new AlertDialog.Builder(getActivity(), R.style.CustomDialogTheme)
                    .setTitle("关于")
                    .setMessage(R.string.about)
                    .setPositiveButton("确定", null)
                    .show();
        }
    }

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
                String name = device.getName();
                if (name == null || name.isEmpty()) {
                    name = "未知设备";
                }
                deviceName.setText(name);
                deviceAddress.setText(device.getAddress());
                int textColor = device.getBondState() == BluetoothDevice.BOND_BONDED ?
                        context.getResources().getColor(android.R.color.black) :
                        context.getResources().getColor(android.R.color.darker_gray);
                deviceName.setTextColor(textColor);
                deviceAddress.setTextColor(context.getResources().getColor(android.R.color.darker_gray));
                avatar.setText("蓝牙");
            }
            return convertView;
        }
    }

    // ==================== 扫描和广播 ====================
    private void startDeviceScanning() {
        bluetoothFinder.fetchPairedDevices();
        IntentFilter filter = new IntentFilter(BluetoothDevice.ACTION_FOUND);
        try {
            registerReceiver(deviceDiscoveryReceiver, filter);
            isReceiverRegistered = true;
        } catch (Exception e) {
            Log.e(TAG, "注册接收器时出错: " + e.getMessage());
        }

        handler.postDelayed(() -> {
            bluetoothFinder.startScan();
            handler.postDelayed(() -> {
                bluetoothFinder.stopScan();
                handler.postDelayed(() -> {
                    bluetoothFinder.startScan();
                    handler.postDelayed(() -> {
                        bluetoothFinder.stopScan();
                        if (deviceSelectionDialog != null && deviceSelectionDialog.isShowing()) {
                            deviceSelectionDialog.getButton(DialogInterface.BUTTON_NEGATIVE).setOnClickListener(null);
                            deviceSelectionDialog.getButton(DialogInterface.BUTTON_NEGATIVE).setOnClickListener(v -> {
                                deviceSelectionDialog.dismiss();
                            });
                            deviceSelectionDialog.getButton(DialogInterface.BUTTON_NEGATIVE).setEnabled(true);
                        }
                        handler.postDelayed(() -> {
                            if (deviceSelectionDialog != null && deviceSelectionDialog.isShowing()) {
                                deviceSelectionDialog.dismiss();
                                if (availableDevices.isEmpty()) {
                                    Toast.makeText(MainActivityNew.this, "没有找到附近已配对的设备", Toast.LENGTH_SHORT).show();
                                }
                            }
                        }, 3000);
                    }, 3000);
                }, 100);
            }, 6000);
        }, 500);
    }

    private final BroadcastReceiver deviceDiscoveryReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (BluetoothDevice.ACTION_FOUND.equals(action)) {
                BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                if (device != null) {
                    if (bluetoothFinder.getPairedDevices().contains(device)) {
                        if (!device.getAddress().equals(connectedDeviceAddress)) {
                            if (!availableDevices.contains(device)) {
                                availableDevices.add(device);
                                String name = device.getName();
                                if (name == null || name.isEmpty()) {
                                    name = "未知设备 (" + device.getAddress() + ")";
                                }
                                deviceNames.add(name);
                                if (deviceAdapter != null) {
                                    runOnUiThread(() -> deviceAdapter.notifyDataSetChanged());
                                }
                            }
                        }
                    }
                }
            }
        }
    };

    private void showPopupMenu(View view) {
        PopupMenu popup = new PopupMenu(this, view);
        popup.getMenuInflater().inflate(R.menu.main_menu, popup.getMenu());
        popup.setOnMenuItemClickListener(this::onOptionsItemSelected);
        popup.show();
    }

    private void startReconnectTask(String deviceAddress, String deviceName) {
        if (bluetoothService != null) {
            bluetoothService.stop();
        }
        Handler reconnectHandler = new Handler();
        int[] reconnectAttempts = {0};

        Runnable reconnectRunnable = new Runnable() {
            @Override
            public void run() {
                if (bluetoothService != null && bluetoothService.getState() == BluetoothService.STATE_CONNECTED) {
                    Toast.makeText(MainActivityNew.this, "已成功连接到 " + deviceName, Toast.LENGTH_SHORT).show();
                    return;
                }

                if (reconnectAttempts[0] < 2) {
                    reconnectAttempts[0]++;
                    String attemptText = reconnectAttempts[0] == 1 ? "第一次" : "第二次";
                    Toast.makeText(MainActivityNew.this,
                            attemptText + "尝试连接到 " + deviceName,
                            Toast.LENGTH_SHORT).show();

                    BluetoothDevice device = bluetoothAdapter.getRemoteDevice(deviceAddress);
                    if (device != null && bluetoothService != null) {
                        String localAddress = bluetoothAdapter.getAddress();
                        String remoteAddress = deviceAddress;
                        boolean isInitiator = localAddress.compareTo(remoteAddress) > 0;
                        bluetoothService.setConnectionRole(isInitiator, remoteAddress);
                    }
                    reconnectHandler.postDelayed(this, 1500);
                } else {
                    Toast.makeText(MainActivityNew.this,
                            "无法连接到 " + deviceName + "，请手动重试",
                            Toast.LENGTH_LONG).show();
                }
            }
        };

        reconnectHandler.postDelayed(reconnectRunnable, 500);
    }
}