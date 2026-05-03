package com.aiagent.android.llm

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json

/** Thin OpenAI-compatible chat-completions client. */
@OptIn(ExperimentalSerializationApi::class)
class LlmClient(
    private val baseUrl: String,
    private val apiKey: String,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    private val client = HttpClient(OkHttp) {
        install(ContentNegotiation) { json(json) }
        install(HttpTimeout) {
            requestTimeoutMillis = 120_000
            connectTimeoutMillis = 30_000
            socketTimeoutMillis = 120_000
        }
    }

    suspend fun chat(request: ChatRequest): ChatResponse {
        val url = baseUrl.trimEnd('/') + "/chat/completions"
        val response: HttpResponse = client.post(url) {
            contentType(ContentType.Application.Json)
            if (apiKey.isNotBlank()) {
                header("Authorization", "Bearer $apiKey")
            }
            setBody(request)
        }
        if (response.status != HttpStatusCode.OK) {
            val body = response.bodyAsText()
            val parsed = runCatching { json.decodeFromString(ApiErrorBody.serializer(), body) }
                .getOrNull()
            val message = parsed?.error?.message ?: body.take(500)
            throw LlmException("HTTP ${response.status.value}: $message")
        }
        return response.body()
    }

    fun close() = client.close()
}

class LlmException(message: String) : RuntimeException(message)
