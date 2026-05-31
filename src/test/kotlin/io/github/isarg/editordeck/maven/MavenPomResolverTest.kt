package io.github.isarg.editordeck.maven

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream

class MavenPomResolverTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `resolves pom next to jar from jar url`() {
        val jar = tempDir.resolve("repo/com/acme/demo/1.2.3/demo-1.2.3.jar")
        val pom = tempDir.resolve("repo/com/acme/demo/1.2.3/demo-1.2.3.pom")
        Files.createDirectories(jar.parent)
        Files.writeString(jar, "jar")
        Files.writeString(pom, "<project/>")

        val result = MavenPomResolver().resolve("jar:///${jar.toString().replace('\\', '/') }!/com/acme/Demo.class")

        assertEquals(PomResolution.Found(PomLocation.LocalPath(pom), jar.parent), result)
    }

    @Test
    fun `resolves pom inside jar before local repository pom`() {
        val jar = tempDir.resolve("repo/com/acme/demo/1.2.3/demo-1.2.3.jar")
        val localPom = tempDir.resolve("repo/com/acme/demo/1.2.3/demo-1.2.3.pom")
        Files.createDirectories(jar.parent)
        writeJar(
            jar,
            mapOf(
                "META-INF/maven/com.acme/demo/pom.xml" to "<project><artifactId>demo</artifactId></project>",
                "com/acme/Demo.class" to "class",
            ),
        )
        Files.writeString(localPom, "<project><artifactId>local</artifactId></project>")

        val result = MavenPomResolver().resolve("jar:///${jar.toString().replace('\\', '/') }!/com/acme/Demo.class")

        assertEquals(
            PomResolution.Found(PomLocation.JarEntry(jar, "META-INF/maven/com.acme/demo/pom.xml"), jar.parent),
            result,
        )
    }

    @Test
    fun `resolves plain pom xml inside jar before local repository pom`() {
        val jar = tempDir.resolve("repo/com/acme/demo/1.2.3/demo-1.2.3.jar")
        val localPom = tempDir.resolve("repo/com/acme/demo/1.2.3/demo-1.2.3.pom")
        Files.createDirectories(jar.parent)
        writeJar(
            jar,
            mapOf(
                "pom.xml" to "<project><artifactId>jar</artifactId></project>",
                "com/acme/Demo.class" to "class",
            ),
        )
        Files.writeString(localPom, "<project><artifactId>local</artifactId></project>")

        val result = MavenPomResolver().resolve(jar.toString())

        assertEquals(
            PomResolution.Found(PomLocation.JarEntry(jar, "pom.xml"), jar.parent),
            result,
        )
    }

    @Test
    fun `creates canonical jar entry path for a pom inside jar`() {
        val jar = tempDir.resolve("repo/com/acme/demo/1.2.3/demo-1.2.3.jar")
        Files.createDirectories(jar.parent)
        writeJar(jar, mapOf("META-INF/maven/com.acme/demo/pom.xml" to "<project/>"))
        val result = MavenPomResolver().resolve(jar.toString())
        result as PomResolution.Found

        val path = MavenPomResolver().jarEntryPath((result.location as PomLocation.JarEntry))

        assertEquals("${jar.toString().replace('\\', '/')}!/META-INF/maven/com.acme/demo/pom.xml", path)
    }

    @Test
    fun `resolves pom next to ordinary jar path`() {
        val jar = tempDir.resolve("repo/com/acme/demo/1.2.3/demo-1.2.3-sources.jar")
        val pom = tempDir.resolve("repo/com/acme/demo/1.2.3/demo-1.2.3.pom")
        Files.createDirectories(jar.parent)
        Files.writeString(jar, "jar")
        Files.writeString(pom, "<project/>")

        val result = MavenPomResolver().resolve(jar.toString())

        assertEquals(PomResolution.Found(PomLocation.LocalPath(pom), jar.parent), result)
    }

    @Test
    fun `resolves gradle cached pom from sibling artifact hash directory`() {
        val versionDir = tempDir.resolve("modules-2/files-2.1/com.acme/demo/1.2.3")
        val jar = versionDir.resolve("jar-hash/demo-1.2.3.jar")
        val pom = versionDir.resolve("pom-hash/demo-1.2.3.pom")
        Files.createDirectories(jar.parent)
        Files.createDirectories(pom.parent)
        Files.writeString(jar, "jar")
        Files.writeString(pom, "<project/>")

        val result = MavenPomResolver().resolve(jar.toString())

        assertEquals(PomResolution.Found(PomLocation.LocalPath(pom), versionDir), result)
    }

    @Test
    fun `reports multiple poms when fallback finds more than one candidate`() {
        val jar = tempDir.resolve("repo/com/acme/demo/1.2.3/custom-name.jar")
        val firstPom = jar.parent.resolve("a.pom")
        val secondPom = jar.parent.resolve("b.pom")
        Files.createDirectories(jar.parent)
        Files.writeString(jar, "jar")
        Files.writeString(firstPom, "<project/>")
        Files.writeString(secondPom, "<project/>")

        val result = MavenPomResolver().resolve(jar.toString())

        assertEquals(
            PomResolution.Multiple(listOf(PomLocation.LocalPath(firstPom), PomLocation.LocalPath(secondPom)), jar.parent),
            result,
        )
    }

    @Test
    fun `reports missing pom with checked directory`() {
        val jar = tempDir.resolve("repo/com/acme/demo/1.2.3/demo-1.2.3.jar")
        val expectedPom = jar.parent.resolve("demo-1.2.3.pom")
        Files.createDirectories(jar.parent)
        Files.writeString(jar, "jar")

        val result = MavenPomResolver().resolve(jar.toString())

        assertTrue(result is PomResolution.Missing)
        result as PomResolution.Missing
        assertEquals(jar.parent, result.checkedDirectory)
        assertTrue(result.message.contains(jar.toString()))
        assertTrue(result.message.contains(expectedPom.toString()))
    }

    @Test
    fun `reports missing when jar directory does not exist`() {
        val jar = tempDir.resolve("missing/repo/com/acme/demo/1.2.3/demo-1.2.3.jar")

        val result = MavenPomResolver().resolve(jar.toString())

        assertTrue(result is PomResolution.Missing)
        result as PomResolution.Missing
        assertEquals(jar.parent, result.checkedDirectory)
        assertTrue(result.message.contains("Directory does not exist"))
    }

    @Test
    fun `reports missing pom details through message provider`() {
        val jar = tempDir.resolve("repo/com/acme/demo/1.2.3/demo-1.2.3.jar")
        val expectedPom = jar.parent.resolve("demo-1.2.3.pom")
        Files.createDirectories(jar.parent)
        Files.writeString(jar, "jar")
        val resolver = MavenPomResolver(
            messages = object : MavenPomMessages {
                override fun message(key: String, vararg params: Any): String =
                    when (key) {
                        "maven.pom.missing.no.pom.next.to.jar" -> "未在所选 JAR 旁找到 POM。"
                        "maven.pom.missing.checked.jar" -> "已检查 JAR：${params[0]}"
                        "maven.pom.missing.expected.pom" -> "预期 POM：${params[0]}"
                        "maven.pom.missing.checked.directory" -> "已检查目录：${params[0]}"
                        else -> error("Unexpected key: $key")
                    }
            },
        )

        val result = resolver.resolve(jar.toString())

        assertTrue(result is PomResolution.Missing)
        result as PomResolution.Missing
        assertEquals(
            """
            未在所选 JAR 旁找到 POM。
            已检查 JAR：$jar
            预期 POM：$expectedPom
            已检查目录：${jar.parent}
            """.trimIndent(),
            result.message,
        )
    }

    private fun writeJar(path: Path, entries: Map<String, String>) {
        JarOutputStream(Files.newOutputStream(path)).use { jar ->
            entries.forEach { (name, content) ->
                jar.putNextEntry(JarEntry(name))
                jar.write(content.toByteArray())
                jar.closeEntry()
            }
        }
    }
}
