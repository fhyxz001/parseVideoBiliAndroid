// 国内镜像优先，失败后回落到官方源。
// CI 上仍走官方源（GitHub Actions 访问官方源更稳定）。
pluginManagement {
    val onCi = System.getenv("CI") == "true"
    repositories {
        if (onCi) {
            google()
            mavenCentral()
            gradlePluginPortal()
        } else {
            maven("https://maven.aliyun.com/repository/gradle-plugin")
            maven("https://maven.aliyun.com/repository/google")
            maven("https://maven.aliyun.com/repository/public")
            google()
            mavenCentral()
            gradlePluginPortal()
        }
    }
}

dependencyResolutionManagement {
    val onCi = System.getenv("CI") == "true"
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        if (onCi) {
            google()
            mavenCentral()
        } else {
            maven("https://maven.aliyun.com/repository/google")
            maven("https://maven.aliyun.com/repository/public")
            google()
            mavenCentral()
        }
    }
}

rootProject.name = "parseVideoBiliAndroid"
include(":app")
