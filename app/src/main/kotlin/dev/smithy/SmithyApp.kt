package dev.smithy

import android.app.Application
import dev.smithy.engine.ModuleChannels
import dev.smithy.feature.apk.InstallChannelRegistry
import dev.smithy.feature.apk.install.AndroidInstallChannel
import dev.smithy.feature.files.AndroidModuleChannel
import dev.smithy.fs.AddOnHost
import dev.smithy.fs.AddOnManager
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

        // 可选组件（rootfs / native 工具链……）装到私有目录：不用 root、随时能读写。
        // 装完顺手扫一遍工具链 —— 用户可能刚把一份传到手机上，也可能上次装过了。
        AddOnHost.install(AddOnManager(addOnRoot()))
        rescanToolchain()
    }

    /**
     * 可选组件的落点。
     *
     * 为什么是私有目录而不是 `/data/local/tmp`：后者更「可执行」，但**要 root 才能写**
     * （在那儿探测一次 root 得在 Application 里同步等 libsu，会拖慢启动甚至 ANR），
     * 而用户从电脑传东西过去时也看不见它。所以默认落在私有目录；真跑不动的时候，
     * 根因是「设备不允许从应用目录执行」还是别的，模块页会给出编译器的原话。
     */
    internal fun addOnRoot(): File = File(filesDir, "addon").apply { mkdirs() }

    /** 重扫工具链。装完可选组件、或用户手工放了一份之后都要调它。 */
    internal fun rescanToolchain(): dev.smithy.fs.NativeToolchain? =
        NativeToolchains.scan(*NativeToolchains.standardRoots(filesDir).toTypedArray())
}
