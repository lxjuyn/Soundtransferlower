# Soundtransferlower(纸条)项目结构分析文档

## 项目概述

**Soundtransferlower**(应用显示名"纸条",作者"秋元",开源地址 gitee.com/yonstus/Soundtransferlower)是一个基于**经典蓝牙 RFCOMM(SPP 串口协议)**的安卓点对点通信应用。两台安装本应用的手机配对后,无需联网即可实现:

1. **文字聊天**:通过 RFCOMM  socket 互发文本消息,带历史记录持久化(按设备 MAC 分文件保存)、记录导出、删除、复制等功能;
2. **文件/图片传输**:独立的服务 + 独立的蓝牙 UUID 通道传输任意文件,带进度和速度显示,支持最大约 5GB;
3. **语音消息**:按住录音(Opus 编码,8kHz),以文件传输方式发送,接收端自动接收并可点击播放;
4. **实时对讲(PTT)**:按下说话、松开发送,Opus 实时编解码 + AudioTrack 流式播放,半双工状态机管理"我方说话/对方说话"状态;
5. **蓝牙语音通话**:类似电话的"拨号—响铃—接听—挂断"全流程,语音通路复用对讲的 Opus 编解码;
6. **"召唤上线"提醒**:给对方发一条高优先级通知(带震动、铃声 channel),点击通知直接进入聊天。

为了在老机器上运行,应用刻意保持极低的 API 要求(minSdk 7 / targetSdk 19,支持 Android 2.1 起),并以**前台服务 + WakeLock + AlarmManager 心跳 + 开机自启 + 屏幕点亮唤醒**的一整套保活机制维持蓝牙后台连接。

---

## 技术栈与配置

| 项目 | 值 | 说明 |
|---|---|---|
| namespace(源码包名) | `com.example.soundtransferlower` | 所有 Java 类所在包 |
| applicationId(应用包名) | `com.am.papertape` | 与 namespace 不一致,打包后的真正包名 |
| versionCode / versionName | 29 / `2.9.3fix` | `app/release/app-release.apk` 已有构建产物 |
| compileSdk | 34 | |
| minSdk | **7**(Android 2.1) | `app/build.gradle` 中为 7,Manifest 中 `uses-sdk` 覆盖声明为 19(Manifest 优先) |
| targetSdk | **19**(Android 4.4) | 刻意保持低 target 以规避运行时权限/存储限制 |
| AGP 版本 | **8.5.2**(`gradle/libs.versions.toml`) | 新版 AGP 构建极老的 target,组合很罕见 |
| Java 版本 | 1.8 | |
| 关键依赖 | `com.android.support:appcompat-v7:24.0.0`、`com.android.support:design:24.0.0`、`fileTree('libs')` | **注意:用的是 2016 年的 support 库,不是 AndroidX**;但 `gradle.properties` 里 `android.useAndroidX=true`,存在冲突风险 |
| Opus 编解码 | `org.concentus`(Concentus,纯 Java Opus) | 由 `AudioRecorderPlayer`/`VoiceRecorder` import,但 `app/libs` 目录不存在且 build.gradle 未显式声明——**该依赖目前缺失,源码无法直接编译** |
| 代码混淆 | 关闭(`minifyEnabled false`) | |
| 仓库 | gitee.com/yonstus/Soundtransferlower | |

---

## 权限与组件(AndroidManifest.xml)

### 权限

| 权限 | 用途 |
|---|---|
| BLUETOOTH / BLUETOOTH_ADMIN | 经典蓝牙连接(Android ≤11) |
| BLUETOOTH_SCAN / BLUETOOTH_CONNECT / BLUETOOTH_ADVERTISE | Android 12+ 新蓝牙权限(声明了但因 targetSdk=19 实际不会被系统强制执行) |
| ACCESS_FINE_LOCATION | 蓝牙扫描所需(Android 6–11) |
| RECORD_AUDIO / MODIFY_AUDIO_SETTINGS | 对讲与通话录音、音频路由 |
| WRITE/READ_EXTERNAL_STORAGE | 保存/导出聊天记录、保存接收文件到公共目录 |
| ACCESS_NETWORK_STATE | 未实际使用 |
| VIBRATE | 连接成功振动反馈 |
| RECEIVE_BOOT_COMPLETED | 开机自启蓝牙服务 |
| WAKE_LOCK | 服务保活 |

