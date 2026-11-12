package forge.media.pocket.discovery

import android.content.Context
import forge.media.pocket.config.DiagnosticLogger
import forge.media.pocket.service.PipelineForegroundService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class CrawlerJobState(
    val isRunning: Boolean = false,
    val query: String = "",
    val platform: HuntPlatform = HuntPlatform.ALL,
    val pagesCrawled: Int = 0,
    val targetCount: Int = 0, // 0 = infinite until STOP
    val statusText: String = "Готов к поиску ссылок (без авто-скачивания мусора)",
    val results: List<DiscoveredMediaCandidate> = emptyList()
)

/**
 * Application-wide Background Link-Only Crawler Coordinator.
 *
 * Survives UI tab switches and runs while the user minimizes the app (keeps PipelineForegroundService awake).
 * Strictly emits LINK CARDS ONLY — zero unsolicited MP4 downloads!
 *
 * 1) `sourceState`: Crawls pages across YouTube / VK / Pikabu / Reddit / Rutube until `targetCount` (e.g. 10/20/30)
 *    verified links are collected or user presses STOP.
 * 2) `bgState`: Infinite Background Harvester ("Искать фоны до СТОПА") — walks page 1, 2, 3...50+
 *    emitting live link cards until the user explicitly hits `⏹ СТОП`!
 */
object BackgroundCrawlerManager {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var sourceJob: Job? = null
    private var bgInfiniteJob: Job? = null

    private val _sourceState = MutableStateFlow(CrawlerJobState())
    val sourceState: StateFlow<CrawlerJobState> = _sourceState.asStateFlow()

    private val _bgState = MutableStateFlow(
        CrawlerJobState(statusText = "Введи тему фона (например Subway Surfers) и нажми «▶️ Искать до СТОПА»")
    )
    val bgState: StateFlow<CrawlerJobState> = _bgState.asStateFlow()

    fun startSourceBatchCrawl(
        context: Context,
        query: String,
        negativeTags: String,
        platform: HuntPlatform,
        sortOrder: ContentSortOrder,
        period: SearchTimePeriod,
        durationMode: SearchDurationMode,
        languageMode: SearchLanguageMode,
        targetCount: Int
    ) {
        val cleanQ = query.trim()
        if (cleanQ.isBlank()) return

        sourceJob?.cancel()
        SearchBlacklistStore.setSourceNegativeTags(context, negativeTags)
        try {
            PipelineForegroundService.startService(context.applicationContext)
        } catch (_: Exception) {
        }

        val engine = TopicHunterEngine(context.applicationContext)
        val session = MultiPlatformCrawlSession(
            rawQuery = cleanQ,
            negativeTags = negativeTags,
            platform = platform,
            sortOrder = sortOrder,
            period = period,
            durationMode = durationMode,
            languageMode = languageMode,
            isBackgroundMode = false
        )

        _sourceState.value = CrawlerJobState(
            isRunning = true,
            query = cleanQ,
            platform = platform,
            pagesCrawled = 0,
            targetCount = targetCount,
            statusText = "🚀 Сбор пачки $targetCount ссылок по запросу «$cleanQ» (работает в фоне)...",
            results = emptyList()
        )

        sourceJob = scope.launch {
            val accumulated = mutableListOf<DiscoveredMediaCandidate>()
            var emptyStreak = 0
            try {
                while (isActive && accumulated.size < targetCount && session.hasMorePages() && session.pageIndex < 25) {
                    val pageItems = engine.crawlNextPage(session)
                    if (pageItems.isEmpty()) {
                        emptyStreak++
                        if (emptyStreak >= 3) break
                    } else {
                        emptyStreak = 0
                        accumulated.addAll(pageItems)
                    }

                    val capped = accumulated.take(targetCount)
                    _sourceState.value = _sourceState.value.copy(
                        pagesCrawled = session.pageIndex,
                        results = capped,
                        statusText = "🔎 Стр. #${session.pageIndex}: собрано ${capped.size} из $targetCount ссылок..."
                    )

                    if (capped.size >= targetCount) break
                    delay(650L)
                }

                // Fallback to ALL_TIME if strict period returned 0
                if (isActive && accumulated.isEmpty() && period != SearchTimePeriod.ALL_TIME) {
                    _sourceState.value = _sourceState.value.copy(
                        statusText = "За период «${period.label}» пусто -> проверяю за всё время..."
                    )
                    val fallbackSession = MultiPlatformCrawlSession(
                        rawQuery = cleanQ,
                        negativeTags = negativeTags,
                        platform = platform,
                        sortOrder = sortOrder,
                        period = SearchTimePeriod.ALL_TIME,
                        durationMode = durationMode,
                        languageMode = languageMode,
                        isBackgroundMode = false
                    )
                    val fbItems = engine.crawlNextPage(fallbackSession).take(targetCount)
                    accumulated.addAll(fbItems)
                }

                val finalResults = accumulated.take(targetCount)
                _sourceState.value = _sourceState.value.copy(
                    isRunning = false,
                    results = finalResults,
                    statusText = if (finalResults.isNotEmpty()) {
                        "✅ Собрано ${finalResults.size} ссылок (стр. ${session.pageIndex}). Выбирай нужные!"
                    } else {
                        "⚠️ По запросу «$cleanQ» ссылок не найдено. Проверь минус-слова или площадку."
                    }
                )
            } catch (e: Exception) {
                DiagnosticLogger.logError("SOURCE-CRAWLER", "Остановка пакетного поиска", e)
                _sourceState.value = _sourceState.value.copy(
                    isRunning = false,
                    statusText = "⏹ Поиск завершён (найдено ${_sourceState.value.results.size} ссылок)"
                )
            }
        }
    }

