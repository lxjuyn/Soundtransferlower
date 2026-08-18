# Soundtransferlower 优化计划

## 目标
1. 代码效率优化 — 减少资源消耗、消除内存泄漏、简化冗余代码
2. 传输速率提升 — 优化蓝牙 RFCOMM 传输性能
3. 代码规范化 — 统一风格、消除坏味道

## Phase 1: 传输速率优化（高优先级）

### 1.1 缓冲区优化
- 当前：1024 字节缓冲区 → 提升至 8192 字节（RFCOMM MTU 约 990 字节，但更大的缓冲区减少系统调用次数）
- 文件传输使用 64KB 缓冲区
- 使用 BufferedInputStream/BufferedOutputStream 包装 socket 流

### 1.2 协议优化
- 文件传输长度字段从 int(4字节) 改为 long(8字节)，支持 >2GB 文件
- 添加 CRC32 校验头，替代简单的长度前缀
- 文本消息添加长度前缀（防止粘包）

### 1.3 线程模型优化
- ConnectedThread 使用 NIO 非阻塞读取（或至少增加超时检测）
- 文件传输使用独立的线程池，避免阻塞主蓝牙通道

## Phase 2: 代码效率优化

### 2.1 内存泄漏修复
- Handler 使用静态内部类 + WeakReference
- 移除嵌套 postDelayed，改用协程或 ScheduledExecutorService
- 确保 BroadcastReceiver 正确注销

### 2.2 资源管理
- WakeLock 使用 try/finally 确保释放
- AudioRecord/AudioTrack 使用完成后立即 release
- 文件流使用 try-with-resources

### 2.3 重复代码消除
- 合并 3 份 DeviceListAdapter 为 1 份
- 合并 connectToDeviceForChat 两个重复方法
- 删除死代码：item_message.xml、device_list_item.xml、fragment_empty.xml

## Phase 3: 代码规范化

### 3.1 编码规范
- 统一使用 UTF-8 编码（修复 GBK 乱码）
- 字符串外部化到 strings.xml
- 常量提取为 static final

### 3.2 架构规范
- 消息协议解析提取为独立类（MessageProtocol）
- 文件传输协议提取为独立类（FileTransferProtocol）
- 回调接口统一命名（XxxCallback → OnXxxListener）

## 涉及文件

| 操作 | 文件 | 说明 |
|---|---|---|
| 修改 | BluetoothService.java | 缓冲区、协议解析、线程优化 |
| 修改 | BluetoothFileTransferService.java | 缓冲区、协议、线程优化 |
| 修改 | MainActivityNew.java | 内存泄漏修复、代码简化 |
| 修改 | ChatWorkFragment.java | Handler 修复、代码简化 |
| 修改 | TalkbackFragment.java | Handler 修复、资源管理 |
| 修改 | AudioRecorderPlayer.java | 资源管理优化 |
| 修改 | VoiceRecorder.java | 资源管理优化 |
| 删除 | DeviceListAdapter.java | 合并重复代码 |
| 删除 | item_message.xml | 死代码 |
| 删除 | device_list_item.xml | 死代码 |
| 删除 | fragment_empty.xml | 死代码 |
| 新建 | MessageProtocol.java | 协议解析提取 |
| 新建 | FileTransferProtocol.java | 传输协议提取 |

## 验证方式
1. 编译通过（assembleDebug）
2. 在模拟器上运行，测试蓝牙连接、聊天、文件传输、对讲功能
3. 检查内存泄漏（LeakCanary 或手动 profiling）
4. 对比优化前后传输速度（大文件传输计时）
