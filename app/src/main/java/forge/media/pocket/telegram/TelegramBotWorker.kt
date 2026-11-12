package forge.media.pocket.telegram

import android.content.Context
import android.util.Log
import forge.media.pocket.clutter.ClutterEntropyFilter
import forge.media.pocket.clutter.StudioInboxItem
import forge.media.pocket.compositor.FFmpegHardwareEngine
import forge.media.pocket.compositor.ModularRenderConfig
import forge.media.pocket.config.DiagnosticLogger
import forge.media.pocket.config.NodeConfigStore
import forge.media.pocket.discovery.ContentSortOrder
import forge.media.pocket.discovery.DiscoveredMediaCandidate
import forge.media.pocket.discovery.DiscoveredSpamChannel
import forge.media.pocket.discovery.HuntPlatform
import forge.media.pocket.discovery.SearchBlacklistStore
import forge.media.pocket.discovery.SearchDurationMode
import forge.media.pocket.discovery.SearchLanguageMode
import forge.media.pocket.discovery.SearchTimePeriod
import forge.media.pocket.discovery.TopicHunterEngine
import forge.media.pocket.service.PipelineForegroundService
import forge.media.pocket.subtitles.SubtitleAssTransformer
import forge.media.pocket.vision.FaceCenteringHelper
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.forms.ChannelProvider
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.jvm.javaio.copyTo
import io.ktor.utils.io.jvm.javaio.toByteReadChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

data class PendingHuntFilterState(
    val queryTags: String,
    var platform: HuntPlatform = HuntPlatform.ALL,
    var sortOrder: ContentSortOrder = ContentSortOrder.POPULAR,
    var period: SearchTimePeriod = SearchTimePeriod.MONTH,
    var durationMode: SearchDurationMode = SearchDurationMode.ANY_UP_TO_6M,
    var languageMode: SearchLanguageMode = SearchLanguageMode.RUSSIAN_ONLY,
    var count: Int = 10
)

data class PendingDirectUrlState(
    val url: String
)

