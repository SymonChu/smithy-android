plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
}

subprojects {
    configurations.configureEach {
        // jcommander 有两个坐标、同一套包名（com.beust.jcommander）：
        //   - com.beust:jcommander:1.64      （jadx 等旧依赖走这条）
        //   - org.jcommander:jcommander:1.85 （迁移后的新坐标）
        // 打 APK 时报 Duplicate class。新坐标是同一库的延续，排除旧坐标即可。
        exclude(group = "com.beust", module = "jcommander")
    }
}
