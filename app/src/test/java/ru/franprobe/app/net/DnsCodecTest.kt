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
        val response = responseFor(
            id = query.transactionId,
            questionType = 1,
            answerType = 1,
            answer = byteArrayOf(93, 184.toByte(), 216.toByte(), 34)
        )
        val parsed = DnsCodec.parseResponse(response, query)
        assertTrue(parsed.response)
        assertFalse(parsed.truncated)
        assertEquals(0, parsed.rcode)
        assertEquals("example.com", parsed.questionName)
        assertEquals(listOf("93.184.216.34"), parsed.addresses)
    }

    @Test
    fun parsesAaaaResponse() {
        val query = DnsCodec.buildQuery("example.com", DnsRecordType.AAAA)
        val address = byteArrayOf(
            0x20, 0x01, 0x0d, 0xb8.toByte(),
            0, 0, 0, 0,
            0, 0, 0, 0,
            0, 0, 0, 1
        )
        val response = responseFor(
            id = query.transactionId,
            questionType = 28,
            answerType = 28,
            answer = address
        )
        val parsed = DnsCodec.parseResponse(response, query)
        assertEquals(1, parsed.addresses.size)
        assertTrue(parsed.addresses.first().contains(':'))
    }

    @Test
    fun preservesTruncatedFlag() {
        val query = DnsCodec.buildQuery("example.com", DnsRecordType.A)
        val response = responseFor(
            id = query.transactionId,
            questionType = 1,
            answerType = 1,
            answer = byteArrayOf(1, 2, 3, 4),
            flags = 0x8380
        )
        assertTrue(DnsCodec.parseResponse(response, query).truncated)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsMismatchedTransactionId() {
        val query = DnsCodec.buildQuery("example.com", DnsRecordType.A)
        val response = responseFor(
            id = (query.transactionId + 1) and 0xFFFF,
            questionType = 1,
            answerType = 1,
            answer = byteArrayOf(1, 2, 3, 4)
        )
        DnsCodec.parseResponse(response, query)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsMismatchedQuestionType() {
        val query = DnsCodec.buildQuery("example.com", DnsRecordType.A)
        val response = responseFor(
            id = query.transactionId,
            questionType = 28,
            answerType = 28,
            answer = ByteArray(16)
        )
        DnsCodec.parseResponse(response, query)
    }


    @Test(expected = IllegalArgumentException::class)
    fun rejectsMismatchedQuestionClass() {
        val query = DnsCodec.buildQuery("example.com", DnsRecordType.A)
        val response = responseFor(
            id = query.transactionId,
            questionType = 1,
            questionClass = 3,
            answerType = 1,
            answer = byteArrayOf(1, 2, 3, 4)
        )
        DnsCodec.parseResponse(response, query)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsRdataLengthBeyondPacket() {
        val query = DnsCodec.buildQuery("example.com", DnsRecordType.A)
        val response = responseFor(
            id = query.transactionId,
            questionType = 1,
            answerType = 1,
            answer = byteArrayOf(1, 2, 3, 4)
        ).copyOf().also { packet ->
            // Последние шесть байт: RDLENGTH=4 и четыре байта IPv4. Заявляем 16 байт.
            packet[packet.size - 6] = 0
            packet[packet.size - 5] = 16
        }
        DnsCodec.parseResponse(response, query)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsResponseWithoutQuestion() {
        val query = DnsCodec.buildQuery("example.com", DnsRecordType.A)
        val out = ByteArrayOutputStream()
        u16(out, query.transactionId)
        u16(out, 0x8180)
        u16(out, 0)
        u16(out, 0)
        u16(out, 0)
        u16(out, 0)
        DnsCodec.parseResponse(out.toByteArray(), query)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsTruncatedPacketBody() {
        val query = DnsCodec.buildQuery("example.com", DnsRecordType.A)
        val response = responseFor(
            id = query.transactionId,
            questionType = 1,
            answerType = 1,
            answer = byteArrayOf(1, 2, 3, 4)
        )
        DnsCodec.parseResponse(response.copyOf(response.size - 2), query)
    }

    private fun responseFor(
        id: Int,
        questionType: Int,
        questionClass: Int = 1,
        answerType: Int,
        answer: ByteArray,
        flags: Int = 0x8180
    ): ByteArray {
        val out = ByteArrayOutputStream()
        u16(out, id)
        u16(out, flags)
        u16(out, 1)
        u16(out, 1)
        u16(out, 0)
        u16(out, 0)
        label(out, "example")
        label(out, "com")
        out.write(0)
        u16(out, questionType)
        u16(out, questionClass)
        u16(out, 0xC00C)
        u16(out, answerType)
        u16(out, 1)
        out.write(byteArrayOf(0, 0, 0, 60))
        u16(out, answer.size)
        out.write(answer)
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
