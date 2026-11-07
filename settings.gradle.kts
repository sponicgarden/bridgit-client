pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google(); mavenCentral()
        // ADB client (libadb-android) is only published on JitPack; nothing else may come from there.
        maven("https://jitpack.io") { content { includeGroupByRegex("com\\.github\\.MuntashirAkon(\\..*)?") } }
    }
}
rootProject.name = "Bridgit"
include(":app")
