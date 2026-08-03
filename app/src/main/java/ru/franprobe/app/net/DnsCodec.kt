package ru.franprobe.app.net

import ru.franprobe.app.model.DnsAnswer
import ru.franprobe.app.model.DnsMessage
import ru.franprobe.app.model.DnsQueryPacket
import ru.franprobe.app.model.DnsRecordType
import java.io.ByteArrayOutputStream
import java.net.IDN
import java.net.InetAddress
import java.security.SecureRandom

object DnsCodec {
    private val random = SecureRandom()

    fun buildQuery(domain: String, type: DnsRecordType): DnsQueryPacket {
        val normalized = normalizeDomain(domain)
        require(normalized.isNotBlank()) { "Пустое доменное имя" }
        val id = random.nextInt(0x10000)
        val out = ByteArrayOutputStream()
        writeU16(out, id)
        writeU16(out, 0x0100) // recursion desired
        writeU16(out, 1)
        writeU16(out, 0)
        writeU16(out, 0)
        writeU16(out, 0)
        val encodedLabels = normalized.split('.').map { label ->
            val bytes = label.toByteArray(Charsets.US_ASCII)
            require(bytes.size in 1..63) { "Некорректная DNS-метка: $label" }
            bytes
        }
        val wireNameLength = encodedLabels.sumOf { 1 + it.size } + 1
        require(wireNameLength <= 255) { "DNS-имя длиннее 255 байт" }
        encodedLabels.forEach { bytes ->
            out.write(bytes.size)
            out.write(bytes)
        }
        out.write(0)
        writeU16(out, type.code)
        writeU16(out, 1) // IN
        return DnsQueryPacket(id, out.toByteArray(), normalized, type)
    }

    fun parseResponse(bytes: ByteArray, expected: DnsQueryPacket? = null): DnsMessage {
        require(bytes.size >= 12) { "DNS-ответ короче заголовка" }
        val reader = Reader(bytes)
        val id = reader.u16()
        val flags = reader.u16()
        val qdCount = reader.u16()
        val anCount = reader.u16()
        val nsCount = reader.u16()
        val arCount = reader.u16()
        val isResponse = flags and 0x8000 != 0
        val truncated = flags and 0x0200 != 0
        val rcode = flags and 0x000F

        if (expected != null) {
            require(id == expected.transactionId) {
                "DNS transaction ID не совпадает: ожидался ${expected.transactionId}, получен $id"
            }
            require(isResponse) { "Получен пакет, который не является DNS-ответом" }
            require(qdCount == 1) { "DNS-ответ должен содержать ровно один исходный вопрос, получено: $qdCount" }
        }

        var questionName: String? = null
        var questionType: Int? = null
        var questionClass: Int? = null
        repeat(qdCount) { index ->
            val name = reader.name()
            val type = reader.u16()
            val clazz = reader.u16()
            if (index == 0) {
                questionName = name
                questionType = type
                questionClass = clazz
            }
        }

        if (expected != null) {
            require(questionName != null && questionType != null) {
                "DNS-ответ не содержит исходный вопрос"
            }
            require(normalizeDomain(questionName!!) == normalizeDomain(expected.domain)) {
                "DNS-ответ относится к другому имени: $questionName"
            }
            require(questionType == expected.type.code) {
                "DNS-ответ относится к другому типу: $questionType"
            }
            require(questionClass == 1) {
                "DNS-ответ относится к другому классу: $questionClass"
            }
        }

        val answers = mutableListOf<DnsAnswer>()
        repeat(anCount) { parseRecord(reader)?.let(answers::add) }
        repeat(nsCount) { skipRecord(reader) }
        repeat(arCount) { skipRecord(reader) }

        return DnsMessage(
            transactionId = id,
            response = isResponse,
            truncated = truncated,
            rcode = rcode,
            questionName = questionName,
            questionType = questionType,
            questionClass = questionClass,
            questionCount = qdCount,
            answerCount = anCount,
            authorityCount = nsCount,
            additionalCount = arCount,
            flags = flags,
            answers = answers,
            rawSize = bytes.size,
            rawPreviewHex = bytes.take(512).joinToString("") { "%02x".format(it.toInt() and 0xFF) }
        )
    }

