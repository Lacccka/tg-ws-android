package com.flowseal.tgwsandroid.proxy

import java.io.ByteArrayInputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.security.SecureRandom

/**
 * Offline WebSocket codec ported from upstream `proxy/raw_websocket.py`.
 *
 * This object intentionally contains only testable byte/string logic from
 * `RawWebSocket._build_frame`, `RawWebSocket._read_frame`,
 * `RawWebSocket.connect`, `WsHandshakeError`, and `_xor_mask`. It does not open
 * sockets, perform TLS, or manage a live WebSocket connection.
 */
object RawWebSocketCodec {
    const val OP_CONT: Int = 0x0
    const val OP_BINARY: Int = 0x2
    const val OP_CLOSE: Int = 0x8
    const val OP_PING: Int = 0x9
    const val OP_PONG: Int = 0xA
    const val MAX_MESSAGE_LEN: Int = 16 * 1024 * 1024

    private val secureRandom = SecureRandom()

    /** Parsed output of upstream-like `RawWebSocket._read_frame`. */
    data class Frame(
        val opcode: Int,
        val payload: ByteArray,
        val masked: Boolean,
        val length: Long,
        val fin: Boolean,
    )

    /** Offline result equivalent for `RawWebSocket.connect` response handling. */
    data class HandshakeResponse(
        val success: Boolean,
        val statusCode: Int,
        val statusLine: String,
        val headers: Map<String, String> = emptyMap(),
        val location: String? = null,
    ) {
        val isRedirect: Boolean
            get() = statusCode in setOf(301, 302, 303, 307, 308)

        val errorMessage: String?
            get() = if (success) null else "HTTP $statusCode: $statusLine"
    }

    /** Applies the upstream `_xor_mask` operation using a four-byte mask key. */
    fun xorMask(data: ByteArray, mask: ByteArray): ByteArray {
        require(mask.size == 4) { "mask must contain exactly 4 bytes" }
        if (data.isEmpty()) return data
        return ByteArray(data.size) { index ->
            (data[index].toInt() xor mask[index % 4].toInt()).toByte()
        }
    }

    /**
     * Builds a WebSocket frame with upstream `RawWebSocket._build_frame`
     * length and masking behavior. Frames are final by default; [fin] is exposed
     * for parity/regression tests covering fragmented upstream messages.
     */
    fun buildFrame(
        opcode: Int,
        data: ByteArray,
        mask: Boolean = false,
        randomProvider: (Int) -> ByteArray = { length ->
            ByteArray(length).also { secureRandom.nextBytes(it) }
        },
        fin: Boolean = true,
    ): ByteArray {
        val length = data.size
        val firstByte = (if (fin) 0x80 else 0x00) or (opcode and 0x0f)
        val header = mutableListOf<Byte>()
        header.add(firstByte.toByte())

        val maskBit = if (mask) 0x80 else 0x00
        when {
            length < 126 -> header.add((maskBit or length).toByte())
            length < 65_536 -> {
                header.add((maskBit or 126).toByte())
                header.add(((length ushr 8) and 0xff).toByte())
                header.add((length and 0xff).toByte())
            }
            else -> {
                header.add((maskBit or 127).toByte())
                val longLength = length.toLong()
                for (shift in 56 downTo 0 step 8) {
                    header.add(((longLength ushr shift) and 0xff).toByte())
                }
            }
        }

        if (!mask) return header.toByteArray() + data

        val maskKey = randomProvider(4)
        require(maskKey.size == 4) { "randomProvider must return exactly 4 bytes for frame masking" }
        return header.toByteArray() + maskKey + xorMask(data, maskKey)
    }

    /** Parses a WebSocket frame from a byte array using upstream `_read_frame` rules. */
    fun parseFrame(frame: ByteArray): Frame = parseFrame(ByteArrayInputStream(frame))

