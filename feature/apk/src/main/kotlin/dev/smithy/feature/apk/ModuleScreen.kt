package dev.smithy.feature.apk

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.smithy.design.SmithyIconButton
import dev.smithy.design.SmithyIcons
import dev.smithy.design.SmithyDialogTitle
import dev.smithy.design.SmithyMotion
import dev.smithy.design.SmithyMono
import dev.smithy.design.SmithyRowMeta
import dev.smithy.design.SmithyRowTitle
import dev.smithy.design.SmithySpacing
import dev.smithy.design.SmithyTopBar
import dev.smithy.design.icon
import dev.smithy.fs.ModuleProp
import dev.smithy.fs.AddOnCatalog
import dev.smithy.fs.AddOnHost
import dev.smithy.fs.NativeToolchains
import dev.smithy.fs.ShellChannels
import dev.smithy.fs.humanSize

/**
 * 模块页：Magisk / Zygisk 模块的查看、改动、打包与刷入。
 *
 * 和改包工作台分开成两个页面，是因为它们处理的是**两种包**：
 * 一个是 apk（有资源表、签名、dex），一个是模块 zip（只有纯文本加一堆 so）。
 * 混在一个界面里，每个按钮都要先问「现在是哪种」。
 *
 * 这一页的重点是**把结构问题摆在眼前**：缺当前设备 ABI、文件套了一层目录、
 * 还带着 disable 标记 —— 这些都能让模块「装上了但不生效，且不报错」。
 *
 * 这一轮把排版并到工作台同一套：顶栏走 [SmithyTopBar]、分块走 [Section]（小标题 + 底色卡，
 * 不再用满宽 [androidx.compose.material3.HorizontalDivider] 切分）、动作走药丸、
 * 确认走统一的破坏性对话框、消息与忙碌条各带图标。
 */
