// :core:engine —— APK 引擎
//
// 为什么是 Android library 而不是纯 JVM 模块：
//   apksig-android、zstd-jni 这些依赖发布的是 aar，纯 JVM 模块（java-library/kotlin-jvm）
//   消费不了（"Incompatible because this component declares 'aar' and the consumer
//   needed class files"）。引擎最终也要跑在 Android 运行时的，所以这里用 android-library。
//
// 但代码约束不变，请守住：
//   **本模块不 import android.* / androidx.***，保持逻辑纯 JVM、可移植、可脱离设备测试。
//   Android 的接缝（Context、SAF、Shizuku）放在 app 与 feature 模块里。
//   单元测试跑在 src/test（JVM 上执行，不需要模拟器）。
//
// 注：插件用不带版本的 id()，理由见原注释（Kotlin 同族插件共用 kotlin-gradle-plugin，
//     子项目带版本声明会报 "already on the classpath with an unknown version"）。
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "dev.smithy.engine"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    // APK 解包 / 回编 / 资源表 / 签名
    api(libs.arsclib)
    api(libs.apksig.android)
    implementation(libs.smali)
    implementation(libs.smali.baksmali)
    implementation(libs.jadx.core)
    implementation(libs.jadx.dex.input)
    implementation(libs.bouncycastle)
    implementation(libs.bouncycastle.pkix)
    implementation(libs.simplemagic)
    implementation(libs.zstd)

    implementation(libs.coroutines.core)
    implementation(libs.serialization.json)

    testImplementation(kotlin("test"))
    testImplementation(libs.coroutines.core)
}

tasks.withType<Test>().configureEach {
    // Android library 的单元测试默认跑 JUnit4，不配 useJUnitPlatform()
    maxHeapSize = "2g"
    // 实测项见 docs/08-risks.md 第六节：
    //  - ARSCLib 处理 5MB / 20MB / 50MB resources.arsc 的内存占用
    //  - APKEditor 的 engine API 能否作为库调用（而非只能走 CLI）
}