    /** Parses a WebSocket frame from a stream-like input using upstream `_read_frame` rules. */
    fun parseFrame(input: InputStream): Frame {
        val first = readByteOrThrow(input)
        val second = readByteOrThrow(input)
        val fin = (first and 0x80) != 0
        val opcode = first and 0x0f
        val masked = (second and 0x80) != 0
        var length = (second and 0x7f).toLong()
        if (length == 126L) {
            length = readUnsignedBigEndian(input, 2)
        } else if (length == 127L) {
            length = readUnsignedBigEndian(input, 8)
        }
        if (length < 0L || length > MAX_MESSAGE_LEN.toLong()) {
            throw IOException("WebSocket frame too large: $length bytes")
        }

        val maskKey = if (masked) readExact(input, 4) else null
        val payload = readExact(input, length.toInt())
        return Frame(
            opcode = opcode,
            payload = if (maskKey != null) xorMask(payload, maskKey) else payload,
            masked = masked,
            length = length,
            fin = fin,
        )
    }

    /** Builds the HTTP Upgrade request text from upstream `RawWebSocket.connect`. */
    fun buildUpgradeRequest(path: String = "/apiws", domain: String, secWebSocketKey: String): String =
        "GET $path HTTP/1.1\r\n" +
            "Host: $domain\r\n" +
            "Upgrade: websocket\r\n" +
            "Connection: Upgrade\r\n" +
            "Sec-WebSocket-Key: $secWebSocketKey\r\n" +
            "Sec-WebSocket-Version: 13\r\n" +
            "Sec-WebSocket-Protocol: binary\r\n" +
            "\r\n"

    /** Parses raw HTTP response bytes like upstream `RawWebSocket.connect` status handling. */
    fun parseHandshakeResponse(rawBytes: ByteArray): HandshakeResponse =
        parseHandshakeResponse(rawBytes.toString(Charsets.UTF_8).httpHeaderLines())

    /** Parses HTTP response lines like upstream `RawWebSocket.connect` status/header handling. */
    fun parseHandshakeResponse(lines: List<String>): HandshakeResponse {
        if (lines.isEmpty()) {
            return HandshakeResponse(success = false, statusCode = 0, statusLine = "empty response")
        }

        val firstLine = lines.first().trim()
        val parts = firstLine.split(" ", limit = 3)
        val statusCode = if (parts.size >= 2) parts[1].toIntOrNull() ?: 0 else 0
        val headers = linkedMapOf<String, String>()
        for (line in lines.drop(1)) {
            val colon = line.indexOf(':')
            if (colon >= 0) {
                headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
            }
        }

        return HandshakeResponse(
            success = statusCode == 101,
            statusCode = statusCode,
            statusLine = firstLine,
            headers = headers,
            location = headers["location"],
        )
    }

    private fun String.httpHeaderLines(): List<String> {
        if (isEmpty()) return emptyList()
        val lines = mutableListOf<String>()
        var offset = 0
        while (offset < length) {
            val nextLf = indexOf('\n', startIndex = offset)
            val rawLine = if (nextLf >= 0) substring(offset, nextLf + 1) else substring(offset)
            val stripped = rawLine.trimEnd('\r', '\n')
            if (stripped.isEmpty()) break
            lines.add(stripped)
            if (nextLf < 0) break
            offset = nextLf + 1
        }
        return lines
    }

    private fun readUnsignedBigEndian(input: InputStream, byteCount: Int): Long {
        var value = 0L
        repeat(byteCount) {
            value = (value shl 8) or readByteOrThrow(input).toLong()
        }
        return value
    }

    private fun readExact(input: InputStream, length: Int): ByteArray {
        val bytes = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val read = input.read(bytes, offset, length - offset)
            if (read < 0) throw EOFException("expected $length bytes, got $offset")
            offset += read
        }
        return bytes
    }

    private fun readByteOrThrow(input: InputStream): Int {
        val value = input.read()
        if (value < 0) throw EOFException("unexpected end of WebSocket frame")
        return value and 0xff
    }
}
