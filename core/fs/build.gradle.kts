plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "dev.smithy.core.fs"
    compileSdk = 35
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation(libs.coroutines.android)
    implementation(libs.simplemagic)
    // commons-net：FTP 客户端（FtpSession）。SMB/SFTP 的可靠实现都拖 JNI 或授权问题，
    // FTP 是「网络存储」里成本最低、且局域网 NAS 最常用的一个
    implementation(libs.commons.net)
    // okhttp：可选组件（AddOn）的下载。要断点续传、要跟随跳转、要边下边报进度 ——
    // 这几件事都得自己写才用 HttpURLConnection，而 okhttp 已经在依赖表里（core:ai / core:mcp 在用）
    implementation(libs.okhttp)

    testImplementation(kotlin("test"))
    testImplementation("junit:junit:4.13.2")
    // 下载逻辑的测试要一个真 HTTP 服务器（续传、206、校验失败都靠它造）
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}

tasks.withType<Test>().configureEach {
    testLogging {
        showStandardStreams = true
        events("passed", "failed", "skipped")
    }
}
