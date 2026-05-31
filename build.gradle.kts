import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.intellij.platform")
    id("org.jetbrains.changelog")
}

repositories {
    // Keep project-level repositories because the local init.gradle injects repositories
    // into all projects; without this block Gradle ignores the settings-level IntelliJ
    // Platform repositories and tries to resolve the IDE only from the injected mirror.
    maven("https://maven.aliyun.com/repository/public")
    mavenCentral()

    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testRuntimeOnly("junit:junit:4.13.2")

    intellijPlatform {
        create("IC", "2025.1")
        bundledPlugin("com.intellij.java")
        testFramework(TestFrameworkType.Platform)
    }
}

tasks.test {
    useJUnitPlatform()
}

kotlin {
    jvmToolchain(21)
}

intellijPlatform {
    // Searchable options are unnecessary for this Tool Window/action-only plugin and
    // slow down local verification noticeably.
    buildSearchableOptions = false

    signing {
        certificateChain.set(providers.environmentVariable("CERTIFICATE_CHAIN"))
        privateKey.set(providers.environmentVariable("PRIVATE_KEY"))
        password.set(providers.environmentVariable("PRIVATE_KEY_PASSWORD"))
    }

    publishing {
        token.set(providers.environmentVariable("PUBLISH_TOKEN"))
    }

    pluginConfiguration {
        name = "Editor Deck"

        ideaVersion {
            sinceBuild = "251"
        }

        description = """
            Adds an Edge/VS Code-style vertical editor deck for open files, plus quick Maven/Gradle POM access for dependency JARs.
        """.trimIndent()

        changeNotes = """
            <ul>
              <li>Migrate Editor Deck into the IntelliJ Platform Plugin Template project structure.</li>
              <li>Add Editor Deck tool window with open editor grouping, pinning, drag sorting, and Maven/Gradle POM lookup.</li>
              <li>Add signing and publishing configuration for release builds.</li>
            </ul>
        """.trimIndent()
    }
}
