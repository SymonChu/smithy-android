# Smithy

> 手机上的 APK 工程工作台 + AI 助手 —— 拆包、改造、重铸、装机，在一台手机上闭环。

**版本 0.1.1** — 代码层与资源层改包全链路已打通（解析 → 搜代码 → 改字符串/smali/资源 → 增量回编 → 签名验签 → 装机），带 AI 助手、文件管理器、Magisk 模块工程与按需下载的原生工具链；296 个自动测试兜底。

---

## 它解决什么

改一个 APK，通常要在几个工具之间来回倒：一个负责拆包改 smali，一个负责换图标改文案，想让 AI 帮忙还得把包传到电脑上。

Smithy 把这些合成一个 App，并且**让 AI 直接动手**——不是只在旁边聊天，而是真的调用解包、改包、重打包、装机的工具，而你随时能看到它改了什么、能一键退回去。

顺带把「手机上编译」也做通了：装一个按需下载的 clang 工具链，就能在本机把 `.so` 从源码编出来，不用电脑。

---

## 功能

### 一、APK 解析

打开一个 APK 就得到完整画像：

- **基本信息** — 包名、版本号/版本名、minSdk / targetSdk、大小、dex 数量
- **权限与组件** — 全部声明权限；Activity / Service / Receiver / Provider 列表，标出**已导出**的和带 IntentFilter 的
- **签名** — v1/v2/v3 方案、证书主体与颁发者、MD5 / SHA1 / SHA256 指纹、是否 debug 签名
- **DEX 统计** — 每个 dex 的类数 / 方法数 / 字符串数，方法数超 65536 预警
- **加固识别** — 常见壳的特征匹配与置信度（给证据，不是猜）
- **结构浏览** — dex / res / assets / lib / META-INF，直接操作 zip 内部，不解压

### 二、代码层编辑

- **DEX 搜索** — 按类名 / 方法名 / 字符串 / 方法调用（支持正则）。最常用的是搜硬编码的 URL、密钥、文案
- **交叉引用** — 找「谁调用了这个方法」
- **反编译看 Java** — 单类按需反编译（不全量，手机上全量必 OOM）；dex 按会话缓存，第二个类不用重新加载
- **smali 查看与编辑** — 语法高亮、按方法跳转、精确改写
- **批量改 smali** — 正则替换、批量删类

### 三、资源层编辑

- **改应用名 / 包名 / 版本号** — 一步到位
- **换图标** — 给一张图，自动生成 mdpi ~ xxxhdpi 全套 + adaptive icon
- **批量换文案** — ARSC 字符串表 + 布局 XML 一起替换，支持正则
- **批量换图片 / 改颜色 / 替换 assets**
- **AXML 编辑** — 二进制 XML 解码、改属性（如 debuggable、exported）

### 四、打包与装机

- **重打包** — 增量回编（没改过的条目复用原字节，改一个字不必重编全部资源）
- **对齐** — zipalign 等价实现，`.so` 保 16KB 页对齐
- **签名** — v1/v2/v3；内置 keystore 自动生成，也可导入你自己的
- **装机** — Shizuku 静默安装 → Root → 系统安装器三级降级
- **进度可见** — 解析资源 → 汇编 smali → 打包 → 对齐 → 签名，分阶段可取消

### 五、改动管理

每次修改都留一条记录：改了什么、谁改的（你或 AI）、AI 为什么改、前后 diff。

- **单次回退** — 不是「全部撤销」，是精确回退某一次改动
- **改动配方** — 把一串改动存成配方，下次对同版本包一键复用
- **AI 与手动共用同一套** — AI 改了什么，你在同一个列表里看到同样的 diff 并能回退，不存在「AI 偷偷改了东西」

### 六、AI 助手

- **Agent 循环** — 多轮工具调用、流式输出、随时可中断
- **门控确认** — 写操作前 AI 必须说明「要做什么、影响什么」，你回 GO 才执行；可切只读模式
- **自定义模型** — 任意 OpenAI 兼容端点（自带 key，只存本机），多套配置随时切换
- **地址怎么填都能用** — 填主机、填到 `/v1`、或直接粘整个接口路径都行（会归一成同一条请求）。局域网 `http://` 网关直接支持（Android 默认禁明文，这里放开了；公网明文会给一条提醒），自签证书可在设置里单独勾「跳过 TLS 校验」
- **网关不会流式也能用** — 上游收下 `stream: true` 却回一整段 JSON 是常见事，这时自动降级成一次性请求，而不是报「连不上」
- **多会话 + 记忆** — 会话独立上下文，超长自动压缩；跨会话长期记忆
- **49 个语义化工具** — UI 与 AI 共用同一套入口，工具粒度是 `dex.search_string` / `smali.patch` / `apk.rebuild` 这种，不给 AI 万能 shell 口子

