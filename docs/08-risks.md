# 08 · 风险、未知与待决策

## 一、技术风险（按严重度排序）

| # | 风险 | 影响 | 对策 |
|---|---|---|---|
| R1 | **签名校验**：很多 App 改包后检测到签名变化直接闪退 | 改包"成功"但跑不起来，最打击体验 | 提供「去签名校验」辅助：扫描 `getPackageInfo`/`PackageManager` 签名比对、`Signature` 相关调用，定位后给 AI 建议打补丁（不保证通杀） |
| R2 | **加固包**：360/腾讯/梆梆等壳，dex 被加密 | 完全无法分析 | 明确识别 + 告知（`apk.packer_guess`），可选脱壳模块后置；不要假装能处理 |
| R3 | **长尾兼容**：非标准 ARSC、畸形 zip、split APK | 打不开 | 失败即降级：报清楚原因 + 给替代路径（可选 rootfs/apktool） |
| R4 | **内存**：手机上解包大包 + 汇编 smali 容易 OOM | 崩溃 | `:worker` 独立进程 + largeHeap + 流式 + 增量回编 |
| R5 | Shizuku 需要 adb/无线调试激活，部分 ROM 重启失效 | 静默安装不可用 | 三级降级：Shizuku → root → 系统安装 Intent（让用户手点确认） |
| R6 | jadx 反编译大 dex 慢 | 界面卡 | 只做单类按需、后台跑、可取消、结果缓存 |
| R7 | APKEditor/ARSCLib 的 API 稳定性（社区项目） | 升级踩坑 | 锁版本、把引擎封装在自己的 `ApkProject` 接口后，便于替换实现 |

## 二、合规与法律风险

- 改包（尤其是去广告、改功能、二次分发）在国内属灰色地带，**工具本身不违法，用途可能违法**。
- 必须做的三件事：
  1. 关于页写明"仅限你自己拥有或已获授权的应用，用于学习与自用修改"
  2. 不做"一键破解 VIP / 去付费墙"这类以侵权为目的的模板功能
  3. 不内置任何破解好的成品包或分发渠道
- 若开源：许可证建议 Apache-2.0（与依赖一致，且可被商用）；注意 R3 中的 GPL 组件不能进。

## 三、产品风险

| 风险 | 说明 |
|---|---|
| 与 MT管理器 正面竞争不现实 | 它是十年产品 + 社区 + 插件生态，正面打必输。**差异化只有两点：AI 原生 + 开放（MCP）** |
| 维护成本高 | 引擎长尾问题会持续消耗时间，建议接受"能开 80% 的包"这个目标 |
| 用户群窄 | 玩机/逆向人群小但粘性高；若走开源，可以靠 MCP 生态获得开发者用户 |
| 法律灰区影响上架 | Google Play 基本别想，国内商店也难过审 → 定位为**开源项目/GitHub 分发**更现实 |

## 四、待你决策（影响架构，越早定越好）

| # | 问题 | 选项 | 我的建议 |
|---|---|---|---|
| D1 | ~~目标形态~~ | **已定：开源项目** | 选型按开源标准执行 |
| D2 | ~~编辑器~~ | **已定：sora-editor (LGPL-2.1)** | 开源下 LGPL 兼容，比自研省 2-3 周 |
| **D2b** | **开源许可证** | GPL-3.0（推荐）/ AGPL / MPL-2.0 / Apache-2.0 | **开工前必须定**：决定能否复用 GPL 生态实现，工期差约 2 周 |
| D3 | 是否要 rootfs 模块 | 要 / 不要 | **P2 再说**，但架构上留接口（否则 R3 无退路） |
| D4 | 工程工作区位置 | App 私有目录 / 用户 SAF 目录 | 私有目录 + 可选导出（避免 SAF 性能陷阱） |
| D5 | 是否内置 AI 默认模型 | 内置免费额度 / 纯 BYOK | 纯 BYOK（自带 Key），无服务器成本 |
| D6 | 项目名 | Smithy / 其他 | 待定，别用 MT 字样（商标风险） |

## 五、必须先定死的接口（防返工）

1. `Tool` / `ToolSpec` / `ToolResult`（M3 的 AI 层直接依赖，改一次全改）
2. `ApkProject`（引擎可替换性的关键）
3. `PatchRecord`（撤销/AI 可解释性/重放都挂在它上面）
4. 工作区状态机（UI 与 AI 都读它做决策）

**建议：先把这四个接口写成一页 Kotlin 文件，评审通过后再动手实现。** 这比先写 UI 省得多。

## 六、已知未知（需要在 M0-M1 实测确认）

- ARSCLib 在 Android 上处理多大体积的 `resources.arsc` 会爆内存？（实测：分别试 5MB / 20MB / 50MB 资源表）
- APKEditor 的 engine API 是否适合当库调用，还是只能走 CLI？（若只能 CLI → 需要内置 JVM/rootfs，架构要改）
- ~~apksig-android 对 v3/v4 与旋转密钥的支持程度~~ → **M1 实测：v2/v3 正常；v1 路径 NPE（见第八节）；v4 未启用（需要额外的 idsig 文件，手机自用安装用不上）**
- sora-editor 打开 5MB smali 的流畅度
- Shizuku 在目标机型（你自己的手机）上的稳定性

