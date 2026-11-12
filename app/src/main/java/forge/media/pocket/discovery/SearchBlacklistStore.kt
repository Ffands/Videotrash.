package forge.media.pocket.discovery

import android.content.Context
import android.content.SharedPreferences
import forge.media.pocket.config.DiagnosticLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/**
 * Persistent Blacklist & Negative-Tags Store shared across Compose UI, BackgroundCrawlerManager,
 * TopicHunterEngine, and TelegramBotWorker.
 *
 * Allows instant banning of unwanted videos or entire channels/authors, plus customizable
 * minus-keywords (e.g. `-концерт -стрим -реакция` or `-clutch -ace -donk -1v5`).
 */
object SearchBlacklistStore {
    private const val PREFS_NAME = "pocket_forge_search_blacklist"
    private const val KEY_BANNED_VIDEOS = "banned_video_keys"
    private const val KEY_BANNED_AUTHORS = "banned_author_names"
    private const val KEY_SOURCE_NEG_TAGS = "source_negative_tags"
    private const val KEY_BG_NEG_TAGS = "bg_negative_tags"

    @Volatile
    private var prefs: SharedPreferences? = null

    private val _bannedVideoKeys = MutableStateFlow<Set<String>>(emptySet())
    val bannedVideoKeys: StateFlow<Set<String>> = _bannedVideoKeys.asStateFlow()

    private val _bannedAuthors = MutableStateFlow<Set<String>>(emptySet())
    val bannedAuthors: StateFlow<Set<String>> = _bannedAuthors.asStateFlow()

    private val _sourceNegativeTags = MutableStateFlow("-концерт -стрим -полный -реакция")
    val sourceNegativeTags: StateFlow<String> = _sourceNegativeTags.asStateFlow()

    private val _bgNegativeTags = MutableStateFlow("-clutch -ace -1v5 -donk -highlights -reaction -монтаж")
    val bgNegativeTags: StateFlow<String> = _bgNegativeTags.asStateFlow()

    @Synchronized
    fun ensureInit(context: Context) {
        if (prefs != null) return
        val sp = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs = sp
        _bannedVideoKeys.value = sp.getStringSet(KEY_BANNED_VIDEOS, emptySet())?.toSet() ?: emptySet()
        _bannedAuthors.value = sp.getStringSet(KEY_BANNED_AUTHORS, emptySet())?.toSet() ?: emptySet()
        _sourceNegativeTags.value = sp.getString(KEY_SOURCE_NEG_TAGS, "-концерт -стрим -полный -реакция")
            ?: "-концерт -стрим -полный -реакция"
        _bgNegativeTags.value = sp.getString(KEY_BG_NEG_TAGS, "-clutch -ace -1v5 -donk -highlights -reaction -монтаж")
            ?: "-clutch -ace -1v5 -donk -highlights -reaction -монтаж"
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
        rawNegativeTags: String
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

        val negWords = parseNegativeWords(rawNegativeTags)
        if (negWords.isNotEmpty()) {
            val haystack = "$fullText $author".lowercase(Locale.ROOT)
            if (negWords.any { nw -> haystack.contains(nw) }) {
                return false
            }
        }
        return true
    }
}
