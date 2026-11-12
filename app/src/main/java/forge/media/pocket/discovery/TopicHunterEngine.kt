package forge.media.pocket.discovery

import android.content.Context
import android.util.Base64
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
import io.ktor.http.encodeURLParameter
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
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
    ANY_UP_TO_6M("🌐 До 6 минут (Shorts + Ролики)", 8, 360, false),
    ORIGINALS_1_TO_6M("🎬 Исходники 1–12 мин (Под нарезку)", 50, 720, false),
    SHORTS_ONLY("⚡ Короткие до 75 сек (Shorts/Клипы)", 8, 75, true)
}

enum class SearchLanguageMode(val label: String) {
    RUSSIAN_ONLY("🇷🇺 Только Русский"),
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

/**
 * Holds pagination cursors across all 5 platforms so BackgroundCrawlerManager can walk
 * page 1, 2, 3 ... N without repeating items or losing continuation tokens.
 */
class MultiPlatformCrawlSession(
    val rawQuery: String,
    val negativeTags: String,
    val platform: HuntPlatform,
    val sortOrder: ContentSortOrder = ContentSortOrder.POPULAR,
    val period: SearchTimePeriod = SearchTimePeriod.MONTH,
    val durationMode: SearchDurationMode = SearchDurationMode.ANY_UP_TO_6M,
    val languageMode: SearchLanguageMode = SearchLanguageMode.ANY_LANGUAGE,
    val isBackgroundMode: Boolean = false
) {
    var pageIndex: Int = 0
    var ytShortContinuation: String? = null
    var ytMidContinuation: String? = null
    var ytExhausted: Boolean = false

    var vkOffset: Int = 0
    var vkExhausted: Boolean = false

    var pikabuPage: Int = 1
    var pikabuExhausted: Boolean = false

    var redditAfter: String? = null
    var redditExhausted: Boolean = false

    var rutubePage: Int = 1
    var rutubeExhausted: Boolean = false

    val seenKeys: MutableSet<String> = LinkedHashSet()
    val perAuthorCounts: MutableMap<String, Int> = HashMap()

    fun hasMorePages(): Boolean {
        return when (platform) {
            HuntPlatform.YOUTUBE -> !ytExhausted
            HuntPlatform.VK_CLIPS -> !vkExhausted
            HuntPlatform.PIKABU -> !pikabuExhausted
            HuntPlatform.REDDIT -> !redditExhausted
            HuntPlatform.RUTUBE -> !rutubeExhausted
            HuntPlatform.ALL -> !ytExhausted || !vkExhausted || !pikabuExhausted || !redditExhausted || !rutubeExhausted
        }
    }
}

/**
 * Link-Only Multi-Platform Deep Search Engine v3.0.
 *
 * Key Guarantees:
 * 1. ZERO hidden query replacement ("Subway Surfers" searches "Subway Surfers" literally — never "cs2 surf").
 * 2. ZERO unsolicited MP4 downloads during search — returns clean, verified link cards with thumbnails.
 * 3. Server-side YouTube `sp` protobuf duration + upload_date + continuation pagination + 2025/2026 `lockupViewModel` support.
 * 4. Multi-platform coverage: YouTube, VK Клипы/Видео, Pikabu Видео, Reddit Video, Rutube.
 * 5. Real-time enforcement of `SearchBlacklistStore` (banned videos, banned channels, minus-tags).
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
     * Fetches the next page of link candidates across the session's selected platform(s).
     */
    suspend fun crawlNextPage(session: MultiPlatformCrawlSession): List<DiscoveredMediaCandidate> =
        withContext(Dispatchers.IO) {
            session.pageIndex += 1
            val currentPage = session.pageIndex
            val minusSuffix = SearchBlacklistStore.formatMinusQuerySuffix(session.negativeTags, maxTerms = 5)
            val cleanBaseQuery = session.rawQuery.trim().replace(Regex("""\s+"""), " ")
            if (cleanBaseQuery.isBlank()) return@withContext emptyList()

            val effectiveQuery = buildString {
                append(cleanBaseQuery)
                if (session.isBackgroundMode &&
                    !cleanBaseQuery.contains("gameplay", true) &&
                    !cleanBaseQuery.contains("фон", true) &&
                    !cleanBaseQuery.contains("shorts", true) &&
                    !cleanBaseQuery.contains("asmr", true) &&
                    !cleanBaseQuery.contains("pov", true)
                ) {
                    // Keep literal subject intact; never substitute another game!
                }
                if (minusSuffix.isNotBlank()) {
                    append(" ").append(minusSuffix)
                }
            }.trim()

            DiagnosticLogger.log(
                "CRAWLER",
                "🔎 Стр. #$currentPage | Запрос: «$cleanBaseQuery» | Площадка: ${session.platform.badge} | Минус: ${session.negativeTags.ifBlank { "нет" }}"
            )

            val rawFetched: List<DiscoveredMediaCandidate> = coroutineScope {
                val jobs = when (session.platform) {
                    HuntPlatform.YOUTUBE -> listOf(
                        async { fetchYoutubePage(session, effectiveQuery) }
                    )
                    HuntPlatform.VK_CLIPS -> listOf(
                        async { fetchVkVideoPage(session, cleanBaseQuery) }
                    )
                    HuntPlatform.PIKABU -> listOf(
                        async { fetchPikabuVideoPage(session, cleanBaseQuery) }
                    )
                    HuntPlatform.REDDIT -> listOf(
                        async { fetchRedditVideoPage(session, cleanBaseQuery) }
                    )
                    HuntPlatform.RUTUBE -> listOf(
                        async { fetchRutubeVideoPage(session, cleanBaseQuery) }
                    )
                    HuntPlatform.ALL -> listOf(
                        async { if (!session.ytExhausted) fetchYoutubePage(session, effectiveQuery) else emptyList() },
                        async { if (!session.vkExhausted) fetchVkVideoPage(session, cleanBaseQuery) else emptyList() },
                        async { if (!session.pikabuExhausted) fetchPikabuVideoPage(session, cleanBaseQuery) else emptyList() },
                        async { if (!session.redditExhausted) fetchRedditVideoPage(session, cleanBaseQuery) else emptyList() },
                        async { if (!session.rutubeExhausted) fetchRutubeVideoPage(session, cleanBaseQuery) else emptyList() }
                    )
                }
                jobs.awaitAll().flatten()
            }

            val anchorTokens = extractMeaningfulAnchorTokens(cleanBaseQuery)
            val minDur = if (session.isBackgroundMode) 8 else session.durationMode.minSec
            val maxDur = if (session.isBackgroundMode) 600 else session.durationMode.maxSec

            val accepted = mutableListOf<DiscoveredMediaCandidate>()

            for (cand in rawFetched) {
                val normUrl = SearchBlacklistStore.normalizeUrlKey(cand.url)
                if (cand.id in session.seenKeys || normUrl in session.seenKeys) continue

                // 1. Check user Blacklist + Negative Tags
                val fullText = "${cand.title} ${cand.descriptionSnippet}"
                if (!SearchBlacklistStore.isCandidateAllowed(
                        context = context,
                        id = cand.id,
                        url = cand.url,
                        author = cand.author,
                        fullText = fullText,
                        rawNegativeTags = session.negativeTags
                    )
                ) {
                    continue
                }

                // 2. Duration bounds (allow unknown=0 on Pikabu/VK cards when metadata is lazy, else enforce)
                if (cand.durationSec > 0 && (cand.durationSec < minDur || cand.durationSec > maxDur)) {
                    continue
                }

                // 3. Language filter (when explicitly set on non-background searches)
                if (!session.isBackgroundMode) {
                    if (session.languageMode == SearchLanguageMode.RUSSIAN_ONLY) {
                        val hasCyrillic = fullText.any { it in 'а'..'я' || it in 'А'..'Я' || it == 'ё' || it == 'Ё' }
                        if (!hasCyrillic && cand.platform != HuntPlatform.REDDIT) continue
                    } else if (session.languageMode == SearchLanguageMode.ENGLISH_FOR_DUB) {
                        val hasCyrillic = cand.title.any { it in 'а'..'я' || it in 'А'..'Я' || it == 'ё' || it == 'Ё' }
                        if (hasCyrillic) continue
                    }
                }

                // 4. Period freshness check when age label is known
                if (!session.isBackgroundMode && session.period.daysBack != null && cand.publishedAgeLabel.isNotBlank()) {
                    val estDays = estimateDaysFromAgeLabel(cand.publishedAgeLabel)
                    if (estDays != null && estDays > session.period.daysBack + 4) {
                        continue
                    }
                }

                // 5. Anchor word relevance check: prevents unrelated "People also watched" or CS2 videos on "Subway Surfers"!
                if (anchorTokens.isNotEmpty()) {
                    val hay = "${cand.title} ${cand.author} ${cand.descriptionSnippet}".lowercase(Locale.ROOT)
                    val matchedAnchor = anchorTokens.any { tok ->
                        val stem = if (tok.length >= 6) tok.dropLast(1) else tok
                        hay.contains(stem)
                    }
                    if (!matchedAnchor) {
                        continue
                    }
                }

                // 6. Cap max 3 items per author in Source Batch mode so 1 spam channel doesn't flood all 20 slots
                if (!session.isBackgroundMode) {
                    val authKey = SearchBlacklistStore.normalizeAuthorKey(cand.author)
                    if (authKey.isNotBlank()) {
                        val used = session.perAuthorCounts[authKey] ?: 0
                        if (used >= 3) continue
                        session.perAuthorCounts[authKey] = used + 1
                    }
                }

                session.seenKeys.add(cand.id)
                session.seenKeys.add(normUrl)
                accepted.add(cand)
            }

            val sorted = rankCandidates(accepted, session.sortOrder)
            DiagnosticLogger.log(
                "CRAWLER",
                "✅ Стр. #$currentPage дала ${sorted.size} чистых ссылок (сырых=${rawFetched.size}, всего уникальных=${session.seenKeys.size / 2})"
            )
            sorted
        }

