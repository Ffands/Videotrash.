package forge.media.pocket.discovery

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Rect
import android.util.Base64
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import forge.media.pocket.config.DiagnosticLogger
import forge.media.pocket.config.GeminiModelResolver
import forge.media.pocket.config.NodeConfigStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.readBytes
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.encodeURLParameter
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.ByteArrayOutputStream
import java.nio.charset.Charset
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

enum class ContentSortOrder(val label: String) {
    POPULAR("🔥 По популярности"),
    NEWEST("🆕 По новизне"),
    VIRAL_VELOCITY("📈 Взлетающие (Просм./день)"),
    RELEVANCE("🎯 Точное совпадение")
}

enum class SearchTimePeriod(val label: String, val daysBack: Int?, val redditParam: String) {
    DAY("За 24 часа", 1, "day"),
    WEEK("За неделю", 7, "week"),
    MONTH("За месяц", 31, "month"),
    YEAR("За год", 365, "year"),
    ALL_TIME("За всё время", null, "all")
}

enum class HuntPlatform(val label: String, val badge: String) {
    ALL("🌐 Все площадки (YT + VK + Pikabu + Reddit + Rutube)", "🌐 ВСЕ"),
    YOUTUBE("▶️ YouTube", "▶️ YT"),
    VK_CLIPS("💙 VK Клипы / Видео", "💙 VK"),
    PIKABU("🧁 Пикабу Видео", "🧁 Pikabu"),
    REDDIT("👽 Reddit Video", "👽 Reddit"),
    RUTUBE("📺 Rutube", "📺 Rutube")
}

enum class SearchDurationMode(
    val label: String,
    val minSec: Int,
    val maxSec: Int,
    val appendShortsKeyword: Boolean
) {
    ANY_UP_TO_6M("🌐 До 6 минут (22с–6м)", 22, 360, false),
    ORIGINALS_1_TO_6M("🎬 Исходники 1–12 мин (Под нарезку)", 50, 720, false),
    SHORTS_ONLY("⚡ Только Shorts / Клипы (22–70 сек)", 22, 72, true)
}

enum class SearchLanguageMode(val label: String) {
    RUSSIAN_ONLY("🇷🇺 Строго Русский"),
    ENGLISH_FOR_DUB("🇺🇸 Английский (Под переозвучку)"),
    ANY_LANGUAGE("🌐 Любой язык")
}

@Serializable
data class DiscoveredMediaCandidate(
    val id: String,
    val url: String,
    val title: String,
    val author: String,
    val platform: HuntPlatform,
    val durationSec: Int,
    val viewOrScoreCount: Long,
    val thumbnailUrl: String = "",
    val uploadDateYmd: String = "",
    val publishedAgeLabel: String = "",
    val descriptionSnippet: String = "",
    val aiWhatsInside: String = "",
    val cloutStatusLabel: String = "",
    val entityPopularityNote: String = "",
    val conversionScore: Int = 0
)

@Serializable
data class VideoHighlightMoment(
    val startSec: Int = 0,
    val endSec: Int = 45,
    val title: String = "",
    val summary: String = ""
)

@Serializable
data class VideoInsideDigest(
    val overallSummary: String = "",
    val keyTopics: List<String> = emptyList(),
    val moments: List<VideoHighlightMoment> = emptyList(),
    val hasTranscript: Boolean = false
)

@Serializable
data class DiscoveredSpamChannel(
    val channelName: String,
    val channelUrl: String,
    val topicQuery: String,
    val spamScore: Int,
    val videosFoundCount: Int,
    val estimatedUploadsPerWeek: Double,
    val titleRandomnessPct: Int,
    val patternSummary: String,
    val sampleVideos: List<DiscoveredMediaCandidate> = emptyList()
)

class MultiPlatformCrawlSession(
    val rawQuery: String,
    val negativeTags: String,
    val platform: HuntPlatform,
    val sortOrder: ContentSortOrder = ContentSortOrder.POPULAR,
    val period: SearchTimePeriod = SearchTimePeriod.MONTH,
    val durationMode: SearchDurationMode = SearchDurationMode.ANY_UP_TO_6M,
    val languageMode: SearchLanguageMode = SearchLanguageMode.RUSSIAN_ONLY,
    val isBackgroundMode: Boolean = false,
    val enableAiCuration: Boolean = true
) {
    var pageIndex: Int = 0
    var ytShortContinuation: String? = null
    var ytMidContinuation: String? = null
    var ytExhausted: Boolean = false
    var variantIndex: Int = 0
    val variantQueries: MutableList<String> = mutableListOf()

    var vkOffset: Int = 0
    var vkExhausted: Boolean = false

    var pikabuPage: Int = 1
    var pikabuExhausted: Boolean = false

    var redditAfter: String? = null
    var redditExhausted: Boolean = false

    var rutubePage: Int = 1
    var rutubeExhausted: Boolean = false

    val seenKeys: MutableSet<String> = LinkedHashSet()
    val acceptedTitles: MutableList<String> = mutableListOf()
    val perAuthorCounts: MutableMap<String, Int> = HashMap()

    fun hasMorePages(): Boolean {
        return when (platform) {
            HuntPlatform.YOUTUBE -> !ytExhausted || variantIndex < variantQueries.size
            HuntPlatform.VK_CLIPS -> !vkExhausted
            HuntPlatform.PIKABU -> !pikabuExhausted
            HuntPlatform.REDDIT -> !redditExhausted
            HuntPlatform.RUTUBE -> !rutubeExhausted
            HuntPlatform.ALL -> !ytExhausted || variantIndex < variantQueries.size ||
                !vkExhausted || !pikabuExhausted || !redditExhausted || !rutubeExhausted
        }
    }
}

/**
 * Link-Only Multi-Platform Deep Search & Visual Frame Curation Engine v4.0.
 *
 * Fixes all previous defects:
 * 1. Pikabu text articles eliminated (`story-block_type_video` / `data-type="video"` strictly required).
 * 2. ZERO silent fallback from `За месяц` to `За всё время` (no 7–10 year old fossil videos!).
 * 3. Word `"шорты"` / `"шортс"` automatically mapped to vertical `#shorts` (`22–72s`), never literal clothing,
 *    and 10–18s empty bait skits are discarded.
 * 4. Strict `RUSSIAN_ONLY` (`>= 55%` Cyrillic letters in title, zero Reddit English leak).
 * 5. Cross-channel Fuzzy Title Stem Deduplication + Persistent Seen-History so repeating a search NEVER repeats old links.
 * 6. Multimodal Gemini + ML Kit Mid-Video Frame Inspection (`hq1.jpg / hq2.jpg / hq3.jpg` at 25%/50%/75%):
 *    - Sources: filters for casual couch-scrolling mass audience (kills "бизнес-стендап" & unfunny 15s trash).
 *    - Backgrounds: kills cartoons, tutorials, guides, menus, facecams, and story videos — passes ONLY pure monotonous filler!
 */
class TopicHunterEngine(private val context: Context) {

    private val configStore = NodeConfigStore(context)

    private val jsonFormat = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    private val httpClient = HttpClient(OkHttp) {
        install(HttpTimeout) {
            requestTimeoutMillis = 28_000L
            connectTimeoutMillis = 12_000L
            socketTimeoutMillis = 28_000L
        }
        install(ContentNegotiation) {
            json(jsonFormat)
        }
    }

    private val quickFrameDetector by lazy {
        val opts = FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setMinFaceSize(0.11f)
            .build()
        FaceDetection.getClient(opts)
    }

    init {
        SearchBlacklistStore.ensureInit(context)
    }

    companion object {
        private const val DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"
        private const val MOBILE_UA =
            "Mozilla/5.0 (Linux; Android 14; CPH2581) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36"

        private val UNRELATED_SHELF_TITLES = listOf(
            "people also watched",
            "previously watched",
            "for you",
            "explore more",
            "другие зрители",
            "ранее просмотренные",
            "вам может понравиться",
            "похожие запросы"
        )
    }

    /**
     * Normalizes user query: strips literal "шорты"/"шортс" (so YouTube doesn't search for pants!)
     * and detects if user explicitly asked for vertical Shorts.
     */
    private fun parseUserSearchIntent(rawQuery: String, initialMode: SearchDurationMode): Pair<String, SearchDurationMode> {
        val words = rawQuery.trim().split(Regex("""\s+""")).filter { it.isNotBlank() }
        var wantsShorts = initialMode == SearchDurationMode.SHORTS_ONLY
        val cleanedWords = mutableListOf<String>()
        for (w in words) {
            val low = w.lowercase(Locale.ROOT).removePrefix("#")
            if (low == "шорты" || low == "шортс" || low == "шортсы" || low == "shorts" || low == "reels" || low == "рилс") {
                wantsShorts = true
            } else {
                cleanedWords.add(w)
            }
        }
        val finalMode = if (wantsShorts) SearchDurationMode.SHORTS_ONLY else initialMode
        val baseSubject = cleanedWords.joinToString(" ").trim().ifBlank {
            // User literally typed ONLY "шорты" -> search popular relatable Russian comedy/story shorts!
            "смешная история из жизни юмор"
        }
        return baseSubject to finalMode
    }

    /**
     * Builds clean, natural search query variations so repeated searches or deeper pages
     * explore fresh angles for casual mass audiences instead of starving YouTube's index.
     */
    private fun buildMassAudienceQueryVariants(cleanSubject: String, isBackground: Boolean, isShorts: Boolean): List<String> {
        if (isBackground) {
            val low = cleanSubject.lowercase(Locale.ROOT)
            val isAsmr = low.contains("мыл") || low.contains("soap") || low.contains("песок") ||
                low.contains("sand") || low.contains("слайм") || low.contains("slime") ||
                low.contains("пресс") || low.contains("asmr") || low.contains("асмр")
            return if (isAsmr) {
                listOf(
                    "$cleanSubject satisfying no talking",
                    "$cleanSubject asmr oddly satisfying",
                    "$cleanSubject резка залипательное без слов"
                )
            } else {
                listOf(
                    "$cleanSubject gameplay no commentary",
                    "$cleanSubject pure gameplay 60fps no facecam",
                    "$cleanSubject clean gameplay walkrun no talking"
                )
            }
        }

        val suffix = if (isShorts) " #shorts" else ""
        val low = cleanSubject.lowercase(Locale.ROOT)
        return when {
            low.contains("стендап") || low.contains("standup") || low.contains("комик") -> listOf(
                "$cleanSubject$suffix",
                "стендап про отношения и бытовуху$suffix",
                "стендап смешной случай из жизни$suffix",
                "лучший стендап монолог зал в слезах$suffix",
                "стендап про работу и семью$suffix"
            )
            else -> listOf(
                "$cleanSubject$suffix",
                "$cleanSubject жиза юмор$suffix",
                "$cleanSubject лучшая история$suffix"
            )
        }.distinct()
    }

