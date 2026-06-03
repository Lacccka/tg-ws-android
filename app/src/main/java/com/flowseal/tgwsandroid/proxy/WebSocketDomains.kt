package com.flowseal.tgwsandroid.proxy

/**
 * Telegram WebSocket host ordering ported from upstream `ws_domains`.
 *
 * The direct WebSocket TCP connection still targets the configured DC IP, while
 * TLS SNI, HTTP Host, and the WebSocket URL use one of these web domains.
 */
fun wsDomains(
    dc: Int,
    isMedia: Boolean,
): List<String> {
    val domainDc = if (dc == 203) 2 else dc
    val primary = "kws$domainDc.web.telegram.org"
    val mediaPreferred = "kws$domainDc-1.web.telegram.org"
    return if (isMedia) {
        listOf(mediaPreferred, primary)
    } else {
        listOf(primary, mediaPreferred)
    }
}
