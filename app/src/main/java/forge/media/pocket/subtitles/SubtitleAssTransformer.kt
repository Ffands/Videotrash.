package forge.media.pocket.subtitles

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Base64
import android.util.Log
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import forge.media.pocket.config.DiagnosticLogger
import forge.media.pocket.config.GeminiModelResolver
import forge.media.pocket.config.NodeConfigStore
import forge.media.pocket.vision.UpperBoxFramingPlan
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.File
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong

enum class TtsVoiceProfile(val geminiVoiceName: String, val pitch: Float, val rate: Float, val label: String) {
    CHARON_DEEP("Charon", 0.88f, 1.04f, "Мужской баритон (Charon)"),
    FENRIR_DYNAMIC("Fenrir", 1.0f, 1.10f, "Мужской бодрый (Fenrir)"),
    KORE_FEMALE("Kore", 1.08f, 1.05f, "Женский чёткий (Kore)"),
    PUCK_STORYTELLER("Puck", 0.96f, 1.06f, "Рассказчик (Puck)")
}

enum class SubtitleDensityMode(val label: String) {
    PUNCH_3_TO_4_WORDS("Динамика (по 3–4 слова)"),
    CLASSIC_6_TO_8_WORDS("Классика (по 6–7 слов)")
}

enum class SubtitlePlacementZone(val defaultY: Int, val label: String) {
    ADAPTIVE_AUTO(960, "Авто (Избегает лиц)"),
    TOP_SAFE(240, "Сверху (Y=240)"),
    UPPER_THIRD(760, "Низ верхнего кадра (Y=760)"),
    CENTER_SEAM(960, "По центру / На шве (Y=960)"),
    LOWER_BOX_MID(1380, "Середина низа (Y=1380)"),
    BOTTOM_SAFE(1640, "Внизу (Y=1640)"),
    CUSTOM_XY(960, "Свои X/Y")
}

data class SubtitleLayerConfig(
    val enabled: Boolean = true,
    val densityMode: SubtitleDensityMode = SubtitleDensityMode.PUNCH_3_TO_4_WORDS,
    val placementZone: SubtitlePlacementZone = SubtitlePlacementZone.ADAPTIVE_AUTO,
    val customX: Int = 540,
    val customY: Int = 960,
    val fontSizePx: Int = 68,
    val syncOffsetMs: Long = 0L,
    val preferredLanguage: String = "ru.*,ru-orig,ru,en.*,en"
) {
    fun resolveCoordinates(framingPlan: UpperBoxFramingPlan?, canvasWidth: Int = 1080, canvasHeight: Int = 1920): Pair<Int, Int> {
        val x = ((customX / 1080.0) * canvasWidth).roundToInt().coerceIn((canvasWidth * 0.12).roundToInt(), (canvasWidth * 0.88).roundToInt())
        val base1920Y = when (placementZone) {
            SubtitlePlacementZone.ADAPTIVE_AUTO -> {
                if (framingPlan?.hasBottomFaceRisk == true) 1080 else 940
            }
            SubtitlePlacementZone.CUSTOM_XY -> customY.coerceIn(120, 1800)
            else -> placementZone.defaultY
        }
        val y = ((base1920Y / 1920.0) * canvasHeight).roundToInt().coerceIn((canvasHeight * 0.08).roundToInt(), (canvasHeight * 0.92).roundToInt())
        return Pair(x, y)
    }
}

data class TimedWord(
    val text: String,
    val startMs: Long,
    val endMs: Long
)

@Serializable
data class EditableTimedWord(
    val id: String = UUID.randomUUID().toString().take(8),
    val text: String,
    val startSec: Double,
    val endSec: Double
)

@Serializable
data class EditableSubtitlePhrase(
    val id: String = UUID.randomUUID().toString().take(8),
    val words: List<EditableTimedWord>
) {
    val startSec: Double
        get() = words.minOfOrNull { it.startSec } ?: 0.0

    val endSec: Double
        get() = words.maxOfOrNull { it.endSec } ?: (startSec + 0.8)

    val joinedText: String
        get() = words.joinToString(" ") { it.text }

    fun withReplacedPhraseText(newPhraseText: String): EditableSubtitlePhrase {
        val tokens = newPhraseText.trim().split(Regex("""\s+""")).filter { it.isNotBlank() }
        if (tokens.isEmpty()) return copy(words = emptyList())
        if (tokens.size == words.size) {
            return copy(
                words = words.mapIndexed { idx, old -> old.copy(text = tokens[idx]) }
            )
        }
        val pStart = startSec
        val pEnd = endSec.coerceAtLeast(pStart + tokens.size * 0.16)
        val step = (pEnd - pStart) / tokens.size.toDouble()
        val rebuilt = tokens.mapIndexed { idx, tok ->
            val ws = ((pStart + idx * step) * 100.0).roundToInt() / 100.0
            val we = if (idx == tokens.lastIndex) {
                (pEnd * 100.0).roundToInt() / 100.0
            } else {
                ((ws + step) * 100.0).roundToInt() / 100.0
            }
            EditableTimedWord(text = tok, startSec = ws, endSec = we.coerceAtLeast(ws + 0.08))
        }
        return copy(words = rebuilt)
    }
}

data class SubtitlePreparationResult(
    val assFile: File?,
    val plainTranscriptText: String
)

data class MusicShazamMatchResult(
    val detectedTrackAndGenre: String,
    val moodAndBpm: String,
    val searchQueries: List<String>,
    val candidateTracks: List<Pair<String, String>> = emptyList()
)

