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

---

## 九、Shizuku 装机：一次核查不完整导致的错误结论

**先记这个错误，因为它比结论本身更值得记住。**

**当时的结论**：Shizuku 13.x 移除了 `newProcess`，静默装包只能走 `IPackageInstaller`
的 session 流程（hidden API，得自己写逐版本匹配的 AIDL），无法在无真机的条件下验证 → 不做。

**为什么错**：只查了 `rikka.shizuku.Shizuku` 这个**门面类**，没查 Shizuku **服务本身的 AIDL 接口**。

```java
// moe.shizuku.server.IShizukuService —— stub 就在 dev.rikka.shizuku:aidl 里
public abstract IRemoteProcess newProcess(String[] cmd, String[] env, String dir);
```

**`newProcess` 一直还在服务接口上。** 13.x 做的只是把它从门面类拿掉
（`ShizukuRemoteProcess` 的构造器也变成了包私有），于是「以 shell 身份跑一条命令」这件事
从「调一行静态方法」退化成「拿服务句柄再调」：

```kotlin
val service = IShizukuService.Stub.asInterface(Shizuku.getBinder())
val proc = service.newProcess(arrayOf("pm", "install", "-r", "-d", "-S", size), null, null)
```

于是既不用写 hidden API 的 AIDL，也不用碰 `IPackageInstaller`。

### 教训（可复用）

**门面类少了一个方法，不等于底层能力消失了。**

判断「某个能力还能不能用」时，要查到**服务接口 / AIDL 那一层**再下结论。
门面类是便利封装，被裁剪掉的往往只是「暴露方式」，不是「实现」。
这次如果按错误结论去做，得绕一大圈写 AIDL、还没有真机可验证；
按正确路径做，只是多引一个 `shizuku-aidl` 依赖。

### 实现里的两个细节

1. **用 `pm install -S <字节数>` 从 stdin 送包，不要传文件路径。**
   `pm install <path>` 的路径最终由 PackageManagerService 打开，
   而 shell 域读 app 私有目录（`/data/user/0/<pkg>/...`）在 SELinux 下可能被拦。
   流式送包绕开这一点，也省掉一次大文件拷贝。写完 stdin 必须关闭流，
   否则 `pm` 收不到 EOF、会一直等。
2. **`-r` 和 `-d` 两个开关都要。**
   `-r` 覆盖安装（改包后包名不变，必须能覆盖）；
   `-d` 允许降级 —— 改完的包 versionCode 常常没变甚至更低，不加会被系统拒。

### 仍未验证的部分（M1 唯一没有测试兜底的一段）

装机三条通道都要真机才能验：Shizuku 的授权流程（`Shizuku.requestPermission` 需要
requestCode 与 Activity 回调）、`pm install` 在具体设备上的行为、以及各家 ROM 的 SELinux 差异。
代码路径是完整的，降级逻辑有单测（`InstallTest` 用假通道把顺序钉死了），
但「真机上能不能装上」这件事没有自动测试覆盖 —— 需要一次真机验收。

### 已核实的事实（省得下次再查）

- `rikka.shizuku.Shizuku` 13.1.5 门面类**没有** `newProcess`，公开能力只有
  `pingBinder` / `checkSelfPermission` / `requestPermission` / `getUid` / `getVersion` /
  `isPreV11` / `getBinder` / `transactRemote(Parcel, Parcel, int)` / `SystemServiceHelper.getSystemService(String)`
- 但 `moe.shizuku.server.IShizukuService`（在 `dev.rikka.shizuku:aidl` 里）**有**
  `newProcess(String[], String[], String) → IRemoteProcess`，
  `IRemoteProcess` 提供 `waitFor` / `exitValue` / `getInputStream` / `getOutputStream` / `getErrorStream`
- `ShizukuRemoteProcess` 的构造器是包私有的（所以别想着直接 new 它，用 `IRemoteProcess` 的接口即可）
- libsu 侧可用，`Shell.cmd("pm install ...").exec()` 的 `Result.isSuccess` 判断成败

---

## 十、换图标：ARSCLib 能改什么、不能改什么

### 10.1 现代包没有位图图标

`minSdk 26+` 的工程，AGP 只生成 adaptive icon：`res/mipmap-anydpi-v26/ic_launcher.xml`
声明前景/背景，而前景通常是**矢量 XML**、背景是**纯色资源** —— 一个位图都没有。

所以「往包里塞一张 PNG」是没用的：资源表里没有对应条目，系统找不到那张图，
图标会变成默认的。

### 10.2 普通 xml 改不动，清单能改（这次最深的坑）

ARSCLib 对**普通 xml**（`loadResXmlDocument`）的行为是「读时解析、写出时回放原始字节」：

- `module.getResXmlDocument(path)` **每次返回不同对象**（实测连续 6 次调用，6 个不同的 identityHashCode）
- 改了内存里的属性值，而且**同一对象读回确实变了**
  （`REFERENCE/2130968582 → REFERENCE/2131230721`）
- 但 `decode` 读回的是另一个对象 → 旧值；产物里也是旧值
- **全程不报任何错**

这是最危险的一类 API：**看起来改了，其实没改，而且没有任何信号**。排查花了十几轮，
最后是靠「同一对象读回 vs 另取一个对象读回」这组对照才定位到根因。