    /**
     * Crawls next page across selected platform(s), enforces strict filters + seen-history + fuzzy dedup,
     * and runs Gemini Multimodal Audience/Frame Curation!
     */
    suspend fun crawlNextPage(session: MultiPlatformCrawlSession): List<DiscoveredMediaCandidate> =
        withContext(Dispatchers.IO) {
            val (cleanSubject, effectiveDurationMode) = parseUserSearchIntent(session.rawQuery, session.durationMode)
            if (session.variantQueries.isEmpty()) {
                session.variantQueries.addAll(
                    buildMassAudienceQueryVariants(
                        cleanSubject = cleanSubject,
                        isBackground = session.isBackgroundMode,
                        isShorts = effectiveDurationMode == SearchDurationMode.SHORTS_ONLY
                    )
                )
            }

            session.pageIndex += 1
            val currentPage = session.pageIndex

            // Keep query sent to platforms natural (at most 2 minus words on YT so YT doesn't starve to 0 results!)
            val activeQueryBase = session.variantQueries.getOrElse(session.variantIndex) { cleanSubject }
            val lightMinus = if (session.isBackgroundMode) "-tutorial -cartoon" else "-стрим -лекция"
            val ytEffectiveQuery = "$activeQueryBase $lightMinus".trim()

            DiagnosticLogger.log(
                "CRAWLER",
                "🔎 Стр. #$currentPage | Запрос: «$activeQueryBase» | Режим: ${if (session.isBackgroundMode) "ФОН-НАПОЛНИТЕЛЬ" else effectiveDurationMode.label} | Период: ${session.period.label}"
            )

            val skipEnglishPlatforms = !session.isBackgroundMode && session.languageMode == SearchLanguageMode.RUSSIAN_ONLY

            val rawFetched: List<DiscoveredMediaCandidate> = coroutineScope {
                val jobs = when (session.platform) {
                    HuntPlatform.YOUTUBE -> listOf(
                        async { fetchYoutubePage(session, ytEffectiveQuery, effectiveDurationMode) }
                    )
                    HuntPlatform.VK_CLIPS -> listOf(
                        async { fetchVkVideoPage(session, activeQueryBase) }
                    )
                    HuntPlatform.PIKABU -> listOf(
                        async { fetchPikabuVideoPage(session, cleanSubject) }
                    )
                    HuntPlatform.REDDIT -> listOf(
                        async { fetchRedditVideoPage(session, cleanSubject) }
                    )
                    HuntPlatform.RUTUBE -> listOf(
                        async { fetchRutubeVideoPage(session, cleanSubject) }
                    )
                    HuntPlatform.ALL -> buildList {
                        add(async { fetchYoutubePage(session, ytEffectiveQuery, effectiveDurationMode) })
                        if (!session.vkExhausted) add(async { fetchVkVideoPage(session, cleanSubject) })
                        if (!session.pikabuExhausted && !session.isBackgroundMode) {
                            add(async { fetchPikabuVideoPage(session, cleanSubject) })
                        }
                        if (!session.redditExhausted && (!skipEnglishPlatforms || session.isBackgroundMode)) {
                            add(async { fetchRedditVideoPage(session, cleanSubject) })
                        }
                        if (!session.rutubeExhausted) add(async { fetchRutubeVideoPage(session, cleanSubject) })
                    }
                }
                jobs.awaitAll().flatten()
            }

            val anchorTokens = extractMeaningfulAnchorTokens(cleanSubject)
            // Background fillers must be at least 30s..900s (no 10s meme clips, no 3-hour streams)
            val minDur = if (session.isBackgroundMode) 30 else effectiveDurationMode.minSec
            val maxDur = if (session.isBackgroundMode) 900 else effectiveDurationMode.maxSec

            val preFiltered = mutableListOf<DiscoveredMediaCandidate>()

            for (cand in rawFetched) {
                val normUrl = SearchBlacklistStore.normalizeUrlKey(cand.url)
                if (cand.id in session.seenKeys || normUrl in session.seenKeys) continue

                // 0. Skip anything already shown to the user in previous searches!
                if (SearchBlacklistStore.wasCandidateAlreadyShown(context, cand.id, cand.url, cand.title)) {
                    continue
                }

                // 1. Check Blacklist + Minus-Tags + Niche/Semantic Guardrails
                val fullText = "${cand.title} ${cand.descriptionSnippet}"
                if (!SearchBlacklistStore.isCandidateAllowed(
                        context = context,
                        id = cand.id,
                        url = cand.url,
                        author = cand.author,
                        fullText = fullText,
                        rawNegativeTags = session.negativeTags,
                        userQuery = cleanSubject,
                        isBackgroundFillerMode = session.isBackgroundMode
                    )
                ) {
                    continue
                }

                // 2. Strict Duration & True Shorts check (kills 12-15s pointless skits & horizontal non-shorts!)
                if (cand.durationSec < minDur || cand.durationSec > maxDur) {
                    continue
                }
                if (!session.isBackgroundMode &&
                    effectiveDurationMode == SearchDurationMode.SHORTS_ONLY &&
                    cand.platform == HuntPlatform.YOUTUBE &&
                    !cand.url.contains("/shorts/")
                ) {
                    continue
                }

                // 3. Strict Language Check (NO Reddit exemption! Requires >=55% Cyrillic letters in title)
                if (!session.isBackgroundMode) {
                    if (session.languageMode == SearchLanguageMode.RUSSIAN_ONLY) {
                        if (!isStrictlyRussianTitle(cand.title)) continue
                    } else if (session.languageMode == SearchLanguageMode.ENGLISH_FOR_DUB) {
                        val hasCyr = cand.title.any { it in 'а'..'я' || it in 'А'..'Я' || it == 'ё' || it == 'Ё' }
                        if (hasCyr) continue
                    }
                }

                // 4. Strict Freshness Check (NEVER allow 7-10 year old fossils when period is set!)
                if (!session.isBackgroundMode && session.period.daysBack != null) {
                    val estDays = estimateDaysFromAgeLabel(cand.publishedAgeLabel)
                    if (estDays != null && estDays > session.period.daysBack + 5) {
                        continue
                    }
                }

                // 5. Minimum Live Audience Floor for Source Videos (reject dead 200-view channels unless brand new today)
                if (!session.isBackgroundMode && session.sortOrder != ContentSortOrder.NEWEST) {
                    val minAudience = when (cand.platform) {
                        HuntPlatform.YOUTUBE -> 3_500L
                        HuntPlatform.VK_CLIPS -> 2_000L
                        HuntPlatform.RUTUBE -> 1_500L
                        HuntPlatform.PIKABU -> 1_500L
                        HuntPlatform.REDDIT -> 80L
                        HuntPlatform.ALL -> 2_000L
                    }
                    if (cand.viewOrScoreCount in 1L until minAudience) {
                        continue
                    }
                }

                // 6. Topic Anchor Relevance Check (especially strict in Background Mode so unrelated cartoons never pass)
                if (session.isBackgroundMode && anchorTokens.isNotEmpty()) {
                    val hay = "${cand.title} ${cand.author}".lowercase(Locale.ROOT)
                    val matched = anchorTokens.any { tok ->
                        val stem = if (tok.length >= 6) tok.dropLast(1) else tok
                        hay.contains(stem)
                    }
                    if (!matched) continue
                }

                // 7. Cross-Channel Fuzzy Title Deduplication (kills ТНТ vs Rutube duplicate of same standup!)
                if (session.acceptedTitles.any { prevTitle ->
                        SearchBlacklistStore.areTitlesNearDuplicates(prevTitle, cand.title)
                    }
                ) {
                    DiagnosticLogger.log("DEDUP", "✂️ Отсеян дубль с другого канала: «${cand.title.take(55)}» (${cand.author})")
                    continue
                }

                // 8. Cap max 2 items per channel in Source Batch mode so one channel doesn't flood results
                if (!session.isBackgroundMode) {
                    val authKey = SearchBlacklistStore.normalizeAuthorKey(cand.author)
                    if (authKey.isNotBlank() && authKey != "youtube shorts") {
                        val used = session.perAuthorCounts[authKey] ?: 0
                        if (used >= 2) continue
                        session.perAuthorCounts[authKey] = used + 1
                    }
                }

                session.seenKeys.add(cand.id)
                session.seenKeys.add(normUrl)
                session.acceptedTitles.add(cand.title)
                preFiltered.add(cand)
            }

            // If YouTube current continuation dried up or yielded 0 new items, rotate to next smart query variant!
            if (preFiltered.size < 3 && session.variantIndex + 1 < session.variantQueries.size) {
                session.variantIndex += 1
                session.ytShortContinuation = null
                session.ytMidContinuation = null
                session.ytExhausted = false
            }

            val ranked = rankCandidates(preFiltered, session.sortOrder)

            // 9. Stage 2 Brain: Visual Mid-Video Frame + Gemini Curation!
            val curated = if (session.isBackgroundMode) {
                verifyPureFillerBackgroundsByFrames(ranked, cleanSubject)
            } else if (session.enableAiCuration && ranked.isNotEmpty()) {
                curateSourcesForMassAudienceWithFrames(ranked, cleanSubject)
            } else {
                ranked
            }

            // Record delivered candidates in Seen-History so next search never repeats them!
            if (!session.isBackgroundMode && curated.isNotEmpty()) {
                SearchBlacklistStore.markCandidatesAsShown(context, curated)
            }

            DiagnosticLogger.log(
                "CRAWLER",
                "✅ Стр. #$currentPage: выдано ${curated.size} проверенных ссылок (сырых=${rawFetched.size}, после мат-фильтра=${ranked.size})"
            )
            curated
        }

