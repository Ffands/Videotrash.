package forge.media.pocket.ui

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import forge.media.pocket.clutter.ClutterEntropyFilter
import forge.media.pocket.compositor.AntiDetectDspLayerConfig
import forge.media.pocket.compositor.AudioStemRoutingMode
import forge.media.pocket.compositor.FFmpegHardwareEngine
import forge.media.pocket.compositor.ModularRenderConfig
import forge.media.pocket.compositor.MusicRemovalMode
import forge.media.pocket.compositor.SeparatedAudioStems
import forge.media.pocket.compositor.VideoLayoutLayer
import forge.media.pocket.compositor.VoiceoverDubMode
import forge.media.pocket.subtitles.EditableSubtitlePhrase
import forge.media.pocket.subtitles.SubtitleAssTransformer
import forge.media.pocket.subtitles.SubtitleDensityMode
import forge.media.pocket.subtitles.SubtitleLayerConfig
import forge.media.pocket.subtitles.SubtitlePlacementZone
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
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * Цех 2 («🎛 Редактор-Сборщик»):
 * Финальная сборка вертикального 9:16 ролика из подготовленного исходника и фонового наполнителя:
 * - Двухслойный 9:16 предпросмотр в реальном времени (Верх + Низ) с перетаскиваемым швом;
 * - Авто-синхронизация субтитров по звуковой волне (FFmpeg silencedetect + Gemini Audio, без токена Groq)
 *   + пословный и пофразовый редактор караоке-таймингов;
 * - Перетаскивание плашки субтитров пальцем прямо по живому 9:16 холсту;
 * - Микшер громкости всех дорожек, Анти-Детект фильтрация и прямой рендер FullHD 1080p в Галерею.
 */
