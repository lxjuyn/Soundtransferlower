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
import android.support.v4.app.Fragment;
import android.support.v4.content.FileProvider;
import android.support.v7.app.AppCompatDelegate;
import android.support.v7.widget.LinearLayoutManager;
import android.support.v7.widget.PopupMenu;
import android.support.v7.widget.RecyclerView;
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

// ★★★ 导入接口 ★★★


public class ChatWorkFragment extends Fragment implements
        IMessageCallback.MessageCallback,      // 该接口仍定义在 BluetoothService 中，但此处仅依赖接口类型
        BluetoothFileTransferService.FileTransferCallback {  // 同理

    private static final long MAX_MEMORY_FILE_SIZE = 50 * 1024 * 1024; // 50MB
    private static final String TAG = "ChatWorkFragment";
    private static final int REQUEST_CODE_PICK_FILE = 1001;
    private static final String IMAGE_MARKER = "[IMAGE]";
    private static final String FILE_MARKER = "[FILE]";
    private static final String VOICE_MARKER = "[VOICE]";
    private static final long MAX_FILE_SIZE = 5000 * 1024 * 1024;

    // UI
    private TextView tvDeviceName;
    private RecyclerView recyclerViewMessages;
    private EditText etMessage;
    private Button btnSend;
    private Button btnMore;
    private ImageButton btnVoice;

    // ★★★ 改为接口类型 ★★★
    private IBluetoothService bluetoothService;
    private IFileTransferService fileTransferService;
    private boolean serviceBound = false;
    private boolean fileTransferBound = false;

    private String deviceAddress;
    private String deviceName;
    private boolean historyLoaded = false;

    // 消息列表
    private MessageAdapter messageAdapter;
    private List<Message> messageList = new ArrayList<>();

    // 删除状态
    private boolean deleteConfirmation = false;
    private Handler deleteHandler = new Handler();
    private Runnable deleteResetRunnable = () -> {
        deleteConfirmation = false;
        Toast.makeText(getActivity(), "删除操作已取消", Toast.LENGTH_SHORT).show();
    };

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

    // 语音
    private VoiceRecorder voiceRecorder;
    private File currentVoiceFile;
    private int currentVoiceDuration = 0;
    private int pendingVoiceDuration = 0;
    private MediaPlayer voicePlayer = null;
    private boolean isVoicePlaying = false;
    private Message currentPlayingVoice = null;
    private int playingPosition = -1;
    private Handler voiceBlinkHandler = new Handler();
    private Runnable voiceBlinkRunnable;

    // 非文本数据计数
    private int nonTextDataCount = 0;
    private Handler nonTextHandler = new Handler(Looper.getMainLooper());
    private Runnable resetNonTextDataCount = () -> nonTextDataCount = 0;
    private Set<String> processedMessages = new HashSet<>();

    // ==================== ServiceConnection（改为获取接口） ====================
    private ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            BluetoothService.LocalBinder binder = (BluetoothService.LocalBinder) service;
            bluetoothService = binder.getInterface();
            bluetoothService.registerCallback(ChatWorkFragment.this);
            serviceBound = true;
            bluetoothService.setMode(IBluetoothService.MODE_CHAT);

            if (deviceAddress != null) {
                int state = bluetoothService.getState();
                if (state == IBluetoothService.STATE_CONNECTED) {
                    // 已连接，直接加载历史
                    loadChatHistory();
                } else if (state == IBluetoothService.STATE_CONNECTING) {
                    // 正在连接中，等待回调，不做额外操作
                    LogUtil.d(TAG, "Already connecting, wait for callback");
                } else {
                    // 未连接，发起连接
                    BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
                    if (adapter != null) {
                        BluetoothDevice device = adapter.getRemoteDevice(deviceAddress);
                        bluetoothService.connect(device);
                    }
                }
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            serviceBound = false;
        }
    };

    private ServiceConnection fileTransferConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            BluetoothFileTransferService.LocalBinder binder = (BluetoothFileTransferService.LocalBinder) service;
            // ★★★ 获取接口实例 ★★★
            fileTransferService = binder.getInterface();
            fileTransferService.registerCallback(ChatWorkFragment.this);
            fileTransferBound = true;
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            fileTransferBound = false;
        }
    };

    // ==================== Fragment生命周期 ====================
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

        // 绑定蓝牙服务
        Intent serviceIntent = new Intent(getActivity(), BluetoothService.class);
        getActivity().bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE);

        // 延迟加载历史（处理已连接情况）
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            if (serviceBound && bluetoothService != null && bluetoothService.getState() == IBluetoothService.STATE_CONNECTED) {
                String addr = bluetoothService.getConnectedDeviceAddress();
                if (addr != null && (deviceAddress == null || deviceAddress.equals(addr))) {
                    if (!historyLoaded) {
                        loadChatHistory();
                        historyLoaded = true;
                    }
                }
            }
        }, 300);

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

    // ==================== UI初始化 ====================
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

        // 语音按钮长按录音
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
            if (item.getItemId() == R.id.menu_delete_chat) {
                handleDeleteChat();
                return true;
            } else if (item.getItemId() == R.id.menu_export_chat) {
                exportChatHistory();
                return true;
            }
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
            if (item.getItemId() == R.id.menu_talkback) {
                startTalkbackActivity();
                return true;
            } else if (item.getItemId() == R.id.menu_send_file) {
                sendFile();
                return true;
            } else if (item.getItemId() == R.id.menu_call) {
                sendCall();
                return true;
            }
            return false;
        });
        popupMenu.show();
    }

    // ==================== 召唤 ====================
    private void sendCall() {
        if (getActivity() == null) return;

        if (bluetoothService == null || bluetoothService.getState() != IBluetoothService.STATE_CONNECTED) {
            Toast.makeText(getActivity(), "未连接，正在重连...", Toast.LENGTH_LONG).show();
            if (deviceAddress != null && bluetoothService != null) {
                BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
                if (adapter != null) {
                    BluetoothDevice device = adapter.getRemoteDevice(deviceAddress);
                    bluetoothService.connect(device);
                    new Handler(Looper.getMainLooper()).postDelayed(() -> {
                        if (getActivity() == null) return;
                        if (bluetoothService != null && bluetoothService.getState() == IBluetoothService.STATE_CONNECTED) {
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
        if (bluetoothService == null || bluetoothService.getState() != IBluetoothService.STATE_CONNECTED) {
            Toast.makeText(getActivity(), "未连接，无法召唤", Toast.LENGTH_SHORT).show();
            return;
        }
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        String callerName = (adapter != null) ? adapter.getName() : "我";
        if (callerName == null || callerName.isEmpty()) callerName = "我";
        // ★★★ 使用接口常量 ★★★
        String callMsg = IBluetoothService.TEXT_PREFIX + IBluetoothService.CALL_PREFIX + callerName;
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
            if (message.getType() == Message.TYPE_IMAGE || message.getType() == Message.TYPE_FILE)
                saveFileToExternal(message);
            else
                Toast.makeText(getActivity(), "只能保存文件", Toast.LENGTH_SHORT).show();
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

    // ==================== 保存文件到外部 ====================
    private void saveFileToExternal(Message message) {
        String srcPath = message.getFilePath();
        if (srcPath == null) { Toast.makeText(getActivity(), "文件路径无效", Toast.LENGTH_SHORT).show(); return; }
        File srcFile = new File(srcPath);
        if (!srcFile.exists()) { Toast.makeText(getActivity(), "文件不存在", Toast.LENGTH_SHORT).show(); return; }
        try {
            File destDir;
            if (message.getType() == Message.TYPE_IMAGE)
                destDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM);
            else
                destDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            if (!destDir.exists() && !destDir.mkdirs()) {
                Toast.makeText(getActivity(), "无法创建目录", Toast.LENGTH_SHORT).show();
                return;
            }
            String originalName = message.getFileName();
            File destFile = new File(destDir, originalName);
            int count = 1;
            while (destFile.exists()) {
                String name = originalName;
                int dotIndex = originalName.lastIndexOf('.');
                if (dotIndex > 0)
                    name = originalName.substring(0, dotIndex) + "_" + count + originalName.substring(dotIndex);
                else
                    name = originalName + "_" + count;
                destFile = new File(destDir, name);
                count++;
            }
            FileInputStream fis = new FileInputStream(srcFile);
            FileOutputStream fos = new FileOutputStream(destFile);
            byte[] buffer = new byte[1024];
            int length;
            while ((length = fis.read(buffer)) > 0) fos.write(buffer, 0, length);
            fos.close();
            fis.close();
            if (message.getType() == Message.TYPE_IMAGE) {
                MediaScannerConnection.scanFile(getActivity(), new String[]{destFile.getAbsolutePath()},
                        new String[]{"image/*"}, null);
            }
            Toast.makeText(getActivity(), "已保存到: " + destFile.getAbsolutePath(), Toast.LENGTH_LONG).show();
        } catch (IOException e) {
            LogUtil.e(TAG, "保存文件失败", e);
            Toast.makeText(getActivity(), "保存失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    // ==================== 发送文本 ====================
    private void sendMessage() {
        String message = etMessage.getText().toString().trim();
        if (TextUtils.isEmpty(message)) return;
        if (bluetoothService == null || bluetoothService.getState() != IBluetoothService.STATE_CONNECTED) {
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
        // ★★★ 使用接口常量 ★★★
        String prefixed = IBluetoothService.TEXT_PREFIX + message;
        bluetoothService.write(prefixed.getBytes());
        etMessage.setText("");
        messageList.add(new Message(message, true, new Date()));
        messageAdapter.notifyItemInserted(messageList.size() - 1);
        recyclerViewMessages.scrollToPosition(messageList.size() - 1);
    }

    // ==================== 发送文件 ====================
    private void sendFile() {
        if (bluetoothService == null || bluetoothService.getState() != IBluetoothService.STATE_CONNECTED) {
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
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("*/*");
        startActivityForResult(Intent.createChooser(intent, "选择文件"), REQUEST_CODE_PICK_FILE);
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

                    long fileSize = getFileSizeFromUri(uri);
                    if (fileSize > MAX_FILE_SIZE) {
                        Toast.makeText(getActivity(), "文件过大，请选择小于" + (MAX_FILE_SIZE / 1024 / 1024) + "MB的文件", Toast.LENGTH_SHORT).show();
                        return;
                    }

                    if (fileSize <= MAX_MEMORY_FILE_SIZE) {
                        InputStream is = resolver.openInputStream(uri);
                        byte[] bytes = readBytes(is);
                        is.close();
                        localFilePath = saveFileToLocal(bytes, fileName);
                    } else {
                        InputStream is = resolver.openInputStream(uri);
                        localFilePath = saveFileToLocalFromStream(is, fileName);
                        is.close();
                    }

                    pendingFileName = fileName;
                    pendingFileSize = fileSize;
                    sendFileRequest(fileName, fileSize, 0);

                } catch (Exception e) {
                    LogUtil.e(TAG, "读取文件失败", e);
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
            FileOutputStream fos = new FileOutputStream(file);
            fos.write(data);
            fos.close();
            return file.getAbsolutePath();
        } catch (IOException e) {
            LogUtil.e(TAG, "保存本地文件失败", e);
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

    // ★★★ 发送文件请求（使用接口常量） ★★★
    private void sendFileRequest(String fileName, long size, int duration) {
        String request = IBluetoothService.TEXT_PREFIX + IBluetoothService.FILE_REQUEST_PREFIX + fileName + "," + size;
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

    // 接收方处理文件请求
    private void handleFileRequest(String fileName, long size, int duration) {
        if (getActivity() == null) return;

        if (duration > 0) {
            pendingReceiveFileName = fileName;
            pendingVoiceDuration = duration;
            String acceptMsg = IBluetoothService.TEXT_PREFIX + IBluetoothService.FILE_ACCEPT;
            bluetoothService.write(acceptMsg.getBytes());
            pauseBluetoothAndStartFileReceive();
            return;
        }

        pendingReceiveFileName = fileName;
        AlertDialog.Builder builder = new AlertDialog.Builder(getActivity());
        builder.setTitle("接收文件");
        builder.setMessage("对方发送文件: " + fileName + " (" + (size / 1024) + "KB)\n是否接收？");
        builder.setPositiveButton("接收", (dialog, which) -> {
            String acceptMsg = IBluetoothService.TEXT_PREFIX + IBluetoothService.FILE_ACCEPT;
            bluetoothService.write(acceptMsg.getBytes());
            pauseBluetoothAndStartFileReceive();
        });
        builder.setNegativeButton("拒绝", (dialog, which) -> {
            String rejectMsg = IBluetoothService.TEXT_PREFIX + IBluetoothService.FILE_REJECT;
            bluetoothService.write(rejectMsg.getBytes());
            Toast.makeText(getActivity(), "已拒绝接收文件", Toast.LENGTH_SHORT).show();
            pendingReceiveFileName = null;
        });
        builder.setCancelable(false);
        builder.show();
    }

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
        if (pendingReceiveFileName != null) {
            intent.putExtra("FILE_NAME", pendingReceiveFileName);
        }
        getActivity().startService(intent);
        getActivity().bindService(intent, fileTransferConnection, Context.BIND_AUTO_CREATE);
        Toast.makeText(getActivity(), "开始接收文件...", Toast.LENGTH_SHORT).show();
    }

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
    private void startVoiceRecording() {
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
                    LogUtil.e(TAG, "录音错误: " + error);
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
            LogUtil.e(TAG, "启动录音失败", e);
            Toast.makeText(getActivity(), "启动录音失败", Toast.LENGTH_SHORT).show();
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
        if (bluetoothService == null || bluetoothService.getState() != IBluetoothService.STATE_CONNECTED) {
            Toast.makeText(getActivity(), "未连接，无法发送语音", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            byte[] data = new byte[(int) voiceFile.length()];
            FileInputStream fis = new FileInputStream(voiceFile);
            fis.read(data);
            fis.close();
            localFilePath = voiceFile.getAbsolutePath();
            pendingFileName = voiceFile.getName();
            pendingFileSize = data.length;
            currentVoiceDuration = duration;
            sendFileRequest(voiceFile.getName(), data.length, duration);
        } catch (IOException e) {
            LogUtil.e(TAG, "读取语音文件失败", e);
            Toast.makeText(getActivity(), "发送失败", Toast.LENGTH_SHORT).show();
        }
    }

    // ==================== 播放语音 ====================
    private void playVoice(Message message) {
        if (message.getType() != Message.TYPE_VOICE) return;
        int position = messageList.indexOf(message);
        if (position < 0) return;

        if (isVoicePlaying && currentPlayingVoice == message) {
            stopVoicePlayback();
            return;
        }

        stopVoicePlayback();

        String path = message.getFilePath();
        if (path == null || !new File(path).exists()) {
            Toast.makeText(getActivity(), "语音文件不存在", Toast.LENGTH_SHORT).show();
            return;
        }

        try {
            byte[] data = new byte[(int) new File(path).length()];
            FileInputStream fis = new FileInputStream(new File(path));
            fis.read(data);
            fis.close();

            if (voiceRecorder == null) {
                voiceRecorder = new VoiceRecorder(null);
            }

            voiceRecorder.playVoice(data, data.length, message.getVoiceDuration(), new VoiceRecorder.OnPlayListener() {
                @Override
                public void onPlayStart() {
                    isVoicePlaying = true;
                    currentPlayingVoice = message;
                    playingPosition = position;
                    startVoiceBlink(position);
                }

                @Override
                public void onPlayFinish() {
                    stopVoicePlayback();
                }
            });
        } catch (IOException e) {
            LogUtil.e(TAG, "读取语音文件失败", e);
            Toast.makeText(getActivity(), "播放失败", Toast.LENGTH_SHORT).show();
        }
    }

    private void startVoiceBlink(int position) {
        voiceBlinkRunnable = new Runnable() {
            private boolean visible = true;
            @Override
            public void run() {
                if (!isVoicePlaying) {
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
            if (fileTransferBound) {
                fileTransferService.unregisterCallback(this);
                getActivity().unbindService(fileTransferConnection);
                fileTransferBound = false;
            }
            getActivity().stopService(new Intent(getActivity(), BluetoothFileTransferService.class));

            if (getActivity() instanceof MainActivityNew) {
                ((MainActivityNew) getActivity()).setFileTransferring(false);
                ((MainActivityNew) getActivity()).updateStatusDisplay();
            }

            lastProgressBytes = 0;
            lastProgressTime = 0;

            if (success) {
                if (isFileSender) {
                    if (pendingFileName.endsWith(".opus") || pendingVoiceDuration > 0) {
                        addVoiceMessage(true, localFilePath, currentVoiceDuration);
                    } else {
                        addFileMessage(true, localFilePath, pendingFileName, pendingFileSize);
                    }
                } else {
                    File file = new File(filePath);
                    if (pendingVoiceDuration > 0) {
                        addVoiceMessage(false, filePath, pendingVoiceDuration);
                        pendingVoiceDuration = 0;
                    } else {
                        String displayName = pendingReceiveFileName != null ? pendingReceiveFileName : file.getName();
                        addFileMessage(false, filePath, displayName, file.length());
                        pendingReceiveFileName = null;
                    }
                }

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
            if (bluetoothService.getState() == IBluetoothService.STATE_NONE) bluetoothService.start();
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
            if (this.deviceAddress == null && deviceAddress != null) {
                this.deviceAddress = deviceAddress;
                if (bluetoothService != null) {
                    String name = bluetoothService.getConnectedDeviceName();
                    if (name != null && !name.isEmpty()) {
                        this.deviceName = name;
                    }
                }
                loadChatHistory();
                tvDeviceName.setText(this.deviceName + " (已连接)");
            }

            if (this.deviceAddress != null && !this.deviceAddress.equals(deviceAddress)) {
                LogUtil.d(TAG, "忽略来自其他设备的消息: " + deviceAddress);
                return;
            }

            LogUtil.d(TAG, "收到消息原始内容: [" + message + "]");

            // ★★★ 使用接口常量判断 ★★★
            if (message.startsWith(IBluetoothService.CALL_REQUEST)) {
                LogUtil.d(TAG, "检测到呼叫请求消息");
                String callerName = message.substring(IBluetoothService.CALL_REQUEST.length());
                if (callerName.isEmpty()) callerName = "未知用户";
                if (getActivity() instanceof MainActivityNew) {
                    ((MainActivityNew) getActivity()).onCallRequest(callerName, deviceAddress);
                }
                return;
            }
            if (message.trim().equals(IBluetoothService.CALL_ACCEPT) ||
                    message.trim().equals(IBluetoothService.CALL_REJECT) ||
                    message.trim().equals(IBluetoothService.CALL_HANGUP)) {
                LogUtil.d(TAG, "检测到呼叫控制消息: " + message);
                if (getActivity() instanceof MainActivityNew) {
                    if (message.trim().equals(IBluetoothService.CALL_ACCEPT))
                        ((MainActivityNew) getActivity()).onCallAccepted(deviceAddress);
                    else if (message.trim().equals(IBluetoothService.CALL_REJECT))
                        ((MainActivityNew) getActivity()).onCallRejected(deviceAddress);
                    else
                        ((MainActivityNew) getActivity()).onCallHungUp(deviceAddress);
                }
                return;
            }

            if (message.startsWith(IBluetoothService.FILE_REQUEST_PREFIX)) {
                LogUtil.d(TAG, "检测到文件请求消息");
                if (processedMessages.contains(message)) {
                    LogUtil.d(TAG, "重复文件请求消息，已忽略");
                    return;
                }
                processedMessages.add(message);
                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                    processedMessages.remove(message);
                }, 5000);

                String[] parts = message.substring(IBluetoothService.FILE_REQUEST_PREFIX.length()).split(",");
                if (parts.length >= 2) {
                    String fileName = parts[0];
                    long size;
                    try {
                        size = Long.parseLong(parts[1]);
                    } catch (NumberFormatException e) {
                        LogUtil.e(TAG, "解析文件大小失败", e);
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

            if (message.startsWith(IBluetoothService.CALL_PREFIX)) {
                String callerName = message.substring(IBluetoothService.CALL_PREFIX.length());
                if (callerName.isEmpty()) callerName = "未知用户";
                Toast.makeText(getActivity(), "📢 " + callerName + " 召唤您！", Toast.LENGTH_LONG).show();
                return;
            }

            if (message.trim().equals(IBluetoothService.FILE_ACCEPT)) {
                LogUtil.d(TAG, "收到对方同意文件接收");
                if (isWaitingForAccept && localFilePath != null && new File(localFilePath).exists()) {
                    startFileSend(localFilePath, pendingFileName);
                } else {
                    LogUtil.e(TAG, "文件不存在或等待状态异常");
                    Toast.makeText(getActivity(), "文件已丢失", Toast.LENGTH_SHORT).show();
                }
                return;
            }
            if (message.trim().equals(IBluetoothService.FILE_REJECT)) {
                LogUtil.d(TAG, "收到对方拒绝文件接收");
                isWaitingForAccept = false;
                localFilePath = null;
                Toast.makeText(getActivity(), "对方拒绝了文件", Toast.LENGTH_SHORT).show();
                return;
            }

            // 普通文本
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
                case IBluetoothService.STATE_CONNECTED:
                    tvDeviceName.setText(displayName + " (已连接)");
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
                case IBluetoothService.STATE_CONNECTING:
                    tvDeviceName.setText(displayName + " (连接中...)");
                    break;
                case IBluetoothService.STATE_LISTEN:
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
                Fragment currentFragment = getActivity().getSupportFragmentManager().findFragmentById(R.id.fragment_container);
                if (currentFragment instanceof TalkbackFragment) {
                    // 已经在对讲模式，沿用原有计数逻辑（可保留或简化）
                    nonTextDataCount++;
                    nonTextHandler.removeCallbacks(resetNonTextDataCount);
                    if (nonTextDataCount >= 2) {
                        // 这里再次切换（实际无意义，可改为重置超时等）
                        switchToTalkbackFragment();
                    } else {
                        nonTextHandler.postDelayed(resetNonTextDataCount, 1000);
                    }
                } else {
                    // ★★★ 修复点：聊天模式下收到非文本数据 → 自动切换到对讲模式 ★★★
                    Toast.makeText(getActivity(), "检测到对讲数据，自动切换至对讲模式", Toast.LENGTH_SHORT).show();
                    // 先设置模式为 TALKBACK
                    if (bluetoothService != null) {
                        bluetoothService.setMode(IBluetoothService.MODE_TALKBACK);
                    }
                    // 切换到 TalkbackFragment
                    if (getActivity() instanceof MainActivityNew) {
                        String name = bluetoothService != null ? bluetoothService.getConnectedDeviceName() : "未知设备";
                        ((MainActivityNew) getActivity()).switchToFragment("TalkbackFragment", deviceAddress, name);
                    }
                }
            }
        });
    }

    @Override
    public void onTalkbackDataReceived(byte[] data, String deviceAddress) {
        // 无需处理
    }

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

    // ==================== 消息操作（复制、打开、删除） ====================
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
                FileOutputStream fos = new FileOutputStream(file, true);
                OutputStreamWriter osw = new OutputStreamWriter(fos, "UTF-8");
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
                osw.close();
                fos.close();
            }
        } catch (IOException e) { LogUtil.e(TAG, "更新聊天记录文件失败", e); }
    }

    private void loadChatHistory() {
        if (serviceBound && deviceAddress != null) {
            // ★★★ 通过接口加载历史 ★★★
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
                        } catch (Exception e) { LogUtil.e(TAG, "解析聊天记录失败: " + line, e); }
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
        try {
            File exportDir = new File(Environment.getExternalStorageDirectory(), "SoundTransferExports");
            if (!exportDir.exists() && !exportDir.mkdirs()) { Toast.makeText(getActivity(), "创建导出目录失败", Toast.LENGTH_SHORT).show(); return; }
            String safeDeviceName = (deviceName != null) ? deviceName.replaceAll("[^a-zA-Z0-9.-]", "_") : "unknown";
            SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault());
            String timestamp = sdf.format(new Date());
            String filename = "chat_" + safeDeviceName + "_" + timestamp + ".txt";
            File exportFile = new File(exportDir, filename);
            FileOutputStream fos = new FileOutputStream(exportFile);
            OutputStreamWriter osw = new OutputStreamWriter(fos, "UTF-8");
            osw.write("聊天记录导出 - " + (deviceName != null ? deviceName : "未知设备") + "\n");
            osw.write("导出时间: " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new Date()) + "\n\n");
            for (Message msg : messageList) {
                String sender = msg.isSent() ? "我方" : "对方";
                String time = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(msg.getTimestamp());
                if (msg.getType() == Message.TYPE_IMAGE) {
                    osw.write(time + " [" + sender + "]: [图片] " + msg.getFileName() + "\n");
                } else if (msg.getType() == Message.TYPE_FILE) {
                    osw.write(time + " [" + sender + "]: [文件] " + msg.getFileName() + " (" + formatFileSize(msg.getFileSize()) + ")\n");
                } else if (msg.getType() == Message.TYPE_VOICE) {
                    osw.write(time + " [" + sender + "]: [语音] " + msg.getVoiceDuration() + "秒\n");
                } else {
                    osw.write(time + " [" + sender + "]: " + msg.getContent() + "\n");
                }
            }
            osw.close();
            fos.close();
            Toast.makeText(getActivity(), "聊天记录已导出到: " + exportFile.getAbsolutePath(), Toast.LENGTH_LONG).show();
        } catch (IOException e) { LogUtil.e(TAG, "导出聊天记录失败", e); Toast.makeText(getActivity(), "导出失败: " + e.getMessage(), Toast.LENGTH_SHORT).show(); }
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
            if (size == 0) {
                try (AssetFileDescriptor fd = resolver.openAssetFileDescriptor(uri, "r")) {
                    if (fd != null) {
                        size = fd.getLength();
                    }
                }
            }
        } catch (Exception e) {
            LogUtil.e(TAG, "获取文件大小失败", e);
        }
        return size;
    }

    private String saveFileToLocalFromStream(InputStream inputStream, String fileName) {
        try {
            File dir = new File(getActivity().getExternalFilesDir(null), "files");
            if (!dir.exists()) dir.mkdirs();
            String timeStamp = String.valueOf(System.currentTimeMillis());
            File file = new File(dir, timeStamp + "_" + fileName);
            FileOutputStream fos = new FileOutputStream(file);
            byte[] buffer = new byte[8192];
            int len;
            while ((len = inputStream.read(buffer)) != -1) {
                fos.write(buffer, 0, len);
            }
            fos.close();
            return file.getAbsolutePath();
        } catch (IOException e) {
            LogUtil.e(TAG, "保存本地文件失败（流式）", e);
            return null;
        }
    }

    private void switchToTalkbackFragment() {
        Toast.makeText(getActivity(), "检测到对讲连接，正在切换...", Toast.LENGTH_SHORT).show();
        if (getActivity() instanceof MainActivityNew) {
            ((MainActivityNew) getActivity()).switchToFragment("TalkbackFragment", deviceAddress, deviceName);
        }
    }
}