class TelegramBotWorker(
    private val context: Context,
    private val configStore: NodeConfigStore,
    private val clutterFilter: ClutterEntropyFilter,
    private val topicHunter: TopicHunterEngine,
    private val faceHelper: FaceCenteringHelper,
    private val subtitleTransformer: SubtitleAssTransformer,
    private val hardwareEngine: FFmpegHardwareEngine,
    private val geminiClient: GeminiMetadataClient,
    private val enqueueHardwareTask: (jobId: String, stage: String, block: suspend () -> Unit) -> Unit
) {

    companion object {
        private const val TAG = "TelegramBotWorker"

        private const val BTN_MENU_HUNT = "🧠 Умный поиск по теме"
        private const val BTN_MENU_BG_SEARCH = "🎬 Поиск фонов / Залипалок"
        private const val BTN_MENU_SPAM_FARMS = "📡 Спам-каналы / Фермы"
        private const val BTN_MENU_LINK = "📥 Скачать по ссылке (1080p)"
        private const val BTN_MENU_INBOX = "📂 Исходники в Студии"
        private const val BTN_MENU_STATUS = "📊 Статус и Лог"

        private val QUICK_HUNT_PRESETS = mapOf(
            "standup_ru" to ("стендап монологи истории о жизни" to SearchLanguageMode.RUSSIAN_ONLY),
            "standup_en" to ("standup comedy crowd work monologue" to SearchLanguageMode.ENGLISH_FOR_DUB),
            "podcast_ru" to ("интересная мысль история из подкаста" to SearchLanguageMode.RUSSIAN_ONLY),
            "cs2_ru" to ("лучшие моменты клатчи CS2" to SearchLanguageMode.RUSSIAN_ONLY),
            "sci_ru" to ("научпоп космос физика интересные факты" to SearchLanguageMode.RUSSIAN_ONLY)
        )

        private val QUICK_SPAM_PRESETS = mapOf(
            "standup" to "стендап комики истории нарезка",
            "moto" to "мотоцикл дрифт ситуации на дороге",
            "podcast" to "подкаст интересный факт мысли",
            "cs2" to "CS2 клатч моменты нарезка"
        )

        private val QUICK_BG_PRESETS = mapOf(
            "moto" to "мотоцикл ночной прохват от первого лица",
            "car" to "дрифт авто beamng drive",
            "asmr" to "резка мыла кинетический песок антистресс",
            "cs2" to "cs2 surf плавный геймплей",
            "mc" to "minecraft паркур без слов",
            "press" to "гидравлический пресс шредер"
        )
    }

    private val isPolling = AtomicBoolean(false)
    private var pollingJob: Job? = null
    private var lastUpdateOffset: Long = 0L

    private val huntFilters = ConcurrentHashMap<String, PendingHuntFilterState>()
    private val discoveredBatches = ConcurrentHashMap<String, List<DiscoveredMediaCandidate>>()
    private val discoveredBgBatches = ConcurrentHashMap<String, List<DiscoveredMediaCandidate>>()
    private val discoveredBgTopics = ConcurrentHashMap<String, String>()
    private val discoveredSpamBatches = ConcurrentHashMap<String, List<DiscoveredSpamChannel>>()
    private val batchTopics = ConcurrentHashMap<String, String>()
    private val downloadedInboxItems = ConcurrentHashMap<String, StudioInboxItem>()
    private val pendingLinks = ConcurrentHashMap<String, PendingDirectUrlState>()
    private val awaitingBgQueryByChat = ConcurrentHashMap<Long, Boolean>()

    private val jsonFormat = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    private val httpClient = HttpClient(OkHttp) {
        install(HttpTimeout) {
            requestTimeoutMillis = 120_000L
            connectTimeoutMillis = 25_000L
            socketTimeoutMillis = 120_000L
        }
        install(ContentNegotiation) {
            json(jsonFormat)
        }
    }

    @Serializable
    private data class TgUpdatesResponse(
        val ok: Boolean = false,
        val result: List<TgUpdate> = emptyList()
    )

    @Serializable
    private data class TgUpdate(
        @SerialName("update_id") val updateId: Long,
        val message: TgMessage? = null,
        val channelPost: TgMessage? = null,
        @SerialName("callback_query") val callbackQuery: TgCallbackQuery? = null
    )

    @Serializable
    private data class TgMessage(
        @SerialName("message_id") val messageId: Long,
        val chat: TgChat,
        val text: String? = null,
        val document: TgDocument? = null
    )

    @Serializable
    private data class TgDocument(
        @SerialName("file_id") val fileId: String,
        @SerialName("file_name") val fileName: String? = null
    )

    @Serializable
    private data class TgGetFileResponse(
        val ok: Boolean = false,
        val result: TgFilePathObj? = null
    )

    @Serializable
    private data class TgFilePathObj(
        @SerialName("file_path") val filePath: String? = null
    )

    @Serializable
    private data class TgChat(
        val id: Long
    )

    @Serializable
    private data class TgCallbackQuery(
        val id: String,
        val message: TgMessage? = null,
        val data: String? = null
    )

    @Serializable
    private data class InlineKeyboardMarkup(
        @SerialName("inline_keyboard") val inlineKeyboard: List<List<InlineKeyboardButton>>
    )

    @Serializable
    private data class InlineKeyboardButton(
        val text: String,
        @SerialName("callback_data") val callbackData: String
    )

    @Serializable
    private data class ReplyKeyboardMarkup(
        val keyboard: List<List<KeyboardButton>>,
        @SerialName("resize_keyboard") val resizeKeyboard: Boolean = true,
        @SerialName("is_persistent") val isPersistent: Boolean = true
    )

    @Serializable
    private data class KeyboardButton(
        val text: String
    )

    private val persistentMainMenuKeyboard = ReplyKeyboardMarkup(
        keyboard = listOf(
            listOf(KeyboardButton(BTN_MENU_HUNT), KeyboardButton(BTN_MENU_BG_SEARCH)),
            listOf(KeyboardButton(BTN_MENU_SPAM_FARMS), KeyboardButton(BTN_MENU_LINK)),
            listOf(KeyboardButton(BTN_MENU_INBOX), KeyboardButton(BTN_MENU_STATUS))
        ),
        resizeKeyboard = true,
        isPersistent = true
    )

    /**
     * Гарантирует жёсткий лимит Telegram Bot API: `callback_data` <= 64 байт в UTF-8.
     */
    private fun btn(label: String, rawCallback: String): InlineKeyboardButton {
        val bytes = rawCallback.toByteArray(Charsets.UTF_8)
        val safeData = if (bytes.size <= 64) {
            rawCallback
        } else {
            var acc = ""
            for (ch in rawCallback) {
                val candidate = acc + ch
                if (candidate.toByteArray(Charsets.UTF_8).size > 62) break
                acc = candidate
            }
            acc
        }
        return InlineKeyboardButton(text = label, callbackData = safeData)
    }

    private fun baseUrl(): String =
        "https://api.telegram.org/bot${configStore.getTelegramBotToken()}"

    private fun isChatAuthorized(chatId: Long): Boolean {
        val allowed = configStore.getAllowedChatIdLong()
        return allowed == 0L || allowed == chatId
    }

    fun startPolling(scope: CoroutineScope) {
        if (configStore.getTelegramBotToken().isBlank()) {
            DiagnosticLogger.log("TELEGRAM", "TELEGRAM_BOT_TOKEN пуст — поллинг не запущен.")
            return
        }
        if (!isPolling.compareAndSet(false, true)) return

        pollingJob = scope.launch(Dispatchers.IO) {
            DiagnosticLogger.log("TELEGRAM", "Запущен цикл Long-Polling Telegram API")
            while (isActive && isPolling.get()) {
                try {
                    val response: TgUpdatesResponse = httpClient.get("${baseUrl()}/getUpdates") {
                        parameter("offset", lastUpdateOffset)
                        parameter("timeout", 25)
                    }.body()

                    if (response.ok) {
                        for (update in response.result) {
                            lastUpdateOffset = update.updateId + 1
                            processUpdate(scope, update)
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Long-polling network retry in 4s: ${e.message}")
                    delay(4000L)
                }
            }
        }
    }

    fun stopPolling() {
        isPolling.set(false)
        pollingJob?.cancel()
        pollingJob = null
    }

    private suspend fun processUpdate(scope: CoroutineScope, update: TgUpdate) {
        val incomingMsg = update.message ?: update.channelPost
        incomingMsg?.let { msg ->
            val chatId = msg.chat.id
            if (!isChatAuthorized(chatId)) {
                DiagnosticLogger.log("TELEGRAM", "Игнорировано сообщение от чужого chatId=$chatId")
                return
            }

            msg.document?.let { doc ->
                val fName = doc.fileName ?: "file"
                if (fName.endsWith(".txt", ignoreCase = true)) {
                    handleCookiesDocumentUpload(chatId, doc)
                    return
                }
            }

            val text = msg.text?.trim() ?: return
            DiagnosticLogger.log("TELEGRAM:IN", "Получено сообщение от $chatId: '$text'")

            // Check if user was prompted to type a custom background query, unless they clicked a main menu button
            val isMainMenuTap = text in setOf(
                BTN_MENU_HUNT, BTN_MENU_BG_SEARCH, BTN_MENU_SPAM_FARMS,
                BTN_MENU_LINK, BTN_MENU_INBOX, BTN_MENU_STATUS, "📜 Полный лог", "📊 Статус и Фон"
            ) || text.startsWith("/")

            if (!isMainMenuTap && awaitingBgQueryByChat.remove(chatId) == true) {
                executeBackgroundSearchInTelegram(chatId, text)
                return
            }

            when {
                text == "/start" || text == "/help" || text == "/menu" -> {
                    awaitingBgQueryByChat.remove(chatId)
                    sendWelcomeMenu(chatId)
                }

                text == "📜 Полный лог" || text.startsWith("/log") -> {
                    sendFullDiagnosticLog(chatId)
                }

                text == BTN_MENU_STATUS || text == "📊 Статус и Фон" || text.startsWith("/status") -> {
                    sendNodeStatusCard(chatId)
                }

                text == BTN_MENU_INBOX || text == "📂 Мои исходники в Студии" || text.startsWith("/inbox") -> {
                    sendStudioInboxSummary(chatId)
                }

                text == BTN_MENU_BG_SEARCH || text.startsWith("/bg") -> {
                    val arg = text.removePrefix(BTN_MENU_BG_SEARCH).removePrefix("/bg").trim()
                    if (arg.isNotBlank()) {
                        executeBackgroundSearchInTelegram(chatId, arg)
                    } else {
                        sendBackgroundFinderMenu(chatId)
                    }
                }

                text == BTN_MENU_HUNT || text == "🔎 Найти видео по теме" -> {
                    awaitingBgQueryByChat.remove(chatId)
                    sendQuickTopicPicker(chatId)
                }

                text == BTN_MENU_SPAM_FARMS || text.startsWith("/spam") -> {
                    awaitingBgQueryByChat.remove(chatId)
                    val arg = text.removePrefix(BTN_MENU_SPAM_FARMS).removePrefix("/spam").trim()
                    if (arg.isNotBlank()) {
                        executeSpamFarmChannelHunt(chatId, arg)
                    } else {
                        sendSpamFarmPickerMessage(chatId)
                    }
                }

                text == BTN_MENU_LINK -> {
                    awaitingBgQueryByChat.remove(chatId)
                    sendMessage(
                        chatId = chatId,
                        text = "📥 Вставь сюда любую ссылку на YouTube / Shorts / Rutube / Reddit:\n" +
                            "• Сможешь за 2 секунды получить выжимку «🧠 Что внутри + Таймкоды» по субтитрам\n" +
                            "• Либо скачать исходник в 1080p в Студию\n" +
                            "• Либо закинуть этот ролик в пул фонов/залипалок!"
                    )
                }

                text.contains("http://") || text.contains("https://") -> {
                    handleDirectLinkMessage(chatId, text)
                }

                text.startsWith("/hunt") -> {
                    handleHuntCommand(chatId, text)
                }

                !text.startsWith("/") -> {
                    handleHuntCommand(chatId, text)
                }

                else -> {
                    sendWelcomeMenu(chatId)
                }
            }
        }

        update.callbackQuery?.let { cb ->
            answerCallbackQuery(cb.id)
            val chatId = cb.message?.chat?.id ?: return
            val messageId = cb.message.messageId
            if (!isChatAuthorized(chatId)) return
            val data = cb.data ?: return
            DiagnosticLogger.log("TELEGRAM:BTN", "Нажата кнопка: '$data'")
            handleInlineButtonCallback(scope, chatId, messageId, data)
        }
    }

    private suspend fun handleCookiesDocumentUpload(chatId: Long, doc: TgDocument) {
        try {
            DiagnosticLogger.log("COOKIES", "Загрузка файла ${doc.fileName} из Telegram...")
            val fileResp: TgGetFileResponse = httpClient.get("${baseUrl()}/getFile") {
                parameter("file_id", doc.fileId)
            }.body()
            val filePath = fileResp.result?.filePath
            if (filePath.isNullOrBlank()) {
                sendMessage(chatId, "❌ Не удалось получить путь к файлу в Telegram.")
                return
            }
            val downloadUrl = "https://api.telegram.org/file/bot${configStore.getTelegramBotToken()}/$filePath"
            val targetCookies = File(context.filesDir, "youtube_cookies.txt")
            val response = httpClient.get(downloadUrl)
            targetCookies.outputStream().use { out ->
                response.bodyAsChannel().copyTo(out)
            }
            DiagnosticLogger.log("COOKIES", "✅ Файл youtube_cookies.txt сохранён (${targetCookies.length()} байт)")
            sendMessage(
                chatId,
                "✅ Файл cookies сохранён (${targetCookies.length() / 1024} KB)!\nТеперь все запросы yt-dlp к YouTube идут с твоими куками."
            )
        } catch (e: Exception) {
            DiagnosticLogger.logError("COOKIES", "Ошибка сохранения cookies.txt", e)
            sendMessage(chatId, "❌ Ошибка сохранения cookies.txt: ${e.message}")
        }
    }

    private suspend fun sendWelcomeMenu(chatId: Long) {
        val aiReady = configStore.getGeminiApiKey().isNotBlank()
        sendMessage(
            chatId = chatId,
            text = "⚡ Pocket Media Forge — Скаут + Студия!\n" +
                "• Gemini AI: ${if (aiReady) "🟢 АКТИВЕН" else "⚪ Выкл (введи ключ в приложении)"}\n\n" +
                "Главное меню внизу:\n" +
                "• «🧠 Умный поиск по теме» (или просто напиши любую тему в чат) — ищет СТРОГО по твоим параметрам (свежесть за месяц, реальные просмотры, до 10–15 шт. с разных площадок: YouTube + Rutube + Reddit).\n" +
                "• «🎬 Поиск фонов / Залипалок» (или `/bg тема`) — поиск и предпросмотр залипалок с добавлением в пул по кнопке.\n" +
                "• «📡 Спам-каналы / Фермы» — поиск потоковых каналов нарезок.\n" +
                "• «🧠 Что внутри #N» под каждым видео — быстрый разбор речи по субтитрам с точными таймкодами!"
        )
    }

    // =========================================================================
    // ПОИСК И УПРАВЛЕНИЕ ФОНАМИ / ЗАЛИПАЛКАМИ ПРЯМО В TELEGRAM
    // =========================================================================

    private suspend fun sendBackgroundFinderMenu(chatId: Long) {
        val poolClips = clutterFilter.listVerifiedPoolClips()
        val text = "🎬 ПОИСК И ПОДБОР ФОНОВ / ЗАЛИПАЛОК (1080×960)\n" +
            "Сейчас готовых фонов в пуле Студии: ${poolClips.size} шт.\n\n" +
            "Выбери категорию кнопкой ниже ИЛИ нажми «✍️ Свой запрос фона» (или напиши `/bg твоя тема`, например `/bg ночной мотоцикл` / `/bg ковка металла`):"

        val keyboard = InlineKeyboardMarkup(
            inlineKeyboard = listOf(
                listOf(
                    btn("✍️ Свой запрос фона", "bg_ask:0"),
                    btn("🗂 Мои фоны в пуле (${poolClips.size})", "bg_list:0")
                ),
                listOf(
                    btn("🏍 Мотоцикл POV", "bgq:moto"),
                    btn("🚗 Авто / BeamNG / Дрифт", "bgq:car")
                ),
                listOf(
                    btn("🔪 Мыло / Песок ASMR", "bgq:asmr"),
                    btn("🎮 CS2 Surf", "bgq:cs2")
                ),
                listOf(
                    btn("⛏ Minecraft Паркур", "bgq:mc"),
                    btn("⚙️ Гидравлический пресс", "bgq:press")
                )
            )
        )
        sendMessageWithKeyboard(chatId, text, keyboard)
    }

    private suspend fun sendPoolClipsSummaryInTelegram(chatId: Long) {
        val clips = clutterFilter.listVerifiedPoolClips()
        if (clips.isEmpty()) {
            sendBackgroundFinderMenu(chatId)
            return
        }
        val sb = StringBuilder("🗂 Фоны в пуле Студии (${clips.size} шт.):\n\n")
        val rows = mutableListOf<List<InlineKeyboardButton>>()

        clips.take(8).forEachIndexed { idx, f ->
            val meta = clutterFilter.getPoolClipMetadata(f)
            val mb = String.format(Locale.US, "%.1f", f.length() / (1024.0 * 1024.0))
            sb.append("${idx + 1}. ${meta.title.take(48)} ($mb МБ)\n")
            sb.append("   🏷 Теги: ${meta.tags.joinToString(" ")}\n\n")
            rows.add(
                listOf(
                    btn("🗑 Удалить фон #${idx + 1}", "bg_del:$idx")
                )
            )
        }
        rows.add(listOf(btn("🔎 Искать новые фоны", "bg_menu:0")))
        sendMessageWithKeyboard(chatId, sb.toString().take(3900), InlineKeyboardMarkup(rows))
    }

    private suspend fun executeBackgroundSearchInTelegram(chatId: Long, topicQuery: String) {
        sendMessage(
            chatId,
            "🎬 Ищу динамичные фоны/залипалки (10–90 сек, >=720p) по запросу:\n«$topicQuery»..."
        )

        enqueueHardwareTask("bg_search", "Searching backgrounds for '$topicQuery'") {
            try {
                val found = topicHunter.searchBackgroundCandidates(topicQuery, limitCount = 6)
                if (found.isEmpty()) {
                    sendMessage(
                        chatId,
                        "⚠️ Коротких фонов по запросу «$topicQuery» не нашлось. Попробуй сформулировать проще (например `/bg motorcycle pov` или `/bg паркур майнкрафт`)."
                    )
                    return@enqueueHardwareTask
                }

                val bgBatchId = UUID.randomUUID().toString().take(6)
                discoveredBgBatches[bgBatchId] = found
                discoveredBgTopics[bgBatchId] = topicQuery

                val msg = buildString {
                    append("🎬 Найдено ${found.size} фонов/залипалок по теме «$topicQuery»:\n")
                    append("Можешь открыть ссылку для предпросмотра и нажать «➕ В пул #N», чтобы скачать и откалибровать фон под Студию:\n\n")
                    found.forEachIndexed { idx, c ->
                        val n = idx + 1
                        append("$n. 🎞 ${c.title.take(65)}\n")
                        append("   ⏱ ${formatDurationMmSs(c.durationSec)} • 👁 ${formatViewsCount(c.viewOrScoreCount)}\n")
                        append("   🔗 ${c.url}\n\n")
                    }
                }

                val rows = mutableListOf<List<InlineKeyboardButton>>()
                val pairChunked = found.indices.chunked(2)
                for (pair in pairChunked) {
                    rows.add(
                        pair.map { idx ->
                            btn("➕ В пул фон #${idx + 1}", "bg_add:${bgBatchId}_$idx")
                        }
                    )
                }
                rows.add(listOf(btn("📦 Скачать ТОП-3 фона в пул", "bg_top3:$bgBatchId")))
                rows.add(listOf(btn("🔎 Другая тема фона", "bg_menu:0")))

                sendMessageWithKeyboard(chatId, msg.take(3950), InlineKeyboardMarkup(rows))
            } catch (e: Exception) {
                DiagnosticLogger.logError("BG-SEARCH", "Ошибка поиска фонов в боте", e)
                sendMessage(chatId, "❌ Ошибка поиска фонов: ${e.message}")
            }
        }
    }

    private suspend fun sendStudioInboxSummary(chatId: Long) {
        val items = clutterFilter.listStudioInboxItems()
        if (items.isEmpty()) {
            sendMessage(
                chatId,
                "📂 В Студии приложения пока нет загруженных исходников.\nОпиши любую тему в чат или скинь ссылку, чтобы скачать исходники в 1080p!"
            )
            return
        }

        val sb = StringBuilder("📂 Загруженные исходники в Студии телефона (${items.size} шт.):\n\n")
        items.take(10).forEachIndexed { idx, item ->
            val mb = String.format(Locale.US, "%.1f", item.sizeKb / 1024.0)
            sb.append("${idx + 1}. «${item.title.take(50)}» (${item.durationSec.toInt()}с • $mb МБ)\n")
            if (item.recommendedEndSec > item.recommendedStartSec) {
                sb.append("   ✂️ Таймкод: ${formatDurationMmSs(item.recommendedStartSec.toInt())} – ${formatDurationMmSs(item.recommendedEndSec.toInt())}\n")
            }
            if (item.aiSummary.isNotBlank()) {
                sb.append("   🧠 Суть: ${item.aiSummary.take(100)}\n")
            }
            if (item.sourceUrl.startsWith("http")) {
                sb.append("   🔗 ${item.sourceUrl}\n")
            }
            sb.append("\n")
        }
        sb.append("📱 Открой приложение Pocket Media Forge -> вкладка «🎛 Студия» (живой предпросмотр 2 слоёв + обрезка + кроп + ластик вотермарки)!")
        sendMessage(chatId, sb.toString())
    }

    private suspend fun sendFullDiagnosticLog(chatId: Long) {
        val ytdlpVer = PipelineForegroundService.queryRealYtDlpVersion()
        val cookiesFile = configStore.getYoutubeCookiesFile()
        val cookiesStatus = if (cookiesFile != null) "ВКЛ (${cookiesFile.length() / 1024} KB)" else "НЕТ"

        val recentLog = DiagnosticLogger.getRecentLinesText(38)
        val header = "📜 Журнал действий узла:\n" +
            "• Версия yt-dlp: $ytdlpVer\n" +
            "• YouTube Cookies: $cookiesStatus\n" +
            "• Исходников в Студии: ${clutterFilter.listStudioInboxItems().size} шт.\n" +
            "• Фонов в пуле: ${clutterFilter.listVerifiedPoolClips().size} шт.\n" +
            "────────────────────\n"

        val bodyText = (header + recentLog).takeLast(3950)

        val keyboard = InlineKeyboardMarkup(
            inlineKeyboard = listOf(
                listOf(
                    btn("🔄 Обновить лог", "log_refresh:0"),
                    btn("📄 Скачать .log файл", "log_file:0")
                ),
                listOf(
                    btn("⬆️ Обновить ядро yt-dlp", "ytdlp_update:0"),
                    btn("🧹 Очистить лог", "log_clear:0")
                )
            )
        )

        sendMessageWithKeyboard(chatId, bodyText, keyboard)
    }

    private suspend fun sendNodeStatusCard(chatId: Long) {
        val poolSize = clutterFilter.listVerifiedPoolClips().size
        val inboxSize = clutterFilter.listStudioInboxItems().size
        val svcState = PipelineForegroundService.statusFlow.value
        val ytdlpVer = PipelineForegroundService.queryRealYtDlpVersion()
        val cookiesLoaded = configStore.getYoutubeCookiesFile() != null
        val geminiLoaded = configStore.getGeminiApiKey().isNotBlank()

        val text = "📊 Статус узла (OnePlus Snapdragon):\n\n" +
            "• Конвейер: $svcState\n" +
            "• Ядро yt-dlp: $ytdlpVer\n" +
            "• Gemini API (Авто-подбор модели): ${if (geminiLoaded) "🟢 Подключён" else "⚪ Не задан"}\n" +
            "• Куки YouTube: ${if (cookiesLoaded) "✅ Загружены" else "Innertube + tv_samsung"}\n" +
            "• Исходников в Студии (1080p): $inboxSize шт.\n" +
            "• Залипалок в пуле (1080×960): $poolSize шт."

        val keyboard = InlineKeyboardMarkup(
            inlineKeyboard = listOf(
                listOf(
                    btn("📂 Исходники в Студии ($inboxSize)", "show_inbox:0"),
                    btn("🎬 Найти фоны ($poolSize)", "bg_menu:0")
                ),
                listOf(
                    btn("🗂 Мои фоны ($poolSize)", "bg_list:0"),
                    btn("📜 Открыть лог", "log_refresh:0")
                )
            )
        )
        sendMessageWithKeyboard(chatId, text, keyboard)
    }

    private suspend fun sendQuickTopicPicker(chatId: Long) {
        val text = "🧠 Опиши в чат ЛЮБУЮ тематику своими словами!\n\n" +
            "• Строгий серверный фильтр даты загрузки (никаких роликов 3-летней давности при выборе «За месяц»!).\n" +
            "• Реальные просмотры без накрутки и честный расчёт охвата.\n" +
            "• Разные каналы + мульти-площадки (YouTube + Rutube + Reddit).\n\n" +
            "Или выбери готовый пресет кнопкой ниже:"
        val keyboard = InlineKeyboardMarkup(
            inlineKeyboard = listOf(
                listOf(
                    btn("😂 Стендап: монологи (RU)", "qh:standup_ru"),
                    btn("🇺🇸 EN Стендап (под дубляж)", "qh:standup_en")
                ),
                listOf(
                    btn("🎙 Мысли из подкастов", "qh:podcast_ru"),
                    btn("🎮 Моменты и клатчи CS2", "qh:cs2_ru")
                ),
                listOf(
                    btn("🧪 Научпоп: космос и физика", "qh:sci_ru")
                )
            )
        )
        sendMessageWithKeyboard(chatId, text, keyboard)
    }

    private suspend fun handleHuntCommand(
        chatId: Long,
        rawText: String,
        defaultLanguage: SearchLanguageMode = SearchLanguageMode.RUSSIAN_ONLY
    ) {
        val queryRaw = rawText.removePrefix("/hunt").trim()
        if (queryRaw.isBlank()) {
            sendQuickTopicPicker(chatId)
            return
        }

        val huntId = UUID.randomUUID().toString().take(6)
        val cleanQuery = queryRaw.replace("#", " ").replace(Regex("""\s+"""), " ").trim()
        val autoDetectedLang = if (
            defaultLanguage == SearchLanguageMode.RUSSIAN_ONLY &&
            cleanQuery.any { it in 'a'..'z' || it in 'A'..'Z' } &&
            !cleanQuery.any { it in 'а'..'я' || it in 'А'..'Я' || it == 'ё' || it == 'Ё' }
        ) {
            SearchLanguageMode.ENGLISH_FOR_DUB
        } else {
            defaultLanguage
        }
        val state = PendingHuntFilterState(
            queryTags = cleanQuery,
            languageMode = autoDetectedLang,
            sortOrder = ContentSortOrder.POPULAR,
            period = SearchTimePeriod.MONTH,
            count = 10
        )
        huntFilters[huntId] = state

        sendOrEditHuntConfigurator(chatId, editMessageId = null, huntId = huntId, state = state)
    }

    private suspend fun sendOrEditHuntConfigurator(
        chatId: Long,
        editMessageId: Long?,
        huntId: String,
        state: PendingHuntFilterState
    ) {
        val text = buildString {
            append("🔎 Настройка точного поиска:\n«").append(state.queryTags).append("»\n\n")
            append("• Сортировка: ").append(state.sortOrder.label).append("\n")
            append("• Период загрузки: ").append(state.period.label).append(" (строгий серверный фильтр!)\n")
            append("• Площадки: ").append(state.platform.label).append("\n")
            append("• Язык: ").append(state.languageMode.label).append("\n")
            append("• Хронометраж: ").append(state.durationMode.label).append("\n")
            append("• Количество роликов: ").append(state.count).append(" шт. (макс. 2 с одного канала)\n\n")
            append("👇 Настрой параметры кнопками (сообщение обновляется на месте) и жми запуск:")
        }

        val keyboard = InlineKeyboardMarkup(
            inlineKeyboard = listOf(
                listOf(
                    btn("🚀 Найти (${state.count} шт. • ${state.period.label})", "hunt_go:$huntId")
                ),
                listOf(
                    btn("Сорт: ${state.sortOrder.label}", "hunt_sort:$huntId"),
                    btn("Период: ${state.period.label}", "hunt_period:$huntId")
                ),
                listOf(
                    btn("Площадки: ${state.platform.label}", "hunt_plat:$huntId"),
                    btn("Кол-во: ${state.count} шт.", "hunt_cnt:$huntId")
                ),
                listOf(
                    btn("Язык: ${state.languageMode.label}", "hunt_lang:$huntId")
                ),
                listOf(
                    btn("Тип: ${state.durationMode.label}", "hunt_dur:$huntId")
                )
            )
        )

        if (editMessageId != null) {
            editMessageWithKeyboard(chatId, editMessageId, text, keyboard)
        } else {
            sendMessageWithKeyboard(chatId, text, keyboard)
        }
    }

    private fun formatDurationMmSs(totalSec: Int): String {
        val m = totalSec / 60
        val s = totalSec % 60
        return String.format(Locale.US, "%02d:%02d", m, s)
    }

    private fun formatViewsCount(views: Long): String {
        return when {
            views >= 1_000_000L -> String.format(Locale.US, "%.1fM", views / 1_000_000.0)
            views >= 1_000L -> String.format(Locale.US, "%.1fK", views / 1_000.0)
            else -> views.toString()
        }
    }

    private suspend fun executeTagSearchAndSendLinks(
        chatId: Long,
        huntId: String,
        state: PendingHuntFilterState
    ) {
        sendMessage(
            chatId,
            "🔎 Ищу СТРОГО по параметрам: «${state.queryTags}»\n" +
                "• Период: ${state.period.label} | Сорт: ${state.sortOrder.label}\n" +
                "• Площадки: ${state.platform.label} | Цель: ${state.count} шт. (без старья и без мёртвых 200-просмотровых видео)..."
        )

        enqueueHardwareTask("search", "Strict Search for '${state.queryTags}'") {
            try {
                val candidates = topicHunter.huntByTags(
                    queryTags = state.queryTags,
                    platform = state.platform,
                    sortOrder = state.sortOrder,
                    period = state.period,
                    durationMode = state.durationMode,
                    languageMode = state.languageMode,
                    limitCount = state.count
                )

                if (candidates.isEmpty()) {
                    val relaxKb = InlineKeyboardMarkup(
                        inlineKeyboard = listOf(
                            listOf(
                                btn("🔓 Расширить период до «За год» и повторить", "hunt_relax:$huntId")
                            )
                        )
                    )
                    sendMessageWithKeyboard(
                        chatId,
                        "⚠️ За период «${state.period.label}» по запросу «${state.queryTags}» живых видео с нормальными просмотрами реально 0 шт. (я специально НЕ стал подсовывать 3-летнее старьё!).\n\nХочешь расширить период до «За год»?",
                        relaxKb
                    )
                    return@enqueueHardwareTask
                }

                val batchId = UUID.randomUUID().toString().take(6)
                discoveredBatches[batchId] = candidates
                batchTopics[batchId] = state.queryTags

                val msgText = buildString {
                    append("🎯 Отобрано ${candidates.size} из ${state.count} видео по теме «${state.queryTags}» (${state.period.label}):\n\n")

                    candidates.forEachIndexed { idx, c ->
                        val num = idx + 1
                        val durStr = formatDurationMmSs(c.durationSec)
                        val viewsStr = formatViewsCount(c.viewOrScoreCount)
                        val platIcon = c.platform.badge
                        val agePart = if (c.publishedAgeLabel.isNotBlank()) " • 📅 ${c.publishedAgeLabel}" else ""
                        append("$num. [$platIcon] ${c.title}\n")
                        append("   ⏱ $durStr • 👁 $viewsStr$agePart • 👤 ${c.author}\n")
                        if (c.cloutStatusLabel.isNotBlank()) {
                            append("   📊 ${c.cloutStatusLabel}\n")
                        }
                        if (c.aiWhatsInside.isNotBlank()) {
                            append("   🧠 Суть: ${c.aiWhatsInside}\n")
                        }
                        append("   🔗 ${c.url}\n\n")
                    }
                }

                val buttonRows = mutableListOf<List<InlineKeyboardButton>>()
                for (idx in candidates.indices) {
                    val num = idx + 1
                    buttonRows.add(
                        listOf(
                            btn("🧠 Внутри #$num", "inside_one:${batchId}_$idx"),
                            btn("✅ Взять #$num", "take_one:${batchId}_$idx"),
                            btn("🚫 Бан канал", "ban_ch:${batchId}_$idx")
                        )
                    )
                }

                buttonRows.add(
                    listOf(
                        btn("📦 Скачать ВСЕ (${candidates.size} шт.) в 1080p", "take_all:$batchId")
                    )
                )
                buttonRows.add(
                    listOf(
                        btn("📡 Спам-фермы по этой теме", "spam_topic:$batchId"),
                        btn("🎬 Найти фоны под тему", "bg_topic:$batchId")
                    )
                )

                sendMessageWithKeyboard(chatId, msgText.take(4000), InlineKeyboardMarkup(buttonRows))
            } catch (e: Exception) {
                DiagnosticLogger.logError("HUNT", "Ошибка умного поиска", e)
                sendMessage(chatId, "❌ Ошибка поиска: ${e.message}")
            }
        }
    }

    private suspend fun sendSpamFarmPickerMessage(chatId: Long) {
        val text = "📡 Охотник на «Спам-Каналы / Контент-Фермы»!\n\n" +
            "Находит каналы, заливающие нарезки пачками (частые заливы, шаблонные #shorts, прострелы просмотров).\n" +
            "• Напиши `/spam твоя тема` или нажми кнопку ниже:"
        val keyboard = InlineKeyboardMarkup(
            inlineKeyboard = listOf(
                listOf(
                    btn("📡 Стендап / Комики нарезки", "sq:standup"),
                    btn("📡 Мото / Авто ситуации", "sq:moto")
                ),
                listOf(
                    btn("📡 Подкасты / Мысли / Факты", "sq:podcast"),
                    btn("📡 CS2 / Игровые нарезки", "sq:cs2")
                )
            )
        )
        sendMessageWithKeyboard(chatId, text, keyboard)
    }

    private suspend fun executeSpamFarmChannelHunt(chatId: Long, topicQuery: String) {
        sendMessage(
            chatId,
            "📡 Сканирую YouTube на потоковые каналы и контент-фермы в нише:\n«$topicQuery»..."
        )

        enqueueHardwareTask("spam_hunt", "Hunting spam-farm channels for '$topicQuery'") {
            try {
                val channels = topicHunter.huntSpamFarmChannels(topicQuery, maxChannels = 5)
                if (channels.isEmpty()) {
                    sendMessage(
                        chatId,
                        "⚠️ По теме «$topicQuery» живых спам-каналов за последнее время не обнаружено. Попробуй задать тему шире."
                    )
                    return@enqueueHardwareTask
                }

                val spamBatchId = UUID.randomUUID().toString().take(6)
                discoveredSpamBatches[spamBatchId] = channels

                val msgText = buildString {
                    append("📡 Найдено ${channels.size} потоковых/спам-каналов по теме «$topicQuery»:\n\n")
                    channels.forEachIndexed { idx, ch ->
                        val num = idx + 1
                        val perWeek = String.format(Locale.US, "%.1f", ch.estimatedUploadsPerWeek)
                        append("$num. 🏭 Канал: ${ch.channelName}\n")
                        append("   🔥 Спам-индекс: ${ch.spamScore}/100 • 📈 ~$perWeek рол./нед • 🎲 Разброс: ${ch.titleRandomnessPct}%\n")
                        append("   📋 ${ch.patternSummary}\n")
                        append("   🔗 ${ch.channelUrl}\n")
                        ch.sampleVideos.take(2).forEach { v ->
                            append("      • «${v.title.take(48)}» (${formatViewsCount(v.viewOrScoreCount)} просм.) — ${v.url}\n")
                        }
                        append("\n")
                    }
                }

                val rows = mutableListOf<List<InlineKeyboardButton>>()
                channels.forEachIndexed { idx, ch ->
                    val num = idx + 1
                    rows.add(
                        listOf(
                            btn(
                                "📥 Взять топ-2 с канала #$num (${ch.channelName.take(16)})",
                                "spam_dl:${spamBatchId}_$idx"
                            )
                        )
                    )
                }

                sendMessageWithKeyboard(chatId, msgText.take(4000), InlineKeyboardMarkup(rows))
            } catch (e: Exception) {
                DiagnosticLogger.logError("SPAM-HUNTER", "Ошибка поиска спам-каналов", e)
                sendMessage(chatId, "❌ Ошибка поиска спам-каналов: ${e.message}")
            }
        }
    }

    private suspend fun inspectCandidateWhatsInside(
        chatId: Long,
        cand: DiscoveredMediaCandidate,
        userTopic: String,
        batchId: String,
        candIdx: Int
    ) {
        sendMessage(
            chatId,
            "🧠 Вытягиваю субтитры и описание ролика «${cand.title.take(50)}» напрямую через Innertube (без скачивания видео)..."
        )

        enqueueHardwareTask("inside", "Inspecting 'What's Inside' for ${cand.id}") {
            try {
                val digest = topicHunter.inspectVideoInsideViaSubtitles(
                    videoUrl = cand.url,
                    videoTitle = cand.title,
                    userTopic = userTopic
                )

                val text = buildString {
                    append("🧠 ЧТО ВНУТРИ РОЛИКА:\n")
                    append("📌 ${cand.title} (${formatDurationMmSs(cand.durationSec)})\n")
                    append("🔗 ${cand.url}\n")
                    append("📝 Источник: ").append(
                        if (digest.hasTranscript) "✅ Реальные субтитры речи + Описание" else "ℹ️ Описание и метаданные (субтитры отключены автором)"
                    ).append("\n\n")

                    append("💡 Честная выжимка сути:\n")
                    append(digest.overallSummary).append("\n\n")

                    if (digest.keyTopics.isNotEmpty()) {
                        append("🏷 Темы: ").append(digest.keyTopics.joinToString(", ")).append("\n\n")
                    }

                    if (digest.moments.isNotEmpty()) {
                        append("🎯 Рекомендованные цельные моменты:\n")
                        digest.moments.forEachIndexed { mIdx, m ->
                            val span = (m.endSec - m.startSec).coerceAtLeast(1)
                            append("${mIdx + 1}) ⏱ ${formatDurationMmSs(m.startSec)} – ${formatDurationMmSs(m.endSec)} ($span сек) — ${m.title}\n")
                            if (m.summary.isNotBlank()) {
                                append("   💬 ${m.summary}\n")
                            }
                        }
                    }
                }

                val rows = mutableListOf<List<InlineKeyboardButton>>()
                digest.moments.take(3).forEachIndexed { mIdx, m ->
                    val labelTime = "${formatDurationMmSs(m.startSec)}–${formatDurationMmSs(m.endSec)}"
                    rows.add(
                        listOf(
                            btn(
                                "✂️ Взять момент #${mIdx + 1} ($labelTime) в 1080p",
                                "take_cut:${batchId}_${candIdx}_${m.startSec}_${m.endSec}"
                            )
                        )
                    )
                }

                rows.add(
                    listOf(
                        btn("📥 Скачать ВЕСЬ исходник целиком в 1080p", "take_one:${batchId}_$candIdx")
                    )
                )

                sendMessageWithKeyboard(chatId, text.take(4000), InlineKeyboardMarkup(rows))
            } catch (e: Exception) {
                DiagnosticLogger.logError("WHATS-INSIDE", "Ошибка анализа ролика", e)
                sendMessage(chatId, "❌ Не удалось получить выжимку: ${e.message}")
            }
        }
    }

    private suspend fun handleDirectLinkMessage(chatId: Long, rawText: String) {
        val tokens = rawText.split(Regex("""\s+"""))
        val url = tokens.firstOrNull { it.startsWith("http://") || it.startsWith("https://") }
        if (url == null) {
            sendMessage(chatId, "❌ Ссылка не распознана.")
            return
        }

        val linkId = UUID.randomUUID().toString().take(6)
        pendingLinks[linkId] = PendingDirectUrlState(url = url)

        val text = "🎬 Ссылка получена:\n$url\n\nЧто сделать с этим роликом?"
        val keyboard = InlineKeyboardMarkup(
            inlineKeyboard = listOf(
                listOf(
                    btn("🧠 Узнать «Что внутри» + Таймкоды", "link_inside:$linkId")
                ),
                listOf(
                    btn("📥 Скачать в Студию как исходник (1080p)", "link_dl:$linkId")
                ),
                listOf(
                    btn("🎬 Добавить в пул фонов/залипалок", "link_bg:$linkId")
                )
            )
        )
        sendMessageWithKeyboard(chatId, text, keyboard)
    }

    private fun downloadCandidateIntoStudioInbox(
        chatId: Long,
        url: String,
        titleHint: String?,
        authorHint: String?,
        recommendedStartSec: Double = 0.0,
        recommendedEndSec: Double = 0.0,
        aiSummary: String = ""
    ) {
        val taskId = UUID.randomUUID().toString().take(6)
        enqueueHardwareTask(taskId, "Downloading 1080p source to Studio") {
            try {
                val timeHintStr = if (recommendedEndSec > recommendedStartSec) {
                    " (выбран момент ${formatDurationMmSs(recommendedStartSec.toInt())}–${formatDurationMmSs(recommendedEndSec.toInt())})"
                } else {
                    ""
                }
                sendMessage(
                    chatId,
                    "📥 Качаю исходник в честном FullHD 1080p$timeHintStr:\n${titleHint ?: url}..."
                )
                val inboxItem = clutterFilter.downloadSourceToStudioInbox(
                    sourceUrl = url,
                    overrideTitle = titleHint,
                    overrideAuthor = authorHint,
                    maxDurationSec = 360,
                    recommendedStartSec = recommendedStartSec,
                    recommendedEndSec = recommendedEndSec,
                    aiSummary = aiSummary
                )
                downloadedInboxItems[inboxItem.id] = inboxItem

                val sizeMb = String.format(Locale.US, "%.1f", inboxItem.sizeKb / 1024.0)
                val replyText = buildString {
                    append("✅ Исходник загружен в FullHD 1080p!\n\n")
                    append("📌 Название: ").append(inboxItem.title).append("\n")
                    append("👤 Автор: ").append(inboxItem.author).append("\n")
                    append("⏱ Длительность: ").append(inboxItem.durationSec.toInt()).append(" сек • 📦 Вес: ").append(sizeMb).append(" МБ\n")
                    if (recommendedEndSec > recommendedStartSec) {
                        append("✂️ Авто-таймкод для Студии: ")
                            .append(formatDurationMmSs(recommendedStartSec.toInt()))
                            .append(" – ")
                            .append(formatDurationMmSs(recommendedEndSec.toInt()))
                            .append("\n")
                    }
                    if (inboxItem.sourceTags.isNotEmpty()) {
                        append("🏷 Теги: ").append(inboxItem.sourceTags.joinToString(" ")).append("\n")
                    }
                    append("🔗 ").append(inboxItem.sourceUrl).append("\n\n")
                    append("📱 Видео уже во вкладке «🎛 Студия» (и в Галерее Movies/PocketMediaForge/Sources)!")
                }

                val topTag = inboxItem.sourceTags.firstOrNull() ?: "#фон"
                val keyboard = InlineKeyboardMarkup(
                    inlineKeyboard = listOf(
                        listOf(
                            btn("🎬 Найти фоны под тег $topTag", "fetch_tag_bg:${inboxItem.id}")
                        ),
                        listOf(
                            btn("📤 Скинуть этот исходник файлом сюда", "send_raw:${inboxItem.id}")
                        ),
                        listOf(
                            btn("⚡ Быстро собрать (Сплит + Анти-Детект)", "quick_render:${inboxItem.id}")
                        )
                    )
                )

                sendMessageWithKeyboard(chatId, replyText, keyboard)
            } catch (e: Exception) {
                DiagnosticLogger.logError("INBOX-DL", "Ошибка скачивания исходника $url", e)
                sendMessage(chatId, "❌ Не удалось скачать исходник:\n${e.message}")
            }
        }
    }

    private suspend fun handleInlineButtonCallback(
        scope: CoroutineScope,
        chatId: Long,
        messageId: Long,
        data: String
    ) {
        val parts = data.split(":", limit = 2)
        if (parts.size < 2) return
        val action = parts[0]
        val targetId = parts[1]

        when (action) {
            "log_refresh" -> {
                sendFullDiagnosticLog(chatId)
                return
            }
            "log_file" -> {
                val logFile = DiagnosticLogger.getFullLogFile(context)
                sendUncompressedDocument(chatId, logFile, "📜 Полный лог (diagnostic_boot.log)")
                return
            }
            "log_clear" -> {
                DiagnosticLogger.clear(context)
                sendFullDiagnosticLog(chatId)
                return
            }
            "show_inbox" -> {
                sendStudioInboxSummary(chatId)
                return
            }
            "ytdlp_update" -> {
                sendMessage(chatId, "⬆️ Проверяю и обновляю ядро yt-dlp напрямую с GitHub...")
                scope.launch(Dispatchers.IO) {
                    try {
                        val ver = PipelineForegroundService.ensureModernYtDlpBinary(context, forceDownload = true)
                        sendMessage(chatId, "✅ Ядро yt-dlp готово!\n• Рабочая версия: $ver")
                    } catch (e: Exception) {
                        sendMessage(chatId, "❌ Ошибка обновления yt-dlp: ${e.message}")
                    }
                }
                return
            }
            "ban_ch" -> {
                val idx = targetId.substringAfterLast("_").toIntOrNull() ?: return
                val batchId = targetId.substringBeforeLast("_")
                val list = discoveredBatches[batchId]
                if (list != null && idx in list.indices) {
                    val c = list[idx]
                    SearchBlacklistStore.banChannel(context, c.author)
                    SearchBlacklistStore.banVideo(context, c.id, c.url, c.title)
                    sendMessage(chatId, "🚫 Канал «${c.author}» и ролик добавлены в чёрный список!")
                }
                return
            }
            "bg_menu" -> {
                sendBackgroundFinderMenu(chatId)
                return
            }
            "bg_ask" -> {
                awaitingBgQueryByChat[chatId] = true
                sendMessage(
                    chatId,
                    "✍️ Напиши следующим сообщением тему фона/залипалки (например: `ночной мотоцикл`, `дрифт бмв`, `резка мыла`, `cs2 surf`, `космос`):"
                )
                return
            }
            "bg_list" -> {
                sendPoolClipsSummaryInTelegram(chatId)
                return
            }
            "bg_del" -> {
                val idx = targetId.toIntOrNull() ?: return
                val clips = clutterFilter.listVerifiedPoolClips()
                if (idx in clips.indices) {
                    val f = clips[idx]
                    clutterFilter.deletePoolClip(f.name)
                    sendMessage(chatId, "🗑 Фон ${f.name} удалён из пула.")
                }
                sendPoolClipsSummaryInTelegram(chatId)
                return
            }
            "bgq" -> {
                val query = QUICK_BG_PRESETS[targetId] ?: "satisfying shorts"
                executeBackgroundSearchInTelegram(chatId, query)
                return
            }
            "bg_topic" -> {
                val topic = batchTopics[targetId] ?: "satisfying gameplay"
                executeBackgroundSearchInTelegram(chatId, topic)
                return
            }
            "bg_add" -> {
                val idx = targetId.substringAfterLast("_").toIntOrNull() ?: return
                val bgBatchId = targetId.substringBeforeLast("_")
                val list = discoveredBgBatches[bgBatchId]
                if (list == null || idx !in list.indices) {
                    sendMessage(chatId, "⚠️ Список фонов устарел. Запусти поиск фонов заново.")
                    return
                }
                val chosen = list[idx]
                val topic = discoveredBgTopics[bgBatchId] ?: chosen.title
                val tags = clutterFilter.extractSemanticTopicTags("$topic ${chosen.title}").ifEmpty { listOf("#фон") }
                enqueueHardwareTask("bg_dl", "Downloading background clip to pool") {
                    try {
                        sendMessage(chatId, "⏳ Качаю и калибрую в 1080×960 фон «${chosen.title.take(45)}»...")
                        val file = clutterFilter.downloadUrlToPoolWithTags(
                            sourceUrl = chosen.url,
                            rawTagsInput = "$topic ${tags.joinToString(" ")}",
                            customTitle = chosen.title.take(65)
                        )
                        sendMessage(
                            chatId,
                            "✅ Фон добавлен в пул Студии: ${file.name} (${file.length() / 1024} KB)\n🏷 Теги: ${tags.joinToString(" ")}"
                        )
                    } catch (e: Exception) {
                        sendMessage(chatId, "❌ Не удалось скачать этот фон: ${e.message?.take(140)}")
                    }
                }
                return
            }
            "bg_top3" -> {
                val list = discoveredBgBatches[targetId]
                if (list.isNullOrEmpty()) {
                    sendMessage(chatId, "⚠️ Список фонов устарел.")
                    return
                }
                val topic = discoveredBgTopics[targetId] ?: ""
                enqueueHardwareTask("bg_top3", "Downloading top-3 backgrounds") {
                    sendMessage(chatId, "📦 Качаю до 3 лучших фонов из подборки в пул Студии...")
                    var addedCount = 0
                    for (cand in list) {
                        if (addedCount >= 3) break
                        val tags = clutterFilter.extractSemanticTopicTags("$topic ${cand.title}").ifEmpty { listOf("#фон") }
                        val f = clutterFilter.ingestAndVerifyClutterCandidate(
                            sourceUrl = cand.url,
                            assignedTags = tags,
                            customTitle = cand.title.take(65)
                        )
                        if (f != null) addedCount++
                    }
                    sendMessage(chatId, "✅ Готово! Добавлено проверенных фонов в пул: $addedCount шт. (всего в пуле: ${clutterFilter.listVerifiedPoolClips().size} шт.)")
                }
                return
            }
            "qh" -> {
                val preset = QUICK_HUNT_PRESETS[targetId] ?: ("стендап монологи" to SearchLanguageMode.RUSSIAN_ONLY)
                handleHuntCommand(chatId, preset.first, preset.second)
                return
            }
            "sq" -> {
                val query = QUICK_SPAM_PRESETS[targetId] ?: "стендап нарезка"
                executeSpamFarmChannelHunt(chatId, query)
                return
            }
            "spam_topic" -> {
                val topic = batchTopics[targetId] ?: "популярные нарезки shorts"
                executeSpamFarmChannelHunt(chatId, topic)
                return
            }
            "spam_dl" -> {
                val subParts = targetId.split("_", limit = 2)
                if (subParts.size < 2) return
                val spamBatchId = subParts[0]
                val chIdx = subParts[1].toIntOrNull() ?: return
                val channels = discoveredSpamBatches[spamBatchId]
                if (channels == null || chIdx !in channels.indices) {
                    sendMessage(chatId, "⚠️ Список спам-каналов устарел. Запусти поиск заново.")
                    return
                }
                val ch = channels[chIdx]
                val vids = ch.sampleVideos.take(2)
                if (vids.isEmpty()) {
                    sendMessage(chatId, "⚠️ У канала нет доступных роликов.")
                    return
                }
                sendMessage(chatId, "📥 Выкачиваю ${vids.size} лучших ролика с канала «${ch.channelName}» в 1080p...")
                for (v in vids) {
                    downloadCandidateIntoStudioInbox(
                        chatId = chatId,
                        url = v.url,
                        titleHint = v.title,
                        authorHint = ch.channelName,
                        aiSummary = "Канал «${ch.channelName}» (спам-индекс ${ch.spamScore}/100)"
                    )
                }
                return
            }
            "link_inside" -> {
                val linkState = pendingLinks[targetId]
                if (linkState == null) {
                    sendMessage(chatId, "⚠️ Ссылка устарела, отправь её ещё раз.")
                    return
                }
                val tempBatchId = "lnk_$targetId"
                val dummyCand = DiscoveredMediaCandidate(
                    id = "direct_$targetId",
                    url = linkState.url,
                    title = linkState.url,
                    author = "Видео по ссылке",
                    platform = HuntPlatform.YOUTUBE,
                    durationSec = 120,
                    viewOrScoreCount = 0L
                )
                discoveredBatches[tempBatchId] = listOf(dummyCand)
                inspectCandidateWhatsInside(
                    chatId = chatId,
                    cand = dummyCand,
                    userTopic = "Полный разбор сути и лучших моментов видео",
                    batchId = tempBatchId,
                    candIdx = 0
                )
                return
            }
            "link_dl" -> {
                val linkState = pendingLinks[targetId]
                if (linkState == null) {
                    sendMessage(chatId, "⚠️ Ссылка устарела, отправь её ещё раз.")
                    return
                }
                downloadCandidateIntoStudioInbox(
                    chatId = chatId,
                    url = linkState.url,
                    titleHint = null,
                    authorHint = null
                )
                return
            }
            "link_bg" -> {
                val linkState = pendingLinks[targetId]
                if (linkState == null) {
                    sendMessage(chatId, "⚠️ Ссылка устарела, отправь её ещё раз.")
                    return
                }
                enqueueHardwareTask("link_bg", "Adding direct URL to background pool") {
                    sendMessage(chatId, "⏳ Качаю ролик по ссылке и калибрую в пул фонов (1080×960)...")
                    val added = clutterFilter.ingestAndVerifyClutterCandidate(
                        sourceUrl = linkState.url,
                        assignedTags = listOf("#свой_фон", "#универсал"),
                        customTitle = "Фон по ссылке"
                    )
                    if (added != null) {
                        sendMessage(chatId, "✅ Ролик добавлен в пул фонов Студии: ${added.name}!")
                    } else {
                        sendMessage(chatId, "⚠️ Ролик не подошёл под фильтр монотонности фона (резкие склейки или статика).")
                    }
                }
                return
            }
            "inside_one" -> {
                val subParts = targetId.split("_", limit = 2)
                if (subParts.size < 2) return
                val batchId = subParts[0]
                val idx = subParts[1].toIntOrNull() ?: return
                val list = discoveredBatches[batchId]
                if (list == null || idx !in list.indices) {
                    sendMessage(chatId, "⚠️ Список устарел. Выполни поиск заново.")
                    return
                }
                val chosen = list[idx]
                val topic = batchTopics[batchId] ?: chosen.title
                inspectCandidateWhatsInside(
                    chatId = chatId,
                    cand = chosen,
                    userTopic = topic,
                    batchId = batchId,
                    candIdx = idx
                )
                return
            }
            "take_cut" -> {
                val subParts = targetId.split("_")
                if (subParts.size < 4) return
                val endSec = subParts.last().toDoubleOrNull() ?: 0.0
                val startSec = subParts[subParts.size - 2].toDoubleOrNull() ?: 0.0
                val idx = subParts[subParts.size - 3].toIntOrNull() ?: return
                val batchId = subParts.dropLast(3).joinToString("_")

                val list = discoveredBatches[batchId]
                if (list == null || idx !in list.indices) {
                    sendMessage(chatId, "⚠️ Список устарел. Выполни поиск заново.")
                    return
                }
                val chosen = list[idx]
                downloadCandidateIntoStudioInbox(
                    chatId = chatId,
                    url = chosen.url,
                    titleHint = chosen.title.takeIf { !it.startsWith("http") },
                    authorHint = chosen.author,
                    recommendedStartSec = startSec,
                    recommendedEndSec = endSec,
                    aiSummary = chosen.aiWhatsInside
                )
                return
            }
            "take_one" -> {
                val idx = targetId.substringAfterLast("_").toIntOrNull() ?: return
                val batchId = targetId.substringBeforeLast("_")
                val list = discoveredBatches[batchId]
                if (list == null || idx !in list.indices) {
                    sendMessage(chatId, "⚠️ Список устарел. Выполни поиск заново.")
                    return
                }
                val chosen = list[idx]
                downloadCandidateIntoStudioInbox(
                    chatId = chatId,
                    url = chosen.url,
                    titleHint = chosen.title.takeIf { !it.startsWith("http") },
                    authorHint = chosen.author,
                    aiSummary = chosen.aiWhatsInside
                )
                return
            }
            "take_all" -> {
                val list = discoveredBatches[targetId]
                if (list.isNullOrEmpty()) {
                    sendMessage(chatId, "⚠️ Список устарел. Выполни поиск заново.")
                    return
                }
                sendMessage(chatId, "📦 Запускаю скачивание всех ${list.size} исходников в FullHD 1080p по очереди в Студию...")
                for (chosen in list) {
                    downloadCandidateIntoStudioInbox(
                        chatId = chatId,
                        url = chosen.url,
                        titleHint = chosen.title,
                        authorHint = chosen.author,
                        aiSummary = chosen.aiWhatsInside
                    )
                }
                return
            }
            "send_raw" -> {
                val item = downloadedInboxItems[targetId]
                    ?: clutterFilter.listStudioInboxItems().firstOrNull { it.id == targetId }
                if (item == null) {
                    sendMessage(chatId, "⚠️ Файл не найден в Студии.")
                    return
                }
                val file = clutterFilter.getInboxVideoFile(item)
                if (!file.exists()) {
                    sendMessage(chatId, "⚠️ Видеофайл был удалён.")
                    return
                }
                scope.launch(Dispatchers.IO) {
                    sendMessage(chatId, "📤 Отправляю исходник «${item.title.take(45)}» файлом без сжатия...")
                    sendUncompressedDocument(
                        chatId = chatId,
                        file = file,
                        caption = "🎬 Исходник 1080p: ${item.title}\n🔗 ${item.sourceUrl}"
                    )
                }
                return
            }
            "fetch_tag_bg" -> {
                val item = downloadedInboxItems[targetId]
                    ?: clutterFilter.listStudioInboxItems().firstOrNull { it.id == targetId }
                if (item == null) {
                    sendMessage(chatId, "⚠️ Исходник не найден.")
                    return
                }
                val tags = item.sourceTags.ifEmpty { clutterFilter.extractSemanticTopicTags(item.title) }
                val query = tags.firstOrNull()?.removePrefix("#") ?: item.title.take(25)
                executeBackgroundSearchInTelegram(chatId, query)
                return
            }
            "quick_render" -> {
                val item = downloadedInboxItems[targetId]
                    ?: clutterFilter.listStudioInboxItems().firstOrNull { it.id == targetId }
                if (item == null) {
                    sendMessage(chatId, "⚠️ Исходник не найден в Студии.")
                    return
                }
                val videoFile = clutterFilter.getInboxVideoFile(item)
                if (!videoFile.exists()) {
                    sendMessage(chatId, "⚠️ Исходник удалён.")
                    return
                }
                sendMessage(
                    chatId,
                    "⚡ Запускаю быструю сборку FullHD (Сплит 50/50 + подбор фона + Анти-Детект)..."
                )
                enqueueHardwareTask(item.id, "Quick FullHD Render & Gallery Save") {
                    try {
                        val fullDur = hardwareEngine.probeDurationSeconds(videoFile)
                        val startS = item.recommendedStartSec.coerceIn(0.0, (fullDur - 2.0).coerceAtLeast(0.0))
                        val endS = if (item.recommendedEndSec > startS + 2.0) {
                            item.recommendedEndSec.coerceAtMost(fullDur)
                        } else {
                            (startS + 65.0).coerceAtMost(fullDur)
                        }
                        val spanS = (endS - startS).coerceAtLeast(5.0)
                        val clutterFile = clutterFilter.buildShuffledClutterReel(
                            targetDurationSec = spanS,
                            fallbackPrimaryVideo = videoFile,
                            preferredTopicTags = item.sourceTags
                        )
                        val framing = faceHelper.computeFramingPlan(videoFile, enableSmartFaceCrop = true)
                        val cfg = ModularRenderConfig.presetPodcastSplit().copy(
                            trimStartSec = startS,
                            trimEndSec = endS,
                            preferredTopicTags = item.sourceTags,
                            videoBitrateKbps = 12000,
                            capBitrateForTelegram50Mb = true
                        )
                        val rendered = hardwareEngine.renderPipeline(
                            primaryVideoFile = videoFile,
                            clutterReelFile = clutterFile,
                            framingPlan = framing,
                            assSubtitleFile = null,
                            config = cfg,
                            isPreviewPass = false
                        )
                        val galleryFile = hardwareEngine.saveRenderedVideoToGallery(rendered, item.title)
                        val meta = geminiClient.generateMetadataIfEnabled(
                            enabled = cfg.enableAiMetadataGeneration,
                            sourceUrl = item.sourceUrl,
                            originalTitle = item.title,
                            originalAuthor = item.author,
                            sourceTags = item.sourceTags,
                            transcriptText = item.aiSummary,
                            adConfig = cfg.adLayer
                        )
                        val captionText = buildString {
                            append("✅ Готовый ролик сохранён в Галерею (${galleryFile.name})!\n")
                            append("📐 Кадр: ").append(framing.summary).append("\n")
                            if (meta != null && meta.titles.isNotEmpty()) {
                                append("\n🔥 Идеи названий:\n")
                                meta.titles.take(3).forEachIndexed { i, t ->
                                    append("${i + 1}) $t\n")
                                }
                            }
                            append("🔗 Оригинал: ").append(item.sourceUrl)
                        }
                        sendUncompressedDocument(
                            chatId = chatId,
                            file = rendered,
                            caption = captionText
                        )
                        rendered.delete()
                    } catch (e: Exception) {
                        sendMessage(chatId, "❌ Ошибка сборки: ${e.message}")
                    }
                }
                return
            }
        }

        if (action.startsWith("hunt_")) {
            val filterState = huntFilters[targetId]
            if (filterState == null) {
                sendMessage(chatId, "⚠️ Сессия поиска истекла. Напиши тему заново в чат.")
                return
            }
            when (action) {
                "hunt_lang" -> {
                    val values = SearchLanguageMode.entries
                    filterState.languageMode = values[(filterState.languageMode.ordinal + 1) % values.size]
                    sendOrEditHuntConfigurator(chatId, messageId, targetId, filterState)
                }
                "hunt_dur" -> {
                    val values = SearchDurationMode.entries
                    filterState.durationMode = values[(filterState.durationMode.ordinal + 1) % values.size]
                    sendOrEditHuntConfigurator(chatId, messageId, targetId, filterState)
                }
                "hunt_sort" -> {
                    val values = ContentSortOrder.entries
                    filterState.sortOrder = values[(filterState.sortOrder.ordinal + 1) % values.size]
                    sendOrEditHuntConfigurator(chatId, messageId, targetId, filterState)
                }
                "hunt_period" -> {
                    val values = SearchTimePeriod.entries
                    filterState.period = values[(filterState.period.ordinal + 1) % values.size]
                    sendOrEditHuntConfigurator(chatId, messageId, targetId, filterState)
                }
                "hunt_plat" -> {
                    val values = HuntPlatform.entries
                    filterState.platform = values[(filterState.platform.ordinal + 1) % values.size]
                    sendOrEditHuntConfigurator(chatId, messageId, targetId, filterState)
                }
                "hunt_cnt" -> {
                    filterState.count = when (filterState.count) {
                        10 -> 20
                        20 -> 30
                        else -> 10
                    }
                    sendOrEditHuntConfigurator(chatId, messageId, targetId, filterState)
                }
                "hunt_relax" -> {
                    filterState.period = SearchTimePeriod.YEAR
                    executeTagSearchAndSendLinks(chatId, targetId, filterState)
                }
                "hunt_go" -> {
                    executeTagSearchAndSendLinks(chatId, targetId, filterState)
                }
            }
        }
    }

    private suspend fun sendMessage(chatId: Long, text: String) {
        try {
            val replyMarkupJson = jsonFormat.encodeToString(persistentMainMenuKeyboard)
            val resp = httpClient.post("${baseUrl()}/sendMessage") {
                contentType(ContentType.Application.FormUrlEncoded)
                parameter("chat_id", chatId)
                parameter("text", text)
                parameter("disable_web_page_preview", true)
                parameter("reply_markup", replyMarkupJson)
            }
            if (!resp.status.isSuccess()) {
                DiagnosticLogger.log("TELEGRAM:ERR", "sendMessage HTTP ${resp.status.value}: ${resp.bodyAsText().take(180)}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "sendMessage failed: ${e.message}")
        }
    }

    private suspend fun sendMessageWithKeyboard(
        chatId: Long,
        text: String,
        keyboard: InlineKeyboardMarkup
    ) {
        try {
            val markupJson = jsonFormat.encodeToString(keyboard)
            val resp = httpClient.post("${baseUrl()}/sendMessage") {
                contentType(ContentType.Application.FormUrlEncoded)
                parameter("chat_id", chatId)
                parameter("text", text)
                parameter("disable_web_page_preview", true)
                parameter("reply_markup", markupJson)
            }
            if (!resp.status.isSuccess()) {
                val errBody = resp.bodyAsText().take(220)
                DiagnosticLogger.log("TELEGRAM:ERR", "sendMessageWithKeyboard HTTP ${resp.status.value}: $errBody")
            }
        } catch (e: Exception) {
            Log.e(TAG, "sendMessageWithKeyboard failed: ${e.message}")
        }
    }

    private suspend fun editMessageWithKeyboard(
        chatId: Long,
        messageId: Long,
        text: String,
        keyboard: InlineKeyboardMarkup
    ) {
        try {
            val markupJson = jsonFormat.encodeToString(keyboard)
            val resp = httpClient.post("${baseUrl()}/editMessageText") {
                contentType(ContentType.Application.FormUrlEncoded)
                parameter("chat_id", chatId)
                parameter("message_id", messageId)
                parameter("text", text)
                parameter("disable_web_page_preview", true)
                parameter("reply_markup", markupJson)
            }
            if (!resp.status.isSuccess()) {
                val body = resp.bodyAsText()
                if (!body.contains("message is not modified", ignoreCase = true)) {
                    sendMessageWithKeyboard(chatId, text, keyboard)
                }
            }
        } catch (_: Exception) {
            sendMessageWithKeyboard(chatId, text, keyboard)
        }
    }

    private suspend fun sendUncompressedDocument(
        chatId: Long,
        file: File,
        caption: String
    ) {
        try {
            val safeCaption = if (caption.length > 1000) caption.take(1000) else caption
            httpClient.post("${baseUrl()}/sendDocument") {
                setBody(
                    MultiPartFormDataContent(
                        formData {
                            append("chat_id", chatId.toString())
                            append("caption", safeCaption)
                            append(
                                key = "document",
                                value = ChannelProvider(file.length()) { file.inputStream().toByteReadChannel() },
                                headers = Headers.build {
                                    append(HttpHeaders.ContentType, "application/octet-stream")
                                    append(HttpHeaders.ContentDisposition, "filename=\"${file.name}\"")
                                }
                            )
                        }
                    )
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "sendUncompressedDocument failed: ${e.message}")
        }
    }

    private suspend fun answerCallbackQuery(callbackQueryId: String) {
        try {
            httpClient.post("${baseUrl()}/answerCallbackQuery") {
                parameter("callback_query_id", callbackQueryId)
            }
        } catch (_: Exception) {
        }
    }
}
