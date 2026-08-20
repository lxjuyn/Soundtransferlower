package com.example.soundtransferlower;

public interface IFileTransferService {
    // 发送文件（指定目标地址、本地文件路径、文件名）
    void sendFile(String deviceAddress, String filePath, String fileName);
    // 接收文件（指定保存目录、期望文件名）
    void receiveFile(String saveDir, String fileName);
    // 取消传输（可选，暂不实现）

    // 回调管理
    void registerCallback(BluetoothFileTransferService.FileTransferCallback callback);
    void unregisterCallback(BluetoothFileTransferService.FileTransferCallback callback);

    // 获取服务接口实例
    IFileTransferService getInterface();
}