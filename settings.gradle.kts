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
    }
}

rootProject.name = "AtrumChat"
include(":app")
