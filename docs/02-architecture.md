# 02 · 架构与模块划分

## Gradle 模块

```
:app                UI 壳（Compose）、导航、依赖注入、权限引导
:core:engine        APK 引擎：解包/回编/签名/资源/DEX（纯 Java，不含 Android UI 依赖）
:core:fs            文件与 zip 抽象（SAF 兼容、大文件流式、zip 直改）
:core:ai            Agent 循环、模型客户端、上下文压缩、记忆
:core:mcp           MCP 服务端 + 客户端（JSON-RPC over HTTP/SSE）
:toolkit            工具集实现（把 engine 能力包装成语义化工具 + JSON schema）
:feature:apk        APK 工作台界面（分析报告、编辑器、改包面板）
:feature:files      双窗口文件管理
:feature:chat       AI 对话、会话管理、计划视图
:feature:settings   模型配置、权限、模块管理
:optional:rootfs    可选 Linux 环境（独立 AAR，按需下载资源）
```

分层原则：**engine 与 toolkit 不依赖 Android UI，可单元测试、可移植**。UI 只通过用例层调用 toolkit。

> **实现注记（M0 实测修正）**：`:core:engine` 构建为 **Android library** 而非纯 JVM 模块——
> 因为 `apksig-android`、`zstd-jni` 发布的是 aar，纯 JVM 模块消费不了
> （`Incompatible because this component declares 'aar' ... consumer needed class files`）。
> 但**代码约束保持不变：本模块不 import `android.*` / `androidx.*`**，逻辑仍是纯 JVM、可移植；
> 单元测试跑在 `src/test`（JVM 执行，无需设备）。Android 的接缝（Context / SAF / Shizuku）留在 app 与 feature 模块。

## 关键抽象（接口先行）

```kotlin
// 打开一个 APK 并持有工作区
interface ApkProject {
    val id: String
    val meta: ApkMeta                  // 包名/版本/签名/大小
    suspend fun list(path: String?): List<ApkEntry>
    suspend fun readEntry(path: String): InputStream
    suspend fun writeEntry(path: String, data: InputStream)
    suspend fun deleteEntry(path: String)
    suspend fun dexUnits(): List<DexUnit>
    suspend fun resources(): ResourceTable
    suspend fun rebuild(): File         // 输出未签名 APK
    suspend fun sign(cfg: SignConfig): File
    fun close()                          // 释放工作区
}

// 所有能力统一成工具，AI 与 UI 共用同一套入口
interface Tool {
    val spec: ToolSpec                   // name/description/JSON schema
    val effect: Effect                   // READ / WRITE / DESTRUCTIVE
    suspend fun invoke(ctx: ToolContext, args: JsonObject): ToolResult
}
interface ToolRegistry { fun all(): List<Tool>; fun find(name: String): Tool? }

// Agent 循环
interface AgentEngine {
    fun run(session: SessionId, input: String): Flow<AgentEvent>  // 流式：文本/工具调用/待确认/完成
    suspend fun cancel()
    suspend fun confirm(callId: String, approved: Boolean)
}
```

## 进程与内存策略（手机上的关键约束）

1. **大操作放独立进程**：解包/回编/签名跑在 `:worker` 进程（`android:process=":worker"`），获得独立堆上限，避免主进程被 OOM 杀掉。
2. **largeHeap 只在 worker 开**，主 UI 进程不开。
3. **流式处理**：entry 读写一律 InputStream/OutputStream，绝不 `readBytes()` 整个 APK。
4. **增量工作区**：解包到 App 私有目录，记录 manifest 校验和，未改动的文件回编时直接复用原字节（避免"改一个字符串要重编全部资源"）。
5. **DEX 懒加载**：只在被搜索/打开时才加载该 dex，用完释放。

## 并发模型

- 引擎调用：`Dispatchers.IO` 限并发 1（解包回编不可并行，会争工作区）
- UI 状态：StateFlow / Compose 单向数据流
- Agent 循环：单协程 + 可取消；工具调用串行（改包是有副作用的操作，并行会乱）

## 状态持久化边界

| 数据 | 存哪 |
|---|---|
| 会话/消息/记忆/角色/模型配置 | Room（SQLite） |
| 改包工作区、中间产物 | App 私有目录 `files/workspaces/<uuid>/` |
| 用户选择的 keystore | App 私有目录 + EncryptedSharedPreferences 存密码 |
| 分析报告导出 | 用户通过 SAF 选目录 |
