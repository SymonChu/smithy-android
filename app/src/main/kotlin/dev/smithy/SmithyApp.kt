package dev.smithy

import android.app.Application
import dev.smithy.engine.ModuleChannels
import dev.smithy.feature.apk.InstallChannelRegistry
import dev.smithy.feature.apk.install.AndroidInstallChannel
import dev.smithy.feature.files.AndroidModuleChannel

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
    }
}
