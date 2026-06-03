package com.flowseal.tgwsandroid.proxy

/**
 * Bundled Cloudflare proxy base-domain defaults ported from upstream
 * `proxy/config.py::CFPROXY_DEFAULT_DOMAINS`.
 *
 * Upstream stores the defaults as lightly encoded `.com` strings and decodes
 * them by shifting each alphabetic character back by the number of alphabetic
 * characters in the pre-suffix label, then replacing `.com` with `.co.uk`.
 * Keeping that algorithm here makes the bundled defaults deterministic and
 * parity-testable without requiring network access.
 *
 * TODO: port upstream's remote `.github/cfproxy-domains.txt` refresh in a
 * future Android-independent component. This milestone intentionally uses only
 * bundled defaults.
 */
object CfProxyDomains {
    private val encodedDefaults = listOf(
        "virkgj.com",
        "vmmzovy.com",
        "mkuosckvso.com",
        "zaewayzmplad.com",
        "twdmbzcm.com",
        "awzwsldi.com",
        "clngqrflngqin.com",
        "tjacxbqtj.com",
        "bxaxtxmrw.com",
        "dmohrsgmohcrwb.com",
    )

    val defaults: List<String> = encodedDefaults.map(::decodeDefaultDomain)

    fun decodeDefaultDomain(encoded: String): String {
        if (!encoded.endsWith(".com")) return encoded
        val prefix = encoded.dropLast(4)
        val alphaCount = prefix.count { it.isLetter() }
        return prefix.map { char ->
            if (!char.isLetter()) {
                char
            } else {
                val base = if (char >= 'a') 'a'.code else 'A'.code
                (((char.code - base - alphaCount).floorMod(26)) + base).toChar()
            }
        }.joinToString(separator = "") + ".co.uk"
    }

    fun normalize(domains: List<String>): List<String> {
        val seen = mutableSetOf<String>()
        return domains.map { it.trim().lowercase() }
            .filter { isValidDomain(it) }
            .filter { seen.add(it) }
    }

    private fun isValidDomain(domain: String): Boolean {
        if (domain.isEmpty() || domain.length > 253) return false
        if (domain.startsWith('.') || domain.endsWith('.')) return false
        val labels = domain.split('.')
        if (labels.size < 2) return false
        for (label in labels) {
            if (label.isEmpty() || label.length > 63) return false
            if (label.startsWith('-') || label.endsWith('-')) return false
            if (!label.all { it.isLetterOrDigit() || it == '-' }) return false
        }
        val tld = labels.last()
        return tld.length >= 2 && tld.any { it.isLetter() }
    }

    private fun Int.floorMod(modulus: Int): Int = ((this % modulus) + modulus) % modulus
}
