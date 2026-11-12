package forge.media.pocket.config

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.util.concurrent.ConcurrentHashMap

object GeminiModelResolver {

    private val jsonFormat = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    private val httpClient = HttpClient(OkHttp) {
        install(HttpTimeout) {
            requestTimeoutMillis = 55_000L
            connectTimeoutMillis = 15_000L
            socketTimeoutMillis = 55_000L
        }
        install(ContentNegotiation) {
            json(jsonFormat)
        }
    }

    @Serializable
    private data class ModelsListResponse(
        val models: List<ModelInfoDto> = emptyList()
    )

    @Serializable
    private data class ModelInfoDto(
        val name: String = "",
        val supportedGenerationMethods: List<String> = emptyList()
    )

    private val cacheMutex = Mutex()
    private var cachedKeyFingerprint: String = ""
    private var cachedTextModels: List<String> = emptyList()
    private var cachedTtsModels: List<String> = emptyList()
    private var cachedAtMs: Long = 0L

    /**
     * Tracks models that returned HTTP 404 or "no longer available to new users" during this session
     * so we never waste latency retrying a retired model (e.g., gemini-2.5-flash on newer keys).
     */
    private val deadModels = ConcurrentHashMap.newKeySet<String>()

    private val defaultFallbackTextModels = listOf(
        "gemini-3-flash-preview",
        "gemini-flash-latest",
        "gemini-2.5-flash-lite",
        "gemini-2.0-flash",
        "gemini-2.5-flash"
    )

    private val defaultFallbackTtsModels = listOf(
        "gemini-2.5-flash-preview-tts",
        "gemini-2.5-pro-preview-tts"
    )

    suspend fun resolveTextAndMultimodalModels(apiKey: String): List<String> = withContext(Dispatchers.IO) {
        val cleanKey = apiKey.trim()
        if (cleanKey.isBlank()) return@withContext emptyList()
        ensureDiscovered(cleanKey)
        val candidates = cachedTextModels.ifEmpty { defaultFallbackTextModels }
        val alive = candidates.filter { it !in deadModels }
        alive.ifEmpty { defaultFallbackTextModels.filter { it !in deadModels } }
    }

    suspend fun resolveTtsModels(apiKey: String): List<String> = withContext(Dispatchers.IO) {
        val cleanKey = apiKey.trim()
        if (cleanKey.isBlank()) return@withContext emptyList()
        ensureDiscovered(cleanKey)
        cachedTtsModels.ifEmpty { defaultFallbackTtsModels }.filter { it !in deadModels }
    }

    private suspend fun ensureDiscovered(cleanKey: String) {
        val fp = cleanKey.take(10) + "_" + cleanKey.takeLast(4)
        val now = System.currentTimeMillis()
        cacheMutex.withLock {
            if (fp == cachedKeyFingerprint && cachedTextModels.isNotEmpty() && (now - cachedAtMs) < 30 * 60_000L) {
                return
            }
            try {
                val resp = httpClient.get("https://generativelanguage.googleapis.com/v1beta/models?key=$cleanKey")
                if (!resp.status.isSuccess()) {
                    DiagnosticLogger.log("GEMINI-MODELS", "Список моделей HTTP ${resp.status.value} — использую стандартный пул Flash-моделей")
                    cachedKeyFingerprint = fp
                    cachedTextModels = defaultFallbackTextModels
                    cachedTtsModels = defaultFallbackTtsModels
                    cachedAtMs = now
                    return
                }

                val parsed = jsonFormat.decodeFromString<ModelsListResponse>(resp.bodyAsText())
                val genModels = parsed.models
                    .filter { "generateContent" in it.supportedGenerationMethods }
                    .map { it.name.removePrefix("models/") }
                    .filter { it.startsWith("gemini", ignoreCase = true) && it !in deadModels }

                val ttsFound = genModels.filter { it.contains("tts", ignoreCase = true) }

                val textCandidates = genModels.filter { m ->
                    val low = m.lowercase()
                    !low.contains("tts") &&
                        !low.contains("image") &&
                        !low.contains("imagen") &&
                        !low.contains("veo") &&
                        !low.contains("embedding") &&
                        !low.contains("aqa") &&
                        !low.contains("robotics") &&
                        !low.contains("computer-use") &&
                        !low.contains("thinking-exp") &&
                        !low.contains("live")
                }

                val rankedText = textCandidates.sortedBy { m ->
                    val low = m.lowercase()
                    when {
                        low == "gemini-3-flash-preview" || low == "gemini-3.1-flash-preview" -> 1
                        low == "gemini-flash-latest" -> 2
                        low == "gemini-2.5-flash-lite" -> 3
                        low == "gemini-2.0-flash" -> 4
                        low == "gemini-2.5-flash" -> 5
                        low.contains("flash") && !low.contains("lite") && !low.contains("8b") -> 6
                        low.contains("flash") -> 7
                        low.contains("pro") -> 8
                        else -> 9
                    }
                }.take(6)

                cachedKeyFingerprint = fp
                cachedTextModels = if (rankedText.isNotEmpty()) rankedText else defaultFallbackTextModels
                cachedTtsModels = if (ttsFound.isNotEmpty()) (ttsFound + defaultFallbackTtsModels).distinct() else defaultFallbackTtsModels
                cachedAtMs = now
                DiagnosticLogger.log(
                    "GEMINI-MODELS",
                    "✅ Ключ Gemini проверен. Приоритет моделей: ${cachedTextModels.joinToString(", ")}"
                )
            } catch (e: Exception) {
                DiagnosticLogger.log("GEMINI-MODELS", "Предупреждение авто-определения моделей: ${e.message?.take(80)}")
                cachedKeyFingerprint = fp
                cachedTextModels = defaultFallbackTextModels
                cachedTtsModels = defaultFallbackTtsModels
                cachedAtMs = now
            }
        }
    }