### 组件

| 组件 | 类型 | 说明 |
|---|---|---|
| `.MainActivityNew` | Activity(唯一入口,LAUNCHER) | 主界面容器,承载所有 Fragment |
| `.BluetoothService` | Service(exported=true,前台服务) | 核心:RFCOMM 聊天/对讲连接管理,常驻保活 |
| `.BluetoothFileTransferService` | Service | 独立的文件传输通道(独立 UUID) |
| `.BootReceiver` | BroadcastReceiver | 监听 BOOT_COMPLETED,开机拉起 BluetoothService |
| `android.support.v4.content.FileProvider` | Provider | `${applicationId}.fileprovider`,用于打开接收的文件 |

---

## 核心类清单(java/com/example/soundtransferlower/ 下 15 个 .java 文件)

> 其中 2 个 Fragment(ChatFragment、MineFragment)和 1 个 Adapter 是 MainActivityNew 的**静态内部类**;TalkbackFragment 内部也自带一份 DeviceListAdapter。

| 类名 | 职责 | 关键方法 | 调用关系 |
|---|---|---|---|
| **MainActivityNew** | 唯一 Activity。顶栏状态显示、底部三 Tab(对讲/聊天/我的)、Fragment 栈管理、设备选择对话框、来电接听/拨号/挂断的全局通话管理 | `onCreate`、`switchToFragment`、`startCall/endCall/dialCall`、`onCallRequest/Accepted/Rejected/HungUp`、`showDeviceSelectionDialog`、`startReconnectTask` | 绑定并注册到 `BluetoothService`(实现 MessageCallback);创建 `BluetoothFinder`;通话时使用 `AudioRecorderPlayer`;加载 `ChatWorkFragment/TalkbackFragment/CallFragment/ChatFragment/MineFragment` |
| **BluetoothService** | 核心前台 Service。管理 RFCOMM 连接三线程(Accept/Connect/Connected)、消息协议解析(TXT:/CALL:/FILE_REQUEST: 等前缀)、消息落盘、前台通知、召唤通知、WakeLock+闹钟+亮屏保活、30s 健康检查 | `start/connect/connected/stop/write`、`setConnectionRole`、`AcceptThread/ConnectThread/ConnectedThread`、`handleTextMessage`、`saveMessageToFile/loadChatHistory`、`showCallNotification` | 被 MainActivityNew、ChatWorkFragment、TalkbackFragment、BootReceiver、自身闹钟广播拉起;通过 `MessageCallback` 回调所有注册者;使用 `AlarmManagerHelper` |
| **BluetoothFileTransferService** | 独立文件传输 Service,使用独立 UUID(`fa87c0d0-...`)建立第二条 RFCOMM 通道;发送方发 [4字节长度][2字节名长][文件名][数据],接收方按协议解析落盘 | `onStartCommand`(SEND/RECEIVE)、`ConnectedThread.run`(收发协议主体)、进度回调 `notifyProgress` | 由 ChatWorkFragment 在文件请求被接受后启动并绑定;回调 `FileTransferCallback` 给 ChatWorkFragment |
| **ChatWorkFragment** | 聊天主界面(文字+文件+语音)。绑定两个 Service,处理 FILE_REQUEST/ACCEPT/REJECT 握手、召唤、消息去重、历史记录读写/导出/删除、语音录制与播放、文件打开/保存到公共目录 | `sendMessage/doSendTextMessage`、`sendFileRequest/handleFileRequest`、`startFileSend/pauseBluetoothAndStartFileReceive`、`onMessageReceived`(协议分发)、`onTransferComplete`、`loadChatHistory/updateChatHistoryFile`、`playVoice` | 实现 `BluetoothService.MessageCallback` 和 `FileTransferCallback`;使用 `VoiceRecorder`、`MessageAdapter`;转发呼叫事件给 MainActivityNew |
| **TalkbackFragment** | 实时对讲界面。设备列表、PTT 按钮、外放/听筒切换、半双工状态机(IDLE/TALKING/RECEIVING)、50 秒无活动自动断开、对讲模式下自动拒绝文件请求 | `startTalking/stopTalking`、`setState`、`sendAudioData`、`onTalkbackDataReceived`、`checkInactivity` | 实现 `AudioRecorderPlayer.AudioDataSender` 与 `BluetoothService.MessageCallback`;通过 `AudioRecorderPlayer` 收发 Opus 帧 |
| **CallFragment** | 通话中界面。显示对方名称、通话时长计时、挂断、免提切换 | `updateDuration`、`toggleSpeaker` | 由 MainActivityNew.startCall 创建;挂断回调 MainActivityNew.endCall |
| **MainActivityNew.ChatFragment**(内部类) | "聊天"Tab:已配对设备列表,点击连接进入 ChatWorkFragment,未配对则反射调 `createBond` 发起配对 | `onCreateView`、`pairDevice` | 使用 MainActivityNew 的配对设备列表与 `connectToDeviceForChat` |
| **MainActivityNew.MineFragment**(内部类) | "我的"Tab:修改蓝牙设备名、关于对话框(版本号) | `showNameDialog`、`setBluetoothName`、`showAboutDialog` | 操作 BluetoothAdapter |
| **AudioRecorderPlayer** | 实时语音引擎:AudioRecord 采集 8kHz 单声道 PCM → Concentus Opus 编码(16kbps, 40ms 帧)→ 回调发送;接收方向:Opus 解码 → 10 帧阻塞队列 → 独立播放线程写 AudioTrack | `startRecording/stopRecording`、`playAudio`、`release` | 被 TalkbackFragment(对讲)和 MainActivityNew(通话)使用 |
| **VoiceRecorder** | 语音消息引擎:录音时把每帧 Opus 数据加 2 字节大端长度头写入 .opus 文件;播放时按长度头逐帧解码播放,带开始/结束回调 | `startRecording/stopRecording`、`playVoice/stopPlayback`、`RecordRunnable` | 仅被 ChatWorkFragment 使用 |
| **Message** | 消息实体:文本/图片/文件/语音四种类型,含 equals/hashCode(供历史去重) | 多个构造器、`isImageFile` | 被 ChatWorkFragment、MessageAdapter 使用 |
| **MessageAdapter** | RecyclerView 适配器,8 种 viewType(收发 × 文本/图/文件/语音),AsyncTask 加载图片缩略图 | `getItemViewType`、`onBindViewHolder`、`ThumbnailTask` | ChatWorkFragment 的消息列表 |
| **DeviceListAdapter**(顶层) | 蓝牙设备 ArrayAdapter(设备名+MAC) | `getView` | 基本未被使用(MainActivityNew 与 TalkbackFragment 各自内置了同名内部类) |
| **BluetoothFinder** | 蓝牙扫描封装:注册 ACTION_FOUND 接收器、管理已配对/已扫描/重复设备三个列表 | `startScan/stopScan`、`fetchPairedDevices` | MainActivityNew 的设备选择对话框使用 |
| **BootReceiver** | 开机广播 → 拉起 BluetoothService | `onReceive` | Manifest 注册 |
| **AlarmManagerHelper** | 用 `setRepeating(ELAPSED_REALTIME_WAKEUP)` 每 30 分钟发自定义广播重启服务 | `startAlarm/cancelAlarm` | BluetoothService 保活使用 |

