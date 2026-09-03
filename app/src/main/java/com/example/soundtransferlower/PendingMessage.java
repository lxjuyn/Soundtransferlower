package com.example.soundtransferlower;

import java.util.UUID;

public class PendingMessage {
    public static final int TYPE_TEXT = 0;
    public static final int TYPE_VOICE = 1;
    public static final int TYPE_FILE = 2;

    public String id;
    public int type;
    public String content;          // 文本内容或文件路径
    public String targetDeviceAddress;
    public String targetDeviceName;
    public String reason;
    public long timestamp;

    public PendingMessage() {}

    public PendingMessage(int type, String content, String targetDeviceAddress, String targetDeviceName, String reason) {
        this.id = UUID.randomUUID().toString();
        this.type = type;
        this.content = content;
        this.targetDeviceAddress = targetDeviceAddress;
        this.targetDeviceName = targetDeviceName;
        this.reason = reason;
        this.timestamp = System.currentTimeMillis();
    }
}