package forge.media.pocket.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
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

private enum class MainBottomTab(val icon: String, val label: String) {
    SEARCH("🔎", "Поиск"),
    STUDIO("🎬", "Студия YouCut"),
    SETTINGS("⚙️", "Настройки и Лог")
}

private enum class SearchHubSubTab(val label: String) {
    SOURCES_BATCH("🎬 Исходники (Масс-ЦА)"),
    BG_UNTIL_STOP("♾ Фоны-наполнители")
}

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DiagnosticLogger.init(applicationContext)
        SearchBlacklistStore.ensureInit(applicationContext)
        DiagnosticLogger.log("UI", "Открыт главный экран Pocket Media Forge v4.0 (YouCut Studio + Gemini Frame Curator)")

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
                    primary = Color(0xFFF97316),
                    secondary = Color(0xFF38BDF8),
                    background = Color(0xFF0A0C10),
                    surface = Color(0xFF141824),
                    onPrimary = Color.White,
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

    val notifPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    var currentTab by remember { mutableStateOf(MainBottomTab.SEARCH) }
    var searchSubTab by remember { mutableStateOf(SearchHubSubTab.SOURCES_BATCH) }

    // Unified YouCut Studio State
    var studioPrimaryFile by remember { mutableStateOf<File?>(null) }
    var studioPrimaryTitle by remember { mutableStateOf("") }
    var studioPrimaryUrl by remember { mutableStateOf("") }
    var studioRecStartSec by remember { mutableDoubleStateOf(0.0) }
    var studioRecEndSec by remember { mutableDoubleStateOf(0.0) }

    var studioBgFile by remember { mutableStateOf<File?>(null) }
    var studioBgTitle by remember { mutableStateOf("") }
    var importedVoiceoverFile by remember { mutableStateOf<File?>(null) }

    LaunchedEffect(Unit) {
        val existingInbox = clutterFilter.listStudioInboxItems()
        if (studioPrimaryFile == null && existingInbox.isNotEmpty()) {
            val first = existingInbox.first()
            studioPrimaryFile = clutterFilter.getInboxVideoFile(first)
            studioPrimaryTitle = first.title
            studioPrimaryUrl = first.sourceUrl
            studioRecStartSec = first.recommendedStartSec
            studioRecEndSec = first.recommendedEndSec
        }
        val existingPool = clutterFilter.listVerifiedPoolClips()
        if (studioBgFile == null && existingPool.isNotEmpty()) {
            studioBgFile = existingPool.first()
            studioBgTitle = existingPool.first().nameWithoutExtension
        }
    }

    // File pickers
    val pickPrimaryVideoLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                try {
                    val item = clutterFilter.importLocalUriToInbox(uri)
                    val saved = clutterFilter.getInboxVideoFile(item)
                    if (saved.exists()) {
                        studioPrimaryFile = saved
                        studioPrimaryTitle = item.title
                        studioPrimaryUrl = item.sourceUrl
                        studioRecStartSec = 0.0
                        studioRecEndSec = 0.0
                        currentTab = MainBottomTab.STUDIO
                        showToastMsg("🎬 Видео открыто на 1-й дорожке YouCut!")
                    }
                } catch (e: Exception) {
                    showToastMsg("❌ Ошибка импорта видео: ${e.message}")
                }
            }
        }
    }

    val pickBgVideoLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                try {
                    val saved = clutterFilter.importLocalUriToPool(uri)
                    if (saved.exists()) {
                        studioBgFile = saved
                        studioBgTitle = saved.nameWithoutExtension
                        currentTab = MainBottomTab.STUDIO
                        showToastMsg("🕹 Фон добавлен на 2-ю дорожку YouCut!")
                    }
                } catch (e: Exception) {
                    showToastMsg("❌ Ошибка добавления фона: ${e.message}")
                }
            }
        }
    }

    val pickVoiceoverAudioLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                val saved = withContext(Dispatchers.IO) {
                    val out = File(context.cacheDir, "custom_vo_${System.currentTimeMillis()}.mp3")
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        out.outputStream().use { output -> input.copyTo(output) }
                    }
                    if (out.exists() && out.length() > 500L) out else null
                }
                if (saved != null) {
                    importedVoiceoverFile = saved
                    showToastMsg("✅ Аудиофайл озвучки подключён")
                }
            }
        }
    }

    val pickCookiesLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                val ok = withContext(Dispatchers.IO) {
                    val out = File(context.filesDir, "youtube_cookies.txt")
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        out.outputStream().use { output -> input.copyTo(output) }
                    }
                    out.exists() && out.length() > 20L
                }
                if (ok) showToastMsg("🍪 cookies.txt сохранён!")
            }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        containerColor = Color(0xFF0A0C10),
        bottomBar = {
            NavigationBar(containerColor = Color(0xFF11141D)) {
                MainBottomTab.entries.forEach { tab ->
                    NavigationBarItem(
                        selected = currentTab == tab,
                        onClick = { currentTab = tab },
                        icon = { Text(tab.icon, fontSize = 18.sp) },
                        label = { Text(tab.label, fontSize = 11.sp, maxLines = 1) }
                    )
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when (currentTab) {
                MainBottomTab.SEARCH -> {
                    SearchAndLibraryHubScreen(
                        activeSubTab = searchSubTab,
                        onChangeSubTab = { searchSubTab = it },
                        clutterFilter = clutterFilter,
                        onLoadSourceIntoStudio = { file, title, url, sSec, eSec ->
                            studioPrimaryFile = file
                            studioPrimaryTitle = title
                            studioPrimaryUrl = url
                            studioRecStartSec = sSec
                            studioRecEndSec = eSec
                            currentTab = MainBottomTab.STUDIO
                            showToastMsg("🎬 Ролик загружен на 1-й дорожке Студии YouCut!")
                        },
                        onLoadBackgroundIntoStudio = { file, title ->
                            studioBgFile = file
                            studioBgTitle = title
                            showToastMsg("🕹 Чистый фон добавлен в пул и на 2-ю дорожку Студии!")
                        },
                        onPickLocalPrimary = { pickPrimaryVideoLauncher.launch("video/*") },
                        onPickLocalBackground = { pickBgVideoLauncher.launch("video/*") },
                        onShowMessage = ::showToastMsg
                    )
                }

                MainBottomTab.STUDIO -> {
                    YouCutStudioScreen(
                        primaryFile = studioPrimaryFile,
                        primaryTitle = studioPrimaryTitle,
                        primarySourceUrl = studioPrimaryUrl,
                        initialStartSec = studioRecStartSec,
                        initialEndSec = studioRecEndSec,
                        backgroundFile = studioBgFile,
                        backgroundTitle = studioBgTitle,
                        clutterFilter = clutterFilter,
                        hardwareEngine = hardwareEngine,
                        faceHelper = faceHelper,
                        subtitleTransformer = subtitleTransformer,
                        onPickPrimaryVideoFromPhone = { pickPrimaryVideoLauncher.launch("video/*") },
                        onSelectPrimaryFromInbox = { f, t, u ->
                            studioPrimaryFile = f
                            studioPrimaryTitle = t
                            studioPrimaryUrl = u
                            studioRecStartSec = 0.0
                            studioRecEndSec = 0.0
                        },
                        onSelectBackgroundFile = { bf, bt ->
                            studioBgFile = bf
                            studioBgTitle = bt
                        },
                        onPickBackgroundFromPhone = { pickBgVideoLauncher.launch("video/*") },
                        onJumpToSearchTab = { forBg ->
                            searchSubTab = if (forBg) SearchHubSubTab.BG_UNTIL_STOP else SearchHubSubTab.SOURCES_BATCH
                            currentTab = MainBottomTab.SEARCH
                        },
                        onImportVoiceoverAudio = { pickVoiceoverAudioLauncher.launch("audio/*") },
                        importedVoiceoverFile = importedVoiceoverFile,
                        onShowMessage = ::showToastMsg
                    )
                }

                MainBottomTab.SETTINGS -> {
                    SettingsAndLogScreen(
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

// =========================================================================
// TAB 1: MULTI-PLATFORM LINK-ONLY SEARCH + GEMINI FRAME CURATOR
// =========================================================================

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SearchAndLibraryHubScreen(
    activeSubTab: SearchHubSubTab,
    onChangeSubTab: (SearchHubSubTab) -> Unit,
    clutterFilter: ClutterEntropyFilter,
    onLoadSourceIntoStudio: (File, String, String, Double, Double) -> Unit,
    onLoadBackgroundIntoStudio: (File, String) -> Unit,
    onPickLocalPrimary: () -> Unit,
    onPickLocalBackground: () -> Unit,
    onShowMessage: (String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val sourceJobState by BackgroundCrawlerManager.sourceState.collectAsState()
    val bgJobState by BackgroundCrawlerManager.bgState.collectAsState()
    val savedSourceNeg by SearchBlacklistStore.sourceNegativeTags.collectAsState()
    val savedBgNeg by SearchBlacklistStore.bgNegativeTags.collectAsState()
    val seenHistoryCount by SearchBlacklistStore.seenCount.collectAsState()

    var sourceQuery by remember { mutableStateOf("Стендап про отношения") }
    var sourceNegInput by remember(savedSourceNeg) { mutableStateOf(savedSourceNeg) }
    var sourcePlatform by remember { mutableStateOf(HuntPlatform.ALL) }
    var sourcePeriod by remember { mutableStateOf(SearchTimePeriod.MONTH) }
    var sourceDuration by remember { mutableStateOf(SearchDurationMode.SHORTS_ONLY) }
    var sourceLanguage by remember { mutableStateOf(SearchLanguageMode.RUSSIAN_ONLY) }
    var sourceSort by remember { mutableStateOf(ContentSortOrder.POPULAR) }
    var sourceTargetCount by remember { mutableIntStateOf(10) }
    var enableAiAudienceFilter by remember { mutableStateOf(true) }
    var directUrlInput by remember { mutableStateOf("") }

    var bgQuery by remember { mutableStateOf("Subway Surfers") }
    var bgNegInput by remember(savedBgNeg) { mutableStateOf(savedBgNeg) }
    var bgPlatform by remember { mutableStateOf(HuntPlatform.YOUTUBE) }

    var downloadingCardId by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            SearchHubSubTab.entries.forEach { tab ->
                val sel = activeSubTab == tab
                Button(
                    onClick = { onChangeSubTab(tab) },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (sel) Color(0xFFF97316) else Color(0xFF1A2030),
                        contentColor = Color.White
                    ),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Text(tab.label, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                }
            }
        }

        if (activeSubTab == SearchHubSubTab.SOURCES_BATCH) {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF141824)),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "🎯 Поиск для массовой ЦА (без дублей и повторов)",
                            color = Color.White,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(1f)
                        )
                        if (seenHistoryCount > 0) {
                            TextButton(
                                onClick = {
                                    SearchBlacklistStore.clearSeenHistory(context)
                                    onShowMessage("🔄 Память просмотренных сброшена")
                                }
                            ) {
                                Text("🔄 Сброс ($seenHistoryCount)", fontSize = 11.sp, color = Color(0xFF93C5FD))
                            }
                        }
                    }

                    OutlinedTextField(
                        value = sourceQuery,
                        onValueChange = { sourceQuery = it },
                        label = { Text("Тема (напр. Стендап про отношения, Смешная история)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = sourceNegInput,
                        onValueChange = {
                            sourceNegInput = it
                            SearchBlacklistStore.setSourceNegativeTags(context, it)
                        },
                        label = { Text("Минус-слова (отсечь душнину и концерты)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "🧠 Фейсконтроль Gemini + проверка кадров",
                                color = Color(0xFFFDE68A),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                "Отсекает бизнес-стендапы, 15с пустышки, статьи и баяны",
                                color = Color(0xFF9CA3AF),
                                fontSize = 10.sp
                            )
                        }
                        Switch(checked = enableAiAudienceFilter, onCheckedChange = { enableAiAudienceFilter = it })
                    }

                    Text("Площадка:", color = Color(0xFF9CA3AF), fontSize = 11.sp)
                    Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        HuntPlatform.entries.forEach { p ->
                            FilterChip(
                                selected = sourcePlatform == p,
                                onClick = { sourcePlatform = p },
                                label = { Text(p.badge, fontSize = 11.sp) }
                            )
                        }
                    }

                    Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        SearchDurationMode.entries.forEach { dm ->
                            FilterChip(
                                selected = sourceDuration == dm,
                                onClick = { sourceDuration = dm },
                                label = { Text(dm.label, fontSize = 11.sp) }
                            )
                        }
                    }

                    Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        SearchTimePeriod.entries.forEach { tp ->
                            FilterChip(
                                selected = sourcePeriod == tp,
                                onClick = { sourcePeriod = tp },
                                label = { Text(tp.label, fontSize = 11.sp) }
                            )
                        }
                    }

                    Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        SearchLanguageMode.entries.forEach { lm ->
                            FilterChip(
                                selected = sourceLanguage == lm,
                                onClick = { sourceLanguage = lm },
                                label = { Text(lm.label, fontSize = 11.sp) }
                            )
                        }
                        listOf(10, 20, 30).forEach { cnt ->
                            FilterChip(
                                selected = sourceTargetCount == cnt,
                                onClick = { sourceTargetCount = cnt },
                                label = { Text("$cnt шт.", fontSize = 11.sp) }
                            )
                        }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
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
                                    targetCount = sourceTargetCount,
                                    enableAiCuration = enableAiAudienceFilter
                                )
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF97316)),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("🚀 Найти НОВЫЕ ($sourceTargetCount ссылок)", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }

                        if (sourceJobState.isRunning) {
                            Button(
                                onClick = { BackgroundCrawlerManager.stopSourceBatchCrawl() },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDC2626))
                            ) {
                                Text("⏹ СТОП", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            }
                        }
                    }

                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (sourceJobState.isRunning) {
                            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp, color = Color(0xFFF97316))
                        }
                        Text(sourceJobState.statusText, color = Color(0xFF93C5FD), fontSize = 11.sp)
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = directUrlInput,
                            onValueChange = { directUrlInput = it },
                            label = { Text("Прямая ссылка YT/VK/Pikabu/Reddit/Rutube") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        Button(
                            onClick = {
                                val u = directUrlInput.trim()
                                if (u.startsWith("http")) {
                                    downloadingCardId = "direct_url"
                                    scope.launch {
                                        try {
                                            val saved = clutterFilter.downloadSourceToStudioInbox(
                                                sourceUrl = u,
                                                overrideTitle = "Ручная ссылка"
                                            )
                                            val vf = clutterFilter.getInboxVideoFile(saved)
                                            downloadingCardId = null
                                            if (vf.exists()) {
                                                directUrlInput = ""
                                                onLoadSourceIntoStudio(vf, saved.title, saved.sourceUrl, 0.0, 0.0)
                                            } else {
                                                onShowMessage("❌ Не удалось скачать видео по ссылке")
                                            }
                                        } catch (e: Exception) {
                                            downloadingCardId = null
                                            onShowMessage("❌ Ошибка загрузки: ${e.message}")
                                        }
                                    }
                                }
                            },
                            enabled = downloadingCardId == null && directUrlInput.startsWith("http")
                        ) {
                            Text("📥", fontSize = 14.sp)
                        }
                        OutlinedButton(onClick = onPickLocalPrimary) {
                            Text("📱", fontSize = 14.sp)
                        }
                    }
                }
            }

            sourceJobState.results.forEach { cand ->
                DiscoveredLinkCard(
                    candidate = cand,
                    isDownloading = downloadingCardId == cand.id,
                    primaryActionLabel = "🎬 В Студию YouCut",
                    onPrimaryAction = {
                        downloadingCardId = cand.id
                        onShowMessage("📥 Скачиваю ролик в Студию YouCut...")
                        scope.launch {
                            try {
                                val saved = clutterFilter.downloadSourceToStudioInbox(
                                    sourceUrl = cand.url,
                                    overrideTitle = cand.title,
                                    overrideAuthor = cand.author,
                                    aiSummary = cand.aiWhatsInside
                                )
                                val vf = clutterFilter.getInboxVideoFile(saved)
                                downloadingCardId = null
                                if (vf.exists()) {
                                    onLoadSourceIntoStudio(vf, saved.title, saved.sourceUrl, 0.0, 0.0)
                                } else {
                                    onShowMessage("❌ Ошибка скачивания ролика")
                                }
                            } catch (e: Exception) {
                                downloadingCardId = null
                                onShowMessage("❌ Ошибка скачивания: ${e.message}")
                            }
                        }
                    },
                    onOpenExternalLink = {
                        try {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(cand.url)))
                        } catch (_: Exception) {
                        }
                    },
                    onBanVideo = {
                        BackgroundCrawlerManager.banCandidateAndPurge(context, cand, banWholeChannel = false)
                        onShowMessage("🚫 Ролик скрыт навсегда")
                    },
                    onBanChannel = {
                        BackgroundCrawlerManager.banCandidateAndPurge(context, cand, banWholeChannel = true)
                        onShowMessage("🚷 Канал «${cand.author}» забанен навсегда")
                    }
                )
            }
        } else {
            // SUB-TAB 2: PURE MONOTONOUS BACKGROUND FILLER HARVESTER (Frame-by-Frame AI Rejection of Cartoons/Guides!)
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF141824)),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "👁 Фоны-наполнители (Покадровый отсев мультиков и гайдов)",
                        color = Color(0xFF6EE7B7),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "Система скачивает 3 кадра из середины каждого видео (25%, 50%, 75%) и через ML Kit + Gemini Vision автоматически выкидывает мультики, сюжетки, гайды, меню и вебки стримеров — оставляя только однотонный геймплей или резку мыла/песка.",
                        color = Color(0xFF9CA3AF),
                        fontSize = 11.sp
                    )

                    Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("Subway Surfers", "CS2 surf", "GTA 5 ramp", "Minecraft parkour", "Резка мыла ASMR", "Кинетический песок").forEach { preset ->
                            FilterChip(
                                selected = bgQuery.equals(preset, ignoreCase = true),
                                onClick = { bgQuery = preset },
                                label = { Text(preset, fontSize = 11.sp) }
                            )
                        }
                    }

                    OutlinedTextField(
                        value = bgQuery,
                        onValueChange = { bgQuery = it },
                        label = { Text("Тема наполнителя (напр. Subway Surfers, резка мыла)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = bgNegInput,
                        onValueChange = {
                            bgNegInput = it
                            SearchBlacklistStore.setBgNegativeTags(context, it)
                        },
                        label = { Text("Минус-слова (против гайдов, мультиков и сюжета)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        if (!bgJobState.isRunning) {
                            Button(
                                onClick = {
                                    BackgroundCrawlerManager.startInfiniteBackgroundCrawl(
                                        context = context,
                                        query = bgQuery,
                                        negativeTags = bgNegInput,
                                        platform = bgPlatform
                                    )
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF059669)),
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("▶️ Искать чистые фоны до СТОПА", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            }
                        } else {
                            Button(
                                onClick = { BackgroundCrawlerManager.stopInfiniteBackgroundCrawl() },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDC2626)),
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("⏹ ОСТАНОВИТЬ ПОИСК ФОНОВ", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            }
                        }
                        OutlinedButton(onClick = onPickLocalBackground) {
                            Text("📱", fontSize = 14.sp)
                        }
                    }

                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (bgJobState.isRunning) {
                            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp, color = Color(0xFF34D399))
                        }
                        Text(bgJobState.statusText, color = Color(0xFFA7F3D0), fontSize = 11.sp)
                    }
                }
            }

            bgJobState.results.forEach { cand ->
                DiscoveredLinkCard(
                    candidate = cand,
                    isDownloading = downloadingCardId == cand.id,
                    primaryActionLabel = "🕹 В пул фонов и Студию",
                    onPrimaryAction = {
                        downloadingCardId = cand.id
                        onShowMessage("📥 Скачиваю чистый фон в твой пул...")
                        scope.launch {
                            try {
                                val clip = clutterFilter.downloadUrlToPoolWithTags(
                                    sourceUrl = cand.url,
                                    rawTagsInput = bgQuery,
                                    customTitle = cand.title.take(60)
                                )
                                downloadingCardId = null
                                if (clip.exists()) {
                                    onLoadBackgroundIntoStudio(clip, cand.title)
                                } else {
                                    onShowMessage("❌ Фон недоступен")
                                }
                            } catch (e: Exception) {
                                downloadingCardId = null
                                onShowMessage("❌ Ошибка скачивания фона: ${e.message}")
                            }
                        }
                    },
                    onOpenExternalLink = {
                        try {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(cand.url)))
                        } catch (_: Exception) {
                        }
                    },
                    onBanVideo = {
                        BackgroundCrawlerManager.banCandidateAndPurge(context, cand, banWholeChannel = false)
                    },
                    onBanChannel = {
                        BackgroundCrawlerManager.banCandidateAndPurge(context, cand, banWholeChannel = true)
                    }
                )
            }
        }
    }
}

