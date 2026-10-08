plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "dev.smithy.feature.settings"
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

    // 扩展页的状态层（分组 / 副标题 / 体积换算）是纯逻辑，可以在 JVM 上测 ——
    // 界面本身要真机肉眼验，那部分不假装测
    testImplementation(kotlin("test"))
    testImplementation("junit:junit:4.13.2")
}