@Composable
fun ModuleScreen(
    state: ModuleUiState,
    onOpen: () -> Unit,
    onCreate: (id: String, name: String, description: String, zygisk: Boolean) -> Unit,
    onClose: () -> Unit,
    onVersion: (String) -> Unit,
    onVersionCode: (String) -> Unit,
    onName: (String) -> Unit,
    onDescription: (String) -> Unit,
    onSaveProp: () -> Unit,
    onEditEntry: (String) -> Unit,
    onEditingText: (String) -> Unit,
    onCancelEdit: () -> Unit,
    onSaveEntry: () -> Unit,
    onInstall: () -> Unit,
    /** 把 jni/ 下的源码编成 zygisk/<abi>.so。没有工具链时它只回报原因，不瞎跑一次编译。 */
    onCompile: () -> Unit,
    /** 装一个可选组件（现在只有 Alpine rootfs 这一条，走下载）。 */
    onInstallComponent: (String) -> Unit,
    /** 把手机本地的一份归档灌进去 —— 目前装上 native 工具链的唯一一条路。 */
    onImportComponent: () -> Unit,
    /** 「准备编译环境」：部署 rootfs 并在里面 apk add clang（要 root）。 */
    onPrepareRootfs: () -> Unit,
    onSetEnabled: (String, Boolean) -> Unit,
    onScheduleRemove: (String) -> Unit,
    onUninstall: (String) -> Unit,
    onRestartZygote: () -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var confirmZygote by remember { mutableStateOf(false) }
    var confirmUninstall by remember { mutableStateOf<String?>(null) }
    // 新建模块要问 id / 名称 / 描述，还有一个「要不要 Zygisk 源码」的选择 ——
    // 这些只活在这一个对话框里，不进 ViewModel（放进去了「取消」也要绕一圈去清）
    var creating by remember { mutableStateOf(false) }

    // 正在编辑条目时占满整屏：改脚本要看得见上下文，旁边留一列按钮反而挤
    state.editing?.let { path ->
        Column(modifier.fillMaxSize()) {
            Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
                Row(
                    Modifier.fillMaxWidth().padding(
                        start = SmithySpacing.gutter,
                        end = SmithySpacing.gap,
                        top = SmithySpacing.barVertical,
                        bottom = SmithySpacing.barVertical,
                    ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = kindOf(path).icon,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.width(SmithySpacing.iconGap))
                    Text(
                        path.substringAfterLast('/'),
                        style = SmithyRowTitle,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    SmithyIconButton(
                        icon = SmithyIcons.Close,
                        contentDescription = "放弃这次编辑",
                        onClick = onCancelEdit,
                    )
                    Button(onClick = onSaveEntry) {
                        Icon(SmithyIcons.Save, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("保存")
                    }
                }
            }
            // 完整路径单独一行：模块里的路径有意义（脚本、prop 各自的位置），
            // 顶栏那行只放得下文件名
            Text(
                path,
                style = SmithyRowMeta.copy(fontFamily = dev.smithy.design.SmithyMono),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(
                    start = SmithySpacing.gutter,
                    end = SmithySpacing.gutter,
                    top = SmithySpacing.gap,
                ),
            )
            OutlinedTextField(
                value = state.editingText,
                onValueChange = onEditingText,
                modifier = Modifier.fillMaxSize().padding(SmithySpacing.gutter),
                textStyle = SmithyRowMeta.copy(fontFamily = dev.smithy.design.SmithyMono),
            )
        }
        return
    }

    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        HeaderRow(state, onOpen, onClose, onRefresh, onNew = { creating = true })

        state.busy?.let { BusyRow(it, Modifier.padding(horizontal = SmithySpacing.gutter, vertical = SmithySpacing.gap)) }
        AnimatedVisibility(
            visible = state.message != null,
            enter = expandVertically(SmithyMotion.enter()) + fadeIn(SmithyMotion.enter()),
            exit = shrinkVertically(SmithyMotion.exit()) + fadeOut(SmithyMotion.exit()),
        ) {
            state.message?.let { MessageBar(it, state.isError) }
        }

        Column(
            Modifier.fillMaxWidth().padding(vertical = SmithySpacing.section),
            verticalArrangement = Arrangement.spacedBy(SmithySpacing.section),
        ) {
            if (state.zipPath == null) {
                EmptyHint(onNew = { creating = true })
            } else {
                PropCard(state, onVersion, onVersionCode, onName, onDescription, onSaveProp)
                StructureCard(state)
                NativeCard(state, onCompile, onInstallComponent, onImportComponent, onPrepareRootfs)
                EntriesCard(state, onEditEntry)
                InstallCard(state, onInstall)
            }

            DeviceCard(
                state = state,
                onSetEnabled = onSetEnabled,
                onScheduleRemove = onScheduleRemove,
                onUninstall = { confirmUninstall = it },
                onRestartZygote = { confirmZygote = true },
            )
        }
    }

    if (confirmZygote) {
        ConfirmDialog(
            title = "软重启 zygote？",
            body = "所有正在运行的应用都会被重启，**没保存的东西会丢**。\n\n" +
                "这是让新装的 Zygisk 模块生效的最快方式（不用整机重启）。" +
                "如果你现在有别的应用正开着没保存，先切过去保存。",
            confirm = "重启",
            destructive = true,
            onConfirm = {
                confirmZygote = false
                onRestartZygote()
            },
            onDismiss = { confirmZygote = false },
        )
    }

    confirmUninstall?.let { id ->
        ConfirmDialog(
            title = "立即删除「$id」？",
            body = "只对已停用的模块允许 —— 还在启用时它的 so 可能正被映射进进程，删掉会让进程行为不可预期。",
            confirm = "删除",
            destructive = true,
            onConfirm = {
                confirmUninstall = null
                onUninstall(id)
            },
            onDismiss = { confirmUninstall = null },
        )
    }

    if (creating) {
        NewModuleDialog(
            onCreate = { id, name, description, zygisk ->
                creating = false
                onCreate(id, name, description, zygisk)
            },
            onDismiss = { creating = false },
        )
    }
}

