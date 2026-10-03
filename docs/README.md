# Smithy · 设计文档索引

手机上的「APK 工程工作台 + AI 助手」。取自 MT管理器（改包链路）、CAssistant（零门槛 + 可视化改包）、CodeForge（AI Agent + 平台化能力）。

## 阅读顺序

| 文档 | 内容 | 什么时候看 |
|---|---|---|
| [00-overview](00-overview.md) | 定位、取谁的长处、明确不做什么 | 先看 |
| [01-scope-requirements](01-scope-requirements.md) | 需求清单 P0/P1/P2（A~G 七组） | 定范围时 |
| [02-architecture](02-architecture.md) | 模块划分、关键接口、进程与内存策略 | 动手前 |
| [03-data-model](03-data-model.md) | 领域实体、Room 表、工作区目录、状态机 | 动手前 |
| [04-mcp-tools](04-mcp-tools.md) | 工具集清单与设计原则 | 写 toolkit 时 |
| [05-ui](05-ui.md) | 四个 Tab 的结构与关键交互 | 写 UI 时 |
| [06-milestones](06-milestones.md) | M0~M6 任务与验收标准 | 排期时 |
| [07-tech-deps](07-tech-deps.md) | 依赖坐标 + 许可证合规 | 建工程时 |
| [08-risks](08-risks.md) | 风险、未知、**待你决策的 6 个问题** | 现在就看 |

## 一句话结论

技术上可行：引擎层用 `ARSCLib + APKEditor + smali/dexlib2 + jadx-core(Android 版) + apksig-android`，**全是纯 Java 库，可在 Android 进程内跑，不需要内置 Linux 环境**。MVP（打开包→搜 dex→改 smali→重打包签名→装机→AI 全程驱动）**约 4-6 周**（选 GPL-3.0 可复用 GPL 生态的现成实现；选 Apache-2.0 则 6-8 周）。

## 当前状态

- [x] 可行性调研与许可证核实
- [x] 需求、架构、数据模型、工具集、UI、里程碑、依赖、风险
- [x] 形态决策：**开源项目**
- [ ] **待决策：开源许可证（D2b）— 开工前必须定**
- [ ] 待决策：rootfs 模块（D3）/ 工作区位置（D4）/ AI 是否 BYOK（D5）/ 项目名（D6）
- [ ] 定死四个核心接口（见 [08](08-risks.md) 第五节）
- [ ] M0 开工

## 形态：开源项目

据此已做的调整：
- 编辑器改用 **sora-editor（LGPL-2.1）**——开源形态下兼容，比自研省 2-3 周
- 若选 **GPL-3.0**，可直接复用 AppManager 的 APK 编辑链路、APKM 支持等，**MVP 从 6-8 周降到约 4-6 周**
- 分发：GitHub Releases + F-Droid / IzzyOnDroid（不再依赖商店上架）
- 开源形态的可持续性与维护风险见 [08](08-risks.md) 第七节
