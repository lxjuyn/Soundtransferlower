# Soundtransferlower — MD3 风格新页面开发计划

## 目标

为"纸条"（Soundtransferlower）项目新增一个 **Material Design 3 风格的新页面**，同时满足：
- **低消耗**：不引入重量级新依赖，纯手写 MD3 视觉规范
- **兼容 Android 4.0.3+（API 15）**：不使用 AndroidX material3 库（该库要求 minSdk 21+），而是用 support library + 自定义主题/drawable 实现 MD3 视觉效果

## 前提：修复编译问题（必做前置）

项目当前**无法编译**，必须先修：

1. **添加 Opus 依赖** — 在 `app/build.gradle` 中添加 Concentus Opus（Maven Central 或本地 jar）
2. **修复 AndroidX 冲突** — `gradle.properties` 中 `android.useAndroidX=true` 与 support 库冲突，需设为 `false`
3. **修复 XML 中文编码** — 部分 menu/layout XML 的中文是 GBK 被当 UTF-8 读取，需修复编码
4. **清理 Manifest 中的 uses-sdk** — 移除 Manifest 中手动声明的 `<uses-sdk>`，统一由 Gradle 管理
5. **统一 minSdk 为 15** — Manifest 删掉 uses-sdk 后，build.gradle 里 minSdk 改为 15（Android 4.0.3）

## MD3 视觉规范映射（API 19 兼容实现）

### 1. 色彩系统（MD3 Color Tokens）

在 `res/values/colors.xml` 中定义：

```
MD3 Token          →  实现值（示例）
─────────────────────────────────────
primary            →  #1A6B52 (绿主色，呼应蓝牙主题)
on-primary         →  #FFFFFF
primary-container  →  #A8F2DC
on-primary-container → #00201A
secondary          →  #4A635B
secondary-container→  #CCE8DE
tertiary           →  #406376
tertiary-container →  #C3E8FD
surface            →  #F5FBF7
surface-variant    →  #DAE5E0
on-surface         →  #171D1A
on-surface-variant →  #3F4945
outline            →  #6F7975
outline-variant    →  #BFC9C4
error              →  #BA1A1A
```

在 `res/values-night/` 下定义暗色主题对应色。

### 2. 形状系统（MD3 Shape Tokens）

创建 shape drawable：

| Token | 文件 | 角度 |
|---|---|---|
| extra-small | `shape_corner_extra_small.xml` | 4dp |
| small | `shape_corner_small.xml` | 8dp |
| medium | `shape_corner_medium.xml` | 12dp |
| large | `shape_corner_large.xml` | 16dp |
| extra-large | `shape_corner_extra_large.xml` | 28dp |
| full (药丸) | `shape_corner_full.xml` | 50% |

### 3. 排版（MD3 Typography）

在 `res/values/styles.xml` 中定义：
- MD3DisplayLarge/Medium/Small
- MD3HeadlineLarge/Medium/Small
- MD3TitleLarge/Medium/Small
- MD3BodyLarge/Medium/Small
- MD3LabelLarge/Medium/Small

基于 AppCompat 主题，设置对应字体大小、字重、行高。

### 4. 层次（MD3 Elevation）

MD3 不用传统阴影，改用 **tonal elevation**（在 surface 上叠加半透明 primary 色）：
- Level 0: surface（无叠加）
- Level 1: surface + 5% primary
- Level 2: surface + 8% primary
- Level 3: surface + 11% primary

用自定义 drawable background 实现（LayerList + 半透明色 + shape background）。

## 新页面设计：「设置」Fragment

选择新增一个 **设置/关于** 页面作为 MD3 风格展示页，原因：
- 设置页是功能独立的新页面，不影响现有聊天/对讲/通话核心逻辑
- 可充分展示 MD3 组件：卡片、开关、滑块、列表项、顶栏
- 用户可感知（有实际用途），不是纯 demo

### 页面布局

