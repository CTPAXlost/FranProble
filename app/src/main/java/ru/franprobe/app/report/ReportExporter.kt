package ru.franprobe.app.report

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import ru.franprobe.app.model.DiagnosticConfig
import ru.franprobe.app.model.DiagnosticReport
import ru.franprobe.app.model.DeviceInfo
import ru.franprobe.app.model.NetworkSnapshot
import ru.franprobe.app.model.ProbeResult
import java.io.File
import java.io.FileOutputStream
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object ReportExporter {
    private val fileStamp = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss_SSS")
        .withZone(ZoneId.systemDefault())
    private val displayTime = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss")
        .withZone(ZoneId.systemDefault())

    fun suggestedFileName(report: DiagnosticReport): String =
        "FranProbe_отчёт_${fileStamp.format(Instant.parse(report.startedAt))}.zip"

    fun createZip(context: Context, report: DiagnosticReport): File {
        val directory = File(context.cacheDir, "exports").apply { mkdirs() }
        val file = File(directory, suggestedFileName(report))
        val base = file.nameWithoutExtension
        ZipOutputStream(FileOutputStream(file).buffered()).use { zip ->
            zip.putTextEntry("$base.txt", toText(report))
            zip.putTextEntry("$base.json", toJson(report).toString(2))
            zip.putTextEntry("$base.csv", toCsv(report))
            zip.putTextEntry("$base.raw.log", toRawLog(report))
            zip.putTextEntry("README.txt", exportReadme())
        }
        return file
    }

    fun saveHistory(context: Context, report: DiagnosticReport): File {
        val directory = File(context.filesDir, "reports").apply { mkdirs() }
        val file = File(directory, suggestedFileName(report))
        val temporary = createZip(context, report)
        temporary.copyTo(file, overwrite = true)
        trimHistory(directory, 20)
        return file
    }

    fun listHistory(context: Context): List<File> =
        File(context.filesDir, "reports")
            .takeIf { it.exists() }
            ?.listFiles { file -> file.isFile && file.extension.equals("zip", true) }
            ?.sortedByDescending { it.lastModified() }
            .orEmpty()

    fun clearHistory(context: Context) {
        listHistory(context).forEach { it.delete() }
    }

    private fun ZipOutputStream.putTextEntry(name: String, text: String) {
        putNextEntry(ZipEntry(name))
        write(text.toByteArray(Charsets.UTF_8))
        closeEntry()
    }

    private fun toText(report: DiagnosticReport): String = buildString {
        appendLine("FranProbe — отчёт диагностики сети")
        appendLine("Версия отчёта: ${report.reportVersion}")
        appendLine("Версия приложения: ${report.appVersion}")
        appendLine("ID отчёта: ${report.reportId}")
        appendLine("Режим: ${report.config.mode.title}")
        appendLine("Начало: ${formatInstant(report.startedAt)}")
        appendLine("Окончание: ${formatInstant(report.finishedAt)}")
        appendLine("Длительность: ${report.durationMs} мс")
        appendLine()
        appendLine("=== УСТРОЙСТВО ===")
        appendLine("Производитель: ${report.device.manufacturer}")
        appendLine("Модель: ${report.device.model}")
        appendLine("Android: ${report.device.androidRelease} (SDK ${report.device.sdkInt})")
        appendLine("ABI: ${report.device.supportedAbis.joinToString()}")
        appendLine()
        appendLine("=== ПАРАМЕТРЫ СЕТИ ===")
        appendNetwork(report.network)
        appendLine()
        appendLine("=== КОНФИГУРАЦИЯ ===")
        appendLine("DNS-серверы: ${report.config.dnsServers.joinToString()}")
        appendLine("Домены: ${report.config.targetDomains.joinToString()}")
        appendLine("SNI: ${report.config.sniCandidates.joinToString()}")
        appendLine("IP матрицы: ${report.config.customMatrixIp}:${report.config.customMatrixPort}")
        appendLine("Тайм-аут подключения: ${report.config.connectTimeoutMs} мс")
        appendLine("Тайм-аут чтения: ${report.config.readTimeoutMs} мс")
        appendLine()
        appendLine("=== ВЫВОДЫ ===")
        report.conclusions.forEachIndexed { index, conclusion -> appendLine("${index + 1}. $conclusion") }
        appendLine()
        appendLine("=== РЕЗУЛЬТАТЫ ===")
        report.results.forEachIndexed { index, result ->
            appendLine()
            appendLine("${index + 1}. [${result.status.name}] ${result.layer.title} / ${result.name}")
            appendLine("Цель: ${result.target}")
            appendLine("Итог: ${result.summary}")
            appendLine("Время: ${result.durationMs} мс")
            result.details.forEach { (key, value) -> appendLine("$key: $value") }
            result.errorType?.let { appendLine("Ошибка: $it") }
            result.errorMessage?.let { appendLine("Сообщение: $it") }
        }
        appendLine()
        appendLine("=== ВАЖНО ===")
        appendLine("Статус NOT_TESTED означает, что зависимый тест не запускался. Он не считается доказанной блокировкой.")
        appendLine("Статус INCONCLUSIVE означает, что по одному симптому нельзя отличить фильтрацию от нормального молчания сервера.")
    }

    private fun StringBuilder.appendNetwork(network: NetworkSnapshot?) {
        if (network == null) {
            appendLine("Активная сеть не обнаружена")
            return
        }
        appendLine("Network handle: ${network.networkHandle ?: "—"}")
        appendLine("Транспорт: ${network.transports.joinToString()}")
        appendLine("Capabilities: ${network.capabilities.joinToString()}")
        appendLine("Интерфейс: ${network.interfaceName ?: "—"}")
        appendLine("MTU: ${network.mtu ?: "—"}")
        appendLine("Адреса: ${network.addresses.joinToString()}")
        appendLine("DNS: ${network.dnsServers.joinToString()}")
        appendLine("Private DNS: ${network.privateDnsActive}; ${network.privateDnsServerName ?: ""}")
        appendLine("Validated: ${network.validated}")
        appendLine("Captive portal: ${network.captivePortal}")
        appendLine("Тарифицируемая: ${network.metered}")
        appendLine("Прокси: ${network.proxy ?: "нет"}")
        appendLine("Маршруты:")
        network.routes.forEach { appendLine("  $it") }
    }

    private fun toCsv(report: DiagnosticReport): String = buildString {
        append('\uFEFF')
        appendLine("timestamp;layer;status;name;target;duration_ms;summary;error_type;error_message;details")
        report.results.forEach { result ->
            val details = result.details.entries.joinToString(" | ") { (key, value) -> "$key=$value" }
            appendLine(
                listOf(
                    result.timestamp,
                    result.layer.name,
                    result.status.name,
                    result.name,
                    result.target,
                    result.durationMs.toString(),
                    result.summary,
                    result.errorType.orEmpty(),
                    result.errorMessage.orEmpty(),
                    details
                ).joinToString(";") { csvCell(it) }
            )
        }
    }

    private fun csvCell(value: String): String =
        "\"${value.replace("\"", "\"\"").replace("\r", " ").replace("\n", " ")}\""

    private fun toRawLog(report: DiagnosticReport): String = buildString {
        report.results.forEach { result ->
            append(result.timestamp)
            append('\t').append(result.layer.name)
            append('\t').append(result.status.name)
            append('\t').append(result.name.replace('\t', ' '))
            append('\t').append(result.target.replace('\t', ' '))
            append('\t').append(result.durationMs)
            append('\t').append(result.summary.replace('\t', ' ').replace('\n', ' '))
            if (result.errorType != null) append("\tERROR=").append(result.errorType)
            if (result.errorMessage != null) append("\tMESSAGE=").append(result.errorMessage.replace('\t', ' '))
            result.details.forEach { (key, value) ->
                append('\t').append(key).append('=').append(value.replace('\t', ' ').replace('\n', ' '))
            }
            appendLine()
        }
    }

    private fun toJson(report: DiagnosticReport): JSONObject = JSONObject().apply {
        put("reportId", report.reportId)
        put("reportVersion", report.reportVersion)
        put("appVersion", report.appVersion)
        put("startedAt", report.startedAt)
        put("finishedAt", report.finishedAt)
        put("durationMs", report.durationMs)
        put("device", report.device.toJson())
        put("config", report.config.toJson())
        put("network", report.network?.toJson() ?: JSONObject.NULL)
        put("conclusions", JSONArray(report.conclusions))
        put("results", JSONArray().apply { report.results.forEach { put(it.toJson()) } })
    }

    private fun ProbeResult.toJson() = JSONObject().apply {
        put("id", id)
        put("timestamp", timestamp)
        put("layer", layer.name)
        put("name", name)
        put("target", target)
        put("status", status.name)
        put("summary", summary)
        put("durationMs", durationMs)
        put("details", JSONObject(details))
        put("errorType", errorType ?: JSONObject.NULL)
        put("errorMessage", errorMessage ?: JSONObject.NULL)
    }

    private fun NetworkSnapshot.toJson() = JSONObject().apply {
        put("networkHandle", networkHandle ?: JSONObject.NULL)
        put("transports", JSONArray(transports))
        put("capabilities", JSONArray(capabilities))
        put("interfaceName", interfaceName ?: JSONObject.NULL)
        put("mtu", mtu ?: JSONObject.NULL)
        put("addresses", JSONArray(addresses))
        put("dnsServers", JSONArray(dnsServers))
        put("routes", JSONArray(routes))
        put("privateDnsActive", privateDnsActive)
        put("privateDnsServerName", privateDnsServerName ?: JSONObject.NULL)
        put("validated", validated)
        put("captivePortal", captivePortal)
        put("metered", metered)
        put("proxy", proxy ?: JSONObject.NULL)
    }

    private fun DiagnosticConfig.toJson() = JSONObject().apply {
        put("mode", mode.name)
        put("dnsServers", JSONArray(dnsServers))
        put("targetDomains", JSONArray(targetDomains))
        put("sniCandidates", JSONArray(sniCandidates))
        put("customMatrixIp", customMatrixIp)
        put("customMatrixPort", customMatrixPort)
        put("connectTimeoutMs", connectTimeoutMs)
        put("readTimeoutMs", readTimeoutMs)
        put("maxResolvedIpsPerDomain", maxResolvedIpsPerDomain)
    }

    private fun DeviceInfo.toJson() = JSONObject().apply {
        put("manufacturer", manufacturer)
        put("model", model)
        put("device", device)
        put("androidRelease", androidRelease)
        put("sdkInt", sdkInt)
        put("supportedAbis", JSONArray(supportedAbis))
    }

    private fun formatInstant(value: String): String = runCatching {
        displayTime.format(Instant.parse(value))
    }.getOrDefault(value)

    private fun trimHistory(directory: File, maxFiles: Int) {
        directory.listFiles()
            ?.filter { it.isFile }
            ?.sortedByDescending { it.lastModified() }
            ?.drop(maxFiles)
            ?.forEach { it.delete() }
    }

    private fun exportReadme(): String = """
        FranProbe сохраняет пять файлов:
        1. TXT — удобный человекочитаемый отчёт.
        2. JSON — структурированные данные для автоматического анализа.
        3. CSV — таблица для Excel/LibreOffice и сравнения запусков.
        4. RAW.LOG — одна строка на каждый тест, удобно сравнивать два запуска.
        5. README — описание формата.

        AVAILABLE — проверка получила положительный протокольный результат.
        LIMITED — нижний слой прошёл, но следующий этап отклонён или работает иначе.
        UNREACHABLE — адрес/порт не ответил либо маршрут отсутствует.
        NOT_TESTED — тест не запускался из-за отсутствия необходимого результата предыдущего этапа.
        INCONCLUSIVE — наблюдение недостаточно для вывода о блокировке.
        ERROR — ошибка входных данных или приложения.
    """.trimIndent()
}
