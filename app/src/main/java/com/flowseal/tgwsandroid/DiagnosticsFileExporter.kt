package com.flowseal.tgwsandroid

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Writes diagnostics reports to app-private cache files that can be shared with a FileProvider. */
data class ExportedDiagnostics(val file: File, val uri: Uri)

class DiagnosticsFileExporter(
    private val context: Context,
    private val clock: Clock = Clock.systemDefaultZone(),
) {
    fun export(diagnosticsText: String): Uri = exportWithFile(diagnosticsText).uri

    fun exportWithFile(diagnosticsText: String): ExportedDiagnostics {
        val sharedLogsDir = File(context.cacheDir, SHARED_LOGS_DIR)
        sharedLogsDir.mkdirs()
        cleanupOldExports(sharedLogsDir)

        val file = File(sharedLogsDir, generateFileName(LocalDateTime.now(clock)))
        writeDiagnosticsText(file, diagnosticsText)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        return ExportedDiagnostics(file = file, uri = uri)
    }

    private fun cleanupOldExports(sharedLogsDir: File) {
        runCatching {
            val files = sharedLogsDir.listFiles { file -> file.isFile } ?: return
            files.sortedByDescending { it.lastModified() }
                .drop(MAX_EXPORTS_TO_KEEP - 1)
                .forEach { it.delete() }
        }
    }

    companion object {
        const val SHARED_LOGS_DIR = "shared_logs"
        private const val MAX_EXPORTS_TO_KEEP = 5
        private val FILE_TIMESTAMP_FORMATTER: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.US)

        fun generateFileName(timestamp: LocalDateTime): String =
            "tg-ws-android-diagnostics-${timestamp.format(FILE_TIMESTAMP_FORMATTER)}.txt"

        fun writeDiagnosticsText(file: File, diagnosticsText: String) {
            file.writeText(diagnosticsText, StandardCharsets.UTF_8)
        }
    }
}
