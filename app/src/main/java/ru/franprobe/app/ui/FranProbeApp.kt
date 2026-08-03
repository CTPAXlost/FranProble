package ru.franprobe.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ru.franprobe.app.model.DiagnosticMode
import ru.franprobe.app.model.ProbeResult
import ru.franprobe.app.model.ProbeStatus
import java.io.File
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FranProbeApp(
    viewModel: MainViewModel,
    onExportFile: (File) -> Unit
) {
    val state by viewModel.state.collectAsState()

    state.errorMessage?.let { message ->
        AlertDialog(
            onDismissRequest = viewModel::dismissError,
            confirmButton = { Button(onClick = viewModel::dismissError) { Text("Понятно") } },
            title = { Text("FranProbe") },
            text = { Text(message) }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("FranProbe", fontWeight = FontWeight.Bold)
                        Text(
                            "Точная диагностика белых списков",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                actions = {
                    if (state.report != null) {
                        IconButton(
                            onClick = { viewModel.prepareCurrentExport()?.let(onExportFile) }
                        ) {
                            Icon(Icons.Default.Save, contentDescription = "Сохранить отчёт")
                        }
                    }
                }
            )
        },
        bottomBar = {
            NavigationBar {
                AppTab.entries.forEach { tab ->
                    NavigationBarItem(
                        selected = state.selectedTab == tab,
                        onClick = { viewModel.selectTab(tab) },
                        icon = {
                            Icon(
                                when (tab) {
                                    AppTab.DIAGNOSTICS -> Icons.Default.NetworkCheck
                                    AppTab.SETTINGS -> Icons.Default.Settings
                                    AppTab.HISTORY -> Icons.Default.History
                                },
                                contentDescription = tab.title
                            )
                        },
                        label = { Text(tab.title) }
                    )
                }
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when (state.selectedTab) {
                AppTab.DIAGNOSTICS -> DiagnosticsScreen(state, viewModel, onExportFile)
                AppTab.SETTINGS -> SettingsScreen(state, viewModel)
                AppTab.HISTORY -> HistoryScreen(state.history, viewModel, onExportFile)
            }
        }
    }
}

@Composable
private fun DiagnosticsScreen(
    state: MainUiState,
    viewModel: MainViewModel,
    onExportFile: (File) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text("Режим проверки", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DiagnosticMode.entries.forEach { mode ->
                    FilterChip(
                        selected = state.mode == mode,
                        onClick = { if (!state.running) viewModel.setMode(mode) },
                        label = { Text(mode.title) },
                        enabled = !state.running
                    )
                }
            }
            Text(
                state.mode.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Info, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            when (state.mode) {
                                DiagnosticMode.QUICK -> "Проверяет основные симптомы без долгой матрицы."
                                DiagnosticMode.FULL -> "Проверяет DNS, прямые IP, TCP, UDP, TLS, SNI и HTTP."
                                DiagnosticMode.SNI_MATRIX -> "Сравнивает один IP с разными SNI и версиями TLS."
                            },
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    Text(
                        "Тест, который не стартовал из-за отсутствия DNS/IP, получит статус «Не проверено», а не ложное «Заблокировано».",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }

        item {
            if (state.running) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    LinearProgressIndicator(
                        progress = { state.progress.fraction },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        "${state.progress.current}/${state.progress.total} — ${state.progress.message}",
                        style = MaterialTheme.typography.bodySmall
                    )
                    OutlinedButton(
                        onClick = viewModel::cancelDiagnostics,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Stop, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Остановить")
                    }
                }
            } else {
                Button(
                    onClick = viewModel::startDiagnostics,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Запустить диагностику")
                }
            }
        }

        if (state.results.isNotEmpty()) {
            item { ResultSummary(state.results) }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { viewModel.prepareCurrentExport()?.let(onExportFile) }, enabled = state.report != null, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.Save, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("Сохранить ZIP")
                    }
                    OutlinedButton(
                        onClick = viewModel::clearCurrentResults,
                        enabled = !state.running,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Clear, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("Очистить")
                    }
                }
            }
            itemsIndexed(state.results, key = { _, item -> item.id }) { index, result ->
                ResultCard(index + 1, result)
            }
        } else if (!state.running) {
            item {
                EmptyState(
                    title = "Отчёта пока нет",
                    description = "Запусти проверку во время включённых ограничений. После завершения отчёт можно сохранить одним ZIP-файлом."
                )
            }
        }
    }
}

@Composable
private fun ResultSummary(results: List<ProbeResult>) {
    val available = results.count { it.status == ProbeStatus.AVAILABLE }
    val limited = results.count { it.status == ProbeStatus.LIMITED }
    val unreachable = results.count { it.status == ProbeStatus.UNREACHABLE }
    val notTested = results.count { it.status == ProbeStatus.NOT_TESTED }
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Сводка", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                SummaryValue("Доступно", available, statusColor(ProbeStatus.AVAILABLE))
                SummaryValue("Ограничено", limited, statusColor(ProbeStatus.LIMITED))
                SummaryValue("Недоступно", unreachable, statusColor(ProbeStatus.UNREACHABLE))
                SummaryValue("Не проверено", notTested, statusColor(ProbeStatus.NOT_TESTED))
            }
        }
    }
}

@Composable
private fun SummaryValue(label: String, value: Int, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value.toString(), fontWeight = FontWeight.Bold, color = color)
        Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 1)
    }
}

