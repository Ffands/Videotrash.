package forge.media.pocket.discovery

import android.content.Context
import android.content.SharedPreferences
import forge.media.pocket.config.DiagnosticLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/**
 * Persistent Blacklist, Seen-History, Fuzzy-Deduplication & Guardrail Store
 * shared across Compose UI, BackgroundCrawlerManager, TopicHunterEngine, and TelegramBotWorker.
 *
 * Guarantees:
 * 1. Banned videos & channels never reappear.
 * 2. Seen-History (`seenVideoKeys` + `seenTitleSignatures`) ensures repeating a search NEVER dumps
 *    the same batch again — crawler automatically skips previously shown videos and digs deeper!
 * 3. Fuzzy Title Stem Matching (`areTitlesNearDuplicates`) kills reuploads of the same video across
 *    different channels (e.g. ТНТ vs Rutube vs fan reupload channels).
 * 4. Mass-Audience & Pure-Filler guardrails strip out corporate/niche boredom ("бизнес-стендап")
 *    and strip out cartoons/guides/story videos from Background Filler searches.
 */
object SearchBlacklistStore {
    private const val PREFS_NAME = "pocket_forge_search_blacklist"
    private const val KEY_BANNED_VIDEOS = "banned_video_keys"
    private const val KEY_BANNED_AUTHORS = "banned_author_names"
    private const val KEY_SOURCE_NEG_TAGS = "source_negative_tags"
    private const val KEY_BG_NEG_TAGS = "bg_negative_tags"
    private const val KEY_SEEN_VIDEOS = "seen_history_video_keys"
    private const val KEY_SEEN_SIGNATURES = "seen_history_title_sigs"
    private const val MAX_SEEN_HISTORY = 1500

    private const val DEFAULT_SOURCE_NEG =
        "-концерт -стрим -полный -реакция -бизнес -форум -конференция -лекция"
    private const val DEFAULT_BG_NEG =
        "-гайд -туториал -обзор -прохождение -сюжет -мультик -анимация -стрим -реакция -guide -tutorial -cartoon -story -highlights"

    /**
     * Niche / corporate / professional boredom terms that alienate ordinary casual Shorts viewers.
     * Filtered out automatically unless the user explicitly typed that term in their query.
     */
    private val NICHE_BORING_SOURCE_TERMS = listOf(
        "бизнес-стендап", "бизнес стендап", "корпоратив", "конференц", "форум",
        "маркетинг", "таргетолог", "недвижимост", "риэлтор", "тренинг", "коуч",
        "инфобиз", "ит-стендап", "it-стендап", "айти стендап", "вебинар", "лекция",
        "семинар", "пленарн", "круглый стол", "b2b", "стартап", "отдел продаж"
    )

    /**
     * Semantic / narrative / cartoon / tutorial terms that ruin background filler videos
     * (backgrounds must be pure hypnotic monotonous gameplay or ASMR filler without story/guides).
     */
    private val SEMANTIC_NON_FILLER_BG_TERMS = listOf(
        "мультик", "мультфильм", "мультсериал", "анимац", "аниме", "cartoon", "animat", "anime",
        "гайд", "туториал", "обучен", "как пройти", "как сделать", "настройк", "guide", "tutorial", "how to",
        "прохожден", "walkthrough", "сюжет", "концовк", "пасхалк", "разбор", "объяснен", "explained",
        "обзор", "review", "история", "лор", "lore", "серия", "сезон", "эпизод", "episode",
        "стрим", "stream", "реакц", "reaction", "facecam", "вебка", "прикол", "мем", "meme",
        "funny moments", "смешные моменты", "монтаж", "эдит", "edit", "clutch", "ace", "1v5",
        "highlights", "хайлайт", "тир лист", "tier list", "топ 10", "top 10", "топ 5", "top 5",
        "спидран", "speedrun", "баги", "читы", "моды", "mods", "trailer", "трейлер", "тизер", "cutscene", "катсцен"
    )

    private val TITLE_STOP_WORDS = setOf(
        "и", "в", "во", "на", "с", "со", "о", "об", "про", "по", "за", "из", "от", "до", "для",
        "не", "ни", "но", "а", "же", "ли", "бы", "то", "как", "что", "это", "все", "всё", "так",
        "его", "ее", "её", "их", "мы", "вы", "он", "она", "они", "ты", "я", "мне", "меня", "своем", "своём",
        "shorts", "short", "шортс", "шорт", "видео", "клип", "прикол", "юмор", "лучшее", "выпуск",
        "часть", "полный", "смотреть", "онлайн", "hd", "the", "and", "for", "with", "from", "this", "that"
    )

