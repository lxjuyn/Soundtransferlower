package com.example.soundtransferlower;

import android.util.Log;

import java.nio.charset.StandardCharsets;

/**
 * 消息协议工具类
 * 统一管理蓝牙聊天/对讲的消息编解码
 */
public final class MessageProtocol {
    private static final String TAG = "MessageProtocol";

    // 消息类型标识
    public static final byte TYPE_TEXT = 0x01;
    public static final byte TYPE_AUDIO = 0x02;

    // 文本前缀
    public static final String TEXT_PREFIX = "TXT:";
    public static final byte[] TEXT_PREFIX_BYTES = TEXT_PREFIX.getBytes(StandardCharsets.UTF_8);

    // 控制消息常量
    public static final String FILE_REQUEST_PREFIX = "FILE_REQUEST:";
    public static final String FILE_ACCEPT = "FILE_ACCEPT";
    public static final String FILE_REJECT = "FILE_REJECT";
    public static final String CALL_PREFIX = "CALL:";
    public static final String CALL_REQUEST = "CALL_REQUEST:";
    public static final String CALL_ACCEPT = "CALL_ACCEPT";
    public static final String CALL_REJECT = "CALL_REJECT";
    public static final String CALL_HANGUP = "CALL_HANGUP";

    private MessageProtocol() {} // 不可实例化

    /**
     * 编码文本消息为字节数组
     * 格式: [4字节长度][TXT:payload]
     */
    public static byte[] encodeText(String message) {
        byte[] payload = (TEXT_PREFIX + message).getBytes(StandardCharsets.UTF_8);
        byte[] lenBytes = intToBytes(payload.length);
        byte[] result = new byte[4 + payload.length];
        System.arraycopy(lenBytes, 0, result, 0, 4);
        System.arraycopy(payload, 0, result, 4, payload.length);
        return result;
    }

    /**
     * 编码音频数据为字节数组
     * 格式: [4字节长度][raw opus data]
     */
    public static byte[] encodeAudio(byte[] opusData) {
        byte[] lenBytes = intToBytes(opusData.length);
        byte[] result = new byte[4 + opusData.length];
        System.arraycopy(lenBytes, 0, result, 0, 4);
        System.arraycopy(opusData, 0, result, 4, opusData.length);
        return result;
    }

    /**
     * 检查数据是否为文本消息
     */
    public static boolean isTextMessage(byte[] data, int length) {
        if (length < TEXT_PREFIX_BYTES.length) return false;
        for (int i = 0; i < TEXT_PREFIX_BYTES.length; i++) {
            if (data[i] != TEXT_PREFIX_BYTES[i]) return false;
        }
        return true;
    }

    /**
     * 从文本载荷中提取消息内容（去掉TXT:前缀）
     */
    public static String extractTextContent(byte[] payload, int length) {
        return new String(payload, TEXT_PREFIX_BYTES.length,
                length - TEXT_PREFIX_BYTES.length, StandardCharsets.UTF_8);
    }

    /**
     * 检查是否为控制消息
     */
    public static boolean isControlMessage(String message) {
        if (message == null) return false;
        String trimmed = message.trim();
        return trimmed.startsWith(FILE_REQUEST_PREFIX) ||
                trimmed.equals(FILE_ACCEPT) ||
                trimmed.equals(FILE_REJECT) ||
                trimmed.startsWith(CALL_PREFIX) ||
                trimmed.startsWith(CALL_REQUEST) ||
                trimmed.equals(CALL_ACCEPT) ||
                trimmed.equals(CALL_REJECT) ||
                trimmed.equals(CALL_HANGUP);
    }

    /**
     * int → 4字节大端序
     */
    public static byte[] intToBytes(int value) {
        return new byte[]{
                (byte) (value >> 24),
                (byte) (value >> 16),
                (byte) (value >> 8),
                (byte) value
        };
    }

    /**
     * 4字节大端序 → int
     */
    public static int bytesToInt(byte[] bytes) {
        return ((bytes[0] & 0xFF) << 24) |
                ((bytes[1] & 0xFF) << 16) |
                ((bytes[2] & 0xFF) << 8) |
                (bytes[3] & 0xFF);
    }
}