@Composable
private fun DiscoveredLinkCard(
    candidate: DiscoveredMediaCandidate,
    isDownloading: Boolean,
    primaryActionLabel: String,
    onPrimaryAction: () -> Unit,
    onOpenExternalLink: () -> Unit,
    onBanVideo: () -> Unit,
    onBanChannel: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF141824)),
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                if (candidate.thumbnailUrl.isNotBlank()) {
                    AsyncImage(
                        model = candidate.thumbnailUrl,
                        contentDescription = candidate.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(width = 96.dp, height = 62.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color.Black)
                            .clickable { onOpenExternalLink() }
                    )
                }

                Column(modifier = Modifier.weight(1f)) {
                    val mins = candidate.durationSec / 60
                    val secs = candidate.durationSec % 60
                    Text(
                        text = "${candidate.platform.badge} • ${mins}:${secs.toString().padStart(2, '0')} • ${candidate.cloutStatusLabel}",
                        color = Color(0xFF93C5FD),
                        fontSize = 10.sp
                    )
                    Text(
                        text = candidate.title,
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "👤 ${candidate.author}" +
                            (if (candidate.publishedAgeLabel.isNotBlank()) " • ${candidate.publishedAgeLabel}" else ""),
                        color = Color(0xFF9CA3AF),
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            if (candidate.aiWhatsInside.isNotBlank()) {
                Text(
                    text = candidate.aiWhatsInside,
                    color = Color(0xFFFDE68A),
                    fontSize = 11.sp
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = onPrimaryAction,
                    enabled = !isDownloading,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF97316)),
                    modifier = Modifier.weight(1f)
                ) {
                    if (isDownloading) {
                        CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp, color = Color.White)
                        Spacer(Modifier.width(6.dp))
                        Text("Качаю...", fontSize = 11.sp)
                    } else {
                        Text(primaryActionLabel, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }

                OutlinedButton(onClick = onOpenExternalLink) {
                    Text("🌐", fontSize = 12.sp)
                }
                OutlinedButton(onClick = onBanVideo) {
                    Text("🚫", fontSize = 12.sp)
                }
                OutlinedButton(onClick = onBanChannel) {
                    Text("🚷", fontSize = 12.sp)
                }
            }
        }
    }
}