    // =========================================================================
    // STAGE 2 BRAIN: MULTIMODAL GEMINI + MID-VIDEO FRAME INSPECTION
    // =========================================================================

    @Serializable
    private data class AiCuratedItemVerdict(
        val idx: Int = -1,
        val keep: Boolean = true,
        val score: Int = 70,
        val reason: String = ""
    )

    /**
     * Inspects mid-video frame strips (25% / 50% / 75% of the video without downloading MP4!)
     * + titles/authors via Gemini Multimodal to filter strictly for casual mass-audience Shorts viewers.
     */
    private suspend fun curateSourcesForMassAudienceWithFrames(
        candidates: List<DiscoveredMediaCandidate>,
        userTopic: String
    ): List<DiscoveredMediaCandidate> = withContext(Dispatchers.IO) {
        val apiKey = configStore.getGeminiApiKey().trim()
        if (apiKey.isBlank() || candidates.isEmpty()) return@withContext candidates

        val inspectSlice = candidates.take(14)
        val remainder = candidates.drop(14)

        // Fetch compact 3-frame mid-video strips in parallel for the top 8 visual candidates
        val framePairs = coroutineScope {
            inspectSlice.take(8).mapIndexed { idx, cand ->
                async {
                    val b64 = buildCandidateThreeFrameStripBase64(cand)?.first
                    idx to b64
                }
            }.awaitAll()
        }

        val inlineImages = mutableListOf<Pair<String, String>>()
        val imageMapNote = StringBuilder()
        for ((idx, b64) in framePairs) {
            if (!b64.isNullOrBlank()) {
                inlineImages.add("image/jpeg" to b64)
                imageMapNote.append("Изображение #${inlineImages.size} — это 3 кадра из середины видео кандидата idx=$idx (25%, 50%, 75%).\n")
            }
        }

        val listDesc = inspectSlice.mapIndexed { idx, c ->
            "idx=$idx | Площадка=${c.platform.badge} | Длит=${c.durationSec}с | Просм=${c.viewOrScoreCount} | Канал=«${c.author}» | Название=«${c.title}» | Описание=«${c.descriptionSnippet.take(90)}»"
        }.joinToString("\n")

        val prompt = """
            Ты — жёсткий шеф-редактор развлекательной ленты YouTube Shorts и VK Клипов.
            Запрос пользователя: «$userTopic».
            Целевая аудитория: ОБЫЧНЫЕ СРЕДНЕСТАТИСТИЧЕСКИЕ ЛЮДИ, которые вечером после работы/учёбы решили позалипать в телефон и посмеяться/отдохнуть.
            
            $imageMapNote
            Список кандидатов:
            $listDesc
            
            Твоя задача — отсеять шлак (keep=false) и оставить только потенциально залипательные ролики для широкой публики (keep=true):
            1. БЕЗЖАЛОСТНО ВЫКИДЫВАЙ (keep=false):
               - узкую профессиональную/корпоративную душнину (бизнес-стендап, маркетинг, IT-конференции, коучей, недвижимость, локальные междусобойчики);
               - тупые бессмысленные 15-секундные недо-скетчи без нормальной истории и панчлайна, дешёвый кликбейт;
               - статьи, текстовые слайдшоу, статичные картинки с аудио или ролики на иностранном языке;
               - древние заезженные баяны в шакальном качестве (посмотри на кадры из середины!).
            2. ОСТАВЛЯЙ (keep=true):
               - живые, понятные каждому темы: отношения, семья, бытовуха, неловкие ситуации, работа, яркие жизненные истории с харизматичной подачей и хорошей картинкой.
            
            Верни СТРОГО JSON массив объектов:
            [{"idx":0,"keep":true,"score":88,"reason":"Понятная жизненная тема про отношения, живая картинка"},{"idx":1,"keep":false,"score":15,"reason":"Нишевый бизнес-стендап"}]
        """.trimIndent()

        return@withContext try {
            val rawJson = GeminiModelResolver.generateTextOrJson(
                apiKey = apiKey,
                prompt = prompt,
                expectJson = true,
                inlineImages = inlineImages
            )
            if (rawJson.isNullOrBlank()) return@withContext candidates

            val verdicts = jsonFormat.decodeFromString<List<AiCuratedItemVerdict>>(rawJson)
                .associateBy { it.idx }

            val approved = mutableListOf<DiscoveredMediaCandidate>()
            inspectSlice.forEachIndexed { idx, cand ->
                val v = verdicts[idx]
                if (v == null || v.keep) {
                    val note = v?.reason?.takeIf { it.isNotBlank() }?.let { "🧠 ЦА-фильтр: $it" } ?: cand.aiWhatsInside
                    approved.add(cand.copy(aiWhatsInside = note, conversionScore = v?.score ?: 75))
                } else {
                    DiagnosticLogger.log("AI-CURATOR", "🗑 Gemini отбраковал [${cand.title.take(45)}]: ${v.reason}")
                }
            }
            // If Gemini rejected literally everything on a weird query, keep top 2 highest-scored as safety net
            val finalSlice = if (approved.isNotEmpty()) {
                approved.sortedByDescending { it.conversionScore }
            } else {
                inspectSlice.sortedByDescending { verdicts[inspectSlice.indexOf(it)]?.score ?: 0 }.take(2)
            }
            finalSlice + remainder
        } catch (e: Exception) {
            DiagnosticLogger.log("AI-CURATOR", "Предупреждение AI-отбора: ${e.message?.take(80)}")
            candidates
        }
    }

    /**
     * Frame-by-Frame / 3-Snapshot Background Filler Inspector:
     * Rejects cartoons, animations, tutorials, guides, menus, facecams, and story videos!
     * Passes ONLY monotonous pure gameplay / ASMR filler with zero semantic distraction.
     */
    private suspend fun verifyPureFillerBackgroundsByFrames(
        candidates: List<DiscoveredMediaCandidate>,
        userQuery: String
    ): List<DiscoveredMediaCandidate> = withContext(Dispatchers.IO) {
        if (candidates.isEmpty()) return@withContext emptyList()
        val batch = candidates.take(10)

        // Step 1: Parallel 3-frame download (25%, 50%, 75%) + ML Kit Facecam/Character rejection + Frame Continuity check!
        data class FrameCheckedBg(
            val candidate: DiscoveredMediaCandidate,
            val stripBase64: String?
        )

        val localPassed = coroutineScope {
            batch.map { cand ->
                async {
                    val stripAndFrames = buildCandidateThreeFrameStripBase64(cand)
                    if (stripAndFrames != null) {
                        val (b64Strip, bitmaps) = stripAndFrames
                        // 1A. Reject if any mid-video frame has a human/cartoon face (streamer webcam, reaction, cutscene dialogue!)
                        for (bmp in bitmaps) {
                            try {
                                val faces = quickFrameDetector.process(InputImage.fromBitmap(bmp, 0)).await()
                                val bigFace = faces.any { f ->
                                    val area = f.boundingBox.width().toFloat() * f.boundingBox.height().toFloat()
                                    area >= (bmp.width * bmp.height * 0.012f)
                                }
                                if (bigFace) {
                                    DiagnosticLogger.log("BG-VISION", "🚫 Отбракован фон (лицо/вебка в кадре): «${cand.title.take(45)}»")
                                    bitmaps.forEach { it.recycle() }
                                    return@async null
                                }
                            } catch (_: Exception) {
                            }
                        }

                        // 1B. Visual continuity check across 25% / 50% / 75%: reject abrupt menu/black-screen/tutorial jumps!
                        if (bitmaps.size >= 3 && hasAbruptMenuOrSlideJump(bitmaps)) {
                            DiagnosticLogger.log("BG-VISION", "🚫 Отбракован фон (резкие скачки меню/заставок): «${cand.title.take(45)}»")
                            bitmaps.forEach { it.recycle() }
                            return@async null
                        }
                        bitmaps.forEach { it.recycle() }
                        FrameCheckedBg(cand, b64Strip)
                    } else {
                        FrameCheckedBg(cand, null)
                    }
                }
            }.awaitAll().filterNotNull()
        }

        if (localPassed.isEmpty()) return@withContext emptyList()

        // Step 2: Gemini Vision Multimodal Frame Check (rejects cartoons, HUD tutorials, arrows, burned-in text, story scenes!)
        val apiKey = configStore.getGeminiApiKey().trim()
        if (apiKey.isBlank()) {
            return@withContext localPassed.map {
                it.candidate.copy(aiWhatsInside = "✅ Проверены кадры: без вебок и резких заставок")
            }
        }

        val inlineImages = mutableListOf<Pair<String, String>>()
        val imgNotes = StringBuilder()
        localPassed.forEachIndexed { idx, item ->
            if (!item.stripBase64.isNullOrBlank() && inlineImages.size < 8) {
                inlineImages.add("image/jpeg" to item.stripBase64)
                imgNotes.append("Изображение #${inlineImages.size} — 3 кадра (25%, 50%, 75%) видео idx=$idx.\n")
            }
        }

        val listDesc = localPassed.mapIndexed { idx, item ->
            val c = item.candidate
            "idx=$idx | Длит=${c.durationSec}с | Канал=«${c.author}» | Название=«${c.title}»"
        }.joinToString("\n")

        val prompt = """
            Ты — визуальный ОТК-фильтр фоновых видео-наполнителей (brainrot filler) для нижней части вертикальных Shorts.
            Тема запроса: «$userQuery».
            $imgNotes
            Кандидаты:
            $listDesc
            
            Посмотри ВНИМАТЕЛЬНО на 3 кадра из середины каждого ролика и его название!
            Нам нужен ТОЛЬКО ОДНОТОННЫЙ, НЕПРЕРЫВНЫЙ, ГИПНОТИЧЕСКИЙ НАПОЛНИТЕЛЬ БЕЗ СМЫСЛОВОЙ НАГРУЗКИ (например: чистый непрерывный бег Subway Surfers, чистый CS surf/bhop, чистый GTA спуск по рампе, чистое монотонное резание мыла/песка крупным планом).
            
            БЕЗЖАЛОСТНО ОТБРАКУЙ (keep=false), если видишь хотя бы один признак:
            - мультик, мультсериал, 2D-анимация, аниме, сюжетная катсцена или персонажи с диалогами;
            - гайд, туториал, обучение, меню настроек, инвентарь, стрелочки, таблицы или объяснения;
            - крупные вшитые субтитры/мемы поверх видео, лицо стримера или нарезка разных игр;
            - картинка несёт смысловую нагрузку и отвлекает от прослушивания основного видео сверху.
            
            Верни СТРОГО JSON массив:
            [{"idx":0,"keep":true,"score":92,"reason":"Чистый монотонный геймплей без текста и сюжета"},{"idx":1,"keep":false,"score":10,"reason":"Мультик / сюжетная анимация"}]
        """.trimIndent()

        return@withContext try {
            val rawJson = GeminiModelResolver.generateTextOrJson(
                apiKey = apiKey,
                prompt = prompt,
                expectJson = true,
                inlineImages = inlineImages
            )
            if (rawJson.isNullOrBlank()) {
                return@withContext localPassed.map { it.candidate }
            }

            val verdicts = jsonFormat.decodeFromString<List<AiCuratedItemVerdict>>(rawJson)
                .associateBy { it.idx }

            val cleanFillers = mutableListOf<DiscoveredMediaCandidate>()
            localPassed.forEachIndexed { idx, item ->
                val v = verdicts[idx]
                if (v != null && v.keep) {
                    cleanFillers.add(
                        item.candidate.copy(
                            aiWhatsInside = "🟢 Чистый наполнитель: ${v.reason.ifBlank { "однотонный видеоряд без сюжета" }}",
                            conversionScore = v.score
                        )
                    )
                } else if (v == null) {
                    cleanFillers.add(item.candidate)
                } else {
                    DiagnosticLogger.log("BG-VISION", "🗑 Gemini Vision отсеял мусорный фон [${item.candidate.title.take(42)}]: ${v.reason}")
                }
            }
            cleanFillers
        } catch (e: Exception) {
            DiagnosticLogger.log("BG-VISION", "Предупреждение Gemini Vision фонов: ${e.message?.take(80)}")
            localPassed.map { it.candidate }
        }
    }

