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
    val targetCount: Int = 0,
    val statusText: String = "Готов к поиску свежих ссылок (просмотренные ролики и дубли скрываются автоматически)",
    val results: List<DiscoveredMediaCandidate> = emptyList()
)

/**
 * Application-wide Background Link-Only Crawler Coordinator v4.0.
 *
 * - NEVER silently falls back from `За месяц` to `За всё время` (no 7–10 year old videos!).
 * - Skips all previously shown results via `SearchBlacklistStore` so repeating a search always brings NEW videos.
 * - Background Filler Harvester runs frame-by-frame mid-video inspection (ML Kit + Gemini Vision)
 *   to reject cartoons, guides, menus, facecams, and story videos automatically.
 */
object BackgroundCrawlerManager {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var sourceJob: Job? = null
    private var bgInfiniteJob: Job? = null

    private val _sourceState = MutableStateFlow(CrawlerJobState())
    val sourceState: StateFlow<CrawlerJobState> = _sourceState.asStateFlow()

    private val _bgState = MutableStateFlow(
        CrawlerJobState(statusText = "Введи тему наполнителя (например Subway Surfers или резка мыла) и нажми «▶️ Искать до СТОПА»")
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
        targetCount: Int,
        enableAiCuration: Boolean = true
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
            isBackgroundMode = false,
            enableAiCuration = enableAiCuration
        )

        _sourceState.value = CrawlerJobState(
            isRunning = true,
            query = cleanQ,
            platform = platform,
            pagesCrawled = 0,
            targetCount = targetCount,
            statusText = "🧠 Ищу НОВЫЕ ролики «$cleanQ» (${period.label}) + проверяю кадры и массовую ЦА...",
            results = emptyList()
        )

        sourceJob = scope.launch {
            val accumulated = mutableListOf<DiscoveredMediaCandidate>()
            var emptyStreak = 0
            try {
                while (isActive && accumulated.size < targetCount && session.hasMorePages() && session.pageIndex < 16) {
                    val pageItems = engine.crawlNextPage(session)
                    val trulyUnique = pageItems.filter { cand ->
                        accumulated.none { prev ->
                            prev.id == cand.id || SearchBlacklistStore.areTitlesNearDuplicates(prev.title, cand.title)
                        }
                    }

                    if (trulyUnique.isEmpty()) {
                        emptyStreak++
                        if (emptyStreak >= 4) break
                    } else {
                        emptyStreak = 0
                        accumulated.addAll(trulyUnique)
                    }

                    val capped = accumulated.take(targetCount)
                    _sourceState.value = _sourceState.value.copy(
                        pagesCrawled = session.pageIndex,
                        results = capped,
                        statusText = "🔎 Стр. #${session.pageIndex}: отобрано ${capped.size} из $targetCount свежих ссылок (без повторов и душнины)..."
                    )

                    if (capped.size >= targetCount) break
                    delay(450L)
                }

                val finalResults = accumulated.take(targetCount)
                _sourceState.value = _sourceState.value.copy(
                    isRunning = false,
                    results = finalResults,
                    statusText = if (finalResults.isNotEmpty()) {
                        "✅ Найдено ${finalResults.size} новых роликов строго за «${period.label}» (без дублей и повторов)!"
                    } else {
                        "⚠️ За период «${period.label}» новых (не показанных ранее) роликов не осталось. Нажми «🔄 Сбросить просмотренные» или выбери «За год»."
                    }
                )
            } catch (e: Exception) {
                DiagnosticLogger.logError("SOURCE-CRAWLER", "Остановка пакетного поиска", e)
                _sourceState.value = _sourceState.value.copy(
                    isRunning = false,
                    statusText = "⏹ Поиск остановлен (найдено ${_sourceState.value.results.size} ссылок)"
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
            sortOrder = ContentSortOrder.RELEVANCE,
            period = SearchTimePeriod.ALL_TIME,
            durationMode = SearchDurationMode.ORIGINALS_1_TO_6M,
            languageMode = SearchLanguageMode.ANY_LANGUAGE,
            isBackgroundMode = true,
            enableAiCuration = true
        )

        _bgState.value = CrawlerJobState(
            isRunning = true,
            query = cleanQ,
            platform = platform,
            pagesCrawled = 0,
            targetCount = 0,
            statusText = "👁 Ищу чистые фоны-наполнители «$cleanQ» (покадровый отсев мультиков, гайдов и вебок)...",
            results = emptyList()
        )

        bgInfiniteJob = scope.launch {
            val liveList = mutableListOf<DiscoveredMediaCandidate>()
            var emptyPagesInRow = 0
            try {
                while (isActive && session.hasMorePages() && session.pageIndex < 60) {
                    val freshLinks = engine.crawlNextPage(session).filter { cand ->
                        liveList.none { prev ->
                            prev.id == cand.id || SearchBlacklistStore.areTitlesNearDuplicates(prev.title, cand.title)
                        }
                    }
                    if (freshLinks.isEmpty()) {
                        emptyPagesInRow++
                        if (emptyPagesInRow >= 5) break
                    } else {
                        emptyPagesInRow = 0
                        liveList.addAll(freshLinks)
                    }

                    _bgState.value = _bgState.value.copy(
                        pagesCrawled = session.pageIndex,
                        results = liveList.toList(),
                        statusText = "♾ Покадровый поиск фонов • Стр. #${session.pageIndex} • Одобрено чистых наполнителей: ${liveList.size} шт."
                    )
                    delay(800L)
                }

                _bgState.value = _bgState.value.copy(
                    isRunning = false,
                    statusText = "✅ Поиск фонов завершён (стр. ${session.pageIndex}, чистых наполнителей: ${liveList.size} шт.)"
                )
            } catch (e: Exception) {
                DiagnosticLogger.logError("BG-CRAWLER", "Остановка фонового краулера", e)
                _bgState.value = _bgState.value.copy(
                    isRunning = false,
                    statusText = "⏹ Поиск фонов остановлен (чистых наполнителей: ${_bgState.value.results.size} шт.)"
                )
            }
        }
    }

    fun stopInfiniteBackgroundCrawl() {
        bgInfiniteJob?.cancel()
        bgInfiniteJob = null
        _bgState.value = _bgState.value.copy(
            isRunning = false,
            statusText = "⏹ Поиск фонов остановлен пользователем (${_bgState.value.results.size} ссылок)"
        )
    }

    fun banCandidateAndPurge(context: Context, candidate: DiscoveredMediaCandidate, banWholeChannel: Boolean) {
        SearchBlacklistStore.banVideo(context, candidate.id, candidate.url, candidate.title)
        if (banWholeChannel) {
            SearchBlacklistStore.banChannel(context, candidate.author)
        }
        val filterFn: (DiscoveredMediaCandidate) -> Boolean = { item ->
            item.id != candidate.id &&
                item.url != candidate.url &&
                (!banWholeChannel || !item.author.equals(candidate.author, ignoreCase = true))
        }
        _sourceState.value = _sourceState.value.copy(
            results = _sourceState.value.results.filter(filterFn)
        )
        _bgState.value = _bgState.value.copy(
            results = _bgState.value.results.filter(filterFn)
        )
    }
}
