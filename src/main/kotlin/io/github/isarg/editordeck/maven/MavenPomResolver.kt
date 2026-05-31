package io.github.isarg.editordeck.maven

import io.github.isarg.editordeck.EditorDeckBundle
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarFile
import kotlin.io.path.extension
import kotlin.io.path.name
import kotlin.io.path.nameWithoutExtension

/**
 * Open target for a resolved POM: either a real file or an entry inside the selected JAR.
 */
sealed class PomLocation {
    data class LocalPath(val path: Path) : PomLocation()
    data class JarEntry(val jarPath: Path, val entryPath: String) : PomLocation()
}

/**
 * Resolver result with enough context to open, ask the user, or explain a miss.
 */
sealed class PomResolution {
    data class Found(val location: PomLocation, val checkedDirectory: Path) : PomResolution()
    data class Multiple(val locations: List<PomLocation>, val checkedDirectory: Path) : PomResolution()
    data class Missing(val checkedDirectory: Path?, val message: String) : PomResolution()
}

interface MavenPomMessages {
    fun message(key: String, vararg params: Any): String
}

private object BundleMavenPomMessages : MavenPomMessages {
    override fun message(key: String, vararg params: Any): String =
        EditorDeckBundle.message(key, *params)
}

/**
 * Resolves the POM that best matches a selected dependency JAR or class inside a JAR.
 *
 * The search order is intentionally local-only: embedded POM, Maven-style sibling POM,
 * Gradle cache sibling hash directories, then same-directory fallback.
 */
