package forge.media.pocket.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import forge.media.pocket.clutter.ClutterEntropyFilter
import forge.media.pocket.clutter.StudioInboxItem
import forge.media.pocket.compositor.FFmpegHardwareEngine
import forge.media.pocket.config.DiagnosticLogger
import forge.media.pocket.config.NodeConfigStore
import forge.media.pocket.discovery.BackgroundCrawlerManager
import forge.media.pocket.discovery.ContentSortOrder
import forge.media.pocket.discovery.DiscoveredMediaCandidate
import forge.media.pocket.discovery.HuntPlatform
import forge.media.pocket.discovery.SearchBlacklistStore
import forge.media.pocket.discovery.SearchDurationMode
import forge.media.pocket.discovery.SearchLanguageMode
import forge.media.pocket.discovery.SearchTimePeriod
import forge.media.pocket.service.PipelineForegroundService
import forge.media.pocket.service.PowerOptimizationHelper
import forge.media.pocket.subtitles.SubtitleAssTransformer
import forge.media.pocket.vision.FaceCenteringHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

private enum class MainBottomTab(val icon: String, val label: String) {
    SEARCH("🔎", "Поиск"),
    PRE_EDITOR("✂️", "Разделка"),
    ASSEMBLER("🎛", "Редактор"),
    SETTINGS("⚙️", "Ключи и Бан")
}

private enum class SearchHubSubTab(val label: String) {
    SOURCES_BATCH("🎬 Исходники (10–30 ссылок)"),
    BG_UNTIL_STOP("♾ Фоны до СТОПА (Ссылки)")
}

