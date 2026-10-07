# 07 · 技术栈与依赖清单（含许可证与合规）

依赖坐标来自 `MuntashirAkon/AppManager` 的生产配置——它就是在 Android 上做 APK 编辑的成熟项目，说明**这套组合在手机上真能跑**，不用自己摸索。

## 引擎层（**:core:engine**）

| 用途 | 坐标 | 许可 | 备注 |
|---|---|---|---|
| 资源表/二进制XML | `com.github.REAndroid:ARSCLib` | Apache-2.0 | 纯 Java 读写 `resources.arsc`，替代 aapt2 |
| APK 解包回编 | `com.github.REAndroid:APKEditor` | Apache-2.0 | aapt 无关；也可只借它的 engine API |
| smali 汇编/反汇编 | `com.android.tools.smali:smali` + `:smali-baksmali` | BSD-3 | dexlib2 在内 |
| dex→Java | `com.github.MuntashirAkon.jadx:jadx-core` + `:jadx-dex-input` | Apache-2.0 | **Android 适配版**，比原版 jadx 更适合手机 |
| 签名 | `com.github.MuntashirAkon:apksig-android` | Apache-2.0（上游） | 官方 apksig 的 Android 移植 |
| 加密/证书 | `org.bouncycastle:bcprov-jdk15to18` / `bcpkix-jdk15to18` | Bouncy Castle（类 MIT） | 生成 keystore、证书解析 |
| 文件类型嗅探 | `com.j256.simplemagic:simplemagic` | Apache-2.0 | 判断真实文件类型 |
| zstd | `com.github.luben:zstd-jni` | Apache-2.0 | 部分 APK/资源用 zstd |

## Android 基础层

| 用途 | 坐标 | 许可 |
|---|---|---|
| 免 root 提权 | `dev.rikka.shizuku:api` + `:provider` | Apache-2.0 |
| root 提权 | `com.github.topjohnwu.libsu:core` | Apache-2.0 |
| 隐藏 API 绕过 | `org.lsposed.hiddenapibypass:hiddenapibypass` | Apache-2.0 |
| SAF/文档树 | `androidx.documentfile:documentfile` | Apache-2.0 |
| 数据库 | `androidx.room:room-runtime` + `:room-ktx` | Apache-2.0 |
| UI | Compose BOM + Material3 + Navigation | Apache-2.0 |
| 序列化 | kotlinx-serialization | Apache-2.0 |

## 编辑器（**:feature:apk / :feature:files**）

开源形态下**首选 sora-editor（LGPL-2.1）**——LGPL 对开源项目完全兼容（只要以未修改的库依赖形式引用 + 许可页列出），功能是同类里最强的：smali/XML 高亮、增量渲染、代码折叠。

| 方案 | 许可 | 取舍 |
|---|---|---|
| **sora-editor**（推荐） | LGPL-2.1 | 体验最好、省 2-3 周；与 GPL/Apache 项目均兼容 |
| CodeMirror 6 in WebView | MIT | WebView 内存开销大、输入法体验略逊；仅当最终选 Apache-2.0 且不想碰 LGPL 时用 |
| 自研 Compose 编辑器 | 自有 | 2-3 周，仅在前面都不满意时考虑 |

## AI 层（**:core:ai / :core:mcp**）

| 用途 | 选型 | 许可 |
|---|---|---|
| HTTP 客户端 | OkHttp + okio | Apache-2.0 |
| SSE 流式 | okhttp-sse | Apache-2.0 |
| JSON | kotlinx-serialization-json | Apache-2.0 |
| MCP | 自研（JSON-RPC 2.0 over HTTP/SSE），或 `modelcontextprotocol/kotlin-sdk`（若可用） | — |
| 本地模型（可选） | llama.cpp（NDK 编译）或 `llama.rn` 思路 | MIT |
| 语音（可选） | `com.alphacephei:vosk-android` | Apache-2.0 |

## 模块工程层（M6）

