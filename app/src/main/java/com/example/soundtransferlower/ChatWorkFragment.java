package com.example.soundtransferlower;

import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.media.MediaPlayer;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.MediaStore;
import androidx.fragment.app.Fragment;
import androidx.core.content.FileProvider;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.appcompat.widget.PopupMenu;
import androidx.recyclerview.widget.RecyclerView;
import android.text.TextUtils;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.PopupWindow;
import android.widget.TextView;
import android.widget.Toast;


import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class ChatWorkFragment extends Fragment implements
        BluetoothService.MessageCallback,
        BluetoothFileTransferService.FileTransferCallback {
    private static final long MAX_MEMORY_FILE_SIZE = 50 * 1024 * 1024; // 50MB，超过则流式复制
    private static final String TAG = "ChatWorkFragment";
    private static final int REQUEST_CODE_PICK_FILE = 1001;
    private static final int REQUEST_STORAGE_PERMISSION = 1002;
    private static final String FILE_REQUEST_PREFIX = "FILE_REQUEST:";
    private static final String FILE_ACCEPT = "FILE_ACCEPT";
    private static final String FILE_REJECT = "FILE_REJECT";
    private static final String IMAGE_MARKER = "[IMAGE]";
    private static final String FILE_MARKER = "[FILE]";
    private static final String VOICE_MARKER = "[VOICE]";
    private static final long MAX_FILE_SIZE = 5000 * 1024 * 1024;
    private boolean isVoicePlaying = false;
    private Message currentPlayingVoice = null;
    private int playingPosition = -1;
    private Handler voiceBlinkHandler = new Handler(Looper.getMainLooper());
    private Runnable voiceBlinkRunnable;
    // UI
    private TextView tvDeviceName;
    private RecyclerView recyclerViewMessages;
    private EditText etMessage;
    private Button btnSend;
    private Button btnMore;
    private ImageButton btnVoice;

    // 蓝牙服务
    private BluetoothService bluetoothService;
    private boolean serviceBound = false;
    private String deviceAddress;
    private String deviceName;
    private boolean historyLoaded = false;

    // 消息
    private MessageAdapter messageAdapter;
    private List<Message> messageList = new ArrayList<>();

    // 删除
    private boolean deleteConfirmation = false;
    private Handler deleteHandler = new Handler(Looper.getMainLooper());
    private Runnable deleteResetRunnable = () -> { deleteConfirmation = false; Toast.makeText(getActivity(), "删除操作已取消", Toast.LENGTH_SHORT).show(); };

    // 文件发送
    private boolean isFileSender = false;
    private boolean isWaitingForAccept = false;
    private String pendingFileName;
    private long pendingFileSize;
    private String localFilePath;
    private String pendingReceiveFileName;
    private long transferStartTime = 0;
    private String pendingTextMessage = null;
    private long lastProgressBytes = 0;
    private long lastProgressTime = 0;

    // ★★★ 语音相关 ★★★
    private VoiceRecorder voiceRecorder;
    private File currentVoiceFile;
    private int currentVoiceDuration = 0;
    private int pendingVoiceDuration = 0;
    private MediaPlayer voicePlayer = null;

    // 文件传输服务
    private BluetoothFileTransferService fileTransferService;
    private boolean fileTransferBound = false;
    private ServiceConnection fileTransferConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            BluetoothFileTransferService.LocalBinder binder = (BluetoothFileTransferService.LocalBinder) service;
            fileTransferService = binder.getService();
            fileTransferService.registerCallback(ChatWorkFragment.this);
            fileTransferBound = true;
        }
        @Override
        public void onServiceDisconnected(ComponentName name) { fileTransferBound = false; }
    };

    private int nonTextDataCount = 0;
    private Handler nonTextHandler = new Handler(Looper.getMainLooper());
    private Runnable resetNonTextDataCount = () -> nonTextDataCount = 0;
    private Set<String> processedMessages = new HashSet<>();

    private ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            BluetoothService.LocalBinder binder = (BluetoothService.LocalBinder) service;
            bluetoothService = binder.getService();
            bluetoothService.registerCallback(ChatWorkFragment.this);
            serviceBound = true;
            bluetoothService.setMode(BluetoothService.MODE_CHAT);
            if (deviceAddress != null) {
                String currentAddress = bluetoothService.getConnectedDeviceAddress();
                if (currentAddress != null && currentAddress.equals(deviceAddress)) {
                    loadChatHistory();
                } else {
                    BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
                    if (adapter != null) {
                        BluetoothDevice device = adapter.getRemoteDevice(deviceAddress);
                        bluetoothService.connect(device);
                    }
                }
            }
        }
        @Override
        public void onServiceDisconnected(ComponentName name) { serviceBound = false; }
    };

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.activity_chat, container, false);
        AppCompatDelegate.setCompatVectorFromResourcesEnabled(true);
        Bundle args = getArguments();
        if (args != null) {
            deviceAddress = args.getString("DEVICE_ADDRESS");
            deviceName = args.getString("DEVICE_NAME");
        }
        initUI(view);
        Intent serviceIntent = new Intent(getActivity(), BluetoothService.class);
        getActivity().bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE);

        // ★★★ 如果服务已经连接，立即加载历史（增强鲁棒性）★★★
        // 由于绑定是异步的，这里不能直接使用 bluetoothService，需在 serviceConnection 中处理
        // 但为了处理从其他界面切回时已连接的情况，我们延迟检查
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            if (serviceBound && bluetoothService != null && bluetoothService.getState() == BluetoothService.STATE_CONNECTED) {
                String addr = bluetoothService.getConnectedDeviceAddress();
                if (addr != null && (deviceAddress == null || deviceAddress.equals(addr))) {
                    if (!historyLoaded) {
                        loadChatHistory();
                        historyLoaded = true;
                    }
                }
            }
        }, 300); // 延迟 300ms 等待绑定完成

        return view;
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        historyLoaded = false;
        pendingTextMessage = null;
        deleteHandler.removeCallbacks(deleteResetRunnable);
        nonTextHandler.removeCallbacks(resetNonTextDataCount);
        stopVoicePlayback();
        voiceBlinkHandler.removeCallbacksAndMessages(null);
        // ★★★ 释放录音机 ★★★
        if (voiceRecorder != null) {
            voiceRecorder.release();
            voiceRecorder = null;
        }

        if (fileTransferBound) {
            fileTransferService.unregisterCallback(this);
            getActivity().unbindService(fileTransferConnection);
            fileTransferBound = false;
        }
        getActivity().stopService(new Intent(getActivity(), BluetoothFileTransferService.class));

        if (serviceBound && getActivity() != null) {
            bluetoothService.unregisterCallback(this);
            getActivity().unbindService(serviceConnection);
            serviceBound = false;
        }
    }
    private void initUI(View view) {
        ImageButton btnBack = view.findViewById(R.id.btnBack);
        tvDeviceName = view.findViewById(R.id.tvDeviceName);
        ImageButton btnMenu = view.findViewById(R.id.btnMenu);
        recyclerViewMessages = view.findViewById(R.id.recyclerViewMessages);
        etMessage = view.findViewById(R.id.etMessage);
        btnSend = view.findViewById(R.id.btnSend);
        btnMore = view.findViewById(R.id.btnMore);
        btnVoice = view.findViewById(R.id.btnVoice);

        if (deviceName != null) tvDeviceName.setText(deviceName);
        else tvDeviceName.setText("未知设备");

        btnBack.setOnClickListener(v -> {
            if (getActivity() != null) getActivity().getSupportFragmentManager().popBackStack();
        });
        btnMenu.setOnClickListener(v -> showMenuOptions());
        btnSend.setOnClickListener(v -> sendMessage());
        btnMore.setOnClickListener(v -> showMoreOptions());

        // ★★★ 语音按钮 ★★★
        btnVoice.setOnTouchListener((v, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    startVoiceRecording();
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    stopVoiceRecordingAndSend();
                    return true;
            }
            return false;
        });

        LinearLayoutManager layoutManager = new LinearLayoutManager(getActivity());
        recyclerViewMessages.setLayoutManager(layoutManager);
        messageAdapter = new MessageAdapter(messageList);
        messageAdapter.setOnMessageLongClickListener((message, position) -> showPopupMenu(message, position));
        messageAdapter.setOnMessageClickListener((message, position) -> {
            int type = message.getType();
            if (type == Message.TYPE_IMAGE || type == Message.TYPE_FILE) {
                openFile(message);
            } else if (type == Message.TYPE_VOICE) {
                playVoice(message);
            }
        });
        messageAdapter.setOnVoiceClickListener((message, position) -> playVoice(message));
        recyclerViewMessages.setAdapter(messageAdapter);
    }

    // ==================== 菜单 ====================
    private void showMenuOptions() {
        PopupMenu popupMenu = new PopupMenu(getActivity(), getView().findViewById(R.id.btnMenu));
        popupMenu.getMenuInflater().inflate(R.menu.chat_menu, popupMenu.getMenu());
        popupMenu.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == R.id.menu_delete_chat) { handleDeleteChat(); return true; }
            else if (item.getItemId() == R.id.menu_export_chat) { exportChatHistory(); return true; }
            return false;
        });
        popupMenu.show();
    }

    private void showMoreOptions() {
        PopupMenu popupMenu = new PopupMenu(getActivity(), getView().findViewById(R.id.btnMore));
        popupMenu.getMenuInflater().inflate(R.menu.chat_more_menu, popupMenu.getMenu());
        popupMenu.getMenu().add(0, android.view.Menu.NONE, 3, "拨号").setOnMenuItemClickListener(item -> {
            if (getActivity() instanceof MainActivityNew) ((MainActivityNew) getActivity()).dialCall();
            return true;
        });
        popupMenu.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == R.id.menu_talkback) { startTalkbackActivity(); return true; }
            else if (item.getItemId() == R.id.menu_send_file) { sendFile(); return true; }
            else if (item.getItemId() == R.id.menu_call) { sendCall(); return true; }
            return false;
        });
        popupMenu.show();
    }

    // ==================== 召唤 ====================
    private void sendCall() {
        if (getActivity() == null) return;

        if (bluetoothService == null || bluetoothService.getState() != BluetoothService.STATE_CONNECTED) {
            Toast.makeText(getActivity(), "未连接，正在重连...", Toast.LENGTH_LONG).show();
            if (deviceAddress != null && bluetoothService != null) {
                BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
                if (adapter != null) {
                    BluetoothDevice device = adapter.getRemoteDevice(deviceAddress);
                    bluetoothService.connect(device);
                    new Handler(Looper.getMainLooper()).postDelayed(() -> {
                        if (getActivity() == null) return;
                        if (bluetoothService != null && bluetoothService.getState() == BluetoothService.STATE_CONNECTED) {
                            doSendCall();
                        } else {
                            Toast.makeText(getActivity(), "重连失败，请稍后重试", Toast.LENGTH_LONG).show();
                        }
                    }, 3000);
                }
            }
            return;
        }
        doSendCall();
    }

    private void doSendCall() {
        if (getActivity() == null) return;

        if (bluetoothService == null || bluetoothService.getState() != BluetoothService.STATE_CONNECTED) {
            Toast.makeText(getActivity(), "未连接，无法召唤", Toast.LENGTH_SHORT).show();
            return;
        }
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        String callerName = (adapter != null) ? adapter.getName() : "我";
        if (callerName == null || callerName.isEmpty()) callerName = "我";
        String callMsg = BluetoothService.TEXT_PREFIX + BluetoothService.CALL_PREFIX + callerName;
        bluetoothService.write(callMsg.getBytes());
        Toast.makeText(getActivity(), "已召唤 " + bluetoothService.getConnectedDeviceName(), Toast.LENGTH_LONG).show();
    }

    // ==================== 消息弹窗 ====================
    private void showPopupMenu(Message message, int position) {
        View anchor = recyclerViewMessages.findViewHolderForAdapterPosition(position) != null ?
                recyclerViewMessages.findViewHolderForAdapterPosition(position).itemView : recyclerViewMessages;
        if (anchor == null) return;
        View popupView = LayoutInflater.from(getActivity()).inflate(R.layout.popup_menu_horizontal, null);
        final PopupWindow popupWindow = new PopupWindow(popupView, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, true);
        popupWindow.setOutsideTouchable(true);
        popupWindow.setFocusable(true);
        Button btnCopy = popupView.findViewById(R.id.btnCopy);
        btnCopy.setOnClickListener(v -> { copyMessageToClipboard(message); popupWindow.dismiss(); });
        Button btnSave = popupView.findViewById(R.id.btnSave);
        btnSave.setOnClickListener(v -> {
            popupWindow.dismiss();
            if (message.getType() == Message.TYPE_IMAGE || message.getType() == Message.TYPE_FILE) saveFileToExternal(message);
            else Toast.makeText(getActivity(), "只能保存文件", Toast.LENGTH_SHORT).show();
        });
        Button btnDelete = popupView.findViewById(R.id.btnDelete);
        btnDelete.setOnClickListener(v -> {
            popupWindow.dismiss();
            new AlertDialog.Builder(getActivity())
                    .setTitle("删除消息")
                    .setMessage("确定要删除这条消息吗？")
                    .setPositiveButton("确定", (dialog, which) -> deleteSingleMessage(message, position))
                    .setNegativeButton("取消", null)
                    .show();
        });
        popupView.measure(View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int popupHeight = popupView.getMeasuredHeight();
        popupWindow.showAsDropDown(anchor, 0, -anchor.getHeight() - popupHeight);
    }

    // ==================== 保存文件 ====================
    private void saveFileToExternal(Message message) {
        String srcPath = message.getFilePath();
        if (srcPath == null) { Toast.makeText(getActivity(), "文件路径无效", Toast.LENGTH_SHORT).show(); return; }
        File srcFile = new File(srcPath);
        if (!srcFile.exists()) { Toast.makeText(getActivity(), "文件不存在", Toast.LENGTH_SHORT).show(); return; }

        // Scoped Storage (API 29+): 使用 MediaStore API
        if (FileHelper.isScopedStorage()) {
            try {
                String fileName = message.getFileName();
                String mimeType = FileHelper.getMimeType(fileName);

                java.io.InputStream fis = new FileInputStream(srcFile);
                Uri savedUri;
                if (message.getType() == Message.TYPE_IMAGE) {
                    savedUri = FileHelper.saveToDCIMViaMediaStore(getActivity(), fileName, fis, mimeType);
                } else {
                    savedUri = FileHelper.saveToDownloadsViaMediaStore(getActivity(), fileName, fis, mimeType);
                }
                fis.close();

                if (savedUri != null) {
                    Toast.makeText(getActivity(), "已保存到: " + savedUri.getPath(), Toast.LENGTH_LONG).show();
                } else {
                    Toast.makeText(getActivity(), "保存失败: 无法创建文件", Toast.LENGTH_SHORT).show();
                }
            } catch (Exception e) {
                Log.e(TAG, "通过 MediaStore 保存文件失败", e);
                Toast.makeText(getActivity(), "保存失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            }
            return;
        }

        // API < 29: 直接写入公共目录
        try {
            File destDir;
            if (message.getType() == Message.TYPE_IMAGE) destDir = FileHelper.getDCIMDir();
            else destDir = FileHelper.getDownloadDir();
            if (!destDir.exists() && !destDir.mkdirs()) { Toast.makeText(getActivity(), "无法创建目录", Toast.LENGTH_SHORT).show(); return; }
            String uniqueName = FileHelper.generateUniqueFileName(destDir, message.getFileName());
            File destFile = new File(destDir, uniqueName);
            FileInputStream fis = null;
            FileOutputStream fos = null;
            try {
                fis = new FileInputStream(srcFile);
                fos = new FileOutputStream(destFile);
                byte[] buffer = new byte[1024];
                int length;
                while ((length = fis.read(buffer)) > 0) fos.write(buffer, 0, length);
                if (message.getType() == Message.TYPE_IMAGE) {
                    MediaScannerConnection.scanFile(getActivity(), new String[]{destFile.getAbsolutePath()}, new String[]{"image/*"}, null);
                }
                Toast.makeText(getActivity(), "已保存到: " + destFile.getAbsolutePath(), Toast.LENGTH_LONG).show();
            } catch (IOException e) {
                Log.e(TAG, "保存文件失败", e);
                Toast.makeText(getActivity(), "保存失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            } finally {
                try { if (fos != null) fos.close(); } catch (IOException ignored) {}
                try { if (fis != null) fis.close(); } catch (IOException ignored) {}
            }
        } catch (Exception e) {
            Log.e(TAG, "保存文件异常", e);
            Toast.makeText(getActivity(), "保存失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    // ==================== 发送文本 ====================
    private void sendMessage() {
        String message = etMessage.getText().toString().trim();
        if (TextUtils.isEmpty(message)) return;
        if (bluetoothService == null || bluetoothService.getState() != BluetoothService.STATE_CONNECTED) {
            pendingTextMessage = message;
            Toast.makeText(getActivity(), "未连接，正在重连...", Toast.LENGTH_LONG).show();
            if (deviceAddress != null && bluetoothService != null) {
                BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
                if (adapter != null) {
                    BluetoothDevice device = adapter.getRemoteDevice(deviceAddress);
                    bluetoothService.connect(device);
                }
            }
            return;
        }
        doSendTextMessage(message);
    }

    private void doSendTextMessage(String message) {
        String prefixed = BluetoothService.TEXT_PREFIX + message;
        bluetoothService.write(prefixed.getBytes());
        etMessage.setText("");
        messageList.add(new Message(message, true, new Date()));
        messageAdapter.notifyItemInserted(messageList.size() - 1);
        recyclerViewMessages.scrollToPosition(messageList.size() - 1);
    }

    // ==================== 发送文件 ====================
    private void sendFile() {
        if (bluetoothService == null || bluetoothService.getState() != BluetoothService.STATE_CONNECTED) {
            Toast.makeText(getActivity(), "未连接，正在重连...", Toast.LENGTH_LONG).show();
            if (deviceAddress != null && bluetoothService != null) {
                BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
                if (adapter != null) {
                    BluetoothDevice device = adapter.getRemoteDevice(deviceAddress);
                    bluetoothService.connect(device);
                }
            }
            return;
        }

        // 检查存储权限
        if (!PermissionHelper.hasPermission(getActivity(), android.Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
            PermissionHelper.requestPermissions(getActivity(),
                new String[]{android.Manifest.permission.WRITE_EXTERNAL_STORAGE},
                REQUEST_STORAGE_PERMISSION);
            return;
        }

        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("*/*");
        startActivityForResult(Intent.createChooser(intent, "选择文件"), REQUEST_CODE_PICK_FILE);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_STORAGE_PERMISSION) {
            if (grantResults.length > 0 && grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                // 权限已授予，继续发送文件
                sendFile();
            } else {
                Toast.makeText(getActivity(), "需要存储权限才能发送文件", Toast.LENGTH_SHORT).show();
            }
        } else if (requestCode == REQUEST_RECORD_AUDIO_PERMISSION) {
            if (grantResults.length > 0 && grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                // 权限已授予，继续录音
                startVoiceRecording();
            } else {
                Toast.makeText(getActivity(), "需要录音权限才能录制语音", Toast.LENGTH_SHORT).show();
            }
        }
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_CODE_PICK_FILE && resultCode == getActivity().RESULT_OK && data != null) {
            Uri uri = data.getData();
            if (uri != null) {
                try {
                    ContentResolver resolver = getActivity().getContentResolver();
                    String fileName = getFileNameFromUri(uri);
                    if (TextUtils.isEmpty(fileName)) fileName = "file_" + System.currentTimeMillis();

                    // ★★★ 获取文件大小 ★★★
                    long fileSize = getFileSizeFromUri(uri);
                    if (fileSize > MAX_FILE_SIZE) {
                        Toast.makeText(getActivity(), "文件过大，请选择小于" + (MAX_FILE_SIZE / 1024 / 1024) + "MB的文件", Toast.LENGTH_SHORT).show();
                        return;
                    }

                    // ★★★ 根据大小选择处理方式 ★★★
                    if (fileSize <= MAX_MEMORY_FILE_SIZE) {
                        // 小文件：一次性读入内存（原有方式）
                        // 优化：使用 try-with-resources 确保资源释放
                        try (InputStream is = resolver.openInputStream(uri)) {
                            byte[] bytes = readBytes(is);
                            localFilePath = saveFileToLocal(bytes, fileName);
                        }
                    } else {
                        // ★★★ 大文件：流式复制，不读入内存 ★★★
                        // 优化：使用 try-with-resources 确保资源释放
                        try (InputStream is = resolver.openInputStream(uri)) {
                            localFilePath = saveFileToLocalFromStream(is, fileName);
                        }
                    }

                    pendingFileName = fileName;
                    pendingFileSize = fileSize;

                    sendFileRequest(fileName, fileSize, 0);

                } catch (Exception e) {
                    Log.e(TAG, "读取文件失败", e);
                    Toast.makeText(getActivity(), "读取文件失败", Toast.LENGTH_SHORT).show();
                }
            }
        }
    }

    private String saveFileToLocal(byte[] data, String fileName) {
        try {
            File dir = new File(getActivity().getExternalFilesDir(null), "files");
            if (!dir.exists()) dir.mkdirs();
            String timeStamp = String.valueOf(System.currentTimeMillis());
            File file = new File(dir, timeStamp + "_" + fileName);
            // 优化：使用 try-with-resources 确保资源释放
            try (FileOutputStream fos = new FileOutputStream(file)) {
                fos.write(data);
            }
            return file.getAbsolutePath();
        } catch (IOException e) {
            Log.e(TAG, "保存本地文件失败", e);
            return null;
        }
    }

    private byte[] readBytes(InputStream inputStream) throws IOException {
        byte[] buffer = new byte[1024];
        int len;
        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        while ((len = inputStream.read(buffer)) != -1) baos.write(buffer, 0, len);
        return baos.toByteArray();
    }

    private String getFileNameFromUri(Uri uri) {
        String fileName = null;
        if ("file".equals(uri.getScheme())) fileName = new File(uri.getPath()).getName();
        else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            try (Cursor cursor = getActivity().getContentResolver().query(uri, null, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    int nameIndex = cursor.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME);
                    if (nameIndex >= 0) fileName = cursor.getString(nameIndex);
                }
            }
        }
        if (TextUtils.isEmpty(fileName)) fileName = "file_" + System.currentTimeMillis();
        return fileName;
    }

    // ★★★ 发送文件请求（支持语音） ★★★
    private void sendFileRequest(String fileName, long size, int duration) {
        String request = BluetoothService.TEXT_PREFIX + FILE_REQUEST_PREFIX + fileName + "," + size;
        if (duration > 0) request += ",VOICE," + duration;
        bluetoothService.write(request.getBytes());
        isWaitingForAccept = true;
        isFileSender = true;
        Toast.makeText(getActivity(), duration > 0 ? "发送语音..." : "已发送文件请求...", Toast.LENGTH_SHORT).show();
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            if (isWaitingForAccept) {
                isWaitingForAccept = false;
                localFilePath = null;
                Toast.makeText(getActivity(), "对方未响应", Toast.LENGTH_SHORT).show();
            }
        }, 30000);
    }

    // 接收方处理文件请求（支持语音）
    private void handleFileRequest(String fileName, long size, int duration) {
        if (getActivity() == null) return;

        // ★★★ 语音消息自动接收 ★★★
        if (duration > 0) {
            pendingReceiveFileName = fileName;
            pendingVoiceDuration = duration;
            String acceptMsg = BluetoothService.TEXT_PREFIX + FILE_ACCEPT;
            bluetoothService.write(acceptMsg.getBytes());
            pauseBluetoothAndStartFileReceive();
            return;
        }

        // ★★★ 普通文件：显示确认对话框 ★★★
        pendingReceiveFileName = fileName;
        AlertDialog.Builder builder = new AlertDialog.Builder(getActivity());
        builder.setTitle("接收文件");
        builder.setMessage("对方发送文件: " + fileName + " (" + (size / 1024) + "KB)\n是否接收？");
        builder.setPositiveButton("接收", (dialog, which) -> {
            String acceptMsg = BluetoothService.TEXT_PREFIX + FILE_ACCEPT;
            bluetoothService.write(acceptMsg.getBytes());
            pauseBluetoothAndStartFileReceive();
        });
        builder.setNegativeButton("拒绝", (dialog, which) -> {
            String rejectMsg = BluetoothService.TEXT_PREFIX + FILE_REJECT;
            bluetoothService.write(rejectMsg.getBytes());
            Toast.makeText(getActivity(), "已拒绝接收文件", Toast.LENGTH_SHORT).show();
            pendingReceiveFileName = null;
        });
        builder.setCancelable(false);
        builder.show();
    }
    // 接收方：启动文件接收服务
    private void pauseBluetoothAndStartFileReceive() {
        if (getActivity() instanceof MainActivityNew) {
            ((MainActivityNew) getActivity()).setFileTransferring(true);
            ((MainActivityNew) getActivity()).setFileTransferStatus("文件接收中...");
        }

        transferStartTime = System.currentTimeMillis();
        lastProgressBytes = 0;
        lastProgressTime = 0;

        isFileSender = false;
        Intent intent = new Intent(getActivity(), BluetoothFileTransferService.class);
        intent.putExtra("ACTION", "RECEIVE");
        intent.putExtra("SAVE_DIR", getActivity().getExternalFilesDir(null) + "/files");
        // ★★★ 关键：传递文件名（用于接收端保存）★★★
        if (pendingReceiveFileName != null) {
            intent.putExtra("FILE_NAME", pendingReceiveFileName);
        }
        getActivity().startService(intent);
        getActivity().bindService(intent, fileTransferConnection, Context.BIND_AUTO_CREATE);
        Toast.makeText(getActivity(), "开始接收文件...", Toast.LENGTH_SHORT).show();
    }

    // 发送方：停止主服务，启动文件发送
    private void startFileSend(String filePath, String fileName) {
        if (getActivity() instanceof MainActivityNew) {
            ((MainActivityNew) getActivity()).setFileTransferring(true);
            ((MainActivityNew) getActivity()).setFileTransferStatus("文件发送中...");
        }
        transferStartTime = System.currentTimeMillis();
        lastProgressBytes = 0;
        lastProgressTime = 0;
        isWaitingForAccept = false;
        if (serviceBound && bluetoothService != null) bluetoothService.stop();
        isFileSender = true;
        if (filePath == null || !new File(filePath).exists()) {
            Toast.makeText(getActivity(), "文件不存在", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent intent = new Intent(getActivity(), BluetoothFileTransferService.class);
        intent.putExtra("ACTION", "SEND");
        intent.putExtra("DEVICE_ADDRESS", deviceAddress);
        intent.putExtra("FILE_PATH", filePath);
        intent.putExtra("FILE_NAME", fileName);
        getActivity().startService(intent);
        getActivity().bindService(intent, fileTransferConnection, Context.BIND_AUTO_CREATE);
        Toast.makeText(getActivity(), "开始发送文件...", Toast.LENGTH_SHORT).show();
    }

    // ==================== 语音录音 ====================
    private static final int REQUEST_RECORD_AUDIO_PERMISSION = 1003;

    private void startVoiceRecording() {
        // 检查录音权限
        if (!PermissionHelper.hasPermission(getActivity(), android.Manifest.permission.RECORD_AUDIO)) {
            PermissionHelper.requestPermissions(getActivity(),
                new String[]{android.Manifest.permission.RECORD_AUDIO},
                REQUEST_RECORD_AUDIO_PERMISSION);
            return;
        }

        if (voiceRecorder == null) {
            voiceRecorder = new VoiceRecorder(new VoiceRecorder.OnVoiceRecordListener() {
                @Override
                public void onRecordStart() {
                    getActivity().runOnUiThread(() -> {
                        etMessage.setVisibility(View.GONE);
                        btnSend.setVisibility(View.GONE);
                        btnMore.setVisibility(View.GONE);
                        btnVoice.setImageResource(android.R.drawable.ic_btn_speak_now);
                    });
                }

                @Override
                public void onRecordProgress(int durationSeconds) {}

                @Override
                public void onRecordFinish(File voiceFile, int durationSeconds) {
                    currentVoiceFile = voiceFile;
                    currentVoiceDuration = durationSeconds;
                    sendVoiceFile(voiceFile, durationSeconds);
                    getActivity().runOnUiThread(() -> {
                        etMessage.setVisibility(View.VISIBLE);
                        btnSend.setVisibility(View.VISIBLE);
                        btnMore.setVisibility(View.VISIBLE);
                        btnVoice.setImageResource(R.drawable.ic_voice);
                    });
                }

                @Override
                public void onRecordError(String error) {
                    Log.e(TAG, "录音错误: " + error);
                    getActivity().runOnUiThread(() -> {
                        Toast.makeText(getActivity(), "录音失败: " + error, Toast.LENGTH_SHORT).show();
                        etMessage.setVisibility(View.VISIBLE);
                        btnSend.setVisibility(View.VISIBLE);
                        btnMore.setVisibility(View.VISIBLE);
                        btnVoice.setImageResource(R.drawable.ic_voice);
                    });
                }
            });
        }
        try {
            File voiceDir = new File(getActivity().getExternalFilesDir(null), "voices");
            if (!voiceDir.exists() && !voiceDir.mkdirs()) {
                Toast.makeText(getActivity(), "无法创建语音目录", Toast.LENGTH_SHORT).show();
                return;
            }
            File file = new File(voiceDir, System.currentTimeMillis() + ".opus");
            voiceRecorder.startRecording(file);
        } catch (Exception e) {
            Log.e(TAG, "启动录音失败", e);
            Toast.makeText(getActivity(), "启动录音失败", Toast.LENGTH_SHORT).show();
            // 恢复 UI
            etMessage.setVisibility(View.VISIBLE);
            btnSend.setVisibility(View.VISIBLE);
            btnMore.setVisibility(View.VISIBLE);
            btnVoice.setImageResource(R.drawable.ic_voice);
        }
    }
    private void stopVoiceRecordingAndSend() {
        if (voiceRecorder != null) voiceRecorder.stopRecording();
    }

    private void sendVoiceFile(File voiceFile, int duration) {
        if (bluetoothService == null || bluetoothService.getState() != BluetoothService.STATE_CONNECTED) {
            Toast.makeText(getActivity(), "未连接，无法发送语音", Toast.LENGTH_SHORT).show();
            return;
        }
        // 优化：使用 try-with-resources 确保资源释放
        try (FileInputStream fis = new FileInputStream(voiceFile)) {
            byte[] data = new byte[(int) voiceFile.length()];
            fis.read(data);
            localFilePath = voiceFile.getAbsolutePath();
            pendingFileName = voiceFile.getName();
            pendingFileSize = data.length;
            // ★★★ 存储语音时长（用于接收端）★★★
            currentVoiceDuration = duration;
            sendFileRequest(voiceFile.getName(), data.length, duration);
        } catch (IOException e) {
            Log.e(TAG, "读取语音文件失败", e);
            Toast.makeText(getActivity(), "发送失败", Toast.LENGTH_SHORT).show();
        }
    }

    // ==================== 播放语音 ====================
    private void playVoice(Message message) {
        if (message.getType() != Message.TYPE_VOICE) return;
        int position = messageList.indexOf(message);
        if (position < 0) return;

        // ★★★ 点击同一语音 -> 停止播放 ★★★
        if (isVoicePlaying && currentPlayingVoice == message) {
            stopVoicePlayback();
            return;
        }

        // 停止当前播放
        stopVoicePlayback();

        String path = message.getFilePath();
        if (path == null || !new File(path).exists()) {
            Toast.makeText(getActivity(), "语音文件不存在", Toast.LENGTH_SHORT).show();
            return;
        }

        // 开始新的播放
        // 优化：使用 try-with-resources 确保资源释放
        try (FileInputStream fis = new FileInputStream(new File(path))) {
            byte[] data = new byte[(int) new File(path).length()];
            fis.read(data);

            if (voiceRecorder == null) {
                voiceRecorder = new VoiceRecorder(null);
            }

            // ★★★ 传入播放监听器 ★★★
            voiceRecorder.playVoice(data, data.length, message.getVoiceDuration(), new VoiceRecorder.OnPlayListener() {
                @Override
                public void onPlayStart() {
                    // 开始闪烁
                    isVoicePlaying = true;
                    currentPlayingVoice = message;
                    playingPosition = position;
                    startVoiceBlink(position);
                }

                @Override
                public void onPlayFinish() {
                    // 停止闪烁
                    stopVoicePlayback();
                }
            });
        } catch (IOException e) {
            Log.e(TAG, "读取语音文件失败", e);
            Toast.makeText(getActivity(), "播放失败", Toast.LENGTH_SHORT).show();
        }
    }
    private void startVoiceBlink(int position) {
        voiceBlinkRunnable = new Runnable() {
            private boolean visible = true;
            @Override
            public void run() {
                if (!isVoicePlaying) {
                    // 停止闪烁，恢复图标
                    updateVoiceIcon(position, true);
                    return;
                }
                visible = !visible;
                updateVoiceIcon(position, visible);
                voiceBlinkHandler.postDelayed(this, 500);
            }
        };
        voiceBlinkHandler.post(voiceBlinkRunnable);
    }

    private void updateVoiceIcon(int position, boolean show) {
        RecyclerView.ViewHolder holder = recyclerViewMessages.findViewHolderForAdapterPosition(position);
        if (holder != null && holder.itemView != null) {
            ImageView iv = holder.itemView.findViewById(R.id.ivVoiceIcon);
            if (iv != null) {
                iv.setAlpha(show ? 1.0f : 0.3f);
            }
        }
    }

    private void stopVoicePlayback() {
        isVoicePlaying = false;
        voiceBlinkHandler.removeCallbacks(voiceBlinkRunnable);
        // 恢复图标
        if (playingPosition >= 0) {
            updateVoiceIcon(playingPosition, true);
            playingPosition = -1;
        }
        currentPlayingVoice = null;
        if (voiceRecorder != null) {
            voiceRecorder.stopPlayback();
        }
    }
    // ==================== 文件传输回调 ====================
    @Override
    public void onProgressUpdate(long totalBytes, long transferredBytes, int progress) {
        long now = System.currentTimeMillis();
        if (lastProgressTime == 0) { lastProgressTime = now; lastProgressBytes = transferredBytes; return; }
        long deltaTime = now - lastProgressTime;
        if (deltaTime < 100) return;
        long deltaBytes = transferredBytes - lastProgressBytes;
        double speed = (deltaBytes * 1000.0) / deltaTime;
        String speedStr;
        if (speed < 1024) speedStr = String.format(Locale.getDefault(), "%.1f B/s", speed);
        else if (speed < 1024 * 1024) speedStr = String.format(Locale.getDefault(), "%.1f KB/s", speed / 1024.0);
        else speedStr = String.format(Locale.getDefault(), "%.1f MB/s", speed / (1024.0 * 1024.0));
        String status = (isFileSender ? "文件发送中" : "文件接收中") + ": " + progress + "% (" + speedStr + ")";
        if (getActivity() instanceof MainActivityNew) ((MainActivityNew) getActivity()).setFileTransferStatus(status);
        lastProgressBytes = transferredBytes;
        lastProgressTime = now;
    }

    @Override
    public void onTransferComplete(boolean success, String filePath) {
        if (getActivity() == null) return;
        getActivity().runOnUiThread(() -> {
            // 解绑文件传输服务
            if (fileTransferBound) {
                fileTransferService.unregisterCallback(this);
                getActivity().unbindService(fileTransferConnection);
                fileTransferBound = false;
            }
            getActivity().stopService(new Intent(getActivity(), BluetoothFileTransferService.class));

            // 更新主Activity状态
            if (getActivity() instanceof MainActivityNew) {
                ((MainActivityNew) getActivity()).setFileTransferring(false);
                ((MainActivityNew) getActivity()).updateStatusDisplay();
            }

            // 重置进度变量
            lastProgressBytes = 0;
            lastProgressTime = 0;

            if (success) {
                if (isFileSender) {
                    // 发送方：判断是否为语音
                    if (pendingFileName.endsWith(".opus") || pendingVoiceDuration > 0) {
                        addVoiceMessage(true, localFilePath, currentVoiceDuration);
                    } else {
                        addFileMessage(true, localFilePath, pendingFileName, pendingFileSize);
                    }
                } else {
                    // 接收方：判断是否为语音
                    File file = new File(filePath);
                    if (pendingVoiceDuration > 0) {
                        // 语音消息
                        addVoiceMessage(false, filePath, pendingVoiceDuration);
                        pendingVoiceDuration = 0; // 重置
                    } else {
                        // 普通文件
                        String displayName = pendingReceiveFileName != null ? pendingReceiveFileName : file.getName();
                        addFileMessage(false, filePath, displayName, file.length());
                        pendingReceiveFileName = null;
                    }
                }

                // 显示速度
                long fileSize = isFileSender ? pendingFileSize : new File(filePath).length();
                long elapsed = System.currentTimeMillis() - transferStartTime;
                if (elapsed > 0) {
                    String speed = formatSpeed(fileSize, elapsed);
                    Toast.makeText(getActivity(), (isFileSender ? "发送" : "接收") + "成功，平均速度: " + speed, Toast.LENGTH_LONG).show();
                } else {
                    Toast.makeText(getActivity(), (isFileSender ? "发送" : "接收") + "成功", Toast.LENGTH_SHORT).show();
                }
            } else {
                Toast.makeText(getActivity(), "文件传输失败", Toast.LENGTH_SHORT).show();
                if (localFilePath != null) {
                    new File(localFilePath).delete();
                    localFilePath = null;
                }
            }

            // 恢复蓝牙服务
            resumeBluetoothService();
        });
    }

    private String formatSpeed(long fileSize, long elapsedMillis) {
        double seconds = elapsedMillis / 1000.0;
        if (seconds < 0.001) seconds = 0.001;
        double speedKB = (fileSize / 1024.0) / seconds;
        if (speedKB < 1024) return String.format(Locale.getDefault(), "%.2f KB/s", speedKB);
        else return String.format(Locale.getDefault(), "%.2f MB/s", speedKB / 1024.0);
    }

    private void addFileMessage(boolean isSent, String filePath, String fileName, long fileSize) {
        Message msg = new Message(fileName, isSent, new Date(), filePath, fileName, fileSize);
        messageList.add(msg);
        messageAdapter.notifyItemInserted(messageList.size() - 1);
        recyclerViewMessages.scrollToPosition(messageList.size() - 1);
        updateChatHistoryFile();
    }

    private void addVoiceMessage(boolean isSent, String filePath, int duration) {
        Message msg = new Message(isSent, new Date(), filePath, duration);
        messageList.add(msg);
        messageAdapter.notifyItemInserted(messageList.size() - 1);
        recyclerViewMessages.scrollToPosition(messageList.size() - 1);
        updateChatHistoryFile();
    }

    private void resumeBluetoothService() {
        if (serviceBound && bluetoothService != null) {
            if (bluetoothService.getState() == BluetoothService.STATE_NONE) bluetoothService.start();
            if (isFileSender) {
                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                    BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
                    if (adapter != null) {
                        BluetoothDevice device = adapter.getRemoteDevice(deviceAddress);
                        bluetoothService.connect(device);
                    }
                }, 500);
            }
        }
        isFileSender = false;
        localFilePath = null;
        if (getActivity() instanceof MainActivityNew) ((MainActivityNew) getActivity()).updateStatusDisplay();
    }

    // ==================== 蓝牙回调 ====================
    @Override
    public void onMessageReceived(String message, String deviceAddress) {
        if (getActivity() == null) return;
        getActivity().runOnUiThread(() -> {
            // ★★★ 如果当前 deviceAddress 未设置，则自动设为第一个发来消息的设备 ★★★
            if (this.deviceAddress == null && deviceAddress != null) {
                this.deviceAddress = deviceAddress;
                // 尝试从 BluetoothService 获取设备名称
                if (bluetoothService != null) {
                    String name = bluetoothService.getConnectedDeviceName();
                    if (name != null && !name.isEmpty()) {
                        this.deviceName = name;
                    }
                }
                // 加载该设备的历史记录
                loadChatHistory();
                // 更新标题
                tvDeviceName.setText(this.deviceName + " (已连接)");
            }

            // ★★★ 接受来自当前 deviceAddress 的消息（或 deviceAddress 为 null 时接受任何消息）★★★
            if (this.deviceAddress != null && !this.deviceAddress.equals(deviceAddress)) {
                Log.d(TAG, "忽略来自其他设备的消息: " + deviceAddress);
                return;
            }

            Log.d(TAG, "收到消息原始内容: [" + message + "]");

            // 1. 呼叫控制消息（最高优先级）
            if (message.startsWith(BluetoothService.CALL_REQUEST)) {
                Log.d(TAG, "检测到呼叫请求消息");
                String callerName = message.substring(BluetoothService.CALL_REQUEST.length());
                if (callerName.isEmpty()) callerName = "未知用户";
                if (getActivity() instanceof MainActivityNew) {
                    ((MainActivityNew) getActivity()).onCallRequest(callerName, deviceAddress);
                }
                return;
            }
            if (message.trim().equals(BluetoothService.CALL_ACCEPT) ||
                    message.trim().equals(BluetoothService.CALL_REJECT) ||
                    message.trim().equals(BluetoothService.CALL_HANGUP)) {
                Log.d(TAG, "检测到呼叫控制消息: " + message);
                if (getActivity() instanceof MainActivityNew) {
                    if (message.trim().equals(BluetoothService.CALL_ACCEPT))
                        ((MainActivityNew) getActivity()).onCallAccepted(deviceAddress);
                    else if (message.trim().equals(BluetoothService.CALL_REJECT))
                        ((MainActivityNew) getActivity()).onCallRejected(deviceAddress);
                    else
                        ((MainActivityNew) getActivity()).onCallHungUp(deviceAddress);
                }
                return;
            }

            // 2. 文件请求（含语音）
            if (message.startsWith(FILE_REQUEST_PREFIX)) {
                Log.d(TAG, "检测到文件请求消息");
                if (processedMessages.contains(message)) {
                    Log.d(TAG, "重复文件请求消息，已忽略");
                    return;
                }
                processedMessages.add(message);
                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                    processedMessages.remove(message);
                }, 5000);

                String[] parts = message.substring(FILE_REQUEST_PREFIX.length()).split(",");
                if (parts.length >= 2) {
                    String fileName = parts[0];
                    long size;
                    try {
                        size = Long.parseLong(parts[1]);
                    } catch (NumberFormatException e) {
                        Log.e(TAG, "解析文件大小失败", e);
                        return;
                    }
                    int duration = 0;
                    if (parts.length >= 4 && "VOICE".equals(parts[2])) {
                        duration = Integer.parseInt(parts[3]);
                    }
                    handleFileRequest(fileName, size, duration);
                }
                return;
            }

            // 3. 召唤消息
            if (message.startsWith(BluetoothService.CALL_PREFIX)) {
                String callerName = message.substring(BluetoothService.CALL_PREFIX.length());
                if (callerName.isEmpty()) callerName = "未知用户";
                Toast.makeText(getActivity(), "📢 " + callerName + " 召唤您！", Toast.LENGTH_LONG).show();
                return;
            }

            // 4. 文件接受/拒绝
            if (message.trim().equals(FILE_ACCEPT)) {
                Log.d(TAG, "收到对方同意文件接收");
                if (isWaitingForAccept && localFilePath != null && new File(localFilePath).exists()) {
                    startFileSend(localFilePath, pendingFileName);
                } else {
                    Log.e(TAG, "文件不存在或等待状态异常");
                    Toast.makeText(getActivity(), "文件已丢失", Toast.LENGTH_SHORT).show();
                }
                return;
            }
            if (message.trim().equals(FILE_REJECT)) {
                Log.d(TAG, "收到对方拒绝文件接收");
                isWaitingForAccept = false;
                localFilePath = null;
                Toast.makeText(getActivity(), "对方拒绝了文件", Toast.LENGTH_SHORT).show();
                return;
            }

            // 5. 普通文本消息（去重）
            boolean exists = false;
            long now = new Date().getTime();
            for (Message msg : messageList) {
                if (!msg.isSent() && msg.getContent().equals(message) &&
                        Math.abs(msg.getTimestamp().getTime() - now) < 2000) {
                    exists = true;
                    break;
                }
            }
            if (!exists) {
                messageList.add(new Message(message, false, new Date()));
                messageAdapter.notifyItemInserted(messageList.size() - 1);
                recyclerViewMessages.scrollToPosition(messageList.size() - 1);
            }
        });
    }
    @Override
    public void onConnectionStatusChanged(int state, String deviceName) {
        if (getActivity() == null) return;
        getActivity().runOnUiThread(() -> {
            final String displayName = (deviceName != null && !deviceName.isEmpty()) ? deviceName : "未知设备";
            switch (state) {
                case BluetoothService.STATE_CONNECTED:
                    tvDeviceName.setText(displayName + " (已连接)");
                    // ★★★ 更新 deviceAddress 和 deviceName ★★★
                    String connectedAddr = bluetoothService != null ? bluetoothService.getConnectedDeviceAddress() : null;
                    if (connectedAddr != null) {
                        if (deviceAddress == null || !deviceAddress.equals(connectedAddr)) {
                            deviceAddress = connectedAddr;
                            ChatWorkFragment.this.deviceName = displayName;
                            historyLoaded = false;
                        }
                    }
                    if (!historyLoaded && deviceAddress != null) {
                        loadChatHistory();
                        historyLoaded = true;
                    }
                    if (pendingTextMessage != null) {
                        String msg = pendingTextMessage;
                        pendingTextMessage = null;
                        doSendTextMessage(msg);
                        Toast.makeText(getActivity(), "重连成功，已发送消息", Toast.LENGTH_SHORT).show();
                    }
                    break;
                case BluetoothService.STATE_CONNECTING:
                    tvDeviceName.setText(displayName + " (连接中...)");
                    break;
                case BluetoothService.STATE_LISTEN:
                    tvDeviceName.setText("等待连接...");
                    break;
            }
        });
    }
    @Override
    public void onNonTextDataReceived(String deviceAddress) {
        if (getActivity() == null) return;
        getActivity().runOnUiThread(() -> {
            if (this.deviceAddress != null && this.deviceAddress.equals(deviceAddress)) {
                // ★★★ 只有在当前 Fragment 为 TalkbackFragment 时才切换，否则忽略 ★★★
                Fragment currentFragment = getActivity().getSupportFragmentManager().findFragmentById(R.id.fragment_container);
                if (currentFragment instanceof TalkbackFragment) {
                    nonTextDataCount++;
                    nonTextHandler.removeCallbacks(resetNonTextDataCount);
                    if (nonTextDataCount >= 2) {
                        switchToTalkbackFragment();
                    } else {
                        nonTextHandler.postDelayed(resetNonTextDataCount, 1000);
                    }
                } else {
                    // 聊天模式下收到非文本数据，忽略（不切换）
                    Log.d(TAG, "聊天模式下忽略非文本数据");
                }
            }
        });
    }
    @Override public void onTalkbackDataReceived(byte[] data, String deviceAddress) {}

    // ==================== 消息操作 ====================
    private void copyMessageToClipboard(Message message) {
        String content;
        if (message.getType() == Message.TYPE_IMAGE || message.getType() == Message.TYPE_FILE) {
            content = "文件: " + message.getFileName() + " (" + formatFileSize(message.getFileSize()) + ")";
        } else if (message.getType() == Message.TYPE_VOICE) {
            content = "语音 (" + message.getVoiceDuration() + "秒)";
        } else {
            content = message.getContent();
        }
        ClipboardManager clipboard = (ClipboardManager) getActivity().getSystemService(Context.CLIPBOARD_SERVICE);
        ClipData clip = ClipData.newPlainText("聊天消息", content);
        clipboard.setPrimaryClip(clip);
        Toast.makeText(getActivity(), "已复制: " + content, Toast.LENGTH_SHORT).show();
    }

    private void openFile(Message message) {
        String filePath = message.getFilePath();
        if (filePath == null) { Toast.makeText(getActivity(), "无法打开文件", Toast.LENGTH_SHORT).show(); return; }
        File file = new File(filePath);
        if (!file.exists()) { Toast.makeText(getActivity(), "文件不存在", Toast.LENGTH_SHORT).show(); return; }
        try {
            Uri uri = FileProvider.getUriForFile(getActivity(), getActivity().getPackageName() + ".fileprovider", file);
            Intent intent = new Intent(Intent.ACTION_VIEW);
            String mime = getMimeType(filePath);
            intent.setDataAndType(uri, mime);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(intent, "打开文件"));
        } catch (Exception e) {
            Toast.makeText(getActivity(), "无法打开文件: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private String getMimeType(String path) {
        String lower = path.toLowerCase();
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".gif")) return "image/gif";
        if (lower.endsWith(".bmp")) return "image/bmp";
        if (lower.endsWith(".pdf")) return "application/pdf";
        if (lower.endsWith(".txt")) return "text/plain";
        if (lower.endsWith(".apk")) return "application/vnd.android.package-archive";
        if (lower.endsWith(".mp3")) return "audio/mpeg";
        if (lower.endsWith(".mp4")) return "video/mp4";
        return "application/octet-stream";
    }

    private String formatFileSize(long size) {
        if (size < 1024) return size + " B";
        if (size < 1024 * 1024) return String.format("%.1f KB", size / 1024.0);
        if (size < 1024 * 1024 * 1024) return String.format("%.1f MB", size / (1024.0 * 1024.0));
        return String.format("%.1f GB", size / (1024.0 * 1024.0 * 1024.0));
    }

    private void deleteSingleMessage(Message message, int position) {
        if (message.getType() == Message.TYPE_IMAGE || message.getType() == Message.TYPE_FILE || message.getType() == Message.TYPE_VOICE) {
            String path = message.getFilePath();
            if (path != null) new File(path).delete();
        }
        messageList.remove(position);
        messageAdapter.notifyItemRemoved(position);
        updateChatHistoryFile();
        Toast.makeText(getActivity(), "已删除", Toast.LENGTH_SHORT).show();
    }

    // ==================== 历史持久化 ====================
    private void updateChatHistoryFile() {
        if (deviceAddress == null || getActivity() == null) return;
        try {
            String filename = "chat_" + deviceAddress.replace(":", "_") + ".txt";
            File file = new File(getActivity().getExternalFilesDir(null), filename);
            if (file.exists()) file.delete();
            if (!messageList.isEmpty()) {
                file.createNewFile();
                // 优化：使用 try-with-resources 确保资源释放
                try (FileOutputStream fos = new FileOutputStream(file, true);
                     OutputStreamWriter osw = new OutputStreamWriter(fos, "UTF-8")) {
                    SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());
                    for (Message msg : messageList) {
                        String sender = msg.isSent() ? "我" : "对方";
                        String timestamp = sdf.format(msg.getTimestamp());
                        if (msg.getType() == Message.TYPE_IMAGE) {
                            osw.write(timestamp + ": " + sender + ": " + IMAGE_MARKER + msg.getFilePath() + "\n");
                        } else if (msg.getType() == Message.TYPE_FILE) {
                            osw.write(timestamp + ": " + sender + ": " + FILE_MARKER + msg.getFilePath() + "|" + msg.getFileName() + "|" + msg.getFileSize() + "\n");
                        } else if (msg.getType() == Message.TYPE_VOICE) {
                            osw.write(timestamp + ": " + sender + ": " + VOICE_MARKER + msg.getFilePath() + "|" + msg.getVoiceDuration() + "\n");
                        } else {
                            osw.write(timestamp + ": " + sender + ": " + msg.getContent() + "\n");
                        }
                    }
                }
            }
        } catch (IOException e) { Log.e(TAG, "更新聊天记录文件失败", e); }
    }

    private void loadChatHistory() {
        if (serviceBound && deviceAddress != null) {
            String history = bluetoothService.loadChatHistory(deviceAddress);
            if (!TextUtils.isEmpty(history)) {
                Set<Message> uniqueMessages = new LinkedHashSet<>();
                String[] lines = history.split("\n");
                for (String line : lines) {
                    if (line.contains(": ")) {
                        try {
                            int timeEndIndex = line.indexOf(": ");
                            String timePart = line.substring(0, timeEndIndex);
                            String messagePart = line.substring(timeEndIndex + 2);
                            boolean isSent = messagePart.startsWith("我: ");
                            String content;
                            if (isSent) content = messagePart.substring(3);
                            else if (messagePart.startsWith("对方: ")) content = messagePart.substring(4);
                            else content = messagePart;
                            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());
                            Date timestamp = sdf.parse(timePart);
                            Message msg = null;
                            if (content.startsWith(IMAGE_MARKER)) {
                                String imagePath = content.substring(IMAGE_MARKER.length());
                                if (new File(imagePath).exists()) msg = new Message("图片", isSent, timestamp, imagePath);
                                else msg = new Message("图片", isSent, timestamp);
                            } else if (content.startsWith(FILE_MARKER)) {
                                String rest = content.substring(FILE_MARKER.length());
                                String[] parts = rest.split("\\|");
                                if (parts.length >= 1) {
                                    String filePath = parts[0];
                                    String fileName = parts.length >= 2 ? parts[1] : new File(filePath).getName();
                                    long fileSize = parts.length >= 3 ? Long.parseLong(parts[2]) : new File(filePath).length();
                                    if (new File(filePath).exists()) msg = new Message(fileName, isSent, timestamp, filePath, fileName, fileSize);
                                    else msg = new Message(fileName, isSent, timestamp);
                                } else msg = new Message(content, isSent, timestamp);
                            } else if (content.startsWith(VOICE_MARKER)) {
                                String rest = content.substring(VOICE_MARKER.length());
                                String[] parts = rest.split("\\|");
                                if (parts.length >= 2) {
                                    String filePath = parts[0];
                                    int duration = Integer.parseInt(parts[1]);
                                    if (new File(filePath).exists()) msg = new Message(isSent, timestamp, filePath, duration);
                                    else msg = new Message("语音", isSent, timestamp);
                                } else msg = new Message("语音", isSent, timestamp);
                            } else {
                                msg = new Message(content, isSent, timestamp);
                            }
                            if (msg != null) uniqueMessages.add(msg);
                        } catch (Exception e) { Log.e(TAG, "解析聊天记录失败: " + line, e); }
                    }
                }
                messageList.clear();
                messageList.addAll(uniqueMessages);
                messageAdapter.notifyDataSetChanged();
                recyclerViewMessages.scrollToPosition(messageList.size() - 1);
            }
        }
    }

    // ==================== 删除全部聊天 ====================
    private void handleDeleteChat() {
        if (deleteConfirmation) {
            deleteHandler.removeCallbacks(deleteResetRunnable);
            deleteChatHistory();
            Toast.makeText(getActivity(), "聊天记录已删除", Toast.LENGTH_SHORT).show();
            deleteConfirmation = false;
        } else {
            Toast.makeText(getActivity(), "再次点击将删除所有聊天记录 (8秒内有效)", Toast.LENGTH_SHORT).show();
            deleteConfirmation = true;
            deleteHandler.postDelayed(deleteResetRunnable, 8000);
        }
    }

    private void deleteChatHistory() {
        if (deviceAddress == null || getActivity() == null) return;
        for (Message msg : messageList) {
            if (msg.getType() == Message.TYPE_IMAGE || msg.getType() == Message.TYPE_FILE || msg.getType() == Message.TYPE_VOICE) {
                String path = msg.getFilePath();
                if (path != null) new File(path).delete();
            }
        }
        messageList.clear();
        messageAdapter.notifyDataSetChanged();
        String filename = "chat_" + deviceAddress.replace(":", "_") + ".txt";
        File historyFile = new File(getActivity().getExternalFilesDir(null), filename);
        if (historyFile.exists()) historyFile.delete();
        deleteConfirmation = false;
        deleteHandler.removeCallbacks(deleteResetRunnable);
        Toast.makeText(getActivity(), "聊天记录及所有文件已彻底删除", Toast.LENGTH_SHORT).show();
    }

    // ==================== 导出 ====================
    private void exportChatHistory() {
        if (messageList.isEmpty()) { Toast.makeText(getActivity(), "没有聊天记录可导出", Toast.LENGTH_SHORT).show(); return; }

        String safeDeviceName = (deviceName != null) ? deviceName.replaceAll("[^a-zA-Z0-9.-]", "_") : "unknown";
        SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault());
        String timestamp = sdf.format(new Date());
        String exportFileName = "chat_" + safeDeviceName + "_" + timestamp + ".txt";

        // 构建导出内容
        StringBuilder exportContent = new StringBuilder();
        exportContent.append("聊天记录导出 - ").append(deviceName != null ? deviceName : "未知设备").append("\n");
        exportContent.append("导出时间: ").append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new Date())).append("\n\n");
        for (Message msg : messageList) {
            String sender = msg.isSent() ? "我方" : "对方";
            String time = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(msg.getTimestamp());
            if (msg.getType() == Message.TYPE_IMAGE) {
                exportContent.append(time).append(" [").append(sender).append("]: [图片] ").append(msg.getFileName()).append("\n");
            } else if (msg.getType() == Message.TYPE_FILE) {
                exportContent.append(time).append(" [").append(sender).append("]: [文件] ").append(msg.getFileName()).append(" (").append(formatFileSize(msg.getFileSize())).append(")\n");
            } else if (msg.getType() == Message.TYPE_VOICE) {
                exportContent.append(time).append(" [").append(sender).append("]: [语音] ").append(msg.getVoiceDuration()).append("秒\n");
            } else {
                exportContent.append(time).append(" [").append(sender).append("]: ").append(msg.getContent()).append("\n");
            }
        }

        // Scoped Storage (API 29+): 使用 SAF (Intent.ACTION_CREATE_DOCUMENT)
        if (FileHelper.isScopedStorage()) {
            pendingExportContent = exportContent.toString();
            pendingExportFileName = exportFileName;
            Intent intent = FileHelper.createSAFIntent(exportFileName);
            startActivityForResult(intent, REQUEST_CODE_EXPORT_CHAT);
            return;
        }

        // API < 29: 直接写入 Downloads 目录
        try {
            File exportDir = FileHelper.getDownloadDir();
            File exportFile = new File(exportDir, exportFileName);
            // 优化：使用 try-with-resources 确保资源释放
            try (FileOutputStream fos = new FileOutputStream(exportFile);
                 OutputStreamWriter osw = new OutputStreamWriter(fos, "UTF-8")) {
                osw.write(exportContent.toString());
            }
            Toast.makeText(getActivity(), "聊天记录已导出到: " + exportFile.getAbsolutePath(), Toast.LENGTH_LONG).show();
        } catch (IOException e) { Log.e(TAG, "导出聊天记录失败", e); Toast.makeText(getActivity(), "导出失败: " + e.getMessage(), Toast.LENGTH_SHORT).show(); }
    }

    private void startTalkbackActivity() {
        Intent intent = new Intent(getActivity(), MainActivityNew.class);
        intent.putExtra("DEVICE_ADDRESS", deviceAddress);
        intent.putExtra("DEVICE_NAME", deviceName);
        intent.putExtra("LOAD_FRAGMENT", "TalkbackFragment");
        startActivity(intent);
    }
    private long getFileSizeFromUri(Uri uri) {
        long size = 0;
        try {
            ContentResolver resolver = getActivity().getContentResolver();
            // 方法1：通过 MediaStore 查询（适用于 content://）
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                try (Cursor cursor = resolver.query(uri, new String[]{MediaStore.MediaColumns.SIZE}, null, null, null)) {
                    if (cursor != null && cursor.moveToFirst()) {
                        int sizeIndex = cursor.getColumnIndex(MediaStore.MediaColumns.SIZE);
                        if (sizeIndex >= 0) {
                            size = cursor.getLong(sizeIndex);
                        }
                    }
                }
            }
            // 方法2：通过 AssetFileDescriptor 获取（适用于 file:// 和部分 content://）
            if (size == 0) {
                try (AssetFileDescriptor fd = resolver.openAssetFileDescriptor(uri, "r")) {
                    if (fd != null) {
                        size = fd.getLength();
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "获取文件大小失败", e);
        }
        return size;
    }
    private String saveFileToLocalFromStream(InputStream inputStream, String fileName) {
        // 优化：使用 try-with-resources 确保资源释放
        try (InputStream is = inputStream) {
            File dir = new File(getActivity().getExternalFilesDir(null), "files");
            if (!dir.exists()) dir.mkdirs();
            String timeStamp = String.valueOf(System.currentTimeMillis());
            File file = new File(dir, timeStamp + "_" + fileName);
            try (FileOutputStream fos = new FileOutputStream(file)) {
                byte[] buffer = new byte[8192]; // 8KB 缓冲区
                int len;
                while ((len = is.read(buffer)) != -1) {
                    fos.write(buffer, 0, len);
                }
            }
            return file.getAbsolutePath();
        } catch (IOException e) {
            Log.e(TAG, "保存本地文件失败（流式）", e);
            return null;
        }
    }
    private void switchToTalkbackFragment() {
        Toast.makeText(getActivity(), "检测到对讲连接，正在切换...", Toast.LENGTH_SHORT).show();
        if (getActivity() instanceof MainActivityNew) {
            ((MainActivityNew) getActivity()).switchToFragment("TalkbackFragment", deviceAddress, deviceName);
        }
    }
    // 在 ChatWorkFragment 中添加
    // ==================== 呼叫回调（转发给主Activity） ====================
    @Override
    public void onCallRequest(String callerName, String deviceAddress) {
        if (getActivity() instanceof MainActivityNew) {
            ((MainActivityNew) getActivity()).onCallRequest(callerName, deviceAddress);
        }
    }

    @Override
    public void onCallAccepted(String deviceAddress) {
        if (getActivity() instanceof MainActivityNew) {
            ((MainActivityNew) getActivity()).onCallAccepted(deviceAddress);
        }
    }

    @Override
    public void onCallRejected(String deviceAddress) {
        if (getActivity() instanceof MainActivityNew) {
            ((MainActivityNew) getActivity()).onCallRejected(deviceAddress);
        }
    }

    @Override
    public void onCallHungUp(String deviceAddress) {
        if (getActivity() instanceof MainActivityNew) {
            ((MainActivityNew) getActivity()).onCallHungUp(deviceAddress);
        }
    }
}