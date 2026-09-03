package com.example.soundtransferlower;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
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
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.support.v4.app.Fragment;
import android.support.v4.app.FragmentActivity;
import android.support.v4.app.FragmentManager;
import android.support.v4.content.ContextCompat;
import android.support.v7.app.AppCompatDelegate;
import android.support.v7.widget.PopupMenu;
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
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class MainActivityNew extends FragmentActivity implements IMessageCallback.MessageCallback {

    private static final String TAG = "MainActivityNew";
    private static final long CALL_TIMEOUT_MS = 10000;
    private static final long RECONNECT_DELAY_MS = 2000;
    private static final int DISCOVERABLE_DURATION = 300; // 5分钟

    // ---------- UI ----------
    private TextView mainStatus;
    private TextView emptyHint;
    private ImageButton btnBack;
    private ImageButton btnMenu;

    // ---------- 蓝牙服务 ----------
    private IBluetoothService bluetoothService;
    private boolean serviceBound = false;
    private BluetoothAdapter bluetoothAdapter;
    private BluetoothFinder bluetoothFinder;

    // ---------- 连接状态 ----------
    private int currentConnectionState = IBluetoothService.STATE_NONE;
    private String connectedDeviceName = "";
    private String connectedDeviceAddress = "";
    private int currentMode = IBluetoothService.MODE_CHAT;

    // ---------- Fragment 管理 ----------
    private boolean isFromNotification = false;
    private String pendingFragmentType;
    private String pendingDeviceAddress;
    private String pendingDeviceName;

    // ---------- 通话管理 ----------
    private final CallManager callManager = new CallManager();

    // ---------- 设备选择 ----------
    private final DeviceSelector deviceSelector = new DeviceSelector();

    // ---------- 文件传输 ----------
    private boolean isFileTransferring = false;

    // ---------- 自动扫描 ----------
    private AutoDeviceScanner autoDeviceScanner;

    // ---------- 可被发现请求 ----------
    private boolean requestDiscoverableEnabled = false;
    private boolean pendingDiscoverableRequest = false;
    private Handler discoverableHandler = new Handler();
    private Runnable discoverableRunnable;

    // ---------- Handler ----------
    private final Handler handler = new Handler();

    // ---------- 广播接收器 ----------
    private boolean isReceiverRegistered = false;
    private final BroadcastReceiver deviceDiscoveryReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (BluetoothDevice.ACTION_FOUND.equals(intent.getAction())) {
                BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                deviceSelector.onDeviceFound(device);
            }
        }
    };

    // ---------- ServiceConnection ----------
    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            BluetoothService.LocalBinder binder = (BluetoothService.LocalBinder) service;
            bluetoothService = binder.getInterface();
            bluetoothService.registerCallback(MainActivityNew.this);
            serviceBound = true;

            if (isFromNotification) {
                handleNotificationIntent();
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
        LogUtil.setEnableLog(prefs.getBoolean("enable_log", false));

        // 初始化自动扫描器
        autoDeviceScanner = new AutoDeviceScanner(this);
        boolean autoScan = prefs.getBoolean("auto_scan_enabled", false);
        int interval = prefs.getInt("auto_scan_interval", 60);
        autoDeviceScanner.setScanInterval(interval);
        autoDeviceScanner.setEnabled(autoScan);

        // 初始化可被发现请求
        initDiscoverableSettings();

        parseIntentExtras();
        initUI();
        initBluetooth();
        bindService();
        setupBottomNavigation();
        loadDefaultFragment();

        getSupportFragmentManager().addOnBackStackChangedListener(this::updateEmptyHintVisibility);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 处理后台待请求可被发现
        if (pendingDiscoverableRequest) {
            pendingDiscoverableRequest = false;
            if (bluetoothAdapter != null && bluetoothAdapter.isEnabled()) {
                requestDiscoverable();
            }
        }
        // 如果设置了开启，但定时器未启动（比如从设置开启后返回），启动它
        if (requestDiscoverableEnabled) {
            startDiscoverableLoop();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        // 进入后台时不停止循环，由定时器自行决策
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (autoDeviceScanner != null) {
            autoDeviceScanner.release();
            autoDeviceScanner = null;
        }
        stopDiscoverableLoop();
        callManager.release();
        unbindServiceIfNeeded();
        stopBluetoothScan();
        safeUnregisterReceiver();
        handler.removeCallbacksAndMessages(null);
        dismissDeviceDialog();
    }

    // ==================== 初始化 ====================

    private void parseIntentExtras() {
        Intent intent = getIntent();
        if (intent != null && intent.hasExtra("LOAD_FRAGMENT")) {
            isFromNotification = true;
            pendingFragmentType = intent.getStringExtra("LOAD_FRAGMENT");
            pendingDeviceAddress = intent.getStringExtra("DEVICE_ADDRESS");
            pendingDeviceName = intent.getStringExtra("DEVICE_NAME");
        }
    }

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

        btnMenu.setOnClickListener(this::showPopupMenu);

        updateStatusDisplay();
        updateEmptyHintVisibility();
    }

    private void initBluetooth() {
        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        if (bluetoothAdapter == null) {
            Toast.makeText(this, "蓝牙不可用", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        bluetoothFinder = new BluetoothFinder(this);
        refreshPairedDevices();
    }

    private void bindService() {
        Intent serviceIntent = new Intent(this, BluetoothService.class);
        startService(serviceIntent);
        bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE);
    }

    private void setupBottomNavigation() {
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
    }

    private void loadDefaultFragment() {
        if (isFromNotification) return;

        handler.postDelayed(() -> {
            if (!isFirstLaunch() || !serviceBound) return;
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
        }, 1000);
    }

    private void handleNotificationIntent() {
        switchToFragment(pendingFragmentType, pendingDeviceAddress, pendingDeviceName);
        if (bluetoothService != null && bluetoothService.getState() == IBluetoothService.STATE_CONNECTED) {
            String name = bluetoothService.getConnectedDeviceName();
            if (name != null && !name.isEmpty()) connectedDeviceName = name;
            else connectedDeviceName = pendingDeviceName;
            updateStatusDisplay();
        }
        clearPendingNotification();
    }

    private void clearPendingNotification() {
        pendingFragmentType = null;
        pendingDeviceAddress = null;
        pendingDeviceName = null;
        isFromNotification = false;
    }

    // ==================== 状态更新 ====================

    public void updateStatusDisplay() {
        runOnUiThread(() -> {
            if (mainStatus == null) return;
            String status;
            if (callManager.isInCall()) {
                status = "通话中: " + callManager.getTargetName();
            } else {
                switch (currentConnectionState) {
                    case IBluetoothService.STATE_NONE: status = "未连接"; break;
                    case IBluetoothService.STATE_LISTEN: status = "等待连接..."; break;
                    case IBluetoothService.STATE_CONNECTING: status = "连接中..."; break;
                    case IBluetoothService.STATE_CONNECTED: status = "已连接: " + connectedDeviceName; break;
                    default: status = "";
                }
            }
            mainStatus.setText(status);
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
            if (bluetoothService != null) bluetoothService.setMode(IBluetoothService.MODE_TALKBACK);
        } else if ("ChatWorkFragment".equals(fragmentType)) {
            fragment = new ChatWorkFragment();
            currentMode = IBluetoothService.MODE_CHAT;
            if (bluetoothService != null) bluetoothService.setMode(IBluetoothService.MODE_CHAT);
        }
        if (fragment == null) return;

        Bundle args = new Bundle();
        args.putString("DEVICE_ADDRESS", deviceAddress);
        args.putString("DEVICE_NAME", deviceName);
        fragment.setArguments(args);

        clearBackStack();
        loadFragment(fragment);

        if (bluetoothService != null && bluetoothService.getState() == IBluetoothService.STATE_CONNECTED) {
            currentConnectionState = IBluetoothService.STATE_CONNECTED;
            String name = bluetoothService.getConnectedDeviceName();
            connectedDeviceName = (name != null && !name.isEmpty()) ? name : deviceName;
        } else {
            currentConnectionState = IBluetoothService.STATE_CONNECTING;
            connectedDeviceName = deviceName;
        }
        updateStatusDisplay();
    }

    // ==================== 蓝牙设备操作 ====================

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

    public void connectToDeviceForChat(BluetoothDevice device) {
        String currentAddress = bluetoothService != null ? bluetoothService.getConnectedDeviceAddress() : null;
        if (currentAddress != null && currentAddress.equals(device.getAddress())) {
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

        Toast.makeText(this,
                isInitiator ? "正在连接 " + device.getName() : "等待 " + device.getName() + " 连接",
                Toast.LENGTH_SHORT).show();

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

    // ==================== 自动扫描管理 ====================

    public void registerScanListener(AutoDeviceScanner.DeviceScanListener listener) {
        if (autoDeviceScanner != null) {
            autoDeviceScanner.addListener(listener);
        }
    }

    public void unregisterScanListener(AutoDeviceScanner.DeviceScanListener listener) {
        if (autoDeviceScanner != null) {
            autoDeviceScanner.removeListener(listener);
        }
    }

    public void updateAutoScannerSettings() {
        SharedPreferences prefs = getSharedPreferences("settings", MODE_PRIVATE);
        boolean enabled = prefs.getBoolean("auto_scan_enabled", false);
        int interval = prefs.getInt("auto_scan_interval", 60);
        if (autoDeviceScanner != null) {
            autoDeviceScanner.setEnabled(enabled);
            autoDeviceScanner.setScanInterval(interval);
        }
    }

    // ==================== 可被发现请求管理 ====================

    private void initDiscoverableSettings() {
        SharedPreferences prefs = getSharedPreferences("settings", MODE_PRIVATE);
        requestDiscoverableEnabled = prefs.getBoolean("request_discoverable", false);
        if (requestDiscoverableEnabled) {
            startDiscoverableLoop();
        }
    }

    public void updateDiscoverableSettings() {
        SharedPreferences prefs = getSharedPreferences("settings", MODE_PRIVATE);
        boolean enabled = prefs.getBoolean("request_discoverable", false);
        if (enabled && !requestDiscoverableEnabled) {
            requestDiscoverableEnabled = true;
            startDiscoverableLoop();
        } else if (!enabled && requestDiscoverableEnabled) {
            requestDiscoverableEnabled = false;
            stopDiscoverableLoop();
        }
    }

    private void startDiscoverableLoop() {
        stopDiscoverableLoop();
        discoverableRunnable = new Runnable() {
            @Override
            public void run() {
                if (!requestDiscoverableEnabled) return;
                if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled()) {
                    // 蓝牙未开启，稍后重试
                    discoverableHandler.postDelayed(this, 30000);
                    return;
                }
                int scanMode = bluetoothAdapter.getScanMode();
                if (scanMode == BluetoothAdapter.SCAN_MODE_CONNECTABLE_DISCOVERABLE) {
                    // 已经可被发现，直接安排下次
                    discoverableHandler.postDelayed(this, DISCOVERABLE_DURATION * 1000L);
                    return;
                }
                // 需要请求可被发现
                if (isAppInForeground()) {
                    requestDiscoverable();
                } else {
                    pendingDiscoverableRequest = true;
                    sendDiscoverableNotification();
                }
                discoverableHandler.postDelayed(this, DISCOVERABLE_DURATION * 1000L);
            }
        };
        discoverableHandler.postDelayed(discoverableRunnable, 5000);
    }

    private void stopDiscoverableLoop() {
        if (discoverableRunnable != null) {
            discoverableHandler.removeCallbacks(discoverableRunnable);
            discoverableRunnable = null;
        }
        pendingDiscoverableRequest = false;
    }

    @SuppressLint("MissingPermission")
    private void requestDiscoverable() {
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled()) return;
        Intent intent = new Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE);
        intent.putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, DISCOVERABLE_DURATION);
        startActivity(intent);
        LogUtil.d(TAG, "请求可被发现，持续 " + DISCOVERABLE_DURATION + " 秒");
    }

    private void sendDiscoverableNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            NotificationChannel channel = new NotificationChannel(
                    "discoverable_channel",
                    "蓝牙可被发现",
                    NotificationManager.IMPORTANCE_DEFAULT
            );
            nm.createNotificationChannel(channel);
        }

        Intent intent = new Intent(this, MainActivityNew.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);

        PendingIntent pendingIntent = PendingIntent.getActivity(
                this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT);

        Notification notification;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            notification = new Notification.Builder(this, "discoverable_channel")
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setContentTitle("蓝牙可被发现请求")
                    .setContentText("点击进入应用以允许本机被其他设备发现")
                    .setContentIntent(pendingIntent)
                    .setAutoCancel(true)
                    .build();
        } else {
            notification = new android.support.v4.app.NotificationCompat.Builder(this)
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setContentTitle("蓝牙可被发现请求")
                    .setContentText("点击进入应用以允许本机被其他设备发现")
                    .setContentIntent(pendingIntent)
                    .setAutoCancel(true)
                    .build();
        }

        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.notify(1003, notification);
        }
        LogUtil.d(TAG, "发送可被发现请求通知");
    }

    private boolean isAppInForeground() {
        android.app.ActivityManager activityManager =
                (android.app.ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        if (activityManager == null) return false;
        List<android.app.ActivityManager.RunningAppProcessInfo> processes =
                activityManager.getRunningAppProcesses();
        if (processes == null) return false;
        for (android.app.ActivityManager.RunningAppProcessInfo process : processes) {
            if (process.processName.equals(getPackageName()) &&
                    process.importance == android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND) {
                return true;
            }
        }
        return false;
    }

    // ==================== 设备选择对话框 ====================

    private class DeviceSelector {
        private AlertDialog dialog;
        private ArrayAdapter<String> adapter;
        private final List<BluetoothDevice> availableDevices = new ArrayList<>();
        private final List<String> deviceNames = new ArrayList<>();
        private boolean discoverableRequested = false;

        public void show() {
            if (!discoverableRequested) {
                Intent intent = new Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE);
                intent.putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300);
                startActivity(intent);
                discoverableRequested = true;
            }

            availableDevices.clear();
            deviceNames.clear();
            adapter = new ArrayAdapter<>(MainActivityNew.this,
                    android.R.layout.simple_list_item_1, deviceNames);

            AlertDialog.Builder builder = new AlertDialog.Builder(MainActivityNew.this);
            builder.setTitle("请选择连接目标设备");
            builder.setAdapter(adapter, (dialog, which) -> {
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
            builder.setOnDismissListener(d -> {
                dialog = null;
                stopScanSafely();
                safeUnregisterReceiver();
                handler.removeCallbacksAndMessages(null);
            });

            dialog = builder.create();
            dialog.show();
            dialog.getButton(DialogInterface.BUTTON_NEGATIVE).setEnabled(false);
            startDeviceScanning();
        }

        public void dismiss() {
            if (dialog != null && dialog.isShowing()) dialog.dismiss();
            dialog = null;
        }

        public void onDeviceFound(BluetoothDevice device) {
            if (device == null || !bluetoothFinder.getPairedDevices().contains(device)) return;
            if (device.getAddress().equals(connectedDeviceAddress)) return;
            if (availableDevices.contains(device)) return;

            availableDevices.add(device);
            String name = device.getName();
            if (name == null || name.isEmpty()) {
                name = "未知设备 (" + device.getAddress() + ")";
            }
            deviceNames.add(name);
            if (adapter != null) {
                runOnUiThread(() -> adapter.notifyDataSetChanged());
            }
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
                            if (dialog != null && dialog.isShowing()) {
                                dialog.getButton(DialogInterface.BUTTON_NEGATIVE).setEnabled(true);
                                dialog.getButton(DialogInterface.BUTTON_NEGATIVE)
                                        .setOnClickListener(v -> dialog.dismiss());
                            }
                            handler.postDelayed(() -> {
                                if (dialog != null && dialog.isShowing()) {
                                    dialog.dismiss();
                                    if (availableDevices.isEmpty()) {
                                        Toast.makeText(MainActivityNew.this,
                                                "没有找到附近已配对的设备", Toast.LENGTH_SHORT).show();
                                    }
                                }
                            }, 3000);
                        }, 3000);
                    }, 100);
                }, 6000);
            }, 500);
        }
    }

    private void showDeviceSelectionDialog() {
        deviceSelector.show();
    }

    private void stopScanSafely() {
        if (bluetoothFinder != null) bluetoothFinder.stopScan();
    }

    private void stopBluetoothScan() {
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

    private void dismissDeviceDialog() {
        deviceSelector.dismiss();
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
        if (item.getItemId() == R.id.menu_refresh) {
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

        final String displayMessage = message.startsWith(IBluetoothService.TEXT_PREFIX) ?
                message.substring(4) : message;
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

            if (state == IBluetoothService.STATE_CONNECTED && !isFileTransferring && !callManager.isInCall()) {
                if (bluetoothService == null) return;
                int mode = bluetoothService.getMode();
                String address = bluetoothService.getConnectedDeviceAddress();
                String name = bluetoothService.getConnectedDeviceName();
                if (address == null || name == null) return;
                Fragment current = getSupportFragmentManager().findFragmentById(R.id.fragment_container);
                boolean isChat = current instanceof ChatWorkFragment;
                boolean isTalkback = current instanceof TalkbackFragment;

                if (mode == IBluetoothService.MODE_CHAT && !isChat) {
                    switchToFragment("ChatWorkFragment", address, name);
                } else if (mode == IBluetoothService.MODE_TALKBACK && !isTalkback) {
                    switchToFragment("TalkbackFragment", address, name);
                }
            }
        });
    }

    @Override
    public void onTalkbackDataReceived(byte[] data, String deviceAddress) {
        if (callManager.isInCall()) {
            if (callManager.getAudioRecorder() != null && data != null && data.length > 10) {
                callManager.getAudioRecorder().playAudio(data, data.length);
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
            if (callManager.isInCall()) {
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
            }, CALL_TIMEOUT_MS);
        });
    }

    @Override
    public void onCallAccepted(String deviceAddress) {
        runOnUiThread(() -> {
            if (!callManager.isInCall()) {
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

    private class CallManager {
        private boolean inCall = false;
        private String targetAddress;
        private String targetName;
        private long startTime;
        private Fragment callFragment;
        private AudioRecorderPlayer audioRecorder;
        private final Handler timerHandler = new Handler();
        private Runnable timerRunnable;

        public boolean isInCall() { return inCall; }
        public String getTargetName() { return targetName; }
        public AudioRecorderPlayer getAudioRecorder() { return audioRecorder; }

        public void startCall(String address, String name) {
            if (inCall) return;
            inCall = true;
            targetAddress = address;
            targetName = name;
            startTime = System.currentTimeMillis();

            if (bluetoothService != null) {
                bluetoothService.setMode(IBluetoothService.MODE_TALKBACK);
                onConnectionStatusChanged(bluetoothService.getState(), bluetoothService.getConnectedDeviceName());
            }

            if (audioRecorder == null) {
                audioRecorder = new AudioRecorderPlayer(MainActivityNew.this);
                audioRecorder.setAudioDataSender(data -> {
                    if (inCall && bluetoothService != null &&
                            bluetoothService.getState() == IBluetoothService.STATE_CONNECTED) {
                        bluetoothService.write(data, IBluetoothService.MODE_TALKBACK);
                    }
                });
            }
            audioRecorder.startRecording();

            CallFragment fragment = new CallFragment();
            Bundle args = new Bundle();
            args.putString("TARGET_NAME", name);
            args.putString("TARGET_ADDRESS", address);
            fragment.setArguments(args);
            callFragment = fragment;

            clearBackStack();
            getSupportFragmentManager().beginTransaction()
                    .replace(R.id.fragment_container, fragment)
                    .addToBackStack(null)
                    .commitAllowingStateLoss();

            updateStatusDisplay();

            timerRunnable = () -> {
                if (inCall) {
                    updateCallDuration();
                    timerHandler.postDelayed(timerRunnable, 1000);
                }
            };
            timerHandler.post(timerRunnable);
        }

        private void updateCallDuration() {
            Fragment current = getSupportFragmentManager().findFragmentById(R.id.fragment_container);
            if (current instanceof CallFragment) {
                long elapsed = System.currentTimeMillis() - startTime;
                ((CallFragment) current).updateDuration(elapsed);
            }
        }

        public void endCall() {
            if (!inCall) return;
            inCall = false;
            timerHandler.removeCallbacks(timerRunnable);

            if (audioRecorder != null) {
                audioRecorder.stopRecording();
                audioRecorder.release();
                audioRecorder = null;
            }

            if (bluetoothService != null && bluetoothService.getState() == IBluetoothService.STATE_CONNECTED) {
                bluetoothService.write((IBluetoothService.TEXT_PREFIX + IBluetoothService.CALL_HANGUP).getBytes());
            }

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
            targetAddress = null;
            targetName = null;
        }

        public void release() {
            timerHandler.removeCallbacks(timerRunnable);
            if (audioRecorder != null) {
                audioRecorder.stopRecording();
                audioRecorder.release();
                audioRecorder = null;
            }
        }
    }

    public void startCall(String targetAddress, String targetName) {
        callManager.startCall(targetAddress, targetName);
    }

    public void endCall() {
        callManager.endCall();
    }

    public void dialCall() {
        if (callManager.isInCall()) {
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
        builder.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, deviceNames),
                (dialog, which) -> {
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
                }, RECONNECT_DELAY_MS);
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

    // ==================== 工具方法 ====================

    public void setFileTransferring(boolean transferring) {
        this.isFileTransferring = transferring;
    }

    public void setFileTransferStatus(String status) {
        runOnUiThread(() -> {
            if (mainStatus != null) mainStatus.setText(status);
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

    private void unbindServiceIfNeeded() {
        if (serviceBound) {
            if (bluetoothService != null) {
                bluetoothService.unregisterCallback(this);
            }
            unbindService(serviceConnection);
            serviceBound = false;
        }
    }

    // ==================== 内部 Fragments ====================

    public static class ChatFragment extends Fragment implements AutoDeviceScanner.DeviceScanListener {
        private DeviceListAdapter adapter;
        private MainActivityNew mainActivity;
        private Set<String> scannedAddresses = new HashSet<>();

        @Override
        public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
            View view = inflater.inflate(R.layout.fragment_chat, container, false);
            ListView listView = view.findViewById(R.id.deviceListView);
            mainActivity = (MainActivityNew) getActivity();
            if (mainActivity != null) {
                adapter = new DeviceListAdapter(getActivity(), R.layout.item_main,
                        mainActivity.getPairedDevices(), scannedAddresses);
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
                mainActivity.registerScanListener(this);
            }
            return view;
        }

        @Override
        public void onDestroyView() {
            super.onDestroyView();
            if (mainActivity != null) {
                mainActivity.unregisterScanListener(this);
            }
        }

        @Override
        public void onResume() {
            super.onResume();
            if (mainActivity != null) {
                mainActivity.registerScanListener(this);
            }
        }

        @Override
        public void onPause() {
            super.onPause();
            if (mainActivity != null) {
                mainActivity.unregisterScanListener(this);
            }
        }

        @Override
        public void onDeviceDetected(BluetoothDevice device) {
            if (device == null || getActivity() == null) return;
            if (mainActivity != null && mainActivity.getPairedDevices().contains(device)) {
                scannedAddresses.add(device.getAddress());
                if (adapter != null) {
                    adapter.notifyDataSetChanged();
                }
            }
        }

        public void refreshDeviceList() {
            if (adapter != null && mainActivity != null) {
                mainActivity.refreshPairedDevices();
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

        private static class DeviceListAdapter extends ArrayAdapter<BluetoothDevice> {
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
                    TextView nameView = convertView.findViewById(R.id.deviceName);
                    TextView addrView = convertView.findViewById(R.id.deviceAddress);
                    TextView avatar = convertView.findViewById(R.id.avatar);
                    String name = device.getName();
                    if (name == null || name.isEmpty()) name = "未知设备";
                    nameView.setText(name);
                    addrView.setText(device.getAddress());
                    int color = (scannedAddresses != null && scannedAddresses.contains(device.getAddress())) ?
                            context.getResources().getColor(android.R.color.holo_blue_light) :
                            context.getResources().getColor(android.R.color.black);
                    nameView.setTextColor(color);
                    addrView.setTextColor(context.getResources().getColor(android.R.color.darker_gray));
                    avatar.setText("蓝牙");
                }
                return convertView;
            }
        }
    }

    public static class MineFragment extends Fragment {
        @Override
        public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
            View view = inflater.inflate(R.layout.fragment_mine, container, false);
            Button btnName = view.findViewById(R.id.btnName);
            Button btnAbout = view.findViewById(R.id.btnAbout);
            Button btnSettings = view.findViewById(R.id.btnSettings);

            btnName.setOnClickListener(v -> showNameDialog());
            btnAbout.setOnClickListener(v -> showAboutDialog());

            btnSettings.setOnClickListener(v -> {
                SettingFragment fragment = new SettingFragment();
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
            String currentName = (main != null && main.bluetoothAdapter != null) ?
                    main.bluetoothAdapter.getName() : "";

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

    // 为了让内部类能访问外部类的 private 字段，保留此列表
    private final List<BluetoothDevice> pairedDevices = new ArrayList<>();
    //==========确认=============
    @Override
    public void onMessageConfirmed(long timestamp) {
        runOnUiThread(() -> {
            Toast.makeText(this, "对方已收到消息", Toast.LENGTH_SHORT).show();
        });
    }
}