// =========================================================================
// TAB 3: CLEAN CREDENTIALS (NO GROQ!) + BLACKLIST + 1500-LINE COPYABLE LOG
// =========================================================================

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SettingsAndLogScreen(
    configStore: NodeConfigStore,
    powerHelper: PowerOptimizationHelper,
    onPickCookiesFile: () -> Unit,
    onShowMessage: (String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var geminiKey by remember { mutableStateOf(configStore.getGeminiApiKey()) }
    var tgToken by remember { mutableStateOf(configStore.getTelegramBotToken()) }
    var chatId by remember { mutableStateOf(configStore.getAllowedChatId()) }

    val bannedAuthors by SearchBlacklistStore.bannedAuthors.collectAsState()
    val bannedVideos by SearchBlacklistStore.bannedVideoKeys.collectAsState()
    val seenCount by SearchBlacklistStore.seenCount.collectAsState()
    val logLines by DiagnosticLogger.logFlow.collectAsState()
    val svcStatus by PipelineForegroundService.statusFlow.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 1. Credentials (ONLY Gemini + Telegram — zero extra keys!)
        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF141824)),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("🔑 Ключи (Никаких лишних регистраций — только Gemini и Бот)", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                Text("Статус сервиса: $svcStatus", color = Color(0xFF6EE7B7), fontSize = 11.sp)

                OutlinedTextField(
                    value = geminiKey,
                    onValueChange = { geminiKey = it },
                    label = { Text("🧠 Gemini API Key (субтитры по волне, ИИ-отсев ЦА и кадров)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = tgToken,
                    onValueChange = { tgToken = it },
                    label = { Text("🤖 Telegram Bot Token (по желанию)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = chatId,
                    onValueChange = { chatId = it },
                    label = { Text("👤 Разрешённый Chat ID (пусто = любой)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Button(
                    onClick = {
                        configStore.setGeminiApiKey(geminiKey)
                        configStore.setTelegramBotToken(tgToken)
                        configStore.setAllowedChatId(chatId)
                        DiagnosticLogger.log("UI", "Ключи Gemini и Telegram сохранены.")
                        PipelineForegroundService.reloadCredentials(context)
                        onShowMessage("✅ Ключи сохранены!")
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF059669)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("💾 Сохранить и перезапустить узел", fontWeight = FontWeight.Bold)
                }
            }
        }

        // 2. Blacklist & Seen History Management
        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF141824)),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "🚷 ЧС и память выдачи (Каналов: ${bannedAuthors.size}, Роликов: ${bannedVideos.size / 2}, Просмотрено: $seenCount)",
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold
                )
                if (bannedAuthors.isNotEmpty()) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        bannedAuthors.forEach { ch ->
                            FilterChip(
                                selected = true,
                                onClick = { SearchBlacklistStore.unbanChannel(context, ch) },
                                label = { Text("✕ $ch", fontSize = 11.sp) }
                            )
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(onClick = { SearchBlacklistStore.clearSeenHistory(context) }, modifier = Modifier.weight(1f)) {
                        Text("🔄 Сброс просмотренных", fontSize = 10.sp)
                    }
                    OutlinedButton(onClick = { SearchBlacklistStore.clearBannedChannels(context) }, modifier = Modifier.weight(1f)) {
                        Text("Разбанить каналы", fontSize = 10.sp)
                    }
                }
            }
        }

        // 3. OnePlus / OxygenOS & yt-dlp tools
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

        // 4. FULL 1500-LINE SCROLLABLE DIAGNOSTIC LOG WITH 1-TAP COPY & SHARE!
        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF0F131D)),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "📜 Полный лог узла (${logLines.size} / 1500 строк)",
                        color = Color(0xFF93C5FD),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                    TextButton(onClick = { DiagnosticLogger.clear(context) }) {
                        Text("Очистить", fontSize = 11.sp, color = Color(0xFFF87171))
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    Button(
                        onClick = {
                            val fullText = DiagnosticLogger.getAllLinesText()
                            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                            cm?.setPrimaryClip(ClipData.newPlainText("PocketMediaForgeLog", fullText))
                            onShowMessage("📋 Весь лог (${logLines.size} строк) скопирован в буфер обмена!")
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2563EB)),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("📋 Скопировать весь лог", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }

                    OutlinedButton(
                        onClick = {
                            val fullText = DiagnosticLogger.getAllLinesText()
                            val sendIntent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_SUBJECT, "Pocket Media Forge Log")
                                putExtra(Intent.EXTRA_TEXT, fullText)
                            }
                            try {
                                context.startActivity(Intent.createChooser(sendIntent, "Отправить лог"))
                            } catch (_: Exception) {
                            }
                        }
                    ) {
                        Text("📤 Поделиться", fontSize = 12.sp)
                    }
                }

                val logScroll = rememberScrollState()
                LaunchedEffect(logLines.size) {
                    logScroll.scrollTo(logScroll.maxValue)
                }

                Surface(
                    color = Color(0xFF07090E),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 180.dp, max = 380.dp)
                ) {
                    SelectionContainer {
                        Text(
                            text = logLines.joinToString("\n").ifBlank { "Лог пуст." },
                            color = Color(0xFFD1D5DB),
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier
                                .verticalScroll(logScroll)
                                .horizontalScroll(rememberScrollState())
                                .padding(8.dp)
                        )
                    }
                }
            }
        }
    }
}
