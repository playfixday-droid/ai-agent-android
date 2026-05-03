package com.aiagent.android.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.aiagent.android.agent.Agent
import com.aiagent.android.agent.AgentLog
import com.aiagent.android.data.Settings
import com.aiagent.android.service.AgentAccessibilityService
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val settings = Settings(app)

    private val _state = MutableStateFlow(
        UiState(
            apiKey = settings.apiKey,
            baseUrl = settings.baseUrl,
            model = settings.model,
            maxSteps = settings.maxSteps,
        ),
    )
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var currentJob: Job? = null

    fun refreshServiceStatus() {
        _state.update { it.copy(serviceEnabled = AgentAccessibilityService.isRunning()) }
    }

    fun updateApiKey(value: String) {
        settings.apiKey = value
        _state.update { it.copy(apiKey = value) }
    }

    fun updateBaseUrl(value: String) {
        settings.baseUrl = value
        _state.update { it.copy(baseUrl = value) }
    }

    fun updateModel(value: String) {
        settings.model = value
        _state.update { it.copy(model = value) }
    }

    fun updateMaxSteps(value: Int) {
        settings.maxSteps = value
        _state.update { it.copy(maxSteps = value) }
    }

    fun updateInstruction(value: String) {
        _state.update { it.copy(instruction = value) }
    }

    fun runAgent() {
        val instruction = _state.value.instruction.trim()
        if (instruction.isEmpty()) return
        if (_state.value.running) return
        refreshServiceStatus()
        _state.update { it.copy(running = true, log = emptyList()) }
        appendLog(LogEntry.System("Starting agent: $instruction"))

        val agent = Agent(getApplication(), settings) { entry -> appendAgentLog(entry) }

        currentJob = viewModelScope.launch {
            try {
                agent.run(instruction)
            } finally {
                _state.update { it.copy(running = false) }
                appendLog(LogEntry.System("Agent finished."))
            }
        }
    }

    fun cancelAgent() {
        currentJob?.cancel()
        currentJob = null
        _state.update { it.copy(running = false) }
        appendLog(LogEntry.System("Cancelled by user."))
    }

    private suspend fun appendAgentLog(entry: AgentLog) {
        val log = when (entry) {
            is AgentLog.Thinking -> LogEntry.Thinking(entry.step)
            is AgentLog.Assistant -> LogEntry.Assistant(entry.text)
            is AgentLog.ToolCall -> LogEntry.Tool(entry.name, entry.arguments, entry.summary)
            is AgentLog.Done -> LogEntry.Done(entry.summary, entry.success)
            is AgentLog.Error -> LogEntry.Error(entry.message)
        }
        appendLog(log)
    }

    private fun appendLog(entry: LogEntry) {
        _state.update { state ->
            state.copy(log = state.log + entry.copyWithTime())
        }
    }
}

data class UiState(
    val instruction: String = "",
    val running: Boolean = false,
    val serviceEnabled: Boolean = false,
    val apiKey: String = "",
    val baseUrl: String = "",
    val model: String = "",
    val maxSteps: Int = 20,
    val log: List<LogEntry> = emptyList(),
)

sealed class LogEntry(val time: String) {
    abstract fun copyWithTime(): LogEntry

    data class System(val text: String, private val t: String = now()) : LogEntry(t) {
        override fun copyWithTime() = copy(t = time)
    }
    data class Thinking(val step: Int, private val t: String = now()) : LogEntry(t) {
        override fun copyWithTime() = copy(t = time)
    }
    data class Assistant(val text: String, private val t: String = now()) : LogEntry(t) {
        override fun copyWithTime() = copy(t = time)
    }
    data class Tool(val name: String, val arguments: String, val summary: String, private val t: String = now()) :
        LogEntry(t) {
        override fun copyWithTime() = copy(t = time)
    }
    data class Done(val summary: String, val success: Boolean, private val t: String = now()) : LogEntry(t) {
        override fun copyWithTime() = copy(t = time)
    }
    data class Error(val message: String, private val t: String = now()) : LogEntry(t) {
        override fun copyWithTime() = copy(t = time)
    }

    companion object {
        private val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)
        fun now(): String = fmt.format(Date())
    }
}
