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
6. Shizuku 集成 + `apk.install` —— **已实现**：引擎定降级顺序（Shizuku → Root → 系统安装器），
   App 层给三条通道。Shizuku 走 `IShizukuService.newProcess` + `pm install -S`，见 `docs/08` 第九节
7. 工作台「代码」标签 + 「改动」标签（diff 视图、单次回退）

**验收**：搜到某 App 的开屏文案 → 改掉 → 重打包 → 签名 → 用 Shizuku 静默装回手机 → 启动看到新文案。全程不碰电脑，单个操作 60 秒内完成（50MB 包）。

---

## M2 · 资源层 + 可视化改包（2 周）

**交付物**：换图标、改应用名、改包名、批量换文案。
**任务**（状态截至最后一次提交）
1. ✅ `arsc.list/set/replace_string` —— 含批量入口与作用域，见 `docs/08` 第十节
2. 🔶 `manifest.set` 已做（应用名 / 包名 / 版本名 / 版本码 / debuggable）；
   `axml.decode/patch` 未做 —— 现在改清单只走 ARSCLib 的结构化字段，任意 xml 节点的增删改还没开
3. 🔶 `icon.replace` 只做了传统 PNG 图标（mdpi~xxxhdpi）；**adaptive icon 未做**
4. ⬜ `asset.put/delete`、`zip.*` 直改（引擎的 `writeEntry`/`deleteEntry` 已经够用，缺的是 UI 暴露）
5. 🔶 工作台「资源」标签已做（列资源 / 改单条 / 批量换文案 / 换图标按钮）；图标预览未做
6. ⬜ 分析报告导出 Markdown

**adaptive icon 为什么没做**：它不是一个 PNG，而是 `mipmap-anydpi-v26/ic_launcher.xml`
声明「前景层 + 背景层」两张图。前景层有安全区 —— 108dp 的画布里只有中间 72dp 保证可见，
系统还会按启动器做视差与裁切。把用户给的图当整块前景塞进去，在启动器上会被裁掉一圈，
**正是验收要避免的「变形」**。做对需要单独设计前景/背景的生成规则（按安全区缩放并留边距），
留下一步做。目前遇到 adaptive icon 的包会明确报错，而不是换一半留下个不一致的图标。

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

可选模块：rootfs（Alpine，跑 apktool/任意 CLI）、构建模块（JDK+Gradle）、native 构建模块（clang+sysroot，见 M6-B）、隧道（cloudflared）、本地模型（llama.cpp）、语音（Vosk+TTS）、定时任务、MCP 服务端对外、插件系统。

**验收**：模块可独立下载/卸载，不装模块时 App 体积 < 40MB。

---

## M6 · 模块工程（Magisk / Zygisk）（2 周）

> 编号排在 M5 之后，只表示它**不在 MVP 关键路径上**，不表示最晚做。它只依赖 M1 的归档与装机能力，可在 M2 之后的任意时点插入，不阻塞其他里程碑。

**交付物**：工作台能处理 APK 之外的另一种包——刷机模块。

**先看清楚一个 Zygisk 模块是什么**（来自官方样例工程的布局）：

```
<module_id>/
├── module.prop          纯文本：id / name / version / versionCode / author / description
└── zygisk/
    ├── arm64-v8a.so     ← Magisk 按【文件名】匹配 ABI，不是 lib<name>.so
    ├── armeabi-v7a.so
    ├── x86.so
    └── x86_64.so
```

可选部分：`service.sh` / `post-fs-data.sh`（开机脚本）、`system/`（overlay）、`system.prop`、`sepolicy.rule`、`customize.sh`（安装期脚本）；目录里放一个空文件 `disable` 即停用，放 `remove` 即卸载。

