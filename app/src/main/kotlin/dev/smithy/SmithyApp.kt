package dev.smithy

import android.app.Application
import dev.smithy.feature.apk.InstallChannelRegistry
import dev.smithy.feature.apk.install.AndroidInstallChannel

class SmithyApp : Application() {

    override fun onCreate() {
        super.onCreate()
        // 装机通道是「引擎（纯 JVM）↔ 平台」的接缝，实现在 :feature:apk 的 install 包里，
        // 在这里注册进 Registry：引擎侧与 UI 都只认接口，不认识 Shizuku/Root/Intent。
        InstallChannelRegistry.register(AndroidInstallChannel(this))
    }
}
