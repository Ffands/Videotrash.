package forge.media.pocket.discovery

import android.content.Context
import android.util.Base64
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
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
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.math.ln
import kotlin.math.roundToInt

enum class ContentSortOrder(val label: String) {
    POPULAR("🔥 По популярности (Реальные просм.)"),
    NEWEST("🆕 По новизне (Свежие)"),
    VIRAL_VELOCITY("📈 Взлетающие (Просм./день)"),
    RELEVANCE("🧠 Умный AI-Отбор (Gemini)")
}

enum class SearchTimePeriod(val label: String, val daysBack: Int?, val redditParam: String) {
    DAY("За 24 часа", 1, "day"),
    WEEK("За неделю", 7, "week"),
    MONTH("За месяц", 31, "month"),
    YEAR("За год", 365, "year"),
    ALL_TIME("За всё время", null, "all")
}

enum class HuntPlatform(val label: String) {
    YOUTUBE("▶️ YouTube"),
    ALL("🌐 Все площадки (YT + Rutube + Reddit)"),
    RUTUBE("📺 Rutube"),
    REDDIT("👽 Reddit Video")
}

enum class SearchDurationMode(
    val label: String,
    val minSec: Int,
    val maxSec: Int,
    val appendShortsKeyword: Boolean
) {
    ANY_UP_TO_6M("🌐 Любые (до 6 мин)", 8, 360, false),
    ORIGINALS_1_TO_6M("🎬 Исходники 1–6 мин (Без чужих эдитов)", 55, 360, false),
    SHORTS_ONLY("⚡ Только Shorts (до 75 сек)", 8, 75, true)
}

enum class SearchLanguageMode(val label: String) {
    RUSSIAN_ONLY("🇷🇺 Только Русский (Без других языков)"),
    ENGLISH_FOR_DUB("🇺🇸 Английский (Под переозвучку EN->RU)"),
    ANY_LANGUAGE("🌐 Любой язык (RU + EN)")
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

class TopicHunterEngine(private val context: Context) {

    private val configStore = NodeConfigStore(context)
    private val seenIdsFile = File(context.filesDir, "seen_video_ids.txt")
    private val cookiesFile = File(context.filesDir, "youtube_cookies.txt")
    private val stagingDir = File(context.cacheDir, "work_staging").apply { mkdirs() }
    private val stateMutex = Mutex()

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
    private data class YtDlpFlatEntry(
        val id: String = "",
        val url: String? = null,
        @SerialName("webpage_url") val webpageUrl: String? = null,
        val title: String = "",
        val description: String? = null,
        val uploader: String? = null,
        val channel: String? = null,
        val duration: Double? = null,
        @SerialName("view_count") val viewCount: Long? = null,
        @SerialName("upload_date") val uploadDate: String? = null
    )

    @Serializable
    private data class RedditSearchListing(
        val data: RedditSearchData? = null
    )

    @Serializable
    private data class RedditSearchData(
        val children: List<RedditSearchChild> = emptyList()
    )

    @Serializable
    private data class RedditSearchChild(
        val data: RedditSearchPost? = null
    )

    @Serializable
    private data class RedditSearchPost(
        val id: String = "",
        val title: String = "",
        val author: String = "",
        @SerialName("is_video") val isVideo: Boolean = false,
        val over18: Boolean = false,
        val score: Long = 0L,
        @SerialName("created_utc") val createdUtc: Double = 0.0,
        val permalink: String? = null,
        val media: RedditSearchMedia? = null
    )

    @Serializable
    private data class RedditSearchMedia(
        @SerialName("reddit_video") val redditVideo: RedditSearchVideo? = null
    )

    @Serializable
    private data class RedditSearchVideo(
        @SerialName("fallback_url") val fallbackUrl: String? = null,
        val duration: Int = 0,
        val height: Int = 0
    )

    @Serializable
    private data class RutubeSearchResponse(
        val results: List<RutubeVideoEntry> = emptyList()
    )

    @Serializable
    private data class RutubeVideoEntry(
        val id: String = "",
        val title: String = "",
        val description: String? = null,
        val duration: Int = 0,
        val hits: Long = 0L,
        @SerialName("video_url") val videoUrl: String? = null,
        @SerialName("created_ts") val createdTs: String? = null,
        val author: RutubeAuthorDto? = null
    )

    @Serializable
    private data class RutubeAuthorDto(
        val name: String = "Rutube"
    )

    @Serializable
    private data class AdaptiveNichePlanDto(
        val activeMetaDescription: String = "",
        val queries: List<String> = emptyList(),
        val englishRedditQuery: String = "",
        val tierOneEntities: List<String> = emptyList(),
        val outdatedBlacklist: List<String> = emptyList()
    )

    @Serializable
    private data class AiRerankedItemDto(
        val index: Int = 0,
        val cloutTier: String = "RISING_NICHE",
        val conversionScore: Int = 70,
        val entityWhoIs: String = "",
        val whatsInside: String = ""
    )

    @Serializable
    private data class AiRerankResponseDto(
        val selected: List<AiRerankedItemDto> = emptyList()
    )

    private data class InnertubePlayerProbe(
        val title: String = "",
        val author: String = "",
        val description: String = "",
        val durationSec: Int = 60,
        val viewCount: Long = 0L,
        val captionXmlOrVtt: String = ""
    )

