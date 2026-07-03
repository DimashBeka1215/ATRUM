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
        // Для uCrop (Yalantis) — он хостится на JitPack
        maven { url = uri("https://jitpack.io") }
        // Guardian Project (tor-android/jtorctl) — мосты Tor (Snowflake/obfs4).
        // Свежие версии публикуются тут раньше, чем долетают до зеркала Maven Central.
        // См. TOR_BRIDGES_CONTINUE.md — путь B.
        maven { url = uri("https://raw.githubusercontent.com/guardianproject/gpmaven/master") }
    }
}

rootProject.name = "AtrumChat"
include(":app")