**第一条和第二条是 M0 必须先验证的，它们可能推翻"纯 Java 无 rootfs"这个前提。**

---

## 七、开源形态下的考量（D1 改为开源后）

| # | 议题 | 结论 |
|---|---|---|
| C1 | **分发渠道** | ✅ 开源解决了最大难题：GitHub Releases + **F-Droid / IzzyOnDroid 仓库**（都不禁止逆向工具，F-Droid 上就有 APK Explorer & Editor）+ Obtainium 自动更新。App Store 仍无可能 |
| C2 | **收费模式** | 开源后的可行路径：捐赠/赞助、Freemium（开源基础版 + 付费便利版）、双授权（需 CLA）。**别指望靠它赚钱**——同类开源项目基本靠爱发电 |
| C3 | **防白嫖** | 选 GPL-3.0 就有法律依据制止闭源抄袭；选 Apache-2.0 只能看着别人拿去卖 |
| C4 | **维护负担（最大风险）** | 开源后 issue/PR 会持续消耗时间，而引擎长尾问题不会减少。要么明确"不接受功能请求"，要么接受长期投入 |
| C5 | **AI 成本** | 走 BYOK（用户自带 Key），项目零服务器成本；要内置额度就得自建中转，成本不可控 |
| C6 | **合规** | 关于页写清"仅限自己拥有或已获授权的应用"；隐私政策好写（无云端）；用户协议要有 |
| C7 | **商标/命名** | 名字、图标避开 MT / CAssistant / CodeForge 的近似，否则容易被投诉 |
| C8 | **贡献者管理** | **现在就决定是否引入 CLA**——若以后想做闭源商业版或双授权，没有 CLA 就做不到，事后补极难 |

**开源后的真正难点是 C4**：技术上 4-6 周能出 MVP，但长期维护一个有人用的逆向工具是持续投入。

---

## 八、M1 实测结论（都是踩过的坑，别再踩）

### 8.1 签名：v1 不可用，因此 minSdk < 24 的包不支持

`apksig-android 4.4.0` 在生成 v1 签名的 `MANIFEST.MF` 时抛 NPE：

```
java.lang.NullPointerException: Cannot invoke "Object.toString()"
  because the return value of "java.util.Map$Entry.getValue()" is null
    at com.android.apksig.internal.jar.ManifestWriter.getAttributesSortedByName(ManifestWriter.java:113)
    at com.android.apksig.internal.apk.v1.V1SchemeSigner.generateManifestFile(V1SchemeSigner.java:382)
```

已排除调用侧的问题：`Builder.setCreatedBy()` 实现正常（拒绝 null 并存入字段），
`build()` 也确实把该字段传给了 `ApkSigner` 构造器（字节码逐条确认过）——
null 产生在库更深处，**外部无法修正**。

**处理**：实现里不签 v1。

- minSdk ≥ 24（Android 7.0）的系统本来就支持 v2/v3，不开 v1 没有任何损失；
- minSdk < 24 的包**直接报错**，而不是签一个"只有 v2/v3"的包让它在 Android 6 上装不上
  —— 后者更糟：改包流程显示成功，装机才失败，而且原因很难看出来。

`SignTest` 里有一个**反向用例**盯着这件事：一旦依赖升级后该用例失败，就说明 v1 能用了，
那时应打开 v1、删掉那个用例并更新本节。

### 8.2 dexlib2 的 Rewriter 每层返回惰性代理

`DexRewriter` 的 `dexFileRewriter.rewrite(dex)` 返回的是 `RewrittenDexFile` 之类的**代理对象**，
只有真正遍历它（例如交给 `DexFileFactory.writeDexFile`）时才会逐层展开到 instruction。

后果：如果按直觉写成"先看有没有命中，再决定要不要写文件"，内部的命中计数**恒为 0**，
**改动被静默丢弃且不抛任何异常** —— M1 第一版就是这么错的，表现是"搜索能搜到、替换却报 0 处命中"。

**正确顺序**：先写出去，再判断（写出后发现没有命中就删掉产物）。

### 8.3 zip 的 extra 有两个独立的坑

1. **central directory 的 extra 与 local header 的 extra 可以不同。**
   AGP 打的包里 `resources.arsc` 的 local header 有 3 字节对齐填充、central directory 里却没有。
   两者必须分别读，混用会算错数据偏移。
2. **extra 在 header 里声明了长度，就必须真的把字节写出去。**
   漏写会让整包所有后续条目整体前移 `extra.size` 字节；症状是读条目报 `LOC header (bad signature)`，
   而单看条目的数据内容又是对的 —— 极难定位。

**配套原则：位置信息只允许有一个来源。**
我们用 `CountingOutputStream` 记录"已经写出了多少字节"，localOffset 与 central directory
偏移都取自它，不再手工累加各部分尺寸（手工累加漏算过一次 extra 的长度，整包偏移全错）。

**通用教训**：自己写 zip 时，凡是"声明出来的长度"都要有断言或单一来源兜住，
否则错误会以"内容看起来对、位置却错了"的形式出现，比 outright 崩溃难查得多。