| 用途 | 选型 | 许可 | 备注 |
|---|---|---|---|
| 模块刷入 / root shell | `com.github.topjohnwu.libsu:core`（已在基础层） | Apache-2.0 | 模块场景**必须 root**，Shizuku 不适用 |
| zip 读写 | JDK 自带 `java.util.zip` | — | 模块 zip 就是普通 deflate zip，无特殊结构要求 |
| ELF 解析 | 自研最小实现（ELF 头 + 节表 + 字符串表，32/64 位小端） | 自有 | 只做只读检视与**等长**字符串替换；不引第三方 binutils |
| 刷入目标约定 | `module.prop` / `zygisk/<abi>.so` / `disable` / `remove` 标记 | — | Magisk 模块规范，非依赖 |
| 可选组件下载（`:core:fs`） | OkHttp + okio | Apache-2.0 | 续传（Range）、跟随跳转、进度回调。原只在 `:core:ai` / `:core:mcp` 用，M5 的 `AddOnManager` 也用上了 |
| rootfs（下载项，不进主包） | Alpine minirootfs 3.20（aarch64） | 各组件各自带许可（含 GPL-2.0 的 busybox 等） | `component.install id=rootfs-alpine` 按需下（3.9MB，sha256 对着上游的 `.sha256` 校验过）。它作为**独立程序**被用户下进来使用，不改变 App 自身许可；装/卸都在 App 里 |
| Zygisk API 头 | `zygisk.hpp`（来自官方模块样例工程） | **0BSD** | **已核实**：措辞为 "permission to use, copy, modify, and/or distribute this software for any purpose with or without fee is hereby granted"，最宽松档，**与 GPL-3.0 完全兼容**。分发须保留版权声明，且头文件内明写 `DO NOT MODIFY ANY CODE IN THIS HEADER`。**现已随 zygisk 骨架分发**：原样放在 `core/fs/src/main/resources/dev/smithy/fs/zygisk.hpp`，`ModuleScaffoldTest` 用 SHA-256 钉住「一字未改」（要更新就换成官方原件） |
| native 编译（M6-B，可选模块） | clang + Android sysroot + libc++ | Apache-2.0 with LLVM exception | 约 300-400MB，**按需下载，不进主包**。调用侧只依赖 `NativeToolchain` 接口（找不到就说清缺什么），工具链二进制放哪儿、怎么下、许可页怎么写，属下载器那一批（M5）的活 |
| **手机上跑的工具链**（路线 C） | Termux 的 bionic 包闭包：`clang` + `ndk-sysroot` + `ndk-multilib-native-stubs`（含 llvm / lld / libllvm / libcompiler-rt / libc++） | clang/llvm/lld/libllvm/libcompiler-rt = Apache-2.0 with LLVM exception；libc++ = MIT/UIUC；其余依赖见包内 copyright | 由 `tools/fetch-termux-toolchain.sh` 在电脑上组装，**约 110MB 的 tar.gz**（装后约 400MB），传进手机导入。**刻意不含 `make`（GPL-3.0）**：这条路不需要它，且要避免 GPL 义务。闭包里另有 LGPL 的 `libiconv`、双许可的 `zstd` 等 —— **要对外分发这份 bundle 之前，必须先过一遍许可**（附上各包 copyright、给出对应源码获取方式）。目前只在用户自己的设备上用（自己下载、自己传），不构成对外分发 |

**Zygisk API 版本对应**（写模板时需声明并在编译期校验）：

| API | 最低 Magisk |
|---|---|
| v5 | 27000 |
| v4 | 26000 |
| v3 | 24300 |
| v2 | 24000 |

**设备侧前置条件（M6 开工前先做兼容性矩阵）**：
- Magisk 内置 Zygisk：Magisk 27+ 自带，设置里开关
- 独立实现（Zygisk Next / ReZygisk）：KernelSU ≥ 10940（ksud ≥ 11575）；Magisk ≥ 26402 且**必须关闭内置 Zygisk**
- 仅 Shizuku、无 Root：**Zygisk 模块无法工作**，工具须明确报错而非静默失败

---

## ⚠️ 许可证：开工前第一个必须定的事

开源许可直接决定**你能不能用 GPL 生态里现成的实现**——这个决定值几周工期：

| 选择 | 能用 GPL 组件/代码 | 后果 |
|---|---|---|
| **GPL-3.0**（推荐） | ✅ | 可直接复用 AppManager 的 APK 编辑链路；且能防"别人拿你代码做闭源版" |
| AGPL-3.0 | ✅ | 同上，另防 SaaS 白嫖（本项目是本地 App，用不上） |
| MPL-2.0 | ❌ | 文件级传染，折中；GPL 组件仍不能用 |
| Apache-2.0 | ❌ | 最宽松，但别人可拿你的代码做闭源商业竞品 |

**选 GPL-3.0 额外拿到的**（实打实的工期收益）：
- `MuntashirAkon/AppManager` — APK 编辑链路、Shizuku 集成、文件管理的成熟实现，可借鉴/取用
- `apk-editor/APK-Explorer-Editor` — APK 编辑 UI 流程
- `MuntashirAkon/unapkm-android` — APKM 支持（P1 直接解决）

对比：**Apache-2.0 → MVP 6-8 周；GPL-3.0 → 约 4-6 周**（省下来的正是这三块）。
注意 GPL-3.0 的代价：将来若想做闭源商业版，需要 CLA（版权集中）才能双授权；且不能上 App Store。

### 无论选哪个都要做的
- `AboutLibraries`（Apache-2.0）自动生成许可页，别手写
- 保留依赖的版权声明与 `NOTICE`（Apache-2.0）、署名与免责（BSD-3，且不得用作者名义背书）
- sora-editor（LGPL-2.1）：以未修改库依赖引用 + 许可页列出即可，无需开源你的代码

## 构建与验证

- 目标：`compileSdk 35`、`minSdk 26`（Android 8.0，覆盖 Shizuku 使用场景）、`targetSdk 35`
- ABI：`arm64-v8a`（主），可选 `armeabi-v7a`
- **多模块 + 独立 `:worker` 进程**（解包/回编/签名放这儿，拿独立堆）
- 单元测试：engine 层是纯 Java，可直接 JVM 测试（不需要模拟器）——**这是先在 JVM 上验证引擎的依据**
- 体积目标：不装可选模块 < 40MB

## 参考项目（读思路）

| 项目 | 许可 | 用途 |
|---|---|---|
| `ATRI5201314/mt-ai-assistant` | MIT | AI 驱动 MT MCP 的安卓客户端，**先读这个** |
| `AAswordman/Operit` | LGPL-3.0 | 安卓 AI Agent 的成熟形态（工具调用/UI） |
| `REAndroid/APKEditor`、`ARSCLib` | Apache-2.0 | 引擎基座 |
| `darbra/awesome-ai-reverse` | — | AI+逆向工具清单 |
