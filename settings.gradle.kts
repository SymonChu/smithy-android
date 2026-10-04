pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // ARSCLib / jadx-android / apksig-android 走 jitpack
        maven("https://jitpack.io")
    }
}

rootProject.name = "Smithy"

include(
    ":app",
    ":core:engine",
    ":core:fs",
    ":core:ai",
    ":core:mcp",
    ":core:design",
    ":toolkit",
    ":feature:apk",
    ":feature:files",
    ":feature:chat",
    ":feature:settings",
)

// :optional:rootfs —— M5 的可选 Linux 环境模块，届时作为独立 AAR 引入