@Composable
private fun ResultCard(index: Int, result: ProbeResult) {
    var expanded by rememberSaveable(result.id) { mutableStateOf(false) }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = statusIcon(result.status),
                    contentDescription = null,
                    tint = statusColor(result.status),
                    modifier = Modifier.size(22.dp)
                )
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        "$index. ${result.name}",
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        "${result.layer.title} • ${result.status.title} • ${result.durationMs} мс",
                        style = MaterialTheme.typography.labelSmall,
                        color = statusColor(result.status)
                    )
                }
            }
            Text(result.target, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(result.summary, style = MaterialTheme.typography.bodyMedium)
            if (expanded) {
                HorizontalDivider()
                result.details.forEach { (key, value) -> DetailRow(key, value) }
                result.errorType?.let { DetailRow("Тип ошибки", it) }
                result.errorMessage?.let { DetailRow("Сообщение", it) }
            } else if (result.details.isNotEmpty() || result.errorType != null) {
                Text(
                    "Нажми, чтобы раскрыть технические детали",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

@Composable
private fun DetailRow(key: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(key, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
        Text(value.ifBlank { "—" }, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun SettingsScreen(state: MainUiState, viewModel: MainViewModel) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("Настройки проверки", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(
            "По одному адресу или домену на строку. Можно вставлять списки через пробел, запятую или точку с запятой.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        OutlinedTextField(
            value = state.dnsServersText,
            onValueChange = viewModel::setDnsServers,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Прямые DNS-серверы") },
            minLines = 3,
            enabled = !state.running
        )
        OutlinedTextField(
            value = state.domainsText,
            onValueChange = viewModel::setDomains,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Контрольные домены") },
            minLines = 6,
            enabled = !state.running
        )
        OutlinedTextField(
            value = state.sniText,
            onValueChange = viewModel::setSni,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Кандидаты SNI") },
            supportingText = { Text("Для безопасности и скорости используется не более 20 уникальных имён за один запуск.") },
            minLines = 6,
            enabled = !state.running
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(
                value = state.matrixIp,
                onValueChange = viewModel::setMatrixIp,
                modifier = Modifier.weight(2f),
                label = { Text("IP матрицы SNI") },
                singleLine = true,
                enabled = !state.running
            )
            OutlinedTextField(
                value = state.matrixPort,
                onValueChange = viewModel::setMatrixPort,
                modifier = Modifier.weight(1f),
                label = { Text("Порт") },
                singleLine = true,
                enabled = !state.running
            )
        }
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Как читать SNI-матрицу", fontWeight = FontWeight.Bold)
                Text("• Если TCP не устанавливается, смена SNI не может помочь: ClientHello ещё не отправлен.")
                Text("• Если TCP проходит, а результаты TLS меняются по SNI, фильтрация или маршрутизация может учитывать ClientHello.")
                Text("• Несовпадение сертификата — не блокировка: сервер ответил, но не обслуживает выбранное имя.")
            }
        }
    }
}

@Composable
private fun HistoryScreen(
    history: List<File>,
    viewModel: MainViewModel,
    onExportFile: (File) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("История отчётов", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text(
                        "Хранятся последние 20 завершённых запусков.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = viewModel::refreshHistory) {
                    Icon(Icons.Default.Refresh, contentDescription = "Обновить")
                }
                IconButton(onClick = viewModel::clearHistory, enabled = history.isNotEmpty()) {
                    Icon(Icons.Default.Delete, contentDescription = "Удалить историю")
                }
            }
        }
        if (history.isEmpty()) {
            item { EmptyState("История пуста", "Завершённые отчёты будут сохраняться здесь автоматически.") }
        } else {
            items(history, key = { it.absolutePath }) { file -> HistoryCard(file, onExportFile) }
        }
    }
}

@Composable
private fun HistoryCard(file: File, onExportFile: (File) -> Unit) {
    Card {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.History, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(file.name, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    "${formatBytes(file.length())} • ${DateFormat.getDateTimeInstance().format(Date(file.lastModified()))}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = { onExportFile(file) }) {
                Icon(Icons.Default.Save, contentDescription = "Сохранить этот отчёт")
            }
        }
    }
}

@Composable
private fun EmptyState(title: String, description: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.large
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(Icons.Default.NetworkCheck, contentDescription = null, modifier = Modifier.size(42.dp))
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(description, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

private fun statusIcon(status: ProbeStatus): ImageVector = when (status) {
    ProbeStatus.AVAILABLE -> Icons.Default.CheckCircle
    ProbeStatus.LIMITED -> Icons.Default.Warning
    ProbeStatus.UNREACHABLE -> Icons.Default.ErrorOutline
    ProbeStatus.NOT_TESTED -> Icons.Default.Info
    ProbeStatus.INCONCLUSIVE -> Icons.Default.Info
    ProbeStatus.ERROR -> Icons.Default.ErrorOutline
}

@Composable
private fun statusColor(status: ProbeStatus): Color = when (status) {
    ProbeStatus.AVAILABLE -> Color(0xFF1C9B62)
    ProbeStatus.LIMITED -> Color(0xFFE5A000)
    ProbeStatus.UNREACHABLE -> MaterialTheme.colorScheme.error
    ProbeStatus.NOT_TESTED -> Color(0xFF70839E)
    ProbeStatus.INCONCLUSIVE -> Color(0xFF6F6BD7)
    ProbeStatus.ERROR -> MaterialTheme.colorScheme.error
}

private fun formatBytes(bytes: Long): String = when {
    bytes < 1024 -> "$bytes Б"
    bytes < 1024 * 1024 -> "%.1f КБ".format(bytes / 1024.0)
    else -> "%.1f МБ".format(bytes / (1024.0 * 1024.0))
}
