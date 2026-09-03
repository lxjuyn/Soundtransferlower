package com.example.soundtransferlower;

public interface IMessageCallback {
    interface MessageCallback {
        void onMessageReceived(String message, String deviceAddress);
        void onConnectionStatusChanged(int state, String deviceName);
        void onTalkbackDataReceived(byte[] data, String deviceAddress);
        void onNonTextDataReceived(String deviceAddress);
        void onCallRequest(String callerName, String deviceAddress);
        void onCallAccepted(String deviceAddress);
        void onCallRejected(String deviceAddress);
        void onCallHungUp(String deviceAddress);

        // ★★★ 新增：消息确认回调 ★★★
        void onMessageConfirmed(long timestamp);
    }
}