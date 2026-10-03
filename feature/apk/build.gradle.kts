plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "dev.smithy.feature.apk"
    compileSdk = 35
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
}

dependencies {
    implementation(project(":core:engine"))
    implementation(project(":core:fs"))
    implementation(project(":core:ai"))
    implementation(project(":toolkit"))
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.activity.compose)          // SAF 选择器
    implementation(libs.androidx.lifecycle.viewmodel.compose) // viewModel()
    implementation(libs.coroutines.android)
    implementation(libs.serialization.json)

    // ── 装机通道（见 install/ 目录）──
    // 系统安装器通道要用 FileProvider 把 APK 暴露给安装器
    implementation(libs.androidx.core.ktx)
    // Shizuku：授权后可以以 shell 身份执行 pm install
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)
    // IShizukuService / IRemoteProcess 的 stub —— 装包靠它，见 AndroidInstallChannel
    implementation(libs.shizuku.aidl)
    // Root：libsu 跑 root shell
    implementation(libs.libsu)
}