/**
 * 顶栏：打开的是哪个模块 + 刷新 / 关闭。
 *
 * 副标题给模块 **id**：它是 Magisk 认的身份（不能改），比文件名更值得一眼看到。
 */
@Composable
private fun HeaderRow(
    state: ModuleUiState,
    onOpen: () -> Unit,
    onClose: () -> Unit,
    onRefresh: () -> Unit,
    onNew: () -> Unit,
) {
    SmithyTopBar(
        title = state.zipPath?.let { it.substringAfterLast('/') } ?: "模块",
        subtitle = state.prop?.let { "id：${it.id}" } ?: "Magisk / Zygisk 模块 zip",
        actions = {
            SmithyIconButton(
                icon = SmithyIcons.Refresh,
                contentDescription = "刷新设备上的模块",
                onClick = onRefresh,
            )
            if (state.zipPath == null) {
                // 从零做一个也是「打开一个模块」那一档的入口，所以放在一起：
                // 没打开任何模块时，用户要么选一个已有的，要么新建一个
                Pill("新建模块", onNew, icon = SmithyIcons.Plus)
                Button(onClick = onOpen) { Text("打开模块 zip") }
            } else {
                SmithyIconButton(
                    icon = SmithyIcons.Close,
                    contentDescription = "关闭这个模块",
                    onClick = onClose,
                )
            }
        },
    )
}

/**
 * 新建模块。
 *
 * 问三件事：id（Magisk 的身份，也是刷入后的目录名）、名称、描述（模块列表里显示的那行字）。
 * [ModuleProp.validateId] 的判定和这里一致 —— 界面先拦一次，工具层那次是给 AI 兜底，
 * 两边都不是「唯一防线」。
 *
 * 「要 Zygisk 源码」用两个药丸切：它决定生成出来的是**能直接刷的脚本模块**，
 * 还是**只有源码、还得自己编 .so** 的骨架。这个差别必须写在按钮旁边 ——
 * 选错的代价不是「不好看」，而是「刷进去什么也没发生」。
 */
