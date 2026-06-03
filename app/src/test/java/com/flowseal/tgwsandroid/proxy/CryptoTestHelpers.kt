package com.flowseal.tgwsandroid.proxy

import org.json.JSONObject

fun Any.loadCryptoVectors(): List<JSONObject> {
    val stream = javaClass.classLoader!!.getResourceAsStream("crypto_vectors.json")
    val json = JSONObject(stream.reader(Charsets.UTF_8).readText())
    val vectors = json.getJSONArray("vectors")
    return buildList {
        for (index in 0 until vectors.length()) {
            add(vectors.getJSONObject(index))
        }
    }
}

fun String.hexToBytes(): ByteArray {
    require(length % 2 == 0)
    return ByteArray(length / 2) { index ->
        val high = Character.digit(this[index * 2], 16)
        val low = Character.digit(this[index * 2 + 1], 16)
        require(high >= 0 && low >= 0)
        ((high shl 4) or low).toByte()
    }
}

fun ByteArray.toHex(): String = joinToString(separator = "") { byte ->
    (byte.toInt() and 0xff).toString(16).padStart(2, '0')
}
