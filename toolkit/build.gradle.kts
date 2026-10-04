// :toolkit —— 工具层，UI 与 AI 共用的唯一能力入口
//
// 为什么单独一个模块：工具集是「AI 能做什么」的全部定义。把它和 UI 分开，
// 才能保证「手动操作」和「AI 操作」走的是同一条路径 —— 否则迟早会出现
// 「AI 改了东西但用户在改动列表里看不到」这种事。
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    // ToolSpec / ToolResult 标了 @Serializable。少了这个插件编译也能过
    // （注解只是元数据），直到运行时要真序列化才炸 —— 而模型协议就是 JSON。
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "dev.smithy.toolkit"
    compileSdk = 35
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    testOptions { unitTests.isReturnDefaultValues = true }
}

dependencies {
    // api 而不是 implementation：Tool 接口直接暴露 ApkProject，
    // 实现工具的模块（以及将来的 AI 层）都得看到引擎类型。
    api(project(":core:engine"))
    implementation(project(":core:fs"))
    implementation(libs.coroutines.android)
    implementation(libs.serialization.json)

    testImplementation(kotlin("test"))
    testImplementation("junit:junit:4.13.2")   // 与 :core:engine 保持一致
    testImplementation(libs.coroutines.core)
}

tasks.withType<Test>().configureEach {
    testLogging {
        showStandardStreams = true
        events("passed", "failed", "skipped")
    }
}