---

## 架构与数据流

### 整体组织

```
┌──────────────────────── MainActivityNew(唯一 Activity)────────────────────────┐
│ 顶栏(返回 / 连接状态 / 菜单)                                                  │
│ ┌───────────────────────── fragment_container ─────────────────────────────┐ │
│ │ TalkbackFragment │ ChatWorkFragment │ CallFragment │ ChatFragment │ Mine  │ │
│ └──────────────────────────────────────────────────────────────────────────┘ │
│ 底部 Tab:[对讲] [聊天] [我的]                                                 │
└──────────────────────────────────────────────────────────────────────────────┘
        │ bind + MessageCallback 注册(多 callback 广播)
        ▼
┌── BluetoothService(前台 Service,常驻保活)──┐   ┌── BluetoothFileTransferService ──┐
│ UUID 00001101(SPP):文本+控制信令+实时语音 │   │ UUID fa87c0d0:独立文件传输通道   │
│ AcceptThread / ConnectThread / ConnectedThread│   │ AcceptThread / ConnectThread /   │
│ 协议:TXT: 前缀=文本;无前缀=Opus 音频帧      │   │ ConnectedThread(SEND/RECEIVE)    │
└───────────────────────────────────────────────┘   └──────────────────────────────────┘
```

UI 层全部运行在 MainActivityNew 内部,通过 `switchToFragment` + 回退栈切换;所有组件都向 BluetoothService 注册回调(CopyOnWriteArrayList 广播),Fragment 销毁时注销。