@Composable
private fun NewModuleDialog(
    onCreate: (id: String, name: String, description: String, zygisk: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var id by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var zygisk by remember { mutableStateOf(false) }
    val problem = id.takeIf { it.isNotBlank() }?.let { ModuleProp.validateId(it.trim()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = MaterialTheme.shapes.extraLarge,
        title = { SmithyDialogTitle(icon = SmithyIcons.ModuleTab, text = "新建模块") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = id,
                    onValueChange = { id = it },
                    label = { Text("id（字母数字和 . _ -）") },
                    singleLine = true,
                    isError = problem != null,
                    supportingText = problem?.let { { Text(it) } },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(SmithySpacing.gap))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(SmithySpacing.gap))
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("描述（模块列表里显示这行）") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(SmithySpacing.gutter))
                Row(horizontalArrangement = Arrangement.spacedBy(SmithySpacing.gap)) {
                    Pill("脚本模块", { zygisk = false }, active = !zygisk, icon = SmithyIcons.Rule)
                    Pill("Zygisk 源码", { zygisk = true }, active = zygisk, icon = SmithyIcons.KindBinary)
                }
                Spacer(Modifier.height(SmithySpacing.gap))
                Text(
                    if (zygisk) {
                        "给 jni/ 下的 native 源码骨架。**它不是能生效的模块**：要先把 " +
                            "arm64-v8a.so 编出来（手机上需要构建模块），骨架里不会放占位的 so。"
                    } else {
                        "给 service.sh 等脚本骨架，刷进去就能生效：改属性、开机跑一次命令、" +
                            "按包名动应用数据都属于这一档，不需要任何编译链。"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onCreate(id.trim(), name, description, zygisk) },
                enabled = id.isNotBlank() && problem == null,
            ) {
                Text("新建")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun EmptyHint(onNew: () -> Unit) {
    Section("还没打开模块") {
        Text(
            "模块 zip 的条目直接在根上（module.prop、zygisk/、service.sh），不套一层目录。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(SmithySpacing.gutter))
        // 两个入口都给出来：手上已有模块 zip 的人选一个，想从零写的人建一个。
        // 「新建」这条路以前没有 —— 只能自己在别处把 zip 结构拼对才能进来
        Button(onClick = onNew, modifier = Modifier.fillMaxWidth()) {
            Icon(SmithyIcons.Plus, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text("新建模块（生成骨架）")
        }
        Spacer(Modifier.height(SmithySpacing.gap))
        Text(
            "改包（apk）在「工作台」那个标签里 —— 这里是刷机模块，两种包不是一回事。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun PropCard(
    state: ModuleUiState,
    onVersion: (String) -> Unit,
    onVersionCode: (String) -> Unit,
    onName: (String) -> Unit,
    onDescription: (String) -> Unit,
    onSave: () -> Unit,
) {
    val prop = state.prop ?: return
    Section("元数据") {
        InfoRow("id", prop.id)
        Spacer(Modifier.height(4.dp))
        Text(
            "id 不能改 —— Magisk 用目录名当模块标识，改了会和已装的对不上",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(SmithySpacing.gutter))

        OutlinedTextField(
            value = state.draftName,
            onValueChange = onName,
            label = { Text("名称") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(SmithySpacing.gap))
        Row(horizontalArrangement = Arrangement.spacedBy(SmithySpacing.gap)) {
            OutlinedTextField(
                value = state.draftVersion,
                onValueChange = onVersion,
                label = { Text("版本") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = state.draftVersionCode,
                onValueChange = onVersionCode,
                label = { Text("版本号") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(SmithySpacing.gap))
        OutlinedTextField(
            value = state.draftDescription,
            onValueChange = onDescription,
            label = { Text("描述") },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(SmithySpacing.gutter))
        Button(onClick = onSave, modifier = Modifier.fillMaxWidth()) {
            Icon(SmithyIcons.Save, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text("保存改动（另存为新 zip）")
        }
        Spacer(Modifier.height(SmithySpacing.gap))
        Text(
            "不覆盖原包：产物写成 <原名>-edited.zip。刷进去不对还能拿原包再来一次。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun StructureCard(state: ModuleUiState) {
    val layout = state.layout ?: return
    Section("结构") {
        if (layout.isZygisk) {
            InfoRow("ABI 覆盖", layout.knownAbis.joinToString("  ").ifEmpty { "（没有有效的）" })
        } else {
            Text(
                "不是 Zygisk 模块（没有 zygisk 下的 so）",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (layout.scripts.isNotEmpty()) {
            InfoRow("脚本", layout.scripts.joinToString(", "))
        }
        if (layout.hasSystemOverlay) {
            InfoRow("overlay", "含 system/ overlay")
        }

        // 结构问题是这一页的核心（装上了不生效、且不报错），所以整条横幅给出来，
        // 而不是混在别的信息里当一行小字
        layout.warnings.forEach { w ->
            Spacer(Modifier.height(SmithySpacing.gap))
            WarnBanner(w)
        }
    }
}

/**
 * native 编译。
 *
 * 只在包里**有 `jni/` 源码**时出现 —— 纯脚本模块不需要编译，给它看一张编译卡是噪音。
 *
 * 这一块存在的理由是那句话：**源码刷进去不会生效**。zygisk 模块要的是
 * `zygisk/<abi>.so`，而手机上有多少人以为「把源码放进 zip 就行了」，
 * 就会有多少次「刷了、重启了、什么都没发生」的排查。
 *
 * 工具链不可用时不把按钮做成可点却没反应的样子：按钮置灰（点它会震一下「现在不行」），
 * 下面直接把缺什么、放哪儿写出来。
 */
@Composable
private fun NativeCard(
    state: ModuleUiState,
    onCompile: () -> Unit,
    onInstallComponent: (String) -> Unit,
    onImportComponent: () -> Unit,
    onPrepareRootfs: () -> Unit,
) {
    val sources = state.entries.filter {
        it.startsWith("jni/") && it.substringAfterLast('.', "").lowercase() in setOf("cpp", "cc", "cxx", "c")
    }
    if (sources.isEmpty()) return

    val toolchain = NativeToolchains.current()
    val ready = toolchain?.available() == true
    val built = state.entries.filter { it.startsWith("zygisk/") }
    val addons = AddOnHost.current()
    val rootfs = AddOnCatalog.rootfsAlpine
    val rootfsInstalled = addons?.installed(rootfs) != null
    val sysrootAddon = AddOnCatalog.sysrootNdkArm64
    val sysrootInstalled = addons?.installed(sysrootAddon) != null

    Section("编译（native）") {
        Text(
            "源码在 jni/ 下（${sources.size} 个），但 Magisk 只认 zygisk/<abi>.so —— " +
                "源码本身刷进去不会生效，得先编出来。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(SmithySpacing.gutter))
        Row(horizontalArrangement = Arrangement.spacedBy(SmithySpacing.gap)) {
            Pill("编译 arm64-v8a", onCompile, icon = SmithyIcons.Run, enabled = ready)
            // 没有工具链时给出两条**真能走的路**，而不是只写一句「缺工具链」：
            // rootfs 是官方能直接下的那一条；本地包是眼下唯一能拿到 arm64 clang 的一条。
            if (!ready) {
                Pill(
                    label = if (rootfsInstalled) {
                        "Alpine rootfs 已装（${addons?.installed(rootfs)?.let { humanSize(it.bytes) } ?: ""}）"
                    } else {
                        "下载 Alpine rootfs（${humanSize(rootfs.bytes)}）"
                    },
                    onClick = { onInstallComponent(rootfs.id) },
                    icon = SmithyIcons.Download,
                    enabled = !rootfsInstalled,
                )
                Pill("导入工具链包…", onImportComponent, icon = SmithyIcons.OpenFolder)
                // 装了 rootfs、也有 root 的话，还有第三条更省事的路：在 rootfs 里装编译器
                if (rootfsInstalled && ShellChannels.current()?.available() == true) {
                    Pill("准备编译环境（在 rootfs 里装 clang）", onPrepareRootfs, icon = SmithyIcons.Package)
                }
            }
        }
        Spacer(Modifier.height(SmithySpacing.gap))
        if (ready) {
            // 可用时把路径写出来：编译失败时这几行就是第一手线索
            Text(
                toolchain!!.describe(),
                style = SmithyRowMeta,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // chroot 那条路还缺 target sysroot 的话，编译一定失败在缺 jni.h —— 先说清
            if (toolchain.name.contains("rootfs") && !sysrootInstalled) {
                Spacer(Modifier.height(SmithySpacing.gap))
                Row(horizontalArrangement = Arrangement.spacedBy(SmithySpacing.gap)) {
                    Pill(
                        "下载 Android sysroot（arm64-v8a，下载 ${humanSize(sysrootAddon.bytes)}，" +
                            "只留约 54MB）",
                        { onInstallComponent(sysrootAddon.id) },
                        icon = SmithyIcons.Download,
                    )
                }
            }
        } else {
            // 和 module.build、以及 NativeToolchains.require() 的异常同一句话
            WarningNote(NativeToolchains.missingHint())
        }
        if (built.isNotEmpty()) {
            Spacer(Modifier.height(SmithySpacing.gap))
            Text(
                "已编出：${built.joinToString()}",
                style = SmithyRowMeta.copy(fontFamily = SmithyMono),
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun EntriesCard(state: ModuleUiState, onEditEntry: (String) -> Unit) {
    Section("文件（${state.entries.size} 个）") {
        Text(
            "点文本条目进去改。二进制条目（so / dex）点不了 —— 文本编辑器改不了它们，" +
                "要改字节得用十六进制那条路。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(SmithySpacing.gap))

        state.entries.forEach { path ->
            val editable = isTextEntry(path)
            val kind = kindOf(path)
            Row(
                Modifier.fillMaxWidth()
                    .defaultMinSize(minHeight = 40.dp)
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = kind.icon,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    // 能点进去改的用主色、二进制压暗：一眼看出哪些行是有动作的
                    tint = if (editable) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                    },
                )
                Spacer(Modifier.width(SmithySpacing.iconGap))
                Text(
                    path,
                    style = SmithyRowMeta.copy(fontFamily = dev.smithy.design.SmithyMono),
                    color = if (editable) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (editable) {
                    Pill("编辑", { onEditEntry(path) }, icon = SmithyIcons.Rename)
                } else {
                    Tag("二进制")
                }
            }
        }
    }
}

/** 能当文本编辑的条目。和文件页那条判断同一个思路：按扩展名。 */
private fun isTextEntry(path: String): Boolean {
    val ext = path.substringAfterLast('.', "").lowercase()
    return ext !in setOf("so", "dex", "arsc", "png", "jpg", "jpeg", "webp", "gif", "ttf", "otf", "zip", "jar")
}

@Composable
private fun InstallCard(state: ModuleUiState, onInstall: () -> Unit) {
    Section("刷入") {
        Text(
            state.packaged?.let { "将刷入改动后的产物：${it.substringAfterLast('/')}" }
                ?: "还没改过，将刷入原包",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(SmithySpacing.gutter))
        Button(onClick = onInstall, enabled = state.rootOk, modifier = Modifier.fillMaxWidth()) {
            Icon(SmithyIcons.Download, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text("刷入设备")
        }
        if (!state.rootOk) {
            WarningNote(
                "刷入需要 root（Shizuku 权限不够：改不了 /data/adb/modules，也调不了 Magisk 的 CLI）。" +
                    "先去文件页授权 root。",
            )
        }
    }
}

@Composable
private fun DeviceCard(
    state: ModuleUiState,
    onSetEnabled: (String, Boolean) -> Unit,
    onScheduleRemove: (String) -> Unit,
    onUninstall: (String) -> Unit,
    onRestartZygote: () -> Unit,
) {
    Section("设备上的模块") {
        if (!state.rootOk) {
            Text(
                "看不到已安装的模块：需要 root。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Section
        }

        if (state.installed.isEmpty()) {
            Text(
                "设备上还没有已安装的模块",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            state.installed.forEach { id ->
                Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = SmithyIcons.KindModule,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.width(SmithySpacing.iconGap))
                        Text(
                            id,
                            style = SmithyRowTitle.copy(fontFamily = dev.smithy.design.SmithyMono),
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Spacer(Modifier.height(SmithySpacing.gap))
                    // 四个动作常驻（不藏进长按菜单）：这一页本来就是低频、看清了再动手的地方，
                    // 而且「停用」和「立即删」点错一个的代价差得很远，不该长得一样  —— 破坏性的那个走 error 色
                    Row(
                        Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(SmithySpacing.gap),
                    ) {
                        Pill("停用", { onSetEnabled(id, false) }, icon = SmithyIcons.Stop)
                        Pill("启用", { onSetEnabled(id, true) }, icon = SmithyIcons.Run)
                        Pill("标记卸载", { onScheduleRemove(id) }, icon = SmithyIcons.Delete)
                        Pill("立即删", { onUninstall(id) }, icon = SmithyIcons.Delete, danger = true)
                    }
                }
            }
        }

        Spacer(Modifier.height(SmithySpacing.gutter))
        Text(
            "停用 / 启用 / 标记卸载都是**下次重启后生效**。想反悔：停用就是删掉目录里的 disable 标记，" +
                "标记卸载就是删掉 remove 标记。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(SmithySpacing.gutter))
        OutlinedButton(
            onClick = onRestartZygote,
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(SmithyIcons.Warning, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text("软重启 zygote（让新模块生效）")
        }
        WarningNote("会影响所有正在运行的应用 —— 进程全部重建，没保存的东西会丢。")
    }
}
