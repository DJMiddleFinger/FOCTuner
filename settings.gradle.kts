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

rootProject.name = "FOCTuner"

include(
    ":app",
    ":core:protocol",
    ":core:transport",
    ":core:config",
    ":core:vesc",
    ":core:sim",
)