### 链路 1:文本聊天

```
发送方 ChatWorkFragment.sendMessage
  → BluetoothService.write("TXT:"+消息)          [MODE_CHAT]
  → ConnectedThread.outputStream.write
  → 本机 saveMessageToFile(chat_<MAC>.txt, "我")
────────────────────────── RFCOMM ──────────────────────────
接收方 ConnectedThread.run 读到字节
  → isTextMessage() 检查 "TXT:" 前缀
  → handleTextMessage:区分 CALL_REQUEST/CALL_ACCEPT/.../FILE_REQUEST/普通文本
  → saveMessageToFile("对方") + notifyMessageReceived
  → MainActivityNew(Toast+存历史) & ChatWorkFragment(上屏/触发文件接收流程)
```

控制协议(均以 `TXT:` 为前缀的字符串):
`CALL_REQUEST:<名字>`、`CALL_ACCEPT`、`CALL_REJECT`、`CALL_HANGUP`、`CALL:<名字>`(旧版"召唤",弹通知)、`FILE_REQUEST:<文件名>,<大小>[,VOICE,<时长>]`、`FILE_ACCEPT`、`FILE_REJECT`。

### 链路 2:文件/语音消息传输

```
ChatWorkFragment(发) --FILE_REQUEST--> 对端 ChatWorkFragment
对端 handleFileRequest:语音→自动接受;文件→弹框确认
对端回 FILE_ACCEPT,并启动 BluetoothFileTransferService(RECEIVE,监听独立 UUID)
发送方收到 FILE_ACCEPT:
  → bluetoothService.stop()          ★ 主通道暂停,避免占串口
  → 启动 BluetoothFileTransferService(SEND,主动连独立 UUID)
  → 协议:[int 文件长度][short 文件名长][文件名 UTF-8][文件字节流]
  → 双方 onProgressUpdate → MainActivityNew 顶栏显示进度/速度
完成后 onTransferComplete → 恢复主 BluetoothService(发送方 500ms 后主动重连)
```

语音消息复用同一条链路,只是 FILE_REQUEST 带 `,VOICE,<秒数>` 标记,文件为 VoiceRecorder 生成的 `.opus`(自定义帧格式:每帧 `2字节大端长度 + Opus 数据`)。

### 链路 3:实时对讲 / 通话

```
TalkbackFragment(或通话中的 MainActivityNew)
  按下对讲 → AudioRecorderPlayer.startRecording
    AudioRecord(8kHz/mono/PCM16) 每 40ms 一帧(640B)
    → OpusEncoder(16kbps) → AudioDataSender.sendAudioData
    → BluetoothService.write(opus帧, MODE_TALKBACK)   ★ 无前缀,裸 Opus
────────────────────────── RFCOMM ──────────────────────────
接收方 ConnectedThread:isTextMessage 失败 → 当前 mode==TALKBACK
  → notifyTalkbackDataReceived
  → TalkbackFragment / MainActivityNew(通话中)
  → AudioRecorderPlayer.playAudio → OpusDecoder
  → PCM 入 10 帧阻塞队列(满则丢最旧帧)→ 播放线程写 AudioTrack(STREAM_VOICE_CALL)
```

通话流程(`dialCall` → 选设备 → `CALL_REQUEST:<我名>` → 对方弹"来电"对话框 → `CALL_ACCEPT` → 双方 `startCall`:切 MODE_TALKBACK + 启动 AudioRecorderPlayer + CallFragment 计时 → `CALL_HANGUP` 结束)。实际上"通话"就是包装了拨号信令的全双工版对讲。

### 保活机制

