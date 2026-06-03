package com.flowseal.tgwsandroid.proxy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CfProxyDomainsTest {
    @Test
    fun defaultDomainsDecodeDeterministicallyLikeUpstream() {
        assertEquals(
            listOf(
                "pclead.co.uk",
                "offshor.co.uk",
                "cakeisalie.co.uk",
                "noskomnadzor.co.uk",
                "lovetrue.co.uk",
                "sorokdva.co.uk",
                "pyatdesyatdva.co.uk",
                "kartoshka.co.uk",
                "sorokodin.co.uk",
                "pyatdesyatodin.co.uk",
            ),
            CfProxyDomains.defaults,
        )
        assertEquals("example.org", CfProxyDomains.decodeDefaultDomain("example.org"))
    }

    @Test
    fun normalizeDropsInvalidAndDuplicateDomains() {
        assertEquals(
            listOf("example.com", "second-domain.co.uk"),
            CfProxyDomains.normalize(listOf(" Example.COM ", "example.com", "-bad.com", "second-domain.co.uk", "no-tld")),
        )
    }

    @Test
    fun balancerReturnsConfiguredOrderAndPromotesWorkingDomainPerDc() {
        val balancer = CfProxyBalancer(listOf("one.example", "two.example", "three.example"))

        assertEquals(listOf("one.example", "two.example", "three.example"), balancer.getDomainsForDc(5))
        assertTrue(balancer.updateDomainForDc(5, "three.example"))
        assertEquals(listOf("three.example", "one.example", "two.example"), balancer.getDomainsForDc(5))
        assertFalse(balancer.updateDomainForDc(5, "three.example"))
        assertEquals(listOf("one.example", "two.example", "three.example"), balancer.getDomainsForDc(2))
    }
}
