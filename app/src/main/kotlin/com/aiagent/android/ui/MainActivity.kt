package com.aiagent.android.ui

import android.content.Intent
import android.os.Bundle
import android.provider.Settings as AndroidSettings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    AppRoot(viewModel)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshServiceStatus()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppRoot(viewModel: MainViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var tab by remember { mutableIntStateOf(0) }
    val context = LocalContext.current

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("AI Agent") })
        },
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            TabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Agent") })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Settings") })
            }
            when (tab) {
                0 -> AgentTab(
                    state = state,
                    onInstruction = viewModel::updateInstruction,
                    onRun = viewModel::runAgent,
                    onCancel = viewModel::cancelAgent,
                    onOpenAccessibility = {
                        context.startActivity(
                            Intent(AndroidSettings.ACTION_ACCESSIBILITY_SETTINGS)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    },
                )
                1 -> SettingsTab(
                    state = state,
                    onApiKey = viewModel::updateApiKey,
                    onBaseUrl = viewModel::updateBaseUrl,
                    onModel = viewModel::updateModel,
                    onMaxSteps = viewModel::updateMaxSteps,
                    onTemperature = viewModel::updateTemperature,
                    onMaxTokens = viewModel::updateMaxTokens,
                    onReasoningEffort = viewModel::updateReasoningEffort,
                    onSystemPrompt = viewModel::updateSystemPrompt,
                )
            }
        }
    }
}

@Composable
fun AgentTab(
    state: UiState,
    onInstruction: (String) -> Unit,
    onRun: () -> Unit,
    onCancel: () -> Unit,
    onOpenAccessibility: () -> Unit,
) {
    val listState = rememberLazyListState()
    LaunchedEffect(state.log.size) {
        if (state.log.isNotEmpty()) {
            listState.animateScrollToItem(state.log.size - 1)
        }
    }
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Card(
            colors = CardDefaults.cardColors(
                containerColor = if (state.serviceEnabled) Color(0xFFDCEDC8) else Color(0xFFFFE0B2),
            ),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = if (state.serviceEnabled) {
                        "Accessibility service enabled."
                    } else {
                        "Enable the AI Agent accessibility service to let the agent control the device."
                    },
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.height(0.dp))
                OutlinedButton(onClick = onOpenAccessibility) { Text("Open settings") }
            }
        }
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = state.instruction,
            onValueChange = onInstruction,
            label = { Text("What should the agent do?") },
            placeholder = { Text("e.g. Open Settings and turn on Battery Saver") },
            modifier = Modifier.fillMaxWidth(),
            minLines = 2,
            maxLines = 5,
            enabled = !state.running,
        )
        Spacer(Modifier.height(8.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            if (state.running) {
                OutlinedButton(onClick = onCancel) { Text("Stop") }
            } else {
                Button(onClick = onRun, enabled = state.instruction.isNotBlank()) { Text("Run") }
            }
        }
        Spacer(Modifier.height(12.dp))
        Text("Log", style = MaterialTheme.typography.titleMedium)
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(Color(0xFFF5F5F5), RoundedCornerShape(8.dp))
                .padding(8.dp),
        ) {
            items(state.log) { entry ->
                LogRow(entry)
            }
        }
    }
}

@Composable
private fun LogRow(entry: LogEntry) {
    val (label, body, color) = when (entry) {
        is LogEntry.System -> Triple("SYS ${entry.time}", entry.text, Color(0xFF455A64))
        is LogEntry.Thinking -> Triple("STEP ${entry.step} ${entry.time}", "thinking…", Color(0xFF1976D2))
        is LogEntry.Assistant -> Triple("ASSISTANT ${entry.time}", entry.text, Color(0xFF1B5E20))
        is LogEntry.Tool -> Triple("TOOL ${entry.time}", "${entry.name}(${entry.arguments}) → ${entry.summary}", Color(0xFF6A1B9A))
        is LogEntry.Done -> Triple("DONE ${entry.time}", entry.summary, if (entry.success) Color(0xFF2E7D32) else Color(0xFFC62828))
        is LogEntry.Error -> Triple("ERROR ${entry.time}", entry.message, Color(0xFFC62828))
    }
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(label, color = color, style = MaterialTheme.typography.labelSmall)
        Text(
            body,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            overflow = TextOverflow.Visible,
        )
    }
}

@Composable
fun SettingsTab(
    state: UiState,
    onApiKey: (String) -> Unit,
    onBaseUrl: (String) -> Unit,
    onModel: (String) -> Unit,
    onMaxSteps: (Int) -> Unit,
    onTemperature: (Float) -> Unit,
    onMaxTokens: (Int) -> Unit,
    onReasoningEffort: (String) -> Unit,
    onSystemPrompt: (String) -> Unit,
) {
    val scrollState = rememberScrollState()
    Column(
        modifier = Modifier.fillMaxSize()
            .verticalScroll(scrollState)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Provider", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            value = state.apiKey,
            onValueChange = onApiKey,
            label = { Text("API key") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        OutlinedTextField(
            value = state.baseUrl,
            onValueChange = onBaseUrl,
            label = { Text("Base URL (OpenAI-compatible)") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        OutlinedTextField(
            value = state.model,
            onValueChange = onModel,
            label = { Text("Model") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )

        Spacer(Modifier.height(4.dp))
        Text("Generation", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            value = state.temperature.toString(),
            onValueChange = {
                val v = it.toFloatOrNull()?.coerceIn(0f, 2f)
                if (v != null) onTemperature(v) else onTemperature(state.temperature)
            },
            label = { Text("Temperature (0.0 – 2.0)") },
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            singleLine = true,
        )
        OutlinedTextField(
            value = state.maxTokens.toString(),
            onValueChange = { onMaxTokens(it.toIntOrNull()?.coerceIn(0, 32768) ?: state.maxTokens) },
            label = { Text("Max completion tokens (0 = provider default)") },
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
        )
        OutlinedTextField(
            value = state.reasoningEffort,
            onValueChange = onReasoningEffort,
            label = { Text("Reasoning effort (low / medium / high; empty = off)") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )

        Spacer(Modifier.height(4.dp))
        Text("Agent", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            value = state.maxSteps.toString(),
            onValueChange = { onMaxSteps(it.toIntOrNull()?.coerceIn(1, 200) ?: state.maxSteps) },
            label = { Text("Max steps per run") },
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
        )
        OutlinedTextField(
            value = state.systemPrompt,
            onValueChange = onSystemPrompt,
            label = { Text("System prompt (empty = built-in default)") },
            modifier = Modifier.fillMaxWidth(),
            minLines = 3,
            maxLines = 8,
        )
        Text(
            "OpenAI-compatible chat completions with tool calling. Examples: " +
                "Groq — https://api.groq.com/openai/v1, model openai/gpt-oss-120b. " +
                "OpenAI — https://api.openai.com/v1, model gpt-4o-mini. " +
                "OpenRouter — https://openrouter.ai/api/v1. " +
                "Local llama.cpp — http://10.0.2.2:8080/v1.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
