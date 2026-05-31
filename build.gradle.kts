import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Delete
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import java.util.Base64

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

abstract class MaterializeSigningFilesTask : DefaultTask() {
    @get:Internal
    abstract val privateKey: Property<String>

    @get:Internal
    abstract val certificateChain: Property<String>

    @get:OutputFile
    abstract val privateKeyFile: RegularFileProperty

    @get:OutputFile
    abstract val certificateChainFile: RegularFileProperty

    @TaskAction
    fun materialize() {
        privateKeyFile.get().asFile.apply {
            parentFile.mkdirs()
            writeText(decodeSigningValue(privateKey.orNull ?: throw GradleException("PRIVATE_KEY is required for plugin signing.")))
        }
        certificateChainFile.get().asFile.apply {
            parentFile.mkdirs()
            writeText(
                decodeSigningValue(
                    certificateChain.orNull ?: throw GradleException("CERTIFICATE_CHAIN is required for plugin signing."),
                ),
            )
        }
    }

    private fun decodeSigningValue(value: String): String {
        val trimmed = value.trim()
        if (trimmed.startsWith("-----BEGIN")) return value
        return runCatching {
            String(Base64.getDecoder().decode(trimmed), Charsets.UTF_8)
        }.getOrElse { value }
    }
}

tasks.test {
    useJUnitPlatform()
}

val signingPrivateKeyFile = layout.buildDirectory.file("tmp/signing/private-key.pem")
val signingCertificateChainFile = layout.buildDirectory.file("tmp/signing/certificate-chain.pem")
val signingFilesDirectory = layout.buildDirectory.dir("tmp/signing")

val materializeSigningFiles by tasks.registering(MaterializeSigningFilesTask::class) {
    privateKey.set(providers.environmentVariable("PRIVATE_KEY"))
    certificateChain.set(providers.environmentVariable("CERTIFICATE_CHAIN"))
    privateKeyFile.set(signingPrivateKeyFile)
    certificateChainFile.set(signingCertificateChainFile)
    outputs.upToDateWhen { false }
}

val cleanSigningFiles by tasks.registering(Delete::class) {
    delete(signingFilesDirectory)
}

tasks.named("signPlugin") {
    dependsOn(materializeSigningFiles)
    finalizedBy(cleanSigningFiles)
}

tasks.named("verifyPluginSignature") {
    dependsOn(tasks.named("signPlugin"))
    finalizedBy(cleanSigningFiles)
}

cleanSigningFiles {
    mustRunAfter(tasks.named("verifyPluginSignature"))
}

kotlin {
    jvmToolchain(21)
}

intellijPlatform {
    // Searchable options are unnecessary for this Tool Window/action-only plugin and
    // slow down local verification noticeably.
    buildSearchableOptions = false

    signing {
        certificateChainFile.set(signingCertificateChainFile)
        privateKeyFile.set(signingPrivateKeyFile)
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