private data class WorkflowVideoPrompt(
    val file: File,
    val title: String,
    val sourceUrl: String,
    val role: PreEditTargetRole,
    val recStartSec: Double = 0.0,
    val recEndSec: Double = 0.0
)

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DiagnosticLogger.init(applicationContext)
        SearchBlacklistStore.ensureInit(applicationContext)
        DiagnosticLogger.log("UI", "Открыт главный экран Pocket Media Forge v3.0 (Compose + Media3 + Whisper)")

        try {
            PipelineForegroundService.startService(applicationContext)
        } catch (_: Exception) {
        }

        val configStore = NodeConfigStore(applicationContext)
        val clutterFilter = ClutterEntropyFilter(applicationContext)
        val hardwareEngine = FFmpegHardwareEngine(applicationContext)
        val faceHelper = FaceCenteringHelper(applicationContext)
        val subtitleTransformer = SubtitleAssTransformer(applicationContext, configStore)
        val powerHelper = PowerOptimizationHelper(applicationContext)

        setContent {
            val forgeDarkScheme = remember {
                darkColorScheme(
                    primary = Color(0xFF38BDF8),
                    secondary = Color(0xFF10B981),
                    background = Color(0xFF0B0D13),
                    surface = Color(0xFF141824),
                    onPrimary = Color.Black,
                    onBackground = Color.White,
                    onSurface = Color.White
                )
            }

            MaterialTheme(colorScheme = forgeDarkScheme) {
                PocketMediaForgeRootApp(
                    configStore = configStore,
                    clutterFilter = clutterFilter,
                    hardwareEngine = hardwareEngine,
                    faceHelper = faceHelper,
                    subtitleTransformer = subtitleTransformer,
                    powerHelper = powerHelper
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PocketMediaForgeRootApp(
    configStore: NodeConfigStore,
    clutterFilter: ClutterEntropyFilter,
    hardwareEngine: FFmpegHardwareEngine,
    faceHelper: FaceCenteringHelper,
    subtitleTransformer: SubtitleAssTransformer,
    powerHelper: PowerOptimizationHelper
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    fun showToastMsg(msg: String) {
        scope.launch {
            snackbarHostState.currentSnackbarData?.dismiss()
            snackbarHostState.showSnackbar(msg)
        }
    }

    // Request notification permission on Android 13+ so ForegroundService notification stays visible
    val notifPermLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = {}
    )
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    var currentTab by remember { mutableStateOf(MainBottomTab.SEARCH) }
    var searchSubTab by remember { mutableStateOf(SearchHubSubTab.SOURCES_BATCH) }

    // -------------------------------------------------------------------------
    // STAGE 1 (PRE-EDITOR) & STAGE 2 (MAIN ASSEMBLER) SHARED PIPELINE STATE
    // -------------------------------------------------------------------------
    var preEditFile by remember { mutableStateOf<File?>(null) }
    var preEditTitle by remember { mutableStateOf("") }
    var preEditSourceUrl by remember { mutableStateOf("") }
    var preEditRole by remember { mutableStateOf(PreEditTargetRole.PRIMARY_SOURCE) }
    var preEditRecStart by remember { mutableStateOf(0.0) }
    var preEditRecEnd by remember { mutableStateOf(0.0) }

    var assemblerPrimaryFile by remember { mutableStateOf<File?>(null) }
    var assemblerPrimaryTitle by remember { mutableStateOf("") }
    var assemblerPrimaryUrl by remember { mutableStateOf("") }

    var assemblerBgFile by remember { mutableStateOf<File?>(null) }
    var assemblerBgTitle by remember { mutableStateOf("") }

    var importedVoiceoverFile by remember { mutableStateOf<File?>(null) }

    // Workflow Choice Modal: "1. Выбрал исходник -> 2. Тебе предложили его переделать -> 3. Переделал -> в Редактор"
    var pendingWorkflowPrompt by remember { mutableStateOf<WorkflowVideoPrompt?>(null) }

    // Refresh counters for local inbox & background pool lists
    var inboxRefreshTick by remember { mutableIntStateOf(0) }
    var poolRefreshTick by remember { mutableIntStateOf(0) }
    var downloadingCardId by remember { mutableStateOf<String?>(null) }

    // -------------------------------------------------------------------------
    // SYSTEM FILE / GALLERY PICKERS
    // -------------------------------------------------------------------------
    val pickSourceVideoLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                try {
                    val item = clutterFilter.importLocalUriToInbox(uri, "Видео с телефона")
                    inboxRefreshTick++
                    val vf = clutterFilter.getInboxVideoFile(item)
                    pendingWorkflowPrompt = WorkflowVideoPrompt(
                        file = vf,
                        title = item.title,
                        sourceUrl = item.sourceUrl,
                        role = PreEditTargetRole.PRIMARY_SOURCE
                    )
                } catch (e: Exception) {
                    showToastMsg("❌ Ошибка импорта видео: ${e.message}")
                }
            }
        }
    }

    val pickBgVideoLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                try {
                    showToastMsg("⏳ Добавляю фон с телефона в список фонов...")
                    val added = clutterFilter.importLocalUriToPool(uri, "#свой_фон")
                    poolRefreshTick++
                    pendingWorkflowPrompt = WorkflowVideoPrompt(
                        file = added,
                        title = added.name,
                        sourceUrl = "local://pool",
                        role = PreEditTargetRole.BACKGROUND_CLIP
                    )
                } catch (e: Exception) {
                    showToastMsg("❌ Ошибка импорта фона: ${e.message}")
                }
            }
        }
    }

    val pickVoiceoverAudioLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                try {
                    val f = subtitleTransformer.importCustomVoiceoverUri(uri)
                    importedVoiceoverFile = f
                    showToastMsg("✅ Аудиофайл озвучки подключён: ${f.name}")
                } catch (e: Exception) {
                    showToastMsg("❌ Ошибка загрузки аудио: ${e.message}")
                }
            }
        }
    }

    val pickCookiesLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            scope.launch(Dispatchers.IO) {
                try {
                    val out = File(context.filesDir, "youtube_cookies.txt")
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        out.outputStream().use { o -> input.copyTo(o) }
                    }
                    withContext(Dispatchers.Main) {
                        showToastMsg("✅ youtube_cookies.txt загружен (${out.length() / 1024} KB)")
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        showToastMsg("❌ Ошибка cookies: ${e.message}")
                    }
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // STEP 2 PROMPT DIALOG: PRE-EDIT FIRST OR SEND STRAIGHT TO ASSEMBLER?
    // -------------------------------------------------------------------------
    pendingWorkflowPrompt?.let { prompt ->
        AlertDialog(
            onDismissRequest = { pendingWorkflowPrompt = null },
            title = {
                Text(
                    text = if (prompt.role == PreEditTargetRole.PRIMARY_SOURCE) {
                        "🎬 Исходник выбран: ${prompt.title}"
                    } else {
                        "🕹 Фон выбран: ${prompt.title}"
                    },
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    text = "Хочешь сначала переделать и почистить его в «✂️ Разделке» (вырезать лишние куски, скадрировать, убрать чужое лого) — или закинуть сразу в Главный Редактор?",
                    fontSize = 14.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        pendingWorkflowPrompt = null
                        preEditFile = prompt.file
                        preEditTitle = prompt.title
                        preEditSourceUrl = prompt.sourceUrl
                        preEditRole = prompt.role
                        preEditRecStart = prompt.recStartSec
                        preEditRecEnd = prompt.recEndSec
                        currentTab = MainBottomTab.PRE_EDITOR
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD97706))
                ) {
                    Text("✂️ Сначала переделать", color = Color.White, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = {
                        pendingWorkflowPrompt = null
                        if (prompt.role == PreEditTargetRole.PRIMARY_SOURCE) {
                            assemblerPrimaryFile = prompt.file
                            assemblerPrimaryTitle = prompt.title
                            assemblerPrimaryUrl = prompt.sourceUrl
                        } else {
                            assemblerBgFile = prompt.file
                            assemblerBgTitle = prompt.title
                        }
                        currentTab = MainBottomTab.ASSEMBLER
                    }
                ) {
                    Text("⚡ Сразу в Редактор")
                }
            }
        )
    }

    Scaffold(
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        bottomBar = {
            NavigationBar(containerColor = Color(0xFF111520)) {
                MainBottomTab.entries.forEach { tab ->
                    NavigationBarItem(
                        selected = currentTab == tab,
                        onClick = { currentTab = tab },
                        icon = { Text(tab.icon, fontSize = 18.sp) },
                        label = { Text(tab.label, fontSize = 11.sp, fontWeight = FontWeight.SemiBold) }
                    )
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(Color(0xFF0B0D13))
        ) {
            when (currentTab) {
                MainBottomTab.SEARCH -> {
                    SearchAndLibraryHubScreen(
                        subTab = searchSubTab,
                        onSubTabChange = { searchSubTab = it },
                        clutterFilter = clutterFilter,
                        inboxRefreshTick = inboxRefreshTick,
                        poolRefreshTick = poolRefreshTick,
                        downloadingCardId = downloadingCardId,
                        onPickLocalSource = { pickSourceVideoLauncher.launch("video/*") },
                        onPickLocalBackground = { pickBgVideoLauncher.launch("video/*") },
                        onDownloadSourceCandidate = { cand ->
                            downloadingCardId = cand.id
                            scope.launch {
                                try {
                                    showToastMsg("📥 Качаю в 1080p: ${cand.title.take(40)}...")
                                    val item = clutterFilter.downloadSourceToStudioInbox(
                                        sourceUrl = cand.url,
                                        overrideTitle = cand.title,
                                        overrideAuthor = cand.author,
                                        maxDurationSec = 720
                                    )
                                    inboxRefreshTick++
                                    downloadingCardId = null
                                    val vf = clutterFilter.getInboxVideoFile(item)
                                    pendingWorkflowPrompt = WorkflowVideoPrompt(
                                        file = vf,
                                        title = item.title,
                                        sourceUrl = item.sourceUrl,
                                        role = PreEditTargetRole.PRIMARY_SOURCE
                                    )
                                } catch (e: Exception) {
                                    downloadingCardId = null
                                    showToastMsg("❌ Не удалось скачать: ${e.message?.take(120)}")
                                }
                            }
                        },
                        onDownloadDirectUrl = { rawUrl, asBackground ->
                            downloadingCardId = "direct_url"
                            scope.launch {
                                try {
                                    if (asBackground) {
                                        showToastMsg("⏳ Скачиваю ролик в список фонов...")
                                        val bgF = clutterFilter.downloadUrlToPoolWithTags(rawUrl, "#ссылка", "Фон по ссылке")
                                        poolRefreshTick++
                                        downloadingCardId = null
                                        pendingWorkflowPrompt = WorkflowVideoPrompt(
                                            file = bgF,
                                            title = bgF.name,
                                            sourceUrl = rawUrl,
                                            role = PreEditTargetRole.BACKGROUND_CLIP
                                        )
                                    } else {
                                        showToastMsg("📥 Скачиваю исходник 1080p по ссылке...")
                                        val item = clutterFilter.downloadSourceToStudioInbox(rawUrl, maxDurationSec = 720)
                                        inboxRefreshTick++
                                        downloadingCardId = null
                                        val vf = clutterFilter.getInboxVideoFile(item)
                                        pendingWorkflowPrompt = WorkflowVideoPrompt(
                                            file = vf,
                                            title = item.title,
                                            sourceUrl = item.sourceUrl,
                                            role = PreEditTargetRole.PRIMARY_SOURCE
                                        )
                                    }
                                } catch (e: Exception) {
                                    downloadingCardId = null
                                    showToastMsg("❌ Ошибка ссылки: ${e.message?.take(120)}")
                                }
                            }
                        },
                        onDownloadBgCandidateToPool = { cand, bgTopic ->
                            downloadingCardId = cand.id
                            scope.launch {
                                try {
                                    showToastMsg("⏳ Скачиваю выбранный фон в список фонов...")
                                    val bgF = clutterFilter.downloadUrlToPoolWithTags(
                                        sourceUrl = cand.url,
                                        rawTagsInput = bgTopic,
                                        customTitle = cand.title
                                    )
                                    poolRefreshTick++
                                    downloadingCardId = null
                                    pendingWorkflowPrompt = WorkflowVideoPrompt(
                                        file = bgF,
                                        title = cand.title,
                                        sourceUrl = cand.url,
                                        role = PreEditTargetRole.BACKGROUND_CLIP
                                    )
                                } catch (e: Exception) {
                                    downloadingCardId = null
                                    showToastMsg("❌ Ошибка скачивания фона: ${e.message?.take(120)}")
                                }
                            }
                        },
                        onOpenDownloadedInboxItem = { item ->
                            val vf = clutterFilter.getInboxVideoFile(item)
                            pendingWorkflowPrompt = WorkflowVideoPrompt(
                                file = vf,
                                title = item.title,
                                sourceUrl = item.sourceUrl,
                                role = PreEditTargetRole.PRIMARY_SOURCE,
                                recStartSec = item.recommendedStartSec,
                                recEndSec = item.recommendedEndSec
                            )
                        },
                        onDeleteInboxItem = { item ->
                            clutterFilter.deleteStudioInboxItem(item.id)
                            inboxRefreshTick++
                        },
                        onOpenPoolClip = { file, title ->
                            pendingWorkflowPrompt = WorkflowVideoPrompt(
                                file = file,
                                title = title,
                                sourceUrl = "local://pool",
                                role = PreEditTargetRole.BACKGROUND_CLIP
                            )
                        },
                        onDeletePoolClip = { fileName ->
                            clutterFilter.deletePoolClip(fileName)
                            poolRefreshTick++
                        },
                        onShowMessage = ::showToastMsg
                    )
                }

                MainBottomTab.PRE_EDITOR -> {
                    PreEditorWorkbenchScreen(
                        inputFile = preEditFile,
                        clipTitle = preEditTitle,
                        targetRole = preEditRole,
                        initialRecommendedStartSec = preEditRecStart,
                        initialRecommendedEndSec = preEditRecEnd,
                        hardwareEngine = hardwareEngine,
                        onPickVideoRequested = { pickSourceVideoLauncher.launch("video/*") },
                        onChangeTargetRole = { preEditRole = it },
                        onBakeFinishedToAssembler = { bakedFile, role, title ->
                            if (role == PreEditTargetRole.PRIMARY_SOURCE) {
                                assemblerPrimaryFile = bakedFile
                                assemblerPrimaryTitle = "$title (Разделан)"
                                assemblerPrimaryUrl = preEditSourceUrl
                                showToastMsg("✅ Основное видео закинуто в Редактор! Теперь выбери фон из списка.")
                            } else {
                                assemblerBgFile = bakedFile
                                assemblerBgTitle = "$title (Фон)"
                                showToastMsg("✅ Фоновое видео закинуто во 2-й слот Редактора!")
                            }
                            currentTab = MainBottomTab.ASSEMBLER
                        },
                        onShowMessage = ::showToastMsg
                    )
                }

                MainBottomTab.ASSEMBLER -> {
                    ForgeAssemblerScreen(
                        primaryFile = assemblerPrimaryFile,
                        primaryTitle = assemblerPrimaryTitle,
                        primarySourceUrl = assemblerPrimaryUrl,
                        backgroundFile = assemblerBgFile,
                        backgroundTitle = assemblerBgTitle,
                        clutterFilter = clutterFilter,
                        hardwareEngine = hardwareEngine,
                        faceHelper = faceHelper,
                        subtitleTransformer = subtitleTransformer,
                        onPickPrimarySourceRequested = {
                            searchSubTab = SearchHubSubTab.SOURCES_BATCH
                            currentTab = MainBottomTab.SEARCH
                        },
                        onSendClipToPreEditor = { file, title, role ->
                            preEditFile = file
                            preEditTitle = title
                            preEditRole = role
                            preEditRecStart = 0.0
                            preEditRecEnd = 0.0
                            currentTab = MainBottomTab.PRE_EDITOR
                        },
                        onSelectBackgroundDirectly = { bgF, bgT ->
                            assemblerBgFile = bgF
                            assemblerBgTitle = bgT
                            showToastMsg("✅ Фон «$bgT» подключён к Сборщику!")
                        },
                        onImportBackgroundFromGalleryRequested = {
                            pickBgVideoLauncher.launch("video/*")
                        },
                        onJumpToBackgroundSearchRequested = {
                            searchSubTab = SearchHubSubTab.BG_UNTIL_STOP
                            currentTab = MainBottomTab.SEARCH
                        },
                        onImportCustomVoiceoverRequested = {
                            pickVoiceoverAudioLauncher.launch("audio/*")
                        },
                        importedCustomVoiceoverFile = importedVoiceoverFile,
                        onShowMessage = ::showToastMsg
                    )
                }

                MainBottomTab.SETTINGS -> {
                    SettingsAndBlacklistScreen(
                        configStore = configStore,
                        powerHelper = powerHelper,
                        onPickCookiesFile = { pickCookiesLauncher.launch("*/*") },
                        onShowMessage = ::showToastMsg
                    )
                }
            }
        }
    }
}

// =============================================================================
// TAB 1: MULTI-PLATFORM LINK-ONLY SEARCH HUB + INFINITE BG HARVESTER + INBOX
// =============================================================================

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SearchAndLibraryHubScreen(
    subTab: SearchHubSubTab,
    onSubTabChange: (SearchHubSubTab) -> Unit,
    clutterFilter: ClutterEntropyFilter,
    inboxRefreshTick: Int,
    poolRefreshTick: Int,
    downloadingCardId: String?,
    onPickLocalSource: () -> Unit,
    onPickLocalBackground: () -> Unit,
    onDownloadSourceCandidate: (DiscoveredMediaCandidate) -> Unit,
    onDownloadDirectUrl: (url: String, asBackground: Boolean) -> Unit,
    onDownloadBgCandidateToPool: (DiscoveredMediaCandidate, String) -> Unit,
    onOpenDownloadedInboxItem: (StudioInboxItem) -> Unit,
    onDeleteInboxItem: (StudioInboxItem) -> Unit,
    onOpenPoolClip: (File, String) -> Unit,
    onDeletePoolClip: (String) -> Unit,
    onShowMessage: (String) -> Unit
) {
    val context = LocalContext.current
    val sourceJobState by BackgroundCrawlerManager.sourceState.collectAsState()
    val bgJobState by BackgroundCrawlerManager.bgState.collectAsState()
    val savedSourceNeg by SearchBlacklistStore.sourceNegativeTags.collectAsState()
    val savedBgNeg by SearchBlacklistStore.bgNegativeTags.collectAsState()

    // Source search form state
    var sourceQuery by remember { mutableStateOf("стендап монологи") }
    var sourceNegInput by remember(savedSourceNeg) { mutableStateOf(savedSourceNeg) }
    var sourcePlatform by remember { mutableStateOf(HuntPlatform.ALL) }
    var sourceSort by remember { mutableStateOf(ContentSortOrder.POPULAR) }
    var sourcePeriod by remember { mutableStateOf(SearchTimePeriod.MONTH) }
    var sourceDuration by remember { mutableStateOf(SearchDurationMode.ANY_UP_TO_6M) }
    var sourceLanguage by remember { mutableStateOf(SearchLanguageMode.RUSSIAN_ONLY) }
    var sourceTargetCount by remember { mutableIntStateOf(20) }
    var directUrlInput by remember { mutableStateOf("") }

    // Background infinite harvester form state
    var bgQuery by remember { mutableStateOf("Subway Surfers") }
    var bgNegInput by remember(savedBgNeg) { mutableStateOf(savedBgNeg) }
    var bgPlatform by remember { mutableStateOf(HuntPlatform.ALL) }

    val inboxItems = remember(inboxRefreshTick) { clutterFilter.listStudioInboxItems() }
    val poolClips = remember(poolRefreshTick) { clutterFilter.listVerifiedPoolClips() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(12.dp)
    ) {
        // Sub-tab switcher: Sources Batch vs Infinite Background Harvester
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            SearchHubSubTab.entries.forEach { st ->
                FilterChip(
                    selected = subTab == st,
                    onClick = { onSubTabChange(st) },
                    label = { Text(st.label, fontSize = 12.sp, fontWeight = FontWeight.Bold) },
                    modifier = Modifier.weight(1f)
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        if (subTab == SearchHubSubTab.SOURCES_BATCH) {
            // Quick Direct Import Bar (Gallery + Direct Link)
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF141824)),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(10.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Button(
                            onClick = onPickLocalSource,
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4F46E5)),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("📱 Видео с телефона", fontSize = 12.sp)
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = directUrlInput,
                            onValueChange = { directUrlInput = it },
                            label = { Text("Прямая ссылка (YT / VK / Pikabu / Reddit / Rutube)", fontSize = 11.sp) },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        Button(
                            onClick = {
                                if (directUrlInput.startsWith("http")) {
                                    onDownloadDirectUrl(directUrlInput.trim(), false)
                                    directUrlInput = ""
                                } else {
                                    onShowMessage("Вставь корректную ссылку https://...")
                                }
                            },
                            enabled = downloadingCardId == null
                        ) {
                            Text("⬇️", fontSize = 14.sp)
                        }
                    }
                }
            }

            Spacer(Modifier.height(10.dp))

            // Multi-Platform Link-Only Batch Crawler Configurator
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF141824)),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        text = "🔎 Пакетный поиск ссылок (без авто-скачивания мусора)",
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(6.dp))

                    OutlinedTextField(
                        value = sourceQuery,
                        onValueChange = { sourceQuery = it },
                        label = { Text("Точный запрос (например: стендап истории, подкаст, cs2 клатчи)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(6.dp))

                    OutlinedTextField(
                        value = sourceNegInput,
                        onValueChange = { sourceNegInput = it },
                        label = { Text("Минус-слова (отсеять мусор: -концерт -стрим -реакция)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(Modifier.height(8.dp))
                    Text("Площадки:", color = Color(0xFF93C5FD), fontSize = 11.sp)
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        HuntPlatform.entries.forEach { p ->
                            FilterChip(
                                selected = sourcePlatform == p,
                                onClick = { sourcePlatform = p },
                                label = { Text(p.badge, fontSize = 11.sp) }
                            )
                        }
                    }

                    Text("Период и Хронометраж:", color = Color(0xFF93C5FD), fontSize = 11.sp)
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        SearchTimePeriod.entries.forEach { per ->
                            FilterChip(
                                selected = sourcePeriod == per,
                                onClick = { sourcePeriod = per },
                                label = { Text(per.label, fontSize = 11.sp) }
                            )
                        }
                    }
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        SearchDurationMode.entries.forEach { dm ->
                            FilterChip(
                                selected = sourceDuration == dm,
                                onClick = { sourceDuration = dm },
                                label = { Text(dm.label.substringBefore(" ("), fontSize = 11.sp) }
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf(10, 20, 30).forEach { cnt ->
                                FilterChip(
                                    selected = sourceTargetCount == cnt,
                                    onClick = { sourceTargetCount = cnt },
                                    label = { Text("$cnt ссылок", fontSize = 11.sp) }
                                )
                            }
                        }
                        FilterChip(
                            selected = sourceLanguage == SearchLanguageMode.ANY_LANGUAGE,
                            onClick = {
                                sourceLanguage = when (sourceLanguage) {
                                    SearchLanguageMode.RUSSIAN_ONLY -> SearchLanguageMode.ENGLISH_FOR_DUB
                                    SearchLanguageMode.ENGLISH_FOR_DUB -> SearchLanguageMode.ANY_LANGUAGE
                                    SearchLanguageMode.ANY_LANGUAGE -> SearchLanguageMode.RUSSIAN_ONLY
                                }
                            },
                            label = { Text(sourceLanguage.label, fontSize = 11.sp) }
                        )
                    }

                    Spacer(Modifier.height(8.dp))

                    if (sourceJobState.isRunning) {
                        Button(
                            onClick = { BackgroundCrawlerManager.stopSourceBatchCrawl() },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDC2626)),
                            modifier = Modifier.fillMaxWidth().height(46.dp)
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = Color.White)
                            Spacer(Modifier.width(8.dp))
                            Text("⏹ ОСТАНОВИТЬ СБОР (${sourceJobState.results.size}/$sourceTargetCount)", fontWeight = FontWeight.Bold)
                        }
                    } else {
                        Button(
                            onClick = {
                                BackgroundCrawlerManager.startSourceBatchCrawl(
                                    context = context,
                                    query = sourceQuery,
                                    negativeTags = sourceNegInput,
                                    platform = sourcePlatform,
                                    sortOrder = sourceSort,
                                    period = sourcePeriod,
                                    durationMode = sourceDuration,
                                    languageMode = sourceLanguage,
                                    targetCount = sourceTargetCount
                                )
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981)),
                            modifier = Modifier.fillMaxWidth().height(46.dp)
                        ) {
                            Text(
                                "🚀 СОБРАТЬ $sourceTargetCount ССЫЛОК В ФОНЕ",
                                color = Color.Black,
                                fontWeight = FontWeight.ExtraBold
                            )
                        }
                    }

                    Spacer(Modifier.height(6.dp))
                    Text(sourceJobState.statusText, color = Color(0xFFA7F3D0), fontSize = 12.sp)
                }
            }

            // Live Discovered Source Link Cards
            if (sourceJobState.results.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Text(
                    "🔗 Найденные ссылки (${sourceJobState.results.size} шт.):",
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(6.dp))
                sourceJobState.results.forEach { cand ->
                    DiscoveredLinkCard(
                        candidate = cand,
                        takeButtonLabel = "✅ Забрать (1080p)",
                        isDownloading = downloadingCardId == cand.id,
                        onOpenBrowser = {
                            try {
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(cand.url)))
                            } catch (_: Exception) {
                            }
                        },
                        onTakeClick = { onDownloadSourceCandidate(cand) },
                        onBanVideo = {
                            BackgroundCrawlerManager.banCandidateAndPurge(context, cand, banEntireChannel = false)
                            onShowMessage("🚫 Ролик скрыт и занесён в бан")
                        },
                        onBanChannel = {
                            BackgroundCrawlerManager.banCandidateAndPurge(context, cand, banEntireChannel = true)
                            onShowMessage("🚫 Канал «${cand.author}» заблокирован навсегда")
                        }
                    )
                    Spacer(Modifier.height(8.dp))
                }
            }

            // Downloaded Studio Inbox Items
            if (inboxItems.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Text(
                    "📂 Уже скачанные исходники (${inboxItems.size} шт.) — нажми, чтобы переделать или собрать:",
                    color = Color(0xFF93C5FD),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(6.dp))
                inboxItems.forEach { item ->
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF171C2B)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 3.dp)
                            .clickable { onOpenDownloadedInboxItem(item) }
                    ) {
                        Row(
                            modifier = Modifier.padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(item.title, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                                Text(
                                    "${item.durationSec.toInt()}с • ${item.sizeKb / 1024} МБ • ${item.author}",
                                    color = Color(0xFF9CA3AF),
                                    fontSize = 11.sp
                                )
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Button(
                                    onClick = { onOpenDownloadedInboxItem(item) },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2563EB)),
                                    modifier = Modifier.height(32.dp)
                                ) { Text("Открыть", fontSize = 11.sp) }

                                OutlinedButton(
                                    onClick = { onDeleteInboxItem(item) },
                                    modifier = Modifier.height(32.dp)
                                ) { Text("🗑", fontSize = 11.sp) }
                            }
                        }
                    }
                }
            }
        } else {
            // =================================================================
            // SUB-TAB 2: MODULE 4 — INFINITE LINK-ONLY BACKGROUND HARVESTER!
            // =================================================================
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF141824)),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        text = "♾ Бесконечный поиск Фонов ССЫЛКАМИ (до кнопки СТОП)",
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Ничего не скачивает без спроса! Идёт страница за страницей в фоне по твоему ТОЧНОМУ запросу, пока ты не нажмёшь СТОП.",
                        color = Color(0xFF9CA3AF),
                        fontSize = 11.sp
                    )
                    Spacer(Modifier.height(8.dp))

                    OutlinedTextField(
                        value = bgQuery,
                        onValueChange = { bgQuery = it },
                        label = { Text("Точная тема фона (например: Subway Surfers, Minecraft Parkour, Мото POV)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(6.dp))

                    OutlinedTextField(
                        value = bgNegInput,
                        onValueChange = { bgNegInput = it },
                        label = { Text("Минус-теги (отсечь лишнее: -clutch -ace -donk -reaction)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        HuntPlatform.entries.forEach { p ->
                            FilterChip(
                                selected = bgPlatform == p,
                                onClick = { bgPlatform = p },
                                label = { Text(p.badge, fontSize = 11.sp) }
                            )
                        }
                    }

                    Spacer(Modifier.height(8.dp))
                    if (bgJobState.isRunning) {
                        Button(
                            onClick = { BackgroundCrawlerManager.stopInfiniteBackgroundCrawl() },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDC2626)),
                            modifier = Modifier.fillMaxWidth().height(48.dp)
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = Color.White)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "⏹ СТОП (Стр. #${bgJobState.pagesCrawled} • Найдено: ${bgJobState.results.size})",
                                fontWeight = FontWeight.ExtraBold
                            )
                        }
                    } else {
                        Button(
                            onClick = {
                                BackgroundCrawlerManager.startInfiniteBackgroundCrawl(
                                    context = context,
                                    query = bgQuery,
                                    negativeTags = bgNegInput,
                                    platform = bgPlatform
                                )
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981)),
                            modifier = Modifier.fillMaxWidth().height(48.dp)
                        ) {
                            Text(
                                "▶️ ИСКАТЬ ФОНЫ ССЫЛКАМИ ДО СТОПА",
                                color = Color.Black,
                                fontWeight = FontWeight.ExtraBold
                            )
                        }
                    }

                    Spacer(Modifier.height(6.dp))
                    Text(bgJobState.statusText, color = Color(0xFFA7F3D0), fontSize = 12.sp)
                }
            }

            if (bgJobState.results.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Text(
                    "🔗 Найденные ссылки на фоны (${bgJobState.results.size} шт.):",
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(6.dp))
                bgJobState.results.forEach { cand ->
                    DiscoveredLinkCard(
                        candidate = cand,
                        takeButtonLabel = "✅ Забрать в Фоны",
                        isDownloading = downloadingCardId == cand.id,
                        onOpenBrowser = {
                            try {
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(cand.url)))
                            } catch (_: Exception) {
                            }
                        },
                        onTakeClick = { onDownloadBgCandidateToPool(cand, bgQuery) },
                        onBanVideo = {
                            BackgroundCrawlerManager.banCandidateAndPurge(context, cand, banEntireChannel = false)
                            onShowMessage("🚫 Ролик убран в бан")
                        },
                        onBanChannel = {
                            BackgroundCrawlerManager.banCandidateAndPurge(context, cand, banEntireChannel = true)
                            onShowMessage("🚫 Канал «${cand.author}» забанен")
                        }
                    )
                    Spacer(Modifier.height(8.dp))
                }
            }

            // Approved Background Pool Manager
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "🗂 Мой список фонов (${poolClips.size} шт.):",
                    color = Color(0xFF34D399),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )
                OutlinedButton(onClick = onPickLocalBackground, modifier = Modifier.height(32.dp)) {
                    Text("📱 Свой с телефона", fontSize = 11.sp)
                }
            }
            Spacer(Modifier.height(6.dp))

            poolClips.forEach { clipFile ->
                val meta = clutterFilter.getPoolClipMetadata(clipFile)
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF171C2B)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 3.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(meta.title, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                            Text("${meta.sizeKb / 1024} МБ • ${meta.tags.joinToString(" ")}", color = Color(0xFF9CA3AF), fontSize = 11.sp)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Button(
                                onClick = { onOpenPoolClip(clipFile, meta.title) },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF059669)),
                                modifier = Modifier.height(32.dp)
                            ) { Text("В Редактор", fontSize = 11.sp) }

                            OutlinedButton(
                                onClick = { onDeletePoolClip(clipFile.name) },
                                modifier = Modifier.height(32.dp)
                            ) { Text("🗑", fontSize = 11.sp) }
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun DiscoveredLinkCard(
    candidate: DiscoveredMediaCandidate,
    takeButtonLabel: String,
    isDownloading: Boolean,
    onOpenBrowser: () -> Unit,
    onTakeClick: () -> Unit,
    onBanVideo: () -> Unit,
    onBanChannel: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF181D2C)),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onOpenBrowser() },
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (candidate.thumbnailUrl.startsWith("http")) {
                    AsyncImage(
                        model = candidate.thumbnailUrl,
                        contentDescription = candidate.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .width(96.dp)
                            .height(64.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0xFF0B0D13))
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .width(96.dp)
                            .height(64.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0xFF232A3E)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(candidate.platform.badge, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "[${candidate.platform.badge}] ${candidate.title}",
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    val durMin = candidate.durationSec / 60
                    val durRem = candidate.durationSec % 60
                    val durFormatted = String.format(Locale.US, "%02d:%02d", durMin, durRem)
                    Text(
                        text = "⏱ $durFormatted • 👤 ${candidate.author} • ${candidate.cloutStatusLabel}",
                        color = Color(0xFF93C5FD),
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = candidate.url,
                        color = Color(0xFF6EE7B7),
                        fontSize = 10.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                OutlinedButton(
                    onClick = onOpenBrowser,
                    modifier = Modifier.height(34.dp)
                ) {
                    Text("🔗 Открыть", fontSize = 11.sp)
                }

                Button(
                    onClick = onTakeClick,
                    enabled = !isDownloading,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981)),
                    modifier = Modifier.weight(1f).height(34.dp)
                ) {
                    if (isDownloading) {
                        CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp, color = Color.Black)
                    } else {
                        Text(takeButtonLabel, color = Color.Black, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }

                OutlinedButton(
                    onClick = onBanVideo,
                    modifier = Modifier.height(34.dp)
                ) {
                    Text("🚫 Видео", fontSize = 10.sp)
                }

                OutlinedButton(
                    onClick = onBanChannel,
                    modifier = Modifier.height(34.dp)
                ) {
                    Text("🚫 Канал", fontSize = 10.sp)
                }
            }
        }
    }
}

