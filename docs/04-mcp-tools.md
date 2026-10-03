# 04 · 工具清单（MCP 工具集设计）

所有能力统一为 Tool，UI 与 AI 共用同一入口。`Effect`：R=只读、W=写入(需确认)、D=破坏性(强制确认)。
**原则：语义化，不给万能口子。** 默认工具集里没有 `shell.exec`，需显式在设置里开启。

## 工作区
| 工具 | Effect | 参数 | 说明 |
|---|---|---|---|
| `workspace.open` | W | apkPath | 返回 workspaceId + ApkMeta 摘要 |
| `workspace.status` | R | workspaceId | 状态机当前值 + 是否 DIRTY + 改动数 |
| `workspace.close` | W | workspaceId, keepArtifacts | 释放/清理工作区 |

## APK 解析
| 工具 | Effect | 参数 | 说明 |
|---|---|---|---|
| `apk.meta` | R | workspaceId | 包名/版本/SDK/大小/dex 数 |
| `apk.list` | R | workspaceId, path?, depth? | 内部结构，分页 |
| `apk.manifest` | R | workspaceId | 结构化 manifest（组件/权限/导出） |
| `apk.signatures` | R | workspaceId | 方案/证书/指纹/debug 判定 |
| `apk.dex_stats` | R | workspaceId | 类/方法/字符串计数，65536 预警 |
| `apk.packer_guess` | R | workspaceId | 加固特征猜测 + 置信度 |

## 代码层
| 工具 | Effect | 参数 | 说明 |
|---|---|---|---|
| `dex.search_class` | R | workspaceId, pattern, dex? | 支持正则 |
| `dex.search_method` | R | workspaceId, pattern | 方法名/签名 |
| `dex.search_string` | R | workspaceId, text, regex?, limit | **最常用**：找硬编码 URL/密钥/文案 |
| `dex.references` | R | workspaceId, className, method? | 找交叉引用（谁调用它） |
| `jadx.decompile_class` | R | workspaceId, className | 单类反编译为 Java，**按需不全量** |
| `smali.read_class` | R | workspaceId, className | 返回 smali 源码 |
| `smali.read_method` | R | workspaceId, className, methodSig | 只读目标方法，省 token |
| `smali.patch` | W | workspaceId, className, methodSig, pattern, replacement, mode | 精确改写，自动记账 PatchRecord |
| `smali.replace_string` | W | workspaceId, from, to, escape?, scope | 批量改字符串常量 |
| `smali.delete_class` | W | workspaceId, className | 批量删类 |

## 资源层
| 工具 | Effect | 参数 | 说明 |
|---|---|---|---|
| `arsc.list` | R | workspaceId, type?, filter? | 资源表条目 |
| `arsc.get` | R | workspaceId, resName | 如 `@string/app_name` |
| `arsc.set` | W | workspaceId, resName, value | 单条修改 |
| `arsc.replace_string` | W | workspaceId, from, to, regex? | 批量文案替换（含布局 XML） |
| `axml.decode` | R | workspaceId, path | 二进制 XML → 可读 |
| `axml.patch` | W | workspaceId, path, target, attr, value | 改属性（如 debuggable、exported） |
| `manifest.set` | W | workspaceId, field, value | appLabel / packageName / versionName / versionCode / debuggable |
| `icon.replace` | W | workspaceId, source(image/dir), densities? | **P0 卖点**：自动生成各密度 mipmap |
| `asset.put` / `asset.delete` | W | workspaceId, path, file? | assets 增删 |

## 打包链路
| 工具 | Effect | 参数 | 说明 |
|---|---|---|---|
| `apk.rebuild` | W | workspaceId, incremental? | 输出 unsigned.apk |
| `apk.align` | W | path | 对齐 |
| `apk.sign` | W | path, keystoreRef?, schemes | 缺省用内置 keystore，自动生成 |
| `apk.verify` | R | path | 校验签名与完整性 |
| `apk.install` | D | path, via=shizuku\|root\|intent | 静默安装优先，失败降级到 intent |

## 文件 / 压缩包
| 工具 | Effect | 参数 |
|---|---|---|
| `fs.list` / `fs.read` / `fs.write` / `fs.copy` / `fs.move` / `fs.delete` | R/W | path, target |
| `zip.list` / `zip.extract` / `zip.put` / `zip.delete` | R/W | archive, path, file |
| `shell.exec` | D | cmd, via（默认关闭，需设置开启 + 每次确认） |

## 改动管理
| 工具 | Effect | 参数 | 说明 |
|---|---|---|---|
| `patch.list` | R | workspaceId | 已有改动清单 |
| `patch.diff` | R | patchId | 前后对比 |
| `patch.revert` | W | patchId | 单次回退 |
| `patch.export_recipe` | R | workspaceId | 导出"改动配方"JSON，可复用 |

## 可选模块
| 工具 | Effect | 说明 |
|---|---|---|
| `tunnel.start/stop` | W | cloudflared 公网预览（对齐 CodeForge） |
| `rootfs.exec` | D | 需先下载 rootfs 模块 |
| `build.gradle_assemble` | W | 需先下载构建模块 |

## 结果与错误约定
- 返回统一 JSON；列表类结果带 `total` + `nextCursor` 分页，避免一次塞爆上下文
- 错误统一 `{code, message, hint}`，**hint 必须给出下一步建议**（例：`UNSUPPORTED_ARSC` → "该包资源表为非常规格式，可用 `rootfs.exec` 走 apktool 路径"）
- 每个工具的输出预算：默认截断到 8KB，超出写文件并返回路径（AI 可按需再读）
