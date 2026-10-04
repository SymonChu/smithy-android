plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    // ChatMessage / ToolCall 都要序列化成上游要求的 JSON，缺了它运行时会炸
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

android {
    namespace = "dev.smithy.core.ai"
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
    api(project(":core:engine"))
    implementation(project(":toolkit"))
    implementation(libs.coroutines.android)
    implementation(libs.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.okhttp.sse)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    testImplementation(kotlin("test"))
    testImplementation("junit:junit:4.13.2")
    testImplementation(libs.coroutines.core)
    // SSE 解析是最容易出错的一环（分片、[DONE]、流里的错误对象），
    // 用真的 HTTP 服务端来测，而不是去 mock OkHttp 的内部行为
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}

tasks.withType<Test>().configureEach {
    testLogging {
        showStandardStreams = true
        events("passed", "failed", "skipped")
    }
}
