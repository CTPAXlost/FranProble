package ru.franprobe.app.model

import android.os.Build
import java.time.Instant
import java.util.UUID

enum class ProbeStatus(val title: String) {
    AVAILABLE("Доступно"),
    LIMITED("Ограничено"),
    UNREACHABLE("Недоступно"),
    NOT_TESTED("Не проверено"),
    INCONCLUSIVE("Неопределённо"),
    ERROR("Ошибка")
}

enum class TestLayer(val title: String) {
    NETWORK("Сеть"),
    DNS("DNS"),
    TCP("TCP"),
    UDP("UDP"),
    TLS("TLS/SNI"),
    HTTP("HTTP"),
    SUMMARY("Вывод")
}

data class ProbeResult(
    val id: String = UUID.randomUUID().toString(),
    val layer: TestLayer,
    val name: String,
    val target: String,
    val status: ProbeStatus,
    val summary: String,
    val durationMs: Long,
    val details: Map<String, String> = emptyMap(),
    val errorType: String? = null,
    val errorMessage: String? = null,
    val timestamp: String = Instant.now().toString()
)

data class NetworkSnapshot(
    val networkHandle: Long?,
    val transports: List<String>,
    val capabilities: List<String>,
    val interfaceName: String?,
    val mtu: Int?,
    val addresses: List<String>,
    val dnsServers: List<String>,
    val routes: List<String>,
    val privateDnsActive: Boolean,
    val privateDnsServerName: String?,
    val validated: Boolean,
    val captivePortal: Boolean,
    val metered: Boolean,
    val proxy: String?
)

enum class DiagnosticMode(val title: String, val description: String) {
    QUICK("Быстрая", "Сеть, DNS и базовый TCP/TLS"),
    FULL("Полная", "Все слои и несколько контрольных сервисов"),
    SNI_MATRIX("Матрица SNI", "Один IP с разными SNI, TLS и Host")
}

data class DiagnosticConfig(
    val mode: DiagnosticMode = DiagnosticMode.FULL,
    val dnsServers: List<String> = listOf("77.88.8.8", "77.88.8.1", "1.1.1.1", "8.8.8.8"),
    val targetDomains: List<String> = listOf(
        "yandex.ru",
        "vk.com",
        "mail.ru",
        "gosuslugi.ru",
        "telegram.org",
        "github.com",
        "www.google.com",
        "www.youtube.com",
        "cloudflare.com",
        "connectivitycheck.gstatic.com"
    ),
    val sniCandidates: List<String> = listOf(
        "yandex.ru",
        "vk.com",
        "mail.ru",
        "gosuslugi.ru",
        "telegram.org",
        "github.com",
        "www.google.com",
        "www.youtube.com",
        "cloudflare.com"
    ),
    val customMatrixIp: String = "77.88.55.88",
    val customMatrixPort: Int = 443,
    val connectTimeoutMs: Int = 4_000,
    val readTimeoutMs: Int = 5_000,
    val maxResolvedIpsPerDomain: Int = 2
)

data class DiagnosticReport(
    val reportId: String = UUID.randomUUID().toString(),
    val reportVersion: Int = 2,
    val appVersion: String,
    val startedAt: String,
    val finishedAt: String,
    val durationMs: Long,
    val config: DiagnosticConfig,
    val network: NetworkSnapshot?,
    val results: List<ProbeResult>,
    val conclusions: List<String>,
    val device: DeviceInfo = DeviceInfo.current()
)

data class DeviceInfo(
    val manufacturer: String,
    val model: String,
    val device: String,
    val androidRelease: String,
    val sdkInt: Int,
    val supportedAbis: List<String>
) {
    companion object {
        fun current() = DeviceInfo(
            manufacturer = Build.MANUFACTURER.orEmpty(),
            model = Build.MODEL.orEmpty(),
            device = Build.DEVICE.orEmpty(),
            androidRelease = Build.VERSION.RELEASE.orEmpty(),
            sdkInt = Build.VERSION.SDK_INT,
            supportedAbis = Build.SUPPORTED_ABIS?.toList().orEmpty()
        )
    }
}

data class RunProgress(
    val current: Int = 0,
    val total: Int = 1,
    val message: String = "Готово к запуску"
) {
    val fraction: Float get() = (current.toFloat() / total.coerceAtLeast(1)).coerceIn(0f, 1f)
}
