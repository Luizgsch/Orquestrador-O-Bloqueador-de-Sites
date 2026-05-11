package com.orquestrador.vpn

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class DnsResolverTest {

    @Before
    fun setUp() {
        DnsResolver.clearCache()
        DnsResolver.setProtect(null)
        DnsResolver.closeSocket()
    }

    @Test
    fun `cache stores and retrieves by name`() {
        val response = byteArrayOf(1, 2, 3, 4)
        DnsResolver.cache("example.com", response)
        assertArrayEquals(response, DnsResolver.getCached("example.com"))
    }

    @Test
    fun `getCached returns null for unknown name`() {
        assertNull(DnsResolver.getCached("notcached.com"))
    }

    @Test
    fun `clearCache removes all entries`() {
        DnsResolver.cache("a.com", byteArrayOf(1))
        DnsResolver.cache("b.com", byteArrayOf(2))
        DnsResolver.clearCache()
        assertNull(DnsResolver.getCached("a.com"))
        assertNull(DnsResolver.getCached("b.com"))
    }

    @Test
    fun `cache evicts eldest entry when capacity exceeded`() {
        for (i in 0 until 101) {
            DnsResolver.cache("domain$i.com", byteArrayOf(i.toByte()))
        }
        // domain0.com was LRU — evicted
        assertNull(DnsResolver.getCached("domain0.com"))
        // domain100.com was most recent — retained
        assertNotNull(DnsResolver.getCached("domain100.com"))
    }

    @Test
    fun `cache access updates LRU order`() {
        for (i in 0 until 100) {
            DnsResolver.cache("domain$i.com", byteArrayOf(i.toByte()))
        }
        // Access domain0 to make it recently used
        DnsResolver.getCached("domain0.com")
        // Adding domain100 should evict domain1 (now LRU), not domain0
        DnsResolver.cache("domain100.com", byteArrayOf(100.toByte()))
        assertNotNull(DnsResolver.getCached("domain0.com"))
        assertNull(DnsResolver.getCached("domain1.com"))
    }

    @Test
    fun `forwardToUpstream returns null when protect rejects socket`() {
        DnsResolver.setProtect { _ -> false }
        val minimalQuery = ByteArray(12)
        val result = DnsResolver.forwardToUpstream(minimalQuery, listOf("1.1.1.3"), null)
        assertNull(result)
    }
}
