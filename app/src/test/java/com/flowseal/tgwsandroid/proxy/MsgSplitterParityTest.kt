package com.flowseal.tgwsandroid.proxy

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class MsgSplitterParityTest {
    @Test
    fun matchesUpstreamPartsAndFlushForChunkBoundaries() {
        for (vector in loadSplitterVectors()) {
            val splitter = MsgSplitter(
                relayInit = vector.getString("relay_init_hex").hexToBytes(),
                protoInt = vector.getLong("proto_int").toInt(),
            )
            val chunks = vector.getJSONArray("chunks_hex")
            val expectedPerChunk = vector.getJSONArray("expected_parts_per_chunk_hex")

            assertEquals(vector.getString("name"), chunks.length(), expectedPerChunk.length())
            for (chunkIndex in 0 until chunks.length()) {
                val actual = splitter.split(chunks.getString(chunkIndex).hexToBytes()).map { it.toHex() }
                val expected = expectedPerChunk.getJSONArray(chunkIndex).let { parts ->
                    List(parts.length()) { partIndex -> parts.getString(partIndex) }
                }
                assertEquals("${vector.getString("name")} chunk $chunkIndex", expected, actual)
            }

            val actualFlush = splitter.flush().map { it.toHex() }
            val expectedFlush = vector.getJSONArray("expected_flush_parts_hex").let { parts ->
                List(parts.length()) { index -> parts.getString(index) }
            }
            assertEquals("${vector.getString("name")} flush", expectedFlush, actualFlush)
        }
    }

    private fun loadSplitterVectors(): List<JSONObject> {
        val stream = javaClass.classLoader!!.getResourceAsStream("splitter_vectors.json")
        val json = JSONObject(stream.reader(Charsets.UTF_8).readText())
        val vectors = json.getJSONArray("vectors")
        return List(vectors.length()) { index -> vectors.getJSONObject(index) }
    }
}