```
BootReceiver(BOOT_COMPLETED) ──┐
AlarmManagerHelper(30min 重复闹钟)──┼──→ startService(BluetoothService) ──→ START_STICKY
亮屏广播(ACTION_SCREEN_ON) ──┘
BluetoothService 自身:前台通知 + PARTIAL_WAKE_LOCK + 30s 健康检查(发现未监听则重新 start)
```

---

## UI 界面清单(res/layout + Fragment)

| 布局文件 | 用于 | 内容 |
|---|---|---|
| `activity_main_new.xml` | MainActivityNew 主容器 | 顶栏(返回/状态/菜单)+ `fragment_container` + 空提示 + 底部三 Tab(对讲/聊天/我的) |
| `activity_main.xml` | **TalkbackFragment** 对讲页(注意:名字带 activity 实为 Fragment 布局) | 状态文本、已配对设备 ListView、刷新/外放切换/按下对讲/断开/拨号/蓝牙设置按钮 |
| `activity_chat.xml` | **ChatWorkFragment** 聊天页 | 顶栏(返回/设备名/菜单)+ RecyclerView 消息列表 + 输入框/发送/语音/更多按钮 |
| `fragment_chat.xml` | ChatFragment(聊天 Tab 首页) | "蓝牙设备"标题 + 配对设备 ListView |
| `fragment_mine.xml` | MineFragment(我的 Tab) | "名称"(改蓝牙名)、"关于"两个按钮 |
| `fragment_call.xml` | CallFragment(通话页) | 对方名称、通话时长(等宽大字体)、免提/挂断按钮 |
| `fragment_empty.xml` | 空占位 | 未使用(空提示已内嵌在 activity_main_new 的 emptyHint) |
| `item_main.xml` / `device_list_item.xml` | 设备列表项 | 头像文字 + 设备名 + MAC(item_main 有 avatar;device_list_item 似乎冗余) |
| `item_message_sent/received[\_image/_file/_voice].xml` | 聊天气泡 8 种 | 对应 MessageAdapter 的 8 个 viewType,配合 `bubble_sent/received`、`bg_message_sent/received` 圆角背景 |
| `item_message.xml` | 旧版消息项 | 疑似遗留未用 |
| `popup_menu_horizontal.xml` | 长按消息弹窗 | 复制 / 保存 / 删除 |
| `menu/main_menu.xml` | 主界面菜单 | 配对添加、刷新设备列表、选择设备 |
| `menu/chat_menu.xml` | 聊天页菜单 | 导出聊天记录、删除聊天记录 |
| `menu/chat_more_menu.xml` | 聊天页"+"菜单 | 对讲、发送文件、召唤 + 代码动态加的"拨号" |
| `raw/ringtone.amr` | 铃声资源 | (召唤/来电提示音素材) |

values:`strings.xml`(app_name="纸条",about 含作者/开源地址/免责声明)、`colors.xml`、`styles.xml`(CustomDialogTheme、BottomNavButton)、`themes.xml` + `values-night`。

---

## 顶层两个成品包

| 文件 | 判断 |
|---|---|
| `Denoised_Soundtransferlower_2.0.4_ReleaseDenoised_Soundtransferlower_2.0.4_Release.zip`(约 4.4 MB) | 老版本 **2.0.4 的"降噪(Denoised)"定制版**发布压缩包(文件名重复了两遍,应是打包脚本拼接问题)。当前源码已是 2.9.3fix,此 zip 为历史成品 |
| `Statusbar_Soundtransferlower1.0.zip`(约 1.5 MB) | 老版本 1.0 的**"状态栏(Statusbar)"定制变体**发布包 |
| 另:`app/release/app-release.apk`(约 1.6 MB) | 当前源码的正式构建产物 |

三个成品版本号(1.0 / 2.0.4 / 2.9.3fix)与不同特性名(状态栏版、降噪版)表明该项目按"基础版 + 定制变体"方式迭代分发。

---

## 后续开发注意事项 / 潜在问题

1. **【最严重】Opus 依赖缺失,项目当前无法编译。**
   `AudioRecorderPlayer` 和 `VoiceRecorder` import `org.concentus.*`(Concentus),但 build.gradle 没声明该依赖,`app/libs` 目录也不存在。需要添加 `implementation 'org.concentus:concentus:1.0.2'`(Maven Central)或放入 jar,否则两台设备即使装上也无法编解码语音。发布 APK 能运行说明历史上 libs 里有 jar,源码包不完整。

