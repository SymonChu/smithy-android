# 04 · 工具清单（MCP 工具集设计）

所有能力统一为 Tool，UI 与 AI 共用同一入口。`Effect`：R=只读、W=写入(需确认)、D=破坏性(强制确认)。
**原则：语义化，不给万能口子。** 默认工具集里没有 `shell.exec`，需显式在设置里开启。

## 工作区
| 工具 | Effect | 参数 | 说明 |
|---|---|---|---|
| `workspace.open` | W | apkPath | 返回 workspaceId + ApkMeta 摘要（模块用 `module.open`） |
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
| `arsc.list` | R | workspaceId, type?, filter? | 资源表条目。filter 同时匹配资源名与值 |
| `arsc.set` | W | workspaceId, resName, value | 单条修改，如 `@string/app_name` |
| `arsc.replace_string` | W | workspaceId, pairs[], scope? | 批量文案替换。**scope 缺省 ARSC**（只改资源表，实测 200 组 300ms）；把 dex 也算上的 BOTH 要 27 秒，见 `docs/08` 第十节 |
| `axml.decode` | R | workspaceId, path | 二进制 XML → 可读文本（清单与布局都能解） |
| `axml.patch` | W | workspaceId, path, elementPath, attr, value | 改任意元素的属性。**elementPath 从根元素的直接子级开始**（清单写 `manifest/application`）；同名取第二个用 `activity[1]`。属性不存在则新建 |
| `manifest.set` | W | workspaceId, field, value | appLabel / packageName / versionName / versionCode / debuggable |
| `icon.replace` | W | workspaceId, source(image/dir), densities? | **P0 卖点**：自动生成各密度 mipmap |
| `asset.put` / `asset.delete` | W | workspaceId, path, file? | 任意条目的增删（不只 assets）；工作台「文件」标签走这条 |
| `report.export` | R | workspaceId, format? | 生成分析报告（Markdown：基本信息 / 签名 / 权限 / 组件 / DEX 五节）。只读 —— 生成的是文本，写到哪由调用方决定 |

## 打包链路
| 工具 | Effect | 参数 | 说明 |
|---|---|---|---|
| `apk.rebuild` | W | workspaceId, incremental? | 输出 unsigned.apk |
| `apk.align` | W | path | 对齐 |
| `apk.sign` | W | path, keystoreRef?, schemes | 缺省用内置 keystore，自动生成 |
| `apk.verify` | R | path | 校验签名与完整性 |
| `apk.install` | D | path, via=shizuku\|root\|intent | 静默安装优先，失败降级到 intent |

## 文件 / 压缩包
| 工具 | Effect | 参数 | 说明 |
|---|---|---|---|
| `fs.list` / `fs.read` / `fs.write` / `fs.copy` / `fs.move` / `fs.delete` | R/W | path, target | 工作区与任意目录的文件操作 |
| `zip.list` / `zip.read` / `zip.extract` / `zip.put` / `zip.delete` | R/W | archive, path, file | 模块 zip 与 APK **共用这一套**，不另造 `archive.*` |
| `shell.exec` | D | cmd, via | 默认关闭，需设置开启 + 每次确认 |

## 模块工程（M6）
> **无 Root 时**：只读检视与重打包仍可用；涉及刷入 / 启停 / 重启的工具返回 `NO_ROOT` 并附 hint，**不静默失败**。

| 工具 | Effect | 参数 | 说明 |
|---|---|---|---|
| `module.create` | W | dir, id, name?, version?, versionCode?, author?, description?, flavour=shell\|zygisk | **从零建一个模块骨架**（其余模块工具都要求先有一个 zip）。`module.prop` / `service.sh` / `post-fs-data.sh` / `system.prop` 按规范摆好，条目在根上。`flavour=shell` 刷入即生效；`flavour=zygisk` 只给 `jni/` 下的 native 源码（`.so` 要自己编，见 M6-B），不会放占位的 so |
| `module.open` | W | path | 打开模块 zip 为工作区，返回 moduleWorkspaceId + 元数据摘要；`workspace.status` / `patch.*` / `fs.*` 对模块工作区同样可用 |
| `module.inspect` | R | workspaceId | 用 `module.prop` 判定模块、用 `zygisk/*.so` 判定 Zygisk 模块；列脚本 / overlay / ABI 覆盖，并与设备 ABI 比对（缺当前 ABI 显式警告） |
| `module.prop_get` | R | workspaceId | 结构化返回 id / name / version / versionCode / author / description，缺失字段显式标出 |
| `module.prop_set` | W | workspaceId, field, value | 改 id / 名称 / 版本 / 描述；改 id 时校验与目录名一致 |
| `module.scripts` | R | workspaceId | 已知脚本与配置清单（`service.sh` / `post-fs-data.sh` / `system.prop` / `sepolicy.rule` / `customize.sh`）；内容用 `zip.read` 取 |
| `module.package` | W | workspaceId, out? | 按规范布局重打包：`module.prop` 在根、`zygisk/` 原样、保持 entry 顺序 |
| `module.install` | D | path \| workspaceId, via=root | **仅 root**；Shizuku 在该场景权限不足，直接返回 `NO_ROOT` + hint |
| `module.enable` / `module.disable` | W | moduleId | 增删 `disable` 标记文件 |
| `module.remove` | D | moduleId | 写 `remove` 标记（下次重启卸载）——比直接删目录安全 |
| `module.uninstall` | D | moduleId | 直接删除模块目录 |
| `zygote.restart` | D | — | 软重启使模块生效；影响所有运行中应用，**信任模式下也不免确认** |
| `elf.inspect` | R | workspaceId, entry | ELF 头 / 架构 / 依赖(NEEDED) / 节表 / 字符串表统计 |
| `elf.strings` | R | workspaceId, entry, filter?, minLen? | 字符串清单，分页；受默认输出预算约束 |
| `elf.patch_string` | W | workspaceId, entry, from, to, scope? | **仅等长替换**；长度不等直接拒绝，hint 指向源码重编（M6-B） |

**明确不做**：反汇编 `.so` 改逻辑再回编——逆向产物没有源码语义，回编不成立，不做半成品。

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
| `build.module_assemble` | W | 需先下载 native 构建模块（clang + sysroot），从源码编译 `.so`；改模块逻辑的唯一正当路径 |

## 结果与错误约定
- 返回统一 JSON；列表类结果带 `total` + `nextCursor` 分页，避免一次塞爆上下文
- 错误统一 `{code, message, hint}`，**hint 必须给出下一步建议**（例：`UNSUPPORTED_ARSC` → "该包资源表为非常规格式，可用 `rootfs.exec` 走 apktool 路径"；`NO_ROOT` → "当前设备未取得 root，模块刷入不可用，可先 `module.package` 导出 zip 手动刷入"）
- 每个工具的输出预算：默认截断到 8KB，超出写文件并返回路径（AI 可按需再读）
