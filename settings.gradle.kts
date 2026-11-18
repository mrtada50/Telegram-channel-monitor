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
        // Only when the user explicitly chose a JitPack-hosted TDLib build.
        if (providers.gradleProperty("tdlibDep").orNull?.startsWith("com.github.") == true) {
            maven("https://jitpack.io")
        }
    }
}
rootProject.name = "TelegramChannelMonitor"
include(":app")
