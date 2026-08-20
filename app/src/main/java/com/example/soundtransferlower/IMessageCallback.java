package com.example.soundtransferlower;

public interface IMessageCallback {
    // ---------- 回调接口 ----------
    interface MessageCallback {
        void onMessageReceived(String message, String deviceAddress);
        void onConnectionStatusChanged(int state, String deviceName);
        void onTalkbackDataReceived(byte[] data, String deviceAddress);
        void onNonTextDataReceived(String deviceAddress);
        void onCallRequest(String callerName, String deviceAddress);
        void onCallAccepted(String deviceAddress);
        void onCallRejected(String deviceAddress);
        void onCallHungUp(String deviceAddress);
    }
}