### 七、开放接口

- **把自身能力做成 MCP 服务端** — App 内起 `127.0.0.1/mcp`，暴露 `apk.*` / `dex.*` / `smali.*` / `arsc.*` / `module.*` / `component.*` 等工具。**外部 AI（Claude / Cursor / Trae）可以直接调用它来改包**
- **同时是 MCP 客户端** — 可挂 Frida、抓包、数据库等外部 MCP

### 八、文件管理

- **双窗口** — 上下两栏，目录同步滚动、路径一键对齐、一键交换
- **多标签** — 最多 6 个浏览位置并存，各标签独立；zip 内未保存的改动会拦你一下
- **zip / apk / tar / tar.gz 内部直接增删改** — 不解压再打包。7z / rar 会明确告诉你不支持（解析器太重），而不是「点了没反应」
- **编辑器** — smali / XML / JSON 语法高亮、大文件、正则搜索替换、编码自动识别、十六进制编辑
- **批量重命名、哈希计算、文件属性、文本对比**
- **Root 通道** — 有 root 时可读写全机文件系统（含 `/data/adb/modules`）、改权限 / 属主

### 九、模块工程

不止 APK —— 刷机模块（Magisk / Zygisk）也能在这个工作台里拆开、改、重打包、刷回去：

- **模块识别与检视** — 解析 `module.prop`，列出文件树、脚本与 ABI 覆盖情况，并比对设备实际支持的 ABI
- **脚本与配置编辑** — `service.sh` / `post-fs-data.sh` / `system.prop` / `sepolicy.rule` / `customize.sh`
- **重打包** — 按模块规范布局重新打成可刷 zip
- **刷入与启停** — 走 Root 通道刷入，支持启用 / 停用 / 标记卸载 / 立即删；改完可软重启 zygote（危险操作有二次确认）
- **从零新建模块** — 骨架生成 + 勾选「要 Zygisk 源码」
- **native 库检视与等长替换** — 查看 `.so` 的架构、依赖（ELF NEEDED）与字符串常量，支持**等长**替换
- **在手机上编 `.so`** — `jni/` 下的 C/C++ 源码 → `zygisk/<abi>.so`，三条路径：免 root 的 bionic 工具链、Alpine rootfs + chroot、或导入本地工具链包
- **Xposed 模块** — 本质是 APK，直接走代码层编辑 + 重签名 + 装机链路

### 十、扩展中心（按需下载）

不装任何扩展时 App 约 27MB（debug 包；release 更小）。需要时才下：**设置 → 扩展**

- **C/C++ 工具链（clang, aarch64）** — 约 154MB，装后约 370MB。手机上直接跑，免 root。装完 zygisk 模块就能在手机上编出 `.so`
- **Alpine rootfs（aarch64）** — 约 4MB。一个 Linux 用户态，可以在里面装构建工具（走 chroot 那条路时需要）
- **Android sysroot（arm64-v8a）** — 只从官方 NDK 里取 bionic 头与桩库（约 54MB），交叉编 so 的必要条件
- **导入本地包** — 电脑传过来的工具链包直接从「导入本地包…」进

三条纪律：**有 sha256 才下**（镜像 failover 时照样逐个地址验）、**归档里的 `..` 与绝对路径一律丢掉**、**失败必须说清下一步**。断点续传中途断了不从头来。

> 扩展不会自动更新：上游换版本后，清单里的 sha256 对不上就会判定为「没装」，需要重装。

---

## 开发状态

| 模块 | 状态 |
|---|---|
| 工程骨架 / Gradle 多模块 / CI | ✅ 可用 |
| APK 解析与报告页 | ✅ |
| 代码层编辑（dex 搜索 / 字符串改写 / smali / jadx） | ✅ |
| 资源层（列 / 改资源、批量换文案、换图标） | ✅ |
| 二进制 XML（解码 / 改任意元素属性） | ✅ |
| 打包链路（增量回编 / 对齐 / v1+v2+v3 签名 / 验签） | ✅ |
| 装机（Shizuku / Root / 系统安装器，自动降级） | ✅ |
| 改动管理（改动记录 / 单次回退 / 配方） | ✅ |
| AI 层（Agent 循环 / 门控 + 信任模式 / 会话落盘 / 上下文压缩 / 消息级 diff） | ✅ |
| 文件管理（多标签 / zip 直改 / 文本与十六进制编辑 / root 通道 / 批量改名） | ✅ |
| 模块工程（Magisk / Zygisk 拆改打包刷入 / 从零新建） | ✅ |
| 原生编译（`jni/` → `zygisk/<abi>.so`，三条路径） | ✅ 代码完成，**真机编出 `.so` 未验** |
| 扩展中心（下载 / 校验 / 解包 / 卸载 / 导入本地包） | ✅ 代码完成，**真机未验** |
| MCP 服务端 + 客户端 | ✅ |
| 网络存储（FTP 已实现；SMB/SFTP/WebDAV 未做） | 部分 |

