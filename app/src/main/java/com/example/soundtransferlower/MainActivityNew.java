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
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.support.v4.app.Fragment;
import android.support.v4.app.FragmentActivity;
import android.support.v4.app.FragmentManager;
import android.support.v7.app.AppCompatDelegate;
import android.support.v7.widget.PopupMenu;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
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

public class MainActivityNew extends FragmentActivity implements IMessageCallback.MessageCallback {

    private static final String TAG = "MainActivityNew";

    // ---------- UI 组件 ----------
    private ImageButton btnBack;
    private ImageButton btnMenu;
    private TextView mainStatus;
    private TextView emptyHint;

    // ---------- 蓝牙服务（接口） ----------
    private IBluetoothService bluetoothService;
    private boolean serviceBound = false;

    // ---------- 蓝牙适配器和设备 ----------
    private BluetoothAdapter bluetoothAdapter;
    private List<BluetoothDevice> pairedDevices = new ArrayList<>();
    private BluetoothFinder bluetoothFinder;

    // ---------- 连接状态 ----------
    private int currentConnectionState = IBluetoothService.STATE_NONE;
    private String connectedDeviceName = "";
    private String connectedDeviceAddress = "";
    private int currentMode = IBluetoothService.MODE_CHAT;

    // ---------- 文件传输 ----------
    private boolean isFileTransferring = false;

    // ---------- 通话相关 ----------
    private boolean isInCall = false;
    private String callTargetAddress;
    private String callTargetName;
    private long callStartTime;
    private Handler callTimerHandler = new Handler();
    private Runnable callTimerRunnable;
    private Fragment callFragment;
    private AudioRecorderPlayer callAudioRecorder;

    // ---------- 召唤/通知 ----------
    private boolean isFromNotification = false;
    private String pendingFragmentType;
    private String pendingDeviceAddress;
    private String pendingDeviceName;
    private boolean isCallNotification = false;
    private String callDeviceAddress;
    private String callDeviceName;
    private String callCallerName;

    // ---------- 设备选择对话框 ----------
    private AlertDialog deviceSelectionDialog;
    private ArrayAdapter<String> deviceAdapter;
    private List<BluetoothDevice> availableDevices = new ArrayList<>();
    private List<String> deviceNames = new ArrayList<>();
    private boolean discoverableRequested = false;

    // ---------- 广播接收器 ----------
    private boolean isReceiverRegistered = false;
    private final BroadcastReceiver deviceDiscoveryReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (BluetoothDevice.ACTION_FOUND.equals(action)) {
                BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                if (device != null && bluetoothFinder.getPairedDevices().contains(device)) {
                    if (!device.getAddress().equals(connectedDeviceAddress) && !availableDevices.contains(device)) {
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
    };

    // ---------- Handler ----------
    private Handler handler = new Handler();

    // ---------- ServiceConnection ----------
    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            BluetoothService.LocalBinder binder = (BluetoothService.LocalBinder) service;
            bluetoothService = binder.getInterface();   // ★ 获取接口
            bluetoothService.registerCallback(MainActivityNew.this);
            serviceBound = true;

            if (isFromNotification) {
                switchToFragment(pendingFragmentType, pendingDeviceAddress, pendingDeviceName);
                if (bluetoothService != null && bluetoothService.getState() == IBluetoothService.STATE_CONNECTED) {
                    String connName = bluetoothService.getConnectedDeviceName();
                    if (connName != null && !connName.isEmpty()) connectedDeviceName = connName;
                    else connectedDeviceName = pendingDeviceName;
                    updateStatusDisplay();
                }
                pendingFragmentType = null;
                pendingDeviceAddress = null;
                pendingDeviceName = null;
                isFromNotification = false;
                return;
            }

            bluetoothService.start();
            onConnectionStatusChanged(bluetoothService.getState(), "");
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            serviceBound = false;
        }
    };

