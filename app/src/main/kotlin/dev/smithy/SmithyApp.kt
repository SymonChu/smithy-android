package dev.smithy

import android.app.Application
import dev.smithy.engine.ModuleChannels
import dev.smithy.feature.apk.InstallChannelRegistry
import dev.smithy.feature.apk.install.AndroidInstallChannel
import dev.smithy.feature.files.AndroidModuleChannel
import dev.smithy.fs.NativeToolchains
import java.io.File

class SmithyApp : Application() {

    override fun onCreate() {
        super.onCreate()
        // 装机通道是「引擎（纯 JVM）↔ 平台」的接缝，实现在 :feature:apk 的 install 包里，
        // 在这里注册进 Registry：引擎侧与 UI 都只认接口，不认识 Shizuku/Root/Intent。
        InstallChannelRegistry.register(AndroidInstallChannel(this))

        // 模块通道同理：实现在 :feature:files（用 libsu 调 magisk），引擎与工具层只认接口。
        // 注册表放在 core:engine 而不是 feature 里 —— 工具层（:toolkit）不依赖 feature 模块，
        // 放 feature 里 AI 就够不着，M6 会变成「有实现、没人能调」
        ModuleChannels.register(AndroidModuleChannel(this))

        // native 编译工具链：在应用目录里找（clang + sysroot 是 300–400MB 的下载项，
        // 不进主包）。找不到就**什么都不注册** —— 模块页与 module.build 会用「缺工具链」
        // 这条路径给出下一步，而不是先失败一次再解释。
        NativeToolchains.register(toolchainDir()?.let { NativeToolchains.locateIn(it) })
    }

    /**
     * 工具链该放哪儿。
     *
     * 私有目录而不是外部存储：它是可执行文件，Android 10 起不允许从外部存储执行，
     * 放在 `filesDir` 里才能被 `ProcessBuilder` 直接起起来。
     *
     * 认两种布局（见 [NativeToolchains.locateIn]）：Smithy 自己的 bundle
     * （`bin/clang++` + `sysroot/`），以及整包 NDK 解在这里的形态。
     */
    private fun toolchainDir(): File? =
        listOf(File(filesDir, "native-toolchain"), File(filesDir, "ndk"))
            .firstOrNull { it.isDirectory }
}