**296 个单元测试**（引擎 67 / 文件与 zip 154 / AI 21 / 图标集 3 / 工具 37 / 扩展状态 14）。

真机验收清单见 [docs/09-verify-checklist.md](docs/09-verify-checklist.md)。

---

## 技术要点

- **纯 Java 引擎，不内置 Linux 环境**：`ARSCLib` + `APKEditor` + `smali/dexlib2` + `jadx-core(Android 版)` + `apksig-android` 直接在 Android 进程内完成解包 / 回编 / 签名
- **重活跑独立进程**：解包 / 汇编 smali / 回编 / 签名在独立进程执行，拿独立堆上限，主进程不会因大包 OOM 被杀
- **增量回编**：未改动条目复用原字节
- **v1 签名自己实现**：`apksig` 只做 v2/v3；v1 三个文件（`MANIFEST.MF` / `SMITHY.SF` / `SMITHY.RSA`）手写，保证 `minSdk 21` 的包也能装
- **原生工具链用 Termux 的包**：官方没有 arm64-Android 的 clang（NDK 只有宿主机版，LLVM 官方也没有 android 目标），而 Termux 的包是原生 aarch64 + bionic 的，解到应用目录就能跑

## 目录

```
docs/          设计文档（从 docs/README.md 进）
core/engine    APK 引擎：解包/回编/签名/资源/DEX（不 import android.*）
core/fs        文件与 zip 抽象、tar 解析、可选组件（扩展）管理
core/ai        Agent 循环、模型客户端、上下文压缩、记忆
core/mcp       MCP 服务端 + 客户端
core/design    设计系统：色板/字阶/圆角/间距 token、图标集、通用零件
toolkit        语义化工具集（UI 与 AI 共用的唯一入口）
feature/apk    工作台：概览/代码/资源/改动/模块
feature/files  文件管理器、文本与十六进制编辑器
feature/chat   对话页与 AI 设置
feature/settings  设置壳、扩展中心
tools/         推拉脚本（github.com 不通时用 API 逐字节重建提交）
```

## 构建

需要 **JDK 17** + **Android SDK（platform-35 / build-tools 35.0.0）**。

```bash
./gradlew :app:assembleDebug              # 出 debug APK
./gradlew :core:engine:testDebugUnitTest # 引擎单元测试（JVM 上跑，不需要设备）
./gradlew testDebugUnitTest              # 全量
```

部分测试要一个真实 APK 作样本，用环境变量传（**必须是绝对路径**）：

```bash
SMITHY_TEST_APK=/abs/path/app-debug.apk ./gradlew testDebugUnitTest
```

没设时那些用例会**跳过**（不是假装通过）。

首次构建会拉 AGP / Kotlin / Compose / ARSCLib 等依赖（几百 MB）。

## 贡献

- 提交 PR 前先开 issue 对齐设计，避免大改动返工
- **引擎接口（`Tool` / `ApkProject` / `PatchRecord` / `WorkspaceState`）的改动必须先讨论**——UI 层与 AI 层同时依赖它们
- **首次贡献需要签署 CLA**（见 [CONTRIBUTING.md](CONTRIBUTING.md)）
- 最有价值的贡献是**兼容性用例**：附上打不开 / 回编失败 / 装机闪退的包（可脱敏）+ 错误日志

## 许可

**GPL-3.0**（见 [LICENSE](LICENSE)）。第三方组件（Apache-2.0 / BSD-3 / LGPL-2.1 等）的许可在 App 内「开源许可」页由 `AboutLibraries` 生成。

扩展中心下载的组件各自带许可（clang/llvm 为 Apache-2.0 with LLVM exception，Alpine 各组件含 GPL-2.0 的 busybox，NDK 以 Apache-2.0 为主）——App 内逐条如实标注。

## 合规声明

本工具仅供**你自己拥有或已获授权**的应用使用，用于学习、技术研究与自用修改。请勿用于破解商业付费软件、盗取数据、绕过他人系统的安全机制或盗版分发。使用者需自行承担合规责任。