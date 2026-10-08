plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

android {
    namespace = "dev.smithy"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.smithy"
        minSdk = 26          // Android 8.0 —— Shizuku 的使用场景下限
        targetSdk = 35
        versionCode = 7
        versionName = "0.1.5"
        // 只发 arm64（见 docs/07 构建与验证）
        ndk { abiFilters += "arm64-v8a" }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
        debug {
            applicationIdSuffix = ".debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    buildFeatures { compose = true }

    packaging {
        resources.excludes += setOf(
            "META-INF/*.kotlin_module",
            "META-INF/DEPENDENCIES",
            "META-INF/AL2.0",
            "META-INF/LGPL2.1",
            // 旧坐标 org.smali:baksmali:2.5.2（jadx 侧引入）与
            // com.android.tools.smali:smali-baksmali:3.0.10 都带这个版本描述文件，
            // 打包时报 "2 files found with path 'baksmali.properties'"。
            // 它只是版本号字符串，取其一即可。
            "baksmali.properties",
        )
    }
}

dependencies {
    // 设计系统（色板/字阶/圆角/间距）。所有页面共用一套观感
    implementation(project(":core:design"))
    implementation(project(":core:engine"))
    implementation(project(":core:fs"))
    implementation(project(":core:ai"))
    implementation(project(":core:mcp"))
    implementation(project(":toolkit"))

    implementation(project(":feature:apk"))
    implementation(project(":feature:files"))
    implementation(project(":feature:chat"))
    implementation(project(":feature:settings"))

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.activity.compose)

    implementation(libs.coroutines.android)
    implementation(libs.serialization.json)
    implementation(libs.aboutlibraries)

    debugImplementation(libs.androidx.compose.ui.tooling)
}
