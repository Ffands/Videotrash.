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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
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
import forge.media.pocket.subtitles.EditableTimedWord
import forge.media.pocket.subtitles.MusicShazamMatchResult
import forge.media.pocket.subtitles.SubtitleAssTransformer
import forge.media.pocket.subtitles.SubtitleDensityMode
import forge.media.pocket.subtitles.SubtitleLayerConfig
import forge.media.pocket.subtitles.SubtitlePlacementZone
import forge.media.pocket.subtitles.TtsVoiceProfile
import forge.media.pocket.vision.ExistingSubtitleRemovalMode
import forge.media.pocket.vision.FaceCenteringHelper
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.math.roundToLong

private enum class AssemblerToolDrawer(val label: String) {
    SUBTITLES_WHISPER("💬 Субтитры (Whisper)"),
    AUDIO_TRACKS("🎚 Звук и Дорожки"),
    VOICEOVER_TTS("🎙 Озвучка (TTS)"),
    ANTIDETECT_EXPORT("🛡 Сборка и Экспорт")
}

/**
 * Цех 2 («Главный Редактор-Сборщик»):
 * Сюда попадают уже подготовленные клипы:
 * - Слот 1: Основное видео (база)
 * - Слот 2: Фоновое видео из пула фонов
 * Инструменты самого Сборщика:
 * - Живой двухслойный синхронный 9:16 предпросмотр (2 ExoPlayer) с перетаскиваемым швом сплита
 * - Распознавание речи по звуку базы через Groq Whisper Large-v3-Turbo + пословный редактор таймкодов + 60fps караоке поверх видео
 * - 4-канальный микшер громкости, 3-дорожечный сплиттер звука, AI-Shazam подмена музыки
 * - Переозвучка TTS (перевод/рерайт речи + синтез голоса)
 * - Авто-замена чужого шва 50/50, удаление старых субтитров, Анти-Детект и FullHD 1080p экспорт в Галерею!
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
    clutterFilter: ClutterEntropyFilter,
    hardwareEngine: FFmpegHardwareEngine,
    faceHelper: FaceCenteringHelper,
    subtitleTransformer: SubtitleAssTransformer,
    onPickPrimarySourceRequested: () -> Unit,
    onSendClipToPreEditor: (file: File, title: String, role: PreEditTargetRole) -> Unit,
    onSelectBackgroundDirectly: (file: File, title: String) -> Unit,
    onImportBackgroundFromGalleryRequested: () -> Unit,
    onJumpToBackgroundSearchRequested: () -> Unit,
    onImportCustomVoiceoverRequested: () -> Unit,
    importedCustomVoiceoverFile: File?,
    onShowMessage: (String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current

    var showBgPickerModal by remember { mutableStateOf(false) }
    var pendingBgChoicePair by remember { mutableStateOf<Pair<File, String>?>(null) }
    var renderedPreviewModalFile by remember { mutableStateOf<File?>(null) }

    var activeDrawer by remember { mutableStateOf(AssemblerToolDrawer.SUBTITLES_WHISPER) }

    // Layout & Seam state
    var layoutLayer by remember { mutableStateOf(VideoLayoutLayer.SPLIT_VSTACK) }
    var splitTopRatio by remember { mutableFloatStateOf(0.50f) }
    var enableSmartFaceAndSeamCrop by remember { mutableStateOf(true) }
    var oldSubRemovalMode by remember { mutableStateOf(ExistingSubtitleRemovalMode.OFF) }

    // Subtitles & Groq Whisper Editable Phrases state
    var subtitlesEnabled by remember { mutableStateOf(true) }
    var editablePhrases by remember(primaryFile?.absolutePath) {
        mutableStateOf<List<EditableSubtitlePhrase>>(emptyList())
    }
    var whisperStatusNote by remember(primaryFile?.absolutePath) {
        mutableStateOf("Нажми «🎙 Распознать речь», чтобы вытянуть точные слова и тайминги из звука базы")
    }
    var isTranscribingWhisper by remember { mutableStateOf(false) }
    var expandedPhraseId by remember { mutableStateOf<String?>(null) }
    var wordsPerPhraseSetting by remember { mutableIntStateOf(4) }
    var subtitleFontSizePx by remember { mutableIntStateOf(68) }
    var subtitleCustomY by remember { mutableIntStateOf(960) } // 0..1920 canvas Y
    var subtitleSyncOffsetMs by remember { mutableLongStateOf(0L) }

    // Audio & Stems & AI-Shazam state
    var mainVolume by remember { mutableFloatStateOf(1.0f) }
    var bgVolume by remember { mutableFloatStateOf(0.0f) }
    var voiceoverVolume by remember { mutableFloatStateOf(1.25f) }
    var musicVolume by remember { mutableFloatStateOf(0.25f) }
    var stemRoutingMode by remember { mutableStateOf(AudioStemRoutingMode.FULL_ORIGINAL_MIX) }
    var musicRemovalMode by remember { mutableStateOf(MusicRemovalMode.OFF) }
    var separatedStems by remember(primaryFile?.absolutePath) { mutableStateOf<SeparatedAudioStems?>(null) }
    var isSplittingStems by remember { mutableStateOf(false) }
    var shazamResult by remember(primaryFile?.absolutePath) { mutableStateOf<MusicShazamMatchResult?>(null) }
    var replacementMusicFile by remember { mutableStateOf<File?>(null) }
    var isRunningShazam by remember { mutableStateOf(false) }

    // Dubbing / TTS state
    var dubScriptText by remember(primaryFile?.absolutePath) { mutableStateOf("") }
    var selectedVoiceProfile by remember { mutableStateOf(TtsVoiceProfile.CHARON_DEEP) }
    var voiceoverDubMode by remember { mutableStateOf(VoiceoverDubMode.OFF) }
    var generatedVoiceoverFile by remember { mutableStateOf<File?>(null) }
    var isGeneratingDub by remember { mutableStateOf(false) }

    val effectiveVoiceoverFile = importedCustomVoiceoverFile ?: generatedVoiceoverFile
    LaunchedEffect(importedCustomVoiceoverFile) {
        if (importedCustomVoiceoverFile != null && voiceoverDubMode == VoiceoverDubMode.OFF) {
            voiceoverDubMode = VoiceoverDubMode.DUB_VOICEOVER_15PCT_BG
        }
    }

    // Anti-detect & Render state
    var antiDetectEnabled by remember { mutableStateOf(true) }
    var antiDetectFlip by remember { mutableStateOf(false) }
    var antiDetectMicroZoom by remember { mutableStateOf(true) }
    var antiDetectPitchShift by remember { mutableStateOf(true) }
    var isRenderingMaster by remember { mutableStateOf(false) }
    var renderProgressLabel by remember { mutableStateOf("") }

    // =========================================================================
    // DUAL SYNCHRONIZED MEDIA3 EXOPLAYERS (PRIMARY + BACKGROUND)
    // =========================================================================
    val primaryDurationSec = remember(primaryFile?.absolutePath) {
        if (primaryFile != null && primaryFile.exists()) {
            hardwareEngine.probeDurationSeconds(primaryFile).coerceAtLeast(1.0)
        } else {
            10.0
        }
    }

    var isPlaying by remember { mutableStateOf(false) }
    var playheadSec by remember { mutableDoubleStateOf(0.0) }
    var auditionStopAtSec by remember { mutableStateOf<Double?>(null) }

    val primaryPlayer = remember {
        ExoPlayer.Builder(context).build().apply {
            setSeekParameters(SeekParameters.EXACT)
            repeatMode = Player.REPEAT_MODE_ALL
        }
    }

    val bgPlayer = remember {
        ExoPlayer.Builder(context).build().apply {
            setSeekParameters(SeekParameters.EXACT)
            repeatMode = Player.REPEAT_MODE_ALL
            volume = 0f
        }
    }

    DisposableEffect(Unit) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
                if (playing && backgroundFile?.exists() == true) {
                    bgPlayer.play()
                } else {
                    bgPlayer.pause()
                }
            }
        }
        primaryPlayer.addListener(listener)
        onDispose {
            primaryPlayer.removeListener(listener)
            primaryPlayer.release()
            bgPlayer.release()
        }
    }

    LaunchedEffect(primaryFile?.absolutePath) {
        if (primaryFile != null && primaryFile.exists()) {
            primaryPlayer.setMediaItem(MediaItem.fromUri(Uri.fromFile(primaryFile)))
            primaryPlayer.prepare()
        } else {
            primaryPlayer.clearMediaItems()
        }
    }

    LaunchedEffect(backgroundFile?.absolutePath) {
        if (backgroundFile != null && backgroundFile.exists()) {
            bgPlayer.setMediaItem(MediaItem.fromUri(Uri.fromFile(backgroundFile)))
            bgPlayer.prepare()
            if (primaryPlayer.isPlaying) bgPlayer.play()
        } else {
            bgPlayer.clearMediaItems()
        }
    }

    LaunchedEffect(mainVolume) {
        primaryPlayer.volume = mainVolume.coerceIn(0f, 1f)
    }

    LaunchedEffect(bgVolume) {
        bgPlayer.volume = bgVolume.coerceIn(0f, 1f)
    }

    // 60fps playhead & word-audition tracker
    LaunchedEffect(primaryPlayer) {
        while (isActive) {
            val pos = (primaryPlayer.currentPosition / 1000.0).coerceAtLeast(0.0)
            playheadSec = pos
            val stopAt = auditionStopAtSec
            if (stopAt != null && pos >= stopAt) {
                primaryPlayer.pause()
                bgPlayer.pause()
                auditionStopAtSec = null
            }
            delay(16L)
        }
    }

    fun seekBothPlayers(sec: Double, autoPlayUntilSec: Double? = null) {
        val clamped = sec.coerceIn(0.0, primaryDurationSec)
        auditionStopAtSec = autoPlayUntilSec
        playheadSec = clamped
        primaryPlayer.seekTo((clamped * 1000.0).roundToLong())
        if (backgroundFile?.exists() == true && bgPlayer.duration > 0) {
            val bgMs = ((clamped * 1000.0).roundToLong()) % bgPlayer.duration.coerceAtLeast(1000L)
            bgPlayer.seekTo(bgMs)
        }
        if (autoPlayUntilSec != null) {
            primaryPlayer.play()
        }
    }

    fun launchFinalOrPreviewRender(isPreview5Sec: Boolean) {
        val pFile = primaryFile
        if (pFile == null || !pFile.exists()) {
            onShowMessage("Сначала выбери основное видео (Слот 1)!")
            return
        }
        primaryPlayer.pause()
        bgPlayer.pause()
        isRenderingMaster = true
        renderProgressLabel = if (isPreview5Sec) {
            "⚡ Сборка тестового 5-сек фрагмента со всеми слоями..."
        } else {
            "💾 Финальная сборка FullHD 1080p + субтитры + анти-детект..."
        }

        scope.launch {
            try {
                val targetDur = hardwareEngine.probeDurationSeconds(pFile).coerceAtLeast(2.0)
                val resolvedClutter: File? = when {
                    backgroundFile != null && backgroundFile.exists() -> backgroundFile
                    layoutLayer == VideoLayoutLayer.SPLIT_VSTACK || layoutLayer == VideoLayoutLayer.BRAINROT_VOICEOVER -> {
                        clutterFilter.buildShuffledClutterReel(
                            targetDurationSec = targetDur,
                            fallbackPrimaryVideo = pFile
                        )
                    }
                    else -> null
                }

                val framingPlan = faceHelper.computeFramingPlan(
                    videoFile = pFile,
                    enableSmartFaceCrop = enableSmartFaceAndSeamCrop,
                    hardsubRemovalMode = oldSubRemovalMode
                )

                val subCfg = SubtitleLayerConfig(
                    enabled = subtitlesEnabled && editablePhrases.isNotEmpty(),
                    densityMode = if (wordsPerPhraseSetting <= 4) {
                        SubtitleDensityMode.PUNCH_3_TO_4_WORDS
                    } else {
                        SubtitleDensityMode.CLASSIC_6_TO_8_WORDS
                    },
                    placementZone = SubtitlePlacementZone.CUSTOM_XY,
                    customX = 540,
                    customY = subtitleCustomY.coerceIn(140, 1780),
                    fontSizePx = subtitleFontSizePx.coerceIn(36, 100),
                    syncOffsetMs = subtitleSyncOffsetMs
                )

                val renderCfg = ModularRenderConfig(
                    layoutLayer = layoutLayer,
                    splitTopRatio = splitTopRatio,
                    mainVolume = mainVolume,
                    bgVolume = bgVolume,
                    voiceoverVolume = voiceoverVolume,
                    musicVolume = musicVolume,
                    enableSmartFaceCrop = enableSmartFaceAndSeamCrop,
                    customVoiceoverAudioFile = effectiveVoiceoverFile,
                    customReplacementMusicFile = replacementMusicFile,
                    enableReplacementMusicBed = replacementMusicFile != null,
                    separatedStems = separatedStems,
                    existingSubtitleRemovalMode = oldSubRemovalMode,
                    subtitleLayer = subCfg,
                    dspLayer = AntiDetectDspLayerConfig(
                        enabled = antiDetectEnabled,
                        enableMicroZoom = antiDetectMicroZoom,
                        enableHorizontalFlip = antiDetectFlip,
                        enableVideoNoiseInjection = antiDetectEnabled,
                        enableMicroColorEqShift = antiDetectEnabled,
                        enableAudioPitchTempoMicroJitter = antiDetectPitchShift,
                        enableAudioEqVoicePresence = antiDetectEnabled,
                        musicRemovalMode = musicRemovalMode,
                        voiceoverDubMode = voiceoverDubMode,
                        stemRoutingMode = stemRoutingMode
                    ),
                    videoBitrateKbps = 13500
                )

                val (canvasW, canvasH) = hardwareEngine.computeOutputCanvasDimensions(pFile, renderCfg)
                val compiledAss: File? = if (subCfg.enabled) {
                    subtitleTransformer.compileEditablePhrasesToAssFile(
                        phrases = editablePhrases,
                        config = subCfg,
                        framingPlan = framingPlan,
                        canvasWidth = canvasW,
                        canvasHeight = canvasH
                    )
                } else {
                    null
                }

                val outMp4 = hardwareEngine.renderPipeline(
                    primaryVideoFile = pFile,
                    clutterReelFile = resolvedClutter,
                    framingPlan = framingPlan,
                    assSubtitleFile = compiledAss,
                    config = renderCfg,
                    isPreviewPass = isPreview5Sec
                )

                if (isPreview5Sec) {
                    isRenderingMaster = false
                    renderedPreviewModalFile = outMp4
                } else {
                    val galleryFile = hardwareEngine.saveRenderedVideoToGallery(outMp4, primaryTitle)
                    isRenderingMaster = false
                    renderedPreviewModalFile = galleryFile
                    onShowMessage("✅ Готово! Ролик сохранён в Галерею: ${galleryFile.name}")
                }
            } catch (e: Exception) {
                isRenderingMaster = false
                onShowMessage("❌ Ошибка рендера: ${e.message}")
            }
        }
    }

    // =========================================================================
    // DIALOGS: BACKGROUND POOL PICKER + PRE-EDIT PROMPT + RENDERED PREVIEW
    // =========================================================================
    if (showBgPickerModal) {
        val poolClips = remember(showBgPickerModal) { clutterFilter.listVerifiedPoolClips() }
        AlertDialog(
            onDismissRequest = { showBgPickerModal = false },
            title = { Text("🗂 Выбери фон из списка фонов (${poolClips.size} шт.)", fontSize = 16.sp) },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(340.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                        OutlinedButton(
                            onClick = {
                                showBgPickerModal = false
                                onImportBackgroundFromGalleryRequested()
                            },
                            modifier = Modifier.weight(1f)
                        ) { Text("📱 С телефона", fontSize = 11.sp) }

                        OutlinedButton(
                            onClick = {
                                showBgPickerModal = false
                                onJumpToBackgroundSearchRequested()
                            },
                            modifier = Modifier.weight(1f)
                        ) { Text("🔎 Найти ссылки", fontSize = 11.sp) }
                    }

                    if (poolClips.isEmpty()) {
                        Text(
                            text = "Список фонов пока пуст. Загрузи видео с телефона или перейди в «🔎 Поиск → Фоны до СТОПА», чтобы набрать крутых фонов по ссылкам!",
                            fontSize = 13.sp,
                            color = Color(0xFF9CA3AF)
                        )
                    } else {
                        poolClips.forEach { clipFile ->
                            val meta = clutterFilter.getPoolClipMetadata(clipFile)
                            Card(
                                colors = CardDefaults.cardColors(containerColor = Color(0xFF1F2433)),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        showBgPickerModal = false
                                        pendingBgChoicePair = clipFile to meta.title
                                    }
                            ) {
                                Row(
                                    modifier = Modifier.padding(10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(meta.title, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                                        Text(
                                            "${meta.sizeKb / 1024} МБ • ${meta.tags.joinToString(" ")}",
                                            color = Color(0xFF9CA3AF),
                                            fontSize = 11.sp
                                        )
                                    }
                                    Text("Выбрать →", color = Color(0xFF34D399), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showBgPickerModal = false }) { Text("Закрыть") }
            }
        )
    }

    pendingBgChoicePair?.let { (bgFile, bgName) ->
        AlertDialog(
            onDismissRequest = { pendingBgChoicePair = null },
            title = { Text("🕹 Фон выбран: $bgName", fontSize = 16.sp) },
            text = {
                Text("Сразу закинуть этот фон во 2-й слот Редактора или сначала подрезать/скадрировать его в Разделке?")
            },
            confirmButton = {
                Button(
                    onClick = {
                        pendingBgChoicePair = null
                        onSelectBackgroundDirectly(bgFile, bgName)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981))
                ) {
                    Text("⚡ Сразу в Редактор", color = Color.Black)
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = {
                        pendingBgChoicePair = null
                        onSendClipToPreEditor(bgFile, bgName, PreEditTargetRole.BACKGROUND_CLIP)
                    }
                ) {
                    Text("✂️ Сначала в Разделку")
                }
            }
        )
    }

    renderedPreviewModalFile?.let { readyFile ->
        AlertDialog(
            onDismissRequest = { renderedPreviewModalFile = null },
            title = { Text("🎬 Готовый результат: ${readyFile.name}", fontSize = 15.sp) },
            text = {
                val previewPlayer = remember(readyFile.absolutePath) {
                    ExoPlayer.Builder(context).build().apply {
                        setMediaItem(MediaItem.fromUri(Uri.fromFile(readyFile)))
                        repeatMode = Player.REPEAT_MODE_ONE
                        prepare()
                        playWhenReady = true
                    }
                }
                DisposableEffect(previewPlayer) {
                    onDispose { previewPlayer.release() }
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
                                useController = true
                                player = previewPlayer
                            }
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                }
            },
            confirmButton = {
                Button(onClick = { renderedPreviewModalFile = null }) {
                    Text("Отлично")
                }
            }
        )
    }

    // =========================================================================
    // MAIN ASSEMBLER SCROLLABLE LAYOUT
    // =========================================================================
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0B0D13))
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        // ---------------------------------------------------------------------
        // STEP DECK: SLOT 1 (PRIMARY VIDEO) + SLOT 2 (BACKGROUND VIDEO)
        // ---------------------------------------------------------------------
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // SLOT 1: PRIMARY
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF151926)),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.weight(1f)
            ) {
                Column(modifier = Modifier.padding(10.dp)) {
                    Text("1️⃣ БАЗА (Исходник)", color = Color(0xFF60A5FA), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    Text(
                        text = if (primaryFile?.exists() == true) primaryTitle else "Не выбрано",
                        color = Color.White,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        OutlinedButton(
                            onClick = onPickPrimarySourceRequested,
                            modifier = Modifier.weight(1f).height(30.dp)
                        ) {
                            Text("📂 Выбрать", fontSize = 10.sp)
                        }
                        if (primaryFile?.exists() == true) {
                            OutlinedButton(
                                onClick = {
                                    primaryPlayer.pause()
                                    onSendClipToPreEditor(primaryFile, primaryTitle, PreEditTargetRole.PRIMARY_SOURCE)
                                },
                                modifier = Modifier.height(30.dp)
                            ) {
                                Text("✂️", fontSize = 11.sp)
                            }
                        }
                    }
                }
            }

            // SLOT 2: BACKGROUND
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF151926)),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.weight(1f)
            ) {
                Column(modifier = Modifier.padding(10.dp)) {
                    Text("2️⃣ ФОН (Залипалка)", color = Color(0xFF34D399), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    Text(
                        text = if (backgroundFile?.exists() == true) backgroundTitle else "Нажми Выбрать фон",
                        color = Color.White,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Button(
                            onClick = { showBgPickerModal = true },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF059669)),
                            modifier = Modifier.weight(1f).height(30.dp)
                        ) {
                            Text("🗂 Список", fontSize = 10.sp)
                        }
                        if (backgroundFile?.exists() == true) {
                            OutlinedButton(
                                onClick = {
                                    bgPlayer.pause()
                                    onSendClipToPreEditor(backgroundFile, backgroundTitle, PreEditTargetRole.BACKGROUND_CLIP)
                                },
                                modifier = Modifier.height(30.dp)
                            ) {
                                Text("✂️", fontSize = 11.sp)
                            }
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        // ---------------------------------------------------------------------
        // LIVE 9:16 DUAL COMPOSITE STAGE + DRAGGABLE SEAM + 60FPS KARAOKE
        // ---------------------------------------------------------------------
        val stageWidthDp = 216.dp
        val stageHeightDp = 384.dp
        val stageHeightPx = with(density) { stageHeightDp.toPx() }

        Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .width(stageWidthDp)
                    .height(stageHeightDp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0xFF050608))
                    .border(1.5.dp, Color(0xFF2D3548), RoundedCornerShape(16.dp))
            ) {
                if (primaryFile == null || !primaryFile.exists()) {
                    Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
                        Text(
                            text = "Шаг 1: Выбери основное видео\nШаг 2: Выбери фон из списка",
                            color = Color(0xFF9CA3AF),
                            fontSize = 12.sp,
                            textAlign = TextAlign.Center
                        )
                    }
                } else if (layoutLayer == VideoLayoutLayer.SPLIT_VSTACK) {
                    Column(Modifier.fillMaxSize()) {
                        // Top Half: Primary Source
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(splitTopRatio.coerceIn(0.30f, 0.70f))
                        ) {
                            AndroidView(
                                factory = { ctx ->
                                    PlayerView(ctx).apply {
                                        useController = false
                                        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                                        player = primaryPlayer
                                    }
                                },
                                update = { pv -> if (pv.player !== primaryPlayer) pv.player = primaryPlayer },
                                modifier = Modifier.fillMaxSize()
                            )
                        }

                        // Bottom Half: Background Reel (or hint to pick from pool)
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight((1f - splitTopRatio).coerceIn(0.30f, 0.70f))
                                .background(Color(0xFF10131C)),
                            contentAlignment = Alignment.Center
                        ) {
                            if (backgroundFile != null && backgroundFile.exists()) {
                                AndroidView(
                                    factory = { ctx ->
                                        PlayerView(ctx).apply {
                                            useController = false
                                            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                                            player = bgPlayer
                                        }
                                    },
                                    update = { pv -> if (pv.player !== bgPlayer) pv.player = bgPlayer },
                                    modifier = Modifier.fillMaxSize()
                                )
                            } else {
                                TextButton(onClick = { showBgPickerModal = true }) {
                                    Text("➕ Выбрать фон из списка", color = Color(0xFF34D399), fontSize = 12.sp)
                                }
                            }
                        }
                    }

                    // Draggable Split Seam Handle!
                    val seamYPx = (stageHeightPx * splitTopRatio).roundToInt()
                    Box(
                        modifier = Modifier
                            .offset { IntOffset(0, seamYPx - 14) }
                            .fillMaxWidth()
                            .height(28.dp)
                            .pointerInput(stageHeightPx) {
                                detectVerticalDragGestures { change, dragAmount ->
                                    change.consume()
                                    val nextRatio = (splitTopRatio + dragAmount / stageHeightPx).coerceIn(0.30f, 0.70f)
                                    splitTopRatio = nextRatio
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(2.dp)
                                .background(Color(0xFF38BDF8))
                        )
                        Surface(
                            color = Color(0xDD0F172A),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text(
                                text = "⇅ Шов ${(splitTopRatio * 100).roundToInt()}/${100 - (splitTopRatio * 100).roundToInt()}",
                                color = Color(0xFF38BDF8),
                                fontSize = 10.sp,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                            )
                        }
                    }
                } else if (layoutLayer == VideoLayoutLayer.BRAINROT_VOICEOVER && backgroundFile?.exists() == true) {
                    AndroidView(
                        factory = { ctx ->
                            PlayerView(ctx).apply {
                                useController = false
                                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                                player = bgPlayer
                            }
                        },
                        update = { pv -> if (pv.player !== bgPlayer) pv.player = bgPlayer },
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    AndroidView(
                        factory = { ctx ->
                            PlayerView(ctx).apply {
                                useController = false
                                resizeMode = if (layoutLayer == VideoLayoutLayer.FULLSCREEN_EDIT) {
                                    AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                                } else {
                                    AspectRatioFrameLayout.RESIZE_MODE_FIT
                                }
                                player = primaryPlayer
                            }
                        },
                        update = { pv -> if (pv.player !== primaryPlayer) pv.player = primaryPlayer },
                        modifier = Modifier.fillMaxSize()
                    )
                }

                // -------------------------------------------------------------
                // LIVE 60FPS KARAOKE WORD-HIGHLIGHT OVERLAY (DRAGGABLE Y!)
                // -------------------------------------------------------------
                if (subtitlesEnabled && editablePhrases.isNotEmpty()) {
                    val effectiveSubtitleClockSec = playheadSec - (subtitleSyncOffsetMs / 1000.0)
                    val activePhrase = editablePhrases.firstOrNull { p ->
                        effectiveSubtitleClockSec >= (p.startSec - 0.04) &&
                            effectiveSubtitleClockSec <= (p.endSec + 0.06)
                    }

                    val subYPx = ((subtitleCustomY.coerceIn(140, 1780) / 1920f) * stageHeightPx).roundToInt()
                    val previewFontSp = ((subtitleFontSizePx / 68f) * 14f).coerceIn(10f, 22f).sp

                    Box(
                        modifier = Modifier
                            .offset { IntOffset(0, (subYPx - 24).coerceAtLeast(8)) }
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp)
                            .pointerInput(stageHeightPx) {
                                detectVerticalDragGestures { change, dragAmount ->
                                    change.consume()
                                    val delta1920 = ((dragAmount / stageHeightPx) * 1920f).roundToInt()
                                    subtitleCustomY = (subtitleCustomY + delta1920).coerceIn(140, 1780)
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        if (activePhrase != null) {
                            val styledKaraoke = buildAnnotatedString {
                                activePhrase.words.forEachIndexed { idx, w ->
                                    val isCurrentWord = effectiveSubtitleClockSec >= (w.startSec - 0.03) &&
                                        effectiveSubtitleClockSec <= (w.endSec + 0.04)
                                    withStyle(
                                        SpanStyle(
                                            color = if (isCurrentWord) Color(0xFFFACC15) else Color.White,
                                            fontWeight = FontWeight.ExtraBold
                                        )
                                    ) {
                                        append(w.text)
                                    }
                                    if (idx < activePhrase.words.lastIndex) append(" ")
                                }
                            }
                            Surface(
                                color = Color(0xAA000000),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text(
                                    text = styledKaraoke,
                                    fontSize = previewFontSp,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                            }
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(6.dp))

        // Synchronized Transport Controls
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Button(
                onClick = {
                    auditionStopAtSec = null
                    if (primaryPlayer.isPlaying) {
                        primaryPlayer.pause()
                        bgPlayer.pause()
                    } else {
                        primaryPlayer.play()
                        if (backgroundFile?.exists() == true) bgPlayer.play()
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2563EB)),
                modifier = Modifier.height(34.dp)
            ) {
                Text(if (isPlaying) "⏸ Пауза" else "▶ Играть 2 слоя", fontSize = 12.sp)
            }

            Text(
                text = "${formatSecPrecise(playheadSec)} / ${formatSecPrecise(primaryDurationSec)}",
                color = Color(0xFFE5E7EB),
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold
            )
        }

        Slider(
            value = playheadSec.toFloat().coerceIn(0f, primaryDurationSec.toFloat()),
            onValueChange = { seekBothPlayers(it.toDouble()) },
            valueRange = 0f..primaryDurationSec.toFloat().coerceAtLeast(1f),
            modifier = Modifier.fillMaxWidth()
        )

        // ---------------------------------------------------------------------
        // 4 ASSEMBLER TOOL DRAWERS
        // ---------------------------------------------------------------------
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            AssemblerToolDrawer.entries.forEach { drawer ->
                FilterChip(
                    selected = activeDrawer == drawer,
                    onClick = { activeDrawer = drawer },
                    label = { Text(drawer.label, fontSize = 12.sp) }
                )
            }
        }

        Spacer(Modifier.height(6.dp))

        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF141824)),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                when (activeDrawer) {
                    // =========================================================
                    // DRAWER 1: GROQ WHISPER + PHRASE & WORD TIMING EDITOR
                    // =========================================================
                    AssemblerToolDrawer.SUBTITLES_WHISPER -> {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Караоке-субтитры на видео", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                            Switch(checked = subtitlesEnabled, onCheckedChange = { subtitlesEnabled = it })
                        }

                        Text(whisperStatusNote, color = Color(0xFF93C5FD), fontSize = 12.sp)
                        Spacer(Modifier.height(8.dp))

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            Button(
                                onClick = {
                                    val pFile = primaryFile
                                    if (pFile == null || !pFile.exists()) {
                                        onShowMessage("Сначала выбери основное видео (Слот 1)!")
                                        return@Button
                                    }
                                    isTranscribingWhisper = true
                                    whisperStatusNote = "🎙 Извлекаю звук из готовой базы и распознаю каждое слово через Groq Whisper..."
                                    scope.launch {
                                        val (phrases, status) = subtitleTransformer.transcribePreparedClipToEditablePhrases(
                                            preparedVideoFile = pFile,
                                            wordsPerPhrase = wordsPerPhraseSetting
                                        )
                                        editablePhrases = phrases
                                        whisperStatusNote = status
                                        subtitlesEnabled = phrases.isNotEmpty()
                                        isTranscribingWhisper = false
                                    }
                                },
                                enabled = !isTranscribingWhisper,
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF7C3AED)),
                                modifier = Modifier.weight(1f)
                            ) {
                                if (isTranscribingWhisper) {
                                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = Color.White)
                                    Spacer(Modifier.width(6.dp))
                                    Text("Слушаю...", fontSize = 12.sp)
                                } else {
                                    Text("🎙 Распознать по звуку (Whisper)", fontSize = 12.sp)
                                }
                            }

                            OutlinedButton(
                                onClick = {
                                    val start = playheadSec
                                    val end = (start + 1.8).coerceAtMost(primaryDurationSec)
                                    val newPhrase = EditableSubtitlePhrase(
                                        words = listOf(
                                            EditableTimedWord(text = "Новое", startSec = start, endSec = start + 0.8),
                                            EditableTimedWord(text = "слово", startSec = start + 0.85, endSec = end)
                                        )
                                    )
                                    editablePhrases = (editablePhrases + newPhrase).sortedBy { it.startSec }
                                    expandedPhraseId = newPhrase.id
                                }
                            ) {
                                Text("➕ Фраза", fontSize = 12.sp)
                            }
                        }

                        Spacer(Modifier.height(8.dp))

                        // Global Font Size, Position Y, and Words-per-card controls
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "Высота Y=$subtitleCustomY (тяни текст на экране!) • Шрифт=${subtitleFontSizePx}px",
                                color = Color(0xFF9CA3AF),
                                fontSize = 11.sp
                            )
                        }
                        Slider(
                            value = subtitleFontSizePx.toFloat(),
                            onValueChange = { subtitleFontSizePx = it.roundToInt() },
                            valueRange = 40f..96f
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Общий сдвиг: ${subtitleSyncOffsetMs}мс", color = Color(0xFFD1D5DB), fontSize = 11.sp)
                            OutlinedButton(onClick = { subtitleSyncOffsetMs -= 100L }, modifier = Modifier.height(30.dp)) {
                                Text("-0.1с", fontSize = 11.sp)
                            }
                            OutlinedButton(onClick = { subtitleSyncOffsetMs += 100L }, modifier = Modifier.height(30.dp)) {
                                Text("+0.1с", fontSize = 11.sp)
                            }
                            if (editablePhrases.isNotEmpty()) {
                                OutlinedButton(
                                    onClick = {
                                        val allWords = editablePhrases.flatMap { it.words }
                                        wordsPerPhraseSetting = if (wordsPerPhraseSetting <= 3) 5 else 3
                                        editablePhrases = subtitleTransformer.groupTimedWordsIntoEditablePhrases(
                                            allWords,
                                            wordsPerPhraseSetting
                                        )
                                    },
                                    modifier = Modifier.height(30.dp)
                                ) {
                                    Text("По $wordsPerPhraseSetting сл.", fontSize = 11.sp)
                                }
                            }
                        }

                        // INTERACTIVE PHRASE & PER-WORD TIMING LIST
                        if (editablePhrases.isNotEmpty()) {
                            Spacer(Modifier.height(10.dp))
                            Text(
                                text = "✏️ Фразы и пословные таймкоды (${editablePhrases.size} шт.) — нажми на фразу для точной правки слов:",
                                color = Color(0xFFFDE68A),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Spacer(Modifier.height(6.dp))

                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                editablePhrases.forEachIndexed { pIdx, phrase ->
                                    val isExpanded = expandedPhraseId == phrase.id
                                    Surface(
                                        color = if (isExpanded) Color(0xFF22293E) else Color(0xFF1A1F2E),
                                        shape = RoundedCornerShape(10.dp),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Column(modifier = Modifier.padding(10.dp)) {
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.SpaceBetween
                                            ) {
                                                Column(
                                                    modifier = Modifier
                                                        .weight(1f)
                                                        .clickable {
                                                            expandedPhraseId = if (isExpanded) null else phrase.id
                                                            seekBothPlayers(phrase.startSec)
                                                        }
                                                ) {
                                                    Text(
                                                        text = "[${String.format(Locale.US, "%.2fс – %.2fс", phrase.startSec, phrase.endSec)}]",
                                                        color = Color(0xFF38BDF8),
                                                        fontSize = 11.sp
                                                    )
                                                    Text(
                                                        text = phrase.joinedText,
                                                        color = Color.White,
                                                        fontSize = 14.sp,
                                                        fontWeight = FontWeight.Bold
                                                    )
                                                }
                                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                                    OutlinedButton(
                                                        onClick = { seekBothPlayers(phrase.startSec, phrase.endSec) },
                                                        modifier = Modifier.height(30.dp)
                                                    ) { Text("▶", fontSize = 11.sp) }

                                                    OutlinedButton(
                                                        onClick = { expandedPhraseId = if (isExpanded) null else phrase.id },
                                                        modifier = Modifier.height(30.dp)
                                                    ) { Text(if (isExpanded) "▲" else "✏️", fontSize = 11.sp) }

                                                    OutlinedButton(
                                                        onClick = {
                                                            editablePhrases = editablePhrases.toMutableList().apply { removeAt(pIdx) }
                                                        },
                                                        modifier = Modifier.height(30.dp)
                                                    ) { Text("🗑", fontSize = 11.sp) }
                                                }
                                            }

                                            if (isExpanded) {
                                                Spacer(Modifier.height(8.dp))
                                                var wholeTextDraft by remember(phrase.id, phrase.joinedText) {
                                                    mutableStateOf(phrase.joinedText)
                                                }
                                                Row(
                                                    modifier = Modifier.fillMaxWidth(),
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                                ) {
                                                    OutlinedTextField(
                                                        value = wholeTextDraft,
                                                        onValueChange = { wholeTextDraft = it },
                                                        label = { Text("Весь текст фразы", fontSize = 11.sp) },
                                                        singleLine = true,
                                                        modifier = Modifier.weight(1f)
                                                    )
                                                    Button(
                                                        onClick = {
                                                            val updatedPhrase = phrase.withReplacedPhraseText(wholeTextDraft)
                                                            editablePhrases = editablePhrases.toMutableList().apply {
                                                                set(pIdx, updatedPhrase)
                                                            }
                                                        }
                                                    ) { Text("ОК", fontSize = 11.sp) }
                                                }

                                                Spacer(Modifier.height(8.dp))
                                                Text(
                                                    "Точный тайминг каждого слова внутри фразы:",
                                                    color = Color(0xFFA7F3D0),
                                                    fontSize = 11.sp
                                                )
                                                Spacer(Modifier.height(4.dp))

                                                phrase.words.forEachIndexed { wIdx, word ->
                                                    Surface(
                                                        color = Color(0xFF121622),
                                                        shape = RoundedCornerShape(8.dp),
                                                        modifier = Modifier
                                                            .fillMaxWidth()
                                                            .padding(vertical = 3.dp)
                                                    ) {
                                                        Column(modifier = Modifier.padding(8.dp)) {
                                                            Row(
                                                                verticalAlignment = Alignment.CenterVertically,
                                                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                                                            ) {
                                                                OutlinedTextField(
                                                                    value = word.text,
                                                                    onValueChange = { newWordTxt ->
                                                                        val nextWords = phrase.words.toMutableList().apply {
                                                                            set(wIdx, word.copy(text = newWordTxt))
                                                                        }
                                                                        editablePhrases = editablePhrases.toMutableList().apply {
                                                                            set(pIdx, phrase.copy(words = nextWords))
                                                                        }
                                                                    },
                                                                    label = { Text("Слово #${wIdx + 1}", fontSize = 10.sp) },
                                                                    singleLine = true,
                                                                    modifier = Modifier.weight(1f)
                                                                )
                                                                OutlinedButton(
                                                                    onClick = { seekBothPlayers(word.startSec, word.endSec) },
                                                                    modifier = Modifier.height(34.dp)
                                                                ) { Text("▶ Слово", fontSize = 10.sp) }

                                                                if (phrase.words.size > 1) {
                                                                    OutlinedButton(
                                                                        onClick = {
                                                                            val nextWords = phrase.words.toMutableList().apply {
                                                                                removeAt(wIdx)
                                                                            }
                                                                            editablePhrases = editablePhrases.toMutableList().apply {
                                                                                set(pIdx, phrase.copy(words = nextWords))
                                                                            }
                                                                        },
                                                                        modifier = Modifier.height(34.dp)
                                                                    ) { Text("✖", fontSize = 10.sp) }
                                                                }
                                                            }

                                                            Spacer(Modifier.height(4.dp))

                                                            // Exact Word Start & End Steppers (0.05s precision!)
                                                            Row(
                                                                modifier = Modifier.fillMaxWidth(),
                                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                                verticalAlignment = Alignment.CenterVertically
                                                            ) {
                                                                Row(
                                                                    verticalAlignment = Alignment.CenterVertically,
                                                                    horizontalArrangement = Arrangement.spacedBy(3.dp)
                                                                ) {
                                                                    Text(
                                                                        String.format(Locale.US, "Старт: %.2fс", word.startSec),
                                                                        color = Color(0xFF93C5FD),
                                                                        fontSize = 11.sp
                                                                    )
                                                                    OutlinedButton(
                                                                        onClick = {
                                                                            val ns = ((word.startSec - 0.05).coerceAtLeast(0.0) * 100.0).roundToInt() / 100.0
                                                                            val nextWords = phrase.words.toMutableList().apply {
                                                                                set(wIdx, word.copy(startSec = ns))
                                                                            }
                                                                            editablePhrases = editablePhrases.toMutableList().apply {
                                                                                set(pIdx, phrase.copy(words = nextWords))
                                                                            }
                                                                        },
                                                                        modifier = Modifier.height(28.dp)
                                                                    ) { Text("-0.05", fontSize = 10.sp) }

                                                                    OutlinedButton(
                                                                        onClick = {
                                                                            val ns = (((word.startSec + 0.05).coerceAtMost(word.endSec - 0.05)) * 100.0).roundToInt() / 100.0
                                                                            val nextWords = phrase.words.toMutableList().apply {
                                                                                set(wIdx, word.copy(startSec = ns))
                                                                            }
                                                                            editablePhrases = editablePhrases.toMutableList().apply {
                                                                                set(pIdx, phrase.copy(words = nextWords))
                                                                            }
                                                                        },
                                                                        modifier = Modifier.height(28.dp)
                                                                    ) { Text("+0.05", fontSize = 10.sp) }
                                                                }

                                                                Row(
                                                                    verticalAlignment = Alignment.CenterVertically,
                                                                    horizontalArrangement = Arrangement.spacedBy(3.dp)
                                                                ) {
                                                                    Text(
                                                                        String.format(Locale.US, "Конец: %.2fс", word.endSec),
                                                                        color = Color(0xFFFCD34D),
                                                                        fontSize = 11.sp
                                                                    )
                                                                    OutlinedButton(
                                                                        onClick = {
                                                                            val ne = (((word.endSec - 0.05).coerceAtLeast(word.startSec + 0.05)) * 100.0).roundToInt() / 100.0
                                                                            val nextWords = phrase.words.toMutableList().apply {
                                                                                set(wIdx, word.copy(endSec = ne))
                                                                            }
                                                                            editablePhrases = editablePhrases.toMutableList().apply {
                                                                                set(pIdx, phrase.copy(words = nextWords))
                                                                            }
                                                                        },
                                                                        modifier = Modifier.height(28.dp)
                                                                    ) { Text("-0.05", fontSize = 10.sp) }

                                                                    OutlinedButton(
                                                                        onClick = {
                                                                            val ne = ((word.endSec + 0.05) * 100.0).roundToInt() / 100.0
                                                                            val nextWords = phrase.words.toMutableList().apply {
                                                                                set(wIdx, word.copy(endSec = ne))
                                                                            }
                                                                            editablePhrases = editablePhrases.toMutableList().apply {
                                                                                set(pIdx, phrase.copy(words = nextWords))
                                                                            }
                                                                        },
                                                                        modifier = Modifier.height(28.dp)
                                                                    ) { Text("+0.05", fontSize = 10.sp) }
                                                                }
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // =========================================================
                    // DRAWER 2: AUDIO MIXER, 3-STEM SPLITTER & AI-SHAZAM
                    // =========================================================
                    AssemblerToolDrawer.AUDIO_TRACKS -> {
                        Text("🔊 Громкость Базы: ${(mainVolume * 100).roundToInt()}%", color = Color.White, fontSize = 12.sp)
                        Slider(value = mainVolume, onValueChange = { mainVolume = it }, valueRange = 0f..1.8f)

                        Text("🕹 Громкость Фона: ${(bgVolume * 100).roundToInt()}%", color = Color.White, fontSize = 12.sp)
                        Slider(value = bgVolume, onValueChange = { bgVolume = it }, valueRange = 0f..1.2f)

                        Text("🎙 Громкость Озвучки: ${(voiceoverVolume * 100).roundToInt()}%", color = Color.White, fontSize = 12.sp)
                        Slider(value = voiceoverVolume, onValueChange = { voiceoverVolume = it }, valueRange = 0f..2.0f)

                        Text("🎵 Громкость Новой Музыки: ${(musicVolume * 100).roundToInt()}%", color = Color.White, fontSize = 12.sp)
                        Slider(value = musicVolume, onValueChange = { musicVolume = it }, valueRange = 0f..1.2f)

                        Spacer(Modifier.height(6.dp))
                        Text("Режим распределения аудио-дорожек:", color = Color(0xFF93C5FD), fontSize = 12.sp)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            AudioStemRoutingMode.entries.forEach { mode ->
                                FilterChip(
                                    selected = stemRoutingMode == mode,
                                    onClick = { stemRoutingMode = mode },
                                    label = { Text(mode.label.removePrefix("Дорожки: "), fontSize = 11.sp) }
                                )
                            }
                        }

                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            Button(
                                onClick = {
                                    val pf = primaryFile ?: return@Button
                                    isSplittingStems = true
                                    scope.launch {
                                        try {
                                            val stems = hardwareEngine.splitVideoAudioIntoStems(pf, 0.0, primaryDurationSec)
                                            separatedStems = stems
                                            onShowMessage("✅ Звук разделён на 3 дорожки: Голос, Интершум, Музыка!")
                                        } catch (e: Exception) {
                                            onShowMessage("❌ Ошибка разделения: ${e.message}")
                                        } finally {
                                            isSplittingStems = false
                                        }
                                    }
                                },
                                enabled = !isSplittingStems && primaryFile?.exists() == true,
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(if (isSplittingStems) "Делю..." else "🎚 Разделить на 3 дорожки", fontSize = 11.sp)
                            }

                            Button(
                                onClick = {
                                    val pf = primaryFile ?: return@Button
                                    isRunningShazam = true
                                    scope.launch {
                                        try {
                                            val stems = separatedStems ?: hardwareEngine.splitVideoAudioIntoStems(pf, 0.0, 30.0)
                                            separatedStems = stems
                                            shazamResult = subtitleTransformer.analyzeMusicStemAndFindSimilar(
                                                stems.musicHarmonicsFile,
                                                primaryTitle
                                            )
                                        } catch (e: Exception) {
                                            onShowMessage("❌ Ошибка AI-Shazam: ${e.message}")
                                        } finally {
                                            isRunningShazam = false
                                        }
                                    }
                                },
                                enabled = !isRunningShazam && primaryFile?.exists() == true,
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0284C7)),
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(if (isRunningShazam) "Слушаю..." else "🎵 AI-Shazam подмена", fontSize = 11.sp)
                            }
                        }

                        shazamResult?.let { sr ->
                            Spacer(Modifier.height(8.dp))
                            Text("🎶 Жанр: ${sr.detectedTrackAndGenre} (${sr.moodAndBpm})", color = Color(0xFFA7F3D0), fontSize = 12.sp)
                            sr.candidateTracks.take(3).forEach { (trackUrl, trackName) ->
                                OutlinedButton(
                                    onClick = {
                                        scope.launch {
                                            try {
                                                onShowMessage("⬇️ Скачиваю мелодию «$trackName»...")
                                                val f = subtitleTransformer.downloadReplacementMusicCandidate(trackUrl, trackName)
                                                replacementMusicFile = f
                                                if (musicRemovalMode == MusicRemovalMode.OFF) {
                                                    musicRemovalMode = MusicRemovalMode.VOICE_BANDPASS_GATE
                                                }
                                                onShowMessage("✅ Новая музыка подключена к проекту!")
                                            } catch (e: Exception) {
                                                onShowMessage("❌ Сбой загрузки трека: ${e.message}")
                                            }
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("⬇️ Подставить: ${trackName.take(42)}", fontSize = 11.sp)
                                }
                            }
                        }
                    }

                    // =========================================================
                    // DRAWER 3: TTS DUBBING & SCRIPT TRANSLATION/REWRITE
                    // =========================================================
                    AssemblerToolDrawer.VOICEOVER_TTS -> {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            Button(
                                onClick = {
                                    val pf = primaryFile ?: return@Button
                                    isGeneratingDub = true
                                    scope.launch {
                                        val script = subtitleTransformer.generateDubbingScriptFromVideo(
                                            sourceUrl = primarySourceUrl,
                                            localVideoFile = pf,
                                            startSec = 0.0,
                                            endSec = primaryDurationSec,
                                            isolatedVocalStemFile = separatedStems?.vocalsOverlayFile
                                        )
                                        dubScriptText = script
                                        isGeneratingDub = false
                                    }
                                },
                                enabled = !isGeneratingDub && primaryFile?.exists() == true,
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("🧠 Перевод / Рерайт речи", fontSize = 12.sp)
                            }

                            OutlinedButton(
                                onClick = onImportCustomVoiceoverRequested,
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("📂 Свой .mp3/.m4a", fontSize = 12.sp)
                            }
                        }

                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = dubScriptText,
                            onValueChange = { dubScriptText = it },
                            label = { Text("Текст переозвучки (можно править вручную)") },
                            minLines = 3,
                            modifier = Modifier.fillMaxWidth()
                        )

                        Spacer(Modifier.height(8.dp))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            TtsVoiceProfile.entries.forEach { vp ->
                                FilterChip(
                                    selected = selectedVoiceProfile == vp,
                                    onClick = { selectedVoiceProfile = vp },
                                    label = { Text(vp.label.substringBefore(" ("), fontSize = 11.sp) }
                                )
                            }
                        }

                        Spacer(Modifier.height(6.dp))
                        Button(
                            onClick = {
                                isGeneratingDub = true
                                scope.launch {
                                    try {
                                        val f = subtitleTransformer.synthesizeVoiceoverToAudioFile(dubScriptText, selectedVoiceProfile)
                                        generatedVoiceoverFile = f
                                        voiceoverDubMode = VoiceoverDubMode.DUB_VOICEOVER_15PCT_BG
                                        onShowMessage("✅ Озвучка готова (${f.length() / 1024} KB)!")
                                    } catch (e: Exception) {
                                        onShowMessage("❌ Ошибка синтеза: ${e.message}")
                                    } finally {
                                        isGeneratingDub = false
                                    }
                                }
                            },
                            enabled = !isGeneratingDub && dubScriptText.isNotBlank(),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("🎙 Синтезировать озвучку", color = Color.Black, fontWeight = FontWeight.Bold)
                        }

                        Spacer(Modifier.height(6.dp))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            VoiceoverDubMode.entries.forEach { vm ->
                                FilterChip(
                                    selected = voiceoverDubMode == vm,
                                    onClick = { voiceoverDubMode = vm },
                                    label = { Text(vm.label.substringBefore(":"), fontSize = 11.sp) }
                                )
                            }
                        }
                    }

                    // =========================================================
                    // DRAWER 4: LAYOUT, 50/50 SEAM REPLACE, ANTI-DETECT & EXPORT
                    // =========================================================
                    AssemblerToolDrawer.ANTIDETECT_EXPORT -> {
                        Text("Шаблон компоновки кадра:", color = Color(0xFF93C5FD), fontSize = 12.sp)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf(
                                VideoLayoutLayer.SPLIT_VSTACK,
                                VideoLayoutLayer.FULLSCREEN_EDIT,
                                VideoLayoutLayer.BLURRED_BG_FRAME,
                                VideoLayoutLayer.BRAINROT_VOICEOVER
                            ).forEach { lay ->
                                FilterChip(
                                    selected = layoutLayer == lay,
                                    onClick = { layoutLayer = lay },
                                    label = { Text(lay.label.substringBefore(" ("), fontSize = 11.sp) }
                                )
                            }
                        }

                        Spacer(Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("🤖 Авто-центровка лиц + отрез старого 50/50", color = Color.White, fontSize = 12.sp)
                            Switch(checked = enableSmartFaceAndSeamCrop, onCheckedChange = { enableSmartFaceAndSeamCrop = it })
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("🛡 Анти-Детект уникализация (без рассинхрона)", color = Color.White, fontSize = 12.sp)
                            Switch(checked = antiDetectEnabled, onCheckedChange = { antiDetectEnabled = it })
                        }

                        if (antiDetectEnabled) {
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                FilterChip(
                                    selected = antiDetectMicroZoom,
                                    onClick = { antiDetectMicroZoom = !antiDetectMicroZoom },
                                    label = { Text("Микро-зум 4%", fontSize = 11.sp) }
                                )
                                FilterChip(
                                    selected = antiDetectFlip,
                                    onClick = { antiDetectFlip = !antiDetectFlip },
                                    label = { Text("Отзеркалить", fontSize = 11.sp) }
                                )
                                FilterChip(
                                    selected = antiDetectPitchShift,
                                    onClick = { antiDetectPitchShift = !antiDetectPitchShift },
                                    label = { Text("Сдвиг тона +2.2%", fontSize = 11.sp) }
                                )
                            }
                        }

                        Spacer(Modifier.height(6.dp))
                        Text("Удаление старых вшитых субтитров:", color = Color(0xFF9CA3AF), fontSize = 11.sp)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            ExistingSubtitleRemovalMode.entries.forEach { mode ->
                                FilterChip(
                                    selected = oldSubRemovalMode == mode,
                                    onClick = { oldSubRemovalMode = mode },
                                    label = { Text(mode.label.substringBefore(" ("), fontSize = 11.sp) }
                                )
                            }
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        // ---------------------------------------------------------------------
        // BOTTOM EXPORT BAR (5-SEC TEST + FULL-HD 1080P GALLERY EXPORT)
        // ---------------------------------------------------------------------
        if (isRenderingMaster) {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 3.dp)
                    Text(renderProgressLabel, color = Color.White, fontSize = 13.sp)
                }
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = { launchFinalOrPreviewRender(isPreview5Sec = true) },
                    modifier = Modifier.height(50.dp)
                ) {
                    Text("⚡ Тест 5 сек", fontSize = 13.sp)
                }

                Button(
                    onClick = { launchFinalOrPreviewRender(isPreview5Sec = false) },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981)),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .weight(1f)
                        .height(50.dp)
                ) {
                    Text(
                        text = "💾 СОХРАНИТЬ 1080p В ГАЛЕРЕЮ",
                        color = Color.Black,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                }
            }
        }

        Spacer(Modifier.height(26.dp))
    }
}
