package com.flowseal.tgwsandroid.proxy

/**
 * Small Android-independent CF-proxy base-domain balancer ported from upstream
 * `proxy/balancer.py`.
 *
 * Unlike upstream's random shuffling, this implementation is deterministic for
 * repeatable Android JVM tests: the active domain for a DC is yielded first,
 * followed by the remaining normalized domains in configured order.
 */
class CfProxyBalancer(domains: List<String> = CfProxyDomains.defaults) {
    private var domains: List<String> = CfProxyDomains.normalize(domains)
    private val activeByDc = mutableMapOf<Int, String>()

    fun updateDomainsList(domainsList: List<String>) {
        val normalized = CfProxyDomains.normalize(domainsList)
        if (domains == normalized) return
        domains = normalized
        activeByDc.clear()
    }

    fun updateDomainForDc(dcId: Int, domain: String): Boolean {
        val normalized = domain.trim().lowercase()
        if (normalized !in domains) return false
        if (activeByDc[dcId] == normalized) return false
        activeByDc[dcId] = normalized
        return true
    }

    fun getDomainsForDc(dcId: Int): List<String> {
        val active = activeByDc[dcId]
        return buildList {
            if (active != null && active in domains) add(active)
            for (domain in domains) {
                if (domain != active) add(domain)
            }
        }
    }
}
