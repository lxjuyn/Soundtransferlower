package com.example.soundtransferlower;

import android.bluetooth.BluetoothDevice;

public interface IBluetoothService {
    // 状态常量
    int STATE_NONE = 0;
    int STATE_LISTEN = 1;
    int STATE_CONNECTING = 2;
    int STATE_CONNECTED = 3;

    int MODE_CHAT = 0;
    int MODE_TALKBACK = 1;

    // 消息协议常量（与原始 Service 保持一致）
    String TEXT_PREFIX = "TXT:";
    String FILE_REQUEST_PREFIX = "FILE_REQUEST:";
    String FILE_ACCEPT = "FILE_ACCEPT";
    String FILE_REJECT = "FILE_REJECT";
    String CALL_PREFIX = "CALL:";
    String CALL_REQUEST = "CALL_REQUEST:";
    String CALL_ACCEPT = "CALL_ACCEPT";
    String CALL_REJECT = "CALL_REJECT";
    String CALL_HANGUP = "CALL_HANGUP";

    // 生命周期控制
    void start();
    void stop();
    void connect(BluetoothDevice device);
    void connect(String deviceAddress);

    // 角色设置
    void setConnectionRole(boolean isInitiator, String targetDeviceAddress);

    // 模式
    void setMode(int mode);
    int getMode();

    // 连接信息
    String getConnectedDeviceAddress();
    String getConnectedDeviceName();
    int getState();

    // 数据发送
    void write(byte[] out);
    void write(byte[] out, int mode);

    // 回调管理（回调接口定义在 BluetoothService 中，此处沿用）
    void registerCallback(IMessageCallback.MessageCallback callback);
    void unregisterCallback(IMessageCallback.MessageCallback callback);

    // 聊天历史
    String loadChatHistory(String deviceAddress);

    // 获取接口实例（用于 Binder）
    IBluetoothService getInterface();
}