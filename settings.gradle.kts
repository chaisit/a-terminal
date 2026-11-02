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
    }
}

rootProject.name = "android-terminal"
include(":app")
include(":terminal-core")
include(":backend-ssh")
include(":backend-termux")
include(":engine") // pure-Kotlin VT engine (zero Android deps) — round 9
include(":engine-render") // Android renderer (TextShaper API31+ / Paint fallback) — round 9 N7