class SubtitleAssTransformer(
    private val context: Context,
    private val configStore: NodeConfigStore
) {

    companion object {
        private const val TAG = "SubtitleAssTransformer"
    }

    private val stagingDir = File(context.cacheDir, "work_staging").apply { mkdirs() }

    private val jsonFormat = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val httpClient = HttpClient(OkHttp) {
        install(HttpTimeout) {
            requestTimeoutMillis = 90_000L
            connectTimeoutMillis = 20_000L
            socketTimeoutMillis = 90_000L
        }
        install(ContentNegotiation) {
            json(jsonFormat)
        }
    }

    @Serializable
    private data class TranscribedSegmentDto(
        val startSec: Double = 0.0,
        val endSec: Double = 1.0,
        val text: String = ""
    )

    /**
     * Модуль 1А: Акустическое пословное распознавание речи УЖЕ ОТРЕДАКТИРОВАННОГО ролика
     * через Groq Whisper Large-v3-Turbo (`timestamp_granularities[]="word"`) с точностью до сотых долей секунды!
     * Возвращает редактируемые фразы + строку статуса источника.
     */
    suspend fun transcribePreparedClipToEditablePhrases(
        preparedVideoFile: File,
        wordsPerPhrase: Int = 4,
        languageHint: String = ""
    ): Pair<List<EditableSubtitlePhrase>, String> = withContext(Dispatchers.IO) {
        if (!preparedVideoFile.exists() || preparedVideoFile.length() < 1000L) {
            return@withContext emptyList<EditableSubtitlePhrase>() to "Видеофайл не найден"
        }

        val groqKey = configStore.getGroqApiKey().trim()
        if (groqKey.isNotBlank()) {
            val groqWords = transcribeViaGroqWhisperWordTimestamps(preparedVideoFile, groqKey, languageHint)
            if (groqWords.isNotEmpty()) {
                val grouped = groupTimedWordsIntoEditablePhrases(groqWords, wordsPerPhrase)
                return@withContext grouped to "✅ Groq Whisper Large-v3-Turbo: ${groqWords.size} слов (${grouped.size} фраз)"
            }
        }

        // Fallback if Groq API key is empty or network call failed: Gemini Audio STT on the prepared file
        val geminiWords = transcribeViaGeminiAudioFallback(
            videoFile = preparedVideoFile,
            trimStartSec = 0.0,
            durationSec = 120.0
        )
        if (geminiWords.isNotEmpty()) {
            val editableWords = geminiWords.map { tw ->
                EditableTimedWord(
                    text = tw.text,
                    startSec = ((tw.startMs / 10.0).roundToInt()) / 100.0,
                    endSec = ((tw.endMs / 10.0).roundToInt()) / 100.0
                )
            }
            val grouped = groupTimedWordsIntoEditablePhrases(editableWords, wordsPerPhrase)
            val note = if (groqKey.isBlank()) {
                "ℹ️ Gemini Audio STT: ${editableWords.size} слов (добавь ключ Groq gsk_... для 100% акустической точности Whisper)"
            } else {
                "ℹ️ Резерв Gemini Audio STT: ${editableWords.size} слов"
            }
            return@withContext grouped to note
        }

        emptyList<EditableSubtitlePhrase>() to "⚠️ Не удалось распознать речь: укажи бесплатный Groq API Key (gsk_...) во вкладке «⚙️ Ключи»"
    }

    private suspend fun transcribeViaGroqWhisperWordTimestamps(
        videoFile: File,
        groqApiKey: String,
        languageHint: String
    ): List<EditableTimedWord> = withContext(Dispatchers.IO) {
        val tempAudio = File(stagingDir, "whisper_${System.currentTimeMillis()}.m4a")
        try {
            val ok = extractSliceM4aAudio(
                inputVideo = videoFile,
                startSec = 0.0,
                durationSec = 180.0,
                outputM4a = tempAudio
            )
            if (!ok || !tempAudio.exists() || tempAudio.length() < 500L) {
                DiagnosticLogger.log("WHISPER", "Не удалось извлечь аудиодорожку .m4a из видео для Groq Whisper")
                return@withContext emptyList()
            }

            val audioBytes = tempAudio.readBytes()
            DiagnosticLogger.log(
                "WHISPER",
                "🎙 Отправка аудио (${audioBytes.size / 1024} KB) в Groq Whisper Large-v3-Turbo (word-level timestamps)..."
            )

            val response = httpClient.post("https://api.groq.com/openai/v1/audio/transcriptions") {
                header(HttpHeaders.Authorization, "Bearer $groqApiKey")
                setBody(
                    MultiPartFormDataContent(
                        formData {
                            append(
                                key = "file",
                                value = audioBytes,
                                headers = Headers.build {
                                    append(HttpHeaders.ContentType, "audio/mp4")
                                    append(HttpHeaders.ContentDisposition, "filename=\"speech.m4a\"")
                                }
                            )
                            append("model", "whisper-large-v3-turbo")
                            append("response_format", "verbose_json")
                            append("timestamp_granularities[]", "word")
                            append("timestamp_granularities[]", "segment")
                            if (languageHint.isNotBlank()) {
                                append("language", languageHint.trim().take(2).lowercase(Locale.ROOT))
                            }
                        }
                    )
                )
            }

            val rawBody = response.bodyAsText()
            if (!response.status.isSuccess()) {
                DiagnosticLogger.log(
                    "WHISPER:ERR",
                    "Groq HTTP ${response.status.value}: ${rawBody.take(180)}"
                )
                return@withContext emptyList()
            }

            val root = jsonFormat.parseToJsonElement(rawBody).jsonObject
            val wordsJson = root["words"]?.jsonArray
            val parsedWords = mutableListOf<EditableTimedWord>()

            if (wordsJson != null && wordsJson.isNotEmpty()) {
                for (item in wordsJson) {
                    val obj = item.jsonObject
                    val wText = obj["word"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                    if (wText.isBlank()) continue
                    val startS = obj["start"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() ?: 0.0
                    val rawEndS = obj["end"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() ?: (startS + 0.22)
                    val endS = rawEndS.coerceAtLeast(startS + 0.08)
                    parsedWords.add(
                        EditableTimedWord(
                            text = wText,
                            startSec = (startS * 100.0).roundToInt() / 100.0,
                            endSec = (endS * 100.0).roundToInt() / 100.0
                        )
                    )
                }
            }

            // Fallback to segments array if words array was omitted
            if (parsedWords.isEmpty()) {
                val segsJson = root["segments"]?.jsonArray
                if (segsJson != null) {
                    for (seg in segsJson) {
                        val obj = seg.jsonObject
                        val segText = obj["text"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                        val tokens = segText.split(Regex("""\s+""")).filter { it.isNotBlank() }
                        if (tokens.isEmpty()) continue
                        val sStart = obj["start"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() ?: 0.0
                        val sEnd = (obj["end"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() ?: (sStart + 1.0))
                            .coerceAtLeast(sStart + 0.20)
                        val step = (sEnd - sStart) / tokens.size
                        tokens.forEachIndexed { idx, tok ->
                            val ws = ((sStart + idx * step) * 100.0).roundToInt() / 100.0
                            val we = ((if (idx == tokens.lastIndex) sEnd else ws + step) * 100.0).roundToInt() / 100.0
                            parsedWords.add(EditableTimedWord(text = tok, startSec = ws, endSec = we.coerceAtLeast(ws + 0.08)))
                        }
                    }
                }
            }

            DiagnosticLogger.log("WHISPER", "✅ Groq Whisper вернул ${parsedWords.size} точных пословных таймкодов!")
            parsedWords
        } catch (e: Exception) {
            DiagnosticLogger.logError("WHISPER", "Сбой запроса к Groq Whisper API", e)
            emptyList()
        } finally {
            tempAudio.delete()
        }
    }

    fun groupTimedWordsIntoEditablePhrases(
        words: List<EditableTimedWord>,
        maxWordsPerPhrase: Int = 4
    ): List<EditableSubtitlePhrase> {
        if (words.isEmpty()) return emptyList()
        val cap = maxWordsPerPhrase.coerceIn(2, 8)
        val phrases = mutableListOf<EditableSubtitlePhrase>()
        val bucket = mutableListOf<EditableTimedWord>()

        for (w in words) {
            val prev = bucket.lastOrNull()
            val hasPauseBreak = prev != null && (w.startSec - prev.endSec) >= 0.42
            val prevEndedSentence = prev != null && (
                prev.text.endsWith(".") || prev.text.endsWith("!") || prev.text.endsWith("?")
                )

            if (bucket.isNotEmpty() && (bucket.size >= cap || hasPauseBreak || prevEndedSentence)) {
                phrases.add(EditableSubtitlePhrase(words = bucket.toList()))
                bucket.clear()
            }
            bucket.add(w)
        }
        if (bucket.isNotEmpty()) {
            phrases.add(EditableSubtitlePhrase(words = bucket.toList()))
        }
        return phrases
    }

    /**
     * Компилирует отредактированные пользователем фразы и пословные таймкоды 1-в-1 в караоке `.ass` файл!
     */
    fun compileEditablePhrasesToAssFile(
        phrases: List<EditableSubtitlePhrase>,
        config: SubtitleLayerConfig,
        framingPlan: UpperBoxFramingPlan?,
        canvasWidth: Int = 1080,
        canvasHeight: Int = 1920
    ): File? {
        val nonEmpty = phrases.filter { it.words.isNotEmpty() }
        if (!config.enabled || nonEmpty.isEmpty()) return null

        val (posX, posY) = config.resolveCoordinates(framingPlan, canvasWidth, canvasHeight)
        val scaledFont = ((config.fontSizePx / 1920.0) * canvasHeight).roundToInt().coerceIn(28, 120)
        val offsetMs = config.syncOffsetMs

        val header = """
            [Script Info]
            ScriptType: v4.00+
            PlayResX: $canvasWidth
            PlayResY: $canvasHeight
            WrapStyle: 0
            ScaledBorderAndShadow: yes

            [V4+ Styles]
            Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding
            Style: KaraokeStyle,Sans-Serif,$scaledFont,&H0000FFFF,&H00FFFFFF,&H00000000,&H80000000,-1,0,0,0,100,100,1,0,1,5,2,5,40,40,40,1

            [Events]
            Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
        """.trimIndent()

        val sb = StringBuilder(header).append("\n")

        for (phrase in nonEmpty) {
            val wordsMs = phrase.words.mapNotNull { w ->
                val sMs = ((w.startSec * 1000.0).roundToLong() + offsetMs).coerceAtLeast(0L)
                val eMs = ((w.endSec * 1000.0).roundToLong() + offsetMs).coerceAtLeast(sMs + 80L)
                if (w.text.isBlank()) null else Triple(w.text.trim(), sMs, eMs)
            }
            if (wordsMs.isEmpty()) continue

            val pStartMs = wordsMs.first().second
            val pEndMs = wordsMs.last().third.coerceAtLeast(pStartMs + 180L)
            val startAss = formatAssTime(pStartMs)
            val endAss = formatAssTime(pEndMs)

            val karaokeText = buildString {
                append("{\\an5\\pos($posX,$posY)}")
                var cursorMs = pStartMs
                for ((txt, wStart, wEnd) in wordsMs) {
                    val gapCs = ((wStart - cursorMs).coerceAtLeast(0L) / 10L)
                    if (gapCs > 2L) {
                        append("{\\k$gapCs}")
                    }
                    val effStart = maxOf(cursorMs, wStart)
                    val durCs = ((wEnd - effStart) / 10L).coerceIn(5L, 400L)
                    val cleanTxt = txt.replace("{", "").replace("}", "").replace("\\", "")
                    append("{\\k$durCs}$cleanTxt ")
                    cursorMs = effStart + durCs * 10L
                }
            }.trim()

            sb.append("Dialogue: 0,$startAss,$endAss,KaraokeStyle,,0,0,0,,$karaokeText\n")
        }

        val outAss = File(stagingDir, "subs_edited_${System.currentTimeMillis()}.ass")
        outAss.writeText(sb.toString(), Charsets.UTF_8)
        DiagnosticLogger.log("SUBS", "✅ Скомпилирован ручной .ass файл караоке (${nonEmpty.size} фраз)")
        return outAss
    }

    /**
     * Генерирует синхронные караоке-субтитры `.ass` с ТОЧНОЙ привязкой к обрезанному куску `[trimStartSec .. trimEndSec]`,
     * учётом скорости воспроизведения `playbackSpeed` и ручного сдвига `config.syncOffsetMs`!
     */
    suspend fun prepareAssSubtitles(
        sourceUrl: String,
        localVideoFile: File,
        config: SubtitleLayerConfig,
        framingPlan: UpperBoxFramingPlan?,
        trimStartSec: Double = 0.0,
        trimEndSec: Double = 0.0,
        playbackSpeed: Float = 1.0f,
        canvasWidth: Int = 1080,
        canvasHeight: Int = 1920,
        cachedWords: List<TimedWord>? = null
    ): Pair<SubtitlePreparationResult, List<TimedWord>> = withContext(Dispatchers.IO) {
        val trimStartMs = (trimStartSec.coerceAtLeast(0.0) * 1000.0).roundToLong()
        val trimEndMs = if (trimEndSec > trimStartSec + 0.5) {
            (trimEndSec * 1000.0).roundToLong()
        } else {
            Long.MAX_VALUE
        }
        val spanSec = if (trimEndSec > trimStartSec + 0.5) {
            (trimEndSec - trimStartSec).coerceIn(2.0, 180.0)
        } else {
            90.0
        }

        var cameFromFullVideoCaptions = false
        val rawWords: List<TimedWord> = if (!cachedWords.isNullOrEmpty()) {
            cameFromFullVideoCaptions = true
            cachedWords
        } else {
            // 1. Try ultra-fast Innertube srv3 word-timed captions first
            val innertubeWords = tryFetchInnertubeSrv3Words(sourceUrl)
            if (innertubeWords.isNotEmpty()) {
                cameFromFullVideoCaptions = true
                innertubeWords
            } else {
                // 2. Fallback to yt-dlp VTT
                val vttFile = tryDownloadYoutubeVtt(sourceUrl, config.preferredLanguage)
                if (vttFile != null && vttFile.exists()) {
                    cameFromFullVideoCaptions = true
                    val parsed = parseWebVttToTimedWords(vttFile.readText())
                    vttFile.delete()
                    parsed
                } else {
                    // 3. Fallback to Gemini Audio STT on the EXACT trimmed slice [trimStartSec .. +spanSec]!
                    cameFromFullVideoCaptions = false
                    transcribeViaGeminiAudioFallback(localVideoFile, trimStartSec, spanSec)
                }
            }
        }

        // Shift full-video timestamps by -trimStartMs so t=0.0s matches the start of the cut video!
        val safeSpeed = playbackSpeed.coerceIn(0.5f, 2.0f).toDouble()
        val alignedWords: List<TimedWord> = rawWords.mapNotNull { w ->
            val relStart: Long
            val relEnd: Long
            if (cameFromFullVideoCaptions) {
                if (w.endMs <= trimStartMs + 40L || w.startMs >= trimEndMs) {
                    return@mapNotNull null
                }
                relStart = (w.startMs - trimStartMs).coerceAtLeast(0L)
                relEnd = (w.endMs - trimStartMs).coerceAtLeast(relStart + 90L)
            } else {
                relStart = w.startMs.coerceAtLeast(0L)
                relEnd = w.endMs.coerceAtLeast(relStart + 90L)
            }

            val speedScaledStart = (relStart / safeSpeed).roundToLong() + config.syncOffsetMs
            val speedScaledEnd = (relEnd / safeSpeed).roundToLong() + config.syncOffsetMs
            if (speedScaledEnd <= 40L) return@mapNotNull null

            val clampedStart = speedScaledStart.coerceAtLeast(0L)
            val clampedEnd = speedScaledEnd.coerceAtLeast(clampedStart + 90L)
            TimedWord(text = w.text, startMs = clampedStart, endMs = clampedEnd)
        }

        val transcriptPlain = alignedWords.joinToString(" ") { it.text }

        if (!config.enabled || alignedWords.isEmpty()) {
            return@withContext Pair(
                SubtitlePreparationResult(assFile = null, plainTranscriptText = transcriptPlain),
                rawWords
            )
        }

        val (posX, posY) = config.resolveCoordinates(framingPlan, canvasWidth, canvasHeight)
        val assFile = File(stagingDir, "subs_${System.currentTimeMillis()}.ass")
        val assContent = buildKaraokeAssDocument(
            words = alignedWords,
            config = config,
            posX = posX,
            posY = posY,
            canvasWidth = canvasWidth,
            canvasHeight = canvasHeight
        )
        assFile.writeText(assContent, Charsets.UTF_8)
        DiagnosticLogger.log(
            "SUBS",
            "✅ Караоке-субтитры синхронизированы: ${alignedWords.size} слов (старт=${trimStartSec}с, сдвиг=${config.syncOffsetMs}мс, скорость=${playbackSpeed}x)"
        )
        Pair(
            SubtitlePreparationResult(assFile = assFile, plainTranscriptText = transcriptPlain),
            rawWords
        )
    }

    /**
     * Быстрое скачивание пословных таймкодов напрямую из YouTube Innertube (`fmt=srv3` с точностью до миллисекунд!).
     */
    private suspend fun tryFetchInnertubeSrv3Words(sourceUrl: String): List<TimedWord> {
        val ytId = extractYoutubeId(sourceUrl) ?: return emptyList()
        return try {
            val ua = "com.google.android.apps.youtube.vr.oculus/1.60.19 (Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip"
            val reqBody = buildJsonObject {
                put("videoId", ytId)
                put("contentCheckOk", true)
                put("racyCheckOk", true)
                putJsonObject("context") {
                    putJsonObject("client") {
                        put("clientName", "ANDROID_VR")
                        put("clientVersion", "1.60.19")
                        put("hl", "ru")
                        put("gl", "RU")
                    }
                }
            }
            val resp = httpClient.post("https://www.youtube.com/youtubei/v1/player?prettyPrint=false") {
                contentType(ContentType.Application.Json)
                header(HttpHeaders.UserAgent, ua)
                setBody(reqBody.toString())
            }
            if (!resp.status.isSuccess()) return emptyList()

            val root = jsonFormat.parseToJsonElement(resp.bodyAsText()).jsonObject
            val tracks = root["captions"]
                ?.jsonObject?.get("playerCaptionsTracklistRenderer")
                ?.jsonObject?.get("captionTracks")
                ?.jsonArray
                ?: return emptyList()

            val chosen = tracks.firstOrNull {
                it.jsonObject["languageCode"]?.jsonPrimitive?.contentOrNull?.startsWith("ru") == true
            } ?: tracks.firstOrNull {
                it.jsonObject["languageCode"]?.jsonPrimitive?.contentOrNull?.startsWith("en") == true
            } ?: tracks.firstOrNull() ?: return emptyList()

            val baseUrl = chosen.jsonObject["baseUrl"]?.jsonPrimitive?.contentOrNull ?: return emptyList()
            val srv3Url = if (baseUrl.contains("fmt=")) baseUrl else "$baseUrl&fmt=srv3"
            val capResp = httpClient.get(srv3Url) {
                header(HttpHeaders.UserAgent, ua)
            }
            if (!capResp.status.isSuccess()) return emptyList()
            parseSrv3OrTimedTextXmlToWords(capResp.bodyAsText())
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun parseSrv3OrTimedTextXmlToWords(xmlRaw: String): List<TimedWord> {
        val words = mutableListOf<TimedWord>()
        val pRegex = Regex("""<p\b[^>]*t="(\d+)"[^>]*d="(\d+)"[^>]*>(.*?)</p>""", RegexOption.DOT_MATCHES_ALL)
        val sRegex = Regex("""<s\b[^>]*>(.*?)</s>""", RegexOption.DOT_MATCHES_ALL)
        val sOffsetRegex = Regex("""t="(\d+)"""")
        val stripTags = Regex("""<[^>]+>""")

        val pMatches = pRegex.findAll(xmlRaw).toList()
        if (pMatches.isNotEmpty()) {
            for (pm in pMatches) {
                val pStartMs = pm.groupValues[1].toLongOrNull() ?: continue
                val pDurMs = (pm.groupValues[2].toLongOrNull() ?: 1500L).coerceAtLeast(150L)
                val pEndMs = pStartMs + pDurMs
                val inner = pm.groupValues[3]

                val sList = Regex("""<s\b([^>]*)>(.*?)</s>""", RegexOption.DOT_MATCHES_ALL).findAll(inner).toList()
                if (sList.isNotEmpty()) {
                    val tempTokens = mutableListOf<Pair<Long, String>>()
                    for (sm in sList) {
                        val attrs = sm.groupValues[1]
                        val relMs = sOffsetRegex.find(attrs)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
                        val tok = decodeEntities(sm.groupValues[2].replace(stripTags, "")).trim()
                        if (tok.isNotBlank()) {
                            tempTokens.add((pStartMs + relMs) to tok)
                        }
                    }
                    for (idx in tempTokens.indices) {
                        val (wStart, wText) = tempTokens[idx]
                        val wEnd = if (idx + 1 < tempTokens.size) {
                            tempTokens[idx + 1].first.coerceAtLeast(wStart + 90L)
                        } else {
                            pEndMs.coerceAtLeast(wStart + 140L)
                        }
                        words.add(TimedWord(wText, wStart, wEnd))
                    }
                } else {
                    val plain = decodeEntities(inner.replace(stripTags, " ")).trim()
                    val split = plain.split(Regex("""\s+""")).filter { it.isNotBlank() }
                    if (split.isNotEmpty()) {
                        val step = (pDurMs / split.size).coerceAtLeast(90L)
                        split.forEachIndexed { idx, tok ->
                            val ws = pStartMs + idx * step
                            val we = if (idx == split.lastIndex) pEndMs else ws + step
                            words.add(TimedWord(tok, ws, we))
                        }
                    }
                }
            }
            if (words.isNotEmpty()) return deduplicateOverlappingWords(words)
        }

        // Classic <text start="1.23" dur="2.34">...</text>
        val textRegex = Regex("""<text\b[^>]*start="([0-9.]+)"[^>]*dur="([0-9.]+)"[^>]*>(.*?)</text>""", RegexOption.DOT_MATCHES_ALL)
        for (tm in textRegex.findAll(xmlRaw)) {
            val startMs = ((tm.groupValues[1].toDoubleOrNull() ?: 0.0) * 1000.0).roundToLong()
            val durMs = (((tm.groupValues[2].toDoubleOrNull() ?: 1.5) * 1000.0).roundToLong()).coerceAtLeast(150L)
            val endMs = startMs + durMs
            val plain = decodeEntities(tm.groupValues[3].replace(stripTags, " ")).trim()
            val split = plain.split(Regex("""\s+""")).filter { it.isNotBlank() }
            if (split.isNotEmpty()) {
                val step = (durMs / split.size).coerceAtLeast(90L)
                split.forEachIndexed { idx, tok ->
                    val ws = startMs + idx * step
                    val we = if (idx == split.lastIndex) endMs else ws + step
                    words.add(TimedWord(tok, ws, we))
                }
            }
        }
        return deduplicateOverlappingWords(words)
    }

    private fun decodeEntities(s: String): String =
        s.replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&apos;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("\n", " ")

    private fun extractYoutubeId(url: String): String? {
        val patterns = listOf(
            Regex("""youtube\.com/shorts/([a-zA-Z0-9_-]{11})"""),
            Regex("""[?&]v=([a-zA-Z0-9_-]{11})"""),
            Regex("""youtu\.be/([a-zA-Z0-9_-]{11})""")
        )
        for (p in patterns) {
            p.find(url)?.groupValues?.get(1)?.let { return it }
        }
        return null
    }

    private fun tryDownloadYoutubeVtt(sourceUrl: String, langPref: String): File? {
        if (!sourceUrl.startsWith("http")) return null
        return try {
            val baseName = "vtt_${System.currentTimeMillis()}"
            val outTemplate = File(stagingDir, "$baseName.%(ext)s").absolutePath
            val request = YoutubeDLRequest(sourceUrl).apply {
                addOption("--skip-download")
                addOption("--write-auto-sub")
                addOption("--write-sub")
                addOption("--sub-langs", langPref)
                addOption("--sub-format", "vtt")
                addOption("--no-playlist")
                addOption("--no-check-certificates")
                addOption("--extractor-args", "youtube:player_client=android_vr,ios,tv_samsung")
                configStore.getYoutubeCookiesFile()?.let { cookies ->
                    addOption("--cookies", cookies.absolutePath)
                }
                addOption("-o", outTemplate)
            }
            YoutubeDL.getInstance().execute(request)

            stagingDir.listFiles { f ->
                f.name.startsWith(baseName) && f.extension.equals("vtt", ignoreCase = true)
            }?.firstOrNull()
        } catch (e: Exception) {
            Log.w(TAG, "Native VTT download unavailable for $sourceUrl: ${e.message}")
            null
        }
    }

    /**
     * Точный парсер WebVTT с корректной обработкой YouTube Rollup (без сдвига на 1 слово и без дублей прошлых строк!).
     */
    fun parseWebVttToTimedWords(vttRaw: String): List<TimedWord> {
        val lines = vttRaw.lines()
        val cueHeaderRegex = Regex(
            """(\d{2}:\d{2}:\d{2}\.\d{3}|\d{2}:\d{2}\.\d{3})\s*-->\s*(\d{2}:\d{2}:\d{2}\.\d{3}|\d{2}:\d{2}\.\d{3})"""
        )
        val inlineTimeTagRegex = Regex("""<(\d{2}:\d{2}:\d{2}\.\d{3}|\d{2}:\d{2}\.\d{3})>""")
        val htmlTagStripRegex = Regex("""<[^>]+>""")

        val extractedWords = mutableListOf<TimedWord>()
        var lastPlainCueLine = ""
        var i = 0

        while (i < lines.size) {
            val line = lines[i].trim()
            val headerMatch = cueHeaderRegex.find(line)
            if (headerMatch != null) {
                val cueStartMs = parseVttTimestampMs(headerMatch.groupValues[1])
                val cueEndMs = parseVttTimestampMs(headerMatch.groupValues[2])
                i++

                val cueLines = mutableListOf<String>()
                while (i < lines.size && lines[i].isNotBlank() && !cueHeaderRegex.containsMatchIn(lines[i])) {
                    cueLines.add(lines[i].trim())
                    i++
                }

                // Skip instant 10ms rollup snapshot cues that YouTube inserts between active lines
                if (cueEndMs - cueStartMs < 40L) continue

                val taggedLines = cueLines.filter { inlineTimeTagRegex.containsMatchIn(it) }
                if (taggedLines.isNotEmpty()) {
                    for (tl in taggedLines) {
                        val timedPairs = mutableListOf<Pair<Long, String>>()
                        val segments = tl.split(Regex("""(?=<\d{2}:)"""))
                        for (seg in segments) {
                            val tm = inlineTimeTagRegex.find(seg)
                            val wordStart = if (tm != null) {
                                parseVttTimestampMs(tm.groupValues[1])
                            } else {
                                cueStartMs
                            }
                            val cleanWord = decodeEntities(seg.replace(htmlTagStripRegex, "")).trim()
                            if (cleanWord.isNotEmpty()) {
                                timedPairs.add(wordStart to cleanWord)
                            }
                        }
                        for (idx in timedPairs.indices) {
                            val (wStart, wText) = timedPairs[idx]
                            val wEnd = if (idx + 1 < timedPairs.size) {
                                timedPairs[idx + 1].first.coerceAtLeast(wStart + 90L)
                            } else {
                                cueEndMs.coerceAtLeast(wStart + 140L)
                            }
                            extractedWords.add(TimedWord(wText, wStart, wEnd))
                        }
                        lastPlainCueLine = timedPairs.joinToString(" ") { it.second }
                    }
                } else {
                    // Plain VTT without inline <00:..> tags: filter out rollup repeat of previous cue line
                    val freshLines = cueLines.map { decodeEntities(it.replace(htmlTagStripRegex, "")).trim() }
                        .filter { it.isNotBlank() && it != lastPlainCueLine }
                    val cleanText = freshLines.joinToString(" ").trim()
                    if (cleanText.isNotBlank()) {
                        lastPlainCueLine = freshLines.last()
                        val words = cleanText.split(Regex("""\s+""")).filter { it.isNotBlank() }
                        if (words.isNotEmpty()) {
                            val totalDur = (cueEndMs - cueStartMs).coerceAtLeast(words.size * 110L)
                            val step = totalDur / words.size
                            words.forEachIndexed { idx, w ->
                                val wStart = cueStartMs + idx * step
                                val wEnd = if (idx == words.lastIndex) cueEndMs else wStart + step
                                extractedWords.add(TimedWord(w, wStart, wEnd))
                            }
                        }
                    }
                }
            } else {
                i++
            }
        }

        return deduplicateOverlappingWords(extractedWords)
    }

    private fun deduplicateOverlappingWords(raw: List<TimedWord>): List<TimedWord> {
        if (raw.isEmpty()) return emptyList()
        val result = mutableListOf<TimedWord>()
        for (word in raw) {
            val prev = result.lastOrNull()
            if (prev != null &&
                prev.text.equals(word.text, ignoreCase = true) &&
                abs(word.startMs - prev.startMs) < 380L
            ) {
                continue
            }
            result.add(word)
        }
        return result
    }

    private suspend fun transcribeViaGeminiAudioFallback(
        videoFile: File,
        trimStartSec: Double,
        durationSec: Double
    ): List<TimedWord> = withContext(Dispatchers.IO) {
        val apiKey = configStore.getGeminiApiKey()
        if (apiKey.isBlank()) {
            DiagnosticLogger.log("SUBS", "Ключ Gemini пуст, а субтитры на YouTube отсутствуют -> слов=0")
            return@withContext emptyList()
        }

        val tempAudio = File(stagingDir, "stt_${System.currentTimeMillis()}.m4a")
        try {
            val extracted = extractSliceM4aAudio(videoFile, trimStartSec, durationSec.coerceIn(3.0, 120.0), tempAudio)
            if (!extracted || !tempAudio.exists()) {
                DiagnosticLogger.log("SUBS", "Не удалось извлечь аудиодорожку .m4a для субтитров")
                return@withContext emptyList()
            }

            DiagnosticLogger.log(
                "SUBS",
                "Отправка точного аудио-куска (${String.format(Locale.US, "%.1fс", trimStartSec)}..+${durationSec.toInt()}с, ${tempAudio.length() / 1024} KB) в Gemini..."
            )
            val base64Audio = Base64.encodeToString(tempAudio.readBytes(), Base64.NO_WRAP)
            val prompt = """
                Transcribe the speech in this audio clip with accurate second-level timestamps starting from 0.0 seconds of this audio file.
                Split into short phrases of 2 to 4 words in the original spoken language (e.g., Russian).
                Return strictly a JSON array:
                [{"startSec": 0.2, "endSec": 1.3, "text": "точная фраза"}]
            """.trimIndent()

            val cleanRawJson = GeminiModelResolver.generateTextOrJson(
                apiKey = apiKey,
                prompt = prompt,
                expectJson = true,
                inlineAudioBase64 = base64Audio,
                inlineAudioMime = "audio/mp4"
            ) ?: return@withContext emptyList()

            val segments = jsonFormat.decodeFromString<List<TranscribedSegmentDto>>(cleanRawJson)
            val words = mutableListOf<TimedWord>()

            for (seg in segments) {
                val tokens = seg.text.trim().split(Regex("""\s+""")).filter { it.isNotBlank() }
                if (tokens.isEmpty()) continue
                val startMs = (seg.startSec * 1000.0).roundToLong().coerceAtLeast(0L)
                val endMs = (seg.endSec * 1000.0).roundToLong().coerceAtLeast(startMs + 180L)
                val step = ((endMs - startMs) / tokens.size).coerceAtLeast(80L)

                tokens.forEachIndexed { idx, token ->
                    val wStart = startMs + idx * step
                    val wEnd = if (idx == tokens.lastIndex) endMs else wStart + step
                    words.add(TimedWord(token, wStart, wEnd))
                }
            }
            DiagnosticLogger.log("SUBS", "✅ Gemini распознал ${words.size} слов с точной привязкой к выбранному куску")
            words
        } catch (e: Exception) {
            Log.e(TAG, "Gemini audio fallback transcription failed", e)
            DiagnosticLogger.logError("SUBS", "Ошибка распознавания речи через Gemini", e)
            emptyList()
        } finally {
            tempAudio.delete()
        }
    }

    private fun buildKaraokeAssDocument(
        words: List<TimedWord>,
        config: SubtitleLayerConfig,
        posX: Int,
        posY: Int,
        canvasWidth: Int = 1080,
        canvasHeight: Int = 1920
    ): String {
        val wordsPerCard = when (config.densityMode) {
            SubtitleDensityMode.PUNCH_3_TO_4_WORDS -> 4
            SubtitleDensityMode.CLASSIC_6_TO_8_WORDS -> 7
        }
        val scaledFont = ((config.fontSizePx / 1920.0) * canvasHeight).roundToInt().coerceIn(28, 110)

        val header = """
            [Script Info]
            ScriptType: v4.00+
            PlayResX: $canvasWidth
            PlayResY: $canvasHeight
            WrapStyle: 0
            ScaledBorderAndShadow: yes

            [V4+ Styles]
            Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding
            Style: KaraokeStyle,Sans-Serif,$scaledFont,&H0000FFFF,&H00FFFFFF,&H00000000,&H80000000,-1,0,0,0,100,100,1,0,1,5,2,5,40,40,40,1

            [Events]
            Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
        """.trimIndent()

        val sb = StringBuilder(header).append("\n")
        val chunks = words.chunked(wordsPerCard)

        for (chunk in chunks) {
            if (chunk.isEmpty()) continue
            val chunkStartMs = chunk.first().startMs
            val chunkEndMs = chunk.last().endMs.coerceAtLeast(chunkStartMs + 220L)

            val startAss = formatAssTime(chunkStartMs)
            val endAss = formatAssTime(chunkEndMs)

            val karaokeText = buildString {
                append("{\\an5\\pos($posX,$posY)}")
                var cursorMs = chunkStartMs
                for (w in chunk) {
                    val gapBeforeCs = ((w.startMs - cursorMs).coerceAtLeast(0L) / 10L)
                    if (gapBeforeCs > 2L) {
                        append("{\\k$gapBeforeCs}")
                    }
                    val effectiveWordStart = maxOf(cursorMs, w.startMs)
                    val durCentiseconds = ((w.endMs - effectiveWordStart) / 10L).coerceIn(6L, 250L)
                    val tag = if (config.densityMode == SubtitleDensityMode.PUNCH_3_TO_4_WORDS) {
                        "\\k$durCentiseconds"
                    } else {
                        "\\kf$durCentiseconds"
                    }
                    val sanitized = w.text
                        .replace("{", "")
                        .replace("}", "")
                        .replace("\\", "")
                    append("{$tag}$sanitized ")
                    cursorMs = effectiveWordStart + durCentiseconds * 10L
                }
            }.trim()

            sb.append("Dialogue: 0,$startAss,$endAss,KaraokeStyle,,0,0,0,,$karaokeText\n")
        }

        return sb.toString()
    }

    private fun parseVttTimestampMs(raw: String): Long {
        val parts = raw.trim().split(":")
        return try {
            if (parts.size == 3) {
                val h = parts[0].toLong()
                val m = parts[1].toLong()
                val secParts = parts[2].split(".")
                val s = secParts[0].toLong()
                val ms = secParts.getOrElse(1) { "0" }.padEnd(3, '0').take(3).toLong()
                (h * 3600 + m * 60 + s) * 1000L + ms
            } else {
                val m = parts[0].toLong()
                val secParts = parts[1].split(".")
                val s = secParts[0].toLong()
                val ms = secParts.getOrElse(1) { "0" }.padEnd(3, '0').take(3).toLong()
                (m * 60 + s) * 1000L + ms
            }
        } catch (_: Exception) {
            0L
        }
    }

    private fun formatAssTime(ms: Long): String {
        val clamped = ms.coerceAtLeast(0L)
        val totalSec = clamped / 1000L
        val h = totalSec / 3600L
        val m = (totalSec % 3600L) / 60L
        val s = totalSec % 60L
        val cs = (clamped % 1000L) / 10L
        return String.format(Locale.US, "%d:%02d:%02d.%02d", h, m, s, cs)
    }

    // =========================================================================
    // ИНСТРУМЕНТ ПЕРЕОЗВУЧКИ: ИИ-РЕРАЙТ / ПЕРЕВОД РЕЧИ + НЕЙРО-СИНТЕЗ (TTS)
    // =========================================================================

    suspend fun generateDubbingScriptFromVideo(
        sourceUrl: String,
        localVideoFile: File,
        startSec: Double,
        endSec: Double,
        isolatedVocalStemFile: File? = null
    ): String = withContext(Dispatchers.IO) {
        val durationSpan = ((if (endSec > startSec + 1.0) endSec - startSec else 30.0)).coerceIn(4.0, 90.0)
        val targetWordCount = (durationSpan * 2.3).toInt().coerceIn(8, 190)

        DiagnosticLogger.log(
            "DUB-SCRIPT",
            "Извлекаю речь из куска ${String.format(Locale.US, "%.1f..%.1fс", startSec, endSec)} (~$targetWordCount слов)..."
        )

        var rawTranscriptSegment = ""
        if (isolatedVocalStemFile == null && sourceUrl.startsWith("http")) {
            val words = tryFetchInnertubeSrv3Words(sourceUrl).ifEmpty {
                val vtt = tryDownloadYoutubeVtt(sourceUrl, "ru.*,ru-orig,ru,en.*,en")
                val parsed = if (vtt != null && vtt.exists()) parseWebVttToTimedWords(vtt.readText()) else emptyList()
                vtt?.delete()
                parsed
            }
            if (words.isNotEmpty()) {
                val startMs = (startSec * 1000).toLong()
                val endMs = if (endSec > startSec + 1.0) (endSec * 1000).toLong() else Long.MAX_VALUE
                val sliceWords = words.filter { it.startMs in startMs..endMs }.ifEmpty { words }
                rawTranscriptSegment = sliceWords.joinToString(" ") { it.text }.take(2800)
            }
        }

        val apiKey = configStore.getGeminiApiKey().trim()
        if (apiKey.isBlank()) {
            return@withContext if (rawTranscriptSegment.isNotBlank()) {
                rawTranscriptSegment
            } else {
                "Укажите Gemini API Key во вкладке «⚙️ Ключи/Лог» для авто-расшифровки и рерайта речи, либо напишите свой текст переозвучки."
            }
        }

        val tempAudio = File(stagingDir, "dub_slice_${System.currentTimeMillis()}.m4a")
        try {
            val promptText = buildString {
                append("Ты — профессиональный автор закадровой переозвучки для Shorts и Reels.\n")
                append("Хронометраж куска: ${durationSpan.toInt()} секунд (нужно ~$targetWordCount слов).\n")
                append("1. Если речь на английском/иностранном — сделай живой русский перевод под дубляж.\n")
                append("2. Если речь на русском — сделай плотный рерайт своими словами с сохранением панчей.\n")
                append("3. Верни ТОЛЬКО чистый готовый текст для диктора на русском без скобок и таймкодов.\n")
                if (rawTranscriptSegment.isNotBlank()) {
                    append("\nРасшифровка куска:\n$rawTranscriptSegment")
                }
            }

            var audioB64: String? = null
            if (isolatedVocalStemFile != null && isolatedVocalStemFile.exists()) {
                audioB64 = Base64.encodeToString(isolatedVocalStemFile.readBytes(), Base64.NO_WRAP)
            } else if (rawTranscriptSegment.isBlank()) {
                val ok = extractSliceM4aAudio(localVideoFile, startSec, durationSpan, tempAudio)
                if (ok && tempAudio.exists()) {
                    audioB64 = Base64.encodeToString(tempAudio.readBytes(), Base64.NO_WRAP)
                }
            }

            val generatedScript = GeminiModelResolver.generateTextOrJson(
                apiKey = apiKey,
                prompt = promptText,
                expectJson = false,
                inlineAudioBase64 = audioB64,
                inlineAudioMime = "audio/mp4"
            ).orEmpty()

            if (generatedScript.isNotBlank()) {
                DiagnosticLogger.log("DUB-SCRIPT", "✅ Готов скрипт переозвучки (${generatedScript.length} симв.)")
                generatedScript
            } else {
                rawTranscriptSegment.ifBlank { "Не удалось извлечь речь — введите текст озвучки вручную." }
            }
        } catch (e: Exception) {
            DiagnosticLogger.logError("DUB-SCRIPT", "Ошибка генерации скрипта переозвучки", e)
            rawTranscriptSegment.ifBlank { "Ошибка Gemini: ${e.message?.take(100)}" }
        } finally {
            tempAudio.delete()
        }
    }

    suspend fun synthesizeVoiceoverToAudioFile(
        scriptText: String,
        voiceProfile: TtsVoiceProfile = TtsVoiceProfile.CHARON_DEEP
    ): File = withContext(Dispatchers.IO) {
        val cleanText = scriptText.trim()
        if (cleanText.isBlank()) {
            throw IllegalArgumentException("Текст для озвучки пуст!")
        }

        val apiKey = configStore.getGeminiApiKey().trim()
        if (apiKey.isNotBlank()) {
            try {
                DiagnosticLogger.log(
                    "DUB-TTS",
                    "🎙 Генерация нейросетевой озвучки через Gemini Neural TTS (голос=${voiceProfile.geminiVoiceName})..."
                )
                val geminiFile = trySynthesizeViaGeminiNeuralTts(cleanText, voiceProfile, apiKey)
                if (geminiFile != null && geminiFile.exists() && geminiFile.length() > 1000L) {
                    DiagnosticLogger.log(
                        "DUB-TTS",
                        "✅ Нейро-озвучка Gemini готова: ${geminiFile.name} (${geminiFile.length() / 1024} KB)"
                    )
                    return@withContext geminiFile
                }
            } catch (e: Exception) {
                DiagnosticLogger.logError("DUB-TTS", "Предупреждение Gemini TTS (переключаюсь на встроенный синтезатор Android)", e)
            }
        }

        DiagnosticLogger.log("DUB-TTS", "🎙 Синтез озвучки через локальный движок Android TextToSpeech (${voiceProfile.label})...")
        val localWav = synthesizeViaAndroidSystemTts(cleanText, voiceProfile)
        if (localWav != null && localWav.exists() && localWav.length() > 500L) {
            DiagnosticLogger.log("DUB-TTS", "✅ Локальная озвучка готова: ${localWav.name} (${localWav.length() / 1024} KB)")
            return@withContext localWav
        }

        throw IllegalStateException("Не удалось синтезировать озвучку (проверьте ключ Gemini или голосовой движок Android).")
    }

    suspend fun importCustomVoiceoverUri(uri: Uri): File = withContext(Dispatchers.IO) {
        val outFile = File(stagingDir, "voiceover_custom_${System.currentTimeMillis()}.m4a")
        val rawTemp = File(stagingDir, "vo_raw_${UUID.randomUUID()}")
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                rawTemp.outputStream().use { output ->
                    input.copyTo(output)
                }
            } ?: throw IllegalStateException("Не удалось открыть аудиофайл")

            val ok = convertAudioToNormalizedM4a(rawTemp, outFile)
            if (!ok || !outFile.exists()) {
                rawTemp.copyTo(outFile, overwrite = true)
            }
            DiagnosticLogger.log("DUB-IMPORT", "✅ Загружен пользовательский аудиофайл: ${outFile.name} (${outFile.length() / 1024} KB)")
            outFile
        } finally {
            rawTemp.delete()
        }
    }

    private suspend fun trySynthesizeViaGeminiNeuralTts(
        text: String,
        voiceProfile: TtsVoiceProfile,
        apiKey: String
    ): File? = withContext(Dispatchers.IO) {
        val payload = buildJsonObject {
            put(
                "contents",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put(
                                "parts",
                                buildJsonArray {
                                    add(
                                        buildJsonObject {
                                            put("text", "Прочитай выразительно и чётко на русском языке: $text")
                                        }
                                    )
                                }
                            )
                        }
                    )
                }
            )
            putJsonObject("generationConfig") {
                put(
                    "responseModalities",
                    buildJsonArray {
                        add(JsonPrimitive("AUDIO"))
                    }
                )
                putJsonObject("speechConfig") {
                    putJsonObject("voiceConfig") {
                        putJsonObject("prebuiltVoiceConfig") {
                            put("voiceName", voiceProfile.geminiVoiceName)
                        }
                    }
                }
            }
        }

        var b64Match: String? = null
        val ttsModels = GeminiModelResolver.resolveTtsModels(apiKey)
        for (ttsModel in ttsModels) {
            try {
                val endpoint = "https://generativelanguage.googleapis.com/v1beta/models/$ttsModel:generateContent?key=$apiKey"
                val httpResp = httpClient.post(endpoint) {
                    contentType(ContentType.Application.Json)
                    setBody(payload.toString())
                }
                if (!httpResp.status.isSuccess()) continue
                val rawResp = httpResp.bodyAsText()
                val found = Regex(""""data"\s*:\s*"([A-Za-z0-9+/=\r\n]+)"""")
                    .find(rawResp)?.groupValues?.get(1)
                if (!found.isNullOrBlank()) {
                    b64Match = found
                    break
                }
            } catch (_: Exception) {
            }
        }
        val validB64 = b64Match ?: return@withContext null

        val pcmBytes = Base64.decode(validB64.replace("\n", "").replace("\r", ""), Base64.DEFAULT)
        if (pcmBytes.size < 1000) return@withContext null

        val rawPcmFile = File(stagingDir, "gemini_tts_${System.currentTimeMillis()}.pcm")
        val outM4aFile = File(stagingDir, "voiceover_gemini_${System.currentTimeMillis()}.m4a")
        try {
            rawPcmFile.writeBytes(pcmBytes)
            val converted = convertRawPcm24kToM4a(rawPcmFile, outM4aFile)
            if (converted && outM4aFile.exists()) outM4aFile else null
        } finally {
            rawPcmFile.delete()
        }
    }

    private suspend fun synthesizeViaAndroidSystemTts(
        text: String,
        voiceProfile: TtsVoiceProfile
    ): File? = withTimeoutOrNull(35_000L) {
        suspendCancellableCoroutine { cont ->
            val outWav = File(stagingDir, "voiceover_tts_${System.currentTimeMillis()}.wav")
            var ttsRef: TextToSpeech? = null
            ttsRef = TextToSpeech(context.applicationContext) { status ->
                val engine = ttsRef
                if (status != TextToSpeech.SUCCESS || engine == null) {
                    if (cont.isActive) cont.resume(null)
                    return@TextToSpeech
                }
                try {
                    val ruResult = engine.setLanguage(Locale("ru", "RU"))
                    if (ruResult == TextToSpeech.LANG_MISSING_DATA || ruResult == TextToSpeech.LANG_NOT_SUPPORTED) {
                        engine.language = Locale.getDefault()
                    }
                    engine.setPitch(voiceProfile.pitch)
                    engine.setSpeechRate(voiceProfile.rate)

                    val utteranceId = "forge_tts_${System.currentTimeMillis()}"
                    engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(id: String?) {}
                        override fun onDone(id: String?) {
                            try {
                                engine.shutdown()
                            } catch (_: Exception) {
                            }
                            if (cont.isActive) cont.resume(outWav)
                        }

                        @Deprecated("Deprecated in Java")
                        override fun onError(id: String?) {
                            try {
                                engine.shutdown()
                            } catch (_: Exception) {
                            }
                            if (cont.isActive) cont.resume(null)
                        }
                    })

                    val params = Bundle()
                    val res = engine.synthesizeToFile(text, params, outWav, utteranceId)
                    if (res != TextToSpeech.SUCCESS) {
                        engine.shutdown()
                        if (cont.isActive) cont.resume(null)
                    }
                } catch (_: Exception) {
                    try {
                        engine.shutdown()
                    } catch (_: Exception) {
                    }
                    if (cont.isActive) cont.resume(null)
                }
            }
        }
    }

    private suspend fun extractSliceM4aAudio(
        inputVideo: File,
        startSec: Double,
        durationSec: Double,
        outputM4a: File
    ): Boolean = suspendCancellableCoroutine { cont ->
        val cmd = buildString {
            append("-hide_banner -loglevel error -nostdin -y ")
            if (startSec > 0.0) {
                append(String.format(Locale.US, "-ss %.2f ", startSec))
            }
            append(String.format(Locale.US, "-t %.2f ", durationSec.coerceIn(2.5, 120.0)))
            append("-i \"${inputVideo.absolutePath}\" ")
            append("-vn -ac 1 -ar 16000 -c:a aac -b:a 48k ")
            append("\"${outputM4a.absolutePath}\"")
        }
        FFmpegKit.executeAsync(cmd) { session ->
            if (cont.isActive) cont.resume(ReturnCode.isSuccess(session.returnCode))
        }
    }

    private suspend fun convertRawPcm24kToM4a(rawPcm: File, outM4a: File): Boolean =
        suspendCancellableCoroutine { cont ->
            val cmd = buildString {
                append("-hide_banner -loglevel error -nostdin -y ")
                append("-f s16le -ar 24000 -ac 1 -i \"${rawPcm.absolutePath}\" ")
                append("-c:a aac -b:a 192k -ar 48000 ")
                append("\"${outM4a.absolutePath}\"")
            }
            FFmpegKit.executeAsync(cmd) { session ->
                if (cont.isActive) cont.resume(ReturnCode.isSuccess(session.returnCode))
            }
        }

    private suspend fun convertAudioToNormalizedM4a(inputAudio: File, outM4a: File): Boolean =
        suspendCancellableCoroutine { cont ->
            val cmd = buildString {
                append("-hide_banner -loglevel error -nostdin -y ")
                append("-i \"${inputAudio.absolutePath}\" ")
                append("-vn -c:a aac -b:a 192k -ar 48000 ")
                append("\"${outM4a.absolutePath}\"")
            }
            FFmpegKit.executeAsync(cmd) { session ->
                if (cont.isActive) cont.resume(ReturnCode.isSuccess(session.returnCode))
            }
        }

    suspend fun analyzeMusicStemAndFindSimilar(
        musicStemFile: File,
        videoTitle: String
    ): MusicShazamMatchResult = withContext(Dispatchers.IO) {
        val apiKey = configStore.getGeminiApiKey()
        var detectedTrackAndGenre = "Фоновый инструментал (Ambient / Lo-Fi / Electronic)"
        var moodAndBpm = "Атмосферный ритм ~105 BPM"
        val searchQueries = mutableListOf<String>()

        if (apiKey.isNotBlank() && musicStemFile.exists() && musicStemFile.length() > 1000L) {
            val compactSlice = File(stagingDir, "shazam_probe_${System.currentTimeMillis()}.m4a")
            try {
                extractSliceM4aAudio(musicStemFile, 0.0, 25.0, compactSlice)
                val targetFile = if (compactSlice.exists() && compactSlice.length() > 500L) compactSlice else musicStemFile
                val base64Audio = Base64.encodeToString(targetFile.readBytes(), Base64.NO_WRAP)

                val prompt = """
                    Ты — музыкальный эксперт и AI-Shazam. Прослушай эту музыкальную дорожку из видео «$videoTitle».
                    Верни СТРОГО в таком формате:
                    TRACK_GENRE: <название трека если узнал, жанр и инструменты на русском>
                    MOOD_BPM: <настроение и BPM на русском>
                    QUERY: <поисковый запрос 1 на английском + no copyright instrumental>
                    QUERY: <поисковый запрос 2 на английском + royalty free music>
                """.trimIndent()

                val text = GeminiModelResolver.generateTextOrJson(
                    apiKey = apiKey,
                    prompt = prompt,
                    expectJson = false,
                    inlineAudioBase64 = base64Audio,
                    inlineAudioMime = "audio/mp4"
                ).orEmpty()

                for (line in text.lines()) {
                    val trimmed = line.trim()
                    when {
                        trimmed.startsWith("TRACK_GENRE:", ignoreCase = true) -> {
                            detectedTrackAndGenre = trimmed.substringAfter(":").trim().ifBlank { detectedTrackAndGenre }
                        }
                        trimmed.startsWith("MOOD_BPM:", ignoreCase = true) -> {
                            moodAndBpm = trimmed.substringAfter(":").trim().ifBlank { moodAndBpm }
                        }
                        trimmed.startsWith("QUERY:", ignoreCase = true) -> {
                            val q = trimmed.substringAfter(":").trim()
                            if (q.length >= 5) searchQueries.add(q)
                        }
                    }
                }
            } catch (e: Exception) {
                DiagnosticLogger.logError("AI-SHAZAM", "Ошибка анализа мелодии", e)
            } finally {
                compactSlice.delete()
            }
        }

        if (searchQueries.isEmpty()) {
            val lower = videoTitle.lowercase(Locale.getDefault())
            when {
                lower.contains("мото") || lower.contains("дрифт") || lower.contains("авто") -> {
                    detectedTrackAndGenre = "Melodic Phonk / Synthwave"
                    moodAndBpm = "Ночной драйв ~120 BPM"
                    searchQueries.add("melodic atmospheric synthwave phonk instrumental no copyright")
                }
                else -> {
                    detectedTrackAndGenre = "Chill Lo-Fi / Ambient Instrumental"
                    moodAndBpm = "Фоновый ритм ~95 BPM"
                    searchQueries.add("chill lofi aesthetic instrumental beat no copyright")
                }
            }
        }

        val candidates = mutableListOf<Pair<String, String>>()
        for (query in searchQueries.take(2)) {
            if (candidates.size >= 6) break
            try {
                val request = YoutubeDLRequest("ytsearch6:$query").apply {
                    addOption("--flat-playlist")
                    addOption("--dump-json")
                    addOption("--no-warnings")
                    addOption("--socket-timeout", "15")
                }
                val response = YoutubeDL.getInstance().execute(request)
                for (line in response.out.lines()) {
                    val trimmed = line.trim()
                    if (!trimmed.startsWith("{")) continue
                    val id = Regex(""""id"\s*:\s*"([^"]+)"""").find(trimmed)?.groupValues?.get(1) ?: continue
                    val title = Regex(""""title"\s*:\s*"([^"]+)"""").find(trimmed)?.groupValues?.get(1) ?: "Instrumental Track"
                    val dur = Regex(""""duration"\s*:\s*([0-9.]+)""").find(trimmed)?.groupValues?.get(1)?.toDoubleOrNull() ?: 90.0
                    if (dur in 20.0..360.0) {
                        val url = "https://www.youtube.com/watch?v=$id"
                        if (candidates.none { it.first == url }) {
                            candidates.add(url to title)
                        }
                    }
                }
            } catch (_: Exception) {
            }
        }

        MusicShazamMatchResult(
            detectedTrackAndGenre = detectedTrackAndGenre,
            moodAndBpm = moodAndBpm,
            searchQueries = searchQueries,
            candidateTracks = candidates
        )
    }

    suspend fun downloadReplacementMusicCandidate(
        sourceUrl: String,
        trackTitle: String = "SimilarMelody"
    ): File = withContext(Dispatchers.IO) {
        val stamp = System.currentTimeMillis()
        val rawDownload = File(stagingDir, "bgm_raw_$stamp.m4a")
        val finalM4a = File(stagingDir, "bgm_replace_$stamp.m4a")

        DiagnosticLogger.log("AI-SHAZAM", "⬇️ Скачиваю мелодию для подмены: «$trackTitle»...")

        val req = YoutubeDLRequest(sourceUrl).apply {
            addOption("--no-playlist")
            addOption("-f", "bestaudio[ext=m4a]/bestaudio/best")
            addOption("--download-sections", "*0-95")
            addOption("--extractor-args", "youtube:player_client=android_vr,ios,tv_samsung")
            addOption("--socket-timeout", "20")
            addOption("-o", rawDownload.absolutePath)
        }
        YoutubeDL.getInstance().execute(req)

        val actualDownloaded = stagingDir.listFiles()?.firstOrNull {
            it.name.startsWith("bgm_raw_$stamp") && it.length() > 5_000L
        } ?: throw IllegalStateException("Не удалось скачать аудиопоток мелодии")

        try {
            val ok = convertAudioToNormalizedM4a(actualDownloaded, finalM4a)
            if (!ok || !finalM4a.exists() || finalM4a.length() < 2_000L) {
                throw IllegalStateException("Ошибка нормализации новой мелодии в AAC")
            }
            finalM4a
        } finally {
            actualDownloaded.delete()
        }
    }
}
