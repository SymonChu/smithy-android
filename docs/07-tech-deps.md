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