    /**
     * Downloads 3 lightweight mid-video JPEGs (`hq1.jpg` 25%, `hq2.jpg` 50%, `hq3.jpg` 75% on YouTube,
     * or main thumbnail on other platforms) and stitches them into a single compact `480x120` JPEG strip
     * for instant Gemini Vision inspection (zero MP4 video download!).
     */
    private suspend fun buildCandidateThreeFrameStripBase64(
        cand: DiscoveredMediaCandidate
    ): Pair<String, List<Bitmap>>? = withContext(Dispatchers.IO) {
        try {
            val ytId = extractYoutubeId(cand.url)
            val urls = if (ytId != null) {
                listOf(
                    "https://i.ytimg.com/vi/$ytId/hq1.jpg",
                    "https://i.ytimg.com/vi/$ytId/hq2.jpg",
                    "https://i.ytimg.com/vi/$ytId/hq3.jpg"
                )
            } else if (cand.thumbnailUrl.startsWith("http")) {
                listOf(cand.thumbnailUrl)
            } else {
                return@withContext null
            }

            val bitmaps = coroutineScope {
                urls.map { u ->
                    async {
                        try {
                            val resp = httpClient.get(u) { header(HttpHeaders.UserAgent, DESKTOP_UA) }
                            if (!resp.status.isSuccess()) return@async null
                            val bytes = resp.readBytes()
                            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        } catch (_: Exception) {
                            null
                        }
                    }
                }.awaitAll().filterNotNull()
            }

            if (bitmaps.isEmpty()) return@withContext null

            val tileW = 160
            val tileH = 110
            val stripBmp = Bitmap.createBitmap(tileW * bitmaps.size, tileH, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(stripBmp)
            bitmaps.forEachIndexed { i, bmp ->
                val dst = Rect(i * tileW, 0, (i + 1) * tileW, tileH)
                canvas.drawBitmap(bmp, null, dst, null)
            }
            val bos = ByteArrayOutputStream()
            stripBmp.compress(Bitmap.CompressFormat.JPEG, 72, bos)
            stripBmp.recycle()
            val b64 = Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP)
            b64 to bitmaps
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Checks if 3 mid-video frames jump wildly between dark/white menus/title-cards and gameplay.
     */
    private fun hasAbruptMenuOrSlideJump(bitmaps: List<Bitmap>): Boolean {
        val meanLums = bitmaps.map { bmp ->
            var sum = 0L
            var count = 0
            val stepX = (bmp.width / 12).coerceAtLeast(1)
            val stepY = (bmp.height / 10).coerceAtLeast(1)
            var y = stepY
            while (y < bmp.height - stepY) {
                var x = stepX
                while (x < bmp.width - stepX) {
                    val c = bmp.getPixel(x, y)
                    val r = (c shr 16) and 0xFF
                    val g = (c shr 8) and 0xFF
                    val b = c and 0xFF
                    sum += (r * 3 + g * 6 + b) / 10
                    count++
                    x += stepX
                }
                y += stepY
            }
            if (count > 0) (sum / count).toInt() else 128
        }
        // Pitch-black title card (<14) or blinding white slide (>242) in mid-video = not continuous gameplay!
        if (meanLums.any { it < 14 || it > 242 }) return true
        val maxDiff = (meanLums.maxOrNull() ?: 128) - (meanLums.minOrNull() ?: 128)
        return maxDiff > 115
    }

    /**
     * Batch search entry point used by TelegramBotWorker (NEVER resets period to ALL_TIME!).
     */
    suspend fun huntByTags(
        queryTags: String,
        platform: HuntPlatform = HuntPlatform.ALL,
        sortOrder: ContentSortOrder = ContentSortOrder.POPULAR,
        period: SearchTimePeriod = SearchTimePeriod.MONTH,
        durationMode: SearchDurationMode = SearchDurationMode.ANY_UP_TO_6M,
        languageMode: SearchLanguageMode = SearchLanguageMode.RUSSIAN_ONLY,
        limitCount: Int = 10,
        negativeTags: String = SearchBlacklistStore.sourceNegativeTags.value
    ): List<DiscoveredMediaCandidate> = withContext(Dispatchers.IO) {
        val session = MultiPlatformCrawlSession(
            rawQuery = queryTags,
            negativeTags = negativeTags,
            platform = platform,
            sortOrder = sortOrder,
            period = period,
            durationMode = durationMode,
            languageMode = languageMode,
            isBackgroundMode = false,
            enableAiCuration = true
        )
        val collected = mutableListOf<DiscoveredMediaCandidate>()
        var attempts = 0
        while (collected.size < limitCount && session.hasMorePages() && attempts < 5) {
            attempts++
            val batch = crawlNextPage(session)
            for (c in batch) {
                if (collected.none { SearchBlacklistStore.areTitlesNearDuplicates(it.title, c.title) }) {
                    collected.add(c)
                }
            }
            if (batch.isEmpty() && attempts >= 3) break
        }
        collected.take(limitCount)
    }

    // =========================================================================
    // 1. YOUTUBE INNERTUBE CRAWLER
    // =========================================================================

    private suspend fun fetchYoutubePage(
        session: MultiPlatformCrawlSession,
        effectiveQuery: String,
        effectiveDurationMode: SearchDurationMode
    ): List<DiscoveredMediaCandidate> {
        val results = mutableListOf<DiscoveredMediaCandidate>()
        try {
            val sortCode = when (session.sortOrder) {
                ContentSortOrder.POPULAR -> 3
                ContentSortOrder.NEWEST -> 2
                ContentSortOrder.VIRAL_VELOCITY -> 3
                ContentSortOrder.RELEVANCE -> 0
            }

            val needFreshQueryCall = session.ytShortContinuation == null && session.ytMidContinuation == null

            if (needFreshQueryCall) {
                if (!session.isBackgroundMode) {
                    // Stream A: < 4 minutes (Shorts + crisp monologues)
                    val spUnder4m = buildYoutubeSearchSpBase64(
                        sortCode = sortCode,
                        period = session.period,
                        durationCode = 1
                    )
                    val pairShort = executeYoutubeInnertubeSearch(query = effectiveQuery, spParams = spUnder4m, continuation = null)
                    results.addAll(pairShort.first)
                    session.ytShortContinuation = pairShort.second
                }

                // Stream B: 4–20 minutes (ALWAYS used for Background Fillers so we get continuous 4–15m gameplay, and for non-Shorts sources)
                if (session.isBackgroundMode || effectiveDurationMode != SearchDurationMode.SHORTS_ONLY) {
                    val spMid = buildYoutubeSearchSpBase64(
                        sortCode = if (session.isBackgroundMode) 0 else sortCode,
                        period = if (session.isBackgroundMode) SearchTimePeriod.ALL_TIME else session.period,
                        durationCode = 3 // 4–20 minutes continuous!
                    )
                    val pairMid = executeYoutubeInnertubeSearch(query = effectiveQuery, spParams = spMid, continuation = null)
                    results.addAll(pairMid.first)
                    session.ytMidContinuation = pairMid.second
                }

                if (session.ytShortContinuation == null && session.ytMidContinuation == null) {
                    session.ytExhausted = true
                }
            } else {
                val tok1 = session.ytShortContinuation
                val tok2 = session.ytMidContinuation
                if (tok1 != null) {
                    val p1 = executeYoutubeInnertubeSearch(query = null, spParams = null, continuation = tok1)
                    results.addAll(p1.first)
                    session.ytShortContinuation = p1.second
                }
                if (tok2 != null && (session.isBackgroundMode || effectiveDurationMode != SearchDurationMode.SHORTS_ONLY)) {
                    val p2 = executeYoutubeInnertubeSearch(query = null, spParams = null, continuation = tok2)
                    results.addAll(p2.first)
                    session.ytMidContinuation = p2.second
                }
                if (session.ytShortContinuation == null && session.ytMidContinuation == null) {
                    session.ytExhausted = true
                }
            }
        } catch (e: Exception) {
            DiagnosticLogger.logError("YT-SEARCH", "Сбой страницы YouTube Innertube", e)
        }
        return results
    }

    private fun buildYoutubeSearchSpBase64(
        sortCode: Int,
        period: SearchTimePeriod,
        durationCode: Int?
    ): String {
        val filterSub = ByteArrayOutputStream()
        val uploadCode = when (period) {
            SearchTimePeriod.DAY -> 2
            SearchTimePeriod.WEEK -> 3
            SearchTimePeriod.MONTH -> 4
            SearchTimePeriod.YEAR -> 5
            SearchTimePeriod.ALL_TIME -> null
        }
        if (uploadCode != null) {
            filterSub.write(0x08)
            filterSub.write(uploadCode)
        }
        filterSub.write(0x10)
        filterSub.write(1) // Videos only
        if (durationCode != null) {
            filterSub.write(0x18)
            filterSub.write(durationCode)
        }

        val outer = ByteArrayOutputStream()
        outer.write(0x08)
        outer.write(sortCode)
        val subBytes = filterSub.toByteArray()
        if (subBytes.isNotEmpty()) {
            outer.write(0x12)
            outer.write(subBytes.size)
            outer.write(subBytes)
        }
        return Base64.encodeToString(outer.toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP)
    }

    private suspend fun executeYoutubeInnertubeSearch(
        query: String?,
        spParams: String?,
        continuation: String?
    ): Pair<List<DiscoveredMediaCandidate>, String?> {
        val bodyObj = buildJsonObject {
            putJsonObject("context") {
                putJsonObject("client") {
                    put("clientName", "WEB")
                    put("clientVersion", "2.20250610.01.00")
                    put("hl", "ru")
                    put("gl", "RU")
                }
            }
            if (!continuation.isNullOrBlank()) {
                put("continuation", continuation)
            } else if (!query.isNullOrBlank()) {
                put("query", query)
                if (!spParams.isNullOrBlank()) {
                    put("params", spParams)
                }
            }
        }

        val resp = httpClient.post("https://www.youtube.com/youtubei/v1/search?prettyPrint=false") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.UserAgent, DESKTOP_UA)
            header("X-YouTube-Client-Name", "1")
            header("X-YouTube-Client-Version", "2.20250610.01.00")
            setBody(bodyObj.toString())
        }
        if (!resp.status.isSuccess()) return emptyList<DiscoveredMediaCandidate>() to null

        val root = jsonFormat.parseToJsonElement(resp.bodyAsText())
        val found = mutableListOf<DiscoveredMediaCandidate>()
        var nextContinuation: String? = null

        fun walkJson(elem: JsonElement) {
            when (elem) {
                is JsonObject -> {
                    val shelfObj = elem["shelfRenderer"]?.jsonObject
                    if (shelfObj != null) {
                        val shelfTitle = extractYtText(shelfObj["title"]).lowercase(Locale.ROOT)
                        if (UNRELATED_SHELF_TITLES.any { shelfTitle.contains(it) }) {
                            return
                        }
                    }

                    elem["continuationItemRenderer"]?.jsonObject?.let { cir ->
                        val tok = cir["continuationEndpoint"]?.jsonObject
                            ?.get("continuationCommand")?.jsonObject
                            ?.get("token")?.jsonPrimitive?.contentOrNull
                        if (!tok.isNullOrBlank()) {
                            nextContinuation = tok
                        }
                    }

                    elem["videoRenderer"]?.jsonObject?.let { vr ->
                        parseYtVideoRenderer(vr)?.let { found.add(it) }
                        return
                    }
                    elem["compactVideoRenderer"]?.jsonObject?.let { vr ->
                        parseYtVideoRenderer(vr)?.let { found.add(it) }
                        return
                    }
                    elem["videoWithContextRenderer"]?.jsonObject?.let { vr ->
                        parseYtVideoRenderer(vr)?.let { found.add(it) }
                        return
                    }
                    elem["reelItemRenderer"]?.jsonObject?.let { rr ->
                        parseYtReelRenderer(rr)?.let { found.add(it) }
                        return
                    }
                    elem["shortsLockupViewModel"]?.jsonObject?.let { slm ->
                        parseYtShortsLockup(slm)?.let { found.add(it) }
                        return
                    }
                    elem["lockupViewModel"]?.jsonObject?.let { lvm ->
                        parseYtLockupViewModel(lvm)?.let { found.add(it) }
                        return
                    }

                    for ((_, child) in elem) {
                        walkJson(child)
                    }
                }
                is JsonArray -> {
                    for (item in elem) walkJson(item)
                }
                else -> {}
            }
        }

        walkJson(root)
        return found to nextContinuation
    }