// =============================================================================
// TAB 4: API KEYS (GROQ WHISPER + GEMINI + TELEGRAM), BLACKLIST & LIVE LOG
// =============================================================================

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SettingsAndBlacklistScreen(
    configStore: NodeConfigStore,
    powerHelper: PowerOptimizationHelper,
    onPickCookiesFile: () -> Unit,
    onShowMessage: (String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var groqKey by remember { mutableStateOf(configStore.getGroqApiKey()) }
    var geminiKey by remember { mutableStateOf(configStore.getGeminiApiKey()) }
    var tgToken by remember { mutableStateOf(configStore.getTelegramBotToken()) }
    var chatId by remember { mutableStateOf(configStore.getAllowedChatId()) }

    val bannedAuthors by SearchBlacklistStore.bannedAuthors.collectAsState()
    val bannedVideos by SearchBlacklistStore.bannedVideoKeys.collectAsState()
    val logLines by DiagnosticLogger.logFlow.collectAsState()
    val svcStatus by PipelineForegroundService.statusFlow.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF141824)),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text("🔑 API Ключи (Groq Whisper + Gemini + Telegram)", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                Text("Статус сервиса: $svcStatus", color = Color(0xFF6EE7B7), fontSize = 11.sp)
                Spacer(Modifier.height(8.dp))

                OutlinedTextField(
                    value = groqKey,
                    onValueChange = { groqKey = it },
                    label = { Text("🎙 Groq API Key (gsk_...) — Точные субтитры Whisper-v3-Turbo") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(6.dp))

                OutlinedTextField(
                    value = geminiKey,
                    onValueChange = { geminiKey = it },
                    label = { Text("🧠 Gemini API Key (AIza...) — Озвучка TTS, Рерайт, Shazam") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(6.dp))

                OutlinedTextField(
                    value = tgToken,
                    onValueChange = { tgToken = it },
                    label = { Text("🤖 Telegram Bot Token") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(6.dp))

                OutlinedTextField(
                    value = chatId,
                    onValueChange = { chatId = it },
                    label = { Text("🔒 Разрешённый Chat ID (пусто = любой)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(10.dp))

                Button(
                    onClick = {
                        configStore.setGroqApiKey(groqKey)
                        configStore.setGeminiApiKey(geminiKey)
                        configStore.setTelegramBotToken(tgToken)
                        configStore.setAllowedChatId(chatId)
                        DiagnosticLogger.log("UI", "Пользователь сохранил ключи (Groq + Gemini + Telegram) и перезапустил узел.")
                        PipelineForegroundService.reloadCredentials(context)
                        onShowMessage("✅ Ключи сохранены, узел перезапущен!")
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981)),
                    modifier = Modifier.fillMaxWidth().height(44.dp)
                ) {
                    Text("💾 СОХРАНИТЬ КЛЮЧИ И ПЕРЕЗАПУСТИТЬ", color = Color.Black, fontWeight = FontWeight.Bold)
                }
            }
        }

        // Blacklist Manager Card
        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF141824)),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    "🚫 Чёрный список поиска (Каналов: ${bannedAuthors.size} • Роликов: ${bannedVideos.size})",
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(6.dp))

                if (bannedAuthors.isNotEmpty()) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        bannedAuthors.forEach { ch ->
                            FilterChip(
                                selected = true,
                                onClick = { SearchBlacklistStore.unbanChannel(context, ch) },
                                label = { Text("✖ $ch", fontSize = 11.sp) }
                            )
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { SearchBlacklistStore.clearBannedChannels(context) },
                        modifier = Modifier.weight(1f)
                    ) { Text("Разбанить все каналы", fontSize = 11.sp) }

                    OutlinedButton(
                        onClick = { SearchBlacklistStore.clearBannedVideos(context) },
                        modifier = Modifier.weight(1f)
                    ) { Text("Очистить бан видео", fontSize = 11.sp) }
                }
            }
        }

        // System Tools: Cookies, OxygenOS Battery, yt-dlp updater
        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF141824)),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("⚡ Инструменты OnePlus / OxygenOS и yt-dlp", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(onClick = onPickCookiesFile, modifier = Modifier.weight(1f)) {
                        Text("🍪 Загрузить cookies.txt", fontSize = 11.sp)
                    }
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                onShowMessage("⬆️ Обновляю бинарник yt-dlp...")
                                val ver = PipelineForegroundService.ensureModernYtDlpBinary(context, forceDownload = true)
                                onShowMessage("✅ yt-dlp обновлён: $ver")
                            }
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("⬆️ Обновить yt-dlp", fontSize = 11.sp)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(
                        onClick = {
                            try {
                                context.startActivity(powerHelper.buildIgnoreBatteryOptimizationIntent())
                            } catch (_: Exception) {
                            }
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("🔋 Без ограничений батареи", fontSize = 11.sp)
                    }
                    OutlinedButton(
                        onClick = {
                            powerHelper.buildOxygenOsAutoLaunchIntent()?.let {
                                try {
                                    context.startActivity(it)
                                } catch (_: Exception) {
                                }
                            } ?: onShowMessage("Меню автозапуска OxygenOS уже активно")
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("🚀 Автозапуск OxygenOS", fontSize = 11.sp)
                    }
                }
            }
        }

        // Live Diagnostic Log Viewer
        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF0F131D)),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("📜 Живой лог узла (${logLines.size} строк)", color = Color(0xFF93C5FD), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    TextButton(onClick = { DiagnosticLogger.clear(context) }) {
                        Text("Очистить", fontSize = 11.sp)
                    }
                }
                Surface(
                    color = Color(0xFF07090E),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = logLines.takeLast(60).joinToString("\n").ifBlank { "Лог пуст." },
                        color = Color(0xFFD1D5DB),
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(8.dp)
                    )
                }
            }
        }
    }
}