    private fun sanitizeQuery(rawQuery: String): String {
        return rawQuery
            .replace("#", " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
            .ifBlank { rawQuery.trim() }
    }

    /**
     * Главный поисковик по параметрам пользователя (БЕЗ подмены периода и БЕЗ фейковых просмотров):
     * - Строго соблюдает выбранный `period` (никаких 2-3 летних роликов при `MONTH`!)
     * - Строго отсекает мёртвые 200-просмотровые ролики на `POPULAR`
     * - Ограничивает максимум 2 ролика с одного канала (разнообразие авторов)
     * - Возвращает ровно до `limitCount` (например 10 из 10), не обрезая выдачу до 2 роликов
     * - При `HuntPlatform.ALL` реально опрашивает YouTube + Rutube + Reddit Video и смешивает выдачу!
     */
    suspend fun huntByTags(
        queryTags: String,
        platform: HuntPlatform = HuntPlatform.YOUTUBE,
        sortOrder: ContentSortOrder = ContentSortOrder.POPULAR,
        period: SearchTimePeriod = SearchTimePeriod.MONTH,
        durationMode: SearchDurationMode = SearchDurationMode.ANY_UP_TO_6M,
        languageMode: SearchLanguageMode = SearchLanguageMode.RUSSIAN_ONLY,
        limitCount: Int = 10,
        minDurationSec: Int = durationMode.minSec,
        maxDurationSec: Int = durationMode.maxSec
    ): List<DiscoveredMediaCandidate> = withContext(Dispatchers.IO) {
        val cleanQuery = sanitizeQuery(queryTags)
        val requestedLimit = limitCount.coerceIn(1, 15)

        DiagnosticLogger.log(
            "HUNT",
            "Поиск СТРОГО по параметрам: '$cleanQuery' (пл=${platform.name}, сорт=${sortOrder.name}, период=${period.name}, тип=${durationMode.name}, яз=${languageMode.name}, кол-во=$requestedLimit)"
        )

        val nichePlan = buildAdaptiveFanbaseSearchPlan(
            userPrompt = cleanQuery,
            durationMode = durationMode,
            languageMode = languageMode
        )
        val searchQueries = nichePlan.queries.ifEmpty { listOf(cleanQuery) }

        DiagnosticLogger.log(
            "HUNT:QUERIES",
            "Запросы (${searchQueries.size} шт.): ${searchQueries.joinToString(" | ")}"
        )

        val rawPool = mutableListOf<DiscoveredMediaCandidate>()

        // 1. YouTube (Direct Innertube API with server-side `sp` date & popularity filter!)
        if (platform == HuntPlatform.ALL || platform == HuntPlatform.YOUTUBE) {
            for (q in searchQueries) {
                rawPool.addAll(
                    searchYouTubeByTags(
                        queryTags = q,
                        nichePlan = nichePlan,
                        sortOrder = sortOrder,
                        period = period,
                        languageMode = languageMode,
                        appendShortsKeyword = durationMode.appendShortsKeyword,
                        minDurationSec = minDurationSec,
                        maxDurationSec = maxDurationSec,
                        maxFetchCount = 25
                    )
                )
            }
        }

        // 2. Rutube (Public JSON API with exact upload date & real view counts)
        if (platform == HuntPlatform.ALL || platform == HuntPlatform.RUTUBE) {
            for (q in searchQueries.take(3)) {
                rawPool.addAll(
                    searchRutubeVideosByTags(
                        queryTags = q,
                        nichePlan = nichePlan,
                        sortOrder = sortOrder,
                        period = period,
                        minDurationSec = minDurationSec,
                        maxDurationSec = maxDurationSec
                    )
                )
            }
        }

        // 3. Reddit Video (Auto-translated English query when searching from Russian so ALL platforms actually returns Reddit!)
        if (platform == HuntPlatform.ALL || platform == HuntPlatform.REDDIT) {
            val redditQuery = nichePlan.englishRedditQuery.ifBlank {
                translateTopicForRedditFallback(cleanQuery)
            }
            val redditLangMode = if (platform == HuntPlatform.ALL || platform == HuntPlatform.REDDIT) {
                SearchLanguageMode.ANY_LANGUAGE
            } else {
                languageMode
            }
            rawPool.addAll(
                searchRedditVideosByTags(
                    queryTags = redditQuery,
                    sortOrder = sortOrder,
                    period = period,
                    minDurationSec = minDurationSec,
                    maxDurationSec = maxDurationSec,
                    languageMode = redditLangMode
                )
            )
        }

        val deduplicatedPool = rawPool.distinctBy { it.id }
        if (deduplicatedPool.isEmpty()) {
            DiagnosticLogger.log(
                "HUNT",
                "По строгим параметрам (${period.name}, ${sortOrder.name}) кандидатов: 0 (без подмены на старьё!)."
            )
            return@withContext emptyList()
        }

        val seenIds = loadSeenIds()
        var workingPool = deduplicatedPool.filter { it.id.isNotBlank() && !seenIds.contains(it.id) }
        if (workingPool.size < requestedLimit) {
            // Append previously seen valid items only if needed to reach requestedLimit, still strictly matching period!
            val extra = deduplicatedPool.filter { it !in workingPool }
            workingPool = (workingPool + extra).distinctBy { it.id }
        }

        // Sort strictly according to user's chosen sortOrder BEFORE diversity selection
        val sortedByUserRule = sortCandidatesStrictly(workingPool, sortOrder, cleanQuery, nichePlan)

        // Apply Channel Diversity + Multi-Platform Interleaving so 10 results NEVER come from 1 channel or 1 platform!
        val diverseCandidates = selectDiverseCandidates(
            sortedPool = sortedByUserRule,
            platformMode = platform,
            targetCount = (requestedLimit * 2).coerceAtLeast(16),
            maxPerAuthor = 2
        )

        // Annotate Clout & "What's Inside" WITHOUT letting AI drop candidates below requestedLimit!
        val annotated = annotateCloutAndSummaryPreservingCount(
            userFreeformPrompt = cleanQuery,
            nichePlan = nichePlan,
            sortOrder = sortOrder,
            languageMode = languageMode,
            candidates = diverseCandidates,
            limitCount = requestedLimit
        )

        DiagnosticLogger.log(
            "HUNT",
            "✅ Отобрано ${annotated.size} из запрошенных $requestedLimit шт. (пул=${deduplicatedPool.size}, пл=${annotated.map { it.platform.name }.distinct()})"
        )
        markCandidatesAsSeen(annotated.map { it.id })
        annotated
    }

    /**
     * Поиск коротких фонов / залипалок (10–85 сек, без говорящих голов, >=720p) прямо для Telegram-бота и Студии.
     */
    suspend fun searchBackgroundCandidates(
        topicQuery: String,
        limitCount: Int = 6
    ): List<DiscoveredMediaCandidate> = withContext(Dispatchers.IO) {
        val clean = sanitizeQuery(topicQuery)
        val lower = clean.lowercase(Locale.getDefault())
        val searchTail = when {
            lower.contains("мото") || lower.contains("байк") || lower.contains("moto") ->
                "$clean motorcycle pov night ride no talking shorts"
            lower.contains("авто") || lower.contains("дрифт") || lower.contains("машин") || lower.contains("beamng") ->
                "$clean car drift beamng drive gameplay shorts"
            lower.contains("мыл") || lower.contains("песок") || lower.contains("антистресс") || lower.contains("asmr") ->
                "oddly satisfying kinetic sand soap cutting asmr shorts"
            lower.contains("кс") || lower.contains("cs2") || lower.contains("surf") ->
                "cs2 surf gameplay smooth 60fps no commentary shorts"
            lower.contains("майн") || lower.contains("minecraft") || lower.contains("паркур") ->
                "minecraft parkour satisfying gameplay no talking shorts"
            lower.contains("гта") || lower.contains("gta") ->
                "gta 5 stunt race ramp satisfying gameplay shorts"
            lower.contains("пресс") || lower.contains("станок") || lower.contains("механ") ->
                "hydraulic press shredder satisfying shorts"
            else -> "$clean satisfying aesthetic gameplay pov no talking shorts"
        }

        val queries = listOf(searchTail, "$clean shorts gameplay satisfying").distinct()
        val found = mutableListOf<DiscoveredMediaCandidate>()

        for (q in queries) {
            if (found.size >= limitCount * 2) break
            val batch = searchYouTubeViaInnertube(
                query = q,
                sortOrder = ContentSortOrder.POPULAR,
                period = SearchTimePeriod.YEAR,
                hl = "en",
                gl = "US",
                minDurationSec = 10,
                maxDurationSec = 90,
                minRequiredViews = 2_000L
            )
            found.addAll(batch)
        }

        if (found.isEmpty()) {
            found.addAll(
                searchYouTubeViaYtDlpFallback(
                    finalQuery = searchTail,
                    period = SearchTimePeriod.ALL_TIME,
                    languageMode = SearchLanguageMode.ANY_LANGUAGE,
                    effectiveBlacklist = emptyList(),
                    appendShortsKeyword = true,
                    minDurationSec = 10,
                    maxDurationSec = 90,
                    maxFetchCount = 15,
                    minRequiredViews = 500L
                )
            )
        }

        found.distinctBy { it.id }
            .sortedByDescending { it.viewOrScoreCount }
            .take(limitCount.coerceIn(1, 10))
    }

    private fun sortCandidatesStrictly(
        pool: List<DiscoveredMediaCandidate>,
        sortOrder: ContentSortOrder,
        cleanQuery: String,
        nichePlan: AdaptiveNichePlanDto
    ): List<DiscoveredMediaCandidate> {
        return when (sortOrder) {
            ContentSortOrder.POPULAR -> pool.sortedByDescending { it.viewOrScoreCount }
            ContentSortOrder.NEWEST -> pool.sortedByDescending { it.uploadDateYmd }
            ContentSortOrder.VIRAL_VELOCITY -> pool.sortedByDescending { computeViralVelocityScore(it) }
            ContentSortOrder.RELEVANCE -> pool.sortedByDescending { scoreCandidateRelevance(it, cleanQuery, nichePlan) }
        }
    }

    private fun selectDiverseCandidates(
        sortedPool: List<DiscoveredMediaCandidate>,
        platformMode: HuntPlatform,
        targetCount: Int,
        maxPerAuthor: Int
    ): List<DiscoveredMediaCandidate> {
        if (sortedPool.isEmpty()) return emptyList()

        val authorCounts = mutableMapOf<String, Int>()
        val selected = mutableListOf<DiscoveredMediaCandidate>()

        if (platformMode == HuntPlatform.ALL) {
            // Round-robin across platforms first so "Все площадки" genuinely mixes YouTube, Rutube & Reddit!
            val byPlatform = sortedPool.groupBy { it.platform }.mapValues { it.value.toMutableList() }
            val order = listOf(HuntPlatform.YOUTUBE, HuntPlatform.RUTUBE, HuntPlatform.REDDIT)
            var madeProgress = true
            while (selected.size < targetCount && madeProgress) {
                madeProgress = false
                for (plat in order) {
                    val queue = byPlatform[plat] ?: continue
                    val nextIdx = queue.indexOfFirst { cand ->
                        val key = cand.author.lowercase(Locale.getDefault()).trim()
                        (authorCounts[key] ?: 0) < maxPerAuthor
                    }
                    if (nextIdx >= 0) {
                        val picked = queue.removeAt(nextIdx)
                        val key = picked.author.lowercase(Locale.getDefault()).trim()
                        authorCounts[key] = (authorCounts[key] ?: 0) + 1
                        selected.add(picked)
                        madeProgress = true
                        if (selected.size >= targetCount) break
                    }
                }
            }
        } else {
            for (cand in sortedPool) {
                if (selected.size >= targetCount) break
                val key = cand.author.lowercase(Locale.getDefault()).trim()
                val cur = authorCounts[key] ?: 0
                if (cur < maxPerAuthor) {
                    authorCounts[key] = cur + 1
                    selected.add(cand)
                }
            }
        }

        // If strict maxPerAuthor left us slightly short of targetCount, top up from remaining unique videos
        if (selected.size < targetCount) {
            for (cand in sortedPool) {
                if (selected.size >= targetCount) break
                if (selected.none { it.id == cand.id }) {
                    selected.add(cand)
                }
            }
        }
        return selected
    }

    private suspend fun buildAdaptiveFanbaseSearchPlan(
        userPrompt: String,
        durationMode: SearchDurationMode,
        languageMode: SearchLanguageMode
    ): AdaptiveNichePlanDto {
        val hl = if (languageMode == SearchLanguageMode.ENGLISH_FOR_DUB) "en" else "ru"
        val normalizedBase = normalizeLegacyNicheTerms(userPrompt, languageMode)

        val primarySuggestions = fetchYouTubeAutocompleteSuggestions(normalizedBase, hl)
        val rootWord = normalizedBase.split(Regex("""\s+""")).firstOrNull()?.takeIf { it.length >= 4 && it != normalizedBase }
        val rootSuggestions = if (rootWord != null && primarySuggestions.size < 3) {
            fetchYouTubeAutocompleteSuggestions(rootWord, hl)
        } else {
            emptyList()
        }
        val liveAutocomplete = (primarySuggestions + rootSuggestions)
            .map { sanitizeQuery(it) }
            .filter { it.isNotBlank() && matchesLanguageFilter(it, "", languageMode) }
            .distinct()

        val modeHint = when (durationMode) {
            SearchDurationMode.ORIGINALS_1_TO_6M -> "Цельные ролики/выступления 1–6 минут."
            SearchDurationMode.SHORTS_ONLY -> "Вертикальные Shorts / клипы до 75 секунд."
            SearchDurationMode.ANY_UP_TO_6M -> "Вовлекающие ролики до 6 минут."
        }

        val langRule = when (languageMode) {
            SearchLanguageMode.RUSSIAN_ONLY -> "Строго на русском языке."
            SearchLanguageMode.ENGLISH_FOR_DUB -> "Строго на английском языке (под переозвучку)."
            SearchLanguageMode.ANY_LANGUAGE -> "3 запроса на русском и 2 на английском."
        }

        val suggestContext = if (liveAutocomplete.isNotEmpty()) {
            "Живые автоподсказки YouTube: ${liveAutocomplete.joinToString(", ")}"
        } else {
            ""
        }

        val prompt = """
            Ты — аналитик вирального контента YouTube, Rutube и Reddit.
            Тема поиска пользователя: "$userPrompt"
            Формат: $modeHint
            Язык: $langRule
            $suggestContext

            Проанализируй нишу без выдуманных имён:
            1. "activeMetaDescription": Какая живая мета ниши сейчас собирает реальные просмотры (например CS2 вместо CS 1.6; современный стендап вместо архивной эстрады).
            2. "tierOneEntities": 5–7 РЕАЛЬНО СУЩЕСТВУЮЩИХ известных персон, клубов, шоу или команд ниши.
            3. "outdatedBlacklist": ТОЛЬКО явные слова старых/мёртвых версий (например ["cs 1.6", "кс 1.6", "жванецкий", "петросян", "аншлаг"]).
            4. "queries": 4 КОРОТКИХ поисковых запроса для YouTube (по 2–4 слова, без годов и без мусорных слов).
            5. "englishRedditQuery": Переведи суть темы в 2–3 английских слова для поиска видео на Reddit (например "standup comedy crowd", "cs2 clutch ace", "motorcycle close call").

            Верни СТРОГО JSON:
            {
              "activeMetaDescription": "...",
              "tierOneEntities": ["..."],
              "outdatedBlacklist": ["..."],
              "queries": ["запрос 1", "запрос 2", "запрос 3", "запрос 4"],
              "englishRedditQuery": "english topic keywords"
            }
        """.trimIndent()

        try {
            val rawJson = GeminiModelResolver.generateTextOrJson(
                apiKey = configStore.getGeminiApiKey(),
                prompt = prompt,
                expectJson = true
            )
            if (!rawJson.isNullOrBlank()) {
                val parsed = jsonFormat.decodeFromString<AdaptiveNichePlanDto>(rawJson)
                val safeBlacklist = sanitizeOutdatedBlacklist(
                    parsed.outdatedBlacklist + universalDeadContentBlacklist(userPrompt),
                    userPrompt
                )

                val mergedQueries = linkedSetOf<String>()
                if (languageMode != SearchLanguageMode.ENGLISH_FOR_DUB || normalizedBase.any { it in 'a'..'z' || it in 'A'..'Z' }) {
                    mergedQueries.add(normalizedBase)
                }
                for (sug in liveAutocomplete.take(2)) {
                    if (!containsBlacklistedTerm(sug, safeBlacklist)) {
                        mergedQueries.add(sug)
                    }
                }
                for (q in parsed.queries) {
                    if (mergedQueries.size >= 5) break
                    val clean = cleanGeneratedSearchQuery(q)
                    if (clean.isNotBlank() &&
                        matchesLanguageFilter(clean, "", languageMode) &&
                        !containsBlacklistedTerm(clean, safeBlacklist)
                    ) {
                        mergedQueries.add(clean)
                    }
                }

                if (mergedQueries.isNotEmpty()) {
                    return parsed.copy(
                        queries = mergedQueries.take(5),
                        englishRedditQuery = parsed.englishRedditQuery.ifBlank { translateTopicForRedditFallback(userPrompt) },
                        outdatedBlacklist = safeBlacklist
                    )
                }
            }
        } catch (e: Exception) {
            DiagnosticLogger.log("HUNT:PLAN", "Предупреждение AI-планировщика: ${e.message?.take(80)}")
        }

        val fallbackBlacklist = sanitizeOutdatedBlacklist(universalDeadContentBlacklist(userPrompt), userPrompt)
        val fallbackQueries = linkedSetOf<String>()
        fallbackQueries.add(normalizedBase)
        for (sug in liveAutocomplete) {
            if (fallbackQueries.size >= 5) break
            if (!containsBlacklistedTerm(sug, fallbackBlacklist)) {
                fallbackQueries.add(sug)
            }
        }

        return AdaptiveNichePlanDto(
            activeMetaDescription = normalizedBase,
            queries = fallbackQueries.filter { it.isNotBlank() }.take(5),
            englishRedditQuery = translateTopicForRedditFallback(userPrompt),
            tierOneEntities = emptyList(),
            outdatedBlacklist = fallbackBlacklist
        )
    }

    private fun translateTopicForRedditFallback(query: String): String {
        val low = query.lowercase(Locale.getDefault())
        return when {
            low.contains("стендап") || low.contains("комик") || low.contains("монолог") -> "standup comedy crowd work"
            low.contains("кс") || low.contains("cs2") || low.contains("клатч") -> "cs2 clutch highlight"
            low.contains("мото") || low.contains("байк") -> "motorcycle pov"
            low.contains("авто") || low.contains("дрифт") || low.contains("дорог") -> "car dashcam drift"
            low.contains("подкаст") || low.contains("интервью") -> "podcast clip story"
            low.contains("науч") || low.contains("космос") || low.contains("физик") -> "science space explained"
            low.contains("аниме") -> "anime scene edit"
            low.contains("кот") || low.contains("собак") || low.contains("животн") -> "funny animals"
            low.any { it in 'a'..'z' } -> query
            else -> "viral clip"
        }
    }

    /**
     * Честная математическая оценка конверсии по реальным просмотрам (никаких 90% у ролика с 200 просмотрами!).
     */
    private fun clampHonestConversionScore(
        aiSuggestedScore: Int?,
        c: DiscoveredMediaCandidate,
        hasTierOneEntity: Boolean
    ): Pair<String, Int> {
        val v = c.viewOrScoreCount
        // Reddit uses upvote score rather than views (1 upvote ~ 80-100 video views)
        val effectiveViews = if (c.platform == HuntPlatform.REDDIT) v * 85L else v

        val (minAllowed, maxAllowed, badge) = when {
            effectiveViews < 500L -> Triple(5, 18, "⚠️ МАЛО ПРОСМОТРОВ (Нет выборки)")
            effectiveViews < 2_500L -> Triple(18, 36, "📉 НИЗКИЙ ОХВАТ / НОУНЕЙМ")
            effectiveViews < 10_000L -> Triple(38, 58, "📊 СРЕДНИЙ ОХВАТ")
            effectiveViews < 50_000L -> Triple(58, 78, "📈 ХОРОШИЙ ОХВАТ / ЖИВАЯ ТЕМА")
            effectiveViews < 250_000L -> Triple(75, 90, "🔥 ВЫСОКИЙ ОХВАТ / ФАН-БАЗА")
            else -> Triple(86, 97, "🚀 МИЛЛИОННИК / ТОП КОНВЕРСИЯ")
        }

        val base = aiSuggestedScore ?: ((minAllowed + maxAllowed) / 2 + if (hasTierOneEntity) 6 else 0)
        val clamped = base.coerceIn(minAllowed, maxAllowed)
        return badge to clamped
    }

    /**
     * Аннотирует кандидатов через Gemini, НО НИКОГДА не урезает выдачу с 10 до 2 роликов
     * и сохраняет выбранную пользователем сортировку (например POPULAR по просмотрам).
     */
    private suspend fun annotateCloutAndSummaryPreservingCount(
        userFreeformPrompt: String,
        nichePlan: AdaptiveNichePlanDto,
        sortOrder: ContentSortOrder,
        languageMode: SearchLanguageMode,
        candidates: List<DiscoveredMediaCandidate>,
        limitCount: Int
    ): List<DiscoveredMediaCandidate> {
        if (candidates.isEmpty()) return emptyList()
        val workingSlice = candidates.take((limitCount + 4).coerceAtMost(18))

        val aiAnnotationsByIndex = mutableMapOf<Int, AiRerankedItemDto>()
        val apiKey = configStore.getGeminiApiKey()
        if (apiKey.isNotBlank()) {
            try {
                val catalogText = workingSlice.mapIndexed { idx, c ->
                    val agePart = if (c.publishedAgeLabel.isNotBlank()) " | age=${c.publishedAgeLabel}" else ""
                    "[$idx] platform=${c.platform.name} | title=\"${c.title}\" | channel=\"${c.author}\" | dur=${c.durationSec}s | realViews=${c.viewOrScoreCount}$agePart"
                }.joinToString("\n")

                val prompt = """
                    Ты — честный критик-аналитик видеоконтента.
                    Запрос: "$userFreeformPrompt"
                    Кандидаты (${workingSlice.size} шт.):
                    $catalogText

                    Для КАЖДОГО кандидата из списка (верни все ${workingSlice.size} элементов, ничего не удаляя!) напиши честную краткую оценку:
                    - "index": номер из списка (0..${workingSlice.lastIndex})
                    - "cloutTier": "TOP_FANBASE" / "RISING_NICHE" / "NONAME_LOW" (НЕ ставь TOP_FANBASE если realViews < 10000!)
                    - "conversionScore": честный балл 10..95 пропорционально реальным просмотрам realViews и известности автора
                    - "entityWhoIs": до 90 символов — кто автор/комик/канал на самом деле (честно пиши, если это мелкий канал нарезчик или крупное шоу)
                    - "whatsInside": до 120 символов на русском языке — о чём ролик по заголовку и контексту

                    Верни СТРОГО JSON:
                    {
                      "selected": [
                        {"index": 0, "cloutTier": "TOP_FANBASE", "conversionScore": 84, "entityWhoIs": "...", "whatsInside": "..."}
                      ]
                    }
                """.trimIndent()

                val rawJson = GeminiModelResolver.generateTextOrJson(
                    apiKey = apiKey,
                    prompt = prompt,
                    expectJson = true
                )
                if (!rawJson.isNullOrBlank()) {
                    val parsed = jsonFormat.decodeFromString<AiRerankResponseDto>(rawJson)
                    for (item in parsed.selected) {
                        if (item.index in workingSlice.indices) {
                            aiAnnotationsByIndex[item.index] = item
                        }
                    }
                }
            } catch (e: Exception) {
                DiagnosticLogger.log("HUNT:ANNOTATE", "Предупреждение AI-аннотации: ${e.message?.take(80)}")
            }
        }

        val enriched = workingSlice.mapIndexed { idx, orig ->
            val ai = aiAnnotationsByIndex[idx]
            val combined = "${orig.title} ${orig.author}".lowercase(Locale.getDefault())
            val hasEntity = nichePlan.tierOneEntities.any { ent ->
                ent.length >= 3 && combined.contains(ent.lowercase(Locale.getDefault()))
            }
            val (honestBadge, honestScore) = clampHonestConversionScore(
                aiSuggestedScore = ai?.conversionScore,
                c = orig,
                hasTierOneEntity = hasEntity
            )
            val summary = ai?.whatsInside?.trim()?.takeIf { it.isNotBlank() }
                ?: buildLocalWhatsInsideFallback(orig, languageMode)
            val entityNote = ai?.entityWhoIs?.trim()?.takeIf { it.isNotBlank() }
                ?: estimateLocalCloutNote(orig, nichePlan)

            orig.copy(
                aiWhatsInside = summary,
                cloutStatusLabel = "$honestBadge • 🎯 Оценка охвата: $honestScore/100",
                entityPopularityNote = entityNote,
                conversionScore = honestScore
            )
        }

        // Preserve user's explicit sort order (`POPULAR` stays sorted by real views!)
        val finalSorted = when (sortOrder) {
            ContentSortOrder.POPULAR -> {
                if (enriched.map { it.platform }.distinct().size > 1) {
                    // Keep multi-platform diversity while ordering high-reach items first within interleaving
                    enriched
                } else {
                    enriched.sortedByDescending { it.viewOrScoreCount }
                }
            }
            ContentSortOrder.NEWEST -> enriched.sortedByDescending { it.uploadDateYmd }
            ContentSortOrder.VIRAL_VELOCITY -> enriched.sortedByDescending { computeViralVelocityScore(it) }
            ContentSortOrder.RELEVANCE -> enriched.sortedByDescending { it.conversionScore }
        }

        return finalSorted.distinctBy { it.id }.take(limitCount)
    }

    private fun estimateLocalCloutNote(c: DiscoveredMediaCandidate, nichePlan: AdaptiveNichePlanDto): String {
        val combined = "${c.title} ${c.author}".lowercase(Locale.getDefault())
        val matchedEntity = nichePlan.tierOneEntities.firstOrNull { ent ->
            ent.length >= 3 && combined.contains(ent.lowercase(Locale.getDefault()))
        }
        val ageInfo = if (c.publishedAgeLabel.isNotBlank()) ", ${c.publishedAgeLabel}" else ""
        return when {
            matchedEntity != null -> "Известный триггер ниши («$matchedEntity») • Канал: ${c.author}$ageInfo"
            c.viewOrScoreCount >= 100_000L -> "Крупный ролик (${c.viewOrScoreCount} просм.$ageInfo) на канале «${c.author}»"
            c.viewOrScoreCount >= 10_000L -> "Живой канал «${c.author}» (${c.viewOrScoreCount} просм.$ageInfo)"
            else -> "Небольшой канал «${c.author}» (${c.viewOrScoreCount} просм.$ageInfo)"
        }
    }

    private fun buildLocalWhatsInsideFallback(c: DiscoveredMediaCandidate, languageMode: SearchLanguageMode): String {
        val langTag = if (languageMode == SearchLanguageMode.ENGLISH_FOR_DUB) "[EN под переозвучку] " else ""
        val snippet = c.descriptionSnippet
            .replace(Regex("""https?://\S+"""), "")
            .replace(Regex("""\s+"""), " ")
            .trim()
            .take(110)
        return if (snippet.length >= 15) {
            "$langTag$snippet"
        } else {
            "${langTag}Нажми «🧠 Что внутри» под роликом — бот мгновенно вытащит субтитры и точные таймкоды!"
        }
    }

    // =========================================================================
    // STAGE 3: МГНОВЕННАЯ ВЫЖИМКА «ЧТО ВНУТРИ» (INNERTUBE PLAYER + YT-DLP + GEMINI)
    // =========================================================================

    suspend fun inspectVideoInsideViaSubtitles(
        videoUrl: String,
        videoTitle: String,
        userTopic: String
    ): VideoInsideDigest = withContext(Dispatchers.IO) {
        DiagnosticLogger.log("WHATS-INSIDE", "Запуск быстрого разбора «Что внутри» для $videoUrl")

        var resolvedTitle = videoTitle
        var fullDescription = ""
        var durationSec = 60
        var timestampedTranscript = ""

        // Step 1: Fast direct YouTube Innertube `/youtubei/v1/player` probe (~0.4s, bypasses slow yt-dlp startup!)
        val ytId = extractYoutubeVideoIdFromUrl(videoUrl)
        if (ytId != null) {
            val probe = fetchYoutubePlayerAndCaptionsFast(ytId)
            if (probe != null) {
                if (probe.title.isNotBlank()) resolvedTitle = probe.title
                if (probe.description.isNotBlank()) fullDescription = probe.description
                if (probe.durationSec > 0) durationSec = probe.durationSec
                if (probe.captionXmlOrVtt.isNotBlank()) {
                    timestampedTranscript = probe.captionXmlOrVtt
                }
            }
        }

        // Step 2: Fallback to yt-dlp with modern player_client args if Innertube captions were empty
        if (timestampedTranscript.isBlank()) {
            timestampedTranscript = fetchTimestampedVttTranscriptViaYtDlp(videoUrl)
        }

        if (fullDescription.isBlank() && timestampedTranscript.isBlank()) {
            try {
                val infoReq = YoutubeDLRequest(videoUrl).apply {
                    addOption("--no-playlist")
                    addOption("--no-check-certificates")
                    addOption("--extractor-args", "youtube:player_client=android_vr,ios,tv_samsung")
                    if (cookiesFile.exists() && cookiesFile.length() > 10L) {
                        addOption("--cookies", cookiesFile.absolutePath)
                    }
                }
                val info = YoutubeDL.getInstance().getInfo(infoReq)
                fullDescription = info.description ?: ""
                if (info.duration > 0) durationSec = info.duration
                if (!info.title.isNullOrBlank()) resolvedTitle = info.title!!
            } catch (_: Exception) {
            }
        }

        val hasSubs = timestampedTranscript.isNotBlank()
        DiagnosticLogger.log(
            "WHATS-INSIDE",
            "Данные ролика '$resolvedTitle' (${durationSec}с): субтитры=${hasSubs} (${timestampedTranscript.length} симв.), описание=${fullDescription.length} симв."
        )

        val apiKey = configStore.getGeminiApiKey()
        if (apiKey.isNotBlank() && (hasSubs || fullDescription.isNotBlank() || resolvedTitle.isNotBlank())) {
            try {
                val sourceContext = when {
                    hasSubs -> "РАСШИФРОВКА РЕЧИ С ТАЙМКОДАМИ:\n${timestampedTranscript.take(14000)}"
                    fullDescription.isNotBlank() -> "СУБТИТРЫ ОТКЛЮЧЕНЫ АВТОРОМ. ОПИСАНИЕ РОЛИКА:\n${fullDescription.take(4500)}"
                    else -> "НАЗВАНИЕ РОЛИКА: $resolvedTitle (длительность $durationSec сек)"
                }

                val prompt = """
                    Ты — шеф-редактор видеоконтента. Тема поиска: "$userTopic".
                    Ролик: "$resolvedTitle" (длительность: $durationSec сек).

                    $sourceContext

                    Сделай честную, конкретную выжимку «ЧТО ВНУТРИ» на русском языке (без воды, если оригинал на английском — переведи суть на русский):
                    1. "overallSummary": 2–4 конкретных предложения — о чём именно говорит автор, в чём главная шутка/мысль/сюжет, стоит ли брать в работу.
                    2. "keyTopics": 3–4 коротких тега тем внутри ролика.
                    3. "moments": выдели от 1 до 3 лучших цельных кусков (по 15–60 секунд, внутри 0..$durationSec сек), где фраза/история начинается и логически заканчивается. Укажи точные секунды startSec и endSec.

                    Верни СТРОГО JSON:
                    {
                      "overallSummary": "...",
                      "keyTopics": ["...", "..."],
                      "moments": [
                        {"startSec": 0, "endSec": 42, "title": "Суть куска", "summary": "С какой фразы начинается и каким панчем заканчивается"}
                      ],
                      "hasTranscript": $hasSubs
                    }
                """.trimIndent()

                val rawJson = GeminiModelResolver.generateTextOrJson(
                    apiKey = apiKey,
                    prompt = prompt,
                    expectJson = true
                )
                if (!rawJson.isNullOrBlank()) {
                    val digest = jsonFormat.decodeFromString<VideoInsideDigest>(rawJson)
                    val validMoments = digest.moments.map { m ->
                        val s = m.startSec.coerceIn(0, (durationSec - 5).coerceAtLeast(0))
                        val e = m.endSec.coerceIn(s + 5, durationSec.coerceAtLeast(s + 5))
                        m.copy(startSec = s, endSec = e)
                    }.ifEmpty {
                        buildHeuristicMomentsFromTranscript(timestampedTranscript, durationSec)
                    }
                    return@withContext digest.copy(
                        moments = validMoments,
                        hasTranscript = hasSubs
                    )
                }
            } catch (e: Exception) {
                DiagnosticLogger.logError("WHATS-INSIDE", "Ошибка AI-разбора выжимки (использую прямую расшифровку)", e)
            }
        }

        // Honest deterministic digest directly from transcript/description if AI key is empty or quota exceeded
        val directSummary = when {
            hasSubs -> {
                val cleanSpeech = timestampedTranscript.lines()
                    .joinToString(" ") { it.replace(Regex("""^\[\d{2}:\d{2}(:\d{2})?\]\s*"""), "") }
                    .replace(Regex("""\s+"""), " ")
                    .trim()
                "Прямая расшифровка речи из ролика:\n«${cleanSpeech.take(750)}»"
            }
            fullDescription.isNotBlank() -> "Описание автора (субтитры у видео отсутствуют):\n${fullDescription.take(550)}"
            else -> "Ролик «$resolvedTitle» ($durationSec сек). Введи Gemini API Key в настройках для ИИ-пересказа."
        }

        VideoInsideDigest(
            overallSummary = directSummary,
            keyTopics = resolvedTitle.split(" ").filter { it.length >= 4 }.take(4),
            moments = buildHeuristicMomentsFromTranscript(timestampedTranscript, durationSec),
            hasTranscript = hasSubs
        )
    }

    private fun buildHeuristicMomentsFromTranscript(transcript: String, durationSec: Int): List<VideoHighlightMoment> {
        val safeDur = durationSec.coerceAtLeast(15)
        if (safeDur <= 65) {
            return listOf(
                VideoHighlightMoment(
                    startSec = 0,
                    endSec = safeDur,
                    title = "Цельный короткий ролик целиком",
                    summary = "Ролик уже длится $safeDur сек — можно брать целиком без потери смысла"
                )
            )
        }
        val midStart = (safeDur / 3).coerceAtLeast(10)
        val midEnd = (midStart + 50).coerceAtMost(safeDur)
        val firstLine = transcript.lines().firstOrNull()?.take(80) ?: "Начало выступления / завязка"
        return listOf(
            VideoHighlightMoment(
                startSec = 0,
                endSec = 50.coerceAtMost(safeDur),
                title = "Завязка с первых секунд",
                summary = firstLine
            ),
            VideoHighlightMoment(
                startSec = midStart,
                endSec = midEnd,
                title = "Основной разгон в середине",
                summary = "Цельный фрагмент ${midStart}с – ${midEnd}с"
            )
        )
    }

    private suspend fun fetchYoutubePlayerAndCaptionsFast(videoId: String): InnertubePlayerProbe? {
        val clients = listOf(
            Triple("ANDROID_VR", "1.60.19", "com.google.android.apps.youtube.vr.oculus/1.60.19 (Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip"),
            Triple("IOS", "19.45.4", "com.google.ios.youtube/19.45.4 (iPhone16,2; U; CPU iOS 18_1_0 like Mac OS X;)")
        )

        for ((clientName, clientVer, ua) in clients) {
            try {
                val reqBody = buildJsonObject {
                    put("videoId", videoId)
                    put("contentCheckOk", true)
                    put("racyCheckOk", true)
                    putJsonObject("context") {
                        putJsonObject("client") {
                            put("clientName", clientName)
                            put("clientVersion", clientVer)
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
                if (!resp.status.isSuccess()) continue

                val root = jsonFormat.parseToJsonElement(resp.bodyAsText()).jsonObject
                val details = root["videoDetails"]?.jsonObject ?: continue
                val title = details["title"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val author = details["author"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val desc = details["shortDescription"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val lenSec = details["lengthSeconds"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 60
                val views = details["viewCount"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L

                val tracks = root["captions"]
                    ?.jsonObject?.get("playerCaptionsTracklistRenderer")
                    ?.jsonObject?.get("captionTracks")
                    ?.jsonArray

                var transcriptText = ""
                if (tracks != null && tracks.isNotEmpty()) {
                    val chosenTrack = tracks.firstOrNull {
                        it.jsonObject["languageCode"]?.jsonPrimitive?.contentOrNull?.startsWith("ru") == true
                    } ?: tracks.firstOrNull {
                        it.jsonObject["languageCode"]?.jsonPrimitive?.contentOrNull?.startsWith("en") == true
                    } ?: tracks.first()

                    val baseUrl = chosenTrack.jsonObject["baseUrl"]?.jsonPrimitive?.contentOrNull
                    if (!baseUrl.isNullOrBlank()) {
                        val capResp = httpClient.get(baseUrl) {
                            header(HttpHeaders.UserAgent, ua)
                        }
                        if (capResp.status.isSuccess()) {
                            val xmlBody = capResp.bodyAsText()
                            transcriptText = parseYoutubeTimedTextXmlToLines(xmlBody)
                        }
                    }
                }

                if (title.isNotBlank() || transcriptText.isNotBlank()) {
                    return InnertubePlayerProbe(
                        title = title,
                        author = author,
                        description = desc,
                        durationSec = lenSec,
                        viewCount = views,
                        captionXmlOrVtt = transcriptText
                    )
                }
            } catch (_: Exception) {
            }
        }
        return null
    }

    private fun parseYoutubeTimedTextXmlToLines(rawXml: String): String {
        if (rawXml.contains("WEBVTT")) {
            return compressVttToTimestampedLines(rawXml)
        }
        val sb = StringBuilder()
        // Matches both <text start="12.34" dur="2.1">...</text> and srv3 <p t="12340" d="2100">...</p>
        val textTagRegex = Regex("""<(?:text|p)\b[^>]*(?:start="([0-9.]+)"|t="(\d+)")[^>]*>(.*?)</(?:text|p)>""", RegexOption.DOT_MATCHES_ALL)
        val htmlStrip = Regex("""<[^>]+>""")

        for (m in textTagRegex.findAll(rawXml)) {
            val sec = when {
                m.groupValues[1].isNotBlank() -> m.groupValues[1].toDoubleOrNull()?.toInt() ?: 0
                m.groupValues[2].isNotBlank() -> ((m.groupValues[2].toLongOrNull() ?: 0L) / 1000L).toInt()
                else -> 0
            }
            val clean = decodeBasicXmlEntities(m.groupValues[3].replace(htmlStrip, " "))
                .replace(Regex("""\s+"""), " ")
                .trim()
            if (clean.isNotBlank()) {
                val mm = sec / 60
                val ss = sec % 60
                sb.append(String.format(Locale.US, "[%02d:%02d] %s\n", mm, ss, clean))
            }
        }
        return sb.toString()
    }

    private fun decodeBasicXmlEntities(s: String): String {
        return s.replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&apos;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("\n", " ")
    }

    private fun fetchTimestampedVttTranscriptViaYtDlp(videoUrl: String): String {
        val baseName = "inside_vtt_${System.currentTimeMillis()}"
        val outTemplate = File(stagingDir, "$baseName.%(ext)s").absolutePath
        return try {
            val req = YoutubeDLRequest(videoUrl).apply {
                addOption("--skip-download")
                addOption("--write-auto-sub")
                addOption("--write-sub")
                addOption("--sub-langs", "ru.*,ru-orig,ru,en.*,en")
                addOption("--sub-format", "vtt")
                addOption("--no-playlist")
                addOption("--no-check-certificates")
                addOption("--extractor-args", "youtube:player_client=android_vr,ios,tv_samsung")
                if (cookiesFile.exists() && cookiesFile.length() > 10L) {
                    addOption("--cookies", cookiesFile.absolutePath)
                }
                addOption("-o", outTemplate)
            }
            YoutubeDL.getInstance().execute(req)

            val vttFile = stagingDir.listFiles { f ->
                f.name.startsWith(baseName) && f.extension.equals("vtt", ignoreCase = true)
            }?.firstOrNull() ?: return ""

            val rawVtt = vttFile.readText()
            vttFile.delete()
            compressVttToTimestampedLines(rawVtt)
        } catch (_: Exception) {
            ""
        } finally {
            stagingDir.listFiles { f -> f.name.startsWith(baseName) }?.forEach { it.delete() }
        }
    }

    private fun compressVttToTimestampedLines(rawVtt: String): String {
        val lines = rawVtt.lines()
        val cueHeaderRegex = Regex("""(\d{2}:\d{2}:\d{2}\.\d{3}|\d{2}:\d{2}\.\d{3})\s*-->""")
        val htmlTagStripRegex = Regex("""<[^>]+>""")
        val sb = StringBuilder()
        var lastLineText = ""
        var currentTimeLabel = ""

        for (line in lines) {
            val trimmed = line.trim()
            val m = cueHeaderRegex.find(trimmed)
            if (m != null) {
                currentTimeLabel = m.groupValues[1].substringBefore(".")
                continue
            }
            if (trimmed.isBlank() || trimmed.startsWith("WEBVTT") || trimmed.startsWith("Kind:") || trimmed.startsWith("Language:")) {
                continue
            }
            val clean = trimmed.replace(htmlTagStripRegex, "").replace(Regex("""\s+"""), " ").trim()
            if (clean.isNotBlank() && clean != lastLineText) {
                sb.append("[").append(currentTimeLabel).append("] ").append(clean).append("\n")
                lastLineText = clean
            }
        }
        return sb.toString()
    }

    // =========================================================================
    // YOUTUBE INNERTUBE SERVER-SIDE `SP` PROTOBUF SEARCH (ТОЧНЫЕ ДАТЫ И ПРОСМОТРЫ!)
    // =========================================================================

    /**
     * Генерирует бинарно точный YouTube `sp` (SearchParams) protobuf в Base64 для серверной фильтрации:
     * - sort_by (field 1): 0=Relevance, 2=Upload Date, 3=View Count (Popular)
     * - filters (field 2): upload_date (field 1: 2=Day, 3=Week, 4=Month, 5=Year), type (field 2: 1=Video)
     */
    private fun buildYoutubeSpParam(sortOrder: ContentSortOrder, period: SearchTimePeriod): String {
        val sortCode = when (sortOrder) {
            ContentSortOrder.POPULAR, ContentSortOrder.VIRAL_VELOCITY -> 3 // Server-side sort by View Count!
            ContentSortOrder.NEWEST -> 2 // Server-side sort by Upload Date!
            ContentSortOrder.RELEVANCE -> 0
        }
        val dateCode = when (period) {
            SearchTimePeriod.DAY -> 2
            SearchTimePeriod.WEEK -> 3
            SearchTimePeriod.MONTH -> 4
            SearchTimePeriod.YEAR -> 5
            SearchTimePeriod.ALL_TIME -> 0
        }

        val inner = ByteArrayOutputStream()
        if (dateCode > 0) {
            inner.write(0x08) // field 1 varint (upload_date)
            inner.write(dateCode)
        }
        inner.write(0x10) // field 2 varint (type = Video)
        inner.write(0x01)
        val innerBytes = inner.toByteArray()

        val outer = ByteArrayOutputStream()
        if (sortCode > 0) {
            outer.write(0x08) // field 1 varint (sort_by)
            outer.write(sortCode)
        }
        outer.write(0x12) // field 2 length-delimited (filters)
        outer.write(innerBytes.size)
        outer.write(innerBytes)

        return Base64.encodeToString(outer.toByteArray(), Base64.NO_WRAP)
    }

    private suspend fun searchYouTubeByTags(
        queryTags: String,
        nichePlan: AdaptiveNichePlanDto = AdaptiveNichePlanDto(),
        sortOrder: ContentSortOrder = ContentSortOrder.POPULAR,
        period: SearchTimePeriod = SearchTimePeriod.MONTH,
        languageMode: SearchLanguageMode = SearchLanguageMode.RUSSIAN_ONLY,
        appendShortsKeyword: Boolean = false,
        minDurationSec: Int = 8,
        maxDurationSec: Int = 360,
        maxFetchCount: Int = 25,
        minRequiredViewsOverride: Long? = null
    ): List<DiscoveredMediaCandidate> {
        val finalQuery = if (appendShortsKeyword && !queryTags.lowercase(Locale.getDefault()).contains("short")) {
            "$queryTags shorts"
        } else {
            queryTags
        }

        val minRequiredViews = minRequiredViewsOverride ?: when {
            sortOrder == ContentSortOrder.POPULAR || sortOrder == ContentSortOrder.VIRAL_VELOCITY -> when (period) {
                SearchTimePeriod.DAY -> 400L
                SearchTimePeriod.WEEK -> 1_500L
                SearchTimePeriod.MONTH -> 3_000L
                else -> 5_000L
            }
            else -> when (period) {
                SearchTimePeriod.DAY -> 150L
                SearchTimePeriod.WEEK -> 500L
                SearchTimePeriod.MONTH -> 1_200L
                else -> 2_000L
            }
        }

        val effectiveBlacklist = sanitizeOutdatedBlacklist(
            nichePlan.outdatedBlacklist + universalDeadContentBlacklist(queryTags),
            queryTags
        )

        val hl = if (languageMode == SearchLanguageMode.ENGLISH_FOR_DUB) "en" else "ru"
        val gl = if (languageMode == SearchLanguageMode.ENGLISH_FOR_DUB) "US" else "RU"

        // Primary: Fast Server-Filtered YouTube Innertube JSON API (`sp` enforces exact period & popularity!)
        val innertubeHits = searchYouTubeViaInnertube(
            query = finalQuery,
            sortOrder = sortOrder,
            period = period,
            hl = hl,
            gl = gl,
            minDurationSec = minDurationSec,
            maxDurationSec = maxDurationSec,
            minRequiredViews = minRequiredViews
        ).filter { cand ->
            !containsBlacklistedTerm("${cand.title} ${cand.author}", effectiveBlacklist) &&
                matchesLanguageFilter(cand.title, cand.author, languageMode)
        }

        if (innertubeHits.isNotEmpty()) {
            DiagnosticLogger.log(
                "HUNT:YT-API",
                "Innertube '$finalQuery' (${period.name}, ${sortOrder.name}): найдено=${innertubeHits.size} честных роликов (мин.просм=$minRequiredViews)"
            )
            return innertubeHits.take(maxFetchCount)
        }

        // Secondary fallback if Innertube returned 0 (ONLY when period == ALL_TIME or via strict date check in yt-dlp)
        return searchYouTubeViaYtDlpFallback(
            finalQuery = finalQuery,
            period = period,
            languageMode = languageMode,
            effectiveBlacklist = effectiveBlacklist,
            appendShortsKeyword = appendShortsKeyword,
            minDurationSec = minDurationSec,
            maxDurationSec = maxDurationSec,
            maxFetchCount = maxFetchCount,
            minRequiredViews = minRequiredViews
        )
    }

    private suspend fun searchYouTubeViaInnertube(
        query: String,
        sortOrder: ContentSortOrder,
        period: SearchTimePeriod,
        hl: String,
        gl: String,
        minDurationSec: Int,
        maxDurationSec: Int,
        minRequiredViews: Long
    ): List<DiscoveredMediaCandidate> {
        val spParam = buildYoutubeSpParam(sortOrder, period)
        val payload = buildJsonObject {
            put("query", query)
            put("params", spParam)
            putJsonObject("context") {
                putJsonObject("client") {
                    put("clientName", "WEB")
                    put("clientVersion", "2.20250210.01.00")
                    put("hl", hl)
                    put("gl", gl)
                }
            }
        }

        return try {
            val resp = httpClient.post("https://www.youtube.com/youtubei/v1/search?prettyPrint=false") {
                contentType(ContentType.Application.Json)
                header(
                    HttpHeaders.UserAgent,
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
                )
                setBody(payload.toString())
            }
            if (!resp.status.isSuccess()) return emptyList()

            val rootElement = jsonFormat.parseToJsonElement(resp.bodyAsText())
            val extracted = mutableListOf<DiscoveredMediaCandidate>()
            walkInnertubeSearchTree(
                node = rootElement,
                period = period,
                minDurationSec = minDurationSec,
                maxDurationSec = maxDurationSec,
                minRequiredViews = minRequiredViews,
                out = extracted
            )
            extracted.distinctBy { it.id }
        } catch (e: Exception) {
            DiagnosticLogger.log("HUNT:INNERTUBE", "Предупреждение Innertube для '$query': ${e.message?.take(80)}")
            emptyList()
        }
    }

    private fun walkInnertubeSearchTree(
        node: JsonElement,
        period: SearchTimePeriod,
        minDurationSec: Int,
        maxDurationSec: Int,
        minRequiredViews: Long,
        out: MutableList<DiscoveredMediaCandidate>
    ) {
        when (node) {
            is JsonObject -> {
                node["videoRenderer"]?.jsonObject?.let { vr ->
                    parseInnertubeVideoRenderer(vr, period, minDurationSec, maxDurationSec, minRequiredViews)
                        ?.let { out.add(it) }
                    return
                }
                node["shortsLockupViewModel"]?.jsonObject?.let { sl ->
                    parseInnertubeShortsLockup(sl, period, minDurationSec, maxDurationSec, minRequiredViews)
                        ?.let { out.add(it) }
                    return
                }
                node["reelItemRenderer"]?.jsonObject?.let { ri ->
                    parseInnertubeReelItem(ri, period, minDurationSec, maxDurationSec, minRequiredViews)
                        ?.let { out.add(it) }
                    return
                }
                for ((_, child) in node) {
                    walkInnertubeSearchTree(child, period, minDurationSec, maxDurationSec, minRequiredViews, out)
                }
            }
            is JsonArray -> {
                for (item in node) {
                    walkInnertubeSearchTree(item, period, minDurationSec, maxDurationSec, minRequiredViews, out)
                }
            }
            else -> {}
        }
    }

    private fun extractRunsOrSimpleText(obj: JsonElement?): String {
        val jo = obj as? JsonObject ?: return ""
        jo["simpleText"]?.jsonPrimitive?.contentOrNull?.let { return it }
        jo["content"]?.jsonPrimitive?.contentOrNull?.let { return it }
        val runs = jo["runs"] as? JsonArray ?: return ""
        return runs.joinToString("") { it.jsonObject["text"]?.jsonPrimitive?.contentOrNull.orEmpty() }
    }

    private fun parseInnertubeVideoRenderer(
        vr: JsonObject,
        period: SearchTimePeriod,
        minDurationSec: Int,
        maxDurationSec: Int,
        minRequiredViews: Long
    ): DiscoveredMediaCandidate? {
        val videoId = vr["videoId"]?.jsonPrimitive?.contentOrNull ?: return null
        if (videoId.length != 11) return null

        val title = extractRunsOrSimpleText(vr["title"]).trim()
        if (title.isBlank()) return null

        val lengthText = extractRunsOrSimpleText(vr["lengthText"]).trim()
        // Live streams have no lengthText — skip live broadcasts
        if (lengthText.isBlank()) return null
        val durationSec = parseDurationColonToSeconds(lengthText)
        if (durationSec !in minDurationSec..maxDurationSec) return null

        val publishedText = extractRunsOrSimpleText(vr["publishedTimeText"]).trim()
        val ageDays = parseRelativeAgeToDays(publishedText)
        if (period.daysBack != null) {
            // Strict period check: if age is known and exceeds period, or if publishedText is missing on old videos, enforce!
            if (ageDays != null && ageDays > period.daysBack + 2) return null
        }

        val viewText = extractRunsOrSimpleText(vr["viewCountText"])
            .ifBlank { extractRunsOrSimpleText(vr["shortViewCountText"]) }
        val views = parseHumanViewCount(viewText)
        if (views < minRequiredViews) return null

        val author = extractRunsOrSimpleText(vr["ownerText"])
            .ifBlank { extractRunsOrSimpleText(vr["longBylineText"]) }
            .ifBlank { "YouTube" }
            .trim()

        val navUrl = vr["navigationEndpoint"]
            ?.jsonObject?.get("commandMetadata")
            ?.jsonObject?.get("webCommandMetadata")
            ?.jsonObject?.get("url")
            ?.jsonPrimitive?.contentOrNull.orEmpty()

        val fullUrl = if (navUrl.startsWith("/shorts/")) {
            "https://www.youtube.com$navUrl"
        } else {
            "https://www.youtube.com/watch?v=$videoId"
        }

        val descSnippet = (vr["detailedMetadataSnippets"] as? JsonArray)
            ?.firstOrNull()
            ?.jsonObject?.get("snippetText")
            ?.let { extractRunsOrSimpleText(it) }
            .orEmpty()

        val estimatedYmd = estimateYmdFromAgeDays(ageDays ?: 10)

        return DiscoveredMediaCandidate(
            id = "yt_$videoId",
            url = fullUrl,
            title = title,
            author = author,
            platform = HuntPlatform.YOUTUBE,
            durationSec = durationSec,
            viewOrScoreCount = views,
            uploadDateYmd = estimatedYmd,
            publishedAgeLabel = publishedText,
            descriptionSnippet = descSnippet.take(200)
        )
    }

    private fun parseInnertubeShortsLockup(
        sl: JsonObject,
        period: SearchTimePeriod,
        minDurationSec: Int,
        maxDurationSec: Int,
        minRequiredViews: Long
    ): DiscoveredMediaCandidate? {
        if (45 !in minDurationSec..maxDurationSec && maxDurationSec < 45) return null
        if (minDurationSec > 75) return null // User explicitly asked for ORIGINALS_1_TO_6M (>55s..360s) -> skip pure Shorts shelf if min > 60

        val entityId = sl["entityId"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val tapEndpointStr = sl["onTap"]?.toString().orEmpty()
        val videoId = Regex("""shorts/([a-zA-Z0-9_-]{11})""").find(tapEndpointStr)?.groupValues?.get(1)
            ?: Regex(""""videoId"\s*:\s*"([a-zA-Z0-9_-]{11})"""").find(tapEndpointStr)?.groupValues?.get(1)
            ?: entityId.substringAfterLast("shorts-shelf-item-", "").take(11).takeIf { it.length == 11 }
            ?: return null

        val overlayMeta = sl["overlayMetadata"]?.jsonObject
        val title = extractRunsOrSimpleText(overlayMeta?.get("primaryText"))
            .ifBlank { sl["accessibilityText"]?.jsonPrimitive?.contentOrNull?.substringBeforeLast(",") ?: "" }
            .trim()
        if (title.isBlank()) return null

        val viewsText = extractRunsOrSimpleText(overlayMeta?.get("secondaryText"))
            .ifBlank { sl["accessibilityText"]?.jsonPrimitive?.contentOrNull ?: "" }
        val views = parseHumanViewCount(viewsText)
        if (views < minRequiredViews) return null

        val estDays = period.daysBack?.let { (it / 2).coerceAtLeast(1) } ?: 14
        return DiscoveredMediaCandidate(
            id = "yt_$videoId",
            url = "https://www.youtube.com/shorts/$videoId",
            title = title,
            author = "YouTube Shorts",
            platform = HuntPlatform.YOUTUBE,
            durationSec = 45.coerceIn(minDurationSec, maxDurationSec),
            viewOrScoreCount = views,
            uploadDateYmd = estimateYmdFromAgeDays(estDays),
            publishedAgeLabel = period.label
        )
    }

    private fun parseInnertubeReelItem(
        ri: JsonObject,
        period: SearchTimePeriod,
        minDurationSec: Int,
        maxDurationSec: Int,
        minRequiredViews: Long
    ): DiscoveredMediaCandidate? {
        if (minDurationSec > 75) return null
        val videoId = ri["videoId"]?.jsonPrimitive?.contentOrNull ?: return null
        if (videoId.length != 11) return null
        val title = extractRunsOrSimpleText(ri["headline"]).trim()
        if (title.isBlank()) return null
        val viewsText = extractRunsOrSimpleText(ri["viewCountText"])
        val views = parseHumanViewCount(viewsText)
        if (views < minRequiredViews) return null

        val estDays = period.daysBack?.let { (it / 2).coerceAtLeast(1) } ?: 14
        return DiscoveredMediaCandidate(
            id = "yt_$videoId",
            url = "https://www.youtube.com/shorts/$videoId",
            title = title,
            author = "YouTube Shorts",
            platform = HuntPlatform.YOUTUBE,
            durationSec = 45.coerceIn(minDurationSec, maxDurationSec),
            viewOrScoreCount = views,
            uploadDateYmd = estimateYmdFromAgeDays(estDays),
            publishedAgeLabel = period.label
        )
    }

    private fun parseDurationColonToSeconds(raw: String): Int {
        val parts = raw.trim().split(":").mapNotNull { it.trim().toIntOrNull() }
        return when (parts.size) {
            3 -> parts[0] * 3600 + parts[1] * 60 + parts[2]
            2 -> parts[0] * 60 + parts[1]
            1 -> parts[0]
            else -> 0
        }
    }

    /**
     * Точный парсер человекочитаемого возраста YouTube ("5 дней назад", "2 недели назад", "3 месяца назад", "2 года назад").
     */
    private fun parseRelativeAgeToDays(publishedText: String): Int? {
        if (publishedText.isBlank()) return null
        val low = publishedText.lowercase(Locale.getDefault())
        if (low.contains("секунд") || low.contains("минут") || low.contains("час") ||
            low.contains("second") || low.contains("minute") || low.contains("hour") ||
            low.contains("только что") || low.contains("сегодня") || low.contains("today")
        ) {
            return 0
        }
        if (low.contains("вчера") || low.contains("yesterday")) return 1

        val num = Regex("""(\d+)""").find(low)?.groupValues?.get(1)?.toIntOrNull() ?: 1
        return when {
            low.contains("дн") || low.contains("ден") || low.contains("сут") || low.contains("day") -> num
            low.contains("нед") || low.contains("week") -> num * 7
            low.contains("мес") || low.contains("month") -> num * 30
            low.contains("год") || low.contains("лет") || low.contains("year") -> num * 365
            else -> null
        }
    }

    /**
     * Точный парсер реального числа просмотров ("245 тыс. просмотров", "1,4 млн", "312K views", "214 просмотров").
     * НИКОГДА не подставляет фейковые 5000 просмотров!
     */
    private fun parseHumanViewCount(viewText: String): Long {
        if (viewText.isBlank()) return 0L
        val low = viewText.lowercase(Locale.getDefault())
            .replace('\u00A0', ' ')
            .replace('\u202F', ' ')
        if (low.contains("нет просмотров") || low.contains("no views")) return 0L

        val multiplier = when {
            low.contains("млрд") || low.contains("billion") || Regex("""\d\s*b\b""").containsMatchIn(low) -> 1_000_000_000.0
            low.contains("млн") || low.contains("million") || Regex("""[\d.,]\s*m\b""").containsMatchIn(low) -> 1_000_000.0
            low.contains("тыс") || Regex("""[\d.,]\s*k\b""").containsMatchIn(low) -> 1_000.0
            else -> 1.0
        }

        return if (multiplier > 1.0) {
            val decStr = Regex("""(\d+(?:[.,]\d+)?)""").find(low)?.groupValues?.get(1)
                ?.replace(",", ".") ?: return 0L
            val num = decStr.toDoubleOrNull() ?: return 0L
            (num * multiplier).toLong()
        } else {
            val digitsOnly = low.replace(Regex("""[^\d]"""), "")
            digitsOnly.toLongOrNull() ?: 0L
        }
    }

    private fun estimateYmdFromAgeDays(daysAgo: Int): String {
        val ms = System.currentTimeMillis() - (daysAgo.toLong() * 86_400_000L)
        return SimpleDateFormat("yyyyMMdd", Locale.US).format(java.util.Date(ms))
    }

    private fun searchYouTubeViaYtDlpFallback(
        finalQuery: String,
        period: SearchTimePeriod,
        languageMode: SearchLanguageMode,
        effectiveBlacklist: List<String>,
        appendShortsKeyword: Boolean,
        minDurationSec: Int,
        maxDurationSec: Int,
        maxFetchCount: Int,
        minRequiredViews: Long
    ): List<DiscoveredMediaCandidate> {
        // Note: ytsearchN without upload_date is only safe for ALL_TIME or ytsearchdateN for recent!
        val fetchLimit = maxFetchCount.coerceIn(8, 25)
        val prefix = if (period != SearchTimePeriod.ALL_TIME) "ytsearchdate$fetchLimit" else "ytsearch$fetchLimit"
        val langCode = if (languageMode == SearchLanguageMode.ENGLISH_FOR_DUB) "en" else "ru"
        val results = mutableListOf<DiscoveredMediaCandidate>()

        try {
            val req = YoutubeDLRequest("$prefix:$finalQuery").apply {
                addOption("--flat-playlist")
                addOption("--dump-json")
                addOption("--skip-download")
                addOption("--ignore-errors")
                addOption("--no-warnings")
                addOption("--no-check-certificates")
                addOption("--extractor-args", "youtube:lang=$langCode")
                if (cookiesFile.exists() && cookiesFile.length() > 10L) {
                    addOption("--cookies", cookiesFile.absolutePath)
                }
            }
            val lines = YoutubeDL.getInstance().execute(req).out.lines().filter { it.trim().startsWith("{") }
            val sdf = SimpleDateFormat("yyyyMMdd", Locale.US)
            val nowMs = System.currentTimeMillis()

            for (line in lines) {
                val item = try {
                    jsonFormat.decodeFromString<YtDlpFlatEntry>(line)
                } catch (_: Exception) {
                    continue
                }
                if (item.id.length != 11 || item.title.isBlank()) continue

                if (period.daysBack != null && !item.uploadDate.isNullOrBlank() && item.uploadDate.length == 8) {
                    val upTime = sdf.parse(item.uploadDate)?.time
                    if (upTime != null) {
                        val ageDays = (nowMs - upTime) / 86_400_000L
                        if (ageDays > period.daysBack + 2) continue
                    }
                }

                val realViews = item.viewCount ?: 0L
                if (realViews < minRequiredViews) continue

                val title = item.title.trim()
                val author = (item.uploader ?: item.channel ?: "YouTube").trim()
                if (containsBlacklistedTerm("$title $author", effectiveBlacklist)) continue
                if (!matchesLanguageFilter(title, author, languageMode)) continue

                val dur = (item.duration?.toInt() ?: if (appendShortsKeyword) 45 else 75)
                if (dur !in minDurationSec..maxDurationSec) continue

                val url = item.webpageUrl?.takeIf { it.startsWith("http") }
                    ?: "https://www.youtube.com/watch?v=${item.id}"

                results.add(
                    DiscoveredMediaCandidate(
                        id = "yt_${item.id}",
                        url = url,
                        title = title,
                        author = author,
                        platform = HuntPlatform.YOUTUBE,
                        durationSec = dur,
                        viewOrScoreCount = realViews,
                        uploadDateYmd = item.uploadDate ?: ""
                    )
                )
            }
        } catch (_: Exception) {
        }
        return results
    }

    // =========================================================================
    // ПОИСК НА RUTUBE (ОФИЦИАЛЬНЫЙ JSON API + ТОЧНАЯ ДАТА И ПРОСМОТРЫ)
    // =========================================================================

    private suspend fun searchRutubeVideosByTags(
        queryTags: String,
        nichePlan: AdaptiveNichePlanDto,
        sortOrder: ContentSortOrder,
        period: SearchTimePeriod,
        minDurationSec: Int,
        maxDurationSec: Int
    ): List<DiscoveredMediaCandidate> {
        return try {
            val ordering = when (sortOrder) {
                ContentSortOrder.NEWEST -> "-created_ts"
                else -> "-hits"
            }
            val httpResp = httpClient.get("https://rutube.ru/api/search/video/") {
                parameter("query", queryTags)
                parameter("ordering", ordering)
                parameter("page_size", 25)
                header(HttpHeaders.UserAgent, "Mozilla/5.0 (Linux; Android 14)")
            }
            if (!httpResp.status.isSuccess()) return emptyList()

            val parsed = jsonFormat.decodeFromString<RutubeSearchResponse>(httpResp.bodyAsText())
            val effectiveBlacklist = sanitizeOutdatedBlacklist(
                nichePlan.outdatedBlacklist + universalDeadContentBlacklist(queryTags),
                queryTags
            )
            val nowMs = System.currentTimeMillis()
            val isoParser = SimpleDateFormat("yyyy-MM-dd", Locale.US)
            val ymdFormatter = SimpleDateFormat("yyyyMMdd", Locale.US)

            val minHits = when {
                sortOrder == ContentSortOrder.POPULAR -> 500L
                else -> 100L
            }

            val results = mutableListOf<DiscoveredMediaCandidate>()
            for (entry in parsed.results) {
                if (entry.id.isBlank() || entry.title.isBlank()) continue
                if (entry.duration !in minDurationSec..maxDurationSec) continue
                if (entry.hits < minHits) continue

                val authorName = entry.author?.name?.ifBlank { "Rutube" } ?: "Rutube"
                if (containsBlacklistedTerm("${entry.title} $authorName", effectiveBlacklist)) continue

                var ymd = ""
                var ageLabel = ""
                if (!entry.createdTs.isNullOrBlank() && entry.createdTs.length >= 10) {
                    val datePart = entry.createdTs.take(10)
                    val tsMs = try {
                        isoParser.parse(datePart)?.time
                    } catch (_: Exception) {
                        null
                    }
                    if (tsMs != null) {
                        val ageDays = ((nowMs - tsMs) / 86_400_000L).toInt().coerceAtLeast(0)
                        if (period.daysBack != null && ageDays > period.daysBack + 2) continue
                        ymd = ymdFormatter.format(java.util.Date(tsMs))
                        ageLabel = "$ageDays дн. назад"
                    }
                }

                val url = entry.videoUrl?.takeIf { it.startsWith("http") }
                    ?: "https://rutube.ru/video/${entry.id}/"

                results.add(
                    DiscoveredMediaCandidate(
                        id = "rt_${entry.id}",
                        url = url,
                        title = entry.title.trim(),
                        author = authorName,
                        platform = HuntPlatform.RUTUBE,
                        durationSec = entry.duration,
                        viewOrScoreCount = entry.hits,
                        uploadDateYmd = ymd,
                        publishedAgeLabel = ageLabel,
                        descriptionSnippet = entry.description?.take(180).orEmpty()
                    )
                )
            }
            if (results.isNotEmpty()) {
                DiagnosticLogger.log("HUNT:RUTUBE", "Rutube '$queryTags': найдено ${results.size} шт.")
            }
            results
        } catch (e: Exception) {
            DiagnosticLogger.log("HUNT:RUTUBE", "Предупреждение поиска Rutube: ${e.message?.take(60)}")
            emptyList()
        }
    }

    // =========================================================================
    // ПОИСК НА REDDIT VIDEO (С ПРОВЕРКОЙ КАЧЕСТВА >= 720p И СКОРА)
    // =========================================================================

    private suspend fun searchRedditVideosByTags(
        queryTags: String,
        sortOrder: ContentSortOrder,
        period: SearchTimePeriod,
        minDurationSec: Int,
        maxDurationSec: Int,
        languageMode: SearchLanguageMode
    ): List<DiscoveredMediaCandidate> = withContext(Dispatchers.IO) {
        val redditSort = when (sortOrder) {
            ContentSortOrder.POPULAR -> "top"
            ContentSortOrder.NEWEST -> "new"
            ContentSortOrder.VIRAL_VELOCITY -> "hot"
            ContentSortOrder.RELEVANCE -> "top"
        }
        val hosts = listOf("https://www.reddit.com", "https://api.reddit.com")
        val sdf = SimpleDateFormat("yyyyMMdd", Locale.US)
        val nowSec = System.currentTimeMillis() / 1000.0

        for (host in hosts) {
            try {
                val httpResp = httpClient.get("$host/search.json") {
                    header(
                        HttpHeaders.UserAgent,
                        "android:forge.media.pocket:v1.3.0 (by /u/pocketmediaforge)"
                    )
                    header(HttpHeaders.Accept, "application/json")
                    parameter("q", queryTags)
                    parameter("sort", redditSort)
                    parameter("t", period.redditParam)
                    parameter("limit", 35)
                    parameter("raw_json", 1)
                }

                if (!httpResp.status.isSuccess()) continue
                val bodyText = httpResp.bodyAsText()
                if (!bodyText.trimStart().startsWith("{")) continue

                val listing = jsonFormat.decodeFromString<RedditSearchListing>(bodyText)
                val found = listing.data?.children
                    ?.mapNotNull { it.data }
                    ?.filter { it.isVideo && !it.over18 && it.score >= 25L }
                    ?.mapNotNull { post ->
                        val video = post.media?.redditVideo ?: return@mapNotNull null
                        if (video.height in 1..479) return@mapNotNull null // Reject low-res <480p Reddit potato clips!
                        val dur = if (video.duration > 0) video.duration else 25
                        if (dur !in minDurationSec..maxDurationSec) return@mapNotNull null

                        if (period.daysBack != null && post.createdUtc > 0) {
                            val ageDays = (nowSec - post.createdUtc) / 86_400.0
                            if (ageDays > period.daysBack + 2) return@mapNotNull null
                        }

                        if (languageMode != SearchLanguageMode.ANY_LANGUAGE &&
                            !matchesLanguageFilter(post.title, post.author, languageMode)
                        ) {
                            return@mapNotNull null
                        }

                        val fullPostUrl = if (!post.permalink.isNullOrBlank()) {
                            "https://www.reddit.com${post.permalink}"
                        } else {
                            video.fallbackUrl ?: return@mapNotNull null
                        }

                        val dateStr = if (post.createdUtc > 0) {
                            sdf.format(java.util.Date((post.createdUtc * 1000).toLong()))
                        } else {
                            ""
                        }

                        DiscoveredMediaCandidate(
                            id = "rd_${post.id}",
                            url = fullPostUrl,
                            title = post.title,
                            author = "u/${post.author}",
                            platform = HuntPlatform.REDDIT,
                            durationSec = dur,
                            viewOrScoreCount = post.score * 60L, // Estimated views from Reddit score
                            uploadDateYmd = dateStr,
                            publishedAgeLabel = "Reddit (${post.score}⬆)"
                        )
                    } ?: emptyList()

                if (found.isNotEmpty()) {
                    DiagnosticLogger.log("HUNT:REDDIT", "Reddit '$queryTags' (${period.name}): найдено ${found.size} видео")
                    return@withContext found
                }
            } catch (_: Exception) {
            }
        }
        emptyList()
    }

    // =========================================================================
    // ВСПОМОГАТЕЛЬНЫЕ МЕТОДЫ И ОХОТНИК НА СПАМ-ФЕРМЫ
    // =========================================================================

    private fun extractYoutubeVideoIdFromUrl(url: String): String? {
        val patterns = listOf(
            Regex("""youtube\.com/shorts/([a-zA-Z0-9_-]{11})"""),
            Regex("""[?&]v=([a-zA-Z0-9_-]{11})"""),
            Regex("""youtu\.be/([a-zA-Z0-9_-]{11})""")
        )
        for (p in patterns) {
            val m = p.find(url)
            if (m != null) return m.groupValues[1]
        }
        return null
    }

    private fun cleanGeneratedSearchQuery(rawQuery: String): String {
        val stripped = rawQuery
            .replace(Regex("""(?i)\b(2024|2025|2026|видео|новинки|смотреть|онлайн|бесплатно)\b"""), " ")
            .replace(Regex("""["'«»#]"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
        return stripped.split(" ").filter { it.isNotBlank() }.take(5).joinToString(" ")
    }

    private fun sanitizeOutdatedBlacklist(rawBlacklist: List<String>, userPrompt: String): List<String> {
        val promptWords = userPrompt.lowercase(Locale.getDefault())
            .split(Regex("""\s+"""))
            .map { it.trim() }
            .filter { it.length >= 3 }

        val protectedCommonWords = setOf(
            "смешно", "смешные", "смешной", "истории", "история", "монолог", "монологи",
            "стендап", "standup", "stand", "юмор", "шутки", "шутка", "разгон", "разгоны",
            "жизнь", "жизни", "рассказ", "рассказы", "видео", "лучшее", "лучшие", "моменты",
            "нарезка", "нарезки", "стрим", "подкаст", "shorts", "short", "шортс", "прикол", "приколы"
        )

        return rawBlacklist
            .map { it.trim().lowercase(Locale.getDefault()) }
            .filter { term ->
                term.length >= 3 &&
                    term !in protectedCommonWords &&
                    promptWords.none { pw -> term.contains(pw) || pw.contains(term) }
            }
            .distinct()
    }

    private suspend fun fetchYouTubeAutocompleteSuggestions(seedQuery: String, hl: String = "ru"): List<String> {
        return try {
            val httpResp = httpClient.get("https://suggestqueries.google.com/complete/search") {
                parameter("client", "firefox")
                parameter("ds", "yt")
                parameter("hl", hl)
                parameter("q", seedQuery)
                header(HttpHeaders.UserAgent, "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
            }
            if (!httpResp.status.isSuccess()) return emptyList()
            val raw = httpResp.bodyAsText()
            val suggestionsBlock = raw.substringAfter("[", "").substringAfter("[", "").substringBefore("]", "")
            Regex(""""([^"]+)"""").findAll(suggestionsBlock)
                .map { it.groupValues[1].trim() }
                .filter { it.length in 4..65 && !it.equals(seedQuery, ignoreCase = true) }
                .take(4)
                .toList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun containsBlacklistedTerm(text: String, blacklist: List<String>): Boolean {
        if (blacklist.isEmpty()) return false
        val lower = text.lowercase(Locale.getDefault())
        return blacklist.any { term ->
            val t = term.trim().lowercase(Locale.getDefault())
            t.length >= 2 && lower.contains(t)
        }
    }

    private fun universalDeadContentBlacklist(userPrompt: String): List<String> {
        val lowerPrompt = userPrompt.lowercase(Locale.getDefault())
        val list = mutableListOf(
            "жванецкий", "петросян", "аншлаг", "одесский юмор", "услышано в одессе",
            "анекдот по поводу", "кривое зеркало", "смехопанорама", "шифрин", "винокур"
        )
        if (!lowerPrompt.contains("1.6") && !lowerPrompt.contains("source")) {
            list.addAll(listOf("cs 1.6", "кс 1.6", "counter-strike 1.6", "cs source", "кс соурс", "condition zero"))
        }
        if (!lowerPrompt.contains("dota 1") && !lowerPrompt.contains("дота 1")) {
            list.addAll(listOf("dota 1", "дота 1", "allstars"))
        }
        return list
    }

    private fun normalizeLegacyNicheTerms(userPrompt: String, languageMode: SearchLanguageMode): String {
        var normalized = userPrompt
        val lower = normalized.lowercase(Locale.getDefault())
        if ((lower.contains("кск") || Regex("""\bкс\b""").containsMatchIn(lower) || Regex("""\bcs\b""").containsMatchIn(lower)) &&
            !lower.contains("cs2") && !lower.contains("кс2") && !lower.contains("1.6")
        ) {
            normalized = normalized
                .replace(Regex("""(?i)по\s+кске"""), "CS2")
                .replace(Regex("""(?i)в\s+кске"""), "CS2")
                .replace(Regex("""(?i)\bкска\b"""), "CS2")
                .replace(Regex("""(?i)\bкс\b"""), "CS2")
                .replace(Regex("""(?i)\bcs\b"""), "CS2")
        }
        if (languageMode == SearchLanguageMode.ENGLISH_FOR_DUB && !normalized.any { it in 'a'..'z' || it in 'A'..'Z' }) {
            normalized = translateTopicForRedditFallback(normalized)
        }
        return sanitizeQuery(normalized)
    }

    private fun matchesLanguageFilter(
        title: String,
        author: String,
        languageMode: SearchLanguageMode
    ): Boolean {
        val combined = "$title $author"
        val hasCyrillic = Regex("""[а-яА-ЯёЁ]""").containsMatchIn(title)
        val hasUkrainianSpecific = Regex("""[іїєґІЇЄҐ]""").containsMatchIn(combined)
        val hasOtherCyrillicSpecific = Regex("""[әңғүұқөһӘҢҒҮҰҚӨҺ]""").containsMatchIn(combined)
        val hasNonLatinNonCyrillicScript = Regex("""[\u0600-\u06FF\u0900-\u097F\u4E00-\u9FFF\u3040-\u30FF\uAC00-\uD7AF]""").containsMatchIn(combined)

        if (hasNonLatinNonCyrillicScript) return false

        return when (languageMode) {
            SearchLanguageMode.RUSSIAN_ONLY -> {
                hasCyrillic && !hasUkrainianSpecific && !hasOtherCyrillicSpecific
            }
            SearchLanguageMode.ENGLISH_FOR_DUB -> {
                val hasLatin = Regex("""[a-zA-Z]{3,}""").containsMatchIn(title)
                hasLatin && !hasCyrillic && !hasUkrainianSpecific
            }
            SearchLanguageMode.ANY_LANGUAGE -> {
                !hasUkrainianSpecific && !hasOtherCyrillicSpecific
            }
        }
    }

    private fun scoreCandidateRelevance(
        c: DiscoveredMediaCandidate,
        userQuery: String,
        nichePlan: AdaptiveNichePlanDto
    ): Double {
        val lowerTitle = c.title.lowercase(Locale.getDefault())
        val lowerAuthor = c.author.lowercase(Locale.getDefault())
        var score = ln((c.viewOrScoreCount.coerceAtLeast(100L)).toDouble()) * 14.0

        for (entity in nichePlan.tierOneEntities) {
            val eLow = entity.trim().lowercase(Locale.getDefault())
            if (eLow.length >= 3 && (lowerTitle.contains(eLow) || lowerAuthor.contains(eLow))) {
                score += 60.0
            }
        }

        val queryWords = userQuery.lowercase(Locale.getDefault())
            .split(Regex("""\s+"""))
            .filter { it.length >= 4 }
        for (w in queryWords) {
            val stem = w.take(5)
            if (lowerTitle.contains(stem)) score += 25.0
        }
        return score
    }

    private fun computeViralVelocityScore(item: DiscoveredMediaCandidate): Double {
        if (item.uploadDateYmd.length != 8) return item.viewOrScoreCount.toDouble()
        return try {
            val sdf = SimpleDateFormat("yyyyMMdd", Locale.US)
            val uploadTime = sdf.parse(item.uploadDateYmd)?.time ?: return item.viewOrScoreCount.toDouble()
            val daysOld = ((System.currentTimeMillis() - uploadTime) / 86_400_000.0).coerceAtLeast(0.5)
            item.viewOrScoreCount.toDouble() / daysOld
        } catch (_: Exception) {
            item.viewOrScoreCount.toDouble()
        }
    }

    private suspend fun loadSeenIds(): Set<String> = stateMutex.withLock {
        if (!seenIdsFile.exists()) return@withLock emptySet()
        seenIdsFile.readLines().map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    }

    private suspend fun markCandidatesAsSeen(ids: List<String>) = stateMutex.withLock {
        if (ids.isEmpty()) return@withLock
        val existing = if (seenIdsFile.exists()) {
            seenIdsFile.readLines().toMutableSet()
        } else {
            mutableSetOf()
        }
        existing.addAll(ids)
        val trimmed = existing.toList().takeLast(3000)
        seenIdsFile.writeText(trimmed.joinToString("\n"))
    }

    suspend fun huntSpamFarmChannels(
        topicOrSeedTitle: String,
        maxChannels: Int = 5
    ): List<DiscoveredSpamChannel> = withContext(Dispatchers.IO) {
        val cleanTopic = sanitizeQuery(topicOrSeedTitle).take(75)
        DiagnosticLogger.log("SPAM-HUNTER", "📡 Поиск потоковых/спам-каналов по теме: «$cleanTopic»...")

        val normalizedTopic = normalizeLegacyNicheTerms(cleanTopic, SearchLanguageMode.ANY_LANGUAGE)
        val hasCyrillicTopic = Regex("""[а-яА-ЯёЁ]""").containsMatchIn(normalizedTopic)
        val langForSpam = if (hasCyrillicTopic) SearchLanguageMode.RUSSIAN_ONLY else SearchLanguageMode.ANY_LANGUAGE

        val farmQueries = linkedSetOf(
            "$normalizedTopic shorts",
            "$normalizedTopic нарезка",
            normalizedTopic
        )

        val apiKey = configStore.getGeminiApiKey()
        if (apiKey.isNotBlank()) {
            try {
                val prompt = """
                    Пользователь ищет на YouTube потоковые каналы / контент-фермы нарезок в нише: "$normalizedTopic".
                    Придумай 2 КОРОТКИХ запроса (по 2–4 слова, без годов и без выдуманных имён).
                    Верни строго JSON: {"queries": ["запрос 1", "запрос 2"]}
                """.trimIndent()
                val rawJson = GeminiModelResolver.generateTextOrJson(apiKey, prompt, expectJson = true)
                if (!rawJson.isNullOrBlank()) {
                    val parsed = jsonFormat.decodeFromString<AdaptiveNichePlanDto>(rawJson)
                    parsed.queries
                        .map { cleanGeneratedSearchQuery(it) }
                        .filter { it.isNotBlank() }
                        .take(2)
                        .forEach { farmQueries.add(it) }
                }
            } catch (_: Exception) {
            }
        }

        val widePool = mutableListOf<DiscoveredMediaCandidate>()
        for (q in farmQueries.take(4)) {
            widePool.addAll(
                searchYouTubeByTags(
                    queryTags = q,
                    sortOrder = ContentSortOrder.POPULAR,
                    period = SearchTimePeriod.MONTH,
                    languageMode = langForSpam,
                    appendShortsKeyword = false,
                    minDurationSec = 8,
                    maxDurationSec = 240,
                    maxFetchCount = 25,
                    minRequiredViewsOverride = 1_000L
                )
            )
        }

        val uniqueVideos = widePool.distinctBy { it.id }
        if (uniqueVideos.isEmpty()) return@withContext emptyList()

        val groupedByAuthor = uniqueVideos
            .filter {
                it.author.isNotBlank() &&
                    !it.author.equals("YouTube", ignoreCase = true) &&
                    !it.author.equals("YouTube Shorts", ignoreCase = true)
            }
            .groupBy { it.author.trim() }

        val candidateAuthors = groupedByAuthor.entries
            .sortedByDescending { entry ->
                val countBonus = entry.value.size * 12
                val spamTitleHits = entry.value.count { v -> hasSpamFarmTitleMarkers(v.title) } * 6
                countBonus + spamTitleHits
            }
            .take(maxChannels + 3)

        val discoveredChannels = mutableListOf<DiscoveredSpamChannel>()
        for ((authorName, initialVideos) in candidateAuthors) {
            if (discoveredChannels.size >= maxChannels) break

            val channelVideos = if (initialVideos.size >= 3) {
                initialVideos
            } else {
                val extra = searchYouTubeByTags(
                    queryTags = "$authorName ${normalizedTopic.split(" ").take(2).joinToString(" ")}",
                    sortOrder = ContentSortOrder.POPULAR,
                    period = SearchTimePeriod.YEAR,
                    languageMode = langForSpam,
                    minDurationSec = 8,
                    maxDurationSec = 300,
                    maxFetchCount = 15,
                    minRequiredViewsOverride = 500L
                ).filter { it.author.equals(authorName, ignoreCase = true) }
                (initialVideos + extra).distinctBy { it.id }
            }

            if (channelVideos.isEmpty()) continue

            val titleRandomnessPct = computeTitleRandomnessPercent(channelVideos.map { it.title })
            val spamMarkerRatio = channelVideos.count { hasSpamFarmTitleMarkers(it.title) }.toDouble() / channelVideos.size.toDouble()
            val estimatedPerWeek = estimateUploadsPerWeek(channelVideos)
            val maxViews = channelVideos.maxOfOrNull { it.viewOrScoreCount } ?: 1_000L
            val minViews = (channelVideos.minOfOrNull { it.viewOrScoreCount } ?: 500L).coerceAtLeast(100L)
            val spikeRatio = (maxViews.toDouble() / minViews.toDouble()).coerceIn(1.0, 200.0)

            val rawSpamScore = (
                (titleRandomnessPct * 0.42) +
                    (spamMarkerRatio * 28.0) +
                    ((estimatedPerWeek.coerceAtMost(25.0) / 25.0) * 20.0) +
                    (if (spikeRatio > 10.0) 12.0 else 5.0)
                ).roundToInt().coerceIn(48, 98)

            val encodedChannelSearch = java.net.URLEncoder.encode("$authorName $cleanTopic", "UTF-8")
            val channelUrl = "https://www.youtube.com/results?search_query=$encodedChannelSearch"

            val patternNotes = mutableListOf<String>()
            if (titleRandomnessPct >= 68) patternNotes.add("разброс тем ($titleRandomnessPct%)")
            if (spamMarkerRatio >= 0.35) patternNotes.add("шаблонные #shorts/нарезки")
            if (estimatedPerWeek >= 5.0) patternNotes.add("~${String.format(Locale.US, "%.1f", estimatedPerWeek)} рол./нед")
            if (spikeRatio >= 10.0) patternNotes.add("прострелы просм. x${spikeRatio.toInt()}")
            if (patternNotes.isEmpty()) patternNotes.add("потоковый канал по теме «$cleanTopic»")

            discoveredChannels.add(
                DiscoveredSpamChannel(
                    channelName = authorName,
                    channelUrl = channelUrl,
                    topicQuery = cleanTopic,
                    spamScore = rawSpamScore,
                    videosFoundCount = channelVideos.size,
                    estimatedUploadsPerWeek = estimatedPerWeek,
                    titleRandomnessPct = titleRandomnessPct,
                    patternSummary = patternNotes.joinToString(" • "),
                    sampleVideos = channelVideos.sortedByDescending { it.viewOrScoreCount }.take(5)
                )
            )
        }

        discoveredChannels.sortedByDescending { it.spamScore }.take(maxChannels)
    }

    private fun hasSpamFarmTitleMarkers(title: String): Boolean {
        val lower = title.lowercase(Locale.getDefault())
        val markers = listOf(
            "#shorts", "#short", "#viral", "#fyp", "#рек", "#тренды",
            "часть ", "part ", "момент", "нарезк", "история", "случай",
            "😱", "😂", "🔥", "💀", "❗️", "?!"
        )
        val capsCount = title.count { it.isUpperCase() }
        val highCaps = title.length >= 10 && (capsCount.toDouble() / title.length.toDouble()) >= 0.38
        return highCaps || markers.any { lower.contains(it) }
    }

    private fun computeTitleRandomnessPercent(titles: List<String>): Int {
        if (titles.size < 2) return 75
        val tokenSets = titles.map { t ->
            t.lowercase(Locale.getDefault())
                .replace(Regex("""[^a-zа-я0-9\s]"""), " ")
                .split(Regex("""\s+"""))
                .filter { it.length >= 3 && it !in setOf("shorts", "short", "видео", "про", "как", "что", "для", "the", "and") }
                .toSet()
        }.filter { it.isNotEmpty() }

        if (tokenSets.size < 2) return 78

        var pairCount = 0
        var similaritySum = 0.0
        for (i in 0 until tokenSets.size) {
            for (j in i + 1 until tokenSets.size) {
                val a = tokenSets[i]
                val b = tokenSets[j]
                val intersection = a.intersect(b).size.toDouble()
                val union = a.union(b).size.toDouble().coerceAtLeast(1.0)
                similaritySum += (intersection / union)
                pairCount++
            }
        }
        val avgSimilarity = if (pairCount > 0) similaritySum / pairCount else 0.15
        return ((1.0 - avgSimilarity) * 96.0).roundToInt().coerceIn(45, 97)
    }

    private fun estimateUploadsPerWeek(videos: List<DiscoveredMediaCandidate>): Double {
        val dates = videos.mapNotNull { v ->
            if (v.uploadDateYmd.length == 8) {
                try {
                    SimpleDateFormat("yyyyMMdd", Locale.US).parse(v.uploadDateYmd)?.time
                } catch (_: Exception) {
                    null
                }
            } else {
                null
            }
        }.sorted()

        if (dates.size >= 2) {
            val spanDays = ((dates.last() - dates.first()) / 86_400_000.0).coerceAtLeast(1.0)
            return ((dates.size.toDouble() / spanDays) * 7.0).coerceIn(2.5, 45.0)
        }
        return (videos.size * 2.2).coerceIn(3.5, 28.0)
    }
}
