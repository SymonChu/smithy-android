# 03 · 数据模型

## 一、APK 领域实体（内存态，非持久化）

```kotlin
data class ApkMeta(
    val sourcePath: String,          // 原始文件
    val packageName: String,
    val versionName: String,
    val versionCode: Long,
    val minSdk: Int, val targetSdk: Int,
    val appLabel: String,
    val permissions: List<String>,
    val components: List<ComponentInfo>,
    val signatures: List<SignatureInfo>,
    val dexUnits: List<DexStat>,
    val sizeBytes: Long,
    val isSplit: Boolean,
    val packer: PackerGuess?,         // 加固猜测，可空
)
data class SignatureInfo(val scheme: Int, val subject: String, val issuer: String,
                         val md5: String, val sha1: String, val sha256: String, val isDebug: Boolean)
data class DexStat(val name: String, val classes: Int, val methods: Int, val strings: Int, val size: Long)
data class ResourceEntry(val type: String, val name: String, val id: Int, val value: String?, val isComplex: Boolean)

// 改动记录（支撑撤销 / diff 视图 / AI 可解释性）
data class PatchRecord(
    val id: String, val workspaceId: String, val ts: Long,
    val kind: PatchKind,              // SMALI / ARSC / AXML / ENTRY_ADD / ENTRY_DEL / MANIFEST
    val target: String,               // 如 "smali/com/a/B.smali#method foo"
    val beforeHash: String?, val afterHash: String?,
    val backupPath: String?,          // 原文件备份，用于回退
    val origin: PatchOrigin,          // USER / AI(sessionId, toolCallId)
    val note: String?,                // AI 给出的修改理由
)
```

## 二、AI 领域实体（Room 持久化）

| 表 | 字段要点 |
|---|---|
| `sessions` | id, title, workspaceId?, createdAt, lastActiveAt, pinned, modelConfigId, roleId, summary(压缩后摘要) |
| `messages` | id, sessionId, role(user/assistant/tool), content, reasoning?(思考链), ts, tokens, isCompressed |
| `tool_calls` | id, messageId, toolName, argsJson, resultJson, status(pending/running/ok/error/denied), startedAt, endedAt |
| `model_configs` | id, name, baseUrl, apiKey(加密), model, contextWindow, maxOutputTokens, isActive, isSummarizer |
| `roles` | id, name, systemPrompt, toolPolicy(允许/禁止的工具), isBuiltin |
| `memory_items` | id, scope(GLOBAL/PROJECT/SESSION), workspaceId?, content, embedding?(可选), ts |
| `plans` | id, sessionId, goal, stepsJson(每步状态), status |
| `skills` | id, name, dirPath, manifestJson, enabled |

## 三、工程工作区目录

```
files/workspaces/<workspaceId>/
├── meta.json                # ApkMeta 快照 + 源文件路径 + 校验和
├── source/base.apk          # 原包（只读）
├── unpack/                  # 解包产物（可增量）
│   ├── AndroidManifest.xml
│   ├── res/  assets/  lib/
│   ├── smali/ smali_classes2/ ...
│   └── resources.arsc       # 或 arsc.json（可读态）
├── out/
│   ├── unsigned.apk
│   ├── signed.apk
│   └── report.md
├── patches/                 # 改动备份（回退用）
└── logs/                    # 每次操作的结构化日志（供 AI 读）
```

## 四、状态机

**工作区**：`IDLE → UNPACKED → DIRTY → REBUILDING → REBUILT → SIGNED → INSTALLED`
（AI 与 UI 都读这个状态决定能做什么，`DIRTY` 时才允许 rebuild）

**工具调用**：`PENDING → (READ 直接执行 | WRITE 需确认) → RUNNING → OK/ERROR`
门控策略：`Effect.DESTRUCTIVE`（删除、覆盖源文件、安装）必须确认；`WRITE` 默认确认、可在设置里改"信任模式"；`READ` 直接执行。

## 五、为什么要有 PatchRecord

三个理由，缺一不可：
1. **撤销**：改包翻车能回退（MT管理器只有全局回退，这里做单次粒度）
2. **AI 可解释**：每个改动挂上"AI 为什么这么改"，对话里可点开看
3. **重放**：把一串 patch 存成"配方"，下次对同版本包一键复用
