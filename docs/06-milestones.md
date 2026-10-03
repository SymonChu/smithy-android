# 06 · 里程碑与验收标准

每个里程碑都必须能**独立验收**——做完能看见东西，而不是"写了一半的架构"。

---

## M0 · 骨架 + 只读分析（1 周）

**交付物**：能装到手机上的 APK 分析器。
**任务**
1. 建 Gradle 工程与模块骨架（见 02），跑通空壳编译出 APK
2. 接入 `:core:engine`：ARSCLib + APKEditor，实现 `ApkProject.open/list/read`
3. 接入 `:core:fs`：SAF 选文件 + 私有工作区
4. 实现工具：`workspace.open/status`、`apk.meta/list/manifest/signatures/dex_stats`
5. 工作台页「概览」标签：把上述数据渲染成报告

**验收**：选一个 50MB 以内的正常 APK，10 秒内出报告：包名、版本、权限数、组件数、dex 类/方法数、签名指纹、大小分布；打开 100MB 的包不崩（超时给明确提示）。

---

## M1 · 代码层编辑 + 打包闭环（2 周）

**交付物**：改一个字符串并成功装回手机。
**任务**
1. `dex.search_class/method/string`（基于 smali/dexlib2）
2. `jadx.decompile_class`（按需单类，Android 版 jadx-core）
3. `smali.read_class/read_method/patch/replace_string` + PatchRecord 落库
4. `apk.rebuild`（增量：未改动 entry 复用原字节）+ `apk.align`
5. `apk.sign`（apksig-android，v1/v2/v3，内置 keystore 自动生成）
6. Shizuku 集成 + `apk.install`
7. 工作台「代码」标签 + 「改动」标签（diff 视图、单次回退）

**验收**：搜到某 App 的开屏文案 → 改掉 → 重打包 → 签名 → 用 Shizuku 静默装回手机 → 启动看到新文案。全程不碰电脑，单个操作 60 秒内完成（50MB 包）。

---

## M2 · 资源层 + 可视化改包（2 周）

**交付物**：换图标、改应用名、改包名、批量换文案。
**任务**
1. `arsc.list/get/set/replace_string`
2. `axml.decode/patch`、`manifest.set`
3. `icon.replace`（自动生成 mdpi~xxxhdpi + adaptive icon 全套）
4. `asset.put/delete`、`zip.*` 直改
5. 工作台「资源」标签：图标预览、字符串表可搜可批量替换
6. 分析报告可导出 Markdown

**验收**：一个不含 adaptive icon 的老包，替换图标后装机显示正常不变形；批量替换 200 条文案耗时 < 30 秒；导出报告含全部 A1-A5 数据。

---

## M3 · AI 层（2 周）

**交付物**：说人话完成一次改包。
**任务**
1. `:core:ai`：模型客户端（OpenAI 兼容、流式 SSE）、Agent 循环（多轮 tool calling、可中断）
2. `:toolkit`：把 M1/M2 的工具全部注册，附 JSON schema
3. 门控：WRITE/DESTRUCTIVE 弹确认条；信任模式开关
4. 会话持久化（Room）、上下文压缩、消息级 diff 展示
5. 对话 Tab 完整交互（工具卡片、思考链折叠、附件选包）

**验收**：输入"把这个包的应用名改成 XX，图标换成这张图，然后装到手机"，AI 自主完成 4 步并成功装机；中途点取消能立刻停下且工作区状态一致；上下文超限时自动压缩且不丢任务目标。

---

## M4 · 文件管理 + 编辑器（4 周）

**交付物**：日常可替代 MT管理器 的文件操作。
**任务**：双窗口 + zip 直改 + sora-editor 编辑器 + 批量重命名 + 哈希/属性 + 长按浮动菜单

**验收**：在 App 内不改用其他工具完成一次"从 QQ 收到 .apk.1 → 改名 → 装 → 打开为工程 → 改文案 → 重打包 → 装机"。

---

## M5 · 平台化（持续）

可选模块：rootfs（Alpine，跑 apktool/任意 CLI）、构建模块（JDK+Gradle）、隧道（cloudflared）、本地模型（llama.cpp）、语音（Vosk+TTS）、定时任务、MCP 服务端对外、插件系统。

**验收**：模块可独立下载/卸载，不装模块时 App 体积 < 40MB。

---

## 关键路径与风险点

```
M0(骨架) → M1(引擎+装机) → M2(资源) → M3(AI)
                ↑
        最大不确定性：ARSCLib/APKEditor 在 Android 上的长尾兼容
```
- 若 M1 的 rebuild 在某些包上失败率 > 20%，**立即插入 M1.5**：接 rootfs+apktool 作为兜底路径（这时 rootfs 从 P2 提前）
- M3 依赖 M0-M2 的稳定工具接口，所以**先定 Tool 接口再写实现**，别边写边改签名
