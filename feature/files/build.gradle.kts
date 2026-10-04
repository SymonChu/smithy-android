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
    implementation(project(":core:engine"))
    implementation(project(":core:fs"))
    implementation(project(":core:ai"))
    implementation(project(":toolkit"))
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.coroutines.android)
    implementation(libs.serialization.json)
    // AndroidViewModel / viewModelScope
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    // 代码编辑器（LGPL-2.1，以未修改库形式引用，许可页已声明）。
    // **只引 editor，不引 language-textmate**：后者要求 API 33 以下开 core library desugaring，
    // 代价是一个额外的构建约束；而这里要编辑的是配置文件、脚本这类文本，纯文本编辑就够用。
    implementation(libs.sora.editor)
}