**清单是例外**：`AndroidManifestBlock` 是 module 自己的对象，改它有效 ——
M1 的 `setManifestField` 一直正常工作，就是这个原因。

**连带发现**：M2 的 `patchXml`（改任意 xml 属性）对**普通 xml 实际不生效**，
它的测试只覆盖了清单所以没暴露。要改普通 xml 的正确做法是**从零构造一份新的 xml 字节**
再整条替换，而不是「读出来改对象」。这条还没实现。

### 10.3 解法：新建资源 + 改清单

既然清单改得动、资源表也建得动，就绕开 adaptive 声明：

1. 新建 `mipmap/smithy_icon`：5 个密度各一个条目
   （`PackageBlock.getOrCreateTypeBlock(ResConfig(dpi), "mipmap")` + `getOrCreateEntry(name)`，
   值设成包内文件路径）
2. `manifest.setIconResourceId(新资源 id)` 把清单的 `android:icon` 指过来
3. 一并落盘 `resources.arsc` 与 `AndroidManifest.xml`（清单这条路径 M1 就验证过）

代价是图标从 adaptive 变成传统位图（Android 8+ 上少了自适应裁切与视差），
但这是**全部部件都验证过**的做法 —— 比赌一个行为不明的 API 靠谱得多。

验证方式不是「看我们自己的账」，而是**重新打开产物问资源表**：
`resources("mipmap", "smithy_icon")` 能查到、清单里的 `android:icon` 变了、
包整体还能正常解析。

**清单里没有 `android:icon` 的包也能设**：这类包的图标可能由主题（`android:icon` 写在 style 里）
指定，也可能压根没设 —— 之前的实现直接报「换不了」，是错的。只要「新建图标资源 +
把 `android:icon` 加到 `<application>`」就行：`setIconResourceId(id)` 在该属性不存在时**会新建它**
（实测产物清单里确实出现了）。`manifest.set` 的 `ICON` 字段就是干这个的：
值给 `@mipmap/xxx` 时先按名字查出资源 id 再写（清单里存的是引用，不是名字），
空字符串表示移除该声明。

### 10.4 架构：规划 → 画图 → 应用

画图要用 Android 的 `Bitmap`（`javax.imageio` 在 Android 上不存在），
而改包结构要用 ARSCLib —— 引擎是纯 JVM、不 import `android.*`。所以切成三步：

| 步骤 | 位置 | 做什么 |
|---|---|---|
| `planIconReplace(): IconPlan` | 引擎 | 说清每张图的画布尺寸与**内容尺寸**（安全区） |
| `IconReplacer.render(file, plan)` | UI 层 | 按规格画，只做「裁成方图 + 缩放 + 居中」 |
| `applyIconReplace(rendered)` | 引擎 | 写图、新建资源、改清单 |

**尺寸规则只有一处定义**（在引擎里）。换图标最容易出的问题（变形、被启动器裁掉）
就出在尺寸上，规则散成两份必然会有一处写错。

## 十一、M2 实测：资源层的代价与边界

### 11.1 两层替换的代价差 87 倍

同一批 200 组替换规则（30 组真命中）：

| 作用域 | 耗时 | 动了哪些条目 |
|---|---|---|
| `ARSC`（只改资源表） | **317ms** | `resources.arsc` |
| `BOTH`（两层都改） | **27525ms** | 5 个 dex + arsc |

差距全在 dex 上：**每个命中的 dex 都要重建成对象树再写出**，65k 方法的 dex 是秒级。
所以「改文案」默认走 `ARSC` 作用域，`BOTH` 只留给「搜到一个词、不确定它在哪层」的场景。

**教训**：接口设计时不能只问「能不能做」，要问「代价是多少」——
把两种代价差两个数量级的操作塞进同一个默认入口，用户会以为工具本身很慢。

### 11.2 资源表改完必须仍是 STORED + 4 字节对齐

安装器按内存映射读 `resources.arsc`，压缩或不对齐都会被系统拒绝安装。
ARSCLib 负责序列化，但**写出去之后是否重压缩由我们的 [ZipRebuilder] 决定**，
所以这条要在端到端验：实测改完后 `STORED`、数据偏移 19258084（`% 4 == 0`）。

测试里专门盯着这条 —— 它一旦破了，症状是「装机失败」而不是「值不对」，
是最难往资源层联想的一类故障。

### 11.3 改动只抽两个条目进覆盖层

ARSCLib 改完要写出**临时整包**才能拿到新的 `resources.arsc`。我们不拿那个包当结果，
只从里面抽 `resources.arsc` 与 `AndroidManifest.xml` 两个条目进工作区覆盖层，
其余条目照旧从原包搬运。

这样「未改动条目字节一致」这条 M1 验证过的性质得以保住 ——
实测改完资源后，**305 个条目全部字节一致**（只有 arsc 变了）。

### 11.4 包会略微变大

改资源后 25720KB → 25867KB（+147KB，约 0.6%）。原因是 ARSCLib 重新序列化了整张资源表
（字符串池排序与 M0 时的 AGP 产物不完全一致）。这是可接受代价，
但要记着：**改资源后的包不会与原包一样大**，做「体积对比」功能时别把它当成异常。



