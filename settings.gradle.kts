import org.jetbrains.intellij.platform.gradle.extensions.intellijPlatform

rootProject.name = "editor-deck"

pluginManagement {
    repositories {
        // Keep plugin resolution friendly for the local China network setup.
        maven("https://maven.aliyun.com/repository/public")
        gradlePluginPortal()
        mavenCentral()
    }

    plugins {
        id("org.jetbrains.kotlin.jvm") version "2.1.20"
        id("org.jetbrains.changelog") version "2.5.0"
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
    id("org.jetbrains.intellij.platform.settings") version "2.16.0"
}

@Suppress("UnstableApiUsage")
dependencyResolutionManagement {
    repositories {
        // Do not use FAIL_ON_PROJECT_REPOS here: the local init.gradle intentionally
        // injects repositories for this workspace.
        maven("https://maven.aliyun.com/repository/public")
        mavenCentral()

        intellijPlatform {
            defaultRepositories()
        }
    }
}