    private fun parseRecord(reader: Reader): DnsAnswer? {
        val name = reader.name()
        val type = reader.u16()
        val clazz = reader.u16()
        val ttl = reader.u32()
        val dataLength = reader.u16()
        reader.requireAvailable(dataLength)
        val start = reader.position
        val value = when {
            clazz != 1 -> null
            type == DnsRecordType.A.code && dataLength == 4 ->
                InetAddress.getByAddress(reader.bytes(dataLength)).hostAddress
            type == DnsRecordType.AAAA.code && dataLength == 16 ->
                InetAddress.getByAddress(reader.bytes(dataLength)).hostAddress
            type == 5 -> reader.name() // CNAME
            else -> null
        }
        reader.position = start + dataLength
        return value?.let { DnsAnswer(name, type, ttl, it) }
    }

    private fun skipRecord(reader: Reader) {
        reader.name()
        reader.u16()
        reader.u16()
        reader.u32()
        val length = reader.u16()
        reader.skip(length)
    }

    private fun normalizeDomain(domain: String): String {
        val trimmed = domain.trim().trimEnd('.')
        require(trimmed.isNotBlank()) { "Пустое доменное имя" }
        return IDN.toASCII(trimmed, IDN.USE_STD3_ASCII_RULES).lowercase()
    }

    private fun writeU16(out: ByteArrayOutputStream, value: Int) {
        out.write((value ushr 8) and 0xFF)
        out.write(value and 0xFF)
    }

    private class Reader(private val data: ByteArray) {
        var position: Int = 0

        fun u16(): Int {
            ensure(2)
            val result = ((data[position].toInt() and 0xFF) shl 8) or
                (data[position + 1].toInt() and 0xFF)
            position += 2
            return result
        }

        fun u32(): Long {
            ensure(4)
            val result = ((data[position].toLong() and 0xFF) shl 24) or
                ((data[position + 1].toLong() and 0xFF) shl 16) or
                ((data[position + 2].toLong() and 0xFF) shl 8) or
                (data[position + 3].toLong() and 0xFF)
            position += 4
            return result
        }

        fun bytes(length: Int): ByteArray {
            ensure(length)
            return data.copyOfRange(position, position + length).also { position += length }
        }

        fun skip(length: Int) {
            ensure(length)
            position += length
        }

        fun name(): String {
            val labels = mutableListOf<String>()
            var cursor = position
            var jumped = false
            var jumps = 0
            var endPosition = -1

            while (true) {
                require(cursor in data.indices) { "DNS-имя выходит за границы пакета" }
                val length = data[cursor].toInt() and 0xFF
                when {
                    length == 0 -> {
                        if (!jumped) position = cursor + 1 else if (endPosition >= 0) position = endPosition
                        return labels.joinToString(".")
                    }
                    length and 0xC0 == 0xC0 -> {
                        require(cursor + 1 < data.size) { "Обрезанный DNS-указатель" }
                        val pointer = ((length and 0x3F) shl 8) or (data[cursor + 1].toInt() and 0xFF)
                        require(pointer < data.size) { "DNS-указатель за пределами пакета" }
                        if (!jumped) endPosition = cursor + 2
                        jumped = true
                        cursor = pointer
                        jumps++
                        require(jumps <= 32) { "Циклические DNS-указатели" }
                    }
                    length and 0xC0 != 0 -> error("Некорректная длина DNS-метки")
                    else -> {
                        require(length <= 63) { "DNS-метка длиннее 63 байт" }
                        val start = cursor + 1
                        val end = start + length
                        require(end <= data.size) { "Обрезанная DNS-метка" }
                        labels += data.copyOfRange(start, end).toString(Charsets.UTF_8)
                        cursor = end
                        if (!jumped) position = cursor
                    }
                }
            }
        }

        fun requireAvailable(length: Int) {
            ensure(length)
        }

        private fun ensure(length: Int) {
            require(length >= 0 && position <= data.size - length) { "DNS-пакет неожиданно закончился" }
        }
    }
}
