import java.util.Properties

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
        maven("https://jitpack.io") {
            content { includeGroup("com.github.mik3y") }
        }
        // com.progeo:progeo-core from github.com/Progeo-Monitoring-GmbH/progeo-android-core.
        // Reading needs a token with read:packages: gpr.user / gpr.key in ~/.gradle/gradle.properties.
        maven("https://maven.pkg.github.com/Progeo-Monitoring-GmbH/progeo-android-core") {
            credentials {
                username = providers.gradleProperty("gpr.user").orElse(providers.environmentVariable("GITHUB_ACTOR")).orNull
                password = providers.gradleProperty("gpr.key").orElse(providers.environmentVariable("GITHUB_TOKEN")).orNull
            }
            content { includeGroup("com.progeo") }
        }
    }
}

rootProject.name = "lua-flasher"
include(":core", ":teltonika", ":app")

// Working on progeo-core at the same time: progeo.core.dir=../progeo-android-core in
// local.properties builds against that checkout instead of the published package.
val local = Properties().apply {
    file("local.properties").takeIf { it.isFile }?.inputStream()?.use { load(it) }
}
local.getProperty("progeo.core.dir")?.let { includeBuild(it) }
