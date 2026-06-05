package com.flowseal.tgwsandroid.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class NetworkRouteDebouncerTest {
    @Test
    fun debounceAppliesOnlyTheLatestNetworkState() {
        val scheduler = Executors.newSingleThreadScheduledExecutor()
        val applied = CopyOnWriteArrayList<String>()
        val latch = CountDownLatch(1)
        val debouncer = NetworkRouteDebouncer(scheduler, delayMs = 40) { status ->
            applied.add(status)
            latch.countDown()
        }
        try {
            debouncer.submit("mobile")
            debouncer.submit("Wi-Fi")

            assertTrue(latch.await(2, TimeUnit.SECONDS))
            Thread.sleep(80)

            assertEquals(listOf("Wi-Fi"), applied.toList())
        } finally {
            debouncer.cancel()
            scheduler.shutdownNow()
        }
    }
}