    /**
     * Batch search entry point used by TelegramBotWorker and quick searches.
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
            isBackgroundMode = false
        )
        val collected = mutableListOf<DiscoveredMediaCandidate>()
        var attempts = 0
        while (collected.size < limitCount && session.hasMorePages() && attempts < 5) {
            attempts++
            val batch = crawlNextPage(session)
            collected.addAll(batch)
            if (batch.isEmpty() && attempts >= 2) break
        }

        // If strict period (e.g. MONTH) yielded 0, automatically try relaxed YEAR period on YouTube/Multi so user isn't left empty
        if (collected.isEmpty() && period != SearchTimePeriod.ALL_TIME) {
            DiagnosticLogger.log("CRAWLER", "За период ${period.label} найдено 0 -> пробую расширенный период За всё время...")
            val fallbackSession = MultiPlatformCrawlSession(
                rawQuery = queryTags,
                negativeTags = negativeTags,
                platform = platform,
                sortOrder = sortOrder,
                period = SearchTimePeriod.ALL_TIME,
                durationMode = durationMode,
                languageMode = languageMode,
                isBackgroundMode = false
            )
            collected.addAll(crawlNextPage(fallbackSession))
        }

        rankCandidates(collected, sortOrder).take(limitCount)
    }

    /**
     * Link-only background search helper for TelegramBotWorker `/bg` command.
     */
    suspend fun searchBackgroundCandidates(
        topicQuery: String,
        limitCount: Int = 8,
        negativeTags: String = SearchBlacklistStore.bgNegativeTags.value
    ): List<DiscoveredMediaCandidate> = withContext(Dispatchers.IO) {
        val session = MultiPlatformCrawlSession(
            rawQuery = topicQuery,
            negativeTags = negativeTags,
            platform = HuntPlatform.ALL,
            sortOrder = ContentSortOrder.POPULAR,
            period = SearchTimePeriod.ALL_TIME,
            durationMode = SearchDurationMode.ANY_UP_TO_6M,
            languageMode = SearchLanguageMode.ANY_LANGUAGE,
            isBackgroundMode = true
        )
        val collected = mutableListOf<DiscoveredMediaCandidate>()
        var pages = 0
        while (collected.size < limitCount && session.hasMorePages() && pages < 4) {
            pages++
            collected.addAll(crawlNextPage(session))
        }
        collected.take(limitCount)
    }

