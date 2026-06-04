package com.flowseal.tgwsandroid.service

import java.io.File
import java.io.IOException

interface RuntimeLogPersistence {
    fun readTailLines(): List<String>
    fun appendLine(line: String)
    fun clear()
}

class FileRuntimeLogPersistence(
    private val logFile: File,
    private val maxBytes: Long = DEFAULT_MAX_BYTES,
) : RuntimeLogPersistence {
    init {
        require(maxBytes > 0) { "maxBytes must be positive" }
    }

    override fun readTailLines(): List<String> = runCatching {
        if (!logFile.exists()) return@runCatching emptyList()
        val bytes = logFile.readBytes()
        val tail = if (bytes.size > maxBytes) bytes.copyOfRange(bytes.size - maxBytes.toInt(), bytes.size) else bytes
        val text = tail.toString(Charsets.UTF_8).trimStart('\uFEFF', '\n', '\r')
        text.lineSequence().filter { it.isNotBlank() }.toList()
    }.getOrDefault(emptyList())

    override fun appendLine(line: String) {
        runCatching {
            logFile.parentFile?.mkdirs()
            logFile.appendText(line + "\n", Charsets.UTF_8)
            trimIfNeeded()
        }
    }

    override fun clear() {
        runCatching {
            logFile.parentFile?.mkdirs()
            if (logFile.exists()) {
                logFile.writeText("", Charsets.UTF_8)
            } else {
                logFile.createNewFile()
            }
        }
    }

    private fun trimIfNeeded() {
        if (!logFile.exists() || logFile.length() <= maxBytes) return
        val bytes = logFile.readBytes()
        val keep = bytes.copyOfRange((bytes.size - maxBytes.toInt()).coerceAtLeast(0), bytes.size)
        val firstNewline = keep.indexOf('\n'.code.toByte())
        val trimmed = if (firstNewline >= 0 && firstNewline + 1 < keep.size) {
            keep.copyOfRange(firstNewline + 1, keep.size)
        } else {
            keep
        }
        logFile.writeBytes(trimmed)
    }

    companion object {
        const val DEFAULT_MAX_BYTES: Long = 512L * 1024L
    }
}