class MavenPomResolver(
    private val messages: MavenPomMessages = BundleMavenPomMessages,
) {
    fun resolve(fileUrlOrPath: String?): PomResolution {
        val jarPath = extractJarPath(fileUrlOrPath)
            ?: return PomResolution.Missing(null, message("maven.pom.missing.no.local.jar.path"))
        val directory = jarPath.parent
            ?: return PomResolution.Missing(null, message("maven.pom.missing.jar.no.parent", jarPath))

        // Prefer the POM embedded in the selected JAR; it is the closest metadata source
        // and avoids Maven/Gradle cache layout differences when present.
        val jarPomCandidates = pomEntriesInsideJar(jarPath)
        when (jarPomCandidates.size) {
            1 -> return PomResolution.Found(PomLocation.JarEntry(jarPath, jarPomCandidates.single()), directory)
            else -> if (jarPomCandidates.size > 1) {
                return PomResolution.Multiple(
                    jarPomCandidates.map { PomLocation.JarEntry(jarPath, it) },
                    directory,
                )
            }
        }

        // Maven local repositories normally place artifact-version.pom next to the JAR.
        val expectedPom = expectedPomPath(jarPath)
        if (expectedPom != null && Files.isRegularFile(expectedPom)) {
            return PomResolution.Found(PomLocation.LocalPath(expectedPom), directory)
        }

        // Gradle keeps JARs and POMs in sibling hash directories under the same version.
        val gradleCachePom = resolveGradleCachePom(jarPath)
        if (gradleCachePom != null) {
            return gradleCachePom
        }

        if (!Files.isDirectory(directory)) {
            return PomResolution.Missing(
                directory,
                missingPomMessage("maven.pom.missing.directory.does.not.exist", jarPath, expectedPom, directory),
            )
        }

        val pomCandidates = Files.list(directory).use { stream ->
            stream
                .filter { Files.isRegularFile(it) && it.extension.equals("pom", ignoreCase = true) }
                .sorted()
                .toList()
        }

        return when (pomCandidates.size) {
            0 -> PomResolution.Missing(
                directory,
                missingPomMessage("maven.pom.missing.no.pom.next.to.jar", jarPath, expectedPom, directory),
            )
            1 -> PomResolution.Found(PomLocation.LocalPath(pomCandidates.single()), directory)
            else -> PomResolution.Multiple(pomCandidates.map { PomLocation.LocalPath(it) }, directory)
        }
    }

    fun jarEntryPath(location: PomLocation.JarEntry): String =
        "${location.jarPath.toString().replace('\\', '/')}!/${location.entryPath.removePrefix("/")}"

    fun extractJarPath(fileUrlOrPath: String?): Path? {
        if (fileUrlOrPath.isNullOrBlank()) return null
        // Accept VirtualFile URLs such as jar://...jar!/A.class, plain jar: paths, and
        // regular filesystem paths from Project View or editor context.
        val beforeEntry = fileUrlOrPath.substringBefore("!")
        val withoutJarScheme = beforeEntry.removePrefix("jar://").removePrefix("jar:")
        val normalized = withoutJarScheme.removePrefix("file://").removePrefix("file:")
        val jarString = when {
            normalized.contains(".jar", ignoreCase = true) ->
                normalized.substringBeforeLast(".jar") + ".jar"
            else -> normalized
        }
        if (!jarString.endsWith(".jar", ignoreCase = true)) return null
        return runCatching {
            if (jarString.startsWith("/") && jarString.getOrNull(2) == ':') {
                Path.of(jarString.drop(1))
            } else if (jarString.startsWith("file:", ignoreCase = true)) {
                Path.of(URI.create(jarString))
            } else {
                Path.of(jarString)
            }
        }.getOrNull()
    }

    private fun expectedPomPath(jarPath: Path): Path? {
        val fileName = jarPath.name
        if (!fileName.endsWith(".jar", ignoreCase = true)) return null
        val base = jarPath.nameWithoutExtension
        val parentName = jarPath.parent?.fileName?.toString()
        val artifactName = jarPath.parent?.parent?.fileName?.toString()

        // Classifier JARs like artifact-1.0-sources.jar still map to artifact-1.0.pom.
        val pomBase = if (artifactName != null && parentName != null && base.startsWith("$artifactName-$parentName")) {
            "$artifactName-$parentName"
        } else {
            base
        }
        return jarPath.parent.resolve("$pomBase.pom")
    }

    private fun pomEntriesInsideJar(jarPath: Path): List<String> {
        if (!Files.isRegularFile(jarPath)) return emptyList()
        return runCatching {
            JarFile(jarPath.toFile()).use { jar ->
                val entries = jar.entries().asSequence()
                    .filterNot { it.isDirectory }
                    .map { it.name }
                    .sorted()
                    .toList()
                // META-INF/maven is the conventional location. Fall back to any pom.xml
                // because some JARs are packaged with non-standard metadata paths.
                entries
                    .filter { it.startsWith("META-INF/maven/") && it.endsWith("/pom.xml") } +
                    entries.filter { it == "pom.xml" || it.endsWith("/pom.xml") }
                        .filterNot { it.startsWith("META-INF/maven/") }
            }
        }.getOrDefault(emptyList())
    }

    private fun resolveGradleCachePom(jarPath: Path): PomResolution? {
        val hashDirectory = jarPath.parent ?: return null
        val versionDirectory = hashDirectory.parent ?: return null
        val normalized = versionDirectory.toString().replace('\\', '/')
        if (!normalized.contains("/modules-2/files-2.1/")) return null
        if (!Files.isDirectory(versionDirectory)) return null

        val expectedName = expectedPomPath(jarPath)?.fileName?.toString()
        val pomCandidates = Files.walk(versionDirectory, 2).use { stream ->
            stream
                .filter { Files.isRegularFile(it) && it.extension.equals("pom", ignoreCase = true) }
                .sorted()
                .toList()
        }
        val expected = pomCandidates.firstOrNull { it.fileName.toString() == expectedName }
        return when {
            expected != null -> PomResolution.Found(PomLocation.LocalPath(expected), versionDirectory)
            pomCandidates.size == 1 -> PomResolution.Found(PomLocation.LocalPath(pomCandidates.single()), versionDirectory)
            pomCandidates.size > 1 -> PomResolution.Multiple(pomCandidates.map { PomLocation.LocalPath(it) }, versionDirectory)
            else -> null
        }
    }

    private fun missingPomMessage(
        reasonKey: String,
        jarPath: Path,
        expectedPom: Path?,
        directory: Path,
    ): String = buildString {
        appendLine(message(reasonKey))
        appendLine(message("maven.pom.missing.checked.jar", jarPath))
        appendLine(message("maven.pom.missing.expected.pom", expectedPom ?: message("common.not.available")))
        append(message("maven.pom.missing.checked.directory", directory))
    }

    private fun message(key: String, vararg params: Any): String =
        messages.message(key, *params)
}
