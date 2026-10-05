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

    testImplementation(kotlin("test"))
    testImplementation("junit:junit:4.13.2")
}

tasks.withType<Test>().configureEach {
    testLogging {
        showStandardStreams = true
        events("passed", "failed", "skipped")
    }
}
