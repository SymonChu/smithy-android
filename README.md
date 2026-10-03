# Smithy

> 手机上的 APK 工程工作台 + AI 助手 —— 拆包、改造、重铸、装机，全在一台手机上闭环。

**状态：设计阶段**（本仓库当前只有设计文档、依赖清单与核心接口定义，尚无可用构建）

## 它是什么

一个 Android App，把三件事合在一起：

1. **改包链路**（借鉴 MT管理器）：解包 → 改 smali/资源 → 回编 → 签名 → 装机，不用电脑
2. **零门槛改包**（借鉴 CAssistant）：全维度解析报告、图标/文案/包名可视化批量替换
3. **AI 原生**（借鉴 CodeForge）：AI 不是聊天窗，而是直接调用 `dex.search_string` / `smali.patch` / `apk.rebuild` / `apk.install` 干活，写操作前有门控确认

### 两个关键设计

- **AI 与手动共用同一套 Tool**：AI 改了什么，用户在「改动」列表里看到同样的 diff，并能单次回退。不存在"AI 偷偷改了东西"。
- **工具集以 MCP 服务端暴露**：App 内置的 APK 能力（`127.0.0.1/mcp`）可被外部 AI（Claude / Cursor / Trae）接入；同时它自己也是 MCP 客户端，能挂 Frida、抓包等外部工具。

## 技术要点

- **纯 Java 引擎，不需要内置 Linux 环境**：`ARSCLib` + `APKEditor` + `smali/dexlib2` + `jadx-core(Android 版)` + `apksig-android` 直接在 Android 进程内完成解包/回编/签名，App 体积目标 < 40MB（对比同类方案动辄 2-3GB 的内置 rootfs）
- 解包/回编/签名跑在独立 `:worker` 进程，避免主进程 OOM
- 增量回编：未改动的条目复用原字节，改一个字符串不必重编全部资源

## 目录

```
docs/          设计文档（从 docs/README.md 进）
core/engine    APK 引擎：解包/回编/签名/资源/DEX（纯 Java，可 JVM 单测）
core/fs        文件与 zip 抽象
core/ai        Agent 循环、模型客户端、上下文压缩、记忆
core/mcp       MCP 服务端 + 客户端
toolkit        语义化工具集（UI 与 AI 共用的唯一入口）
feature/*      APK 工作台 / 文件管理 / 对话 / 设置
gradle/libs.versions.toml   依赖与版本（标注了哪些版本已核实、哪些待核）
```

## 构建

需要 **JDK 17** + **Android SDK（platform-35 / build-tools 35.0.0）**。

```bash
./gradlew :app:assembleDebug        # 出 debug APK
./gradlew :core:engine:test         # 引擎层是纯 JVM 模块，可脱离 Android 单测
```

首次构建会拉 AGP / Kotlin / Compose / ARSCLib 等依赖（几百 MB）。

`app` 模块与工程骨架已就位（M0 的骨架部分）；功能实现进度见 `docs/06-milestones.md`。

## 贡献

- 提交 PR 前请先开 issue 对齐设计，避免大改动返工
- **首次贡献需要签署 CLA**（见 `CONTRIBUTING.md`）—— 用于保留将来双授权的可能，请在动手前确认你接受这一点
- 引擎层是纯 Java，优先级最高的贡献是**兼容性用例**：附上"打不开的包"和期望行为

## 许可

**GPL-3.0**（见 [LICENSE](LICENSE)）。

选它的原因：本项目直接借鉴了同样以 GPL-3.0 发布的 `AppManager` 等项目的实现，且这类工具被盗版闭源转卖的动机很高。

第三方组件许可（Apache-2.0 / BSD-3 / LGPL-2.1 等）在 App 内「开源许可」页由 `AboutLibraries` 自动生成。

## 合规声明

本工具仅供**你自己拥有或已获授权**的应用使用，用于学习、技术研究与自用修改。请勿用于破解商业付费软件、盗取数据、绕过他人系统的安全机制或盗版分发。使用者需自行承担合规责任。
