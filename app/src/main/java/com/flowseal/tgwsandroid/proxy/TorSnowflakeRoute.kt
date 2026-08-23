package com.flowseal.tgwsandroid.proxy

import java.io.IOException

/** Dedicated outbound identity for Telegram WebSockets carried through Tor/Snowflake. */
const val TOR_SNOWFLAKE_ROUTE_TYPE: String = "tor-snowflake"

/**
 * The Tor/Snowflake runtime exists but is not ready to carry a WebSocket yet.
 * This is intentionally distinct from a network/connect failure so callers can
 * report warm-up/backoff without poisoning direct or Cloudflare health.
 */
class TorSnowflakeUnavailableException(message: String) : IOException(message)