    // ==================== 生命周期 ====================

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        AppCompatDelegate.setCompatVectorFromResourcesEnabled(true);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main_new);
// 初始化日志开关
        SharedPreferences prefs = getSharedPreferences("settings", Context.MODE_PRIVATE);
        boolean enableLog = prefs.getBoolean("enable_log", false);
        LogUtil.setEnableLog(enableLog);
        // 处理 Intent 传参
        Intent intent = getIntent();
        if (intent != null) {
            if (intent.hasExtra("LOAD_FRAGMENT")) {
                isFromNotification = true;
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

        initUI();
        initBluetooth();

        // 绑定服务
        Intent serviceIntent = new Intent(this, BluetoothService.class);
        startService(serviceIntent);
        bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE);

        // 默认加载聊天界面
        if (!isFromNotification) {
            handler.postDelayed(() -> {
                if (isFirstLaunch() && serviceBound) {
                    if (bluetoothService != null && bluetoothService.getState() == IBluetoothService.STATE_CONNECTED) {
                        String addr = bluetoothService.getConnectedDeviceAddress();
                        String name = bluetoothService.getConnectedDeviceName();
                        if (addr != null && name != null) {
                            switchToFragment("ChatWorkFragment", addr, name);
                            return;
                        }
                    }
                    loadFragment(new ChatWorkFragment());
                    currentConnectionState = IBluetoothService.STATE_NONE;
                    updateStatusDisplay();
                }
            }, 1000);
        }

        // 底部导航
        Button btnTalkback = findViewById(R.id.btnTalkback);
        Button btnChat = findViewById(R.id.btnChat);
        Button btnMine = findViewById(R.id.btnMine);

        btnTalkback.setOnClickListener(v -> {
            clearBackStack();
            loadFragment(new TalkbackFragment());
            currentMode = IBluetoothService.MODE_TALKBACK;
            updateStatusDisplay();
        });

        btnChat.setOnClickListener(v -> {
            clearBackStack();
            loadFragment(new ChatFragment());
            currentMode = IBluetoothService.MODE_CHAT;
            updateStatusDisplay();
        });

        btnMine.setOnClickListener(v -> {
            clearBackStack();
            loadFragment(new MineFragment());
            updateStatusDisplay();
        });

        getSupportFragmentManager().addOnBackStackChangedListener(() -> updateEmptyHintVisibility());
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // 释放音频
        if (callAudioRecorder != null) {
            callAudioRecorder.release();
            callAudioRecorder = null;
        }
        // 解绑服务
        if (serviceBound) {
            if (bluetoothService != null) {
                bluetoothService.unregisterCallback(this);
            }
            unbindService(serviceConnection);
            serviceBound = false;
        }
        if (bluetoothFinder != null) {
            bluetoothFinder.stopScan();
        }
        safeUnregisterReceiver();
        handler.removeCallbacksAndMessages(null);
        callTimerHandler.removeCallbacks(callTimerRunnable);
        if (deviceSelectionDialog != null && deviceSelectionDialog.isShowing()) {
            deviceSelectionDialog.dismiss();
        }
    }

    // ==================== UI 初始化 ====================

    private void initUI() {
        btnBack = findViewById(R.id.btnBack);
        btnMenu = findViewById(R.id.btnMenu);
        mainStatus = findViewById(R.id.mainStatus);
        emptyHint = findViewById(R.id.emptyHint);

        btnBack.setOnClickListener(v -> {
            if (getSupportFragmentManager().getBackStackEntryCount() > 0) {
                getSupportFragmentManager().popBackStack();
                updateEmptyHintVisibility();
            } else {
                finish();
            }
        });

        btnMenu.setOnClickListener(v -> showPopupMenu(v));

        updateStatusDisplay();
        updateEmptyHintVisibility();
    }

    private void initBluetooth() {
        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        if (bluetoothAdapter == null) {
            Toast.makeText(this, "蓝牙不可用", Toast.LENGTH_SHORT).show();
            finish();
        }
        bluetoothFinder = new BluetoothFinder(this);
        refreshPairedDevices();
    }

    // ==================== 状态更新 ====================

    public void updateStatusDisplay() {
        runOnUiThread(() -> {
            if (mainStatus == null) return;
            String statusText;
            if (isInCall) {
                statusText = "通话中: " + callTargetName;
            } else {
                switch (currentConnectionState) {
                    case IBluetoothService.STATE_NONE:
                        statusText = "未连接";
                        break;
                    case IBluetoothService.STATE_LISTEN:
                        statusText = "等待连接...";
                        break;
                    case IBluetoothService.STATE_CONNECTING:
                        statusText = "连接中...";
                        break;
                    case IBluetoothService.STATE_CONNECTED:
                        statusText = "已连接: " + connectedDeviceName;
                        break;
                    default:
                        statusText = "";
                }
            }
            mainStatus.setText(statusText);
        });
    }

    private void updateEmptyHintVisibility() {
        if (getSupportFragmentManager().getBackStackEntryCount() == 0) {
            emptyHint.setVisibility(View.VISIBLE);
        } else {
            emptyHint.setVisibility(View.GONE);
        }
    }

    private boolean isFirstLaunch() {
        return getSupportFragmentManager().getBackStackEntryCount() == 0;
    }

    // ==================== Fragment 管理 ====================

    private void loadFragment(Fragment fragment) {
        getSupportFragmentManager().beginTransaction()
                .replace(R.id.fragment_container, fragment)
                .addToBackStack(null)
                .commitAllowingStateLoss();
        updateEmptyHintVisibility();
    }

    private void clearBackStack() {
        FragmentManager fm = getSupportFragmentManager();
        if (fm.getBackStackEntryCount() > 0) {
            FragmentManager.BackStackEntry first = fm.getBackStackEntryAt(0);
            fm.popBackStack(first.getId(), FragmentManager.POP_BACK_STACK_INCLUSIVE);
        }
        updateEmptyHintVisibility();
    }

    public void switchToFragment(String fragmentType, String deviceAddress, String deviceName) {
        Fragment fragment = null;
        if ("TalkbackFragment".equals(fragmentType)) {
            fragment = new TalkbackFragment();
            currentMode = IBluetoothService.MODE_TALKBACK;
            if (bluetoothService != null) {
                bluetoothService.setMode(IBluetoothService.MODE_TALKBACK);
            }
        } else if ("ChatWorkFragment".equals(fragmentType)) {
            fragment = new ChatWorkFragment();
            currentMode = IBluetoothService.MODE_CHAT;
            if (bluetoothService != null) {
                bluetoothService.setMode(IBluetoothService.MODE_CHAT);
            }
        }
        if (fragment != null) {
            Bundle args = new Bundle();
            args.putString("DEVICE_ADDRESS", deviceAddress);
            args.putString("DEVICE_NAME", deviceName);
            fragment.setArguments(args);
            clearBackStack();
            loadFragment(fragment);
            // 更新连接状态显示
            if (bluetoothService != null && bluetoothService.getState() == IBluetoothService.STATE_CONNECTED) {
                currentConnectionState = IBluetoothService.STATE_CONNECTED;
                String name = bluetoothService.getConnectedDeviceName();
                if (name != null && !name.isEmpty()) connectedDeviceName = name;
                else connectedDeviceName = deviceName;
            } else {
                currentConnectionState = IBluetoothService.STATE_CONNECTING;
                connectedDeviceName = deviceName;
            }
            updateStatusDisplay();
        }
    }
    // ==================== 设备连接 ====================

    @SuppressLint("MissingPermission")
    public void refreshPairedDevices() {
        if (bluetoothAdapter == null) return;
        Set<BluetoothDevice> devices = bluetoothAdapter.getBondedDevices();
        pairedDevices.clear();
        pairedDevices.addAll(devices);
        LogUtil.d(TAG, "刷新设备列表，数量: " + pairedDevices.size());
    }

    public List<BluetoothDevice> getPairedDevices() {
        return pairedDevices;
    }

    /**
     * 连接到设备并进入聊天界面（如果已连接则直接跳转）
     */
    public void connectToDeviceForChat(BluetoothDevice device) {
        String currentAddress = bluetoothService != null ? bluetoothService.getConnectedDeviceAddress() : null;
        if (currentAddress != null && currentAddress.equals(device.getAddress())) {
            // 已连接，直接跳转
            ChatWorkFragment f = new ChatWorkFragment();
            Bundle args = new Bundle();
            args.putString("DEVICE_ADDRESS", device.getAddress());
            args.putString("DEVICE_NAME", device.getName());
            f.setArguments(args);
            clearBackStack();
            loadFragment(f);
            updateStatusDisplay();
            return;
        }

        if (!serviceBound || bluetoothService == null) {
            Toast.makeText(this, "蓝牙服务未就绪", Toast.LENGTH_SHORT).show();
            return;
        }

        bluetoothService.setMode(IBluetoothService.MODE_CHAT);
        currentMode = IBluetoothService.MODE_CHAT;

        String localAddress = bluetoothAdapter.getAddress();
        String remoteAddress = device.getAddress();
        boolean isInitiator = localAddress.compareTo(remoteAddress) > 0;
        bluetoothService.setConnectionRole(isInitiator, remoteAddress);

        Toast.makeText(this, isInitiator ? "正在连接 " + device.getName() : "等待 " + device.getName() + " 连接", Toast.LENGTH_SHORT).show();

        connectedDeviceName = device.getName();
        connectedDeviceAddress = device.getAddress();
        currentConnectionState = IBluetoothService.STATE_CONNECTING;
        updateStatusDisplay();

        ChatWorkFragment f = new ChatWorkFragment();
        Bundle args = new Bundle();
        args.putString("DEVICE_ADDRESS", device.getAddress());
        args.putString("DEVICE_NAME", device.getName());
        f.setArguments(args);
        clearBackStack();
        loadFragment(f);
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
        deviceAdapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, deviceNames);

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("请选择连接目标设备");
        builder.setAdapter(deviceAdapter, (dialog, which) -> {
            BluetoothDevice selected = availableDevices.get(which);
            connectToDeviceForChat(selected);
        });
        builder.setNeutralButton("直接进入", (dialog, which) -> {
            stopScanSafely();
            safeUnregisterReceiver();
            handler.removeCallbacksAndMessages(null);
            clearBackStack();
            loadFragment(new ChatWorkFragment());
            currentConnectionState = IBluetoothService.STATE_NONE;
            updateStatusDisplay();
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

    private void startDeviceScanning() {
        bluetoothFinder.fetchPairedDevices();
        IntentFilter filter = new IntentFilter(BluetoothDevice.ACTION_FOUND);
        try {
            registerReceiver(deviceDiscoveryReceiver, filter);
            isReceiverRegistered = true;
        } catch (Exception e) {
            LogUtil.e(TAG, "注册接收器失败", e);
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
                            deviceSelectionDialog.getButton(DialogInterface.BUTTON_NEGATIVE).setEnabled(true);
                            deviceSelectionDialog.getButton(DialogInterface.BUTTON_NEGATIVE).setOnClickListener(v -> deviceSelectionDialog.dismiss());
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

    private void stopScanSafely() {
        if (bluetoothFinder != null) bluetoothFinder.stopScan();
    }

    private void safeUnregisterReceiver() {
        if (isReceiverRegistered) {
            try {
                unregisterReceiver(deviceDiscoveryReceiver);
                isReceiverRegistered = false;
            } catch (IllegalArgumentException e) {
                LogUtil.w(TAG, "接收器未注册");
            }
        }
    }

    // ==================== 菜单 ====================

    private void showPopupMenu(View view) {
        PopupMenu popup = new PopupMenu(this, view);
        popup.getMenuInflater().inflate(R.menu.main_menu, popup.getMenu());
        popup.setOnMenuItemClickListener(this::onOptionsItemSelected);
        popup.show();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.main_menu, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == R.id.menu_pair) {
            // 配对功能（可扩展）
            return true;
        } else if (item.getItemId() == R.id.menu_refresh) {
            refreshPairedDevices();
            return true;
        } else if (item.getItemId() == R.id.menu_select_device) {
            showDeviceSelectionDialog();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    // ==================== 蓝牙服务回调 ====================

    @Override
    public void onMessageReceived(String message, String deviceAddress) {
        if (message == null) return;
        String trimmed = message.trim();
        // 过滤控制消息
        if (trimmed.startsWith(IBluetoothService.FILE_REQUEST_PREFIX) ||
                trimmed.equals(IBluetoothService.FILE_ACCEPT) ||
                trimmed.equals(IBluetoothService.FILE_REJECT) ||
                trimmed.startsWith(IBluetoothService.CALL_PREFIX) ||
                trimmed.startsWith(IBluetoothService.CALL_REQUEST) ||
                trimmed.equals(IBluetoothService.CALL_ACCEPT) ||
                trimmed.equals(IBluetoothService.CALL_REJECT) ||
                trimmed.equals(IBluetoothService.CALL_HANGUP)) {
            return;
        }

        final String displayMessage = message.startsWith(IBluetoothService.TEXT_PREFIX) ? message.substring(4) : message;
        runOnUiThread(() -> {
            Toast.makeText(this, "收到新消息: " + displayMessage, Toast.LENGTH_SHORT).show();
            Fragment current = getSupportFragmentManager().findFragmentById(R.id.fragment_container);
            if (current instanceof ChatFragment) {
                ((ChatFragment) current).refreshDeviceList();
            }
            saveMessageToChatHistory(displayMessage, deviceAddress);
        });
    }

    @Override
    public void onConnectionStatusChanged(int state, String deviceName) {
        runOnUiThread(() -> {
            currentConnectionState = state;
            if (deviceName != null && !deviceName.isEmpty()) {
                connectedDeviceName = deviceName;
            }
            updateStatusDisplay();

            if (state == IBluetoothService.STATE_CONNECTED && !isFileTransferring && !isInCall) {
                if (bluetoothService == null) return;
                int mode = bluetoothService.getMode();
                String address = bluetoothService.getConnectedDeviceAddress();
                String connName = bluetoothService.getConnectedDeviceName();
                if (address == null || connName == null) return;
                Fragment current = getSupportFragmentManager().findFragmentById(R.id.fragment_container);
                boolean isChat = current instanceof ChatWorkFragment;
                boolean isTalkback = current instanceof TalkbackFragment;

                if (mode == IBluetoothService.MODE_CHAT && !isChat) {
                    switchToFragment("ChatWorkFragment", address, connName);
                } else if (mode == IBluetoothService.MODE_TALKBACK && !isTalkback) {
                    switchToFragment("TalkbackFragment", address, connName);
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
            Fragment current = getSupportFragmentManager().findFragmentById(R.id.fragment_container);
            if (current instanceof TalkbackFragment) {
                ((TalkbackFragment) current).onTalkbackDataReceived(data, deviceAddress);
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
                    bluetoothService.write((IBluetoothService.TEXT_PREFIX + IBluetoothService.CALL_REJECT).getBytes());
                }
                return;
            }
            AlertDialog.Builder builder = new AlertDialog.Builder(this);
            builder.setTitle("来电");
            builder.setMessage(callerName + " 邀请您通话");
            builder.setPositiveButton("接听", (dialog, which) -> {
                if (bluetoothService != null) {
                    bluetoothService.write((IBluetoothService.TEXT_PREFIX + IBluetoothService.CALL_ACCEPT).getBytes());
                    startCall(deviceAddress, callerName);
                }
            });
            builder.setNegativeButton("拒绝", (dialog, which) -> {
                if (bluetoothService != null) {
                    bluetoothService.write((IBluetoothService.TEXT_PREFIX + IBluetoothService.CALL_REJECT).getBytes());
                }
            });
            builder.setCancelable(false);
            AlertDialog dialog = builder.create();
            dialog.show();

            handler.postDelayed(() -> {
                if (dialog.isShowing()) {
                    dialog.dismiss();
                    if (bluetoothService != null) {
                        bluetoothService.write((IBluetoothService.TEXT_PREFIX + IBluetoothService.CALL_HANGUP).getBytes());
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
            bluetoothService.setMode(IBluetoothService.MODE_TALKBACK);
            onConnectionStatusChanged(bluetoothService.getState(), bluetoothService.getConnectedDeviceName());
        }

        if (callAudioRecorder == null) {
            callAudioRecorder = new AudioRecorderPlayer(this);
            callAudioRecorder.setAudioDataSender(data -> {
                if (isInCall && bluetoothService != null && bluetoothService.getState() == IBluetoothService.STATE_CONNECTED) {
                    bluetoothService.write(data, IBluetoothService.MODE_TALKBACK);
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
        Fragment current = getSupportFragmentManager().findFragmentById(R.id.fragment_container);
        if (current instanceof CallFragment) {
            long elapsed = System.currentTimeMillis() - callStartTime;
            ((CallFragment) current).updateDuration(elapsed);
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

        if (bluetoothService != null && bluetoothService.getState() == IBluetoothService.STATE_CONNECTED) {
            bluetoothService.write((IBluetoothService.TEXT_PREFIX + IBluetoothService.CALL_HANGUP).getBytes());
        }

        // 返回聊天界面
        String address = bluetoothService != null ? bluetoothService.getConnectedDeviceAddress() : null;
        String name = bluetoothService != null ? bluetoothService.getConnectedDeviceName() : null;
        if (address != null && name != null) {
            getSupportFragmentManager().popBackStack();
            switchToFragment("ChatWorkFragment", address, name);
        } else {
            getSupportFragmentManager().popBackStack();
        }

        updateStatusDisplay();
        callFragment = null;
        callTargetAddress = null;
        callTargetName = null;
    }

    // ==================== 拨号 ====================

    public void dialCall() {
        if (isInCall) {
            Toast.makeText(this, "通话中，无法拨号", Toast.LENGTH_SHORT).show();
            return;
        }
        if (bluetoothService == null || bluetoothService.getState() != IBluetoothService.STATE_CONNECTED) {
            Toast.makeText(this, "未连接，无法拨号", Toast.LENGTH_SHORT).show();
            return;
        }

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

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("选择通话设备");
        builder.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, deviceNames), (dialog, which) -> {
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
            if (bluetoothService == null || bluetoothService.getState() != IBluetoothService.STATE_CONNECTED) {
                Toast.makeText(this, "未连接，无法呼叫", Toast.LENGTH_SHORT).show();
                return;
            }
            String currentAddr = bluetoothService.getConnectedDeviceAddress();
            if (currentAddr == null || !currentAddr.equals(device.getAddress())) {
                bluetoothService.stop();
                bluetoothService.setConnectionRole(true, device.getAddress());
                handler.postDelayed(() -> {
                    if (bluetoothService.getState() == IBluetoothService.STATE_CONNECTED) {
                        String myName = bluetoothAdapter.getName();
                        if (myName == null) myName = "我";
                        bluetoothService.write((IBluetoothService.TEXT_PREFIX +
                                IBluetoothService.CALL_REQUEST + myName).getBytes());
                        Toast.makeText(MainActivityNew.this, "正在呼叫 " + device.getName(), Toast.LENGTH_LONG).show();
                    } else {
                        Toast.makeText(MainActivityNew.this, "连接失败", Toast.LENGTH_SHORT).show();
                    }
                }, 2000);
            } else {
                String myName = bluetoothAdapter.getName();
                if (myName == null) myName = "我";
                bluetoothService.write((IBluetoothService.TEXT_PREFIX +
                        IBluetoothService.CALL_REQUEST + myName).getBytes());
                Toast.makeText(this, "正在呼叫 " + device.getName(), Toast.LENGTH_LONG).show();
            }
        });
        builder.setNegativeButton("取消", null);
        builder.show();
    }

    // ==================== 召唤通知 ====================

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
                if (bluetoothService != null && bluetoothService.getState() == IBluetoothService.STATE_CONNECTED) {
                    String address = bluetoothService.getConnectedDeviceAddress();
                    String name = bluetoothService.getConnectedDeviceName();
                    if (address != null && name != null) {
                        switchToFragment("ChatWorkFragment", address, name);
                        return;
                    }
                }
                if (callDeviceAddress != null && !callDeviceAddress.isEmpty()) {
                    BluetoothDevice device = bluetoothAdapter.getRemoteDevice(callDeviceAddress);
                    connectToDeviceForChat(device);
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

    // ==================== 工具方法 ====================

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

    private void saveMessageToChatHistory(String message, String deviceAddress) {
        try {
            String filename = "chat_" + deviceAddress.replace(":", "_") + ".txt";
            File file = new File(getExternalFilesDir(null), filename);
            if (!file.exists()) file.createNewFile();
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());
            String timestamp = sdf.format(new Date());
            FileOutputStream fos = new FileOutputStream(file, true);
            OutputStreamWriter osw = new OutputStreamWriter(fos, "UTF-8");
            osw.write(timestamp + ": " + message + "\n");
            osw.close();
            fos.close();
        } catch (IOException e) {
            LogUtil.e(TAG, "保存消息失败", e);
        }
    }

    // ==================== 内部 Fragments ====================

    public static class ChatFragment extends Fragment {
        private DeviceListAdapter adapter;
        private MainActivityNew mainActivity;

        @Override
        public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
            View view = inflater.inflate(R.layout.fragment_chat, container, false);
            ListView listView = view.findViewById(R.id.deviceListView);
            mainActivity = (MainActivityNew) getActivity();
            if (mainActivity != null) {
                adapter = new DeviceListAdapter(getActivity(), R.layout.item_main, mainActivity.getPairedDevices());
                listView.setAdapter(adapter);
                mainActivity.refreshPairedDevices();
                listView.setOnItemClickListener((parent, v, position, id) -> {
                    BluetoothDevice device = mainActivity.getPairedDevices().get(position);
                    if (device.getBondState() == BluetoothDevice.BOND_BONDED) {
                        mainActivity.connectToDeviceForChat(device);
                    } else {
                        pairDevice(device);
                    }
                });
            }
            return view;
        }

        public void refreshDeviceList() {
            if (adapter != null && mainActivity != null) {
                adapter.notifyDataSetChanged();
            }
        }

        @SuppressLint("MissingPermission")
        private void pairDevice(BluetoothDevice device) {
            try {
                Method method = device.getClass().getMethod("createBond");
                method.invoke(device);
                Toast.makeText(getActivity(), "正在配对: " + device.getName(), Toast.LENGTH_SHORT).show();
            } catch (Exception e) {
                LogUtil.e(TAG, "配对失败", e);
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
            Button btnSettings = view.findViewById(R.id.btnSettings);  // ★ 新增

            btnName.setOnClickListener(v -> showNameDialog());
            btnAbout.setOnClickListener(v -> showAboutDialog());

            // ★ 设置按钮点击事件
            btnSettings.setOnClickListener(v -> {
                SettingFragment fragment = new SettingFragment();
                // 使用 getActivity().getSupportFragmentManager() 保证兼容
                getActivity().getSupportFragmentManager()
                        .beginTransaction()
                        .replace(R.id.fragment_container, fragment)
                        .addToBackStack("settings")
                        .commit();
            });

            return view;
        }

        private void showNameDialog() {
            AlertDialog.Builder builder = new AlertDialog.Builder(getActivity(), R.style.CustomDialogTheme);
            builder.setTitle("修改蓝牙名称");
            MainActivityNew main = (MainActivityNew) getActivity();
            String currentName = (main != null && main.bluetoothAdapter != null) ? main.bluetoothAdapter.getName() : "";

            LinearLayout layout = new LinearLayout(getActivity());
            layout.setOrientation(LinearLayout.VERTICAL);
            layout.setPadding(50, 30, 50, 10);
            TextView tvCurrent = new TextView(getActivity());
            tvCurrent.setText("当前名称: " + currentName);
            tvCurrent.setTextSize(16);
            tvCurrent.setTextIsSelectable(true);
            layout.addView(tvCurrent);

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
                if (!newName.isEmpty() && main != null && main.bluetoothAdapter != null) {
                    main.bluetoothAdapter.setName(newName);
                    Toast.makeText(getActivity(), "蓝牙名称已修改", Toast.LENGTH_SHORT).show();
                }
            });
            builder.setNegativeButton("取消", null);
            builder.show();
        }

        private void showAboutDialog() {
            String versionName = "未知版本";
            try {
                if (getActivity() != null) {
                    versionName = getActivity().getPackageManager()
                            .getPackageInfo(getActivity().getPackageName(), 0).versionName;
                }
            } catch (PackageManager.NameNotFoundException e) {
                e.printStackTrace();
            }
            String aboutText = getString(R.string.about, versionName);
            new AlertDialog.Builder(getActivity(), R.style.CustomDialogTheme)
                    .setTitle("关于")
                    .setMessage(aboutText)
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
                TextView nameView = convertView.findViewById(R.id.deviceName);
                TextView addrView = convertView.findViewById(R.id.deviceAddress);
                TextView avatar = convertView.findViewById(R.id.avatar);
                String name = device.getName();
                if (name == null || name.isEmpty()) name = "未知设备";
                nameView.setText(name);
                addrView.setText(device.getAddress());
                int color = (device.getBondState() == BluetoothDevice.BOND_BONDED) ?
                        context.getResources().getColor(android.R.color.black) :
                        context.getResources().getColor(android.R.color.darker_gray);
                nameView.setTextColor(color);
                addrView.setTextColor(context.getResources().getColor(android.R.color.darker_gray));
                avatar.setText("蓝牙");
            }
            return convertView;
        }
    }
}