plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "dev.smithy.feature.files"
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
    // 设计系统（色板/字阶/圆角/间距）。所有页面共用一套观感
    implementation(project(":core:design"))
    implementation(project(":core:engine"))
    implementation(project(":core:fs"))
    implementation(project(":core:ai"))
    implementation(project(":toolkit"))
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.coroutines.android)
    implementation(libs.serialization.json)
    // libsu：root shell。系统分区、别的应用的数据、/data/adb/modules 都要靠它 ——
    // 普通文件 API 只能看应用私有目录与用户授权的目录，有 root 也用不上。
    implementation(libs.libsu)
    // AndroidViewModel / viewModelScope
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    // 代码编辑器（LGPL-2.1，以未修改库形式引用，许可页已声明）。
    // **只引 editor，不引 language-textmate**：后者要求 API 33 以下开 core library desugaring，
    // 代价是一个额外的构建约束；而这里要编辑的是配置文件、脚本这类文本，纯文本编辑就够用。
    implementation(libs.sora.editor)
}