    @Volatile
    private var prefs: SharedPreferences? = null

    private val _bannedVideoKeys = MutableStateFlow<Set<String>>(emptySet())
    val bannedVideoKeys: StateFlow<Set<String>> = _bannedVideoKeys.asStateFlow()

    private val _bannedAuthors = MutableStateFlow<Set<String>>(emptySet())
    val bannedAuthors: StateFlow<Set<String>> = _bannedAuthors.asStateFlow()

    private val _seenVideoKeys = MutableStateFlow<LinkedHashSet<String>>(LinkedHashSet())
    private val _seenTitleSignatures = MutableStateFlow<LinkedHashSet<String>>(LinkedHashSet())

    private val _seenCount = MutableStateFlow(0)
    val seenCount: StateFlow<Int> = _seenCount.asStateFlow()

    private val _sourceNegativeTags = MutableStateFlow(DEFAULT_SOURCE_NEG)
    val sourceNegativeTags: StateFlow<String> = _sourceNegativeTags.asStateFlow()

    private val _bgNegativeTags = MutableStateFlow(DEFAULT_BG_NEG)
    val bgNegativeTags: StateFlow<String> = _bgNegativeTags.asStateFlow()

    @Synchronized
    fun ensureInit(context: Context) {
        if (prefs != null) return
        val sp = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs = sp
        _bannedVideoKeys.value = sp.getStringSet(KEY_BANNED_VIDEOS, emptySet())?.toSet() ?: emptySet()
        _bannedAuthors.value = sp.getStringSet(KEY_BANNED_AUTHORS, emptySet())?.toSet() ?: emptySet()

        val savedSeenKeys = sp.getStringSet(KEY_SEEN_VIDEOS, emptySet()) ?: emptySet()
        val savedSeenSigs = sp.getStringSet(KEY_SEEN_SIGNATURES, emptySet()) ?: emptySet()
        _seenVideoKeys.value = LinkedHashSet(savedSeenKeys)
        _seenTitleSignatures.value = LinkedHashSet(savedSeenSigs)
        _seenCount.value = _seenTitleSignatures.value.size.coerceAtLeast(_seenVideoKeys.value.size / 2)

        _sourceNegativeTags.value = sp.getString(KEY_SOURCE_NEG_TAGS, DEFAULT_SOURCE_NEG) ?: DEFAULT_SOURCE_NEG
        _bgNegativeTags.value = sp.getString(KEY_BG_NEG_TAGS, DEFAULT_BG_NEG) ?: DEFAULT_BG_NEG
    }

    fun normalizeUrlKey(url: String): String =
        url.trim()
            .lowercase(Locale.ROOT)
            .removePrefix("https://")
            .removePrefix("http://")
            .removePrefix("www.")
            .removePrefix("m.")
            .trimEnd('/')

    fun normalizeAuthorKey(author: String): String =
        author.trim().lowercase(Locale.ROOT).removePrefix("@").trim()

    /**
     * Extracts normalized root stems from a video title so reuploads across different channels
     * (e.g. ТНТ vs Rutube with slightly edited words) produce overlapping stem sets.
     */
    fun extractNormalizedTitleStems(title: String): Set<String> {
        if (title.isBlank()) return emptySet()
        val clean = title.lowercase(Locale.ROOT)
            .replace('ё', 'е')
            .replace(Regex("""#[a-zа-я0-9_]+"""), " ")
            .replace(Regex("""[^a-zа-я0-9\s]"""), " ")
        val stems = LinkedHashSet<String>()
        for (rawWord in clean.split(Regex("""\s+"""))) {
            val w = rawWord.trim()
            if (w.length < 3 || w in TITLE_STOP_WORDS) continue
            val stem = when {
                w.length >= 8 -> w.take(6)
                w.length >= 6 -> w.dropLast(2)
                w.length >= 5 -> w.dropLast(1)
                else -> w
            }
            if (stem.length >= 3) stems.add(stem)
        }
        return stems
    }

    fun buildCanonicalTitleSignature(title: String): String {
        val stems = extractNormalizedTitleStems(title).sorted().take(7)
        return stems.joinToString("_")
    }

