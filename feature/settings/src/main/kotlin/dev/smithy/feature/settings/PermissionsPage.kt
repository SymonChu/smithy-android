package dev.smithy.feature.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.smithy.design.SmithyCard
import dev.smithy.design.SmithyIcons
import dev.smithy.design.SmithyRowMeta
import dev.smithy.design.SmithySectionTitle
import dev.smithy.design.SmithySpacing
import dev.smithy.design.SmithyTopBar
import dev.smithy.design.rememberSmithyHaptics

/**
 * 权限页。
 *
 * **从扩展页里提出来的理由**：这一页管的是「这台设备上我能碰到什么」（root / 共享存储 /
 * adb 身份），扩展页管的是「我装了哪些东西」。两件事会在同一个决策里出现 ——
 * 「刷模块要 root，装 rootfs 也要 root」—— 但它们不是一类东西，混在一页里找起来费劲。
 *
 * **只陈述，不主动请求**（除了「所有文件访问」）：
 * - Shizuku 真要发起授权得走它的 AIDL，App 里没接。强行接会变成「点一下检测就弹授权框」，
 *   用户会觉得 App 在替他要权限。没授权就说没授权。
 * - 电池白名单 / 无障碍这些 **App 目前没有检测能力**，所以不列 —— 菜单里摆一条
 *   永远显示「未知」的项，比不摆更糟（用户会以为 App 坏了）。这一点在页尾写明。
 */
@Composable
fun PermissionsPage(
    state: ExtensionUiState,
    onRequestStorageAccess: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth()) {
        SmithyTopBar(
            title = "权限",
            subtitle = "决定这台设备上你能碰到什么",
        )
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(bottom = SmithySpacing.section),
            verticalArrangement = Arrangement.spacedBy(SmithySpacing.section),
        ) {
            SmithySectionTitle(text = "系统权限")
            SmithyCard {
                PermissionRow(
                    icon = SmithyIcons.Root,
                    title = "Root 能力",
                    desc = "以 su 身份执行命令。要用它装 rootfs、走 chroot 编译，或刷 Magisk 模块",
                    state = state.caps.rootGranted,
                    okText = "已获取",
                    badText = "未获取",
                )
                PermissionRow(
                    icon = SmithyIcons.Permission,
                    title = "Shizuku",
                    desc = "以 adb 身份执行命令（保活、杀进程）。刷模块需要 root 而不只是 Shizuku",
                    state = state.caps.shizukuGranted,
                    okText = "已授权",
                    badText = "未授权",
                )
                PermissionRow(
                    icon = SmithyIcons.Key,
                    title = "所有文件访问",
                    desc = "直接读写共享存储（下载、文档等）。扩展装在应用私有目录，" +
                        "这一项只影响你能不能操作那些目录里的文件",
                    state = state.caps.storageGranted,
                    okText = "已授权",
                    badText = "未开启",
                    onClick = if (!state.caps.storageGranted) onRequestStorageAccess else null,
                )
            }

            // 说清「没检测能力」这件事，比让人以为上面就是全部
            Text(
                "通知权限由系统在你首次用到时弹窗申请，没有独立开关。电池白名单与无障碍" +
                    "App 目前不检测 —— 上面没列的项不是忘了做，而是没有可靠的办法在这一页" +
                    "如实显示状态；摆一条永远写着「未知」的项，比不摆更糟。",
                style = SmithyRowMeta,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = SmithySpacing.gutter),
            )
        }
    }
}

/**
 * 权限行。
 *
 * 右端是「绿/灰状态 + 箭头」那一组。**箭头只在点得动的时候出现**（没开启且我们有
 * 下一步时）—— 给一个没有后续动作的行挂箭头，用户点了只会觉得界面坏了。
 */
@Composable
fun PermissionRow(
    icon: ImageVector,
    title: String,
    desc: String,
    state: Boolean,
    okText: String,
    badText: String,
    onClick: (() -> Unit)? = null,
) {
    val haptics = rememberSmithyHaptics()
    Row(
        Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = SmithySpacing.rowHeight)
            .then(
                if (onClick != null) {
                    Modifier.clickable {
                        haptics.tap()
                        onClick()
                    }
                } else {
                    Modifier
                },
            )
            .padding(vertical = SmithySpacing.rowVertical),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(SmithySpacing.iconSize))
        Spacer(Modifier.width(SmithySpacing.iconGap))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(
                desc,
                style = SmithyRowMeta,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(SmithySpacing.gap))
        Text(
            if (state) okText else badText,
            style = MaterialTheme.typography.labelMedium,
            color = if (state) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
        if (onClick != null) {
            Icon(
                SmithyIcons.ChevronRight,
                contentDescription = null,
                modifier = Modifier.size(16.dp).padding(start = 2.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}