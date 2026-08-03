package ru.franprobe.app.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.franprobe.app.model.DnsRecordType
import java.io.ByteArrayOutputStream

class DnsCodecTest {
    @Test
    fun queryContainsExpectedQuestion() {
        val query = DnsCodec.buildQuery("Example.COM.", DnsRecordType.A)
        assertTrue(query.transactionId in 0..65535)
        assertEquals("example.com", query.domain)
        assertEquals(DnsRecordType.A, query.type)
        assertTrue(query.bytes.size > 12)
    }

    @Test
    fun convertsUnicodeDomainToAscii() {
        val query = DnsCodec.buildQuery("Exämple.COM.", DnsRecordType.A)
        assertEquals("xn--exmple-cua.com", query.domain)
    }

    @Test
    fun parsesAResponseAndValidatesQuestion() {
        val query = DnsCodec.buildQuery("example.com", DnsRecordType.A)
        val response = responseFor(query.transactionId, byteArrayOf(93, 184.toByte(), 216.toByte(), 34))
        val parsed = DnsCodec.parseResponse(response, query)
        assertTrue(parsed.response)
        assertFalse(parsed.truncated)
        assertEquals(0, parsed.rcode)
        assertEquals("example.com", parsed.questionName)
        assertEquals(listOf("93.184.216.34"), parsed.addresses)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsMismatchedTransactionId() {
        val query = DnsCodec.buildQuery("example.com", DnsRecordType.A)
        val response = responseFor((query.transactionId + 1) and 0xFFFF, byteArrayOf(1, 2, 3, 4))
        DnsCodec.parseResponse(response, query)
    }

    private fun responseFor(id: Int, address: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        u16(out, id)
        u16(out, 0x8180)
        u16(out, 1)
        u16(out, 1)
        u16(out, 0)
        u16(out, 0)
        label(out, "example")
        label(out, "com")
        out.write(0)
        u16(out, 1)
        u16(out, 1)
        u16(out, 0xC00C)
        u16(out, 1)
        u16(out, 1)
        out.write(byteArrayOf(0, 0, 0, 60))
        u16(out, 4)
        out.write(address)
        return out.toByteArray()
    }

    private fun label(out: ByteArrayOutputStream, value: String) {
        val bytes = value.toByteArray()
        out.write(bytes.size)
        out.write(bytes)
    }

    private fun u16(out: ByteArrayOutputStream, value: Int) {
        out.write((value ushr 8) and 0xFF)
        out.write(value and 0xFF)
    }
}
