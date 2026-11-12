package forge.media.pocket.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.graphics.Typeface
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import forge.media.pocket.clutter.ClutterEntropyFilter
import forge.media.pocket.clutter.StudioInboxItem
import forge.media.pocket.compositor.AntiDetectDspLayerConfig
import forge.media.pocket.compositor.AudioStemRoutingMode
import forge.media.pocket.compositor.FFmpegHardwareEngine
import forge.media.pocket.compositor.ManualCropRect
import forge.media.pocket.compositor.ModularRenderConfig
import forge.media.pocket.compositor.MusicRemovalMode
import forge.media.pocket.compositor.SeparatedAudioStems
import forge.media.pocket.compositor.VideoLayoutLayer
import forge.media.pocket.compositor.VoiceoverDubMode
import forge.media.pocket.compositor.WatermarkEraseMethod
import forge.media.pocket.compositor.WatermarkMaskBox
import forge.media.pocket.config.DiagnosticLogger
import forge.media.pocket.config.NodeConfigStore
import forge.media.pocket.discovery.DiscoveredMediaCandidate
import forge.media.pocket.discovery.DiscoveredSpamChannel
import forge.media.pocket.discovery.TopicHunterEngine
import forge.media.pocket.service.PipelineForegroundService
import forge.media.pocket.service.PowerOptimizationHelper
import forge.media.pocket.service.ServiceStatus
import forge.media.pocket.subtitles.MusicShazamMatchResult
import forge.media.pocket.subtitles.SubtitleAssTransformer
import forge.media.pocket.subtitles.SubtitleLayerConfig
import forge.media.pocket.subtitles.SubtitlePlacementZone
import forge.media.pocket.subtitles.TtsVoiceProfile
import forge.media.pocket.vision.DetectedHardsubRegion
import forge.media.pocket.vision.ExistingSubtitleRemovalMode
import forge.media.pocket.vision.FaceCenteringHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {

    private lateinit var configStore: NodeConfigStore
    private lateinit var powerHelper: PowerOptimizationHelper
    private lateinit var clutterFilter: ClutterEntropyFilter
    private lateinit var topicHunter: TopicHunterEngine
    private lateinit var hardwareEngine: FFmpegHardwareEngine
    private lateinit var faceHelper: FaceCenteringHelper
    private lateinit var subtitleTransformer: SubtitleAssTransformer

    private lateinit var statusBadgeView: TextView
    private lateinit var liveLogTextView: TextView

    // Top 4-Tab containers
    private lateinit var tabInboxContainer: LinearLayout
    private lateinit var tabStudioContainer: LinearLayout
    private lateinit var tabPoolContainer: LinearLayout
    private lateinit var tabSettingsContainer: LinearLayout

    private lateinit var tabBtnInbox: Button
    private lateinit var tabBtnStudio: Button
    private lateinit var tabBtnPool: Button
    private lateinit var tabBtnSettings: Button

    // Inbox & Spam-Farm Hunter UI views
    private lateinit var inboxListLayout: LinearLayout
    private lateinit var directUrlDownloadInput: EditText
    private lateinit var spamFarmTopicInput: EditText
    private lateinit var spamFarmStatusView: TextView
    private lateinit var spamFarmListLayout: LinearLayout

    // Studio Live Dual-Layer Canvas & Timeline UI views
    private lateinit var studioSelectedSourceLabel: TextView
    private lateinit var studioCanvasModeBadge: TextView
    private lateinit var stageOuterFrame: FrameLayout
    private lateinit var topVideoTexture: TextureView
    private lateinit var bottomBgTexture: TextureView
    private lateinit var canvasOverlayView: StudioCanvasOverlayView
    private lateinit var returnToLiveCompositeBtn: Button

    private lateinit var playPauseTransportBtn: Button
    private lateinit var timelineTimecodeLabel: TextView
    private lateinit var playheadSeekBar: SeekBar
    private lateinit var trimStartSeekBar: SeekBar
    private lateinit var trimEndSeekBar: SeekBar
    private lateinit var trimStartInput: EditText
    private lateinit var trimEndInput: EditText

    // Studio 6-Tool Sub-Panels (CapCut / YouCut clean drawer architecture)
    private val studioToolButtons = mutableListOf<Button>()
    private val studioToolPanels = mutableListOf<LinearLayout>()

    private lateinit var speedValueLabel: TextView
    private lateinit var templateSelectBtn: Button
    private lateinit var splitRatioLabel: TextView
    private lateinit var splitRatioSeekBar: SeekBar
    private lateinit var toggleFreeformCropBtn: Button
    private lateinit var toggleSplitDragBtn: Button
    private lateinit var cropSummaryLabel: TextView
    private lateinit var poolClipSelectBtn: Button
    private lateinit var chkSmartFace: CheckBox

    private lateinit var watermarkStatusLabel: TextView
    private lateinit var watermarkEraseMethodBtn: Button
    private lateinit var toggleWatermarkTouchBtn: Button
    private lateinit var hardsubRemovalModeBtn: Button
    private lateinit var hardsubStatusLabel: TextView

    private lateinit var mainVolLabel: TextView
    private lateinit var bgVolLabel: TextView
    private lateinit var voVolLabel: TextView
    private lateinit var musicVolLabel: TextView
    private lateinit var stemRoutingModeBtn: Button
    private lateinit var stemStatusLabel: TextView
    private lateinit var shazamStatusLabel: TextView
    private lateinit var toggleReplaceMusicChk: CheckBox
    private lateinit var musicRemovalBtn: Button

    private lateinit var chkSubtitles: CheckBox
    private lateinit var subZoneBtn: Button
    private lateinit var subFontSizeBtn: Button
    private lateinit var subSyncOffsetLabel: TextView
    private lateinit var voiceoverDubModeBtn: Button
    private lateinit var ttsVoiceProfileBtn: Button
    private lateinit var dubScriptInput: EditText
    private lateinit var voiceoverStatusLabel: TextView

    private lateinit var chkVideoZoom: CheckBox
    private lateinit var chkVideoColorNoise: CheckBox
    private lateinit var chkVideoFlip: CheckBox
    private lateinit var chkAudioPitchTempo: CheckBox
    private lateinit var chkAudioEqStereo: CheckBox
    private lateinit var chkAudioTrimSilence: CheckBox

    private lateinit var studioRenderStatusView: TextView

    // Pool UI views
    private lateinit var poolListLayout: LinearLayout
    private lateinit var poolTopicTagInput: EditText
    private lateinit var poolSearchResultsLayout: LinearLayout
    private lateinit var poolSearchStatusView: TextView

    // Settings UI views
    private lateinit var botTokenInput: EditText
    private lateinit var geminiKeyInput: EditText
    private lateinit var allowedChatIdInput: EditText

    // Live Dual MediaPlayers & Surfaces
    private var topSourcePlayer: MediaPlayer? = null
    private var bottomBgPlayer: MediaPlayer? = null
    private var topSurface: Surface? = null
    private var bottomSurface: Surface? = null
    private var topSourceFileLoaded: File? = null
    private var bottomBgFileLoaded: File? = null
    private var isShowingRenderedPreviewFile: Boolean = false
    private var isUserDraggingPlayhead: Boolean = false
    private var isUpdatingTrimUiProgrammatically: Boolean = false

    private var sourceVideoNaturalWidth: Int = 1080
    private var sourceVideoNaturalHeight: Int = 1920
    private var bgVideoNaturalWidth: Int = 1080
    private var bgVideoNaturalHeight: Int = 960

    // Current Studio Editing State
    private var selectedInboxItem: StudioInboxItem? = null
    private var currentSourceDurationSec: Double = 30.0
    private var currentTrimStartSec: Double = 0.0
    private var currentTrimEndSec: Double = 30.0
    private var currentPlaybackSpeed: Float = 1.0f

    private var currentLayoutLayer: VideoLayoutLayer = VideoLayoutLayer.SPLIT_VSTACK
    private var currentSplitTopRatio: Float = 0.50f
    private var currentManualCrop: ManualCropRect = ManualCropRect(enabled = false)
    private var currentWatermarkBoxes: MutableList<WatermarkMaskBox> = mutableListOf()
    private var currentWatermarkEraseMethod: WatermarkEraseMethod = WatermarkEraseMethod.DELOGO_INPAINT

    private var currentMainVolume: Float = 1.0f
    private var currentBgVolume: Float = 0.0f
    private var currentVoiceoverVolume: Float = 1.25f
    private var currentMusicVolume: Float = 0.25f

    private var selectedPoolClipName: String = "AUTO"
    private var currentStemRoutingMode: AudioStemRoutingMode = AudioStemRoutingMode.FULL_ORIGINAL_MIX
    private var currentSeparatedStems: SeparatedAudioStems? = null
    private var stemPreviewPlayer: MediaPlayer? = null
    private var lastShazamResult: MusicShazamMatchResult? = null
    private var currentShazamCandidateIndex: Int = 0
    private var currentReplacementMusicFile: File? = null
    private var currentMusicRemovalMode: MusicRemovalMode = MusicRemovalMode.OFF
    private var currentVoiceoverDubMode: VoiceoverDubMode = VoiceoverDubMode.OFF
    private var currentTtsVoiceProfile: TtsVoiceProfile = TtsVoiceProfile.CHARON_DEEP
    private var currentCustomVoiceoverFile: File? = null
    private var currentHardsubRemovalMode: ExistingSubtitleRemovalMode = ExistingSubtitleRemovalMode.OFF
    private var currentDetectedHardsubRegion: DetectedHardsubRegion? = null
    private var currentSubZone: SubtitlePlacementZone = SubtitlePlacementZone.ADAPTIVE_AUTO
    private var currentSubFontSizePx: Int = 68
    private var currentSubSyncOffsetMs: Long = 0L
    private var lastRenderedGalleryFile: File? = null

    private val importSourceLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            lifecycleScope.launch {
                try {
                    val item = clutterFilter.importLocalUriToInbox(uri)
                    selectedInboxItem = item
                    refreshInboxTab()
                    bindSelectedSourceToStudio(item)
                    switchTab(1)
                    Toast.makeText(this@MainActivity, "✅ Видео загружено в Студию!", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(this@MainActivity, "Ошибка: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private val importPoolClipLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            lifecycleScope.launch {
                try {
                    val enteredTags = if (::poolTopicTagInput.isInitialized) poolTopicTagInput.text.toString().trim() else ""
                    Toast.makeText(this@MainActivity, "⏳ Нормализую видео в 1080p для пула фонов...", Toast.LENGTH_SHORT).show()
                    val added = clutterFilter.importLocalUriToPool(uri, enteredTags)
                    selectedPoolClipName = added.name
                    refreshPoolTab()
                    updatePoolSelectorButtonText()
                    reloadLiveBackgroundPlayer()
                    Toast.makeText(this@MainActivity, "✅ Фон ${added.name} добавлен и подключён на холст!", Toast.LENGTH_LONG).show()
                } catch (e: Exception) {
                    Toast.makeText(this@MainActivity, "Ошибка: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private val importVoiceoverAudioLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            lifecycleScope.launch {
                try {
                    Toast.makeText(this@MainActivity, "⏳ Подключаю аудиофайл озвучки...", Toast.LENGTH_SHORT).show()
                    val voFile = subtitleTransformer.importCustomVoiceoverUri(uri)
                    currentCustomVoiceoverFile = voFile
                    if (currentVoiceoverDubMode != VoiceoverDubMode.DUB_VOICEOVER_15PCT_BG &&
                        currentVoiceoverDubMode != VoiceoverDubMode.DUB_REPLACE_100PCT
                    ) {
                        currentVoiceoverDubMode = VoiceoverDubMode.DUB_VOICEOVER_15PCT_BG
                        voiceoverDubModeBtn.text = "🎭 Режим переозвучки: ${currentVoiceoverDubMode.label}"
                    }
                    voiceoverStatusLabel.text = "✅ Аудио озвучки готово: ${voFile.name} (${voFile.length() / 1024} KB)"
                    Toast.makeText(this@MainActivity, "✅ Файл озвучки подключён!", Toast.LENGTH_LONG).show()
                } catch (e: Exception) {
                    Toast.makeText(this@MainActivity, "Ошибка: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private val importReplacementMusicLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            lifecycleScope.launch {
                try {
                    Toast.makeText(this@MainActivity, "⏳ Подключаю свою мелодию для подмены...", Toast.LENGTH_SHORT).show()
                    val bgmFile = subtitleTransformer.importCustomVoiceoverUri(uri)
                    currentReplacementMusicFile = bgmFile
                    toggleReplaceMusicChk.isChecked = true
                    shazamStatusLabel.text = "✅ Своя мелодия подключена: ${bgmFile.name} (${bgmFile.length() / 1024} KB)"
                    Toast.makeText(this@MainActivity, "✅ Мелодия подключена для подмены!", Toast.LENGTH_LONG).show()
                } catch (e: Exception) {
                    Toast.makeText(this@MainActivity, "Ошибка: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ -> }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        DiagnosticLogger.init(applicationContext)
        DiagnosticLogger.log("UI", "Открыт главный экран MainActivity (Двухслойная Студия + Скаут)")

        configStore = NodeConfigStore(applicationContext)
        powerHelper = PowerOptimizationHelper(applicationContext)
        clutterFilter = ClutterEntropyFilter(applicationContext)
        topicHunter = TopicHunterEngine(applicationContext)
        hardwareEngine = FFmpegHardwareEngine(applicationContext)
        faceHelper = FaceCenteringHelper(applicationContext)
        subtitleTransformer = SubtitleAssTransformer(applicationContext, configStore)

        requestNotificationPermissionIfNeeded()
        setContentView(buildMainTabbedInterface())
        observePipelineStatus()
        observeDiagnosticLogs()
        startLivePlayheadTicker()

        refreshInboxTab()
        refreshPoolTab()

        val existingInbox = clutterFilter.listStudioInboxItems()
        if (existingInbox.isNotEmpty()) {
            bindSelectedSourceToStudio(existingInbox.first())
        } else {
            reloadLiveBackgroundPlayer()
        }

        if (configStore.getTelegramBotToken().isNotBlank()) {
            PipelineForegroundService.startService(applicationContext)
        }
    }

    override fun onResume() {
        super.onResume()
        refreshInboxTab()
        refreshPoolTab()
    }

    override fun onPause() {
        super.onPause()
        pauseLiveStagePlayers()
        stopStemAudioPreview()
    }

    override fun onDestroy() {
        releaseLiveStagePlayers()
        stopStemAudioPreview()
        super.onDestroy()
    }

    private fun buildMainTabbedInterface(): ScrollView {
        val scroll = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#09090B"))
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = dp(14)
            setPadding(pad, dp(18), pad, dp(34))
        }

        val titleView = TextView(this).apply {
            text = "POCKET MEDIA FORGE • STUDIO"
            setTextColor(Color.parseColor("#FFFFFF"))
            textSize = 17f
            typeface = Typeface.MONOSPACE
            setTypeface(typeface, Typeface.BOLD)
        }
        root.addView(titleView)

        statusBadgeView = TextView(this).apply {
            text = "● СТАТУС: ПРОВЕРКА..."
            setTextColor(Color.parseColor("#FBBF24"))
            setBackgroundColor(Color.parseColor("#18181B"))
            textSize = 11f
            typeface = Typeface.MONOSPACE
            val p = dp(9)
            setPadding(p, p, p, p)
        }
        root.addView(statusBadgeView, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(6); bottomMargin = dp(8) })

        val tabBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            weightSum = 4f
        }

        tabBtnInbox = createTabButton("📥 Исходники") { switchTab(0) }
        tabBtnStudio = createTabButton("🎛 Студия") { switchTab(1) }
        tabBtnPool = createTabButton("🗂 Фоны") { switchTab(2) }
        tabBtnSettings = createTabButton("⚙️ Ключи/Лог") { switchTab(3) }

        tabBar.addView(tabBtnInbox, tabButtonLayoutParams())
        tabBar.addView(tabBtnStudio, tabButtonLayoutParams())
        tabBar.addView(tabBtnPool, tabButtonLayoutParams())
        tabBar.addView(tabBtnSettings, tabButtonLayoutParams())
        root.addView(tabBar)

        tabInboxContainer = buildInboxTabView()
        tabStudioContainer = buildStudioTabView()
        tabPoolContainer = buildPoolTabView()
        tabSettingsContainer = buildSettingsTabView()

        root.addView(tabInboxContainer)
        root.addView(tabStudioContainer)
        root.addView(tabPoolContainer)
        root.addView(tabSettingsContainer)

        if (configStore.getTelegramBotToken().isBlank()) {
            switchTab(3)
        } else {
            switchTab(1)
        }

        scroll.addView(root)
        return scroll
    }

    private fun switchTab(index: Int) {
        tabInboxContainer.visibility = if (index == 0) View.VISIBLE else View.GONE
        tabStudioContainer.visibility = if (index == 1) View.VISIBLE else View.GONE
        tabPoolContainer.visibility = if (index == 2) View.VISIBLE else View.GONE
        tabSettingsContainer.visibility = if (index == 3) View.VISIBLE else View.GONE

        val buttons = listOf(tabBtnInbox, tabBtnStudio, tabBtnPool, tabBtnSettings)
        buttons.forEachIndexed { idx, btn ->
            if (idx == index) {
                btn.setBackgroundColor(Color.parseColor("#10B981"))
                btn.setTextColor(Color.parseColor("#09090B"))
            } else {
                btn.setBackgroundColor(Color.parseColor("#27272A"))
                btn.setTextColor(Color.parseColor("#E4E4E7"))
            }
        }

        if (index != 1) {
            pauseLiveStagePlayers()
        }
        if (index == 0) refreshInboxTab()
        if (index == 2) refreshPoolTab()
    }

    // =========================================================================
    // ВКЛАДКА 0: 📥 ИСХОДНИКИ (INBOX) + 📡 ОХОТНИК НА СПАМ-КАНАЛЫ
    // =========================================================================
    private fun buildInboxTabView(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL

            addView(sectionHeader("📥 ЗАГРУЖЕННЫЕ ИСХОДНИКИ (ИЗ TELEGRAM ИЛИ С ТЕЛЕФОНА)"))

            val hint = TextView(this@MainActivity).apply {
                text = "Ищи свежие популярные ролики в Telegram-боте (YouTube + Rutube + Reddit) -> жми «✅ Взять #... (1080p)» или загрузи видео с телефона."
                setTextColor(Color.parseColor("#A1A1AA"))
                textSize = 12f
                setPadding(0, 0, 0, dp(8))
            }
            addView(hint)

            val actionRow = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                weightSum = 2f
            }

            val importPhoneBtn = createStyledButton("📂 Взять видео из Галереи", "#09090B", "#10B981").apply {
                setOnClickListener { importSourceLauncher.launch("video/*") }
            }
            val refreshBtn = createStyledButton("🔄 Обновить список", "#F4F4F5", "#27272A").apply {
                setOnClickListener { refreshInboxTab() }
            }

            actionRow.addView(importPhoneBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(6) })
            actionRow.addView(refreshBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(actionRow)

            addView(fieldLabel("Прямая скачка по ссылке (YouTube / Shorts / Rutube / Reddit / TikTok) в 1080p:"))
            directUrlDownloadInput = createInputField("https://www.youtube.com/watch?v=...", "", false)
            addView(directUrlDownloadInput)

            val dlLinkBtn = createStyledButton("⬇️ Скачать по ссылке в 1080p", "#F4F4F5", "#3B82F6").apply {
                setOnClickListener {
                    val url = directUrlDownloadInput.text.toString().trim()
                    if (!url.startsWith("http")) {
                        Toast.makeText(this@MainActivity, "Вставь корректную ссылку https://...", Toast.LENGTH_SHORT).show()
                        return@setOnClickListener
                    }
                    Toast.makeText(this@MainActivity, "⏳ Качаю исходник в 1080p...", Toast.LENGTH_LONG).show()
                    lifecycleScope.launch {
                        try {
                            val item = clutterFilter.downloadSourceToStudioInbox(url)
                            directUrlDownloadInput.setText("")
                            refreshInboxTab()
                            bindSelectedSourceToStudio(item)
                            switchTab(1)
                            Toast.makeText(this@MainActivity, "✅ Скачано в 1080p и открыто в Студии!", Toast.LENGTH_LONG).show()
                        } catch (e: Exception) {
                            Toast.makeText(this@MainActivity, "❌ Ошибка: ${e.message}", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
            addView(dlLinkBtn, buttonLayoutParams())

            addView(sectionHeader("📡 ОХОТНИК НА СПАМ-КАНАЛЫ И ФЕРМЫ ПЕРЕЗАЛИВОВ"))

            spamFarmTopicInput = createInputField("Тема ниши (например: мото ситуации, стендап истории, кс2)...", "", false)
            addView(spamFarmTopicInput)

            val spamBtnRow = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                weightSum = 2f
                setPadding(0, dp(6), 0, 0)
            }

            val huntSpamByTopicBtn = createStyledButton("📡 Найти спам-каналы", "#09090B", "#F59E0B").apply {
                setTypeface(typeface, Typeface.BOLD)
                setOnClickListener {
                    val query = spamFarmTopicInput.text.toString().trim().ifBlank {
                        selectedInboxItem?.let { "${it.title} ${it.sourceTags.joinToString(" ")}" } ?: ""
                    }
                    if (query.isBlank()) {
                        Toast.makeText(this@MainActivity, "Введи тему ниши или выбери исходник ниже", Toast.LENGTH_SHORT).show()
                        return@setOnClickListener
                    }
                    runSpamFarmSearchInUi(query)
                }
            }

            val huntSpamFromSelectedBtn = createStyledButton("🕵️ Похожие на исходник", "#F4F4F5", "#27272A").apply {
                setOnClickListener {
                    val cur = selectedInboxItem
                    if (cur == null) {
                        Toast.makeText(this@MainActivity, "Сначала выбери любой исходник в списке", Toast.LENGTH_SHORT).show()
                        return@setOnClickListener
                    }
                    val seed = "${cur.title} ${cur.sourceTags.joinToString(" ")}"
                    spamFarmTopicInput.setText(seed.take(45))
                    runSpamFarmSearchInUi(seed)
                }
            }

            spamBtnRow.addView(huntSpamByTopicBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.1f).apply { rightMargin = dp(6) })
            spamBtnRow.addView(huntSpamFromSelectedBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.9f))
            addView(spamBtnRow)

            spamFarmStatusView = TextView(this@MainActivity).apply {
                text = "📡 Введи нишу выше, чтобы найти потоковые спам-каналы с подходящими исходниками."
                setTextColor(Color.parseColor("#FDE68A"))
                textSize = 11.5f
                setPadding(0, dp(4), 0, dp(4))
            }
            addView(spamFarmStatusView)

            spamFarmListLayout = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(4), 0, dp(8))
            }
            addView(spamFarmListLayout)

            addView(sectionHeader("📂 МОИ ЗАГРУЖЕННЫЕ ИСХОДНИКИ"))

            inboxListLayout = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(6), 0, 0)
            }
            addView(inboxListLayout)
        }
    }

    private fun runSpamFarmSearchInUi(topicQuery: String) {
        spamFarmStatusView.text = "⏳ Ищу потоковые спам-каналы и контент-фермы по теме «$topicQuery»..."
        spamFarmListLayout.removeAllViews()
        lifecycleScope.launch {
            try {
                val channels = topicHunter.huntSpamFarmChannels(topicQuery, maxChannels = 4)
                if (channels.isEmpty()) {
                    spamFarmStatusView.text = "⚠️ По запросу «$topicQuery» спам-каналов не найдено."
                    return@launch
                }
                spamFarmStatusView.text = "✅ Найдено ${channels.size} потоковых/спам-каналов по теме «$topicQuery»:"
                renderSpamChannelsCards(channels)
            } catch (e: Exception) {
                spamFarmStatusView.text = "❌ Ошибка поиска спам-каналов: ${e.message}"
            }
        }
    }

    private fun renderSpamChannelsCards(channels: List<DiscoveredSpamChannel>) {
        spamFarmListLayout.removeAllViews()
        for (ch in channels) {
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(Color.parseColor("#141419"))
                val p = dp(10)
                setPadding(p, p, p, p)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(8) }
            }

            val perWeek = String.format(Locale.US, "%.1f", ch.estimatedUploadsPerWeek)
            val headerTv = TextView(this).apply {
                text = "🏭 ${ch.channelName} • 🔥 Спам-индекс: ${ch.spamScore}/100\n" +
                    "📈 ~$perWeek рол./нед • 🎲 Разброс тем: ${ch.titleRandomnessPct}%\n" +
                    "📋 ${ch.patternSummary}"
                setTextColor(Color.parseColor("#F4F4F5"))
                textSize = 12f
                setTypeface(typeface, Typeface.BOLD)
            }
            card.addView(headerTv)

            val topVideo = ch.sampleVideos.firstOrNull()
            if (topVideo != null) {
                val sampleTv = TextView(this).apply {
                    text = "🎬 Топ-ролик: «${topVideo.title.take(55)}» (${topVideo.durationSec}с)"
                    setTextColor(Color.parseColor("#A7F3D0"))
                    textSize = 11.5f
                    setPadding(0, dp(4), 0, dp(6))
                }
                card.addView(sampleTv)
            }

            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                weightSum = 2f
            }

            val dlTopBtn = createStyledButton("⬇️ Скачать топ-ролик (1080p)", "#09090B", "#10B981").apply {
                setOnClickListener {
                    val targetVid = ch.sampleVideos.firstOrNull()
                    if (targetVid == null) {
                        Toast.makeText(this@MainActivity, "Нет доступного ролика", Toast.LENGTH_SHORT).show()
                        return@setOnClickListener
                    }
                    Toast.makeText(this@MainActivity, "⏳ Качаю «${targetVid.title.take(30)}» в 1080p...", Toast.LENGTH_LONG).show()
                    lifecycleScope.launch {
                        try {
                            val item = clutterFilter.downloadSourceToStudioInbox(
                                sourceUrl = targetVid.url,
                                overrideTitle = targetVid.title,
                                overrideAuthor = ch.channelName,
                                aiSummary = "Из спам-канала «${ch.channelName}»"
                            )
                            refreshInboxTab()
                            bindSelectedSourceToStudio(item)
                            switchTab(1)
                            Toast.makeText(this@MainActivity, "✅ Скачано со спам-канала в Студию!", Toast.LENGTH_LONG).show()
                        } catch (e: Exception) {
                            Toast.makeText(this@MainActivity, "❌ Ошибка: ${e.message}", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }

            val openChannelBtn = createStyledButton("🔗 Открыть канал", "#E4E4E7", "#27272A").apply {
                setOnClickListener {
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(ch.channelUrl)))
                    } catch (_: Exception) {
                    }
                }
            }

            row.addView(dlTopBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.15f).apply { rightMargin = dp(6) })
            row.addView(openChannelBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.85f))
            card.addView(row)
            spamFarmListLayout.addView(card)
        }
    }

    private fun refreshInboxTab() {
        inboxListLayout.removeAllViews()
        val items = clutterFilter.listStudioInboxItems()
        if (items.isEmpty()) {
            val emptyView = TextView(this).apply {
                text = "Список исходников пока пуст.\nОткрой Telegram-бот -> «🧠 Умный поиск по теме» -> жми «✅ Взять (1080p)» или выбери видео из Галереи кнопкой выше."
                setTextColor(Color.parseColor("#71717A"))
                textSize = 12.5f
                setPadding(dp(12), dp(18), dp(12), dp(18))
                setBackgroundColor(Color.parseColor("#18181B"))
            }
            inboxListLayout.addView(emptyView)
            return
        }

        for (item in items) {
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(Color.parseColor("#18181B"))
                val p = dp(12)
                setPadding(p, p, p, p)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(10) }
            }

            val sizeMb = String.format(Locale.US, "%.1f", item.sizeKb / 1024.0)
            val cutNote = if (item.recommendedEndSec > item.recommendedStartSec) {
                " • ✂️ Момент: ${String.format(Locale.US, "%.0f–%.0fс", item.recommendedStartSec, item.recommendedEndSec)}"
            } else {
                ""
            }
            val titleTv = TextView(this).apply {
                text = "🎬 ${item.title}\n👤 ${item.author} • ⏱ ${item.durationSec} сек • 📦 $sizeMb МБ$cutNote"
                setTextColor(Color.parseColor("#F4F4F5"))
                textSize = 13f
                setTypeface(typeface, Typeface.BOLD)
            }
            card.addView(titleTv)

            val tagsLine = clutterFilter.extractSemanticTopicTags(
                text = "${item.title} ${item.aiSummary}",
                rawTags = item.sourceTags
            )
            if (tagsLine.isNotEmpty()) {
                val tagsTv = TextView(this).apply {
                    text = "🏷 Теги: ${tagsLine.joinToString(" ")}"
                    setTextColor(Color.parseColor("#FBBF24"))
                    textSize = 11.5f
                    setPadding(0, dp(3), 0, dp(2))
                }
                card.addView(tagsTv)
            }

            if (item.aiSummary.isNotBlank()) {
                val aiTv = TextView(this).apply {
                    text = "🧠 Что внутри: ${item.aiSummary}"
                    setTextColor(Color.parseColor("#A7F3D0"))
                    textSize = 11.5f
                    setPadding(0, dp(4), 0, dp(2))
                }
                card.addView(aiTv)
            }

            val btnRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                weightSum = 3f
                setPadding(0, dp(6), 0, 0)
            }

            val loadStudioBtn = createStyledButton("🎛 В Студию", "#09090B", "#10B981").apply {
                setOnClickListener {
                    bindSelectedSourceToStudio(item)
                    switchTab(1)
                }
            }

            val openOrigBtn = createStyledButton("🔗 Оригинал", "#E4E4E7", "#27272A").apply {
                setOnClickListener {
                    if (item.sourceUrl.startsWith("http")) {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(item.sourceUrl)))
                    } else {
                        Toast.makeText(this@MainActivity, "Это локальный файл с телефона", Toast.LENGTH_SHORT).show()
                    }
                }
            }

            val delBtn = createStyledButton("🗑 Удалить", "#FCA5A5", "#3F1D1D").apply {
                setOnClickListener {
                    clutterFilter.deleteStudioInboxItem(item.id)
                    refreshInboxTab()
                }
            }

            btnRow.addView(loadStudioBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.2f).apply { rightMargin = dp(6) })
            btnRow.addView(openOrigBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(6) })
            btnRow.addView(delBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.8f))

            card.addView(btnRow)
            inboxListLayout.addView(card)
        }
    }

    // =========================================================================
    // ВКЛАДКА 1: 🎛 ДВУХСЛОЙНАЯ ЖИВАЯ СТУДИЯ В СТИЛЕ CAPCUT / YOUCUT
    // =========================================================================
    private fun buildStudioTabView(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL

            studioSelectedSourceLabel = TextView(this@MainActivity).apply {
                text = "🎬 Исходник не выбран — открой «📥 Исходники» или импортируй из Галереи"
                setTextColor(Color.parseColor("#34D399"))
                textSize = 11.5f
                typeface = Typeface.MONOSPACE
                setPadding(0, dp(6), 0, dp(4))
            }
            addView(studioSelectedSourceLabel)

            studioCanvasModeBadge = TextView(this@MainActivity).apply {
                text = "👁 ЖИВОЙ ХОЛСТ 9:16 (Верх + Фон одновременно • Тап по экрану = Старт/Пауза)"
                setTextColor(Color.parseColor("#E4E4E7"))
                setBackgroundColor(Color.parseColor("#18181B"))
                textSize = 11f
                val p = dp(7)
                setPadding(p, dp(5), p, dp(5))
            }
            addView(studioCanvasModeBadge)

            // Centered 9:16 Composite Stage (252dp x 448dp = exact 9:16 aspect!)
            val stageWrapper = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setBackgroundColor(Color.parseColor("#101014"))
                setPadding(0, dp(8), 0, dp(8))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(4); bottomMargin = dp(6) }
            }

            stageOuterFrame = FrameLayout(this@MainActivity).apply {
                setBackgroundColor(Color.BLACK)
                layoutParams = LinearLayout.LayoutParams(dp(252), dp(448))
                clipChildren = true
                clipToPadding = true
            }

            bottomBgTexture = TextureView(this@MainActivity).apply {
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    dp(224),
                    Gravity.BOTTOM
                )
                surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                    override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                        bottomSurface?.release()
                        bottomSurface = Surface(st)
                        bottomBgPlayer?.setSurface(bottomSurface)
                        applyLiveVideoTransforms()
                    }
                    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {
                        applyLiveVideoTransforms()
                    }
                    override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                        bottomBgPlayer?.setSurface(null)
                        bottomSurface?.release()
                        bottomSurface = null
                        return true
                    }
                    override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
                }
            }

            topVideoTexture = TextureView(this@MainActivity).apply {
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    dp(224),
                    Gravity.TOP
                )
                surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                    override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                        topSurface?.release()
                        topSurface = Surface(st)
                        topSourcePlayer?.setSurface(topSurface)
                        applyLiveVideoTransforms()
                    }
                    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {
                        applyLiveVideoTransforms()
                    }
                    override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                        topSourcePlayer?.setSurface(null)
                        topSurface?.release()
                        topSurface = null
                        return true
                    }
                    override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
                }
            }

            canvasOverlayView = StudioCanvasOverlayView(this@MainActivity).apply {
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                onCanvasTapTogglePlay = { toggleLiveStagePlayPause() }
                onSplitRatioChanged = { newRatio ->
                    currentSplitTopRatio = newRatio
                    val pct = (newRatio * 100f).roundToInt()
                    if (::splitRatioLabel.isInitialized) {
                        splitRatioLabel.text = "Граница Сплита: Верх $pct% / Низ ${100 - pct}%"
                    }
                    if (::splitRatioSeekBar.isInitialized) {
                        splitRatioSeekBar.progress = (pct - 30).coerceIn(0, 40)
                    }
                    updateStageLayerLayout()
                }
                onManualCropChanged = { crop ->
                    currentManualCrop = crop
                    updateCropSummaryText()
                }
                onWatermarkBoxesChanged = { boxes, selIdx ->
                    currentWatermarkBoxes.clear()
                    currentWatermarkBoxes.addAll(boxes)
                    updateWatermarkStatusText(selIdx)
                }
            }

            stageOuterFrame.addView(bottomBgTexture)
            stageOuterFrame.addView(topVideoTexture)
            stageOuterFrame.addView(canvasOverlayView)
            stageWrapper.addView(stageOuterFrame)

            returnToLiveCompositeBtn = createStyledButton(
                "👁 Вернуться к живому 2-слойному холсту (Исходник + Фон)",
                "#09090B",
                "#38BDF8"
            ).apply {
                visibility = View.GONE
                setOnClickListener {
                    isShowingRenderedPreviewFile = false
                    visibility = View.GONE
                    val cur = selectedInboxItem
                    if (cur != null) {
                        loadSourceIntoLivePlayer(clutterFilter.getInboxVideoFile(cur), seekSec = currentTrimStartSec, autoPlay = true)
                    }
                    reloadLiveBackgroundPlayer()
                    updateStageLayerLayout()
                }
            }
            stageWrapper.addView(returnToLiveCompositeBtn, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(6) })

            addView(stageWrapper)

            // =================================================================
            // ВИЗУАЛЬНЫЙ ТАЙМЛАЙН И ПОЛЗУНКИ ТОЧНОЙ ОБРЕЗКИ (SCRUBBING + TRIM)
            // =================================================================
            val timelineCard = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(Color.parseColor("#141419"))
                val p = dp(10)
                setPadding(p, dp(8), p, dp(10))
            }

            timelineTimecodeLabel = TextView(this@MainActivity).apply {
                text = "▶ 00:00.0  •  Вырезан кусок: 0.0с – 30.0с [30.0с]"
                setTextColor(Color.parseColor("#FBBF24"))
                textSize = 11.5f
                typeface = Typeface.MONOSPACE
                setTypeface(typeface, Typeface.BOLD)
            }
            timelineCard.addView(timelineTimecodeLabel)

            playheadSeekBar = SeekBar(this@MainActivity).apply {
                max = 1000
                progress = 0
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                        if (fromUser && currentSourceDurationSec > 0.5) {
                            val targetSec = (progress / 1000.0) * currentSourceDurationSec
                            seekLiveSourceToSec(targetSec)
                            updateTimelineReadout(targetSec)
                        }
                    }
                    override fun onStartTrackingTouch(sb: SeekBar?) {
                        isUserDraggingPlayhead = true
                    }
                    override fun onStopTrackingTouch(sb: SeekBar?) {
                        isUserDraggingPlayhead = false
                    }
                })
            }
            timelineCard.addView(playheadSeekBar, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(4); bottomMargin = dp(4) })

            val transportRow = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                weightSum = 5f
            }

            playPauseTransportBtn = createCompactButton("⏸ Пауза", "#09090B", "#10B981").apply {
                setOnClickListener { toggleLiveStagePlayPause() }
            }
            val stepBackBtn = createCompactButton("⏪ -1с", "#F4F4F5", "#27272A").apply {
                setOnClickListener {
                    val curMs = topSourcePlayer?.currentPosition ?: 0
                    seekLiveSourceToSec(((curMs - 1000).coerceAtLeast(0)) / 1000.0)
                }
            }
            val stepFwdBtn = createCompactButton("⏩ +1с", "#F4F4F5", "#27272A").apply {
                setOnClickListener {
                    val curMs = topSourcePlayer?.currentPosition ?: 0
                    seekLiveSourceToSec((curMs + 1000) / 1000.0)
                }
            }
            val setStartHereBtn = createCompactButton("📍 Старт", "#09090B", "#FBBF24").apply {
                setOnClickListener {
                    val sec = (topSourcePlayer?.currentPosition ?: 0) / 1000.0
                    applyNewTrimBounds(sec, maxOf(currentTrimEndSec, sec + 1.0))
                }
            }
            val setEndHereBtn = createCompactButton("🏁 Конец", "#09090B", "#38BDF8").apply {
                setOnClickListener {
                    val sec = (topSourcePlayer?.currentPosition ?: 0) / 1000.0
                    applyNewTrimBounds(min(currentTrimStartSec, (sec - 1.0).coerceAtLeast(0.0)), sec.coerceAtLeast(1.0))
                }
            }

            transportRow.addView(playPauseTransportBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.2f).apply { rightMargin = dp(4) })
            transportRow.addView(stepBackBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.9f).apply { rightMargin = dp(4) })
            transportRow.addView(stepFwdBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.9f).apply { rightMargin = dp(4) })
            transportRow.addView(setStartHereBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f).apply { rightMargin = dp(4) })
            transportRow.addView(setEndHereBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f))
            timelineCard.addView(transportRow)

            // Dual visual range sliders for Start & End trim right under the scrubber
            val startSliderRow = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(6), 0, 0)
            }
            startSliderRow.addView(TextView(this@MainActivity).apply {
                text = "Начало:"
                setTextColor(Color.parseColor("#FBBF24"))
                textSize = 11f
                layoutParams = LinearLayout.LayoutParams(dp(54), ViewGroup.LayoutParams.WRAP_CONTENT)
            })
            trimStartSeekBar = SeekBar(this@MainActivity).apply {
                max = 1000
                progress = 0
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                        if (fromUser && !isUpdatingTrimUiProgrammatically && currentSourceDurationSec > 1.0) {
                            val newStart = ((progress / 1000.0) * currentSourceDurationSec)
                                .coerceIn(0.0, (currentTrimEndSec - 1.0).coerceAtLeast(0.0))
                            applyNewTrimBounds(newStart, currentTrimEndSec, seekToEdge = newStart)
                        }
                    }
                    override fun onStartTrackingTouch(sb: SeekBar?) {}
                    override fun onStopTrackingTouch(sb: SeekBar?) {}
                })
            }
            startSliderRow.addView(trimStartSeekBar)
            timelineCard.addView(startSliderRow)

            val endSliderRow = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(2), 0, 0)
            }
            endSliderRow.addView(TextView(this@MainActivity).apply {
                text = "Конец:"
                setTextColor(Color.parseColor("#38BDF8"))
                textSize = 11f
                layoutParams = LinearLayout.LayoutParams(dp(54), ViewGroup.LayoutParams.WRAP_CONTENT)
            })
            trimEndSeekBar = SeekBar(this@MainActivity).apply {
                max = 1000
                progress = 1000
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                        if (fromUser && !isUpdatingTrimUiProgrammatically && currentSourceDurationSec > 1.0) {
                            val newEnd = ((progress / 1000.0) * currentSourceDurationSec)
                                .coerceIn(currentTrimStartSec + 1.0, currentSourceDurationSec)
                            applyNewTrimBounds(currentTrimStartSec, newEnd, seekToEdge = (newEnd - 0.5).coerceAtLeast(currentTrimStartSec))
                        }
                    }
                    override fun onStartTrackingTouch(sb: SeekBar?) {}
                    override fun onStopTrackingTouch(sb: SeekBar?) {}
                })
            }
            endSliderRow.addView(trimEndSeekBar)
            timelineCard.addView(endSliderRow)

            addView(timelineCard)

            // =================================================================
            // ГОРИЗОНТАЛЬНАЯ ЛЕНТА ИНСТРУМЕНТОВ CAPCUT / YOUCUT (6 ВКЛАДОК)
            // =================================================================
            val toolScroll = HorizontalScrollView(this@MainActivity).apply {
                isHorizontalScrollBarEnabled = false
                setPadding(0, dp(8), 0, dp(6))
            }
            val toolBarRow = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
            }

            val toolTitles = listOf(
                "✂️ Обрезка",
                "📐 Кроп и Сплит",
                "🧹 Вотермарка",
                "🎚 Звук и Музыка",
                "💬 Сабы и Озвучка",
                "🛡 Анти-Детект"
            )

            studioToolButtons.clear()
            studioToolPanels.clear()

            toolTitles.forEachIndexed { idx, title ->
                val btn = Button(this@MainActivity).apply {
                    text = title
                    textSize = 11.5f
                    isAllCaps = false
                    val ph = dp(12)
                    setPadding(ph, dp(8), ph, dp(8))
                    setOnClickListener { switchStudioToolPanel(idx) }
                }
                studioToolButtons.add(btn)
                toolBarRow.addView(btn, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { rightMargin = dp(6) })
            }
            toolScroll.addView(toolBarRow)
            addView(toolScroll)

            // Build the 6 clean modular tool panels
            val panelTrim = buildToolPanelTrimAndSpeed()
            val panelCropSplit = buildToolPanelCropAndSplit()
            val panelWatermark = buildToolPanelWatermarkAndHardsubs()
            val panelAudio = buildToolPanelAudioAndStems()
            val panelSubsDub = buildToolPanelSubtitlesAndDubbing()
            val panelAntiDetect = buildToolPanelAntiDetect()

            listOf(panelTrim, panelCropSplit, panelWatermark, panelAudio, panelSubsDub, panelAntiDetect).forEach { panel ->
                studioToolPanels.add(panel)
                addView(panel)
            }

            switchStudioToolPanel(1) // Open Crop & Split by default so user immediately sees composite & freeform controls

            // =================================================================
            // НИЖНИЙ БЛОК БЫСТРОГО ТЕСТА И ФИНАЛЬНОГО ЭКСПОРТА В ГАЛЕРЕЮ
            // =================================================================
            addView(sectionHeader("ЭКСПОРТ ПРОЕКТА (FULL-HD 12 МБИТ/С)"))

            studioRenderStatusView = TextView(this@MainActivity).apply {
                text = "Готово к работе. Настрой кроп/обрезку на живом холсте сверху и выбери тест 5 сек или полный экспорт."
                setTextColor(Color.parseColor("#FBBF24"))
                setBackgroundColor(Color.parseColor("#18181B"))
                textSize = 11.5f
                typeface = Typeface.MONOSPACE
                val p = dp(10)
                setPadding(p, p, p, p)
            }
            addView(studioRenderStatusView)

            val exportRow = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                weightSum = 2.4f
                setPadding(0, dp(8), 0, 0)
            }

            val preview5sBtn = createStyledButton(
                "⚡ Тест 5 сек",
                "#F4F4F5",
                "#3B82F6"
            ).apply {
                setTypeface(typeface, Typeface.BOLD)
                setOnClickListener { runStudioRender(isPreviewPass = true, forceCleanYouCutExport = false) }
            }

            val masterGalleryBtn = createStyledButton(
                "💾 ЭКСПОРТ В ГАЛЕРЕЮ (1080p)",
                "#09090B",
                "#10B981"
            ).apply {
                setTypeface(typeface, Typeface.BOLD)
                setOnClickListener { runStudioRender(isPreviewPass = false, forceCleanYouCutExport = false) }
            }

            exportRow.addView(preview5sBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.85f).apply { rightMargin = dp(6) })
            exportRow.addView(masterGalleryBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.55f))
            addView(exportRow)
        }
    }

    private fun switchStudioToolPanel(activeIndex: Int) {
        studioToolPanels.forEachIndexed { idx, panel ->
            panel.visibility = if (idx == activeIndex) View.VISIBLE else View.GONE
        }
        studioToolButtons.forEachIndexed { idx, btn ->
            if (idx == activeIndex) {
                btn.setBackgroundColor(Color.parseColor("#F59E0B"))
                btn.setTextColor(Color.parseColor("#09090B"))
                btn.setTypeface(btn.typeface, Typeface.BOLD)
            } else {
                btn.setBackgroundColor(Color.parseColor("#27272A"))
                btn.setTextColor(Color.parseColor("#E4E4E7"))
                btn.setTypeface(btn.typeface, Typeface.NORMAL)
            }
        }
        // Automatically return canvas touch mode to normal preview unless user stays on Crop or Watermark panel
        if (activeIndex != 1 && (canvasOverlayView.touchMode == CanvasTouchMode.FREEFORM_CROP || canvasOverlayView.touchMode == CanvasTouchMode.SPLIT_SEAM)) {
            setCanvasTouchModeAndRefresh(CanvasTouchMode.PLAY_PAUSE_ONLY)
        }
        if (activeIndex != 2 && canvasOverlayView.touchMode == CanvasTouchMode.WATERMARK_ERASER) {
            setCanvasTouchModeAndRefresh(CanvasTouchMode.PLAY_PAUSE_ONLY)
        }
    }

    // -------------------------------------------------------------------------
    // ПАНЕЛЬ 0: ✂️ ОБРЕЗКА, СКОРОСТЬ И ЧИСТЫЙ ЭКСПОРТ БЕЗ ШАБЛОНОВ (КАК YOUCUT)
    // -------------------------------------------------------------------------
    private fun buildToolPanelTrimAndSpeed(): LinearLayout {
        return createToolCardContainer().apply {
            addView(panelTitle("✂️ ТОЧНАЯ ОБРЕЗКА ПО СЕКУНДАМ + СКОРОСТЬ + ЧИСТЫЙ ЭКСПОРТ"))

            val trimInputsRow = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                weightSum = 2f
            }

            val startCol = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(8) }
            }
            startCol.addView(fieldLabel("Старт (сек):"))
            trimStartInput = createInputField("0.0", "0.0", false).apply {
                addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                    override fun afterTextChanged(s: Editable?) {
                        if (isUpdatingTrimUiProgrammatically) return
                        val parsed = s?.toString()?.replace(",", ".")?.toDoubleOrNull() ?: return
                        currentTrimStartSec = parsed.coerceIn(0.0, (currentTrimEndSec - 0.5).coerceAtLeast(0.0))
                        syncTrimSeekBarsOnly()
                    }
                })
            }
            startCol.addView(trimStartInput)

            val endCol = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            endCol.addView(fieldLabel("Конец (сек):"))
            trimEndInput = createInputField("30.0", "30.0", false).apply {
                addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                    override fun afterTextChanged(s: Editable?) {
                        if (isUpdatingTrimUiProgrammatically) return
                        val parsed = s?.toString()?.replace(",", ".")?.toDoubleOrNull() ?: return
                        if (parsed > currentTrimStartSec + 0.5) {
                            currentTrimEndSec = parsed.coerceAtMost(currentSourceDurationSec.coerceAtLeast(parsed))
                            syncTrimSeekBarsOnly()
                        }
                    }
                })
            }
            endCol.addView(trimEndInput)

            trimInputsRow.addView(startCol)
            trimInputsRow.addView(endCol)
            addView(trimInputsRow)

            val nudgeRow = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                weightSum = 5f
                setPadding(0, dp(6), 0, 0)
            }
            nudgeRow.addView(createCompactButton("Старт -0.5", "#E4E4E7", "#27272A").apply {
                setOnClickListener { applyNewTrimBounds((currentTrimStartSec - 0.5).coerceAtLeast(0.0), currentTrimEndSec) }
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(4) })
            nudgeRow.addView(createCompactButton("Старт +0.5", "#E4E4E7", "#27272A").apply {
                setOnClickListener { applyNewTrimBounds((currentTrimStartSec + 0.5).coerceAtMost(currentTrimEndSec - 1.0), currentTrimEndSec) }
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(4) })
            nudgeRow.addView(createCompactButton("Конец -0.5", "#E4E4E7", "#27272A").apply {
                setOnClickListener { applyNewTrimBounds(currentTrimStartSec, (currentTrimEndSec - 0.5).coerceAtLeast(currentTrimStartSec + 1.0)) }
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(4) })
            nudgeRow.addView(createCompactButton("Конец +0.5", "#E4E4E7", "#27272A").apply {
                setOnClickListener { applyNewTrimBounds(currentTrimStartSec, (currentTrimEndSec + 0.5).coerceAtMost(currentSourceDurationSec)) }
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(4) })
            nudgeRow.addView(createCompactButton("Весь ролик", "#09090B", "#FBBF24").apply {
                setOnClickListener { applyNewTrimBounds(0.0, currentSourceDurationSec) }
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(nudgeRow)

            speedValueLabel = fieldLabel("⏩ Скорость воспроизведения и рендера: 1.00x (оригинал)")
            addView(speedValueLabel)

            val speedPresets = listOf(0.85f, 1.0f, 1.05f, 1.15f, 1.25f, 1.50f)
            val speedRow = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                weightSum = speedPresets.size.toFloat()
            }
            for (spd in speedPresets) {
                val b = createCompactButton("${spd}x", "#F4F4F5", "#27272A").apply {
                    setOnClickListener {
                        currentPlaybackSpeed = spd
                        speedValueLabel.text = String.format(Locale.US, "⏩ Скорость воспроизведения и рендера: %.2fx", spd)
                        applyLivePlaybackSpeed()
                    }
                }
                speedRow.addView(b, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(3) })
            }
            addView(speedRow)

            val cleanExportBtn = createStyledButton(
                "✂️ СОХРАНИТЬ ЧИСТУЮ ОБРЕЗКУ / КРОП ОРИГИНАЛА (БЕЗ ФОНОВ, КАК YOUCUT)",
                "#09090B",
                "#34D399"
            ).apply {
                setTypeface(typeface, Typeface.BOLD)
                setOnClickListener {
                    runStudioRender(isPreviewPass = false, forceCleanYouCutExport = true)
                }
            }
            addView(cleanExportBtn, buttonLayoutParams())
        }
    }

    // -------------------------------------------------------------------------
    // ПАНЕЛЬ 1: 📐 СВОБОДНЫЙ КРОП (ГОРИЗОНТ + ВЕРТИКАЛЬ), ШОВ СПЛИТА И ФОН
    // -------------------------------------------------------------------------
    private fun buildToolPanelCropAndSplit(): LinearLayout {
        return createToolCardContainer().apply {
            addView(panelTitle("📐 МАКЕТ ХОЛСТА, СВОБОДНЫЙ КРОП ПАЛЬЦЕМ И НИЖНИЙ ФОН"))

            templateSelectBtn = createStyledButton(
                "📐 Макет: ${currentLayoutLayer.label} (нажми для смены)",
                "#09090B",
                "#F59E0B"
            ).apply {
                setTypeface(typeface, Typeface.BOLD)
                setOnClickListener {
                    val values = VideoLayoutLayer.entries
                    currentLayoutLayer = values[(currentLayoutLayer.ordinal + 1) % values.size]
                    text = "📐 Макет: ${currentLayoutLayer.label} (нажми для смены)"
                    updateStageLayerLayout()
                }
            }
            addView(templateSelectBtn, buttonLayoutParams())

            // Interactive Freeform Crop & Split Seam Touch Toggles
            val touchControlsRow = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                weightSum = 2f
                setPadding(0, dp(8), 0, 0)
            }

            toggleFreeformCropBtn = createStyledButton(
                "✋ Двигать Кроп пальцем (Ш×В)",
                "#09090B",
                "#10B981"
            ).apply {
                setTypeface(typeface, Typeface.BOLD)
                setOnClickListener {
                    if (canvasOverlayView.touchMode == CanvasTouchMode.FREEFORM_CROP) {
                        setCanvasTouchModeAndRefresh(CanvasTouchMode.PLAY_PAUSE_ONLY)
                        Toast.makeText(this@MainActivity, "✅ Кроп применён на живое превью!", Toast.LENGTH_SHORT).show()
                    } else {
                        if (!currentManualCrop.enabled) {
                            currentManualCrop = ManualCropRect(enabled = true, leftNorm = 0.05f, topNorm = 0.05f, rightNorm = 0.95f, bottomNorm = 0.95f)
                            canvasOverlayView.manualCrop = currentManualCrop
                        }
                        setCanvasTouchModeAndRefresh(CanvasTouchMode.FREEFORM_CROP)
                        Toast.makeText(this@MainActivity, "Тяни зелёные углы/грани прямо на видео сверху!", Toast.LENGTH_SHORT).show()
                    }
                }
            }

            toggleSplitDragBtn = createStyledButton(
                "⇅ Двигать шов Сплита",
                "#F4F4F5",
                "#27272A"
            ).apply {
                setOnClickListener {
                    if (currentLayoutLayer != VideoLayoutLayer.SPLIT_VSTACK) {
                        currentLayoutLayer = VideoLayoutLayer.SPLIT_VSTACK
                        templateSelectBtn.text = "📐 Макет: ${currentLayoutLayer.label} (нажми для смены)"
                        updateStageLayerLayout()
                    }
                    if (canvasOverlayView.touchMode == CanvasTouchMode.SPLIT_SEAM) {
                        setCanvasTouchModeAndRefresh(CanvasTouchMode.PLAY_PAUSE_ONLY)
                    } else {
                        setCanvasTouchModeAndRefresh(CanvasTouchMode.SPLIT_SEAM)
                    }
                }
            }

            touchControlsRow.addView(toggleFreeformCropBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.15f).apply { rightMargin = dp(6) })
            touchControlsRow.addView(toggleSplitDragBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.85f))
            addView(touchControlsRow)

            // Quick Crop Aspect Ratio Presets + Reset
            val cropPresetRow = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                weightSum = 5f
                setPadding(0, dp(6), 0, 0)
            }
            cropPresetRow.addView(createCompactButton("9:16", "#E4E4E7", "#27272A").apply {
                setOnClickListener { applyAspectPresetCrop(9f / 16f) }
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(4) })
            cropPresetRow.addView(createCompactButton("9:8 Верх", "#E4E4E7", "#27272A").apply {
                setOnClickListener { applyAspectPresetCrop(9f / 8f) }
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(4) })
            cropPresetRow.addView(createCompactButton("1:1", "#E4E4E7", "#27272A").apply {
                setOnClickListener { applyAspectPresetCrop(1f) }
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(4) })
            cropPresetRow.addView(createCompactButton("16:9", "#E4E4E7", "#27272A").apply {
                setOnClickListener { applyAspectPresetCrop(16f / 9f) }
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(4) })
            cropPresetRow.addView(createCompactButton("Сброс", "#FCA5A5", "#3F1D1D").apply {
                setOnClickListener {
                    currentManualCrop = ManualCropRect(enabled = false)
                    canvasOverlayView.manualCrop = currentManualCrop
                    setCanvasTouchModeAndRefresh(CanvasTouchMode.PLAY_PAUSE_ONLY)
                    updateCropSummaryText()
                }
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(cropPresetRow)

            cropSummaryLabel = TextView(this@MainActivity).apply {
                text = "📐 Свободный кроп: Выкл (используется авто-кадрирование). Нажми «✋ Двигать Кроп пальцем», чтобы обрезать края по горизонтали и вертикали."
                setTextColor(Color.parseColor("#A7F3D0"))
                textSize = 11f
                setPadding(0, dp(4), 0, dp(4))
            }
            addView(cropSummaryLabel)

            splitRatioLabel = fieldLabel("Граница Сплита: Верх 50% / Низ 50%")
            addView(splitRatioLabel)

            splitRatioSeekBar = SeekBar(this@MainActivity).apply {
                max = 40 // 30% .. 70%
                progress = 20
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                        val pct = (30 + progress).coerceIn(30, 70)
                        currentSplitTopRatio = pct / 100f
                        splitRatioLabel.text = "Граница Сплита: Верх $pct% / Низ ${100 - pct}%"
                        canvasOverlayView.splitTopRatio = currentSplitTopRatio
                        if (fromUser) {
                            updateStageLayerLayout()
                        }
                    }
                    override fun onStartTrackingTouch(sb: SeekBar?) {}
                    override fun onStopTrackingTouch(sb: SeekBar?) {}
                })
            }
            addView(splitRatioSeekBar)

            poolClipSelectBtn = createStyledButton(
                "🎬 Нижний фон: Авто-микс из пула (нажми для смены вживую)",
                "#F4F4F5",
                "#27272A"
            ).apply {
                setOnClickListener {
                    cyclePoolClipSelection()
                }
            }
            addView(poolClipSelectBtn, buttonLayoutParams())

            val autoFetchTopicBgBtn = createStyledButton(
                "⚡ Скачать с YouTube залипалку под тему этого исходника",
                "#F4F4F5",
                "#3B82F6"
            ).apply {
                setOnClickListener {
                    val cur = selectedInboxItem
                    if (cur == null) {
                        Toast.makeText(this@MainActivity, "Сначала выбери исходник во вкладке «📥 Исходники»", Toast.LENGTH_SHORT).show()
                        return@setOnClickListener
                    }
                    val tags = clutterFilter.extractSemanticTopicTags("${cur.title} ${cur.aiSummary}", cur.sourceTags)
                    val query = tags.firstOrNull()?.removePrefix("#") ?: cur.title.take(25)
                    Toast.makeText(this@MainActivity, "⏳ Ищу и качаю залипалку по теме «$query»...", Toast.LENGTH_LONG).show()
                    lifecycleScope.launch {
                        val added = clutterFilter.downloadTaggedClutterByTopic(query, maxClipsToAdd = 1)
                        if (added.isNotEmpty()) {
                            selectedPoolClipName = added.first().name
                            updatePoolSelectorButtonText()
                            refreshPoolTab()
                            reloadLiveBackgroundPlayer()
                            Toast.makeText(this@MainActivity, "✅ Залипалка скачана и запущена в нижнем окне!", Toast.LENGTH_LONG).show()
                        } else {
                            Toast.makeText(this@MainActivity, "⚠️ Попробуй задать слово во вкладке «🗂 Фоны»", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
            addView(autoFetchTopicBgBtn, buttonLayoutParams())

            chkSmartFace = createCheckBox("🎯 Авто-центровка говорящего лица (Google ML Kit, если ручной кроп выкл)", true)
            addView(chkSmartFace)
        }
    }

    // -------------------------------------------------------------------------
    // ПАНЕЛЬ 2: 🧹 УДАЛЕНИЕ ВОТЕРМАРКИ / ЛОГОТИПА / СТАРЫХ ВШИТЫХ СУБТИТРОВ
    // -------------------------------------------------------------------------
    private fun buildToolPanelWatermarkAndHardsubs(): LinearLayout {
        return createToolCardContainer().apply {
            addView(panelTitle("🧹 ИНТЕРАКТИВНОЕ УДАЛЕНИЕ ВОТЕРМАРКИ И ВШИТЫХ СУБТИТРОВ"))

            val hint = TextView(this@MainActivity).apply {
                text = "Добавь рамку на холст сверху -> наведи пальцем прямо на чужой логотип, никнейм TikTok/TG или вшитые субтитры -> потяни за угол для размера."
                setTextColor(Color.parseColor("#A1A1AA"))
                textSize = 11.5f
                setPadding(0, 0, 0, dp(6))
            }
            addView(hint)

            val wmButtonsRow = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                weightSum = 3f
            }

            val addBoxBtn = createCompactButton("➕ Рамка", "#09090B", "#F43F5E").apply {
                setOnClickListener {
                    addWatermarkBoxPreset(WatermarkMaskBox(xNorm = 0.62f, yNorm = 0.05f, wNorm = 0.32f, hNorm = 0.09f))
                }
            }
            val delSelectedBoxBtn = createCompactButton("🗑 Убрать", "#FCA5A5", "#3F1D1D").apply {
                setOnClickListener {
                    val idx = canvasOverlayView.selectedWatermarkIndex
                    if (idx in currentWatermarkBoxes.indices) {
                        currentWatermarkBoxes.removeAt(idx)
                        canvasOverlayView.setWatermarkList(currentWatermarkBoxes)
                        updateWatermarkStatusText(canvasOverlayView.selectedWatermarkIndex)
                    } else if (currentWatermarkBoxes.isNotEmpty()) {
                        currentWatermarkBoxes.clear()
                        canvasOverlayView.setWatermarkList(emptyList())
                        updateWatermarkStatusText(-1)
                    }
                }
            }
            toggleWatermarkTouchBtn = createCompactButton("✋ Двигать", "#09090B", "#FBBF24").apply {
                setOnClickListener {
                    if (canvasOverlayView.touchMode == CanvasTouchMode.WATERMARK_ERASER) {
                        setCanvasTouchModeAndRefresh(CanvasTouchMode.PLAY_PAUSE_ONLY)
                    } else {
                        if (currentWatermarkBoxes.isEmpty()) {
                            currentWatermarkBoxes.add(WatermarkMaskBox(0.62f, 0.05f, 0.32f, 0.09f))
                            canvasOverlayView.setWatermarkList(currentWatermarkBoxes, 0)
                        }
                        setCanvasTouchModeAndRefresh(CanvasTouchMode.WATERMARK_ERASER)
                    }
                }
            }

            wmButtonsRow.addView(addBoxBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(4) })
            wmButtonsRow.addView(toggleWatermarkTouchBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.1f).apply { rightMargin = dp(4) })
            wmButtonsRow.addView(delSelectedBoxBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.9f))
            addView(wmButtonsRow)

            // 1-Tap Quick Placement Presets
            val quickCornerRow = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                weightSum = 5f
                setPadding(0, dp(6), 0, 0)
            }
            quickCornerRow.addView(createCompactButton("↖ В-Лево", "#E4E4E7", "#27272A").apply {
                setOnClickListener { addWatermarkBoxPreset(WatermarkMaskBox(0.03f, 0.03f, 0.30f, 0.08f)) }
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(3) })
            quickCornerRow.addView(createCompactButton("↗ В-Право", "#E4E4E7", "#27272A").apply {
                setOnClickListener { addWatermarkBoxPreset(WatermarkMaskBox(0.66f, 0.03f, 0.31f, 0.08f)) }
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(3) })
            quickCornerRow.addView(createCompactButton("↙ Н-Лево", "#E4E4E7", "#27272A").apply {
                setOnClickListener { addWatermarkBoxPreset(WatermarkMaskBox(0.03f, 0.40f, 0.32f, 0.08f)) }
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(3) })
            quickCornerRow.addView(createCompactButton("↘ Н-Право", "#E4E4E7", "#27272A").apply {
                setOnClickListener { addWatermarkBoxPreset(WatermarkMaskBox(0.65f, 0.40f, 0.32f, 0.08f)) }
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(3) })
            quickCornerRow.addView(createCompactButton("▬ Сабы", "#E4E4E7", "#27272A").apply {
                setOnClickListener { addWatermarkBoxPreset(WatermarkMaskBox(0.08f, 0.36f, 0.84f, 0.11f)) }
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(quickCornerRow)

            watermarkEraseMethodBtn = createStyledButton(
                "🖌 Метод затирки рамок: ${currentWatermarkEraseMethod.label}",
                "#09090B",
                "#38BDF8"
            ).apply {
                setOnClickListener {
                    val methods = WatermarkEraseMethod.entries
                    currentWatermarkEraseMethod = methods[(currentWatermarkEraseMethod.ordinal + 1) % methods.size]
                    text = "🖌 Метод затирки рамок: ${currentWatermarkEraseMethod.label}"
                }
            }
            addView(watermarkEraseMethodBtn, buttonLayoutParams())

            watermarkStatusLabel = TextView(this@MainActivity).apply {
                text = "🧹 Активных рамок удаления: 0 шт."
                setTextColor(Color.parseColor("#FDE68A"))
                textSize = 11.5f
                setPadding(0, dp(4), 0, dp(6))
            }
            addView(watermarkStatusLabel)

            val scanHardsubBtn = createStyledButton(
                "🔍 Авто-поиск старых вшитых субтитров на исходнике",
                "#09090B",
                "#F59E0B"
            ).apply {
                setTypeface(typeface, Typeface.BOLD)
                setOnClickListener {
                    val cur = selectedInboxItem
                    if (cur == null) {
                        Toast.makeText(this@MainActivity, "Сначала выбери исходник во вкладке «📥 Исходники»", Toast.LENGTH_SHORT).show()
                        return@setOnClickListener
                    }
                    val srcFile = clutterFilter.getInboxVideoFile(cur)
                    val span = (currentTrimEndSec - currentTrimStartSec).coerceIn(5.0, 25.0)
                    hardsubStatusLabel.text = "⏳ Сканирую кадры на наличие вшитых субтитров..."
                    lifecycleScope.launch {
                        try {
                            val reg = faceHelper.detectBurnedInSubtitleRegion(srcFile, currentTrimStartSec, span)
                            currentDetectedHardsubRegion = reg
                            if (reg.detected) {
                                if (currentHardsubRemovalMode == ExistingSubtitleRemovalMode.OFF) {
                                    currentHardsubRemovalMode = ExistingSubtitleRemovalMode.AUTO_CROP_ABOVE_SUBS
                                    hardsubRemovalModeBtn.text = "🧹 Старые субтитры: ${currentHardsubRemovalMode.label}"
                                }
                                hardsubStatusLabel.text = "✅ ${reg.summary}\nМетод: ${currentHardsubRemovalMode.label}"
                            } else {
                                hardsubStatusLabel.text = "ℹ️ ${reg.summary} (можешь выделить зону вручную кнопкой «➕ Рамка»)"
                            }
                        } catch (e: Exception) {
                            hardsubStatusLabel.text = "❌ Ошибка сканера: ${e.message}"
                        }
                    }
                }
            }
            addView(scanHardsubBtn, buttonLayoutParams())

            hardsubRemovalModeBtn = createStyledButton(
                "🧹 Старые субтитры: ${currentHardsubRemovalMode.label}",
                "#F4F4F5",
                "#27272A"
            ).apply {
                setOnClickListener {
                    val modes = ExistingSubtitleRemovalMode.entries
                    currentHardsubRemovalMode = modes[(currentHardsubRemovalMode.ordinal + 1) % modes.size]
                    text = "🧹 Старые субтитры: ${currentHardsubRemovalMode.label}"
                }
            }
            addView(hardsubRemovalModeBtn, buttonLayoutParams())

            hardsubStatusLabel = TextView(this@MainActivity).apply {
                text = "Старые вшитые субтитры: выбери авто-кроп выше или выдели рамкой вручную."
                setTextColor(Color.parseColor("#A1A1AA"))
                textSize = 11f
                setPadding(0, dp(4), 0, 0)
            }
            addView(hardsubStatusLabel)
        }
    }

    // -------------------------------------------------------------------------
    // ПАНЕЛЬ 3: 🎚 МИКШЕР ГРОМКОСТИ, РАЗДЕЛЕНИЕ НА 3 ДОРОЖКИ И AI-SHAZAM
    // -------------------------------------------------------------------------
    private fun buildToolPanelAudioAndStems(): LinearLayout {
        return createToolCardContainer().apply {
            addView(panelTitle("🎚 МИКШЕР ГРОМКОСТИ КАНАЛОВ + 3 ДОРОЖКИ + AI-SHAZAM"))

            mainVolLabel = fieldLabel("🔊 Громкость исходника: 100%")
            addView(mainVolLabel)
            addView(SeekBar(this@MainActivity).apply {
                max = 150
                progress = 100
                setOnSeekBarChangeListener(simpleSeekListener { p ->
                    currentMainVolume = p / 100f
                    mainVolLabel.text = "🔊 Громкость исходника: $p%"
                    val clamped = currentMainVolume.coerceIn(0f, 1f)
                    topSourcePlayer?.setVolume(clamped, clamped)
                })
            })

            bgVolLabel = fieldLabel("🎬 Громкость нижнего фона (залипалки): 0% (без звука)")
            addView(bgVolLabel)
            addView(SeekBar(this@MainActivity).apply {
                max = 100
                progress = 0
                setOnSeekBarChangeListener(simpleSeekListener { p ->
                    currentBgVolume = p / 100f
                    bgVolLabel.text = "🎬 Громкость нижнего фона (залипалки): $p%"
                    val clamped = currentBgVolume.coerceIn(0f, 1f)
                    bottomBgPlayer?.setVolume(clamped, clamped)
                })
            })

            voVolLabel = fieldLabel("🎙 Громкость новой озвучки (TTS / своей): 125%")
            addView(voVolLabel)
            addView(SeekBar(this@MainActivity).apply {
                max = 150
                progress = 125
                setOnSeekBarChangeListener(simpleSeekListener { p ->
                    currentVoiceoverVolume = p / 100f
                    voVolLabel.text = "🎙 Громкость новой озвучки (TTS / своей): $p%"
                })
            })

            musicVolLabel = fieldLabel("🎵 Громкость новой подменной музыки: 25%")
            addView(musicVolLabel)
            addView(SeekBar(this@MainActivity).apply {
                max = 100
                progress = 25
                setOnSeekBarChangeListener(simpleSeekListener { p ->
                    currentMusicVolume = p / 100f
                    musicVolLabel.text = "🎵 Громкость новой подменной музыки: $p%"
                })
            })

            val splitStemsBtn = createStyledButton(
                "🎚 Разделить звук куска на 3 дорожки (Голос / Интершум / Музыка)",
                "#09090B",
                "#F59E0B"
            ).apply {
                setTypeface(typeface, Typeface.BOLD)
                setOnClickListener {
                    val cur = selectedInboxItem
                    if (cur == null) {
                        Toast.makeText(this@MainActivity, "Сначала выбери исходник", Toast.LENGTH_SHORT).show()
                        return@setOnClickListener
                    }
                    val srcFile = clutterFilter.getInboxVideoFile(cur)
                    stemStatusLabel.text = "⏳ Разделяю звук куска (${String.format(Locale.US, "%.1f–%.1fс", currentTrimStartSec, currentTrimEndSec)}) на Голос, Интершум (стерео+моно) и Музыку..."
                    lifecycleScope.launch {
                        try {
                            val stems = hardwareEngine.splitVideoAudioIntoStems(srcFile, currentTrimStartSec, currentTrimEndSec)
                            currentSeparatedStems = stems
                            currentStemRoutingMode = AudioStemRoutingMode.DUB_OVER_CLEAN_AMBIENCE
                            stemRoutingModeBtn.text = "🎛 ${currentStemRoutingMode.label}"
                            stemStatusLabel.text = "✅ Дорожки готовы!\n" +
                                "• 🗣 Голос (105–7800Hz без обрывов): ${stems.vocalsOverlayFile.length() / 1024} KB\n" +
                                "• 🏍 Интершум (гибрид Stereo+Mono): ${stems.ambienceSfxFile.length() / 1024} KB\n" +
                                "• 🎵 Музыкальный подклад: ${stems.musicHarmonicsFile.length() / 1024} KB"
                        } catch (e: Exception) {
                            stemStatusLabel.text = "❌ Ошибка разделения дорожек: ${e.message}"
                        }
                    }
                }
            }
            addView(splitStemsBtn, buttonLayoutParams())

            stemRoutingModeBtn = createStyledButton(
                "🎛 ${currentStemRoutingMode.label}",
                "#F4F4F5",
                "#27272A"
            ).apply {
                setOnClickListener {
                    val modes = AudioStemRoutingMode.entries
                    currentStemRoutingMode = modes[(currentStemRoutingMode.ordinal + 1) % modes.size]
                    text = "🎛 ${currentStemRoutingMode.label}"
                }
            }
            addView(stemRoutingModeBtn, buttonLayoutParams())

            val listenStemsRow = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                weightSum = 4f
                setPadding(0, dp(6), 0, 0)
            }
            listenStemsRow.addView(createCompactButton("▶️ Голос", "#09090B", "#38BDF8").apply {
                setOnClickListener {
                    val f = currentSeparatedStems?.vocalsOverlayFile
                    if (f != null && f.exists()) playStemAudioPreview(f, "Выделенный Голос")
                    else Toast.makeText(this@MainActivity, "Сначала нажми «Разделить на 3 дорожки»", Toast.LENGTH_SHORT).show()
                }
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(4) })
            listenStemsRow.addView(createCompactButton("▶️ Шум", "#09090B", "#10B981").apply {
                setOnClickListener {
                    val f = currentSeparatedStems?.ambienceSfxFile
                    if (f != null && f.exists()) playStemAudioPreview(f, "Чистый Интершум")
                    else Toast.makeText(this@MainActivity, "Сначала нажми «Разделить на 3 дорожки»", Toast.LENGTH_SHORT).show()
                }
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(4) })
            listenStemsRow.addView(createCompactButton("▶️ Музыка", "#09090B", "#FBBF24").apply {
                setOnClickListener {
                    val f = currentSeparatedStems?.musicHarmonicsFile
                    if (f != null && f.exists()) playStemAudioPreview(f, "Музыкальная подложка")
                    else Toast.makeText(this@MainActivity, "Сначала нажми «Разделить на 3 дорожки»", Toast.LENGTH_SHORT).show()
                }
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.1f).apply { rightMargin = dp(4) })
            listenStemsRow.addView(createCompactButton("⏹ Стоп", "#FCA5A5", "#3F1D1D").apply {
                setOnClickListener { stopStemAudioPreview() }
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.9f))
            addView(listenStemsRow)

            stemStatusLabel = TextView(this@MainActivity).apply {
                text = "🎚 Дорожки ещё не разделялись."
                setTextColor(Color.parseColor("#FDE68A"))
                textSize = 11f
                setPadding(0, dp(4), 0, dp(6))
            }
            addView(stemStatusLabel)

            musicRemovalBtn = createStyledButton(
                "🎵 Фильтр фоновой музыки: ${currentMusicRemovalMode.label}",
                "#09090B",
                "#38BDF8"
            ).apply {
                setOnClickListener {
                    val modes = MusicRemovalMode.entries
                    currentMusicRemovalMode = modes[(currentMusicRemovalMode.ordinal + 1) % modes.size]
                    text = "🎵 Фильтр фоновой музыки: ${currentMusicRemovalMode.label}"
                }
            }
            addView(musicRemovalBtn, buttonLayoutParams())

            val aiShazamBtn = createStyledButton(
                "🔎 AI-Shazam: Узнать музыку в видео и заменить на похожую",
                "#09090B",
                "#34D399"
            ).apply {
                setTypeface(typeface, Typeface.BOLD)
                setOnClickListener {
                    val cur = selectedInboxItem
                    if (cur == null) {
                        Toast.makeText(this@MainActivity, "Сначала выбери исходник", Toast.LENGTH_SHORT).show()
                        return@setOnClickListener
                    }
                    val srcFile = clutterFilter.getInboxVideoFile(cur)
                    shazamStatusLabel.text = "⏳ AI-Shazam анализирует музыкальную дорожку и ищет похожую без АП..."
                    lifecycleScope.launch {
                        try {
                            val stems = currentSeparatedStems ?: hardwareEngine.splitVideoAudioIntoStems(srcFile, currentTrimStartSec, currentTrimEndSec).also {
                                currentSeparatedStems = it
                            }
                            val match = subtitleTransformer.analyzeMusicStemAndFindSimilar(
                                musicStemFile = stems.musicHarmonicsFile,
                                videoTitle = "${cur.title} ${cur.aiSummary}"
                            )
                            lastShazamResult = match
                            currentShazamCandidateIndex = 0
                            if (match.candidateTracks.isNotEmpty()) {
                                val (firstUrl, firstTitle) = match.candidateTracks.first()
                                val bgmFile = subtitleTransformer.downloadReplacementMusicCandidate(firstUrl, firstTitle)
                                currentReplacementMusicFile = bgmFile
                                toggleReplaceMusicChk.isChecked = true
                                if (currentStemRoutingMode == AudioStemRoutingMode.FULL_ORIGINAL_MIX) {
                                    currentStemRoutingMode = AudioStemRoutingMode.VOCAL_PLUS_AMBIENCE_NO_MUSIC
                                    stemRoutingModeBtn.text = "🎛 ${currentStemRoutingMode.label}"
                                }
                                shazamStatusLabel.text = "🎵 Узнано: ${match.detectedTrackAndGenre} (${match.moodAndBpm})\n✅ Заменено на: «$firstTitle»"
                            } else {
                                shazamStatusLabel.text = "🎵 Узнано: ${match.detectedTrackAndGenre}\n⚠️ Выбери свою мелодию кнопкой «📂 Своя музыка»."
                            }
                        } catch (e: Exception) {
                            shazamStatusLabel.text = "❌ Ошибка AI-Shazam: ${e.message}"
                        }
                    }
                }
            }
            addView(aiShazamBtn, buttonLayoutParams())

            val shazamRow = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                weightSum = 3f
                setPadding(0, dp(6), 0, 0)
            }
            shazamRow.addView(createCompactButton("🔄 Другая", "#F4F4F5", "#27272A").apply {
                setOnClickListener {
                    val match = lastShazamResult
                    if (match == null || match.candidateTracks.isEmpty()) return@setOnClickListener
                    currentShazamCandidateIndex = (currentShazamCandidateIndex + 1) % match.candidateTracks.size
                    val (url, title) = match.candidateTracks[currentShazamCandidateIndex]
                    lifecycleScope.launch {
                        try {
                            val bgmFile = subtitleTransformer.downloadReplacementMusicCandidate(url, title)
                            currentReplacementMusicFile = bgmFile
                            toggleReplaceMusicChk.isChecked = true
                            shazamStatusLabel.text = "✅ Мелодия [${currentShazamCandidateIndex + 1}/${match.candidateTracks.size}]: «$title»"
                            playStemAudioPreview(bgmFile, title)
                        } catch (e: Exception) {
                            shazamStatusLabel.text = "❌ Ошибка скачивания варианта: ${e.message}"
                        }
                    }
                }
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(4) })
            shazamRow.addView(createCompactButton("▶️ Слушать", "#09090B", "#38BDF8").apply {
                setOnClickListener {
                    val bgm = currentReplacementMusicFile
                    if (bgm != null && bgm.exists()) playStemAudioPreview(bgm, bgm.name)
                }
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(4) })
            shazamRow.addView(createCompactButton("📂 Своя музыка", "#E4E4E7", "#27272A").apply {
                setOnClickListener { importReplacementMusicLauncher.launch("audio/*") }
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(shazamRow)

            toggleReplaceMusicChk = createCheckBox("🎵 Включить подмену музыки на выбранную мелодию", false)
            addView(toggleReplaceMusicChk)

            shazamStatusLabel = TextView(this@MainActivity).apply {
                text = "🎵 Подмена музыки выключена."
                setTextColor(Color.parseColor("#A7F3D0"))
                textSize = 11f
            }
            addView(shazamStatusLabel)
        }
    }

    // -------------------------------------------------------------------------
    // ПАНЕЛЬ 4: 💬 ТОЧНЫЕ СУБТИТРЫ (С СИНХРОНОМ ПОД КУСОК) И ОЗВУЧКА
    // -------------------------------------------------------------------------
    private fun buildToolPanelSubtitlesAndDubbing(): LinearLayout {
        return createToolCardContainer().apply {
            addView(panelTitle("💬 КАРАОКЕ-СУБТИТРЫ (ТОЧНЫЙ СИНХРОН) + ПЕРЕОЗВУЧКА"))

            chkSubtitles = createCheckBox("💬 Генерировать свои караоке-субтитры (srv3 миллисекунды / VTT / Gemini STT)", false)
            addView(chkSubtitles)

            val subConfigRow = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                weightSum = 2f
            }
            subZoneBtn = createCompactButton("📍 Зона: ${currentSubZone.label}", "#E4E4E7", "#27272A").apply {
                setOnClickListener {
                    val zones = listOf(
                        SubtitlePlacementZone.ADAPTIVE_AUTO,
                        SubtitlePlacementZone.CENTER_SEAM,
                        SubtitlePlacementZone.TOP_SAFE,
                        SubtitlePlacementZone.BOTTOM_SAFE
                    )
                    val idx = zones.indexOf(currentSubZone).coerceAtLeast(0)
                    currentSubZone = zones[(idx + 1) % zones.size]
                    text = "📍 Зона: ${currentSubZone.label}"
                }
            }
            subFontSizeBtn = createCompactButton("🔤 Шрифт: 68px", "#E4E4E7", "#27272A").apply {
                setOnClickListener {
                    currentSubFontSizePx = when (currentSubFontSizePx) {
                        56 -> 68
                        68 -> 80
                        else -> 56
                    }
                    text = "🔤 Шрифт: ${currentSubFontSizePx}px"
                }
            }
            subConfigRow.addView(subZoneBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.25f).apply { rightMargin = dp(6) })
            subConfigRow.addView(subFontSizeBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.75f))
            addView(subConfigRow)

            subSyncOffsetLabel = fieldLabel("⏱ Ручная микро-подстройка синхрона субтитров: 0 мс (автоматически привязано к началу куска)")
            addView(subSyncOffsetLabel)

            val syncRow = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                weightSum = 3f
            }
            syncRow.addView(createCompactButton("⏪ Раньше (-200мс)", "#E4E4E7", "#27272A").apply {
                setOnClickListener {
                    currentSubSyncOffsetMs = (currentSubSyncOffsetMs - 200L).coerceIn(-3000L, 3000L)
                    subSyncOffsetLabel.text = "⏱ Микро-сдвиг субтитров: ${currentSubSyncOffsetMs} мс"
                }
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.1f).apply { rightMargin = dp(4) })
            syncRow.addView(createCompactButton("Сброс 0мс", "#09090B", "#FBBF24").apply {
                setOnClickListener {
                    currentSubSyncOffsetMs = 0L
                    subSyncOffsetLabel.text = "⏱ Микро-сдвиг субтитров: 0 мс"
                }
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.8f).apply { rightMargin = dp(4) })
            syncRow.addView(createCompactButton("Позже (+200мс) ⏩", "#E4E4E7", "#27272A").apply {
                setOnClickListener {
                    currentSubSyncOffsetMs = (currentSubSyncOffsetMs + 200L).coerceIn(-3000L, 3000L)
                    subSyncOffsetLabel.text = "⏱ Микро-сдвиг субтитров: +${currentSubSyncOffsetMs} мс".replace("+-", "-")
                }
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.1f))
            addView(syncRow)

            // Dubbing / Voiceover
            voiceoverDubModeBtn = createStyledButton(
                "🎭 Режим переозвучки: ${currentVoiceoverDubMode.label}",
                "#09090B",
                "#A855F7"
            ).apply {
                setTypeface(typeface, Typeface.BOLD)
                setOnClickListener {
                    val modes = VoiceoverDubMode.entries
                    currentVoiceoverDubMode = modes[(currentVoiceoverDubMode.ordinal + 1) % modes.size]
                    text = "🎭 Режим переозвучки: ${currentVoiceoverDubMode.label}"
                }
            }
            addView(voiceoverDubModeBtn, buttonLayoutParams())

            ttsVoiceProfileBtn = createStyledButton(
                "🗣 Голос диктора TTS: ${currentTtsVoiceProfile.label}",
                "#E4E4E7",
                "#27272A"
            ).apply {
                setOnClickListener {
                    val voices = TtsVoiceProfile.entries
                    currentTtsVoiceProfile = voices[(currentTtsVoiceProfile.ordinal + 1) % voices.size]
                    text = "🗣 Голос диктора TTS: ${currentTtsVoiceProfile.label}"
                }
            }
            addView(ttsVoiceProfileBtn, buttonLayoutParams())

            addView(fieldLabel("Текст для новой озвучки / дубляжа:"))
            dubScriptInput = EditText(this@MainActivity).apply {
                hint = "Нажми «✍️ ИИ-Рерайт/Перевод» или введи свой текст..."
                setHintTextColor(Color.parseColor("#52525B"))
                setTextColor(Color.parseColor("#F4F4F5"))
                setBackgroundColor(Color.parseColor("#18181B"))
                textSize = 13f
                minLines = 3
                maxLines = 6
                val p = dp(10)
                setPadding(p, p, p, p)
            }
            addView(dubScriptInput)

            val dubActionsRow = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                weightSum = 3f
                setPadding(0, dp(6), 0, 0)
            }
            dubActionsRow.addView(createCompactButton("✍️ ИИ-Перевод", "#09090B", "#FBBF24").apply {
                setOnClickListener {
                    val cur = selectedInboxItem ?: return@setOnClickListener
                    val srcFile = clutterFilter.getInboxVideoFile(cur)
                    val isolatedVoc = currentSeparatedStems?.vocalsOverlayFile?.takeIf { it.exists() }
                    voiceoverStatusLabel.text = "⏳ Извлекаю речь куска и готовлю перевод/рерайт..."
                    lifecycleScope.launch {
                        val script = subtitleTransformer.generateDubbingScriptFromVideo(
                            sourceUrl = cur.sourceUrl,
                            localVideoFile = srcFile,
                            startSec = currentTrimStartSec,
                            endSec = currentTrimEndSec,
                            isolatedVocalStemFile = isolatedVoc
                        )
                        dubScriptInput.setText(script)
                        voiceoverStatusLabel.text = "✅ Текст готов! Отредактируй при необходимости и жми «🗣 Озвучить»."
                    }
                }
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.1f).apply { rightMargin = dp(4) })
            dubActionsRow.addView(createCompactButton("🗣 Озвучить TTS", "#09090B", "#10B981").apply {
                setOnClickListener {
                    val txt = dubScriptInput.text.toString().trim()
                    if (txt.isBlank()) {
                        Toast.makeText(this@MainActivity, "Введи текст для озвучки", Toast.LENGTH_SHORT).show()
                        return@setOnClickListener
                    }
                    voiceoverStatusLabel.text = "⏳ Синтезирую голос (${currentTtsVoiceProfile.label})..."
                    lifecycleScope.launch {
                        try {
                            val voFile = subtitleTransformer.synthesizeVoiceoverToAudioFile(txt, currentTtsVoiceProfile)
                            currentCustomVoiceoverFile = voFile
                            if (currentVoiceoverDubMode != VoiceoverDubMode.DUB_VOICEOVER_15PCT_BG &&
                                currentVoiceoverDubMode != VoiceoverDubMode.DUB_REPLACE_100PCT
                            ) {
                                currentVoiceoverDubMode = VoiceoverDubMode.DUB_VOICEOVER_15PCT_BG
                                voiceoverDubModeBtn.text = "🎭 Режим переозвучки: ${currentVoiceoverDubMode.label}"
                            }
                            voiceoverStatusLabel.text = "✅ Озвучка готова: ${voFile.name} (${voFile.length() / 1024} KB)"
                        } catch (e: Exception) {
                            voiceoverStatusLabel.text = "❌ Ошибка синтеза: ${e.message}"
                        }
                    }
                }
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.1f).apply { rightMargin = dp(4) })
            dubActionsRow.addView(createCompactButton("📂 Свой звук", "#F4F4F5", "#27272A").apply {
                setOnClickListener { importVoiceoverAudioLauncher.launch("audio/*") }
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.8f))
            addView(dubActionsRow)

            voiceoverStatusLabel = TextView(this@MainActivity).apply {
                text = "🎙 Используется родной голос."
                setTextColor(Color.parseColor("#A7F3D0"))
                textSize = 11f
                setPadding(0, dp(4), 0, 0)
            }
            addView(voiceoverStatusLabel)
        }
    }

    // -------------------------------------------------------------------------
    // ПАНЕЛЬ 5: 🛡 УНИКАЛИЗАЦИЯ / АНТИ-ДЕТЕКТ (БЕЗ РАССИНХРОНА САБОВ)
    // -------------------------------------------------------------------------
    private fun buildToolPanelAntiDetect(): LinearLayout {
        return createToolCardContainer().apply {
            addView(panelTitle("🛡 УНИКАЛИЗАЦИЯ КАДРА И ЗВУКА (100% СИНХРОН ДЛИТЕЛЬНОСТИ)"))

            chkVideoZoom = createCheckBox("🛡 Видео: Микро-зум 1.04x и сдвиг пиксельной сетки", true)
            chkVideoColorNoise = createCheckBox("🛡 Видео: Сдвиг гаммы/контраста + микро-зерно + очистка метаданных", true)
            chkVideoFlip = createCheckBox("🪞 Видео: Отзеркалить кадр по горизонтали (Flip)", false)
            chkAudioPitchTempo = createCheckBox("🔊 Аудио: Сдвиг тембра голоса (+2.2% Pitch с компенсацией темпа 1.000x — без рассинхрона!)", true)
            chkAudioEqStereo = createCheckBox("🔊 Аудио: Сдвиг АЧХ голоса (EQ 3.1kHz + Стерео-расширение)", true)
            chkAudioTrimSilence = createCheckBox("✂️ Аудио: Вырезать мёртвые паузы (выкл по умолчанию, чтобы не сбивать таймкоды)", false)

            addView(chkVideoZoom)
            addView(chkVideoColorNoise)
            addView(chkVideoFlip)
            addView(chkAudioPitchTempo)
            addView(chkAudioEqStereo)
            addView(chkAudioTrimSilence)
        }
    }

    // =========================================================================
    // ЛОГИКА ЖИВОГО ДВУХСЛОЙНОГО ХОЛСТА И СИНХРОННОГО ПРЕДПРОСМОТРА
    // =========================================================================
    private fun bindSelectedSourceToStudio(item: StudioInboxItem) {
        selectedInboxItem = item
        val file = clutterFilter.getInboxVideoFile(item)
        val sizeMb = String.format(Locale.US, "%.1f", item.sizeKb / 1024.0)
        studioSelectedSourceLabel.text = "🎬 «${item.title.take(46)}» (${String.format(Locale.US, "%.1fс", item.durationSec)} • $sizeMb МБ)"

        currentSourceDurationSec = item.durationSec.coerceAtLeast(2.0)
        val startSec = if (item.recommendedEndSec > item.recommendedStartSec + 1.0) item.recommendedStartSec else 0.0
        val endSec = if (item.recommendedEndSec > item.recommendedStartSec + 1.0) item.recommendedEndSec else currentSourceDurationSec

        isShowingRenderedPreviewFile = false
        if (::returnToLiveCompositeBtn.isInitialized) {
            returnToLiveCompositeBtn.visibility = View.GONE
        }

        applyNewTrimBounds(startSec, endSec)
        updatePoolSelectorButtonText()

        if (file.exists()) {
            val (natW, natH) = hardwareEngine.probeVideoDimensions(file)
            sourceVideoNaturalWidth = natW
            sourceVideoNaturalHeight = natH
            loadSourceIntoLivePlayer(file, seekSec = startSec, autoPlay = true)
        }
        reloadLiveBackgroundPlayer()
        updateStageLayerLayout()
    }

    private fun loadSourceIntoLivePlayer(file: File, seekSec: Double = 0.0, autoPlay: Boolean = true) {
        try {
            topSourceFileLoaded = file
            topSourcePlayer?.release()
            val mp = MediaPlayer()
            topSourcePlayer = mp
            mp.setDataSource(file.absolutePath)
            topSurface?.let { mp.setSurface(it) }
            mp.isLooping = true
            val vol = currentMainVolume.coerceIn(0f, 1f)
            mp.setVolume(vol, vol)
            mp.setOnVideoSizeChangedListener { _, w, h ->
                if (w > 0 && h > 0) {
                    sourceVideoNaturalWidth = w
                    sourceVideoNaturalHeight = h
                    applyLiveVideoTransforms()
                }
            }
            mp.setOnPreparedListener { prepared ->
                val durSec = (prepared.duration / 1000.0).takeIf { it > 0.5 } ?: currentSourceDurationSec
                if (!isShowingRenderedPreviewFile) {
                    currentSourceDurationSec = durSec
                    if (currentTrimEndSec <= 0.5 || currentTrimEndSec > durSec + 0.5) {
                        applyNewTrimBounds(currentTrimStartSec.coerceAtMost(durSec - 1.0), durSec)
                    }
                }
                applyLivePlaybackSpeed()
                val seekMs = (seekSec * 1000.0).roundToInt().coerceAtLeast(0)
                if (seekMs > 0) {
                    seekMediaPlayerExact(prepared, seekMs)
                }
                if (autoPlay && tabStudioContainer.visibility == View.VISIBLE) {
                    prepared.start()
                    bottomBgPlayer?.let { bg -> if (!bg.isPlaying) runCatching { bg.start() } }
                    updatePlayPauseButtonUi(true)
                } else {
                    updatePlayPauseButtonUi(false)
                }
                applyLiveVideoTransforms()
            }
            mp.prepareAsync()
        } catch (e: Exception) {
            DiagnosticLogger.logError("STAGE-TOP", "Ошибка загрузки видео в верхний слой: ${file.name}", e)
        }
    }

    private fun reloadLiveBackgroundPlayer() {
        val clips = clutterFilter.listVerifiedPoolClips()
        if (clips.isEmpty()) {
            bottomBgPlayer?.release()
            bottomBgPlayer = null
            bottomBgFileLoaded = null
            return
        }
        val curItem = selectedInboxItem
        val sourceTags = if (curItem != null) {
            clutterFilter.extractSemanticTopicTags("${curItem.title} ${curItem.aiSummary}", curItem.sourceTags)
        } else {
            emptyList()
        }
        val chosenBg = if (selectedPoolClipName != "AUTO") {
            clips.firstOrNull { it.name == selectedPoolClipName } ?: clips.first()
        } else {
            clutterFilter.findMatchingPoolClipsByTags(sourceTags).firstOrNull()?.first ?: clips.first()
        }

        if (bottomBgFileLoaded?.absolutePath == chosenBg.absolutePath && bottomBgPlayer != null) {
            updateStageLayerLayout()
            return
        }

        try {
            bottomBgFileLoaded = chosenBg
            bottomBgPlayer?.release()
            val mp = MediaPlayer()
            bottomBgPlayer = mp
            mp.setDataSource(chosenBg.absolutePath)
            bottomSurface?.let { mp.setSurface(it) }
            mp.isLooping = true
            val vol = currentBgVolume.coerceIn(0f, 1f)
            mp.setVolume(vol, vol)
            mp.setOnVideoSizeChangedListener { _, w, h ->
                if (w > 0 && h > 0) {
                    bgVideoNaturalWidth = w
                    bgVideoNaturalHeight = h
                    applyLiveVideoTransforms()
                }
            }
            mp.setOnPreparedListener { prepared ->
                if (topSourcePlayer?.isPlaying == true && tabStudioContainer.visibility == View.VISIBLE) {
                    prepared.start()
                }
                applyLiveVideoTransforms()
            }
            mp.prepareAsync()
        } catch (e: Exception) {
            DiagnosticLogger.logError("STAGE-BG", "Ошибка загрузки фоновой залипалки: ${chosenBg.name}", e)
        }
    }

    private fun updateStageLayerLayout() {
        if (!::stageOuterFrame.isInitialized) return
        val stageW = dp(252)
        val stageH = dp(448)

        if (isShowingRenderedPreviewFile) {
            bottomBgTexture.visibility = View.GONE
            topVideoTexture.visibility = View.VISIBLE
            topVideoTexture.layoutParams = FrameLayout.LayoutParams(stageW, stageH, Gravity.CENTER)
            canvasOverlayView.isSplitLayoutActive = false
            applyLiveVideoTransforms()
            return
        }

        when (currentLayoutLayer) {
            VideoLayoutLayer.SPLIT_VSTACK -> {
                val topH = (stageH * currentSplitTopRatio).roundToInt().coerceIn(dp(110), stageH - dp(110))
                val botH = (stageH - topH).coerceAtLeast(dp(80))
                topVideoTexture.visibility = View.VISIBLE
                bottomBgTexture.visibility = View.VISIBLE
                topVideoTexture.layoutParams = FrameLayout.LayoutParams(stageW, topH, Gravity.TOP)
                bottomBgTexture.layoutParams = FrameLayout.LayoutParams(stageW, botH, Gravity.BOTTOM)
                canvasOverlayView.isSplitLayoutActive = true
                canvasOverlayView.splitTopRatio = currentSplitTopRatio
            }

            VideoLayoutLayer.BRAINROT_VOICEOVER -> {
                topVideoTexture.visibility = View.INVISIBLE
                bottomBgTexture.visibility = View.VISIBLE
                bottomBgTexture.layoutParams = FrameLayout.LayoutParams(stageW, stageH, Gravity.CENTER)
                canvasOverlayView.isSplitLayoutActive = false
            }

            else -> {
                topVideoTexture.visibility = View.VISIBLE
                bottomBgTexture.visibility = View.GONE
                topVideoTexture.layoutParams = FrameLayout.LayoutParams(stageW, stageH, Gravity.CENTER)
                canvasOverlayView.isSplitLayoutActive = false
            }
        }
        stageOuterFrame.post { applyLiveVideoTransforms() }
    }

    /**
     * Применяет живую GPU-матрицу кадрирования к верхнему и нижнему TextureView:
     * - В режиме редактирования рамки кропа (FREEFORM_CROP) вписывает весь исходный кадр целиком,
     *   чтобы пользователь видел отрезаемые края по горизонтали и вертикали.
     * - В режиме обычного просмотра мгновенно показывает обрезанный/зумированный результат вживую!
     */
    private fun applyLiveVideoTransforms() {
        if (!::topVideoTexture.isInitialized || !::bottomBgTexture.isInitialized) return

        val topViewW = topVideoTexture.width.toFloat()
        val topViewH = topVideoTexture.height.toFloat()
        if (topViewW > 10f && topViewH > 10f && sourceVideoNaturalWidth > 0 && sourceVideoNaturalHeight > 0) {
            val srcW = sourceVideoNaturalWidth.toFloat()
            val srcH = sourceVideoNaturalHeight.toFloat()

            if (canvasOverlayView.touchMode == CanvasTouchMode.FREEFORM_CROP && !isShowingRenderedPreviewFile) {
                // Fit entire source frame inside topView so user can drag horizontal & vertical edges freely
                val fitScale = min(topViewW / srcW, topViewH / srcH)
                val drawnW = srcW * fitScale
                val drawnH = srcH * fitScale
                val dx = (topViewW - drawnW) / 2f
                val dy = (topViewH - drawnH) / 2f

                val matrix = Matrix()
                matrix.setScale(drawnW / topViewW, drawnH / topViewH)
                matrix.postTranslate(dx, dy)
                topVideoTexture.setTransform(matrix)

                canvasOverlayView.sourceVideoFitRect = RectF(
                    topVideoTexture.left + dx,
                    topVideoTexture.top + dy,
                    topVideoTexture.left + dx + drawnW,
                    topVideoTexture.top + dy + drawnH
                )
            } else {
                canvasOverlayView.sourceVideoFitRect = null
                val crop = if (!isShowingRenderedPreviewFile && currentManualCrop.isNonTrivial) {
                    currentManualCrop
                } else {
                    ManualCropRect(enabled = false, leftNorm = 0f, topNorm = 0f, rightNorm = 1f, bottomNorm = 1f)
                }

                val subW = (crop.rightNorm - crop.leftNorm).coerceIn(0.15f, 1f) * srcW
                val subH = (crop.bottomNorm - crop.topNorm).coerceIn(0.15f, 1f) * srcH

                val isFitMode = isShowingRenderedPreviewFile ||
                    currentLayoutLayer == VideoLayoutLayer.CLEAN_ORIGINAL_CROP ||
                    currentLayoutLayer == VideoLayoutLayer.DISTORTED_ORIGINAL ||
                    currentLayoutLayer == VideoLayoutLayer.BLURRED_BG_FRAME

                val scale = if (isFitMode) {
                    min(topViewW / subW, topViewH / subH)
                } else {
                    maxOf(topViewW / subW, topViewH / subH)
                }

                val totalFullW = srcW * scale
                val totalFullH = srcH * scale
                val cropCenterX = (crop.leftNorm + crop.rightNorm) * 0.5f * totalFullW
                val cropCenterY = (crop.topNorm + crop.bottomNorm) * 0.5f * totalFullH

                val matrix = Matrix()
                matrix.setScale(totalFullW / topViewW, totalFullH / topViewH)
                matrix.postTranslate(topViewW * 0.5f - cropCenterX, topViewH * 0.5f - cropCenterY)
                topVideoTexture.setTransform(matrix)
            }
        }

        // Center-crop transform for bottom background clip
        val bgViewW = bottomBgTexture.width.toFloat()
        val bgViewH = bottomBgTexture.height.toFloat()
        if (bgViewW > 10f && bgViewH > 10f && bgVideoNaturalWidth > 0 && bgVideoNaturalHeight > 0) {
            val bw = bgVideoNaturalWidth.toFloat()
            val bh = bgVideoNaturalHeight.toFloat()
            val scale = maxOf(bgViewW / bw, bgViewH / bh)
            val scaledW = bw * scale
            val scaledH = bh * scale
            val m = Matrix()
            m.setScale(scaledW / bgViewW, scaledH / bgViewH)
            m.postTranslate((bgViewW - scaledW) * 0.5f, (bgViewH - scaledH) * 0.5f)
            bottomBgTexture.setTransform(m)
        }
    }

    private fun setCanvasTouchModeAndRefresh(mode: CanvasTouchMode) {
        canvasOverlayView.touchMode = mode
        studioCanvasModeBadge.text = "👁 РЕЖИМ ХОЛСТА: ${mode.label}"
        if (::toggleFreeformCropBtn.isInitialized) {
            toggleFreeformCropBtn.text = if (mode == CanvasTouchMode.FREEFORM_CROP) {
                "✅ Применить Кроп на холст"
            } else {
                "✋ Двигать Кроп пальцем (Ш×В)"
            }
        }
        if (::toggleSplitDragBtn.isInitialized) {
            toggleSplitDragBtn.text = if (mode == CanvasTouchMode.SPLIT_SEAM) {
                "✅ Зафиксировать шов"
            } else {
                "⇅ Двигать шов Сплита"
            }
        }
        if (::toggleWatermarkTouchBtn.isInitialized) {
            toggleWatermarkTouchBtn.text = if (mode == CanvasTouchMode.WATERMARK_ERASER) {
                "✅ Готово"
            } else {
                "✋ Двигать"
            }
        }
        applyLiveVideoTransforms()
    }

    private fun applyAspectPresetCrop(targetAspectWH: Float) {
        val srcW = sourceVideoNaturalWidth.coerceAtLeast(1).toFloat()
        val srcH = sourceVideoNaturalHeight.coerceAtLeast(1).toFloat()
        val srcAspect = srcW / srcH
        val (wNorm, hNorm) = if (srcAspect > targetAspectWH) {
            (targetAspectWH / srcAspect).coerceIn(0.2f, 1f) to 1f
        } else {
            1f to (srcAspect / targetAspectWH).coerceIn(0.2f, 1f)
        }
        val left = (1f - wNorm) * 0.5f
        val top = (1f - hNorm) * 0.5f
        currentManualCrop = ManualCropRect(
            enabled = true,
            leftNorm = left,
            topNorm = top,
            rightNorm = left + wNorm,
            bottomNorm = top + hNorm
        )
        canvasOverlayView.manualCrop = currentManualCrop
        setCanvasTouchModeAndRefresh(CanvasTouchMode.FREEFORM_CROP)
        updateCropSummaryText()
    }

    private fun updateCropSummaryText() {
        if (!::cropSummaryLabel.isInitialized) return
        if (!currentManualCrop.isNonTrivial) {
            cropSummaryLabel.text = "📐 Свободный кроп: Выкл (весь кадр / авто-центровка)."
        } else {
            val wPct = ((currentManualCrop.rightNorm - currentManualCrop.leftNorm) * 100f).roundToInt()
            val hPct = ((currentManualCrop.bottomNorm - currentManualCrop.topNorm) * 100f).roundToInt()
            cropSummaryLabel.text = String.format(
                Locale.US,
                "✅ Свободный кроп активен: Ширина %d%% × Высота %d%% (X: %.0f%%..%.0f%%, Y: %.0f%%..%.0f%%)",
                wPct, hPct,
                currentManualCrop.leftNorm * 100f, currentManualCrop.rightNorm * 100f,
                currentManualCrop.topNorm * 100f, currentManualCrop.bottomNorm * 100f
            )
        }
    }

    private fun addWatermarkBoxPreset(box: WatermarkMaskBox) {
        if (currentWatermarkBoxes.size >= 4) {
            Toast.makeText(this, "Максимум 4 рамки одновременно", Toast.LENGTH_SHORT).show()
        } else {
            currentWatermarkBoxes.add(box)
        }
        canvasOverlayView.setWatermarkList(currentWatermarkBoxes, currentWatermarkBoxes.lastIndex)
        setCanvasTouchModeAndRefresh(CanvasTouchMode.WATERMARK_ERASER)
        updateWatermarkStatusText(currentWatermarkBoxes.lastIndex)
    }

    private fun updateWatermarkStatusText(selIdx: Int) {
        if (!::watermarkStatusLabel.isInitialized) return
        if (currentWatermarkBoxes.isEmpty()) {
            watermarkStatusLabel.text = "🧹 Активных рамок удаления: 0 шт."
        } else {
            val activeInfo = if (selIdx in currentWatermarkBoxes.indices) {
                val b = currentWatermarkBoxes[selIdx]
                String.format(Locale.US, "Выбрана #%d (X=%.0f%%, Y=%.0f%%, Ш=%.0f%%, В=%.0f%%)", selIdx + 1, b.xNorm * 100f, b.yNorm * 100f, b.wNorm * 100f, b.hNorm * 100f)
            } else {
                ""
            }
            watermarkStatusLabel.text = "🧹 Рамок на холсте: ${currentWatermarkBoxes.size} шт. $activeInfo"
        }
    }

    private fun toggleLiveStagePlayPause() {
        val mp = topSourcePlayer ?: return
        if (mp.isPlaying) {
            pauseLiveStagePlayers()
        } else {
            stopStemAudioPreview()
            runCatching { mp.start() }
            if (!isShowingRenderedPreviewFile) {
                bottomBgPlayer?.let { bg -> runCatching { bg.start() } }
            }
            updatePlayPauseButtonUi(true)
        }
    }

    private fun pauseLiveStagePlayers() {
        runCatching { if (topSourcePlayer?.isPlaying == true) topSourcePlayer?.pause() }
        runCatching { if (bottomBgPlayer?.isPlaying == true) bottomBgPlayer?.pause() }
        updatePlayPauseButtonUi(false)
    }

    private fun releaseLiveStagePlayers() {
        runCatching { topSourcePlayer?.release() }
        runCatching { bottomBgPlayer?.release() }
        topSourcePlayer = null
        bottomBgPlayer = null
    }

    private fun updatePlayPauseButtonUi(isPlaying: Boolean) {
        if (!::playPauseTransportBtn.isInitialized) return
        playPauseTransportBtn.text = if (isPlaying) "⏸ Пауза" else "▶️ Играть"
    }

    private fun applyLivePlaybackSpeed() {
        val mp = topSourcePlayer ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                val wasPlaying = mp.isPlaying
                mp.playbackParams = PlaybackParams().setSpeed(currentPlaybackSpeed.coerceIn(0.5f, 2.0f))
                if (!wasPlaying) {
                    mp.pause()
                }
            } catch (_: Exception) {
            }
        }
    }

    private fun seekMediaPlayerExact(mp: MediaPlayer, targetMs: Int) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                mp.seekTo(targetMs.toLong(), MediaPlayer.SEEK_CLOSEST)
            } else {
                mp.seekTo(targetMs)
            }
        } catch (_: Exception) {
        }
    }

    private fun seekLiveSourceToSec(sec: Double) {
        val mp = topSourcePlayer ?: return
        val ms = (sec.coerceIn(0.0, currentSourceDurationSec) * 1000.0).roundToInt()
        seekMediaPlayerExact(mp, ms)
    }

    private fun applyNewTrimBounds(newStartSec: Double, newEndSec: Double, seekToEdge: Double? = null) {
        val maxDur = currentSourceDurationSec.coerceAtLeast(2.0)
        val clampedStart = newStartSec.coerceIn(0.0, (maxDur - 1.0).coerceAtLeast(0.0))
        val clampedEnd = newEndSec.coerceIn(clampedStart + 1.0, maxDur)

        currentTrimStartSec = clampedStart
        currentTrimEndSec = clampedEnd

        isUpdatingTrimUiProgrammatically = true
        if (::trimStartInput.isInitialized) {
            val sTxt = String.format(Locale.US, "%.1f", clampedStart)
            if (trimStartInput.text.toString() != sTxt) trimStartInput.setText(sTxt)
        }
        if (::trimEndInput.isInitialized) {
            val eTxt = String.format(Locale.US, "%.1f", clampedEnd)
            if (trimEndInput.text.toString() != eTxt) trimEndInput.setText(eTxt)
        }
        syncTrimSeekBarsOnly()
        isUpdatingTrimUiProgrammatically = false

        if (seekToEdge != null) {
            seekLiveSourceToSec(seekToEdge)
            updateTimelineReadout(seekToEdge)
        } else {
            updateTimelineReadout(clampedStart)
        }
    }

    private fun syncTrimSeekBarsOnly() {
        val maxDur = currentSourceDurationSec.coerceAtLeast(1.0)
        if (::trimStartSeekBar.isInitialized) {
            trimStartSeekBar.progress = ((currentTrimStartSec / maxDur) * 1000.0).roundToInt().coerceIn(0, 1000)
        }
        if (::trimEndSeekBar.isInitialized) {
            trimEndSeekBar.progress = ((currentTrimEndSec / maxDur) * 1000.0).roundToInt().coerceIn(0, 1000)
        }
    }

    private fun updateTimelineReadout(playheadSec: Double) {
        if (!::timelineTimecodeLabel.isInitialized) return
        val span = (currentTrimEndSec - currentTrimStartSec).coerceAtLeast(0.0)
        timelineTimecodeLabel.text = String.format(
            Locale.US,
            "▶ %05.1fс  •  Кусок: %.1fс – %.1fс [%.1fс]",
            playheadSec,
            currentTrimStartSec,
            currentTrimEndSec,
            span
        )
    }

    private fun startLivePlayheadTicker() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (isActive) {
                    val mp = topSourcePlayer
                    if (mp != null && tabStudioContainer.visibility == View.VISIBLE) {
                        try {
                            val posSec = mp.currentPosition / 1000.0
                            if (!isShowingRenderedPreviewFile && mp.isPlaying) {
                                // Strictly loop within user's chosen [currentTrimStartSec .. currentTrimEndSec]
                                if (posSec >= currentTrimEndSec - 0.05 || posSec < currentTrimStartSec - 0.35) {
                                    seekMediaPlayerExact(mp, (currentTrimStartSec * 1000.0).roundToInt())
                                }
                            }
                            if (!isUserDraggingPlayhead && currentSourceDurationSec > 0.5) {
                                val permille = ((posSec / currentSourceDurationSec) * 1000.0).roundToInt().coerceIn(0, 1000)
                                playheadSeekBar.progress = permille
                                updateTimelineReadout(posSec)
                            }
                        } catch (_: Exception) {
                        }
                    }
                    delay(110L)
                }
            }
        }
    }

    private fun cyclePoolClipSelection() {
        val clips = clutterFilter.listVerifiedPoolClips()
        if (clips.isEmpty()) {
            selectedPoolClipName = "AUTO"
            updatePoolSelectorButtonText()
            Toast.makeText(this, "Пул фонов пока пуст — перейди во вкладке «🗂 Фоны»", Toast.LENGTH_SHORT).show()
            return
        }
        val options = listOf("AUTO") + clips.map { it.name }
        val curIdx = options.indexOf(selectedPoolClipName).coerceAtLeast(0)
        selectedPoolClipName = options[(curIdx + 1) % options.size]
        updatePoolSelectorButtonText()
        reloadLiveBackgroundPlayer()
    }

    private fun updatePoolSelectorButtonText() {
        if (!::poolClipSelectBtn.isInitialized) return
        val clips = clutterFilter.listVerifiedPoolClips()
        val curItem = selectedInboxItem
        val sourceTags = if (curItem != null) {
            clutterFilter.extractSemanticTopicTags("${curItem.title} ${curItem.aiSummary}", curItem.sourceTags)
        } else {
            emptyList()
        }

        if (selectedPoolClipName == "AUTO" || clips.none { it.name == selectedPoolClipName }) {
            selectedPoolClipName = "AUTO"
            if (sourceTags.isNotEmpty()) {
                val matched = clutterFilter.findMatchingPoolClipsByTags(sourceTags)
                poolClipSelectBtn.text = if (matched.isNotEmpty()) {
                    "🎬 Фон внизу: Авто по тегу ${sourceTags.take(2).joinToString(" ")} (${matched.size} шт.)"
                } else {
                    "🎬 Фон внизу: Авто-микс (${clips.size} шт. в пуле)"
                }
            } else {
                poolClipSelectBtn.text = "🎬 Фон внизу: Авто-микс (${clips.size} шт. в пуле)"
            }
        } else {
            val chosenFile = clips.first { it.name == selectedPoolClipName }
            val meta = clutterFilter.getPoolClipMetadata(chosenFile)
            val idx = clips.indexOfFirst { it.name == selectedPoolClipName } + 1
            poolClipSelectBtn.text = "🎬 Фон #$idx: ${meta.tags.take(2).joinToString(" ")} ($selectedPoolClipName)"
        }
    }

    private fun playStemAudioPreview(audioFile: File, label: String) {
        try {
            stopStemAudioPreview()
            pauseLiveStagePlayers()
            stemPreviewPlayer = MediaPlayer().apply {
                setDataSource(audioFile.absolutePath)
                prepare()
                start()
            }
            stemStatusLabel.text = "🔊 Сейчас играет дорожка: $label"
        } catch (e: Exception) {
            Toast.makeText(this, "Ошибка прослушивания: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun stopStemAudioPreview() {
        try {
            stemPreviewPlayer?.stop()
            stemPreviewPlayer?.release()
        } catch (_: Exception) {
        }
        stemPreviewPlayer = null
    }

    private fun buildCurrentStudioRenderConfig(forceCleanYouCutExport: Boolean): ModularRenderConfig {
        val curItem = selectedInboxItem
        val preferredTags = if (curItem != null) {
            clutterFilter.extractSemanticTopicTags("${curItem.title} ${curItem.aiSummary}", curItem.sourceTags)
        } else {
            emptyList()
        }

        val effectiveLayout = if (forceCleanYouCutExport) {
            VideoLayoutLayer.CLEAN_ORIGINAL_CROP
        } else {
            currentLayoutLayer
        }

        val dsp = if (forceCleanYouCutExport) {
            AntiDetectDspLayerConfig(
                enabled = currentMusicRemovalMode != MusicRemovalMode.OFF ||
                    currentVoiceoverDubMode != VoiceoverDubMode.OFF ||
                    currentStemRoutingMode != AudioStemRoutingMode.FULL_ORIGINAL_MIX,
                enableMicroZoom = false,
                enableHorizontalFlip = chkVideoFlip.isChecked,
                enableVideoNoiseInjection = false,
                enableMicroColorEqShift = false,
                enableAudioPitchTempoMicroJitter = false,
                enableAudioEqVoicePresence = false,
                enableSilenceTrimming350ms = false,
                musicRemovalMode = currentMusicRemovalMode,
                voiceoverDubMode = currentVoiceoverDubMode,
                stemRoutingMode = currentStemRoutingMode
            )
        } else {
            AntiDetectDspLayerConfig(
                enabled = chkVideoZoom.isChecked || chkVideoColorNoise.isChecked || chkVideoFlip.isChecked ||
                    chkAudioPitchTempo.isChecked || chkAudioEqStereo.isChecked || chkAudioTrimSilence.isChecked ||
                    currentMusicRemovalMode != MusicRemovalMode.OFF ||
                    currentVoiceoverDubMode != VoiceoverDubMode.OFF ||
                    currentStemRoutingMode != AudioStemRoutingMode.FULL_ORIGINAL_MIX,
                enableMicroZoom = chkVideoZoom.isChecked,
                enableHorizontalFlip = chkVideoFlip.isChecked,
                enableVideoNoiseInjection = chkVideoColorNoise.isChecked,
                enableMicroColorEqShift = chkVideoColorNoise.isChecked,
                enableAudioPitchTempoMicroJitter = chkAudioPitchTempo.isChecked,
                enableAudioEqVoicePresence = chkAudioEqStereo.isChecked,
                enableSilenceTrimming350ms = chkAudioTrimSilence.isChecked,
                musicRemovalMode = currentMusicRemovalMode,
                voiceoverDubMode = currentVoiceoverDubMode,
                stemRoutingMode = currentStemRoutingMode
            )
        }

        val subs = SubtitleLayerConfig(
            enabled = chkSubtitles.isChecked,
            placementZone = currentSubZone,
            customY = currentSubZone.defaultY,
            fontSizePx = currentSubFontSizePx,
            syncOffsetMs = currentSubSyncOffsetMs
        )

        return ModularRenderConfig(
            layoutLayer = effectiveLayout,
            splitTopRatio = currentSplitTopRatio,
            manualCrop = currentManualCrop,
            watermarkBoxes = currentWatermarkBoxes.toList(),
            watermarkEraseMethod = currentWatermarkEraseMethod,
            playbackSpeed = currentPlaybackSpeed,
            mainVolume = currentMainVolume,
            bgVolume = currentBgVolume,
            voiceoverVolume = currentVoiceoverVolume,
            musicVolume = currentMusicVolume,
            enableSmartFaceCrop = chkSmartFace.isChecked && !currentManualCrop.isNonTrivial,
            trimStartSec = currentTrimStartSec,
            trimEndSec = currentTrimEndSec,
            selectedPoolClipName = selectedPoolClipName,
            preferredTopicTags = preferredTags,
            customVoiceoverAudioFile = currentCustomVoiceoverFile,
            customReplacementMusicFile = currentReplacementMusicFile,
            enableReplacementMusicBed = toggleReplaceMusicChk.isChecked,
            separatedStems = currentSeparatedStems,
            existingSubtitleRemovalMode = currentHardsubRemovalMode,
            detectedHardsubRegion = currentDetectedHardsubRegion,
            subtitleLayer = subs,
            dspLayer = dsp,
            videoBitrateKbps = 12000,
            capBitrateForTelegram50Mb = false
        )
    }

    private fun runStudioRender(isPreviewPass: Boolean, forceCleanYouCutExport: Boolean) {
        val item = selectedInboxItem
        if (item == null) {
            Toast.makeText(this, "Сначала выбери исходник во вкладке «📥 Исходники»!", Toast.LENGTH_LONG).show()
            switchTab(0)
            return
        }
        val sourceFile = clutterFilter.getInboxVideoFile(item)
        if (!sourceFile.exists()) {
            Toast.makeText(this, "Файл исходника не найден!", Toast.LENGTH_LONG).show()
            return
        }

        pauseLiveStagePlayers()
        setCanvasTouchModeAndRefresh(CanvasTouchMode.PLAY_PAUSE_ONLY)

        val initialConfig = buildCurrentStudioRenderConfig(forceCleanYouCutExport)
        studioRenderStatusView.text = when {
            isPreviewPass -> "⏳ Рендер 5-сек теста (с ${String.format(Locale.US, "%.1f", initialConfig.trimStartSec)} сек)..."
            forceCleanYouCutExport -> "⏳ Экспорт чистой обрезки / свободного кропа в Галерею..."
            else -> "⏳ Полный рендер FullHD 12 Мбит/с (${initialConfig.layoutLayer.label})..."
        }

        lifecycleScope.launch {
            try {
                val (renderedFile, framingSummary) = withContext(Dispatchers.IO) {
                    val spanSec = if (initialConfig.trimEndSec > initialConfig.trimStartSec + 1.0) {
                        initialConfig.trimEndSec - initialConfig.trimStartSec
                    } else {
                        item.durationSec - initialConfig.trimStartSec
                    }.coerceAtLeast(2.0)

                    val activeHardsubRegion = if (initialConfig.existingSubtitleRemovalMode != ExistingSubtitleRemovalMode.OFF) {
                        initialConfig.detectedHardsubRegion ?: faceHelper.detectBurnedInSubtitleRegion(
                            videoFile = sourceFile,
                            startSec = initialConfig.trimStartSec,
                            durationSec = spanSec.coerceAtMost(20.0)
                        ).also { currentDetectedHardsubRegion = it }
                    } else {
                        null
                    }

                    val config = initialConfig.copy(detectedHardsubRegion = activeHardsubRegion)

                    val needsBackground = config.layoutLayer == VideoLayoutLayer.SPLIT_VSTACK ||
                        config.layoutLayer == VideoLayoutLayer.BRAINROT_VOICEOVER

                    val clutterFile = if (needsBackground) {
                        clutterFilter.buildShuffledClutterReel(
                            targetDurationSec = if (isPreviewPass) 5.0 else (spanSec / config.playbackSpeed).coerceAtLeast(4.0),
                            fallbackPrimaryVideo = sourceFile,
                            specificPoolClipName = config.selectedPoolClipName,
                            preferredTopicTags = config.preferredTopicTags
                        )
                    } else {
                        null
                    }

                    val framing = faceHelper.computeFramingPlan(
                        videoFile = sourceFile,
                        enableSmartFaceCrop = config.enableSmartFaceCrop,
                        hardsubRegion = activeHardsubRegion,
                        hardsubRemovalMode = config.existingSubtitleRemovalMode
                    )

                    val (canvasW, canvasH) = hardwareEngine.computeOutputCanvasDimensions(sourceFile, config)

                    val assFile = if (config.subtitleLayer.enabled) {
                        val effectiveSubEnd = if (isPreviewPass) {
                            config.trimStartSec + 5.5
                        } else {
                            config.trimEndSec
                        }
                        subtitleTransformer.prepareAssSubtitles(
                            sourceUrl = item.sourceUrl,
                            localVideoFile = sourceFile,
                            config = config.subtitleLayer,
                            framingPlan = framing,
                            trimStartSec = config.trimStartSec,
                            trimEndSec = effectiveSubEnd,
                            playbackSpeed = config.playbackSpeed,
                            canvasWidth = canvasW,
                            canvasHeight = canvasH
                        ).first.assFile
                    } else {
                        null
                    }

                    val out = hardwareEngine.renderPipeline(
                        primaryVideoFile = sourceFile,
                        clutterReelFile = clutterFile,
                        framingPlan = framing,
                        assSubtitleFile = assFile,
                        config = config,
                        isPreviewPass = isPreviewPass
                    )
                    out to framing.summary
                }

                if (isPreviewPass) {
                    val kb = renderedFile.length() / 1024
                    studioRenderStatusView.text = "✅ 5-сек тест готов ($kb KB) • Смотри результат на экране!"
                    showRenderedVideoOnStage(renderedFile, "⚡ 5-сек Тест рендера")
                } else {
                    val savedGalleryFile = hardwareEngine.saveRenderedVideoToGallery(renderedFile, item.title)
                    lastRenderedGalleryFile = savedGalleryFile
                    renderedFile.delete()
                    val mb = String.format(Locale.US, "%.1f", savedGalleryFile.length() / (1024.0 * 1024.0))
                    studioRenderStatusView.text = "✅ СОХРАНЕНО В ГАЛЕРЕЮ ($mb МБ)\nMovies/PocketMediaForge/${savedGalleryFile.name}"
                    showRenderedVideoOnStage(savedGalleryFile, "💾 Готовый Мастер: ${savedGalleryFile.name}")
                    Toast.makeText(
                        this@MainActivity,
                        "✅ Сохранено в Галерею ($mb МБ)!",
                        Toast.LENGTH_LONG
                    ).show()
                }
            } catch (e: Exception) {
                studioRenderStatusView.text = "❌ Ошибка рендера: ${e.message}"
                Toast.makeText(this@MainActivity, "Ошибка: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun showRenderedVideoOnStage(file: File, badgeLabel: String) {
        isShowingRenderedPreviewFile = true
        returnToLiveCompositeBtn.visibility = View.VISIBLE
        studioCanvasModeBadge.text = badgeLabel
        runCatching { bottomBgPlayer?.pause() }
        val (w, h) = hardwareEngine.probeVideoDimensions(file)
        sourceVideoNaturalWidth = w
        sourceVideoNaturalHeight = h
        updateStageLayerLayout()
        loadSourceIntoLivePlayer(file, seekSec = 0.0, autoPlay = true)
    }

    // =========================================================================
    // ВКЛАДКА 2: 🗂 МОИ ФОНЫ (ПУЛ ЗАЛИПАЛОК + ЖИВОЙ ПОИСК ФОНОВ С ВЫБОРОМ)
    // =========================================================================
    private fun buildPoolTabView(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL

            addView(sectionHeader("🗂 ПОИСК И МЕНЕДЖЕР ФОНОВ (ЗАЛИПАЛОК)"))

            val desc = TextView(this@MainActivity).apply {
                text = "Ищи залипалки прямо здесь или в Telegram-боте (кнопка «🎬 Поиск фонов / Залипалок»). Любой выбранный фон сразу крутится в нижнем окне живой Студии!"
                setTextColor(Color.parseColor("#A1A1AA"))
                textSize = 12f
                setPadding(0, 0, 0, dp(8))
            }
            addView(desc)

            addView(fieldLabel("Тема залипалки (например: gta ramp, cs2 surf, гидравлический пресс, резка мыла, дрифт):"))
            poolTopicTagInput = createInputField("cs2 surf gameplay", "", false)
            addView(poolTopicTagInput)

            val searchPoolRow = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                weightSum = 2f
                setPadding(0, dp(6), 0, 0)
            }

            val searchPickBgBtn = createStyledButton("🔎 Найти варианты фонов", "#09090B", "#38BDF8").apply {
                setTypeface(typeface, Typeface.BOLD)
                setOnClickListener {
                    val query = poolTopicTagInput.text.toString().trim()
                    if (query.isBlank()) {
                        Toast.makeText(this@MainActivity, "Введи тему залипалки выше", Toast.LENGTH_SHORT).show()
                        return@setOnClickListener
                    }
                    runInteractiveBackgroundSearchInApp(query)
                }
            }

            val fastAutoDownloadBgBtn = createStyledButton("⬇️ Сразу скачать 2 шт.", "#09090B", "#F59E0B").apply {
                setTypeface(typeface, Typeface.BOLD)
                setOnClickListener {
                    val query = poolTopicTagInput.text.toString().trim()
                    if (query.isBlank()) {
                        Toast.makeText(this@MainActivity, "Введи тему в поле выше", Toast.LENGTH_SHORT).show()
                        return@setOnClickListener
                    }
                    Toast.makeText(this@MainActivity, "⏳ Качаю залипалки по теме «$query»...", Toast.LENGTH_LONG).show()
                    lifecycleScope.launch {
                        val added = clutterFilter.downloadTaggedClutterByTopic(query, maxClipsToAdd = 2)
                        refreshPoolTab()
                        reloadLiveBackgroundPlayer()
                        if (added.isNotEmpty()) {
                            Toast.makeText(this@MainActivity, "✅ Добавлено ${added.size} шт. в пул!", Toast.LENGTH_LONG).show()
                        } else {
                            Toast.makeText(this@MainActivity, "⚠️ Попробуй нажать «🔎 Найти варианты фонов»", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }

            searchPoolRow.addView(searchPickBgBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.05f).apply { rightMargin = dp(6) })
            searchPoolRow.addView(fastAutoDownloadBgBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.95f))
            addView(searchPoolRow)

            poolSearchStatusView = TextView(this@MainActivity).apply {
                text = ""
                setTextColor(Color.parseColor("#FDE68A"))
                textSize = 11.5f
                visibility = View.GONE
                setPadding(0, dp(4), 0, dp(4))
            }
            addView(poolSearchStatusView)

            poolSearchResultsLayout = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
            }
            addView(poolSearchResultsLayout)

            val btnRow = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                weightSum = 2f
                setPadding(0, dp(6), 0, 0)
            }

            val addOwnBgBtn = createStyledButton("➕ Свой фон с телефона", "#09090B", "#10B981").apply {
                setOnClickListener { importPoolClipLauncher.launch("video/*") }
            }

            val fetchMoreBtn = createStyledButton("🔄 Докачать базовые фоны", "#F4F4F5", "#3B82F6").apply {
                setOnClickListener {
                    clutterFilter.triggerBackgroundPoolWarmupIfNeeded(forceAddMore = true)
                    Toast.makeText(this@MainActivity, "🔄 Запущена докачка фонов!", Toast.LENGTH_LONG).show()
                }
            }

            btnRow.addView(addOwnBgBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(6) })
            btnRow.addView(fetchMoreBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(btnRow)

            addView(sectionHeader("📦 ЗАГРУЖЕННЫЕ ФОНЫ В ПУЛЕ"))

            poolListLayout = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(6), 0, 0)
            }
            addView(poolListLayout)
        }
    }

    private fun runInteractiveBackgroundSearchInApp(query: String) {
        poolSearchStatusView.visibility = View.VISIBLE
        poolSearchStatusView.text = "⏳ Ищу качественные HD фоны/залипалки по запросу «$query»..."
        poolSearchResultsLayout.removeAllViews()
        lifecycleScope.launch {
            try {
                val results = topicHunter.searchBackgroundCandidates(query, limitCount = 6)
                if (results.isEmpty()) {
                    poolSearchStatusView.text = "⚠️ По запросу «$query» фонов не найдено. Попробуй другое слово."
                    return@launch
                }
                poolSearchStatusView.text = "✅ Найдено ${results.size} фонов — смотри оригинал или жми «➕ В пул»:"
                renderBackgroundCandidateCards(results, query)
            } catch (e: Exception) {
                poolSearchStatusView.text = "❌ Ошибка поиска фонов: ${e.message}"
            }
        }
    }

    private fun renderBackgroundCandidateCards(candidates: List<DiscoveredMediaCandidate>, tagQuery: String) {
        poolSearchResultsLayout.removeAllViews()
        candidates.forEachIndexed { idx, c ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(Color.parseColor("#141419"))
                val p = dp(10)
                setPadding(p, p, p, p)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(6) }
            }

            val viewsStr = if (c.viewOrScoreCount >= 1000) "${c.viewOrScoreCount / 1000}K просм." else "${c.viewOrScoreCount} просм."
            card.addView(TextView(this).apply {
                text = "🎬 #${idx + 1}: ${c.title}\n👤 ${c.author} • ⏱ ${c.durationSec}с • 👁 $viewsStr"
                setTextColor(Color.parseColor("#F4F4F5"))
                textSize = 12f
                setTypeface(typeface, Typeface.BOLD)
            })

            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                weightSum = 2f
                setPadding(0, dp(6), 0, 0)
            }
            row.addView(createCompactButton("➕ Скачать в пул", "#09090B", "#10B981").apply {
                setOnClickListener {
                    Toast.makeText(this@MainActivity, "⏳ Качаю и нормализую фон #${idx + 1}...", Toast.LENGTH_LONG).show()
                    lifecycleScope.launch {
                        try {
                            val added = clutterFilter.downloadUrlToPoolWithTags(c.url, tagQuery, c.title)
                            selectedPoolClipName = added.name
                            refreshPoolTab()
                            reloadLiveBackgroundPlayer()
                            Toast.makeText(this@MainActivity, "✅ Фон добавлен и выбран для Студии!", Toast.LENGTH_LONG).show()
                        } catch (e: Exception) {
                            Toast.makeText(this@MainActivity, "❌ Ошибка: ${e.message}", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.2f).apply { rightMargin = dp(6) })
            row.addView(createCompactButton("🔗 Смотреть", "#E4E4E7", "#27272A").apply {
                setOnClickListener {
                    runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(c.url))) }
                }
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.8f))
            card.addView(row)
            poolSearchResultsLayout.addView(card)
        }
    }

    private fun refreshPoolTab() {
        poolListLayout.removeAllViews()
        val clips = clutterFilter.listVerifiedPoolClips()
        updatePoolSelectorButtonText()

        if (clips.isEmpty()) {
            val emptyTv = TextView(this).apply {
                text = "Пул фонов пуст.\nВведи тему выше (например: «cs2 surf») и нажми «🔎 Найти варианты фонов» или загрузи видео с телефона."
                setTextColor(Color.parseColor("#71717A"))
                textSize = 12.5f
                setPadding(dp(12), dp(18), dp(12), dp(18))
                setBackgroundColor(Color.parseColor("#18181B"))
            }
            poolListLayout.addView(emptyTv)
            return
        }

        clips.forEachIndexed { idx, file ->
            val meta = clutterFilter.getPoolClipMetadata(file)
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(Color.parseColor("#18181B"))
                val p = dp(12)
                setPadding(p, p, p, p)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(8) }
            }

            val sizeMb = String.format(Locale.US, "%.1f", file.length() / (1024.0 * 1024.0))
            val isSelected = selectedPoolClipName == file.name
            val labelTv = TextView(this).apply {
                text = "Фон #${idx + 1}: ${meta.title.ifBlank { file.name }} ($sizeMb МБ)" +
                    if (isSelected) " ✅ [АКТИВЕН В СТУДИИ]" else ""
                setTextColor(if (isSelected) Color.parseColor("#34D399") else Color.parseColor("#F4F4F5"))
                textSize = 12.5f
                setTypeface(typeface, Typeface.BOLD)
                setPadding(0, 0, 0, dp(4))
            }
            card.addView(labelTv)

            val tagsEditRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                weightSum = 3f
                setPadding(0, 0, 0, dp(6))
            }

            val tagEditInput = createInputField("#мото #гонки", meta.tags.joinToString(" "), false)
            val saveTagBtn = createStyledButton("💾 Теги", "#09090B", "#FBBF24").apply {
                setOnClickListener {
                    clutterFilter.setPoolClipTags(file.name, tagEditInput.text.toString())
                    refreshPoolTab()
                    Toast.makeText(this@MainActivity, "✅ Теги обновлены!", Toast.LENGTH_SHORT).show()
                }
            }

            tagsEditRow.addView(tagEditInput, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 2.1f).apply { rightMargin = dp(6) })
            tagsEditRow.addView(saveTagBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.9f))
            card.addView(tagsEditRow)

            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                weightSum = 2f
            }

            val selectBtn = createStyledButton("✅ Поставить вниз в Студию", "#09090B", "#10B981").apply {
                setOnClickListener {
                    selectedPoolClipName = file.name
                    updatePoolSelectorButtonText()
                    refreshPoolTab()
                    reloadLiveBackgroundPlayer()
                    switchTab(1)
                }
            }

            val deleteBtn = createStyledButton("🗑 Удалить", "#FCA5A5", "#3F1D1D").apply {
                setOnClickListener {
                    clutterFilter.deletePoolClip(file.name)
                    if (selectedPoolClipName == file.name) {
                        selectedPoolClipName = "AUTO"
                    }
                    refreshPoolTab()
                    reloadLiveBackgroundPlayer()
                }
            }

            row.addView(selectBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.3f).apply { rightMargin = dp(6) })
            row.addView(deleteBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.7f))

            card.addView(row)
            poolListLayout.addView(card)
        }
    }

    // =========================================================================
    // ВКЛАДКА 3: ⚙️ КЛЮЧИ И ЛОГ
    // =========================================================================
    private fun buildSettingsTabView(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL

            addView(sectionHeader("1. КЛЮЧИ TELEGRAM-СКАУТА И GEMINI"))

            addView(fieldLabel("Telegram Bot Token (от @BotFather):"))
            botTokenInput = createInputField("123456789:AAH...", configStore.getTelegramBotToken(), false)
            addView(botTokenInput)

            addView(fieldLabel("Gemini API Key (для «Что внутри», караоке-сабов и переозвучки):"))
            geminiKeyInput = createInputField("AIzaSy...", configStore.getGeminiApiKey(), false)
            addView(geminiKeyInput)

            addView(fieldLabel("Разрешённый Chat ID (оставь пустым для любого):"))
            allowedChatIdInput = createInputField("Например: 7820650522", configStore.getAllowedChatId(), false)
            addView(allowedChatIdInput)

            val saveAndStartBtn = createStyledButton(
                "🚀 СОХРАНИТЬ КЛЮЧИ И ПЕРЕЗАПУСТИТЬ УЗЕЛ",
                "#09090B",
                "#10B981"
            ).apply {
                setTypeface(typeface, Typeface.BOLD)
                setOnClickListener {
                    configStore.setTelegramBotToken(botTokenInput.text.toString())
                    configStore.setGeminiApiKey(geminiKeyInput.text.toString())
                    configStore.setAllowedChatId(allowedChatIdInput.text.toString())
                    DiagnosticLogger.log("UI", "Пользователь сохранил настройки и перезапустил узел.")
                    PipelineForegroundService.reloadCredentials(applicationContext)
                    Toast.makeText(this@MainActivity, "✅ Ключи сохранены! Telegram-скаут перезапущен.", Toast.LENGTH_LONG).show()
                }
            }
            addView(saveAndStartBtn, buttonLayoutParams())

            val batteryBtn = createStyledButton(
                "🔋 Отключить оптимизацию батареи OxygenOS",
                "#FBBF24",
                "#27272A"
            ).apply {
                setOnClickListener {
                    if (!powerHelper.isIgnoringBatteryOptimizations()) {
                        startActivity(powerHelper.buildIgnoreBatteryOptimizationIntent())
                    } else {
                        Toast.makeText(this@MainActivity, "Оптимизация батареи уже отключена ✅", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            addView(batteryBtn, buttonLayoutParams())

            val nodeManageRow = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                weightSum = 2f
                setPadding(0, dp(6), 0, 0)
            }

            val autoLaunchBtn = createStyledButton(
                "⚡ Автозапуск OnePlus / ColorOS",
                "#E4E4E7",
                "#27272A"
            ).apply {
                setOnClickListener {
                    val intent = powerHelper.buildOxygenOsAutoLaunchIntent()
                    if (intent != null) {
                        startActivity(intent)
                    } else {
                        Toast.makeText(this@MainActivity, "Меню автозапуска не требуется ✅", Toast.LENGTH_SHORT).show()
                    }
                }
            }

            val stopNodeBtn = createStyledButton(
                "⏹ Остановить узел",
                "#FCA5A5",
                "#3F1D1D"
            ).apply {
                setOnClickListener {
                    PipelineForegroundService.stopService(applicationContext)
                    Toast.makeText(this@MainActivity, "⏹ Фоновый узел остановлен.", Toast.LENGTH_SHORT).show()
                }
            }

            nodeManageRow.addView(autoLaunchBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.15f).apply { rightMargin = dp(6) })
            nodeManageRow.addView(stopNodeBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.85f))
            addView(nodeManageRow)

            addView(sectionHeader("2. ПОЛНЫЙ ЛОГ ДЕЙСТВИЙ С ЗАПУСКА (LIVE)"))

            liveLogTextView = TextView(this@MainActivity).apply {
                text = DiagnosticLogger.getRecentLinesText(35)
                setTextColor(Color.parseColor("#34D399"))
                setBackgroundColor(Color.parseColor("#111115"))
                textSize = 10.5f
                typeface = Typeface.MONOSPACE
                val p = dp(12)
                setPadding(p, p, p, p)
            }
            addView(liveLogTextView)

            val copyLogBtn = createStyledButton(
                "📋 Скопировать весь лог в буфер обмена",
                "#E4E4E7",
                "#27272A"
            ).apply {
                setOnClickListener {
                    val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val fullText = DiagnosticLogger.getFullLogFile(applicationContext).readText()
                    cm.setPrimaryClip(ClipData.newPlainText("PocketForgeLog", fullText))
                    Toast.makeText(this@MainActivity, "✅ Лог скопирован!", Toast.LENGTH_SHORT).show()
                }
            }
            addView(copyLogBtn, buttonLayoutParams())
        }
    }

    private fun observePipelineStatus() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                PipelineForegroundService.statusFlow.collect { status ->
                    val inboxCount = clutterFilter.listStudioInboxItems().size
                    val poolCount = clutterFilter.listVerifiedPoolClips().size
                    val (text, colorHex) = when (status) {
                        is ServiceStatus.Stopped ->
                            "● ОСТАНОВЛЕН • Исходников: $inboxCount • Фонов: $poolCount" to "#F87171"
                        is ServiceStatus.Initializing ->
                            "● ИНИЦИАЛИЗАЦИЯ И ПРОВЕРКА YT-DLP..." to "#FBBF24"
                        is ServiceStatus.IdleListening ->
                            "● СКАУТ ОНЛАЙН • Исходников: $inboxCount • Фонов: $poolCount" to "#34D399"
                        is ServiceStatus.Processing ->
                            "● РАБОТА [#${status.jobId}]: ${status.stage}" to "#38BDF8"
                        is ServiceStatus.Error ->
                            "● ВНИМАНИЕ: ${status.reason}" to "#FB923C"
                    }
                    statusBadgeView.text = text
                    statusBadgeView.setTextColor(Color.parseColor(colorHex))
                }
            }
        }
    }

    private fun observeDiagnosticLogs() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                DiagnosticLogger.logFlow.collect { lines ->
                    liveLogTextView.text = if (lines.isEmpty()) {
                        "Лог пока пуст..."
                    } else {
                        lines.takeLast(35).joinToString("\n")
                    }
                }
            }
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    // =========================================================================
    // UI HELPERS
    // =========================================================================
    private fun createToolCardContainer(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(Color.parseColor("#141419"))
        val p = dp(12)
        setPadding(p, dp(10), p, dp(12))
    }

    private fun panelTitle(title: String): TextView = TextView(this).apply {
        text = title
        setTextColor(Color.parseColor("#F59E0B"))
        textSize = 11f
        typeface = Typeface.MONOSPACE
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, 0, 0, dp(6))
    }

    private fun simpleSeekListener(onChange: (Int) -> Unit): SeekBar.OnSeekBarChangeListener =
        object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) onChange(progress)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        }

    private fun createTabButton(title: String, onClick: () -> Unit): Button =
        Button(this).apply {
            text = title
            textSize = 11f
            isAllCaps = false
            val p = dp(8)
            setPadding(p, dp(10), p, dp(10))
            setOnClickListener { onClick() }
        }

    private fun tabButtonLayoutParams(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            rightMargin = dp(4)
        }

    private fun createCheckBox(label: String, checked: Boolean): CheckBox =
        CheckBox(this).apply {
            text = label
            isChecked = checked
            setTextColor(Color.parseColor("#E4E4E7"))
            textSize = 12f
            setPadding(dp(4), dp(5), dp(4), dp(5))
        }

    private fun sectionHeader(title: String): TextView = TextView(this).apply {
        text = title
        setTextColor(Color.parseColor("#F59E0B"))
        textSize = 11f
        typeface = Typeface.MONOSPACE
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(16), 0, dp(6))
    }

    private fun fieldLabel(label: String): TextView = TextView(this).apply {
        text = label
        setTextColor(Color.parseColor("#A1A1AA"))
        textSize = 11.5f
        setPadding(0, dp(5), 0, dp(3))
    }

    private fun createInputField(hint: String, initial: String, isSecret: Boolean): EditText =
        EditText(this).apply {
            this.hint = hint
            setText(initial)
            setHintTextColor(Color.parseColor("#52525B"))
            setTextColor(Color.parseColor("#F4F4F5"))
            setBackgroundColor(Color.parseColor("#18181B"))
            textSize = 13f
            typeface = Typeface.MONOSPACE
            val p = dp(10)
            setPadding(p, p, p, p)
            inputType = if (isSecret) {
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            } else {
                InputType.TYPE_CLASS_TEXT
            }
        }

    private fun createStyledButton(text: String, textColorHex: String, bgHex: String): Button =
        Button(this).apply {
            this.text = text
            setTextColor(Color.parseColor(textColorHex))
            setBackgroundColor(Color.parseColor(bgHex))
            textSize = 11.5f
            isAllCaps = false
            gravity = Gravity.CENTER
            val p = dp(10)
            setPadding(p, p, p, p)
        }

    private fun createCompactButton(text: String, textColorHex: String, bgHex: String): Button =
        Button(this).apply {
            this.text = text
            setTextColor(Color.parseColor(textColorHex))
            setBackgroundColor(Color.parseColor(bgHex))
            textSize = 10.5f
            isAllCaps = false
            gravity = Gravity.CENTER
            val p = dp(6)
            setPadding(p, dp(7), p, dp(7))
        }

    private fun buttonLayoutParams(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = dp(8)
        }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}
