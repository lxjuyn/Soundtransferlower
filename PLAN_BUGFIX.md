# Bug 修复计划 — 3 个关键问题

## Bug 1: 文件传输无法弹出文件管理器

### 问题分析
ChatWorkFragment 中发送文件时，文件选择器无法弹出。可能原因：
- Android 10+ Scoped Storage 改造后，文件选择 Intent 未正确触发
- `startActivityForResult` 在 Fragment 中调用方式有问题
- 缺少 Intent 的 `addCategory(Intent.CATEGORY_OPENABLE)` 标记

### 修复方案
1. 检查 ChatWorkFragment 中触发文件选择的代码（chat_more_menu 或 "+" 按钮）
2. 确保使用 `ACTION_GET_CONTENT` 或 `ACTION_OPEN_DOCUMENT` + `CATEGORY_OPENABLE`
3. 确保 `startActivityForResult(intent, REQUEST_FILE_PICK)` 正确调用
4. 检查 `onActivityResult` 是否正确处理返回的 URI

## Bug 2: 蓝牙连接状态切换页面丢失

### 问题分析
核心原因：BluetoothService 的 MessageCallback 是 CopyOnWriteArrayList 广播模式，每个 Fragment 在 onCreateView 时注册、onDestroyView 时注销。切换 Fragment 后，新 Fragment 没有重新查询当前连接状态，导致显示"未连接"。

### 修复方案
1. **MainActivityNew 添加全局连接状态字段**：保存当前连接状态（connected/disconnected/deviceName）
2. **Fragment onResume 时查询状态**：新 Fragment 在 onResume 中从 MainActivityNew 获取当前连接状态
3. **或者让 BluetoothService 维护状态**：添加 `isConnected()` 和 `getConnectedDeviceName()` 方法供 Fragment 查询

## Bug 3: 应用无法在后台运行

### 问题分析
targetSdk 34 后台限制：
1. `startForegroundService()` 后必须在 5 秒内调用 `startForeground()`，否则系统杀进程
2. BluetoothService.onCreate() 中的初始化可能耗时过长
3. 从后台被杀后，AlarmManager 重启服务可能失败

### 修复方案
1. **BluetoothService.onCreate() 中立即调用 startForeground()**：在任何耗时操作之前
2. **确保 Notification Channel 在 startForeground 之前创建**
3. **BootReceiver 和 AlarmReceiver 使用 startForegroundService + 立即 startForeground**
4. **添加 SYSTEM_ALERT_WINDOW 权限**（可选，保持后台活动）

## 涉及文件

| 文件 | Bug | 修改内容 |
|------|-----|----------|
| ChatWorkFragment.java | Bug 1 | 修复文件选择 Intent |
| MainActivityNew.java | Bug 2 | 添加全局连接状态查询 |
| BluetoothService.java | Bug 2, 3 | 添加状态查询方法 + 立即 startForeground |
| TalkbackFragment.java | Bug 2 | onResume 查询连接状态 |

## 实施顺序
1. 先修复 Bug 3（后台运行，最关键，影响通话/召唤）
2. 再修复 Bug 2（连接状态，体验问题）
3. 最后修复 Bug 1（文件传输，功能问题）
