# AGENTS.md — AI 协作指南

本文件为 AI 助手（Claude Code / Cursor 等）修改本项目时的必读指南。

## 项目概览

- **MusicHapticsX**：Android Xposed/LSPosed 音乐触觉引擎模块
- **原理**：Hook 目标 App 的 `AudioTrack`（PCM 写入）→ 实时音频分析（低频/鼓点/旋律）→ 语义合成（Haptic Composer）→ 设备级马达驱动（LRA 建模）→ 震动
- **包名**：`com.mouya.musichaptics`
- **模块机制**：libxposed API 102（`META-INF/xposed/module.prop` + `java_init.list` + `MODULE_SETTINGS` activity-alias + `XposedProvider`）

## 构建环境（Windows 已配置）

```bash
export JAVA_HOME="D:/Program Files/Java/jdk-26.0.2"
./gradlew.bat assembleDebug   # 调试版（多 dex，仅调试用）
./gradlew.bat assembleRelease # 发布版（R8 混淆，交付用）
```

- SDK：项目内 `local-sdk/`（compileSdk 37、build-tools 37.0.0、NDK r27）
- AGP 9.3.1 / Kotlin 2.4.10 / Gradle 9.7.0（wrapper）
- 镜像：settings.gradle.kts 阿里云 google/gradle-plugin + 腾讯 maven-public

## 架构地图

```
MainHook.kt                 ← XposedModule 入口（onPackageLoaded → hook AudioTrack）
├── 纯 libxposed API（XposedModule + hook(Executable) + Hooker/Chain + 反射）
├── 禁用 de.robv（XposedHelpers 等 legacy 类）——R8 混淆会导致 NoClassDefFoundError
├── AudioTrack 构造器/play/write hook → PCM 捕获
└── NativeBridge / HapticEngine（震动引擎）
HapticEngine.kt             ← 核心引擎（PCM→分析→合成→驱动）
├── MusicStructureAnalyzer  ← 音乐结构（energy/section/confidence）
├── HapticComposer          ← 语义合成（命令队列→震动原语）
├── HapticTimeline          ← 时间线渲染（振幅/时长）
├── VibrateProxy            ← 马达驱动（IPC/直接）
└── LogBroadcaster          ← 遥测（广播到 App UI）
HapticDashboardActivity.kt  ← App UI（设置/频谱/触觉动态/遥测）
ConfigProvider.kt           ← content:// provider（跨进程偏好快照）
NativeBridge.kt / native/   ← C++ DSP（分频/音高/缓冲）
```

## 关键实现细节

### Hook 链路（MainHook.kt）

1. `onPackageLoaded` → `findClassSafe("android.media.AudioTrack")`（反射，**不用 XposedHelpers**）
2. `hook(ctor)` 构造器 → `hookMethods(clazz, "write", Hooker)` 捕获 PCM
3. PCM → `HapticEngine.processPcm()` → native 分析 → 震动输出
4. `hookLog()` 写文件日志（`/sdcard/Download/MusicHapticsX/hook.log`，MediaStore）+ logcat

### 偏好同步（阈值/模式等）

- 注入进程通过 `content://com.mouya.musichaptics.provider` 快照模块 App 的 `haptic_settings` prefs
- 该 URI 必须与 `AndroidManifest.xml` 的 provider authorities 一致（改包名时同步改）

### 震动模式与阈值

- 模式：`KICK`（鼓点）/ `BASS_COMP`（低音包络）/ `SMART`（默认）
- 阈值：`haptic_threshold`（0-1，默认 0）→ `adjustedAmplitudes` 过滤（只影响渲染输出，不影响 energy 分析）
- 推荐：鼓点模式 + 阈值 0.7

## 修改红线

1. **不要引入 de.robv legacy 类**（XposedHelpers/XposedBridge/XC_MethodHook）——R8 混淆后 lspd 无法解析，注入直接失败
2. **libc++_shared.so 必须保留在 jniLibs/arm64-v8a/**（libhaptic-engine.so 动态依赖；已 16KB 对齐）
3. **native .so 必须 16KB 对齐**（LOAD Align 0x4000，Android 15+ 要求）
4. **包名/authorities/广播 action 三处同步**：`build.gradle.kts applicationId`、`AndroidManifest.xml`、代码常量（ACTION_LOG/CONFIG_SYNC_PERMISSION/CONFIG_PROVIDER_URI）
5. **注入进程的 Hooker 语义**：after 逻辑 = `val result = chain.proceed()` 后再处理，返回 `result`；before 逻辑 = 处理后再 `return chain.proceed()`
6. **R8 keep**：`proguard-rules.pro` 保留 `MainHook`、libxposed、de.robv 类

## 测试验证

- 设备：无线 adb（用户手机，地址可变）
- 注入验证：`/sdcard/Download/MusicHapticsX/hook.log`（hookLog 文件日志）或 logcat `MusicHapticsX-Hook` tag
- lspd 日志：`/data/adb/lspd/log/verbose_*.log`（模块加载错误）
- 修改后：构建 release → 安装 → 管理器确认启用+勾选 B站 → 强停 B站重开 → 放歌验证震动

## 版本记录

- v4.2：纯 libxposed 102 重写（修复注入）、libc++_shared、偏好快照、energy 动态增强