    fun stopSourceBatchCrawl() {
        sourceJob?.cancel()
        sourceJob = null
        _sourceState.value = _sourceState.value.copy(
            isRunning = false,
            statusText = "⏹ Поиск остановлен пользователем (${_sourceState.value.results.size} ссылок)"
        )
    }

    /**
     * Module 4: Infinite Link-Only Background Harvester!
     * Walks page 1, 2, 3 ... 60 continuously across platforms until the user hits `⏹ СТОП`.
     * NEVER downloads any MP4 automatically — streams clickable link cards live!
     */
    fun startInfiniteBackgroundCrawl(
        context: Context,
        query: String,
        negativeTags: String,
        platform: HuntPlatform
    ) {
        val cleanQ = query.trim()
        if (cleanQ.isBlank()) return

        bgInfiniteJob?.cancel()
        SearchBlacklistStore.setBgNegativeTags(context, negativeTags)
        try {
            PipelineForegroundService.startService(context.applicationContext)
        } catch (_: Exception) {
        }

        val engine = TopicHunterEngine(context.applicationContext)
        val session = MultiPlatformCrawlSession(
            rawQuery = cleanQ,
            negativeTags = negativeTags,
            platform = platform,
            sortOrder = ContentSortOrder.POPULAR,
            period = SearchTimePeriod.ALL_TIME,
            durationMode = SearchDurationMode.ANY_UP_TO_6M,
            languageMode = SearchLanguageMode.ANY_LANGUAGE,
            isBackgroundMode = true
        )

        _bgState.value = CrawlerJobState(
            isRunning = true,
            query = cleanQ,
            platform = platform,
            pagesCrawled = 0,
            targetCount = 0,
            statusText = "♾ Бесконечный поиск ссылок «$cleanQ» запущен! Иду по страницам до нажатия СТОП...",
            results = emptyList()
        )

        bgInfiniteJob = scope.launch {
            val liveList = mutableListOf<DiscoveredMediaCandidate>()
            var emptyPagesInRow = 0
            try {
                while (isActive && session.hasMorePages() && session.pageIndex < 80) {
                    val freshLinks = engine.crawlNextPage(session)
                    if (freshLinks.isEmpty()) {
                        emptyPagesInRow++
                        if (emptyPagesInRow >= 4) break
                    } else {
                        emptyPagesInRow = 0
                        liveList.addAll(freshLinks)
                    }

                    _bgState.value = _bgState.value.copy(
                        pagesCrawled = session.pageIndex,
                        results = liveList.toList(),
                        statusText = "♾ Ищу фоны до СТОПА • Стр. #${session.pageIndex} • Найдено ссылок: ${liveList.size} шт."
                    )

                    delay(1100L)
                }

                _bgState.value = _bgState.value.copy(
                    isRunning = false,
                    statusText = "✅ Все доступные страницы пройдены (${liveList.size} ссылок по теме «$cleanQ»)"
                )
            } catch (_: Exception) {
                _bgState.value = _bgState.value.copy(
                    isRunning = false,
                    statusText = "⏹ Поиск фонов остановлен (собрано ${_bgState.value.results.size} ссылок)"
                )
            }
        }
    }

    fun stopInfiniteBackgroundCrawl() {
        bgInfiniteJob?.cancel()
        bgInfiniteJob = null
        _bgState.value = _bgState.value.copy(
            isRunning = false,
            statusText = "⏹ Остановлено по кнопке СТОП. Найдено ссылок: ${_bgState.value.results.size} шт."
        )
    }

    /**
     * Immediately purges a banned video or channel from both live result lists!
     */
    fun banCandidateAndPurge(context: Context, candidate: DiscoveredMediaCandidate, banEntireChannel: Boolean) {
        if (banEntireChannel) {
            SearchBlacklistStore.banChannel(context, candidate.author)
            val normBan = SearchBlacklistStore.normalizeAuthorKey(candidate.author)
            _sourceState.value = _sourceState.value.copy(
                results = _sourceState.value.results.filterNot {
                    SearchBlacklistStore.normalizeAuthorKey(it.author) == normBan
                }
            )
            _bgState.value = _bgState.value.copy(
                results = _bgState.value.results.filterNot {
                    SearchBlacklistStore.normalizeAuthorKey(it.author) == normBan
                }
            )
        } else {
            SearchBlacklistStore.banVideo(context, candidate.id, candidate.url, candidate.title)
            _sourceState.value = _sourceState.value.copy(
                results = _sourceState.value.results.filterNot { it.id == candidate.id || it.url == candidate.url }
            )
            _bgState.value = _bgState.value.copy(
                results = _bgState.value.results.filterNot { it.id == candidate.id || it.url == candidate.url }
            )
        }
    }
}
