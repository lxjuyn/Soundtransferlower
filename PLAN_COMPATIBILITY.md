# 兼容性改造计划：Android 4.0 → 16/17 全覆盖

## 目标
让"纸条"应用支持 Android 4.0 (API 14) 到 Android 16/17 (API 36/37) 的所有设备。

## 当前问题

| 问题 | 影响 | 优先级 |
|------|------|--------|
| targetSdk 19 | Android 14+ 拒绝安装 | P0 |
| support-v7:24 | 无法使用现代 API，停止更新 | P0 |
| 无运行时权限申请 | Android 6+ 蓝牙/录音/存储崩溃 | P0 |
| 无前台服务类型声明 | Android 14+ 前台服务崩溃 | P1 |
| 无 Scoped Storage | Android 10+ 文件操作失败 | P1 |
| BluetoothService 保活机制过时 | Android 8+ 后台限制 | P1 |

## Phase 1: 基础升级（必须，最先做）

### 1.1 AndroidX 迁移
- `gradle.properties`: `android.useAndroidX=true`, `android.enableJetifier=true`
- `build.gradle`: 替换 `com.android.support:appcompat-v7:24.0.0` → `androidx.appcompat:appcompat:1.6.1`
- `build.gradle`: 替换 `com.android.support:design:24.0.0` → `com.google.android.material:material:1.11.0`
- 全局替换 Java import:
  - `android.support.v7.app.AppCompatActivity` → `androidx.appcompat.app.AppCompatActivity`
  - `android.support.v4.app.Fragment` → `androidx.fragment.app.Fragment`
  - `android.support.v4.content.FileProvider` → `androidx.core.content.FileProvider`
  - `android.support.v7.widget.SwitchCompat` → `androidx.appcompat.widget.SwitchCompat`
  - `android.support.v7.widget.RecyclerView` → `androidx.recyclerview.widget.RecyclerView`
  - `android.support.design.*` → `com.google.android.material.*`
- XML namespace 替换: `android.support.v7` → `androidx`, `android.support.design` → `com.google.android.material`

### 1.2 TargetSdk 升级
- `targetSdk 19` → `targetSdk 34`
- `compileSdk 34` → `compileSdk 35`
- `minSdk 15` → `minSdk 14`（支持 Android 4.0）

### 1.3 Gradle 升级
- AGP 升级到 8.7+（当前已是 8.5.2，接近）
- Gradle wrapper 保持 8.7+
- 确保 JDK 17 兼容

## Phase 2: 运行时权限（P0，Android 6+ 必须）

### 2.1 权限申请框架
新建 `PermissionHelper.java` 工具类：
```java
// 统一处理运行时权限申请
// 蓝牙: BLUETOOTH_SCAN, BLUETOOTH_CONNECT (API 31+)
//       ACCESS_FINE_LOCATION (API 23-30)
// 录音: RECORD_AUDIO
// 存储: READ/WRITE_EXTERNAL_STORAGE (API < 29)
//       MANAGE_EXTERNAL_STORAGE (API 30+, 可选)
```

### 2.2 各组件权限检查
- `MainActivityNew.onCreate`: 启动时请求蓝牙+位置权限
- `ChatWorkFragment`: 发送文件前检查存储权限
- `TalkbackFragment`: 对讲前检查录音+蓝牙权限
- `VoiceRecorder`: 录音前检查录音权限

## Phase 3: 蓝牙权限适配（P0，Android 12+ 必须）

### 3.1 新增权限声明 (AndroidManifest.xml)
```xml
<!-- API 31+ -->
<uses-permission android:name="android.permission.BLUETOOTH_SCAN"
    android:usesPermissionFlags="neverForLocation" />
<uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />
<uses-permission android:name="android.permission.BLUETOOTH_ADVERTISE" />
```

### 3.2 条件化权限 (maxSdkVersion)
```xml
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION"
    android:maxSdkVersion="30" />
<uses-permission android:name="android.permission.WRITE_EXTERNAL_STORAGE"
    android:maxSdkVersion="28" />
<uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE"
    android:maxSdkVersion="32" />
```

### 3.3 蓝牙 API 版本适配
```java
// BluetoothAdapter.getName() → API 31+ 需要 BLUETOOTH_CONNECT
// BluetoothDevice.createBond() → API 31+ 需要 BLUETOOTH_CONNECT
// BluetoothAdapter.startDiscovery() → API 31+ 需要 BLUETOOTH_SCAN
// BluetoothAdapter.getRemoteDevice() → 始终可用
```