**任务（A 档：结构层）**
1. 归档层**复用既有 `zip.*`**（04 已定义），不另造 `archive.*`；模块 zip 与 APK 共用同一实现
2. `module.open` + `module.inspect` —— 打开模块 zip 为工作区；用 `module.prop` 判定模块、用 `zygisk/*.so` 判定 Zygisk 模块；解析元数据、列出脚本与 ABI 覆盖，并与设备实际 ABI 比对（缺当前 ABI 的 .so 要显式警告）
3. `module.prop_get` / `module.prop_set` —— 结构化读写 id / 名称 / 版本 / 描述；改 id 时校验目录名与 id 一致
4. `module.package` —— 按模块规范布局重打包成可刷 zip
5. 脚本与配置编辑 —— `service.sh` / `post-fs-data.sh` / `system.prop` / `sepolicy.rule` / `customize.sh`，走 `zip.read` + `fs.write` + `zip.put`
6. `module.install` —— 走 **Root 通道**刷入。**此场景 Shizuku 权限不足，只有 Root 一档有效**；另含 `module.enable` / `module.disable`（增删 `disable` 标记）、`module.remove`（写 `remove` 标记，下次重启卸载，比直接删目录安全）、`module.uninstall`
7. `zygote.restart` —— 软重启使模块生效；**DESTRUCTIVE 门控 + 显式确认**（信任模式下也不免确认），并提示会影响所有正在运行的应用
8. `elf.inspect` / `elf.strings` / `elf.patch_string` —— 解析 ELF 节表与字符串表，展示架构 / 依赖 / 字符串常量；替换**强制等长**，变长直接拒绝
9. 工作台新增「模块」标签：元数据、文件树、脚本编辑、ABI 覆盖表

**A 档明确不做**
- **改 `.so` 的逻辑**（反汇编 → 改 → 回编）：逆向产物没有源码语义，回编不成立，不做半成品
- **变长字符串替换**：会破坏 ELF 内的偏移引用与段布局，风险远大于收益；正确路径是源码重编（M6-B）
- **改动 `zygisk.hpp` 本身**：头文件里明写 `DO NOT MODIFY ANY CODE IN THIS HEADER`

**验收**
- 解出一个 Zygisk 模块 → 改 `module.prop` 的版本与描述、改 `service.sh` 里一行 → 重打包 → 刷入 → 软重启 → 模块列表显示新版本且脚本确实生效
- 等长字符串替换后模块仍能正常加载，ABI 目录结构未被破坏
- 变长替换被明确拒绝，并提示走源码重编
- 设备无 Root 时，所有写操作给出明确原因，而不是静默失败
- Xposed 模块（本质是 APK）走 M1 链路即可完成，不需要 M6 的能力

---

## M6-B · native 编译（可选模块，归入 M5 下载项）

改模块逻辑的唯一正当路径是**改源码重编**，不是二进制硬改。

**任务**
1. 以 M5 的「构建模块」为底座，追加 `clang` + Android `sysroot` + `libc++`（约 300-400MB，按需下载，不进主包）
2. 附 Zygisk 模块骨架模板：`module.prop` + `CMakeLists.txt` + `zygisk.hpp`（0BSD）+ 最小 `ModuleBase` 实现
3. 目标 ABI 以 `arm64-v8a` 为主
4. 模板声明所依赖的 Zygisk API 版本并在编译期校验（v5 → Magisk 27000+，v4 → 26000+，v3 → 24300+）
5. 分发 `zygisk.hpp` 时保留原版权声明、不改动内容

**验收**：从模板起一个模块 → 改一行 hook 目标 → 编译出 `arm64-v8a.so` → 打包 → 刷入 → 重启生效，全程在手机上完成。

**开工前先做设备兼容性矩阵**（这是 M6 的主要失败面）
- Magisk 内置 Zygisk：Magisk 27+ 自带，设置里开关
- 独立实现（Zygisk Next / ReZygisk）：KernelSU ≥ 10940（ksud ≥ 11575）；Magisk ≥ 26402 且**必须关闭内置 Zygisk**
- 仅有 Shizuku、无 Root：**Zygisk 模块无法工作**，工具要明确报错而不是静默失败

---

## 关键路径与风险点

```
M0(骨架) → M1(引擎+装机) → M2(资源) → M3(AI) → M4(文件管理) → M5(平台化)
              │
              └──────→ M6(模块工程)     ← 只依赖 M1，不阻塞也不被阻塞
              ↑
      最大不确定性：ARSCLib/APKEditor 在 Android 上的长尾兼容
```
- 若 M1 的 rebuild 在某些包上失败率 > 20%，**立即插入 M1.5**：接 rootfs+apktool 作为兜底路径（这时 rootfs 从 P2 提前）
- M3 依赖 M0-M2 的稳定工具接口，所以**先定 Tool 接口再写实现**，别边写边改签名
- M6 的最大不确定性是**目标设备的 root 方案差异**（内置 Zygisk / 独立实现 / 无 Root 三种环境行为不同），先搭兼容性矩阵再写工具