@androidx.annotation.OptIn(UnstableApi::class)
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ForgeAssemblerScreen(
    primaryFile: File?,
    primaryTitle: String,
    primarySourceUrl: String,
    backgroundFile: File?,
    backgroundTitle: String,
    preparedPayload: PreparedWorkbenchPayload?,
    clutterFilter: ClutterEntropyFilter,
    hardwareEngine: FFmpegHardwareEngine,
    faceHelper: FaceCenteringHelper,
    subtitleTransformer: SubtitleAssTransformer,
    onSelectBackgroundFile: (File?, String) -> Unit,
    onPickBackgroundFromPhone: () -> Unit,
    onJumpToPreEditor: () -> Unit,
    onJumpToBackgroundSearch: () -> Unit,
    onShowMessage: (String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current

    var showBgPoolDialog by remember { mutableStateOf(false) }
    var previewRenderedFile by remember { mutableStateOf<File?>(null) }

    val activeSourceFile = preparedPayload?.bakedVideoFile?.takeIf { it.exists() } ?: primaryFile

    // Layout & Split Seam
    var layoutLayer by remember { mutableStateOf(VideoLayoutLayer.SPLIT_VSTACK) }
    var splitTopRatio by remember { mutableFloatStateOf(0.52f) }
    var enableSmartFaceCrop by remember { mutableStateOf(true) }

    // Subtitles (Groq-free Speech Islands + Gemini Audio)
    var subtitlesEnabled by remember { mutableStateOf(true) }
    var editablePhrases by remember(activeSourceFile?.absolutePath) { mutableStateOf<List<EditableSubtitlePhrase>>(emptyList()) }
    var sttStatusNote by remember(activeSourceFile?.absolutePath) {
        mutableStateOf("Нажми «✨ Сгенерировать субтитры» — тайминги сведутся по голосу без Groq")
    }
    var isTranscribing by remember { mutableStateOf(false) }
    var selectedPhraseId by remember { mutableStateOf<String?>(null) }
    var wordsPerPhrase by remember { mutableIntStateOf(4) }
    var subtitleFontSizePx by remember { mutableIntStateOf(68) }
    var subtitleCustomY by remember { mutableIntStateOf(960) } // 0..1920
    var subtitleSyncOffsetMs by remember { mutableLongStateOf(0L) }

    // Audio Mixer & Anti-Detect
    var mainVolume by remember { mutableFloatStateOf(1.0f) }
    var bgVolume by remember { mutableFloatStateOf(0.0f) }
    var voiceoverVolume by remember { mutableFloatStateOf(1.25f) }
    var musicVolume by remember { mutableFloatStateOf(0.25f) }

    var antiDetectEnabled by remember { mutableStateOf(true) }
    var enableMicroZoom by remember { mutableStateOf(true) }
    var enableHorizontalFlip by remember { mutableStateOf(false) }
    var enableNoiseInjection by remember { mutableStateOf(true) }
    var enablePitchJitter by remember { mutableStateOf(true) }
    var enableSilenceTrim by remember { mutableStateOf(false) }

    var isRendering by remember { mutableStateOf(false) }
    var renderStatusText by remember { mutableStateOf("") }

    var totalDurSec by remember(activeSourceFile?.absolutePath) { mutableDoubleStateOf(15.0) }
    var currentPosSec by remember { mutableDoubleStateOf(0.0) }
    var isPlaying by remember { mutableStateOf(true) }

    // Synchronized Dual ExoPlayers (Upper Primary + Lower Background Filler)
    val mainPlayer = remember {
        ExoPlayer.Builder(context).build().apply {
            repeatMode = Player.REPEAT_MODE_ONE
            playWhenReady = true
        }
    }
    val bgPlayer = remember {
        ExoPlayer.Builder(context).build().apply {
            repeatMode = Player.REPEAT_MODE_ALL
            volume = 0f
            playWhenReady = true
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            try {
                mainPlayer.release()
                bgPlayer.release()
            } catch (_: Exception) {
            }
        }
    }

    LaunchedEffect(activeSourceFile?.absolutePath) {
        val f = activeSourceFile
        if (f != null && f.exists()) {
            val dur = withContext(Dispatchers.IO) {
                hardwareEngine.probeDurationSeconds(f).coerceAtLeast(1.0)
            }
            totalDurSec = dur
            mainPlayer.setMediaItem(MediaItem.fromUri(Uri.fromFile(f)))
            mainPlayer.prepare()
            mainPlayer.playWhenReady = true
            isPlaying = true
        } else {
            mainPlayer.clearMediaItems()
        }
    }

    LaunchedEffect(backgroundFile?.absolutePath) {
        val bf = backgroundFile
        if (bf != null && bf.exists()) {
            bgPlayer.setMediaItem(MediaItem.fromUri(Uri.fromFile(bf)))
            bgPlayer.prepare()
            bgPlayer.volume = bgVolume.coerceIn(0f, 1f)
            bgPlayer.playWhenReady = mainPlayer.isPlaying
        } else {
            bgPlayer.clearMediaItems()
        }
    }

    LaunchedEffect(mainVolume, bgVolume) {
        mainPlayer.volume = mainVolume.coerceIn(0f, 1f)
        bgPlayer.volume = bgVolume.coerceIn(0f, 1f)
    }

    LaunchedEffect(mainPlayer, activeSourceFile?.absolutePath) {
        while (isActive) {
            if (activeSourceFile != null && mainPlayer.duration > 0) {
                currentPosSec = mainPlayer.currentPosition / 1000.0
                isPlaying = mainPlayer.isPlaying
                if (backgroundFile != null && bgPlayer.playbackState == Player.STATE_READY) {
                    if (mainPlayer.isPlaying && !bgPlayer.isPlaying) bgPlayer.play()
                    if (!mainPlayer.isPlaying && bgPlayer.isPlaying) bgPlayer.pause()
                }
            }
            delay(40L)
        }
    }

    // Active karaoke phrase at current playhead
    val activePhraseAtPlayhead = remember(editablePhrases, currentPosSec, subtitleSyncOffsetMs) {
        val shiftedSec = currentPosSec - (subtitleSyncOffsetMs / 1000.0)
        editablePhrases.firstOrNull { shiftedSec >= it.startSec - 0.04 && shiftedSec <= it.endSec + 0.08 }
    }

    suspend fun executeRenderPass(isPreview5s: Boolean) {
        val srcFile = activeSourceFile
        if (srcFile == null || !srcFile.exists()) {
            onShowMessage("⚠️ Сначала выбери или разделай исходное видео!")
            return
        }
        isRendering = true
        renderStatusText = if (isPreview5s) "Быстрый рендер 5-сек пробы..." else "Полный рендер 1080p (Аппаратный кодек + Выравнивание лица)..."
        try {
            mainPlayer.pause()
            bgPlayer.pause()
            isPlaying = false

            val framingPlan = withContext(Dispatchers.IO) {
                faceHelper.computeFramingPlan(
                    videoFile = srcFile,
                    enableSmartFaceCrop = enableSmartFaceCrop,
                    hardsubRegion = preparedPayload?.detectedHardsubRegion,
                    hardsubRemovalMode = preparedPayload?.existingSubtitleRemovalMode ?: ExistingSubtitleRemovalMode.OFF
                )
            }

            val subLayerCfg = SubtitleLayerConfig(
                enabled = subtitlesEnabled,
                densityMode = if (wordsPerPhrase <= 4) SubtitleDensityMode.PUNCH_3_TO_4_WORDS else SubtitleDensityMode.CLASSIC_6_TO_8_WORDS,
                placementZone = SubtitlePlacementZone.CUSTOM_XY,
                customX = 540,
                customY = subtitleCustomY,
                fontSizePx = subtitleFontSizePx,
                syncOffsetMs = subtitleSyncOffsetMs
            )

            val assFile = withContext(Dispatchers.IO) {
                if (!subtitlesEnabled) {
                    null
                } else if (editablePhrases.isNotEmpty()) {
                    subtitleTransformer.compileEditablePhrasesToAssFile(
                        phrases = editablePhrases,
                        layerConfig = subLayerCfg,
                        framingPlan = framingPlan,
                        canvasWidth = 1080,
                        canvasHeight = 1920
                    )
                } else {
                    subtitleTransformer.prepareAssSubtitles(
                        primaryVideoFile = srcFile,
                        sourceUrl = primarySourceUrl,
                        framingPlan = framingPlan,
                        layerConfig = subLayerCfg,
                        customVoiceoverScript = null,
                        startOffsetSec = 0.0,
                        clipDurationSec = totalDurSec
                    ).assFile
                }
            }

            val renderCfg = ModularRenderConfig(
                layoutLayer = layoutLayer,
                splitTopRatio = splitTopRatio,
                playbackSpeed = 1.0f,
                mainVolume = mainVolume,
                bgVolume = bgVolume,
                voiceoverVolume = voiceoverVolume,
                musicVolume = musicVolume,
                enableSmartFaceCrop = enableSmartFaceCrop,
                trimStartSec = 0.0,
                trimEndSec = totalDurSec,
                customVoiceoverAudioFile = preparedPayload?.customVoiceoverFile,
                customReplacementMusicFile = preparedPayload?.replacementMusicFile,
                enableReplacementMusicBed = preparedPayload?.replacementMusicFile != null,
                separatedStems = preparedPayload?.separatedStems,
                existingSubtitleRemovalMode = preparedPayload?.existingSubtitleRemovalMode ?: ExistingSubtitleRemovalMode.OFF,
                detectedHardsubRegion = preparedPayload?.detectedHardsubRegion,
                subtitleLayer = subLayerCfg,
                dspLayer = AntiDetectDspLayerConfig(
                    enabled = antiDetectEnabled,
                    enableMicroZoom = enableMicroZoom,
                    enableHorizontalFlip = enableHorizontalFlip,
                    enableVideoNoiseInjection = enableNoiseInjection,
                    enableAudioPitchTempoMicroJitter = enablePitchJitter,
                    enableSilenceTrimming350ms = enableSilenceTrim,
                    musicRemovalMode = preparedPayload?.musicRemovalMode ?: MusicRemovalMode.OFF,
                    voiceoverDubMode = preparedPayload?.voiceoverDubMode ?: VoiceoverDubMode.OFF,
                    stemRoutingMode = preparedPayload?.stemRoutingMode ?: AudioStemRoutingMode.FULL_ORIGINAL_MIX
                )
            )

            val out = hardwareEngine.renderPipeline(
                primaryVideoFile = srcFile,
                clutterReelFile = backgroundFile,
                framingPlan = framingPlan,
                assSubtitleFile = assFile,
                config = renderCfg,
                isPreviewPass = isPreview5s
            )

            if (isPreview5s) {
                previewRenderedFile = out
                onShowMessage("👁 5-секундная проба готова!")
            } else {
                val saved = hardwareEngine.saveRenderedVideoToGallery(out, primaryTitle.ifBlank { "ForgeShort" })
                onShowMessage("🎉 Готовый ролик 1080p сохранён в Галерею: ${saved.name}")
            }
        } catch (e: Exception) {
            onShowMessage("❌ Ошибка рендера: ${e.message}")
        } finally {
            isRendering = false
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0A0C10))
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 1. TOP SLOTS SUMMARY (Track 1: Source + Track 2: Background Filler)
        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF141824)),
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "🎛 Цех 2: Редактор-Сборщик (Вертикальный холст 9:16 + Караоке)",
                    color = Color(0xFF38BDF8),
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Slot 1: Main Video
                    Surface(
                        color = Color(0xFF1E293B),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .weight(1f)
                            .clickable { onJumpToPreEditor() }
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Text("🎬 Верх (Исходник):", color = Color(0xFFF97316), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            Text(
                                text = activeSourceFile?.let { primaryTitle.ifBlank { it.name } } ?: "Не выбран (Открой Разделку)",
                                color = Color.White,
                                fontSize = 12.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text("✂️ Изменить в Разделке", color = Color(0xFF94A3B8), fontSize = 10.sp)
                        }
                    }

                    // Slot 2: Background Filler
                    Surface(
                        color = Color(0xFF1E293B),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .weight(1f)
                            .clickable { showBgPoolDialog = true }
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Text("🕹 Низ (Фон-залипалка):", color = Color(0xFF38BDF8), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            Text(
                                text = backgroundFile?.let { backgroundTitle.ifBlank { it.nameWithoutExtension } } ?: "Не выбран (Нажми для выбора)",
                                color = Color.White,
                                fontSize = 12.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text("📂 Выбрать из пула / телефона", color = Color(0xFF94A3B8), fontSize = 10.sp)
                        }
                    }
                }
            }
        }

        // 2. LIVE 9:16 COMPOSITE CANVAS (Dual ExoPlayers + Draggable Split Seam + Draggable Karaoke Subtitles)
        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF141824)),
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Layout template chips
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    VideoLayoutLayer.entries.forEach { layer ->
                        FilterChip(
                            selected = layoutLayer == layer,
                            onClick = { layoutLayer = layer },
                            label = { Text(layer.label.substringBefore(" ("), fontSize = 11.sp) }
                        )
                    }
                }

                var canvasSize by remember { mutableStateOf(IntSize.Zero) }

                Box(
                    modifier = Modifier
                        .size(width = 236.dp, height = 420.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color.Black)
                        .border(1.dp, Color(0xFF334155), RoundedCornerShape(14.dp))
                        .onSizeChanged { canvasSize = it }
                ) {
                    if (layoutLayer == VideoLayoutLayer.SPLIT_VSTACK) {
                        Column(modifier = Modifier.fillMaxSize()) {
                            // Upper Primary Video
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .fillMaxHeight(splitTopRatio.coerceIn(0.28f, 0.75f))
                                    .background(Color.Black)
                            ) {
                                AndroidView(
                                    factory = { ctx ->
                                        PlayerView(ctx).apply {
                                            player = mainPlayer
                                            useController = false
                                            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                                        }
                                    },
                                    update = { pv -> pv.player = mainPlayer },
                                    modifier = Modifier.fillMaxSize()
                                )
                            }

                            // Lower Background Video
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f)
                                    .background(Color(0xFF090D16))
                            ) {
                                if (backgroundFile != null && backgroundFile.exists()) {
                                    AndroidView(
                                        factory = { ctx ->
                                            PlayerView(ctx).apply {
                                                player = bgPlayer
                                                useController = false
                                                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                                            }
                                        },
                                        update = { pv -> pv.player = bgPlayer },
                                        modifier = Modifier.fillMaxSize()
                                    )
                                } else {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .clickable { showBgPoolDialog = true }
                                            .padding(12.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.Center
                                    ) {
                                        Text("🕹 Нажми, чтобы выбрать фон", color = Color(0xFF38BDF8), fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                        Text("(Пул фонов / С телефона / Поиск)", color = Color(0xFF94A3B8), fontSize = 10.sp)
                                    }
                                }
                            }
                        }

                        // Interactive Draggable Split Seam Handle
                        if (canvasSize.height > 20) {
                            val seamY = (splitTopRatio * canvasSize.height).roundToInt()
                            Box(
                                modifier = Modifier
                                    .offset { IntOffset(0, (seamY - with(density) { 12.dp.toPx() }).roundToInt()) }
                                    .fillMaxWidth()
                                    .height(24.dp)
                                    .pointerInput(canvasSize.height) {
                                        detectVerticalDragGestures { change, dragAmount ->
                                            change.consume()
                                            splitTopRatio = (splitTopRatio + dragAmount / canvasSize.height.toFloat())
                                                .coerceIn(0.28f, 0.75f)
                                        }
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(2.dp)
                                        .background(Color(0xFFF97316))
                                )
                                Surface(
                                    color = Color(0xFFF97316),
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Text(
                                        text = "⬍ Шов ${(splitTopRatio * 100).roundToInt()}%",
                                        color = Color.White,
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }
                        }
                    } else {
                        // Single Fullscreen 9:16 or Blurred Frame mode
                        AndroidView(
                            factory = { ctx ->
                                PlayerView(ctx).apply {
                                    player = if (layoutLayer == VideoLayoutLayer.BRAINROT_VOICEOVER && backgroundFile != null) bgPlayer else mainPlayer
                                    useController = false
                                    resizeMode = if (layoutLayer == VideoLayoutLayer.FULLSCREEN_EDIT || layoutLayer == VideoLayoutLayer.BRAINROT_VOICEOVER) {
                                        AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                                    } else {
                                        AspectRatioFrameLayout.RESIZE_MODE_FIT
                                    }
                                }
                            },
                            update = { pv ->
                                pv.player = if (layoutLayer == VideoLayoutLayer.BRAINROT_VOICEOVER && backgroundFile != null) bgPlayer else mainPlayer
                            },
                            modifier = Modifier.fillMaxSize()
                        )
                    }

                    // Live Draggable Karaoke Subtitle Overlay on the 9:16 Canvas
                    if (subtitlesEnabled && canvasSize.height > 20) {
                        val subNormY = (subtitleCustomY / 1920f).coerceIn(0.08f, 0.90f)
                        val subPxY = (subNormY * canvasSize.height).roundToInt()
                        val shiftedSec = currentPosSec - (subtitleSyncOffsetMs / 1000.0)

                        Box(
                            modifier = Modifier
                                .offset { IntOffset(0, (subPxY - with(density) { 22.dp.toPx() }).roundToInt()) }
                                .fillMaxWidth()
                                .padding(horizontal = 10.dp)
                                .pointerInput(canvasSize.height) {
                                    detectVerticalDragGestures { change, dragAmount ->
                                        change.consume()
                                        val delta1920 = (dragAmount / canvasSize.height.toFloat()) * 1920f
                                        subtitleCustomY = (subtitleCustomY + delta1920.roundToInt()).coerceIn(140, 1780)
                                    }
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Surface(
                                color = Color(0xBB000000),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                val phrase = activePhraseAtPlayhead
                                if (phrase != null && phrase.words.isNotEmpty()) {
                                    Text(
                                        text = buildAnnotatedString {
                                            phrase.words.forEachIndexed { idx, w ->
                                                val isActiveWord = shiftedSec >= w.startSec - 0.03 && shiftedSec <= w.endSec + 0.05
                                                withStyle(
                                                    SpanStyle(
                                                        color = if (isActiveWord) Color(0xFFFACC15) else Color.White,
                                                        fontWeight = FontWeight.ExtraBold
                                                    )
                                                ) {
                                                    append(w.text.uppercase(Locale.getDefault()))
                                                }
                                                if (idx < phrase.words.lastIndex) append(" ")
                                            }
                                        },
                                        fontSize = (subtitleFontSizePx / 5.0f).coerceIn(11f, 18f).sp,
                                        textAlign = TextAlign.Center,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                    )
                                } else {
                                    Text(
                                        text = "💬 СУБТИТРЫ (ТЯНИ ВВЕРХ/ВНИЗ Y=$subtitleCustomY)",
                                        color = Color(0xFFFACC15),
                                        fontWeight = FontWeight.ExtraBold,
                                        fontSize = 11.sp,
                                        textAlign = TextAlign.Center,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                // Player transport bar
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = {
                            if (mainPlayer.isPlaying) {
                                mainPlayer.pause()
                                bgPlayer.pause()
                                isPlaying = false
                            } else {
                                mainPlayer.play()
                                bgPlayer.play()
                                isPlaying = true
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E293B))
                    ) {
                        Text(if (isPlaying) "⏸" else "▶️")
                    }

                    Slider(
                        value = currentPosSec.toFloat().coerceIn(0f, totalDurSec.toFloat().coerceAtLeast(1f)),
                        onValueChange = { v ->
                            currentPosSec = v.toDouble()
                            mainPlayer.seekTo((v * 1000).roundToLong())
                        },
                        valueRange = 0f..totalDurSec.toFloat().coerceAtLeast(1f),
                        modifier = Modifier.weight(1f)
                    )

                    Text(
                        text = "${String.format(Locale.US, "%.1f", currentPosSec)}/${String.format(Locale.US, "%.1fс", totalDurSec)}",
                        color = Color(0xFFCBD5E1),
                        fontSize = 11.sp
                    )
                }
            }
        }

        // 3. KARAOKE SUBTITLES WORKBENCH (FFmpeg silencedetect + Gemini Audio — NO GROQ!)
        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF141824)),
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "💬 Субтитры по звуковой волне (Без Groq)",
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 14.sp
                    )
                    Switch(checked = subtitlesEnabled, onCheckedChange = { subtitlesEnabled = it })
                }

                if (subtitlesEnabled) {
                    Text(sttStatusNote, color = Color(0xFF94A3B8), fontSize = 12.sp)

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            enabled = !isTranscribing && activeSourceFile != null,
                            onClick = {
                                val src = activeSourceFile ?: return@Button
                                scope.launch {
                                    isTranscribing = true
                                    sttStatusNote = "⏳ Анализирую паузы голоса (FFmpeg silencedetect + Gemini Audio)..."
                                    try {
                                        val (phrases, note) = subtitleTransformer.transcribePreparedClipToEditablePhrases(
                                            preparedVideoFile = src,
                                            sourceUrl = primarySourceUrl,
                                            wordsPerPhrase = wordsPerPhrase,
                                            separatedVocalStem = preparedPayload?.separatedStems?.vocalsOverlayFile
                                        )
                                        editablePhrases = phrases
                                        selectedPhraseId = phrases.firstOrNull()?.id
                                        sttStatusNote = note
                                        onShowMessage("✅ Готово фраз: ${phrases.size}")
                                    } catch (e: Exception) {
                                        sttStatusNote = "❌ Ошибка распознавания: ${e.message}"
                                    } finally {
                                        isTranscribing = false
                                    }
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF97316)),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(
                                if (isTranscribing) "⏳ Считаю тайминги..." else "✨ Распознать речь и свести в тайминги",
                                fontSize = 12.sp
                            )
                        }
                    }

                    // Font size, words per phrase & sync offset
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        FilterChip(
                            selected = wordsPerPhrase <= 4,
                            onClick = { wordsPerPhrase = 4 },
                            label = { Text("По 3–4 слова", fontSize = 11.sp) }
                        )
                        FilterChip(
                            selected = wordsPerPhrase > 4,
                            onClick = { wordsPerPhrase = 7 },
                            label = { Text("По 6–7 слов", fontSize = 11.sp) }
                        )
                        OutlinedButton(onClick = { subtitleSyncOffsetMs -= 100L }) {
                            Text("-0.1с", fontSize = 11.sp)
                        }
                        Text("${subtitleSyncOffsetMs}мс", color = Color.White, fontSize = 11.sp)
                        OutlinedButton(onClick = { subtitleSyncOffsetMs += 100L }) {
                            Text("+0.1с", fontSize = 11.sp)
                        }
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Размер шрифта (${subtitleFontSizePx}px):", color = Color(0xFFCBD5E1), fontSize = 12.sp)
                        Slider(
                            value = subtitleFontSizePx.toFloat(),
                            onValueChange = { subtitleFontSizePx = it.roundToInt() },
                            valueRange = 46f..96f,
                            modifier = Modifier.weight(1f)
                        )
                    }

                    // Editable phrase list
                    if (editablePhrases.isNotEmpty()) {
                        Text(
                            "Нажми на фразу для правки текста и тайминга (${editablePhrases.size} фраз):",
                            color = Color(0xFF38BDF8),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            editablePhrases.forEachIndexed { idx, p ->
                                val isSel = p.id == selectedPhraseId
                                Surface(
                                    color = if (isSel) Color(0xFF1E3A8A) else Color(0xFF1E293B),
                                    shape = RoundedCornerShape(10.dp),
                                    modifier = Modifier
                                        .border(
                                            width = if (isSel) 2.dp else 1.dp,
                                            color = if (isSel) Color(0xFFFACC15) else Color(0xFF334155),
                                            shape = RoundedCornerShape(10.dp)
                                        )
                                        .clickable {
                                            selectedPhraseId = p.id
                                            mainPlayer.seekTo((p.startSec * 1000.0).roundToLong())
                                        }
                                ) {
                                    Column(modifier = Modifier.padding(10.dp)) {
                                        Text(
                                            "#${idx + 1} (${String.format(Locale.US, "%.1f..%.1fс", p.startSec, p.endSec)})",
                                            color = Color(0xFFFACC15),
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Text(
                                            p.joinedText,
                                            color = Color.White,
                                            fontSize = 12.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }
                        }

                        val selPhrase = editablePhrases.firstOrNull { it.id == selectedPhraseId }
                        if (selPhrase != null) {
                            var phraseTextDraft by remember(selPhrase.id, selPhrase.joinedText) {
                                mutableStateOf(selPhrase.joinedText)
                            }
                            OutlinedTextField(
                                value = phraseTextDraft,
                                onValueChange = { newTxt ->
                                    phraseTextDraft = newTxt
                                    editablePhrases = editablePhrases.map {
                                        if (it.id == selPhrase.id) it.withReplacedPhraseText(newTxt) else it
                                    }
                                },
                                label = { Text("Текст выбранной фразы (${String.format(Locale.US, "%.2f..%.2fс", selPhrase.startSec, selPhrase.endSec)})") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true
                            )
                        }
                    }
                }
            }
        }

        // 4. AUDIO MIXER & ANTI-DETECT DSP
        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF141824)),
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("🔊 Микшер громкости и 🛡 Анти-Детект", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Голос/Исходник ${(mainVolume * 100).roundToInt()}%", color = Color(0xFFCBD5E1), fontSize = 12.sp, modifier = Modifier.weight(0.45f))
                    Slider(value = mainVolume, onValueChange = { mainVolume = it }, valueRange = 0f..2.0f, modifier = Modifier.weight(0.55f))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Звук фона ${(bgVolume * 100).roundToInt()}%", color = Color(0xFFCBD5E1), fontSize = 12.sp, modifier = Modifier.weight(0.45f))
                    Slider(value = bgVolume, onValueChange = { bgVolume = it }, valueRange = 0f..1.0f, modifier = Modifier.weight(0.55f))
                }

                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(
                        selected = enableSmartFaceCrop,
                        onClick = { enableSmartFaceCrop = !enableSmartFaceCrop },
                        label = { Text("🎯 Авто-центровка лиц", fontSize = 11.sp) }
                    )
                    FilterChip(
                        selected = antiDetectEnabled,
                        onClick = { antiDetectEnabled = !antiDetectEnabled },
                        label = { Text("🛡 Анти-Детект: ${if (antiDetectEnabled) "ВКЛ" else "ВЫКЛ"}", fontSize = 11.sp) }
                    )
                    if (antiDetectEnabled) {
                        FilterChip(
                            selected = enableMicroZoom,
                            onClick = { enableMicroZoom = !enableMicroZoom },
                            label = { Text("Микро-зум", fontSize = 11.sp) }
                        )
                        FilterChip(
                            selected = enableHorizontalFlip,
                            onClick = { enableHorizontalFlip = !enableHorizontalFlip },
                            label = { Text("Отзеркалить", fontSize = 11.sp) }
                        )
                        FilterChip(
                            selected = enablePitchJitter,
                            onClick = { enablePitchJitter = !enablePitchJitter },
                            label = { Text("Анти-Хэш Голоса", fontSize = 11.sp) }
                        )
                    }
                }
            }
        }

        // 5. EXPORT BAR (5s Preview + Full 1080p Render to Gallery)
        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF141824)),
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (isRendering) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), color = Color(0xFFF97316))
                        Text(renderStatusText, color = Color.White, fontSize = 13.sp)
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        enabled = !isRendering && activeSourceFile != null,
                        onClick = { scope.launch { executeRenderPass(isPreview5s = true) } },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("👁 Проба 5 сек", fontSize = 12.sp)
                    }

                    Button(
                        enabled = !isRendering && activeSourceFile != null,
                        onClick = { scope.launch { executeRenderPass(isPreview5s = false) } },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF97316)),
                        modifier = Modifier.weight(1.5f)
                    ) {
                        Text("💾 Собрать 1080p в Галерею", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    }
                }
            }
        }
    }

    if (showBgPoolDialog) {
        val poolFiles = remember { clutterFilter.listVerifiedPoolClips() }
        val poolMeta = remember { clutterFilter.listPoolClipsMetadata().associateBy { it.fileName } }
        AlertDialog(
            onDismissRequest = { showBgPoolDialog = false },
            title = { Text("🕹 Выбери фоновый наполнитель (${poolFiles.size})") },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                showBgPoolDialog = false
                                onPickBackgroundFromPhone()
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0284C7)),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("📱 С телефона", fontSize = 12.sp)
                        }
                        OutlinedButton(
                            onClick = {
                                showBgPoolDialog = false
                                onJumpToBackgroundSearch()
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("🔎 Найти в Поиске", fontSize = 12.sp)
                        }
                    }

                    if (poolFiles.isEmpty()) {
                        Text("Пул фонов пока пуст. Скачай фон из вкладки «Поиск -> Фоны-наполнители» или выбери видео с телефона.")
                    } else {
                        poolFiles.forEach { f ->
                            val meta = poolMeta[f.name]
                            val label = meta?.title?.takeIf { it.isNotBlank() } ?: f.nameWithoutExtension
                            Card(
                                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        onSelectBackgroundFile(f, label)
                                        showBgPoolDialog = false
                                    }
                            ) {
                                Column(modifier = Modifier.padding(10.dp)) {
                                    Text(label, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                    Text("${f.length() / 1024} КБ • Готов к подстановке вниз", color = Color(0xFF94A3B8), fontSize = 11.sp)
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showBgPoolDialog = false }) {
                    Text("Закрыть")
                }
            }
        )
    }

    val previewModal = previewRenderedFile
    if (previewModal != null && previewModal.exists()) {
        AlertDialog(
            onDismissRequest = { previewRenderedFile = null },
            title = { Text("👁 5-секундная проба рендера") },
            text = {
                val modalPlayer = remember(previewModal.absolutePath) {
                    ExoPlayer.Builder(context).build().apply {
                        setMediaItem(MediaItem.fromUri(Uri.fromFile(previewModal)))
                        repeatMode = Player.REPEAT_MODE_ONE
                        prepare()
                        playWhenReady = true
                    }
                }
                DisposableEffect(previewModal.absolutePath) {
                    onDispose {
                        try {
                            modalPlayer.release()
                        } catch (_: Exception) {
                        }
                    }
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(340.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color.Black)
                ) {
                    AndroidView(
                        factory = { ctx ->
                            PlayerView(ctx).apply {
                                player = modalPlayer
                                useController = true
                            }
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { previewRenderedFile = null }) {
                    Text("Закрыть")
                }
            }
        )
    }
}