    private fun parseYtVideoRenderer(vr: JsonObject): DiscoveredMediaCandidate? {
        val videoId = vr["videoId"]?.jsonPrimitive?.contentOrNull ?: return null
        if (videoId.length != 11) return null
        val title = extractYtText(vr["title"]).ifBlank { extractYtText(vr["headline"]) }
        if (title.isBlank()) return null

        val durText = extractYtText(vr["lengthText"]).ifBlank {
            vr["thumbnailOverlays"]?.jsonArray?.firstNotNullOfOrNull { ov ->
                ov.jsonObject["thumbnailOverlayTimeStatusRenderer"]?.jsonObject?.get("text")?.let { extractYtText(it) }
            }.orEmpty()
        }
        if (durText.isBlank()) return null
        val durSec = parseColonDurationSec(durText)
        if (durSec <= 0) return null

        val author = extractYtText(vr["ownerText"])
            .ifBlank { extractYtText(vr["longBylineText"]) }
            .ifBlank { extractYtText(vr["shortBylineText"]) }
            .ifBlank { "YouTube Канал" }

        val viewsRaw = extractYtText(vr["viewCountText"]).ifBlank { extractYtText(vr["shortViewCountText"]) }
        val views = parseHumanViewsCount(viewsRaw)
        val publishedAge = extractYtText(vr["publishedTimeText"])
        val snippet = extractYtText(vr["detailedMetadataSnippets"]?.jsonArray?.firstOrNull()?.jsonObject?.get("snippetText"))
            .ifBlank { extractYtText(vr["descriptionSnippet"]) }

        // Check navigationEndpoint for explicit /shorts/ path
        val navStr = vr["navigationEndpoint"]?.toString().orEmpty()
        val isShort = navStr.contains("reelWatchEndpoint") || navStr.contains("/shorts/") || durSec <= 62
        val url = if (isShort) "https://www.youtube.com/shorts/$videoId" else "https://www.youtube.com/watch?v=$videoId"

        return DiscoveredMediaCandidate(
            id = "yt_$videoId",
            url = url,
            title = title,
            author = author,
            platform = HuntPlatform.YOUTUBE,
            durationSec = durSec,
            viewOrScoreCount = views,
            thumbnailUrl = "https://i.ytimg.com/vi/$videoId/hqdefault.jpg",
            publishedAgeLabel = publishedAge,
            descriptionSnippet = snippet,
            cloutStatusLabel = formatCloutBadge(views)
        )
    }

    private fun parseYtLockupViewModel(lvm: JsonObject): DiscoveredMediaCandidate? {
        val contentType = lvm["contentType"]?.jsonPrimitive?.contentOrNull ?: ""
        if (contentType.isNotBlank() && !contentType.contains("VIDEO", ignoreCase = true)) return null

        val videoId = lvm["contentId"]?.jsonPrimitive?.contentOrNull ?: return null
        if (videoId.length != 11) return null

        val metaVm = lvm["metadata"]?.jsonObject?.get("lockupMetadataViewModel")?.jsonObject
        val title = metaVm?.get("title")?.jsonObject?.get("content")?.jsonPrimitive?.contentOrNull ?: return null

        var durText = ""
        val overlays = lvm["contentImage"]?.jsonObject
            ?.get("thumbnailViewModel")?.jsonObject
            ?.get("overlays")?.jsonArray
        if (overlays != null) {
            for (ov in overlays) {
                val badges = ov.jsonObject["thumbnailOverlayBadgeViewModel"]?.jsonObject?.get("thumbnailBadges")?.jsonArray
                if (badges != null) {
                    for (b in badges) {
                        val txt = b.jsonObject["thumbnailBadgeViewModel"]?.jsonObject?.get("text")?.jsonPrimitive?.contentOrNull
                        if (!txt.isNullOrBlank() && txt.contains(":")) {
                            durText = txt
                            break
                        }
                    }
                }
            }
        }
        val durSec = if (durText.isNotBlank()) parseColonDurationSec(durText) else 45
        if (durSec <= 0) return null

        val rows = metaVm["metadata"]?.jsonObject
            ?.get("contentMetadataViewModel")?.jsonObject
            ?.get("metadataRows")?.jsonArray
        var author = "YouTube"
        var views = 0L
        var age = ""
        if (rows != null) {
            rows.forEachIndexed { rIdx, rElem ->
                val parts = rElem.jsonObject["metadataParts"]?.jsonArray ?: return@forEachIndexed
                parts.forEach { pElem ->
                    val txt = pElem.jsonObject["text"]?.jsonObject?.get("content")?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                    if (txt.isBlank()) return@forEach
                    when {
                        rIdx == 0 && author == "YouTube" && !txt.contains("просм", true) && !txt.contains("view", true) -> author = txt
                        txt.contains("просм", true) || txt.contains("view", true) -> views = parseHumanViewsCount(txt)
                        txt.contains("назад", true) || txt.contains("ago", true) -> age = txt
                    }
                }
            }
        }

        val url = if (durSec <= 62) "https://www.youtube.com/shorts/$videoId" else "https://www.youtube.com/watch?v=$videoId"
        return DiscoveredMediaCandidate(
            id = "yt_$videoId",
            url = url,
            title = title,
            author = author,
            platform = HuntPlatform.YOUTUBE,
            durationSec = durSec,
            viewOrScoreCount = views,
            thumbnailUrl = "https://i.ytimg.com/vi/$videoId/hqdefault.jpg",
            publishedAgeLabel = age,
            cloutStatusLabel = formatCloutBadge(views)
        )
    }

