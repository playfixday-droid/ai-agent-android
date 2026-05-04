package com.aiagent.android.agent

import android.content.Context
import android.content.Intent
import android.util.Log
import com.aiagent.android.data.Settings
import com.aiagent.android.llm.ChatMessage
import com.aiagent.android.llm.ChatRequest
import com.aiagent.android.llm.LlmClient
import com.aiagent.android.llm.ToolCall
import com.aiagent.android.service.AgentAccessibilityService
import com.aiagent.android.service.ScreenState
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Drives the LLM <-> device interaction loop.
 *
 *  1. Send the user's instruction + system prompt to the LLM with the available tool schemas.
 *  2. The LLM returns one or more tool calls.
 *  3. Each tool call is executed against the AccessibilityService.
 *  4. The tool results are appended to the conversation and we loop until the LLM calls `done`
 *     (or we exceed [Settings.maxSteps]).
 */
class Agent(
    private val context: Context,
    private val settings: Settings,
    private val onLog: suspend (AgentLog) -> Unit,
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    suspend fun run(userInstruction: String) {
        val service = AgentAccessibilityService.instance
        if (service == null) {
            onLog(AgentLog.Error("Accessibility service is not running. Enable it in Android Settings → Accessibility → AI Agent."))
            return
        }
        if (settings.apiKey.isBlank()) {
            onLog(AgentLog.Error("API key is empty. Set it on the Settings tab."))
            return
        }

        val client = LlmClient(settings.baseUrl, settings.apiKey)
        val systemPrompt = settings.systemPrompt.takeIf { it.isNotBlank() } ?: SYSTEM_PROMPT
        val messages = mutableListOf<ChatMessage>(
            ChatMessage(role = "system", content = systemPrompt),
            ChatMessage(role = "user", content = userInstruction),
        )
        var lastScreenState: ScreenState? = null

        try {
            for (step in 1..settings.maxSteps) {
                onLog(AgentLog.Thinking(step))
                val response = client.chat(
                    ChatRequest(
                        model = settings.model,
                        messages = messages,
                        tools = Tools.toolList(),
                        toolChoice = "auto",
                        temperature = settings.temperature.toDouble(),
                        maxCompletionTokens = settings.maxTokens.takeIf { it > 0 },
                        reasoningEffort = settings.reasoningEffort.takeIf { it.isNotBlank() },
                    ),
                )
                val choice = response.choices.firstOrNull()
                    ?: run {
                        onLog(AgentLog.Error("Empty response from model"))
                        return
                    }
                val msg = choice.message
                msg.content?.takeIf { it.isNotBlank() }?.let {
                    onLog(AgentLog.Assistant(it))
                }
                messages.add(msg)
                val toolCalls = msg.toolCalls.orEmpty()
                if (toolCalls.isEmpty()) {
                    // Model decided to stop without calling `done`. Treat as completion.
                    onLog(AgentLog.Done(msg.content ?: "(stopped without tool call)", success = true))
                    return
                }
                var sawDone = false
                for (call in toolCalls) {
                    val result = executeTool(service, call, lastScreenState)
                    lastScreenState = result.newScreenState ?: lastScreenState
                    onLog(AgentLog.ToolCall(call.function.name, call.function.arguments, result.summary))
                    messages.add(
                        ChatMessage(
                            role = "tool",
                            toolCallId = call.id,
                            name = call.function.name,
                            content = result.toolContent,
                        ),
                    )
                    if (result.done != null) {
                        sawDone = true
                        onLog(AgentLog.Done(result.done.summary, result.done.success))
                    }
                }
                if (sawDone) return
            }
            onLog(AgentLog.Error("Max steps (${settings.maxSteps}) reached without completion."))
        } catch (e: Exception) {
            Log.e(TAG, "Agent loop failed", e)
            onLog(AgentLog.Error(e.message ?: e.toString()))
        } finally {
            client.close()
        }
    }

    private suspend fun executeTool(
        service: AgentAccessibilityService,
        call: ToolCall,
        lastScreenState: ScreenState?,
    ): ToolResult {
        val args = parseArgs(call.function.arguments)
        return when (call.function.name) {
            "read_screen" -> {
                val state = service.captureScreenState()
                ToolResult(
                    toolContent = "Foreground app: ${state.packageName}\n${state.description}",
                    summary = "read_screen → ${state.nodes.size} nodes",
                    newScreenState = state,
                )
            }
            "tap" -> {
                val nodeId = args.intOf("node_id") ?: return ToolResult.error("Missing node_id")
                val node = lastScreenState?.nodes?.getOrNull(nodeId)
                    ?: return ToolResult.error("Unknown node_id $nodeId. Call read_screen first.")
                val ok = service.tapNode(node)
                ToolResult(
                    toolContent = if (ok) "Tapped node $nodeId" else "Tap dispatch failed",
                    summary = "tap node=$nodeId ok=$ok",
                )
            }
            "tap_at" -> {
                val x = args.intOf("x") ?: return ToolResult.error("Missing x")
                val y = args.intOf("y") ?: return ToolResult.error("Missing y")
                val ok = service.tap(x, y)
                ToolResult(
                    toolContent = if (ok) "Tapped at ($x,$y)" else "Tap dispatch failed",
                    summary = "tap_at ($x,$y) ok=$ok",
                )
            }
            "swipe" -> {
                val direction = args.stringOf("direction") ?: return ToolResult.error("Missing direction")
                val distance = args.stringOf("distance") ?: "medium"
                val (x1, y1, x2, y2) = computeSwipe(direction, distance)
                val ok = service.swipe(x1, y1, x2, y2)
                ToolResult(
                    toolContent = if (ok) "Swiped $direction" else "Swipe failed",
                    summary = "swipe $direction $distance ok=$ok",
                )
            }
            "swipe_at" -> {
                val x1 = args.intOf("x1") ?: return ToolResult.error("Missing x1")
                val y1 = args.intOf("y1") ?: return ToolResult.error("Missing y1")
                val x2 = args.intOf("x2") ?: return ToolResult.error("Missing x2")
                val y2 = args.intOf("y2") ?: return ToolResult.error("Missing y2")
                val duration = args.intOf("duration_ms")?.toLong() ?: 300L
                val ok = service.swipe(x1, y1, x2, y2, duration)
                ToolResult(
                    toolContent = if (ok) "Swiped ($x1,$y1)→($x2,$y2)" else "Swipe failed",
                    summary = "swipe_at ($x1,$y1)→($x2,$y2) ok=$ok",
                )
            }
            "type_text" -> {
                val text = args.stringOf("text") ?: return ToolResult.error("Missing text")
                val nodeId = args.intOf("node_id")
                val ok = if (nodeId != null) {
                    val node = lastScreenState?.nodes?.getOrNull(nodeId)
                        ?: return ToolResult.error("Unknown node_id $nodeId")
                    service.typeTextInNode(node, text)
                } else {
                    service.typeText(text)
                }
                ToolResult(
                    toolContent = if (ok) "Typed '$text'" else "Could not find an editable field",
                    summary = "type_text len=${text.length} ok=$ok",
                )
            }
            "press_back" -> {
                val ok = service.pressBack()
                ToolResult(
                    toolContent = if (ok) "Back pressed" else "Back failed",
                    summary = "press_back ok=$ok",
                )
            }
            "press_home" -> {
                val ok = service.pressHome()
                ToolResult(
                    toolContent = if (ok) "Home pressed" else "Home failed",
                    summary = "press_home ok=$ok",
                )
            }
            "press_recents" -> {
                val ok = service.pressRecents()
                ToolResult(
                    toolContent = if (ok) "Recents opened" else "Recents failed",
                    summary = "press_recents ok=$ok",
                )
            }
            "open_app" -> {
                val pkg = args.stringOf("package_name") ?: return ToolResult.error("Missing package_name")
                val launchIntent = context.packageManager.getLaunchIntentForPackage(pkg)
                if (launchIntent == null) {
                    ToolResult.error("App $pkg not installed or no launcher entry.")
                } else {
                    launchIntent.flags = launchIntent.flags or Intent.FLAG_ACTIVITY_NEW_TASK
                    context.startActivity(launchIntent)
                    delay(500)
                    ToolResult(
                        toolContent = "Launched $pkg",
                        summary = "open_app $pkg",
                    )
                }
            }
            "wait" -> {
                val ms = (args.intOf("ms") ?: 500).coerceIn(0, 5000)
                delay(ms.toLong())
                ToolResult(
                    toolContent = "Waited ${ms}ms",
                    summary = "wait $ms",
                )
            }
            "done" -> {
                val summary = args.stringOf("summary") ?: "(no summary)"
                val success = args.boolOf("success") ?: true
                ToolResult(
                    toolContent = "Acknowledged: $summary",
                    summary = "done success=$success: $summary",
                    done = DoneSignal(summary, success),
                )
            }
            else -> ToolResult.error("Unknown tool: ${call.function.name}")
        }
    }

    private fun computeSwipe(direction: String, distance: String): IntArray {
        // Use display metrics from the running service window when available.
        val service = AgentAccessibilityService.instance
        val metrics = service?.resources?.displayMetrics
        val w = metrics?.widthPixels ?: 1080
        val h = metrics?.heightPixels ?: 1920
        val dist = when (distance) {
            "short" -> 0.25
            "long" -> 0.75
            else -> 0.5
        }
        val cx = w / 2
        val cy = h / 2
        return when (direction) {
            "up" -> intArrayOf(cx, (h * (0.5 + dist / 2)).toInt(), cx, (h * (0.5 - dist / 2)).toInt())
            "down" -> intArrayOf(cx, (h * (0.5 - dist / 2)).toInt(), cx, (h * (0.5 + dist / 2)).toInt())
            "left" -> intArrayOf((w * (0.5 + dist / 2)).toInt(), cy, (w * (0.5 - dist / 2)).toInt(), cy)
            "right" -> intArrayOf((w * (0.5 - dist / 2)).toInt(), cy, (w * (0.5 + dist / 2)).toInt(), cy)
            else -> intArrayOf(cx, cy, cx, cy)
        }
    }

    private fun parseArgs(raw: String): JsonObject {
        if (raw.isBlank()) return JsonObject(emptyMap())
        return runCatching { json.parseToJsonElement(raw).jsonObject }.getOrElse { JsonObject(emptyMap()) }
    }

    private fun JsonObject.intOf(key: String): Int? =
        (get(key) as? JsonPrimitive)?.intOrNull
            ?: (get(key) as? JsonPrimitive)?.contentOrNull?.toIntOrNull()

    private fun JsonObject.stringOf(key: String): String? =
        (get(key) as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.boolOf(key: String): Boolean? =
        runCatching { (get(key) as? JsonPrimitive)?.boolean }.getOrNull()

    companion object {
        private const val TAG = "Agent"
        private const val SYSTEM_PROMPT = """You are an AI agent that controls an Android phone via the system Accessibility API on behalf of the user.

You can call tools to inspect the screen and perform UI actions. Always:
1. Start by calling `read_screen` to understand the current state.
2. Decide the next single concrete action and call exactly one tool.
3. After actions that change the UI (tap, type, swipe, open_app, press_back, press_home, press_recents), call `read_screen` again before deciding the next action.
4. When the task is complete (or impossible), call `done` with a concise summary.

Rules:
- Prefer `tap` with a node_id from the most recent `read_screen` over `tap_at` coordinates.
- If a field is editable but not yet focused, tap it first, then call `type_text` on the next turn.
- If you need to scroll to find content, use `swipe up` to scroll content downward.
- Be cautious: do not perform destructive actions (deleting data, sending money, mass-messaging) unless the user explicitly asked for them.
- Keep textual replies short. Most of your output should be tool calls.
- If you see a permission dialog blocking the task, tap the appropriate button (Allow/While using the app) yourself.
"""
    }
}

sealed class AgentLog {
    data class Thinking(val step: Int) : AgentLog()
    data class Assistant(val text: String) : AgentLog()
    data class ToolCall(val name: String, val arguments: String, val summary: String) : AgentLog()
    data class Done(val summary: String, val success: Boolean) : AgentLog()
    data class Error(val message: String) : AgentLog()
}

private data class ToolResult(
    val toolContent: String,
    val summary: String,
    val newScreenState: ScreenState? = null,
    val done: DoneSignal? = null,
) {
    companion object {
        fun error(message: String) = ToolResult(toolContent = "ERROR: $message", summary = "error: $message")
    }
}

private data class DoneSignal(val summary: String, val success: Boolean)