2. **support 库 vs AndroidX 配置冲突。**
   代码全部使用 `android.support.*`(appcompat-v7:24.0.0,2016 年),而 `gradle.properties` 设置 `android.useAndroidX=true`,且 `libs.versions.toml` 里声明了 androidx appcompat 1.7.0 / material 1.12.0 却未在 app 模块引用。`useAndroidX=true` 会导致 support 包依赖解析错乱(Manifest 里的 `android.support.v4.content.FileProvider` 也会受影响)。要么删掉 useAndroidX 或加 `android.enableJetifier=true`,要么整体迁移 AndroidX。

3. **Manifest 与 Gradle 配置打架 + 过低 targetSdk 的合规风险。**
   Manifest 里手写 `<uses-sdk minSdk=19 targetSdk=19>` 会**覆盖** Gradle 的 minSdk 7(实际效果=min 19);`uses-sdk` 本不应出现在 Manifest。targetSdk=19 在 AGP 8.5 下能编,但 Google Play 早已禁止上架,且新系统行为(后台启动 Activity、前台服务类型、附近设备权限)全部按旧模式豁免——属于"钻兼容性空子",Android 14+ 上蓝牙运行时权限(BLUETOOTH_CONNECT 等)不申请的话,部分机型会直接 SecurityException 崩溃;`RECORD_AUDIO` 等运行时权限只有 TalkbackFragment 做了申请,聊天页录音(VoiceRecorder)没有申请逻辑。

4. **通讯协议脆弱,容易粘包/误判。**
   - 文本靠 `TXT:` 前缀区分,音频是裸 Opus 流;一旦 TCP 式粘包(一次 read 读到"音频+TXT 文本"或半条文本),就会整包丢弃或把音频当文本/反之。
   - 缓冲区只有 1024 字节,单条文本消息过长会被截断。
   - `FILE_REQUEST:` 参数用逗号分隔,**文件名含逗号即解析错乱**;文件长度用 int 传输,>2GB 溢出(与 UI 层宣称的 5000MB 上限矛盾)。
   - 双 UUID 方案在文件传输时 `bluetoothService.stop()` 主通道,传输期间所有聊天/对讲能力中断,且对方若恰在此时发消息会丢失。

5. **其他明显坏味道。**
   - **重复造轮子**:`DeviceListAdapter` 存在 3 份(顶层文件 + MainActivityNew 内部类 + TalkbackFragment 内部类);`connectToDeviceForChat` 与 `connectToDeviceForChatAndNavigate` 近乎完全重复;顶层 `DeviceListAdapter.java`、`item_message.xml`、`device_list_item.xml`、`fragment_empty.xml` 疑似死代码。
   - **资源文件乱码**:多个 menu/layout XML 里的中文 title 是 GBK 被按 UTF-8 读取的乱码(如 `menu_pair` 的 title 显示为乱码字符),构建后菜单文字会是乱码,需要修复编码。
   - 配对用反射 `createBond`(hide API),国产 ROM 上可能失败。
   - `MessageAdapter.ThumbnailTask` 用了已废弃的 `AsyncTask`,且没有 LruCache,快速滚动会重复解码。
   - 聊天记录用"每设备一个 txt + 全量重写"的方式持久化,记录多时 `updateChatHistoryFile` 每次删除消息都全文重写,O(n²) 且并发写无锁(BluetoothService 的写文件与 ChatWorkFragment 的重写会互相覆盖——Service 追加写入、Fragment 整文件重写,两套写逻辑不一致,存在丢记录风险)。
   - MainActivityNew 中大量 `new Handler()`/`postDelayed` 嵌套(扫描流程四层嵌套),生命周期一旦变化容易 NPE 或泄漏;`commitAllowingStateLoss` 全线使用说明状态保存时序本来就乱。
   - 硬编码:召唤通知 ID、健康检查 30s、对讲 50s 无活动断开、10 秒来电超时、MAC 地址字符串比较决定主从(`localAddress.compareTo(remoteAddress) > 0`,不配对场景 MAC 随机化时会不稳定)等。
   - `app_name` 显示为"纸条"而项目名叫 Soundtransferlower,manifest label 与各类日志 TAG 不一致,排查问题时注意。