    private fun parseYtReelRenderer(rr: JsonObject): DiscoveredMediaCandidate? {
        val videoId = rr["videoId"]?.jsonPrimitive?.contentOrNull ?: return null
        val title = extractYtText(rr["headline"]).ifBlank { return null }
        val views = parseHumanViewsCount(extractYtText(rr["viewCountText"]))
        return DiscoveredMediaCandidate(
            id = "yt_$videoId",
            url = "https://www.youtube.com/shorts/$videoId",
            title = title,
            author = "YouTube Shorts",
            platform = HuntPlatform.YOUTUBE,
            durationSec = 42,
            viewOrScoreCount = views,
            thumbnailUrl = "https://i.ytimg.com/vi/$videoId/hqdefault.jpg",
            cloutStatusLabel = formatCloutBadge(views)
        )
    }

    private fun parseYtShortsLockup(slm: JsonObject): DiscoveredMediaCandidate? {
        val rawStr = slm.toString()
        val videoId = Regex(""""videoId"\s*:\s*"([a-zA-Z0-9_-]{11})"""").find(rawStr)?.groupValues?.get(1) ?: return null
        val title = slm["overlayMetadata"]?.jsonObject
            ?.get("primaryText")?.jsonObject
            ?.get("content")?.jsonPrimitive?.contentOrNull ?: return null
        val viewsTxt = slm["overlayMetadata"]?.jsonObject
            ?.get("secondaryText")?.jsonObject
            ?.get("content")?.jsonPrimitive?.contentOrNull.orEmpty()
        val views = parseHumanViewsCount(viewsTxt)
        return DiscoveredMediaCandidate(
            id = "yt_$videoId",
            url = "https://www.youtube.com/shorts/$videoId",
            title = title,
            author = "YouTube Shorts",
            platform = HuntPlatform.YOUTUBE,
            durationSec = 42,
            viewOrScoreCount = views,
            thumbnailUrl = "https://i.ytimg.com/vi/$videoId/hqdefault.jpg",
            cloutStatusLabel = formatCloutBadge(views)
        )
    }

    private fun extractYtText(elem: JsonElement?): String {
        if (elem == null || elem !is JsonObject) return ""
        elem["simpleText"]?.jsonPrimitive?.contentOrNull?.let { return it }
        elem["content"]?.jsonPrimitive?.contentOrNull?.let { return it }
        val runs = elem["runs"]?.jsonArray ?: return ""
        return runs.joinToString("") { it.jsonObject["text"]?.jsonPrimitive?.contentOrNull.orEmpty() }
    }

    // =========================================================================
    // 2. VK КЛИПЫ / VK ВИДЕО
    // =========================================================================

    private suspend fun fetchVkVideoPage(
        session: MultiPlatformCrawlSession,
        query: String
    ): List<DiscoveredMediaCandidate> {
        val out = mutableListOf<DiscoveredMediaCandidate>()
        try {
            val offset = session.vkOffset
            val encoded = query.encodeURLParameter()
            val urlsToTry = listOf(
                "https://m.vk.com/video?q=$encoded&offset=$offset",
                "https://vkvideo.ru/?q=$encoded&offset=$offset"
            )

            for (targetUrl in urlsToTry) {
                try {
                    val resp = httpClient.get(targetUrl) {
                        header(HttpHeaders.UserAgent, MOBILE_UA)
                        header(HttpHeaders.AcceptLanguage, "ru-RU,ru;q=0.9")
                    }
                    if (!resp.status.isSuccess()) continue
                    val html = resp.bodyAsText()
                    val parsed = parseVkHtmlCards(html)
                    if (parsed.isNotEmpty()) {
                        out.addAll(parsed)
                        break
                    }
                } catch (_: Exception) {
                }
            }

            if (out.isEmpty()) {
                session.vkExhausted = true
            } else {
                session.vkOffset += 20
            }
        } catch (e: Exception) {
            DiagnosticLogger.logError("VK-SEARCH", "Сбой страницы VK Видео", e)
            session.vkExhausted = true
        }
        return out
    }

    private fun parseVkHtmlCards(html: String): List<DiscoveredMediaCandidate> {
        val list = mutableListOf<DiscoveredMediaCandidate>()
        val seenVkIds = HashSet<String>()
        val linkRegex = Regex("""/(clip|video)(-?\d+_\d+)""")
        for (m in linkRegex.findAll(html)) {
            val kind = m.groupValues[1]
            val oidVid = m.groupValues[2]
            if (!seenVkIds.add(oidVid)) continue

            val windowStart = (m.range.first - 600).coerceAtLeast(0)
            val windowEnd = (m.range.last + 1200).coerceAtMost(html.length)
            val chunk = html.substring(windowStart, windowEnd)

            val title = Regex("""(?:title|aria-label|alt)="([^"]{6,160})"""")
                .find(chunk)?.groupValues?.get(1)
                ?.let { decodeHtmlEntities(it) }
                ?: Regex("""class="[^"]*VideoItem__title[^"]*"[^>]*>([^<]{5,160})<""")
                    .find(chunk)?.groupValues?.get(1)?.let { decodeHtmlEntities(it) }
                ?: continue

            if (title.equals("VK Видео", true) || title.equals("Видео", true)) continue

            val author = Regex("""class="[^"]*(?:author|subtitle|owner)[^"]*"[^>]*>([^<]{2,60})<""", RegexOption.IGNORE_CASE)
                .find(chunk)?.groupValues?.get(1)?.let { decodeHtmlEntities(it).trim() }
                ?: "VK Автор"

            val durMatch = Regex(""">(\d{1,2}:\d{2})<""").find(chunk)?.groupValues?.get(1)
            val durSec = if (durMatch != null) parseColonDurationSec(durMatch) else 45

            val thumb = Regex("""https://[a-zA-Z0-9._/-]+\.userapi\.com/[^"'\s>]+""")
                .find(chunk)?.value?.replace("&amp;", "&").orEmpty()

            val viewsMatch = Regex("""([\d\s.,]+[KКMМтысмил]*)\s*(?:просм|views)""", RegexOption.IGNORE_CASE)
                .find(chunk)?.groupValues?.get(1)
            val views = if (viewsMatch != null) parseHumanViewsCount(viewsMatch) else 0L

            val fullUrl = "https://vk.com/${kind}${oidVid}"
            list.add(
                DiscoveredMediaCandidate(
                    id = "vk_$oidVid",
                    url = fullUrl,
                    title = title,
                    author = author,
                    platform = HuntPlatform.VK_CLIPS,
                    durationSec = durSec,
                    viewOrScoreCount = views,
                    thumbnailUrl = thumb,
                    cloutStatusLabel = if (views > 0) formatCloutBadge(views) else "💙 VK Клип"
                )
            )
            if (list.size >= 24) break
        }
        return list
    }

    // =========================================================================
    // 3. PIKABU ВИДЕО (Strictly VIDEO posts only — ZERO text articles!)
    // =========================================================================

    private suspend fun fetchPikabuVideoPage(
        session: MultiPlatformCrawlSession,
        query: String
    ): List<DiscoveredMediaCandidate> {
        val out = mutableListOf<DiscoveredMediaCandidate>()
        try {
            val page = session.pikabuPage
            val encoded = query.encodeURLParameter()
            // Explicitly filter by tag `Видео` on Pikabu so text stories are excluded server-side too!
            val url = "https://pikabu.ru/tag/%D0%92%D0%B8%D0%B4%D0%B5%D0%BE?q=$encoded&st=3&page=$page"
            val resp = httpClient.get(url) {
                header(HttpHeaders.UserAgent, DESKTOP_UA)
                header(HttpHeaders.AcceptLanguage, "ru-RU,ru;q=0.9")
            }
            if (!resp.status.isSuccess()) {
                session.pikabuExhausted = true
                return emptyList()
            }

            val bytes = resp.bodyAsText(Charset.forName("windows-1251")).let { winText ->
                val cyrCountWin = winText.count { it in 'а'..'я' || it in 'А'..'Я' }
                if (cyrCountWin > 20) winText else resp.bodyAsText(Charsets.UTF_8)
            }

            val parsed = parsePikabuStoriesHtml(bytes)
            if (parsed.isEmpty()) {
                session.pikabuExhausted = true
            } else {
                session.pikabuPage += 1
                out.addAll(parsed)
            }
        } catch (e: Exception) {
            DiagnosticLogger.logError("PIKABU-SEARCH", "Сбой страницы Pikabu", e)
            session.pikabuExhausted = true
        }
        return out
    }

    private fun parsePikabuStoriesHtml(html: String): List<DiscoveredMediaCandidate> {
        val results = mutableListOf<DiscoveredMediaCandidate>()
        val articleBlocks = html.split("<article")
        for (idx in 1 until articleBlocks.size) {
            val block = articleBlocks[idx]
            val storyUrl = Regex("""https://pikabu\.ru/story/[a-zA-Z0-9_-]+_\d+""")
                .find(block)?.value ?: continue
            val storyId = storyUrl.substringAfterLast("_")

            // STRICT REAL VIDEO PLAYER CHECK: never let text articles pass!
            val hasRealVideoBlock = block.contains("story-block_type_video", ignoreCase = true) ||
                block.contains("data-type=\"video\"", ignoreCase = true) ||
                block.contains("data-source-url", ignoreCase = true) ||
                block.contains("player__player", ignoreCase = true) ||
                block.contains(".mp4", ignoreCase = true) ||
                block.contains("data-webm", ignoreCase = true)

            if (!hasRealVideoBlock) continue

            val title = Regex("""class="story__title-link[^"]*"[^>]*>(.*?)</a>""", RegexOption.DOT_MATCHES_ALL)
                .find(block)?.groupValues?.get(1)
                ?.replace(Regex("""<[^>]+>"""), "")
                ?.let { decodeHtmlEntities(it).trim() }
                ?: continue
            if (title.isBlank()) continue

            val author = Regex("""data-name="([^"]+)"""").find(block)?.groupValues?.get(1)
                ?: Regex("""class="user__nick[^"]*"[^>]*>([^<]+)<""").find(block)?.groupValues?.get(1)?.trim()
                ?: "Pikabu"

            val rating = Regex("""data-rating="(-?\d+)"""").find(block)?.groupValues?.get(1)?.toLongOrNull()
                ?: 0L
            if (rating < 15L) continue

            val durSec = Regex("""data-duration="(\d+)"""").find(block)?.groupValues?.get(1)?.toIntOrNull()
                ?: 45

            val thumb = Regex("""data-thumb="([^"]+)"""").find(block)?.groupValues?.get(1)
                ?: Regex("""poster="([^"]+)"""").find(block)?.groupValues?.get(1)
                ?: ""

            results.add(
                DiscoveredMediaCandidate(
                    id = "pkb_$storyId",
                    url = storyUrl,
                    title = title,
                    author = author,
                    platform = HuntPlatform.PIKABU,
                    durationSec = durSec,
                    viewOrScoreCount = rating.coerceAtLeast(0L) * 100L,
                    thumbnailUrl = thumb,
                    cloutStatusLabel = "🧁 Рейтинг Пикабу: +$rating"
                )
            )
        }
        return results
    }

    // =========================================================================
    // 4. REDDIT VIDEO
    // =========================================================================

    private suspend fun fetchRedditVideoPage(
        session: MultiPlatformCrawlSession,
        query: String
    ): List<DiscoveredMediaCandidate> {
        val out = mutableListOf<DiscoveredMediaCandidate>()
        try {
            val sortStr = when (session.sortOrder) {
                ContentSortOrder.POPULAR -> "top"
                ContentSortOrder.NEWEST -> "new"
                ContentSortOrder.VIRAL_VELOCITY -> "hot"
                ContentSortOrder.RELEVANCE -> "relevance"
            }
            val resp = httpClient.get("https://www.reddit.com/search.json") {
                header(HttpHeaders.UserAgent, "android:forge.media.pocket:v4.0 (by /u/pocketforge)")
                parameter("q", query)
                parameter("sort", sortStr)
                parameter("t", if (session.isBackgroundMode) "all" else session.period.redditParam)
                parameter("limit", 40)
                parameter("raw_json", 1)
                session.redditAfter?.let { parameter("after", it) }
            }
            if (!resp.status.isSuccess()) {
                session.redditExhausted = true
                return emptyList()
            }

            val root = jsonFormat.parseToJsonElement(resp.bodyAsText()).jsonObject
            val dataObj = root["data"]?.jsonObject
            val nextAfter = dataObj?.get("after")?.jsonPrimitive?.contentOrNull
            session.redditAfter = nextAfter
            if (nextAfter.isNullOrBlank()) {
                session.redditExhausted = true
            }

            val children = dataObj?.get("children")?.jsonArray ?: return emptyList()
            for (child in children) {
                val post = child.jsonObject["data"]?.jsonObject ?: continue
                if (post["over_18"]?.jsonPrimitive?.contentOrNull == "true") continue

                val isVideo = post["is_video"]?.jsonPrimitive?.contentOrNull == "true"
                val rv = post["media"]?.jsonObject?.get("reddit_video")?.jsonObject
                    ?: post["secure_media"]?.jsonObject?.get("reddit_video")?.jsonObject

                if (!isVideo || rv == null) continue

                val id = post["id"]?.jsonPrimitive?.contentOrNull ?: continue
                val title = post["title"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                if (title.isBlank()) continue

                val permalink = post["permalink"]?.jsonPrimitive?.contentOrNull
                val postUrl = if (!permalink.isNullOrBlank()) {
                    "https://www.reddit.com$permalink"
                } else {
                    continue
                }

                val subreddit = post["subreddit_name_prefixed"]?.jsonPrimitive?.contentOrNull ?: "r/videos"
                val durSec = rv["duration"]?.jsonPrimitive?.intOrNull ?: 0
                if (durSec <= 0) continue
                val score = post["score"]?.jsonPrimitive?.longOrNull ?: 0L

                val thumb = post["preview"]?.jsonObject
                    ?.get("images")?.jsonArray?.firstOrNull()?.jsonObject
                    ?.get("source")?.jsonObject?.get("url")?.jsonPrimitive?.contentOrNull
                    ?: post["thumbnail"]?.jsonPrimitive?.contentOrNull?.takeIf { it.startsWith("http") }
                    ?: ""

                out.add(
                    DiscoveredMediaCandidate(
                        id = "rd_$id",
                        url = postUrl,
                        title = title,
                        author = subreddit,
                        platform = HuntPlatform.REDDIT,
                        durationSec = durSec,
                        viewOrScoreCount = score,
                        thumbnailUrl = thumb,
                        cloutStatusLabel = "👽 Upvotes: $score"
                    )
                )
            }
        } catch (e: Exception) {
            DiagnosticLogger.logError("REDDIT-SEARCH", "Сбой страницы Reddit", e)
            session.redditExhausted = true
        }
        return out
    }

    // =========================================================================
    // 5. RUTUBE API
    // =========================================================================

    private suspend fun fetchRutubeVideoPage(
        session: MultiPlatformCrawlSession,
        query: String
    ): List<DiscoveredMediaCandidate> {
        val out = mutableListOf<DiscoveredMediaCandidate>()
        try {
            val page = session.rutubePage
            val resp = httpClient.get("https://rutube.ru/api/search/video/") {
                header(HttpHeaders.UserAgent, DESKTOP_UA)
                parameter("query", query)
                parameter("page", page)
            }
            if (!resp.status.isSuccess()) {
                session.rutubeExhausted = true
                return emptyList()
            }

            val root = jsonFormat.parseToJsonElement(resp.bodyAsText()).jsonObject
            val hasNext = root["has_next"]?.jsonPrimitive?.booleanOrNull
                ?: (root["has_next"]?.jsonPrimitive?.contentOrNull == "true")
            if (!hasNext) session.rutubeExhausted = true else session.rutubePage += 1

            val results = root["results"]?.jsonArray ?: return emptyList()
            for (item in results) {
                val obj = item.jsonObject
                val id = obj["id"]?.jsonPrimitive?.contentOrNull ?: continue
                val title = obj["title"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                if (title.isBlank()) continue
                val videoUrl = obj["video_url"]?.jsonPrimitive?.contentOrNull
                    ?: "https://rutube.ru/video/$id/"
                val durSec = obj["duration"]?.jsonPrimitive?.intOrNull ?: 0
                val hits = obj["hits"]?.jsonPrimitive?.longOrNull ?: 0L
                val author = obj["author"]?.jsonObject?.get("name")?.jsonPrimitive?.contentOrNull ?: "Rutube"
                val thumb = obj["thumbnail_url"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val desc = obj["description"]?.jsonPrimitive?.contentOrNull?.take(160).orEmpty()
                val createdTs = obj["created_ts"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val ageLabel = estimateAgeLabelFromIsoDate(createdTs)

                out.add(
                    DiscoveredMediaCandidate(
                        id = "rt_$id",
                        url = videoUrl,
                        title = title,
                        author = author,
                        platform = HuntPlatform.RUTUBE,
                        durationSec = durSec,
                        viewOrScoreCount = hits,
                        thumbnailUrl = thumb,
                        publishedAgeLabel = ageLabel,
                        descriptionSnippet = desc,
                        cloutStatusLabel = formatCloutBadge(hits)
                    )
                )
            }
        } catch (e: Exception) {
            DiagnosticLogger.logError("RUTUBE-SEARCH", "Сбой страницы Rutube", e)
            session.rutubeExhausted = true
        }
        return out
    }

    // =========================================================================
    // SPAM-FARM CHANNEL HUNTER & "WHAT'S INSIDE" INSPECTOR
    // =========================================================================

    suspend fun huntSpamFarmChannels(
        topicQuery: String,
        maxChannels: Int = 6
    ): List<DiscoveredSpamChannel> = withContext(Dispatchers.IO) {
        val session = MultiPlatformCrawlSession(
            rawQuery = "$topicQuery shorts нарезка",
            negativeTags = "",
            platform = HuntPlatform.YOUTUBE,
            sortOrder = ContentSortOrder.NEWEST,
            period = SearchTimePeriod.MONTH,
            durationMode = SearchDurationMode.ANY_UP_TO_6M,
            languageMode = SearchLanguageMode.ANY_LANGUAGE
        )
        val pool = mutableListOf<DiscoveredMediaCandidate>()
        repeat(2) {
            if (session.hasMorePages()) {
                val p = executeYoutubeInnertubeSearch(
                    query = if (it == 0) "$topicQuery #shorts" else null,
                    spParams = if (it == 0) buildYoutubeSearchSpBase64(2, SearchTimePeriod.MONTH, 1) else null,
                    continuation = if (it == 0) null else session.ytShortContinuation
                )
                session.ytShortContinuation = p.second
                pool.addAll(p.first)
            }
        }

        val byAuthor = pool.groupBy { it.author }.filter { it.key.isNotBlank() && it.value.size >= 2 }
        byAuthor.entries.map { (author, vids) ->
            val avgViews = vids.map { it.viewOrScoreCount }.average().toLong()
            val score = (45 + vids.size * 12 + (if (avgViews > 15_000L) 20 else 5)).coerceIn(40, 99)
            DiscoveredSpamChannel(
                channelName = author,
                channelUrl = vids.first().url,
                topicQuery = topicQuery,
                spamScore = score,
                videosFoundCount = vids.size,
                estimatedUploadsPerWeek = (vids.size * 3.5),
                titleRandomnessPct = 35,
                patternSummary = "Частые заливы по теме «$topicQuery», средние просм.: ${formatViewsShort(avgViews)}",
                sampleVideos = vids.take(4)
            )
        }.sortedByDescending { it.spamScore }.take(maxChannels)
    }

    suspend fun inspectVideoInsideViaSubtitles(
        videoUrl: String,
        videoTitle: String,
        userTopic: String
    ): VideoInsideDigest = withContext(Dispatchers.IO) {
        val ytId = extractYoutubeId(videoUrl)
        var transcriptSnippet = ""
        if (ytId != null) {
            transcriptSnippet = fetchQuickYoutubeCaptionText(ytId)
        }

        val apiKey = configStore.getGeminiApiKey()
        if (apiKey.isNotBlank()) {
            val prompt = buildString {
                append("Разбери видео «$videoTitle» по запросу «$userTopic».\n")
                if (transcriptSnippet.isNotBlank()) {
                    append("Субтитры с таймкодами:\n${transcriptSnippet.take(5500)}\n")
                }
                append("Верни JSON: {\"overallSummary\":\"кратко 2 предл.\",\"keyTopics\":[\"тема1\"],\"moments\":[{\"startSec\":5,\"endSec\":45,\"title\":\"Панч/момент\",\"summary\":\"о чём\"}]}")
            }
            val raw = GeminiModelResolver.generateTextOrJson(apiKey, prompt, expectJson = true)
            if (!raw.isNullOrBlank()) {
                try {
                    val parsed = jsonFormat.decodeFromString<VideoInsideDigest>(raw)
                    return@withContext parsed.copy(hasTranscript = transcriptSnippet.isNotBlank())
                } catch (_: Exception) {
                }
            }
        }

        VideoInsideDigest(
            overallSummary = if (transcriptSnippet.isNotBlank()) {
                transcriptSnippet.take(360)
            } else {
                "Видео «$videoTitle» готово к открытию в Студии YouCut."
            },
            keyTopics = listOf(userTopic),
            moments = listOf(VideoHighlightMoment(0, 45, "Основной фрагмент", videoTitle.take(80))),
            hasTranscript = transcriptSnippet.isNotBlank()
        )
    }

    private suspend fun fetchQuickYoutubeCaptionText(videoId: String): String {
        return try {
            val ua = "com.google.android.apps.youtube.vr.oculus/1.60.19 (Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip"
            val req = buildJsonObject {
                put("videoId", videoId)
                putJsonObject("context") {
                    putJsonObject("client") {
                        put("clientName", "ANDROID_VR")
                        put("clientVersion", "1.60.19")
                        put("hl", "ru")
                    }
                }
            }
            val resp = httpClient.post("https://www.youtube.com/youtubei/v1/player?prettyPrint=false") {
                contentType(ContentType.Application.Json)
                header(HttpHeaders.UserAgent, ua)
                setBody(req.toString())
            }
            if (!resp.status.isSuccess()) return ""
            val root = jsonFormat.parseToJsonElement(resp.bodyAsText()).jsonObject
            val tracks = root["captions"]?.jsonObject
                ?.get("playerCaptionsTracklistRenderer")?.jsonObject
                ?.get("captionTracks")?.jsonArray ?: return ""
            val baseUrl = tracks.firstOrNull()?.jsonObject?.get("baseUrl")?.jsonPrimitive?.contentOrNull ?: return ""
            val xml = httpClient.get(baseUrl) { header(HttpHeaders.UserAgent, ua) }.bodyAsText()
            Regex("""<(?:text|p)\b[^>]*>(.*?)</(?:text|p)>""", RegexOption.DOT_MATCHES_ALL)
                .findAll(xml)
                .map { decodeHtmlEntities(it.groupValues[1].replace(Regex("""<[^>]+>"""), " ")).trim() }
                .filter { it.isNotBlank() }
                .joinToString(" ")
        } catch (_: Exception) {
            ""
        }
    }

    // =========================================================================
    // HELPERS
    // =========================================================================

    private fun isStrictlyRussianTitle(title: String): Boolean {
        var cyr = 0
        var lat = 0
        val withoutHashtags = title.replace(Regex("""#[a-zA-Z0-9_]+"""), " ")
        for (ch in withoutHashtags) {
            when {
                ch in 'а'..'я' || ch in 'А'..'Я' || ch == 'ё' || ch == 'Ё' -> cyr++
                ch in 'a'..'z' || ch in 'A'..'Z' -> lat++
            }
        }
        if (cyr < 3) return false
        val ratio = cyr.toDouble() / (cyr + lat).coerceAtLeast(1).toDouble()
        return ratio >= 0.55
    }

    private fun estimateAgeLabelFromIsoDate(iso: String): String {
        if (iso.length < 10) return ""
        return try {
            val year = iso.substring(0, 4).toInt()
            val month = iso.substring(5, 7).toInt()
            val cal = java.util.Calendar.getInstance()
            val curYear = cal.get(java.util.Calendar.YEAR)
            val curMonth = cal.get(java.util.Calendar.MONTH) + 1
            val monthsDiff = (curYear - year) * 12 + (curMonth - month)
            when {
                monthsDiff <= 0 -> "1 нед. назад"
                monthsDiff == 1 -> "1 мес. назад"
                monthsDiff < 12 -> "$monthsDiff мес. назад"
                else -> "${(monthsDiff / 12).coerceAtLeast(1)} г. назад"
            }
        } catch (_: Exception) {
            ""
        }
    }

    private fun extractMeaningfulAnchorTokens(query: String): List<String> {
        val stopWords = setOf(
            "видео", "ролики", "ролик", "клипы", "клип", "shorts", "short", "шорты", "шортс",
            "про", "для", "как", "что", "или", "под", "без", "фон", "фоны", "залипалка", "залипалки",
            "лучшие", "моменты", "топ", "истории", "жизни", "смешная"
        )
        return query.lowercase(Locale.ROOT)
            .split(Regex("""[^a-zA-Zа-яА-ЯёЁ0-9]+"""))
            .map { it.trim() }
            .filter { it.length >= 3 && it !in stopWords }
            .distinct()
    }

    private fun rankCandidates(
        list: List<DiscoveredMediaCandidate>,
        sortOrder: ContentSortOrder
    ): List<DiscoveredMediaCandidate> {
        return when (sortOrder) {
            ContentSortOrder.POPULAR -> list.sortedByDescending { it.viewOrScoreCount }
            ContentSortOrder.NEWEST -> list.sortedBy { estimateDaysFromAgeLabel(it.publishedAgeLabel) ?: 15 }
            ContentSortOrder.VIRAL_VELOCITY -> list.sortedByDescending { c ->
                val days = (estimateDaysFromAgeLabel(c.publishedAgeLabel) ?: 14).coerceAtLeast(1)
                c.viewOrScoreCount / days
            }
            ContentSortOrder.RELEVANCE -> list
        }
    }

    private fun parseColonDurationSec(raw: String): Int {
        val clean = raw.trim().replace(Regex("""[^\d:]"""), "")
        val parts = clean.split(":").filter { it.isNotBlank() }
        return try {
            when (parts.size) {
                3 -> parts[0].toInt() * 3600 + parts[1].toInt() * 60 + parts[2].toInt()
                2 -> parts[0].toInt() * 60 + parts[1].toInt()
                1 -> parts[0].toInt()
                else -> 0
            }
        } catch (_: Exception) {
            0
        }
    }

    private fun parseHumanViewsCount(raw: String): Long {
        if (raw.isBlank()) return 0L
        val low = raw.lowercase(Locale.ROOT).replace('\u00A0', ' ').trim()
        val numMatch = Regex("""(\d+(?:[.,]\d+)?)""").find(low)?.groupValues?.get(1)?.replace(",", ".")
            ?.toDoubleOrNull() ?: return 0L
        val mult = when {
            low.contains("млн") || low.contains("m") -> 1_000_000.0
            low.contains("тыс") || low.contains("k") || low.contains("к") -> 1_000.0
            else -> 1.0
        }
        if (mult == 1.0) {
            val digitsOnly = low.replace(Regex("""[^\d]"""), "")
            return digitsOnly.toLongOrNull() ?: numMatch.toLong()
        }
        return (numMatch * mult).roundToInt().toLong()
    }

    private fun estimateDaysFromAgeLabel(label: String): Int? {
        if (label.isBlank()) return null
        val low = label.lowercase(Locale.ROOT)
        val n = Regex("""(\d+)""").find(low)?.groupValues?.get(1)?.toIntOrNull() ?: 1
        return when {
            low.contains("мин") || low.contains("час") || low.contains("hour") || low.contains("min") -> 0
            low.contains("дн") || low.contains("ден") || low.contains("сут") || low.contains("day") -> n
            low.contains("нед") || low.contains("week") -> n * 7
            low.contains("мес") || low.contains("month") -> n * 30
            low.contains("год") || low.contains("лет") || low.contains("year") || low.contains("г.") -> n * 365
            else -> null
        }
    }

    private fun formatCloutBadge(views: Long): String = when {
        views >= 500_000L -> "🔥 Хитовый ролик (${formatViewsShort(views)})"
        views >= 50_000L -> "📈 Хороший охват (${formatViewsShort(views)})"
        views >= 5_000L -> "✅ Живая аудитория (${formatViewsShort(views)})"
        views > 0L -> "👁 ${formatViewsShort(views)}"
        else -> "🔗 Прямая ссылка"
    }

    private fun formatViewsShort(v: Long): String = when {
        v >= 1_000_000L -> String.format(Locale.US, "%.1fM", v / 1_000_000.0)
        v >= 1_000L -> String.format(Locale.US, "%.1fK", v / 1_000.0)
        else -> v.toString()
    }

    private fun decodeHtmlEntities(s: String): String =
        s.replace("&quot;", "\"")
            .replace("&amp;", "&")
            .replace("&#39;", "'")
            .replace("&apos;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&nbsp;", " ")

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
}