    suspend fun generateTextOrJson(
        apiKey: String,
        prompt: String,
        expectJson: Boolean,
        inlineAudioBase64: String? = null,
        inlineAudioMime: String = "audio/mp4",
        inlineImages: List<Pair<String, String>> = emptyList() // Pair<mimeType, base64Data>
    ): String? = withContext(Dispatchers.IO) {
        val cleanKey = apiKey.trim()
        if (cleanKey.isBlank()) return@withContext null

        val models = resolveTextAndMultimodalModels(cleanKey)
        val partsArray: JsonArray = buildJsonArray {
            if (!inlineAudioBase64.isNullOrBlank()) {
                add(
                    buildJsonObject {
                        putJsonObject("inlineData") {
                            put("mimeType", inlineAudioMime)
                            put("data", inlineAudioBase64)
                        }
                    }
                )
            }
            for ((mime, b64) in inlineImages) {
                if (b64.isNotBlank()) {
                    add(
                        buildJsonObject {
                            putJsonObject("inlineData") {
                                put("mimeType", mime)
                                put("data", b64)
                            }
                        }
                    )
                }
            }
            add(
                buildJsonObject {
                    put("text", prompt)
                }
            )
        }

        val requestJson: JsonObject = buildJsonObject {
            put(
                "contents",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("parts", partsArray)
                        }
                    )
                }
            )
            if (expectJson) {
                putJsonObject("generationConfig") {
                    put("responseMimeType", "application/json")
                }
            }
        }

        val payloadString = requestJson.toString()

        for (modelName in models) {
            if (modelName in deadModels) continue
            try {
                val endpoint = "https://generativelanguage.googleapis.com/v1beta/models/$modelName:generateContent?key=$cleanKey"
                val httpResp = httpClient.post(endpoint) {
                    contentType(ContentType.Application.Json)
                    setBody(payloadString)
                }
                val rawBody = httpResp.bodyAsText()
                if (!httpResp.status.isSuccess()) {
                    val code = httpResp.status.value
                    if (code == 404 ||
                        (code == 400 && (
                            rawBody.contains("no longer available", ignoreCase = true) ||
                                rawBody.contains("not found", ignoreCase = true) ||
                                rawBody.contains("not supported", ignoreCase = true)
                            ))
                    ) {
                        deadModels.add(modelName)
                        DiagnosticLogger.log("AI-CALL", "Модель [$modelName] отключена Google (HTTP $code) -> помечена в чёрный список сессии")
                    } else {
                        DiagnosticLogger.log(
                            "AI-CALL",
                            "Gemini [$modelName] HTTP $code: ${rawBody.take(120).replace("\n", " ")}"
                        )
                    }
                    continue
                }

                val rootObj = jsonFormat.parseToJsonElement(rawBody).jsonObject
                val text = rootObj["candidates"]
                    ?.jsonArray?.firstOrNull()
                    ?.jsonObject?.get("content")
                    ?.jsonObject?.get("parts")
                    ?.jsonArray?.firstOrNull { it.jsonObject["text"] != null }
                    ?.jsonObject?.get("text")
                    ?.jsonPrimitive?.contentOrNull
                    ?.trim()

                if (!text.isNullOrBlank()) {
                    if (!expectJson) return@withContext text
                    val cleaned = text.replace("```json", "").replace("```", "").trim()
                    val objStart = cleaned.indexOf('{')
                    val arrStart = cleaned.indexOf('[')
                    return@withContext when {
                        arrStart >= 0 && (objStart < 0 || arrStart < objStart) -> {
                            val arrEnd = cleaned.lastIndexOf(']')
                            if (arrEnd > arrStart) cleaned.substring(arrStart, arrEnd + 1) else cleaned
                        }
                        objStart >= 0 -> {
                            val objEnd = cleaned.lastIndexOf('}')
                            if (objEnd > objStart) cleaned.substring(objStart, objEnd + 1) else cleaned
                        }
                        else -> cleaned
                    }
                }
            } catch (e: Exception) {
                DiagnosticLogger.log("AI-CALL", "Сбой модели $modelName: ${e.message?.take(80)}")
            }
        }
        null
    }
}
