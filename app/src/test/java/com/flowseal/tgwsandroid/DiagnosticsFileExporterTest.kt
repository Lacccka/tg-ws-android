package com.flowseal.tgwsandroid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Paths
import java.time.LocalDateTime

class DiagnosticsFileExporterTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun generatedFilenameUsesDiagnosticsPrefixAndTxtExtension() {
        val fileName = DiagnosticsFileExporter.generateFileName(
            LocalDateTime.of(2026, 6, 4, 12, 34, 56),
        )

        assertTrue(fileName.contains("tg-ws-android-diagnostics"))
        assertTrue(fileName.endsWith(".txt"))
        assertEquals("tg-ws-android-diagnostics-20260604-123456.txt", fileName)
    }

    @Test
    fun writeDiagnosticsTextPreservesContent() {
        val diagnosticsText = "Siberian Empire Proxy diagnostics\nLine with unicode: Привіт 🚀\nFinal line"
        val file = temporaryFolder.newFile("diagnostics.txt")

        DiagnosticsFileExporter.writeDiagnosticsText(file, diagnosticsText)

        assertEquals(diagnosticsText, file.readText(Charsets.UTF_8))
    }

    @Test
    fun fileProviderPathsUseUnqualifiedNameAndPathAttributes() {
        val xml = readFileProviderPathsXml()

        assertTrue(xml.contains("<cache-path"))
        assertTrue(xml.contains("name=\"shared_logs\""))
        assertTrue(xml.contains("path=\"shared_logs/\""))
        assertFalse(xml.contains("android:name=\"shared_logs\""))
        assertFalse(xml.contains("android:path=\"shared_logs/\""))
    }

    private fun readFileProviderPathsXml(): String {
        val userDir = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize()
        val candidates = generateSequence(userDir) { it.parent }
            .flatMap { directory ->
                sequenceOf(
                    directory.resolve("app/src/main/res/xml/file_paths.xml"),
                    directory.resolve("src/main/res/xml/file_paths.xml"),
                )
            }
            .map { it.normalize() }
            .distinct()
            .toList()

        val xmlPath = candidates.firstOrNull { Files.exists(it) }
            ?: throw AssertionError(
                "Unable to locate app/src/main/res/xml/file_paths.xml from user.dir=$userDir. " +
                    "Attempted paths:${System.lineSeparator()}" +
                    candidates.joinToString(System.lineSeparator()) { " - $it" },
            )

        return String(Files.readAllBytes(xmlPath), Charsets.UTF_8)
    }
}
