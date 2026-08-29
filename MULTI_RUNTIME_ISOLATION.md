# Android Spine 多运行时隔离架构设计 (Multi-Runtime Isolation Architecture)

本项目采用了与 **Spine Viewer for Android** 和 **Live2DViewerEX** 相同的业界标准方案——**多运行时隔离（Multi-Runtime Isolation）**。

---

## 🎯 为什么需要“多运行时隔离”？

官方 Spine 运行时存在以下历史限制：
1. **版本强兼容性断代**：Spine 3.8 与 Spine 4.1 / 4.2 的 `.skel` 二进制结构及 JSON 格式存在根本差异（如 3.8 使用 Long 骨骼哈希 + 直列字符串，4.0+ 使用多段字符串表 + 贝塞尔曲线变换插值）。
2. **Java 包名冲突**：官方各个版本的 `spine-libgdx` 库在 Maven Central 上的类全限定名完全一样（均为 `com.esotericsoftware.spine.*`）。
3. **ClassLoader 冲突**：如果在一个 Android 进程中同时引入两个官方 Spine 依赖，JVM / Android ART 虚拟机只能加载最先出现的类，导致低版本或高版本模型加载时抛出 `VerifyError`、`NoSuchMethodError` 或奔溃。

---

## 🏗️ 架构分层与设计模式

```
                          [ .skel / .json 模型文件 ]
                                     │
                                     ▼
                ┌──────────────────────────────────────────┐
                │        SpineVersionDetector.kt           │
                │ (嗅探文件头 Magic Bytes / JSON spine 版本字段) │
                └────────────────────┬─────────────────────┘
                                     │
                        识别出 SpineVersion (V38/V40/V41/V42)
                                     │
                                     ▼
                ┌──────────────────────────────────────────┐
                │       SpineMultiRuntimeManager.kt        │
                │        (动态运行时调度与工厂注册中心)          │
                └──────┬────────────┬────────────┬─────────┘
                       │            │            │
       ┌───────────────┘            │            └───────────────┐
       ▼                            ▼                            ▼
┌──────────────┐             ┌──────────────┐             ┌──────────────┐
│ :spine-      │             │ :spine-      │             │ :spine-      │
│ runtime-v38  │             │ :spine-      │             │ runtime-v42  │
│ 模块         │             │ runtime-v41  │             │ 模块         │
│ (包名:       │             │ (包名:       │             │ (包名:       │
│  .spine.v38) │             │  .spine.v41) │             │  .spine.v42) │
└──────┬───────┘             └──────┬───────┘             └──────┬───────┘
       │                            │                            │
       └────────────────────────────┼────────────────────────────┘
                                    │
                                    ▼
                ┌──────────────────────────────────────────┐
                │          ISpineModelAdapter.kt           │
                │        (核心统一抽象接口 :spine-core-bridge)  │
                └───────────────────┬──────────────────────┘
                                    │
                                    ▼
                ┌──────────────────────────────────────────┐
                │         SpineWallpaperService.kt         │
                │     (OpenGL ES 2.0 / 3.0 动态壁纸引擎)     │
                └──────────────────────────────────────────┘
```

---

## 📦 模块构成清单

| 模块名称 | 职责 | 隔离包名 | 支持格式 |
|---|---|---|---|
| `:spine-core-bridge` | 统一接口、版本探测器、调度工厂 | `com.spine.wallpaper.bridge.*` | 纯接口定义，无 Spine 依赖 |
| `:spine-runtime-v38` | Spine 3.8.99 官方运行时独立封装 | `com.esotericsoftware.spine.v38.*` | 明日方舟、蔚蓝档案、碧蓝航线等 3.8 / 3.7 模型 |
| `:spine-runtime-v40` | Spine 4.0.64 官方运行时独立封装 | `com.esotericsoftware.spine.v40.*` | 4.0.xx 曲线格式模型 |
| `:spine-runtime-v41` | Spine 4.1.24 官方运行时独立封装 | `com.esotericsoftware.spine.v41.*` | Spineboy 官方 4.1 标准模型 |
| `:spine-runtime-v42` | Spine 4.2.18 官方运行时独立封装 | `com.esotericsoftware.spine.v42.*` | 4.2 物理约束与动画分立时间轴模型 |
| `:app` | Android 桌面动态壁纸服务与 Compose UI | `com.spine.wallpaper.*` | 调度所有模块，提供预览与壁纸设置 |

---

## 🔧 如何在 Android Studio 中构建与验证

1. 打开 Android Studio，打开根目录。
2. 运行 Gradle 构建：`./gradlew assembleDebug`。
3. 动态壁纸服务运行时：
   - 当用户从相册或 ZIP 导入任意 Spine 模型时，`SpineVersionDetector` 自动判断其骨骼版本。
   - `SpineMultiRuntimeManager` 动态分发至对应的 `:spine-runtime-vXX` 模块。
   - 彻底告别 ClassLoader 冲突，实现“一个 App，完美兼容所有版本”！
