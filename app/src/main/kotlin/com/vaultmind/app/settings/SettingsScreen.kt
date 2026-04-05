package com.vaultmind.app.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.vaultmind.app.ingestion.EmbeddingEngine
import com.vaultmind.app.rag.LlmEngine
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val prefs: AppPreferences
) : ViewModel() {

    val settings = prefs.settings.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        AppSettings()
    )

    fun setTopK(v: Int) = viewModelScope.launch { prefs.setTopK(v) }
    fun setTemperature(v: Float) = viewModelScope.launch { prefs.setTemperature(v) }
    fun setThinkingMode(v: Boolean) = viewModelScope.launch { prefs.setThinkingMode(v) }
    fun setContextWindow(v: Int) = viewModelScope.launch { prefs.setContextWindow(v) }
    fun setAutoLock(v: AutoLockDelay) = viewModelScope.launch { prefs.setAutoLock(v) }
    fun setLlmModelPath(path: String) = viewModelScope.launch {
        prefs.setLlmModelPath(path)
    }
    fun setEmbeddingModelPath(path: String) = viewModelScope.launch {
        prefs.setEmbeddingModelPath(path)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val llmPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            // Persist access permission so we can read it after process restart
            context.contentResolver.takePersistableUriPermission(
                it, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
            viewModel.setLlmModelPath(it.toString())
        }
    }

    val embeddingPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            context.contentResolver.takePersistableUriPermission(
                it, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
            viewModel.setEmbeddingModelPath(it.toString())
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 16.dp)
        ) {
            SettingsSection("RAG Parameters") {
                SettingsSliderRow(
                    label = "Retrieved chunks (Top-K)",
                    value = settings.topK.toFloat(),
                    range = 1f..15f,
                    steps = 13,
                    displayValue = settings.topK.toString(),
                    onValueChange = { viewModel.setTopK(it.toInt()) }
                )

                Spacer(Modifier.height(8.dp))

                SettingsSliderRow(
                    label = "Temperature",
                    value = settings.temperature,
                    range = 0f..1f,
                    steps = 9,
                    displayValue = "%.1f".format(settings.temperature),
                    onValueChange = { viewModel.setTemperature(it) }
                )

                Spacer(Modifier.height(8.dp))

                SettingsSwitchRow(
                    label = "Thinking mode",
                    description = "Enables step-by-step reasoning (slower, more accurate)",
                    checked = settings.thinkingMode,
                    onCheckedChange = viewModel::setThinkingMode
                )

                Spacer(Modifier.height(16.dp))

                SettingsSliderRow(
                    label = "Context Window",
                    value = settings.contextWindow.toFloat(),
                    range = 2048f..32768f,
                    steps = 14,
                    displayValue = "${settings.contextWindow / 1024}K tokens",
                    onValueChange = { viewModel.setContextWindow(it.toInt()) }
                )
                Text(
                    text = "Larger windows fit more retrieved notes but use more RAM.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(Modifier.height(24.dp))

            SettingsSection("Security") {
                Text(
                    text = "Auto-lock",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                AutoLockDelay.entries.forEach { delay ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(vertical = 4.dp)
                    ) {
                        androidx.compose.material3.RadioButton(
                            selected = settings.autoLock == delay,
                            onClick = { viewModel.setAutoLock(delay) }
                        )
                        Text(
                            text = when (delay) {
                                AutoLockDelay.IMMEDIATE -> "Immediately on background"
                                AutoLockDelay.THIRTY_SECONDS -> "After 30 seconds"
                                AutoLockDelay.ONE_MINUTE -> "After 1 minute"
                            },
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }

            Spacer(Modifier.height(24.dp))

            SettingsSection("Model Files") {
                Text(
                    text = "Place model files in your Downloads folder, then locate them here.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))

                ModelPathRow(
                    label = "LLM — Gemma 4 E4B",
                    description = "${LlmEngine.MODEL_FILENAME} (~4-5 GB)",
                    path = settings.llmModelPath,
                    onLocate = { llmPicker.launch(arrayOf("*/*")) }
                )

                Spacer(Modifier.height(12.dp))

                ModelPathRow(
                    label = "Embedding — EmbeddingGemma 300M",
                    description = "${EmbeddingEngine.MODEL_FILENAME} (~150-200 MB)",
                    path = settings.embeddingModelPath,
                    onLocate = { embeddingPicker.launch(arrayOf("*/*")) }
                )
            }
        }
    }
}

@Composable
private fun SettingsSection(title: String, content: @Composable () -> Unit) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary
    )
    Spacer(Modifier.height(12.dp))
    content()
    Spacer(Modifier.height(4.dp))
    HorizontalDivider()
}

@Composable
private fun SettingsSliderRow(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    displayValue: String,
    onValueChange: (Float) -> Unit
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text(
                displayValue,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary
            )
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = range,
            steps = steps,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun SettingsSwitchRow(
    label: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun ModelPathRow(
    label: String,
    description: String,
    path: String,
    onLocate: () -> Unit
) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Text(
            description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(4.dp))
        if (path.isNotBlank()) {
            Text(
                path,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary
            )
        }
        Spacer(Modifier.height(4.dp))
        OutlinedButton(onClick = onLocate) {
            Text(if (path.isBlank()) "Locate file" else "Change file")
        }
    }
}
