package ru.franprobe.app.model

enum class DnsRecordType(val code: Int) {
    A(1),
    AAAA(28)
}

data class DnsAnswer(
    val name: String,
    val type: Int,
    val ttl: Long,
    val value: String
)

data class DnsMessage(
    val transactionId: Int,
    val response: Boolean,
    val truncated: Boolean,
    val rcode: Int,
    val questionName: String?,
    val questionType: Int?,
    val questionCount: Int,
    val answerCount: Int,
    val authorityCount: Int,
    val additionalCount: Int,
    val flags: Int,
    val answers: List<DnsAnswer>,
    val rawSize: Int,
    val rawPreviewHex: String
) {
    val addresses: List<String> = answers
        .filter { it.type == DnsRecordType.A.code || it.type == DnsRecordType.AAAA.code }
        .map { it.value }
        .distinct()
}

data class DnsQueryPacket(
    val transactionId: Int,
    val bytes: ByteArray,
    val domain: String,
    val type: DnsRecordType
)
