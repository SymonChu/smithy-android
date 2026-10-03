package dev.smithy.feature.apk

import dev.smithy.engine.InstallChannel

/**
 * 装机通道的注册点。
 *
 * 为什么要有它：装机通道的实现必须碰 Android 平台 API（Shizuku 服务、Root shell、
 * 系统安装器 Intent），而 `:feature:apk` 这份 UI 代码不该把它们写死在这里 ——
 * 换一种装机方式、或将来把通道挪到别的模块，都不该改 UI。
 *
 * App 层在启动时注册（见 `dev.smithy.SmithyApp`）。没注册时是 null：
 * UI 仍然可以完整走「改包 → 打包 → 签名」，只有最后一步会明确告诉你没有通道，
 * 而不是假装装上了。
 */
object InstallChannelRegistry {

    @Volatile
    var install: InstallChannel? = null
        private set

    fun register(channel: InstallChannel?) {
        install = channel
    }
}
