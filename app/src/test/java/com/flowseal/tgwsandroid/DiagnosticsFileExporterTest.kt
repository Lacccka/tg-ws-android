package com.flowseal.tgwsandroid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
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
        val diagnosticsText = "TG WS Android diagnostics\nLine with unicode: Привіт 🚀\nFinal line"
        val file = temporaryFolder.newFile("diagnostics.txt")

        DiagnosticsFileExporter.writeDiagnosticsText(file, diagnosticsText)

        assertEquals(diagnosticsText, file.readText(Charsets.UTF_8))
    }
    @Test
    fun fileProviderPathsUseUnqualifiedNameAndPathAttributes() {
        val xml = java.io.File("app/src/main/res/xml/file_paths.xml").readText()

        assertTrue(xml.contains("name=\"shared_logs\""))
        assertTrue(xml.contains("path=\"shared_logs/\""))
        assertFalse(xml.contains("android:name="))
        assertFalse(xml.contains("android:path="))
    }

}