## Phase 4: 前台服务适配（P1，Android 8+ / 14+）

### 4.1 前台服务类型声明 (AndroidManifest.xml)
```xml
<service android:name=".BluetoothService"
    android:foregroundServiceType="connectedDevice" />
<service android:name=".BluetoothFileTransferService"
    android:foregroundServiceType="dataSync" />
```

### 4.2 Notification Channel (API 26+)
```java
if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
    NotificationChannel channel = new NotificationChannel(
        "bluetooth_service", "蓝牙服务",
        NotificationManager.IMPORTANCE_LOW);
    notificationManager.createNotificationChannel(channel);
}
```

### 4.3 前台服务启动限制 (API 34+)
- 需要在 `startForeground()` 时传入 `ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE`
- 不能从后台启动前台服务（需要 `FOREGROUND_SERVICE_TYPE_SPECIAL_USE` 或改用 WorkManager）

## Phase 5: 存储适配（P1，Android 10+）

### 5.1 Scoped Storage 迁移
- 聊天记录保存: 使用 `context.getExternalFilesDir()` (不需要权限)
- 接收文件保存: 使用 `MediaStore` API 或 `SAF` (Android 10+)
- 导出聊天记录: 使用 `SAF` DocumentFile

### 5.2 兼容方案
```java
if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
    // Scoped Storage: 使用 MediaStore 或 SAF
} else {
    // 旧版: 直接使用 Environment.getExternalStorageDirectory()
}
```

## Phase 6: 后台限制适配（P1，Android 8+）

### 6.1 BluetoothService 保活机制改造
- ~~AlarmManager 30分钟重启~~ → WorkManager PeriodicWorkRequest
- ~~BOOT_COMPLETED 直接启动服务~~ → BootReceiver + WorkManager
- 保留前台通知（始终有效）

### 6.2 后台蓝牙连接
- Android 8+: 使用 `startForegroundService()` 替代 `startService()`
- Android 12+: 精确闹钟需要 `SCHEDULE_EXACT_ALARM` 权限

## Phase 7: Concentus Opus 替代（P0，编译必须）

### 方案对比

| 方案 | 优点 | 缺点 |
|------|------|------|
| A: JitPack 构建 | 源码可控 | 需要配置 JitPack 仓库 |
| B: 本地源码编译 | 无外部依赖 | 维护成本高 |
| C: tinskeleton/opus-android | Maven Central 可用 | 接口不同，需要适配 |
| D: Oboe + native Opus | 性能最佳 | 需要 NDK，minSdk 提升 |

### 推荐方案: A + B 备选
1. 首选: 从 Concentus GitHub 源码构建 JAR，通过 JitPack 分发
2. 备选: 将 Concentus 源码直接放入项目作为 module

## 实施顺序与依赖

```
Phase 1 (AndroidX迁移)
  ├── Phase 2 (运行时权限) ← 依赖 AndroidX
  ├── Phase 3 (蓝牙权限) ← 依赖 AndroidX
  └── Phase 4 (前台服务) ← 依赖 AndroidX
Phase 5 (存储适配) ← 可独立
Phase 6 (后台限制) ← 可独立
Phase 7 (Opus替代) ← 可独立，但编译前必须解决
```

## 风险与注意事项

1. **Concentus Opus 是最高优先级** — 没有它项目无法编译
2. **AndroidX 迁移是第二优先级** — 所有后续改动都依赖它
3. **测试策略**: 每完成一个 Phase 都在模拟器上验证
4. **向后兼容**: 使用 `Build.VERSION.SDK_INT` 条件判断
5. **targetSdk 34**: Google Play 要求每年更新 targetSdk

## 预估工作量

| Phase | 预估时间 | 难度 |
|-------|----------|------|
| Phase 1: AndroidX | 30-60 分钟 | 中 |
| Phase 2: 运行时权限 | 20-30 分钟 | 低 |
| Phase 3: 蓝牙权限 | 20-30 分钟 | 中 |
| Phase 4: 前台服务 | 30-40 分钟 | 中 |
| Phase 5: 存储适配 | 30-40 分钟 | 中 |
| Phase 6: 后台限制 | 30-40 分钟 | 高 |
| Phase 7: Opus替代 | 20-40 分钟 | 中 |
| **总计** | **3-4 小时** | |