    /**
     * Returns true when two video titles represent the same clip / standup monologue / skit
     * reuploaded on two different channels or platforms.
     */
    fun areTitlesNearDuplicates(titleA: String, titleB: String): Boolean {
        val stemsA = extractNormalizedTitleStems(titleA)
        val stemsB = extractNormalizedTitleStems(titleB)
        if (stemsA.size < 2 || stemsB.size < 2) {
            return titleA.trim().equals(titleB.trim(), ignoreCase = true)
        }
        val intersection = stemsA.intersect(stemsB).size
        val minSize = minOf(stemsA.size, stemsB.size)
        val unionSize = stemsA.union(stemsB).size
        val containment = intersection.toDouble() / minSize.toDouble()
        val jaccard = intersection.toDouble() / unionSize.toDouble()
        return containment >= 0.65 || jaccard >= 0.48 || (intersection >= 4 && containment >= 0.55)
    }

    @Synchronized
    fun wasCandidateAlreadyShown(
        context: Context,
        id: String,
        url: String,
        title: String
    ): Boolean {
        ensureInit(context)
        val seenKeys = _seenVideoKeys.value
        if (id.isNotBlank() && id.trim() in seenKeys) return true
        val normUrl = normalizeUrlKey(url)
        if (normUrl.isNotBlank() && normUrl in seenKeys) return true

        val sig = buildCanonicalTitleSignature(title)
        if (sig.length >= 7 && sig in _seenTitleSignatures.value) return true
        return false
    }

    @Synchronized
    fun markCandidatesAsShown(context: Context, candidates: List<DiscoveredMediaCandidate>) {
        if (candidates.isEmpty()) return
        ensureInit(context)
        val keys = LinkedHashSet(_seenVideoKeys.value)
        val sigs = LinkedHashSet(_seenTitleSignatures.value)
        for (c in candidates) {
            if (c.id.isNotBlank()) keys.add(c.id.trim())
            if (c.url.isNotBlank()) keys.add(normalizeUrlKey(c.url))
            val sig = buildCanonicalTitleSignature(c.title)
            if (sig.length >= 7) sigs.add(sig)
        }
        while (keys.size > MAX_SEEN_HISTORY * 2) {
            val first = keys.firstOrNull() ?: break
            keys.remove(first)
        }
        while (sigs.size > MAX_SEEN_HISTORY) {
            val first = sigs.firstOrNull() ?: break
            sigs.remove(first)
        }
        _seenVideoKeys.value = keys
        _seenTitleSignatures.value = sigs
        _seenCount.value = sigs.size.coerceAtLeast(keys.size / 2)
        prefs?.edit()
            ?.putStringSet(KEY_SEEN_VIDEOS, keys.toSet())
            ?.putStringSet(KEY_SEEN_SIGNATURES, sigs.toSet())
            ?.apply()
    }

    @Synchronized
    fun clearSeenHistory(context: Context) {
        ensureInit(context)
        _seenVideoKeys.value = LinkedHashSet()
        _seenTitleSignatures.value = LinkedHashSet()
        _seenCount.value = 0
        prefs?.edit()
            ?.remove(KEY_SEEN_VIDEOS)
            ?.remove(KEY_SEEN_SIGNATURES)
            ?.apply()
        DiagnosticLogger.log("HISTORY", "🔄 Память ранее показанных роликов сброшена")
    }

    @Synchronized
    fun banVideo(context: Context, id: String, url: String, title: String = "") {
        ensureInit(context)
        val updated = _bannedVideoKeys.value.toMutableSet()
        if (id.isNotBlank()) updated.add(id.trim())
        if (url.isNotBlank()) updated.add(normalizeUrlKey(url))
        _bannedVideoKeys.value = updated
        prefs?.edit()?.putStringSet(KEY_BANNED_VIDEOS, updated)?.apply()
        DiagnosticLogger.log("BLACKLIST", "🚫 Ролик добавлен в чёрный список: ${title.ifBlank { id }}")
    }

    @Synchronized
    fun clearBannedVideos(context: Context) {
        ensureInit(context)
        _bannedVideoKeys.value = emptySet()
        prefs?.edit()?.putStringSet(KEY_BANNED_VIDEOS, emptySet())?.apply()
        DiagnosticLogger.log("BLACKLIST", "🧹 Чёрный список роликов очищен")
    }