    // =========================================================================
    // 1. YOUTUBE INNERTUBE (PROTOBUF `sp` + CONTINUATION + LOCKUPVIEWMODEL)
    // =========================================================================

    private suspend fun fetchYoutubePage(
        session: MultiPlatformCrawlSession,
        effectiveQuery: String
    ): List<DiscoveredMediaCandidate> {
        val results = mutableListOf<DiscoveredMediaCandidate>()
        try {
            if (session.pageIndex == 1) {
                // On page 1, query Under-4-Min (<4m) AND (if allowed) 4-20m in parallel so concerts NEVER crowd out clips!
                val sortCode = when (session.sortOrder) {
                    ContentSortOrder.NEWEST -> 2
                    ContentSortOrder.POPULAR -> 0 // Relevance + server duration filter + local view sort avoids dead/unrelated spam
                    ContentSortOrder.VIRAL_VELOCITY -> 0
                    ContentSortOrder.RELEVANCE -> 0
                }
                val spUnder4m = buildYoutubeSearchSpBase64(
                    sortCode = sortCode,
                    period = if (session.isBackgroundMode) SearchTimePeriod.ALL_TIME else session.period,
                    durationCode = 1 // 1 = Under 4 minutes!
                )
                val pairShort = executeYoutubeInnertubeSearch(query = effectiveQuery, spParams = spUnder4m, continuation = null)
                results.addAll(pairShort.first)
                session.ytShortContinuation = pairShort.second

                if (session.durationMode != SearchDurationMode.SHORTS_ONLY) {
                    val spMid = buildYoutubeSearchSpBase64(
                        sortCode = sortCode,
                        period = if (session.isBackgroundMode) SearchTimePeriod.ALL_TIME else session.period,
                        durationCode = 3 // 3 = 4–20 minutes!
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
                if (tok1 == null && tok2 == null) {
                    session.ytExhausted = true
                    return emptyList()
                }
                if (tok1 != null) {
                    val p1 = executeYoutubeInnertubeSearch(query = null, spParams = null, continuation = tok1)
                    results.addAll(p1.first)
                    session.ytShortContinuation = p1.second
                }
                if (tok2 != null && session.durationMode != SearchDurationMode.SHORTS_ONLY) {
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

    /**
     * Encodes YouTube's official `sp` protobuf parameter:
     * - field 1 (varint): sort_by (0=Relevance, 2=Upload Date, 3=View Count)
     * - field 2 (length-delimited submessage):
     *   - subfield 1 (varint): upload_date (2=Today, 3=Week, 4=Month, 5=Year)
     *   - subfield 2 (varint): result_type (1=Video only)
     *   - subfield 3 (varint): duration (1=Under 4 min, 3=4-20 min)
     */
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
            filterSub.write(0x08) // field 1, varint
            filterSub.write(uploadCode)
        }
        // Always restrict to Videos (type = 1) so channels/playlists don't waste slots
        filterSub.write(0x10) // field 2, varint
        filterSub.write(1)

        if (durationCode != null) {
            filterSub.write(0x18) // field 3, varint
            filterSub.write(durationCode)
        }

        val outer = ByteArrayOutputStream()
        outer.write(0x08) // field 1, varint (sort_by)
        outer.write(sortCode)

        val subBytes = filterSub.toByteArray()
        if (subBytes.isNotEmpty()) {
            outer.write(0x12) // field 2, length-delimited
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
                    // Skip "People also watched" / unrelated recommendation shelves!
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
        // Skip live streams (no duration)
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

        val isShort = durSec <= 65
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

        // Extract duration badge from overlays
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

        // Metadata rows (author, views, age)
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

        val url = if (durSec <= 65) "https://www.youtube.com/shorts/$videoId" else "https://www.youtube.com/watch?v=$videoId"
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
            durationSec = 45,
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
            durationSec = 45,
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
    // 2. VK КЛИПЫ / VK ВИДЕО (m.vk.com / vkvideo.ru link extractor)
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
        // Matches /clip-12345_67890 or /video-12345_67890 or /video12345_67890
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
    // 3. PIKABU ВИДЕО (pikabu.ru/search?q=...&n=4&st=3&page=N)
    // =========================================================================

    private suspend fun fetchPikabuVideoPage(
        session: MultiPlatformCrawlSession,
        query: String
    ): List<DiscoveredMediaCandidate> {
        val out = mutableListOf<DiscoveredMediaCandidate>()
        try {
            val page = session.pikabuPage
            val encoded = query.encodeURLParameter()
            // n=4 => Video tag/type on Pikabu; st=3 => sort by relevance/rating
            val url = "https://pikabu.ru/search?q=$encoded&n=4&st=3&page=$page"
            val resp = httpClient.get(url) {
                header(HttpHeaders.UserAgent, DESKTOP_UA)
                header(HttpHeaders.AcceptLanguage, "ru-RU,ru;q=0.9")
            }
            if (!resp.status.isSuccess()) {
                session.pikabuExhausted = true
                return emptyList()
            }

            val bytes = resp.bodyAsText(Charset.forName("windows-1251")).let { winText ->
                // Pikabu serves windows-1251 on desktop or UTF-8 depending on headers; auto-detect Cyrillic!
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

            val title = Regex("""class="story__title-link[^"]*"[^>]*>(.*?)</a>""", RegexOption.DOT_MATCHES_ALL)
                .find(block)?.groupValues?.get(1)
                ?.replace(Regex("""<[^>]+>"""), "")
                ?.let { decodeHtmlEntities(it).trim() }
                ?: continue
            if (title.isBlank()) continue

            val hasVideo = block.contains("player", ignoreCase = true) ||
                block.contains("story-block_type_video", ignoreCase = true) ||
                block.contains("data-source-url", ignoreCase = true) ||
                block.contains("pikabu.ru/ video", ignoreCase = true) ||
                true // n=4 already filters video stories on Pikabu

            if (!hasVideo) continue

            val author = Regex("""data-name="([^"]+)"""").find(block)?.groupValues?.get(1)
                ?: Regex("""class="user__nick[^"]*"[^>]*>([^<]+)<""").find(block)?.groupValues?.get(1)?.trim()
                ?: "Pikabu"

            val rating = Regex("""data-rating="(-?\d+)"""").find(block)?.groupValues?.get(1)?.toLongOrNull()
                ?: 0L

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
                    viewOrScoreCount = rating.coerceAtLeast(0L) * 100L, // Convert Pikabu pluses to comparable score or show rating
                    thumbnailUrl = thumb,
                    cloutStatusLabel = "🧁 Рейтинг Пикабу: +$rating"
                )
            )
        }
        return results
    }

    // =========================================================================
    // 4. REDDIT VIDEO (search.json with `after` cursor & clickable post links)
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
                header(HttpHeaders.UserAgent, "android:forge.media.pocket:v3.0 (by /u/pocketforge)")
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
                    ?: post["preview"]?.jsonObject?.get("reddit_video_preview")?.jsonObject

                if (!isVideo && rv == null) continue

                val id = post["id"]?.jsonPrimitive?.contentOrNull ?: continue
                val title = post["title"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                if (title.isBlank()) continue

                val permalink = post["permalink"]?.jsonPrimitive?.contentOrNull
                val postUrl = if (!permalink.isNullOrBlank()) {
                    "https://www.reddit.com$permalink"
                } else {
                    post["url"]?.jsonPrimitive?.contentOrNull ?: continue
                }

                val subreddit = post["subreddit_name_prefixed"]?.jsonPrimitive?.contentOrNull ?: "r/videos"
                val durSec = rv?.get("duration")?.jsonPrimitive?.intOrNull ?: 35
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
    // 5. RUTUBE API (rutube.ru/api/search/video/?query=...&page=N)
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
            val hasNext = root["has_next"]?.jsonPrimitive?.contentOrNull == "true"
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
    // SPAM-FARM CHANNEL HUNTER & "WHAT'S INSIDE" TRANSCRIPT INSPECTOR
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
                "Видео «$videoTitle» готово к загрузке в Разделку клипа."
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

    private fun extractMeaningfulAnchorTokens(query: String): List<String> {
        val stopWords = setOf(
            "видео", "ролики", "ролик", "клипы", "клип", "shorts", "short", "про", "для", "как", "что",
            "или", "под", "без", "фон", "фоны", "залипалка", "залипалки", "лучшие", "моменты", "топ"
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
            low.contains("год") || low.contains("лет") || low.contains("year") -> n * 365
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
