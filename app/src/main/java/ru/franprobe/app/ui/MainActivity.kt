package ru.franprobe.app.ui

import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import ru.franprobe.app.ui.theme.FranProbeTheme
import java.io.File

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()
    private var pendingExport: File? = null

    private val createDocument = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        val source = pendingExport
        pendingExport = null
        if (uri != null && source != null) {
            runCatching {
                contentResolver.openOutputStream(uri)?.use { output ->
                    source.inputStream().use { input -> input.copyTo(output) }
                } ?: error("Не удалось открыть выбранный файл")
            }.onSuccess {
                Toast.makeText(this, "Отчёт сохранён", Toast.LENGTH_SHORT).show()
            }.onFailure { error ->
                Toast.makeText(this, "Ошибка сохранения: ${error.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent {
            FranProbeTheme {
                FranProbeApp(
                    viewModel = viewModel,
                    onExportFile = { file ->
                        pendingExport = file
                        createDocument.launch(file.name)
                    }
                )
            }
        }
    }
}
