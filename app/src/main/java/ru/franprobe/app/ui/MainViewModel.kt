package ru.franprobe.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ru.franprobe.app.engine.DiagnosticEngine
import ru.franprobe.app.model.DiagnosticConfig
import ru.franprobe.app.model.DiagnosticMode
import ru.franprobe.app.model.DiagnosticReport
import ru.franprobe.app.model.ProbeResult
import ru.franprobe.app.model.RunProgress
import ru.franprobe.app.net.NetworkTools
import ru.franprobe.app.report.ReportExporter
import java.io.File


data class MainUiState(
    val selectedTab: AppTab = AppTab.DIAGNOSTICS,
    val mode: DiagnosticMode = DiagnosticMode.FULL,
    val running: Boolean = false,
    val progress: RunProgress = RunProgress(),
    val results: List<ProbeResult> = emptyList(),
    val report: DiagnosticReport? = null,
    val errorMessage: String? = null,
    val dnsServersText: String = "77.88.8.8\n77.88.8.1\n1.1.1.1\n8.8.8.8",
    val domainsText: String = "yandex.ru\nvk.com\nmail.ru\ngosuslugi.ru\ntelegram.org\ngithub.com\nwww.google.com\nwww.youtube.com\ncloudflare.com\nconnectivitycheck.gstatic.com",
    val sniText: String = "yandex.ru\nvk.com\nmail.ru\ngosuslugi.ru\ntelegram.org\ngithub.com\nwww.google.com\nwww.youtube.com\ncloudflare.com",
    val matrixIp: String = "77.88.55.88",
    val matrixPort: String = "443",
    val history: List<File> = emptyList()
)

enum class AppTab(val title: String) {
    DIAGNOSTICS("Диагностика"),
    SETTINGS("Настройки"),
    HISTORY("История")
}

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val engine = DiagnosticEngine(application)
    private val _state = MutableStateFlow(MainUiState())
    val state: StateFlow<MainUiState> = _state.asStateFlow()
    private var runJob: Job? = null

    init {
        refreshHistory()
    }

    fun selectTab(tab: AppTab) = _state.update { it.copy(selectedTab = tab) }
    fun setMode(mode: DiagnosticMode) = _state.update { it.copy(mode = mode) }
    fun setDnsServers(value: String) = _state.update { it.copy(dnsServersText = value) }
    fun setDomains(value: String) = _state.update { it.copy(domainsText = value) }
    fun setSni(value: String) = _state.update { it.copy(sniText = value) }
    fun setMatrixIp(value: String) = _state.update { it.copy(matrixIp = value) }
    fun setMatrixPort(value: String) = _state.update { it.copy(matrixPort = value.filter(Char::isDigit).take(5)) }
    fun dismissError() = _state.update { it.copy(errorMessage = null) }

    fun startDiagnostics() {
        if (_state.value.running) return
        val config = buildConfig() ?: return
        runJob = viewModelScope.launch {
            _state.update {
                it.copy(
                    running = true,
                    progress = RunProgress(0, 1, "Подготовка диагностики"),
                    results = emptyList(),
                    report = null,
                    errorMessage = null,
                    selectedTab = AppTab.DIAGNOSTICS
                )
            }
            try {
                val report = engine.run(config) { progress ->
                    _state.update { it.copy(progress = progress) }
                }
                ReportExporter.saveHistory(getApplication(), report)
                _state.update {
                    it.copy(
                        running = false,
                        report = report,
                        results = report.results,
                        progress = RunProgress(1, 1, "Диагностика завершена")
                    )
                }
                refreshHistory()
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                _state.update {
                    it.copy(running = false, progress = RunProgress(0, 1, "Диагностика отменена"))
                }
            } catch (error: Throwable) {
                _state.update {
                    it.copy(
                        running = false,
                        errorMessage = error.message ?: error::class.java.simpleName,
                        progress = RunProgress(0, 1, "Ошибка диагностики")
                    )
                }
            }
        }
    }

    fun cancelDiagnostics() {
        runJob?.cancel()
        runJob = null
    }

    fun clearCurrentResults() {
        if (_state.value.running) return
        _state.update { it.copy(results = emptyList(), report = null, progress = RunProgress()) }
    }

    fun prepareCurrentExport(): File? {
        val report = _state.value.report ?: return null
        return ReportExporter.createZip(getApplication(), report)
    }

    fun clearHistory() {
        ReportExporter.clearHistory(getApplication())
        refreshHistory()
    }

    fun refreshHistory() {
        _state.update { it.copy(history = ReportExporter.listHistory(getApplication())) }
    }

    private fun buildConfig(): DiagnosticConfig? {
        val current = _state.value
        val port = current.matrixPort.toIntOrNull()
        if (current.mode == DiagnosticMode.SNI_MATRIX && (port == null || port !in 1..65535)) {
            _state.update { it.copy(errorMessage = "Порт матрицы должен быть от 1 до 65535") }
            return null
        }
        val dns = splitLines(current.dnsServersText)
        if (dns.isEmpty()) {
            _state.update { it.copy(errorMessage = "Добавьте хотя бы один DNS-сервер") }
            return null
        }
        val invalidResolver = dns.firstOrNull { !NetworkTools.isIpLiteral(it) }
        if (invalidResolver != null) {
            _state.update {
                it.copy(errorMessage = "DNS-сервер должен быть прямым IPv4/IPv6 адресом: $invalidResolver")
            }
            return null
        }
        val domains = splitLines(current.domainsText)
        val sni = splitLines(current.sniText)
        return DiagnosticConfig(
            mode = current.mode,
            dnsServers = dns,
            targetDomains = domains.ifEmpty { DiagnosticConfig().targetDomains },
            sniCandidates = sni.ifEmpty { DiagnosticConfig().sniCandidates },
            customMatrixIp = current.matrixIp.trim(),
            customMatrixPort = port ?: 443
        )
    }

    private fun splitLines(text: String): List<String> = text
        .split('\n', ',', ';', ' ', '\t')
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .distinct()
}
