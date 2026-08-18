package com.example.soundtransferlower;

import java.util.zip.CRC32;

/**
 * 文件传输协议工具类
 * 统一管理文件传输的编解码和校验
 *
 * 协议格式:
 * [8字节文件长度(long)] [2字节文件名长度(short)] [文件名(UTF-8)] [文件数据] [4字节CRC32校验码]
 */
public final class FileTransferProtocol {
    // 文件传输长度字段从int(4字节)升级为long(8字节)，支持>2GB文件
    public static final int FILE_LENGTH_BYTES = 8;
    public static final int NAME_LENGTH_BYTES = 2;
    public static final int CRC32_BYTES = 4;

    // 传输缓冲区大小
    public static final int BUFFER_SIZE_SOCKET = 8192;    // socket缓冲区
    public static final int BUFFER_SIZE_FILE = 65536;      // 文件读写缓冲区(64KB)

    private FileTransferProtocol() {} // 不可实例化

    /**
     * long → 8字节大端序
     */
    public static byte[] longToBytes(long value) {
        return new byte[]{
                (byte) (value >> 56),
                (byte) (value >> 48),
                (byte) (value >> 40),
                (byte) (value >> 32),
                (byte) (value >> 24),
                (byte) (value >> 16),
                (byte) (value >> 8),
                (byte) value
        };
    }

    /**
     * 8字节大端序 → long
     */
    public static long bytesToLong(byte[] bytes) {
        return ((long) (bytes[0] & 0xFF) << 56) |
                ((long) (bytes[1] & 0xFF) << 48) |
                ((long) (bytes[2] & 0xFF) << 40) |
                ((long) (bytes[3] & 0xFF) << 32) |
                ((long) (bytes[4] & 0xFF) << 24) |
                ((long) (bytes[5] & 0xFF) << 16) |
                ((long) (bytes[6] & 0xFF) << 8) |
                ((long) (bytes[7] & 0xFF));
    }

    /**
     * short → 2字节大端序
     */
    public static byte[] shortToBytes(short value) {
        return new byte[]{
                (byte) (value >> 8),
                (byte) value
        };
    }

    /**
     * 2字节大端序 → short
     */
    public static short bytesToShort(byte[] bytes) {
        return (short) (((bytes[0] & 0xFF) << 8) | (bytes[1] & 0xFF));
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

    /**
     * 计算CRC32校验码
     */
    public static CRC32 createCRC32() {
        return new CRC32();
    }

    /**
     * 获取CRC32值的4字节表示
     */
    public static byte[] crc32ToBytes(CRC32 crc32) {
        return intToBytes((int) crc32.getValue());
    }

    /**
     * 验证CRC32校验码
     */
    public static boolean verifyCRC32(CRC32 computed, int received) {
        return (int) computed.getValue() == received;
    }
}
