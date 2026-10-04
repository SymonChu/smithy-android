plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

/**
 * 设计系统：色板、字阶、圆角、间距。
 *
 * **为什么单独一个模块**：它要被 app 和每个 feature 共用（文件页、工作台、模块页、
 * 对话、设置都要同一套观感）。放在任何 feature 里都会变成「别的 feature 反向依赖它」，
 * 而放进 app 又够不着（依赖方向是 app → feature，反过来不行）。
 */
android {
    namespace = "dev.smithy.design"
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
    // 用 api 而不是 implementation：用到主题的模块要在自己的 Composable 里直接写
    // MaterialTheme / Text / Surface / Card，这些符号必须传递可见 ——
    // 否则每个模块都得重复声明一遍 Compose 依赖，而漏掉的那次会在编译时才暴露
    api(platform(libs.androidx.compose.bom))
    api(libs.androidx.compose.ui)
    api(libs.androidx.compose.material3)
}
