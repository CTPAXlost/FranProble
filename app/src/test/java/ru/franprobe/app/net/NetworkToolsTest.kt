package ru.franprobe.app.net

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkToolsTest {
    @Test
    fun acceptsValidIpv4AndIpv6Literals() {
        assertTrue(NetworkTools.isIpLiteral("77.88.8.8"))
        assertTrue(NetworkTools.isIpLiteral("2001:4860:4860::8888"))
        assertTrue(NetworkTools.isIpLiteral("[2606:4700:4700::1111]"))
    }

    @Test
    fun rejectsDomainsAndMalformedAddresses() {
        assertFalse(NetworkTools.isIpLiteral("dns.google"))
        assertFalse(NetworkTools.isIpLiteral("999.1.1.1"))
        assertFalse(NetworkTools.isIpLiteral("1.2.3"))
        assertFalse(NetworkTools.isIpLiteral(""))
    }
}