```
┌─────────────────────────────────┐
│  MD3 TopAppBar（surface + tonal） │
│  ← 返回    设置                   │
├─────────────────────────────────┤
│  ┌─ 蓝牙设置卡片 ──────────────┐  │
│  │  设备名称: [改名]           │  │
│  │  自动重连:    [开关 MD3]    │  │
│  │  超时断开:    [50s]         │  │
│  └─────────────────────────────┘  │
│                                   │
│  ┌─ 通知设置卡片 ──────────────┐  │
│  │  召唤提醒:    [开关]        │  │
│  │  振动反馈:    [开关]        │  │
│  └─────────────────────────────┘  │
│                                   │
│  ┌─ 关于卡片 ──────────────────┐  │
│  │  版本: 2.9.3fix             │  │
│  │  作者: 秋元                 │  │
│  │  开源: gitee.com/yonstus    │  │
│  └─────────────────────────────┘  │
└─────────────────────────────────┘
```

### 涉及文件（新建/修改）

| 操作 | 文件 | 说明 |
|---|---|---|
| **新建** | `fragment_settings.xml` | 设置页布局 |
| **新建** | `SettingsFragment.java` | 设置页逻辑 |
| **新建** | `res/drawable/shape_corner_*.xml` × 6 | MD3 形状 |
| **新建** | `res/drawable/bg_md3_card.xml` | MD3 卡片背景（tonal elevation） |
| **新建** | `res/drawable/bg_md3_switch_track.xml` | MD3 开关轨道 |
| **新建** | `res/drawable/bg_md3_switch_thumb.xml` | MD3 开关滑块 |
| **新建** | `res/drawable/bg_md3_button.xml` | MD3 填充按钮 |
| **新建** | `res/drawable/bg_md3_top_app_bar.xml` | MD3 顶栏背景 |
| **修改** | `res/values/colors.xml` | 添加 MD3 色彩 tokens |
| **修改** | `res/values/styles.xml` | 添加 MD3 排版/主题样式 |
| **修改** | `res/values-night/themes.xml` | 暗色主题 MD3 色彩 |
| **修改** | `MainActivityNew.java` | 添加"设置"入口（菜单或 tab） |

### 依赖对比

| 项目 | 之前 | 之后 | 变化 |
|---|---|---|---|
| 库依赖 | support-v7:24 + design:24 | 不变 | +0 |
| 新 jar | 无 | Concentus（修编译用） | +1（必须） |
| 新资源 | 0 | ~15 drawable/xml | 极小 |
| APK 体积影响 | - | +<100KB | 可忽略 |

## 实施步骤（Workflow 拆分）

### Phase 1: 修复编译（Sonnet，简单修复）
1. 添加 Concentus 依赖到 build.gradle
2. 设 `android.useAndroidX=false`
3. 修复 XML 编码
4. 清理 Manifest uses-sdk
5. 验证 assembleDebug 通过

### Phase 2: MD3 基础设施（Sonnet，中等任务）
1. 创建 6 个 shape corner drawable
2. 更新 colors.xml 加入 MD3 token 色
3. 更新 styles.xml 加入 MD3 排版
4. 创建 MD3 组件背景 drawable（card/switch/button/topbar）
5. 创建暗色主题支持

### Phase 3: 新页面开发（Opus，重要中等任务）
1. 创建 fragment_settings.xml 布局
2. 创建 SettingsFragment.java
3. 集成到 MainActivityNew（添加入口）
4. 验证编译通过

### Phase 4: 验证（Haiku，简单验证）
1. 在模拟器上运行
2. 截图验证亮色/暗色主题
3. 检查资源文件编码

## 风险与注意事项

1. **Concentus 依赖**：Maven Central 上的包名可能是 `org.concentus:concentus`，需确认可用性；若不行，需从发布 APK 中提取 jar
2. **support 库限制**：CardView 等组件在 support 库中有，可用；但某些 MD3 特效（如 MaterialShapeDrawable）需 AndroidX，不可用
3. **API 19 兼容**：Ripple 效果需 API 21+，需提供 ripple-v19 fallback（用 selector 替代）
4. **暗色主题**：API 19 不支持系统级暗色模式切换，需在应用内手动切换