    @Synchronized
    fun banChannel(context: Context, author: String) {
        ensureInit(context)
        val norm = normalizeAuthorKey(author)
        if (norm.isBlank() || norm == "автор" || norm == "канал") return
        val updated = _bannedAuthors.value.toMutableSet().apply { add(norm) }
        _bannedAuthors.value = updated
        prefs?.edit()?.putStringSet(KEY_BANNED_AUTHORS, updated)?.apply()
        DiagnosticLogger.log("BLACKLIST", "🚫 Канал добавлен в чёрный список: «$author»")
    }

    @Synchronized
    fun unbanChannel(context: Context, author: String) {
        ensureInit(context)
        val norm = normalizeAuthorKey(author)
        val updated = _bannedAuthors.value.toMutableSet().apply { remove(norm) }
        _bannedAuthors.value = updated
        prefs?.edit()?.putStringSet(KEY_BANNED_AUTHORS, updated)?.apply()
        DiagnosticLogger.log("BLACKLIST", "♻️ Канал разбанен: «$author»")
    }

    @Synchronized
    fun clearBannedChannels(context: Context) {
        ensureInit(context)
        _bannedAuthors.value = emptySet()
        prefs?.edit()?.putStringSet(KEY_BANNED_AUTHORS, emptySet())?.apply()
        DiagnosticLogger.log("BLACKLIST", "🧹 Чёрный список каналов очищен")
    }

    fun setSourceNegativeTags(context: Context, raw: String) {
        ensureInit(context)
        _sourceNegativeTags.value = raw
        prefs?.edit()?.putString(KEY_SOURCE_NEG_TAGS, raw)?.apply()
    }

    fun setBgNegativeTags(context: Context, raw: String) {
        ensureInit(context)
        _bgNegativeTags.value = raw
        prefs?.edit()?.putString(KEY_BG_NEG_TAGS, raw)?.apply()
    }

    fun parseNegativeWords(raw: String): List<String> {
        if (raw.isBlank()) return emptyList()
        return raw.split(Regex("""[\s,;]+"""))
            .map { it.trim().removePrefix("-").removePrefix("#").lowercase(Locale.ROOT) }
            .filter { it.length >= 2 }
            .distinct()
    }

    fun formatMinusQuerySuffix(rawNegativeTags: String, maxTerms: Int = 6): String {
        val terms = parseNegativeWords(rawNegativeTags).take(maxTerms)
        if (terms.isEmpty()) return ""
        return terms.joinToString(" ") { "-$it" }
    }

    fun isCandidateAllowed(
        context: Context,
        id: String,
        url: String,
        author: String,
        fullText: String,
        rawNegativeTags: String,
        userQuery: String = "",
        isBackgroundFillerMode: Boolean = false
    ): Boolean {
        ensureInit(context)
        val vids = _bannedVideoKeys.value
        if (id.isNotBlank() && id.trim() in vids) return false
        if (url.isNotBlank() && normalizeUrlKey(url) in vids) return false

        val normAuthor = normalizeAuthorKey(author)
        val bannedCh = _bannedAuthors.value
        if (normAuthor.isNotBlank() && bannedCh.any { b -> normAuthor == b || (b.length >= 4 && normAuthor.contains(b)) }) {
            return false
        }

        val haystack = "$fullText $author".lowercase(Locale.ROOT).replace('ё', 'е')
        val queryLow = userQuery.lowercase(Locale.ROOT).replace('ё', 'е')

        val negWords = parseNegativeWords(rawNegativeTags)
        if (negWords.isNotEmpty() && negWords.any { nw -> haystack.contains(nw.replace('ё', 'е')) }) {
            return false
        }

        if (isBackgroundFillerMode) {
            // Reject cartoons, tutorials, guides, reviews, story videos, streams, highlights unless user explicitly asked
            val hitSemanticGarbage = SEMANTIC_NON_FILLER_BG_TERMS.any { bad ->
                !queryLow.contains(bad) && haystack.contains(bad)
            }
            if (hitSemanticGarbage) return false
        } else {
            // Reject corporate/business/coaching niche boredom unless user explicitly asked for it
            val hitNicheBoredom = NICHE_BORING_SOURCE_TERMS.any { niche ->
                !queryLow.contains(niche) && haystack.contains(niche)
            }
            if (hitNicheBoredom) return false
        }

        return true
    }
}
