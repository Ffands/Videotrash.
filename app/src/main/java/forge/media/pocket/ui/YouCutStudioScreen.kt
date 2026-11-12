package forge.media.pocket.ui

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
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
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import forge.media.pocket.clutter.ClutterEntropyFilter
import forge.media.pocket.compositor.AntiDetectDspLayerConfig
import forge.media.pocket.compositor.AudioStemRoutingMode
import forge.media.pocket.compositor.ClipTimeSegment
import forge.media.pocket.compositor.FFmpegHardwareEngine
import forge.media.pocket.compositor.ManualCropRect
import forge.media.pocket.compositor.ModularRenderConfig
import forge.media.pocket.compositor.MusicRemovalMode
import forge.media.pocket.compositor.SeparatedAudioStems
import forge.media.pocket.compositor.VideoLayoutLayer
import forge.media.pocket.compositor.VoiceoverDubMode
import forge.media.pocket.compositor.WatermarkEraseMethod
import forge.media.pocket.compositor.WatermarkMaskBox
import forge.media.pocket.subtitles.EditableSubtitlePhrase
import forge.media.pocket.subtitles.MusicShazamMatchResult
import forge.media.pocket.subtitles.SubtitleAssTransformer
import forge.media.pocket.subtitles.SubtitleDensityMode
import forge.media.pocket.subtitles.SubtitleLayerConfig
import forge.media.pocket.subtitles.SubtitlePlacementZone
import forge.media.pocket.subtitles.TtsVoiceProfile
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
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.roundToLong

private enum class YouCutToolDrawer(val icon: String, val label: String) {
    NONE("", ""),
    CANVAS_SPLIT("📐", "Холст/Сплит"),
    BACKGROUND_TRACK("🕹", "Фон"),
    SUBTITLES("💬", "Субтитры"),
    WATERMARK("🧼", "Ластик лого"),
    AUDIO_MIX("🔊", "Звук"),
    VOICEOVER("🎙", "Озвучка"),
    SPEED("⚡", "Скорость"),
    ANTIDETECT("🛡", "Антидетект")
}

private data class StudioUndoSnapshot(
    val segments: List<ClipTimeSegment>,
    val splitTopRatio: Float,
    val primaryZoom: Float,
    val primaryPanX: Float,
    val primaryPanY: Float,
    val bgZoom: Float,
    val bgPanX: Float,
    val bgPanY: Float,
    val watermarkBoxes: List<WatermarkMaskBox>,
    val phrases: List<EditableSubtitlePhrase>
)

/**
 * Единый одноэкранный видеоредактор в стиле YouCut / CapCut (без прыжков между двумя вкладками):
 * 1. Крупный живой 9:16 холст (2 синхронных аппаратных ExoPlayer) с поддержкой Pinch-to-Zoom и Pan двумя пальцами прямо на кадре.
 * 2. Скроллящийся многодорожечный Filmstrip-таймлайн с реальными мини-кадрами видео под фиксированной белой иглой по центру:
 *    - Дорожка 1: Основное видео (разрезка `✂️ Разделить` в 1 тап, удаление кусков со склейкой без дыр, ушки подрезки по краям).
 *    - Дорожка 2: Фоновый наполнитель прямо под основным видео.
 *    - Дорожка 3: Плашки субтитров на той же шкале времени (синхронизация Gemini Audio + FFmpeg звуковая волна, 0 лишних ключей).
 * 3. Узкая нижняя карусель инструментов YouCut + стек `↩️ Undo / ↪️ Redo` + прямой Экспорт FullHD 1080p в Галерею.
 */
@androidx.annotation.OptIn(UnstableApi::class)
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun YouCutStudioScreen(
    primaryFile: File?,
    primaryTitle: String,
    primarySourceUrl: String,
    initialStartSec: Double = 0.0,
    initialEndSec: Double = 0.0,
    backgroundFile: File?,
    backgroundTitle: String,
    clutterFilter: ClutterEntropyFilter,
    hardwareEngine: FFmpegHardwareEngine,
    faceHelper: FaceCenteringHelper,
    subtitleTransformer: SubtitleAssTransformer,
    onPickPrimaryVideoFromPhone: () -> Unit,
    onSelectPrimaryFromInbox: (File, String, String) -> Unit,
    onSelectBackgroundFile: (File?, String) -> Unit,
    onPickBackgroundFromPhone: () -> Unit,
    onJumpToSearchTab: (forBackground: Boolean) -> Unit,
    onImportVoiceoverAudio: () -> Unit,
    importedVoiceoverFile: File?,
    onShowMessage: (String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current

    var showSourcePickerDialog by remember { mutableStateOf(false) }
    var showBgPickerDialog by remember { mutableStateOf(false) }
    var previewModalVideoFile by remember { mutableStateOf<File?>(null) }

    var activeTool by remember { mutableStateOf(YouCutToolDrawer.NONE) }
    var selectedCanvasSlot by remember { mutableIntStateOf(0) } // 0 = Upper Primary, 1 = Lower Background

    // Source duration & Filmstrip thumbnails
    var sourceTotalDurSec by remember(primaryFile?.absolutePath) { mutableDoubleStateOf(15.0) }
    val primaryFilmstrip = remember { mutableStateListOf<ImageBitmap>() }
    val bgFilmstrip = remember { mutableStateListOf<ImageBitmap>() }

    // Ripple segments on Track 1 (kept segments play back-to-back seamlessly via ExoPlayer ClippingConfiguration)
    var segments by remember(primaryFile?.absolutePath) {
        mutableStateOf(listOf(ClipTimeSegment(startSec = 0.0, endSec = 15.0, kept = true)))
    }
    var selectedSegIndex by remember(primaryFile?.absolutePath) { mutableIntStateOf(0) }

    // Canvas Layout, Pinch-to-Zoom & Pan per layer, Split Seam
    var layoutLayer by remember { mutableStateOf(VideoLayoutLayer.SPLIT_VSTACK) }
    var splitTopRatio by remember { mutableFloatStateOf(0.52f) }
    var primaryZoom by remember(primaryFile?.absolutePath) { mutableFloatStateOf(1.0f) }
    var primaryPanX by remember(primaryFile?.absolutePath) { mutableFloatStateOf(0.0f) } // -1..1
    var primaryPanY by remember(primaryFile?.absolutePath) { mutableFloatStateOf(0.0f) } // -1..1

    var bgZoom by remember(backgroundFile?.absolutePath) { mutableFloatStateOf(1.0f) }
    var bgPanX by remember(backgroundFile?.absolutePath) { mutableFloatStateOf(0.0f) }
    var bgPanY by remember(backgroundFile?.absolutePath) { mutableFloatStateOf(0.0f) }

    var enableSmartFaceAndSeamCrop by remember { mutableStateOf(true) }
    var oldSubRemovalMode by remember { mutableStateOf(ExistingSubtitleRemovalMode.OFF) }

    // Watermark eraser boxes on canvas
    var watermarkBoxes by remember(primaryFile?.absolutePath) { mutableStateOf<List<WatermarkMaskBox>>(emptyList()) }
    var selectedWmIdx by remember { mutableIntStateOf(-1) }
    var eraseMethod by remember { mutableStateOf(WatermarkEraseMethod.DELOGO_INPAINT) }

    // Subtitles (Variant 1B: Gemini Audio + FFmpeg Speech Islands — zero extra keys)
    var subtitlesEnabled by remember { mutableStateOf(true) }
    var editablePhrases by remember(primaryFile?.absolutePath) { mutableStateOf<List<EditableSubtitlePhrase>>(emptyList()) }
    var sttStatusNote by remember(primaryFile?.absolutePath) {
        mutableStateOf("Нажми «✨ Авто-субтитры по звуку» — речь ляжет ровно по звуковой волне без лишних ключей")
    }
    var isTranscribingSpeech by remember { mutableStateOf(false) }
    var selectedPhraseId by remember { mutableStateOf<String?>(null) }
    var wordsPerPhraseSetting by remember { mutableIntStateOf(4) }
    var subtitleFontSizePx by remember { mutableIntStateOf(68) }
    var subtitleCustomY by remember { mutableIntStateOf(960) } // 0..1920
    var subtitleSyncOffsetMs by remember { mutableLongStateOf(0L) }

    // Audio, Stems, Shazam, TTS, Speed, Anti-Detect
    var playbackSpeed by remember(primaryFile?.absolutePath) { mutableFloatStateOf(1.0f) }
    var mainVolume by remember { mutableFloatStateOf(1.0f) }
    var bgVolume by remember { mutableFloatStateOf(0.0f) }
    var voiceoverVolume by remember { mutableFloatStateOf(1.25f) }
    var musicVolume by remember { mutableFloatStateOf(0.25f) }
    var musicRemovalMode by remember { mutableStateOf(MusicRemovalMode.OFF) }
    var stemRoutingMode by remember { mutableStateOf(AudioStemRoutingMode.FULL_ORIGINAL_MIX) }
    var separatedStems by remember(primaryFile?.absolutePath) { mutableStateOf<SeparatedAudioStems?>(null) }
    var isSplittingStems by remember { mutableStateOf(false) }
    var shazamResult by remember { mutableStateOf<MusicShazamMatchResult?>(null) }
    var isRunningShazam by remember { mutableStateOf(false) }
    var replacementMusicFile by remember { mutableStateOf<File?>(null) }

    var voiceoverDubMode by remember { mutableStateOf(VoiceoverDubMode.OFF) }
    var selectedVoiceProfile by remember { mutableStateOf(TtsVoiceProfile.MALE_NARRATOR_DEEP) }
    var dubScriptText by remember { mutableStateOf("") }
    var generatedVoiceoverFile by remember { mutableStateOf<File?>(null) }
    var isGeneratingDub by remember { mutableStateOf(false) }

    var antiDetectEnabled by remember { mutableStateOf(true) }
    var antiDetectMicroZoom by remember { mutableStateOf(true) }
    var antiDetectPitchShift by remember { mutableStateOf(true) }
    var antiDetectFlip by remember { mutableStateOf(false) }

    // Render state
    var isRenderingMaster by remember { mutableStateOf(false) }
    var renderStatusLabel by remember { mutableStateOf("") }

    // Undo / Redo stacks
    val undoStack = remember { mutableStateListOf<StudioUndoSnapshot>() }
    val redoStack = remember { mutableStateListOf<StudioUndoSnapshot>() }

    fun pushUndoSnapshot() {
        undoStack.add(
            StudioUndoSnapshot(
                segments = segments.toList(),
                splitTopRatio = splitTopRatio,
                primaryZoom = primaryZoom,
                primaryPanX = primaryPanX,
                primaryPanY = primaryPanY,
                bgZoom = bgZoom,
                bgPanX = bgPanX,
                bgPanY = bgPanY,
                watermarkBoxes = watermarkBoxes.toList(),
                phrases = editablePhrases.toList()
            )
        )
        if (undoStack.size > 25) undoStack.removeAt(0)
        redoStack.clear()
    }

    fun performUndo() {
        val snap = undoStack.removeLastOrNull() ?: return
        redoStack.add(
            StudioUndoSnapshot(
                segments = segments.toList(),
                splitTopRatio = splitTopRatio,
                primaryZoom = primaryZoom,
                primaryPanX = primaryPanX,
                primaryPanY = primaryPanY,
                bgZoom = bgZoom,
                bgPanX = bgPanX,
                bgPanY = bgPanY,
                watermarkBoxes = watermarkBoxes.toList(),
                phrases = editablePhrases.toList()
            )
        )
        segments = snap.segments
        splitTopRatio = snap.splitTopRatio
        primaryZoom = snap.primaryZoom
        primaryPanX = snap.primaryPanX
        primaryPanY = snap.primaryPanY
        bgZoom = snap.bgZoom
        bgPanX = snap.bgPanX
        bgPanY = snap.bgPanY
        watermarkBoxes = snap.watermarkBoxes
        editablePhrases = snap.phrases
    }

    fun performRedo() {
        val snap = redoStack.removeLastOrNull() ?: return
        undoStack.add(
            StudioUndoSnapshot(
                segments = segments.toList(),
                splitTopRatio = splitTopRatio,
                primaryZoom = primaryZoom,
                primaryPanX = primaryPanX,
                primaryPanY = primaryPanY,
                bgZoom = bgZoom,
                bgPanX = bgPanX,
                bgPanY = bgPanY,
                watermarkBoxes = watermarkBoxes.toList(),
                phrases = editablePhrases.toList()
            )
        )
        segments = snap.segments
        splitTopRatio = snap.splitTopRatio
        primaryZoom = snap.primaryZoom
        primaryPanX = snap.primaryPanX
        primaryPanY = snap.primaryPanY
        bgZoom = snap.bgZoom
        bgPanX = snap.bgPanX
        bgPanY = snap.bgPanY
        watermarkBoxes = snap.watermarkBoxes
        editablePhrases = snap.phrases
    }

    // Probe primary video duration & extract Filmstrip thumbnails asynchronously
    LaunchedEffect(primaryFile?.absolutePath) {
        primaryFilmstrip.clear()
        undoStack.clear()
        redoStack.clear()
        val f = primaryFile ?: return@LaunchedEffect
        if (!f.exists()) return@LaunchedEffect

        val dur = withContext(Dispatchers.IO) {
            hardwareEngine.probeDurationSeconds(f).coerceAtLeast(1.0)
        }
        sourceTotalDurSec = dur
        val s0 = initialStartSec.coerceIn(0.0, (dur - 0.5).coerceAtLeast(0.0))
        val e0 = if (initialEndSec > s0 + 0.5) initialEndSec.coerceAtMost(dur) else dur
        segments = listOf(ClipTimeSegment(startSec = s0, endSec = e0, kept = true))
        selectedSegIndex = 0

        val thumbs = extractFilmstripThumbnails(f, dur, count = 14)
        primaryFilmstrip.addAll(thumbs)
    }

    // Extract Background Filmstrip thumbnails asynchronously
    LaunchedEffect(backgroundFile?.absolutePath) {
        bgFilmstrip.clear()
        val bf = backgroundFile ?: return@LaunchedEffect
        if (!bf.exists()) return@LaunchedEffect
        val bDur = withContext(Dispatchers.IO) {
            hardwareEngine.probeDurationSeconds(bf).coerceAtLeast(1.0)
        }
        val thumbs = extractFilmstripThumbnails(bf, bDur, count = 10)
        bgFilmstrip.addAll(thumbs)
    }

    val keptSegments = remember(segments) {
        segments.filter { it.kept && it.endSec > it.startSec + 0.12 }
    }
    val timelineTotalSec = remember(keptSegments, playbackSpeed) {
        (keptSegments.sumOf { it.durationSec } / playbackSpeed.coerceIn(0.5f, 2.0f)).coerceAtLeast(0.5)
    }

    // Dual hardware Media3 ExoPlayers
    var isPlaying by remember { mutableStateOf(false) }
    var timelinePosSec by remember { mutableDoubleStateOf(0.0) }
    var timelineDpPerSec by remember { mutableFloatStateOf(46f) }

    val primaryPlayer = remember(context) {
        ExoPlayer.Builder(context).build().apply {
            setSeekParameters(SeekParameters.EXACT)
            repeatMode = Player.REPEAT_MODE_ALL
        }
    }
    val bgPlayer = remember(context) {
        ExoPlayer.Builder(context).build().apply {
            setSeekParameters(SeekParameters.EXACT)
            repeatMode = Player.REPEAT_MODE_ALL
            volume = 0f
        }
    }

    DisposableEffect(primaryPlayer, bgPlayer) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
                if (backgroundFile != null && backgroundFile.exists()) {
                    if (playing && !bgPlayer.isPlaying) bgPlayer.play()
                    if (!playing && bgPlayer.isPlaying) bgPlayer.pause()
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

    // Rebuild primary ExoPlayer ClippingConfiguration playlist whenever segments change (instant 0-wait ripple cut!)
    LaunchedEffect(primaryFile?.absolutePath, keptSegments) {
        val pf = primaryFile
        if (pf != null && pf.exists() && keptSegments.isNotEmpty()) {
            val items = keptSegments.map { seg ->
                MediaItem.Builder()
                    .setUri(Uri.fromFile(pf))
                    .setClippingConfiguration(
                        MediaItem.ClippingConfiguration.Builder()
                            .setStartPositionMs((seg.startSec * 1000.0).roundToLong())
                            .setEndPositionMs((seg.endSec * 1000.0).roundToLong())
                            .build()
                    )
                    .build()
            }
            val wasPlaying = primaryPlayer.isPlaying
            primaryPlayer.setMediaItems(items, true)
            primaryPlayer.prepare()
            primaryPlayer.playWhenReady = wasPlaying
        } else {
            primaryPlayer.clearMediaItems()
        }
    }

    LaunchedEffect(backgroundFile?.absolutePath) {
        val bf = backgroundFile
        if (bf != null && bf.exists()) {
            bgPlayer.setMediaItem(MediaItem.fromUri(Uri.fromFile(bf)))
            bgPlayer.prepare()
            bgPlayer.volume = bgVolume.coerceIn(0f, 1.5f)
            if (primaryPlayer.isPlaying) bgPlayer.play()
        } else {
            bgPlayer.clearMediaItems()
        }
    }

    LaunchedEffect(playbackSpeed) {
        primaryPlayer.playbackParameters = PlaybackParameters(playbackSpeed.coerceIn(0.5f, 2.0f))
    }

    LaunchedEffect(mainVolume, bgVolume) {
        primaryPlayer.volume = mainVolume.coerceIn(0f, 1.5f)
        bgPlayer.volume = bgVolume.coerceIn(0f, 1.5f)
    }

    // Map multi-item ExoPlayer position -> unified ripple timeline seconds
    fun readCurrentTimelineSec(): Double {
        val idx = primaryPlayer.currentMediaItemIndex.coerceIn(0, (keptSegments.size - 1).coerceAtLeast(0))
        val prefixDur = keptSegments.take(idx).sumOf { it.durationSec }
        val inClipSec = (primaryPlayer.currentPosition.coerceAtLeast(0L)) / 1000.0
        return ((prefixDur + inClipSec) / playbackSpeed.coerceIn(0.5f, 2.0f)).coerceIn(0.0, timelineTotalSec)
    }

    fun seekRippleTimelineTo(targetTimelineSec: Double) {
        val clamped = targetTimelineSec.coerceIn(0.0, timelineTotalSec)
        timelinePosSec = clamped
        val unscaledTarget = clamped * playbackSpeed.coerceIn(0.5f, 2.0f)
        var acc = 0.0
        for ((idx, seg) in keptSegments.withIndex()) {
            if (unscaledTarget <= acc + seg.durationSec || idx == keptSegments.lastIndex) {
                val offsetInSegMs = ((unscaledTarget - acc).coerceIn(0.0, seg.durationSec) * 1000.0).roundToLong()
                primaryPlayer.seekTo(idx, offsetInSegMs)
                selectedSegIndex = idx
                break
            }
            acc += seg.durationSec
        }
        if (backgroundFile != null && bgPlayer.duration > 0) {
            val bgMs = ((clamped * 1000.0).roundToLong()) % bgPlayer.duration.coerceAtLeast(1000L)
            bgPlayer.seekTo(bgMs)
        }
    }

    // Horizontal Scroll State for Filmstrip Timeline
    val timelineScrollState = rememberScrollState()
    var isUserScrubbingTimeline by remember { mutableStateOf(false) }

    // 60fps Playhead & Timeline Auto-Scroll Loop
    LaunchedEffect(primaryPlayer, timelineDpPerSec, keptSegments, playbackSpeed) {
        while (isActive) {
            if (primaryPlayer.isPlaying && !isUserScrubbingTimeline) {
                val cur = readCurrentTimelineSec()
                timelinePosSec = cur
                val targetPx = with(density) { (cur.toFloat() * timelineDpPerSec).dp.roundToPx() }
                if (!timelineScrollState.isScrollInProgress) {
                    timelineScrollState.scrollTo(targetPx)
                }
            }
            delay(16L)
        }
    }

    // When user drags the filmstrip timeline manually while paused/scrubbing -> seek video live!
    LaunchedEffect(timelineScrollState.value, timelineScrollState.isScrollInProgress) {
        if (timelineScrollState.isScrollInProgress) {
            if (primaryPlayer.isPlaying) primaryPlayer.pause()
            val pxPerSec = with(density) { timelineDpPerSec.dp.toPx() }.coerceAtLeast(1f)
            val targetSec = (timelineScrollState.value / pxPerSec).toDouble().coerceIn(0.0, timelineTotalSec)
            seekRippleTimelineTo(targetSec)
        }
    }

    // Split active segment right under the white center needle (1-tap YouCut Split!)
    fun splitAtWhitePlayhead() {
        if (keptSegments.isEmpty()) return
        val unscaledTarget = timelinePosSec * playbackSpeed.coerceIn(0.5f, 2.0f)
        var acc = 0.0
        for ((idx, seg) in keptSegments.withIndex()) {
            if (unscaledTarget <= acc + seg.durationSec || idx == keptSegments.lastIndex) {
                val cutPoint = seg.startSec + (unscaledTarget - acc)
                if (cutPoint <= seg.startSec + 0.25 || cutPoint >= seg.endSec - 0.25) {
                    onShowMessage("Поставь белую иглу минимум в 0.3с от края куска, чтобы разрезать")
                    return
                }
                pushUndoSnapshot()
                val leftPart = ClipTimeSegment(startSec = seg.startSec, endSec = cutPoint, kept = true)
                val rightPart = ClipTimeSegment(startSec = cutPoint, endSec = seg.endSec, kept = true)
                val next = keptSegments.toMutableList().apply {
                    removeAt(idx)
                    add(idx, rightPart)
                    add(idx, leftPart)
                }
                segments = next
                selectedSegIndex = idx + 1
                onShowMessage("✂️ Разрезано в ${formatTimeShort(timelinePosSec)}")
                return
            }
            acc += seg.durationSec
        }
    }

    fun deleteSelectedSegment() {
        if (keptSegments.size <= 1) {
            onShowMessage("Сначала нажми «✂️ Разделить», чтобы отделить лишний кусок, и затем удали его")
            return
        }
        val idx = selectedSegIndex.coerceIn(0, keptSegments.lastIndex)
        pushUndoSnapshot()
        val removed = keptSegments[idx]
        val next = keptSegments.toMutableList().apply { removeAt(idx) }
        segments = next
        selectedSegIndex = (idx - 1).coerceAtLeast(0)
        onShowMessage("🗑 Кусок (${String.format(Locale.US, "%.1fс", removed.durationSec)}) удалён, соседние склеены!")
    }

    // Convert Pinch-to-Zoom + Pan to ManualCropRect for 1:1 FFmpeg bake
    fun zoomPanToCropRect(zoom: Float, panX: Float, panY: Float): ManualCropRect {
        if (zoom <= 1.02f) return ManualCropRect(enabled = false)
        val span = (1f / zoom).coerceIn(0.25f, 1f)
        val maxSlack = (1f - span) / 2f
        val cx = (0.5f - panX.coerceIn(-1f, 1f) * maxSlack).coerceIn(span / 2f, 1f - span / 2f)
        val cy = (0.5f - panY.coerceIn(-1f, 1f) * maxSlack).coerceIn(span / 2f, 1f - span / 2f)
        return ManualCropRect(
            enabled = true,
            leftNorm = (cx - span / 2f).coerceIn(0f, 0.9f),
            topNorm = (cy - span / 2f).coerceIn(0f, 0.9f),
            rightNorm = (cx + span / 2f).coerceIn(0.1f, 1f),
            bottomNorm = (cy + span / 2f).coerceIn(0.1f, 1f)
        )
    }

    // Helper: flattens current ripple timeline + primary zoom/pan + watermarks + speed into clean intermediate MP4
    suspend fun prepareFlattenedPrimaryClip(): File {
        val pFile = primaryFile ?: throw IllegalStateException("Основное видео не выбрано")
        val pCrop = zoomPanToCropRect(primaryZoom, primaryPanX, primaryPanY)
        return hardwareEngine.bakePreparedClip(
            inputFile = pFile,
            segments = keptSegments,
            manualCrop = pCrop,
            watermarkBoxes = watermarkBoxes,
            eraseMethod = eraseMethod,
            playbackSpeed = playbackSpeed,
            prefix = "youcut_stage"
        )
    }

    // Run Variant 1B Speech Recognition on the currently edited timeline!
    fun runAutoSubtitlesFromVoice() {
        val pFile = primaryFile
        if (pFile == null || !pFile.exists()) {
            onShowMessage("Сначала открой основное видео")
            return
        }
        primaryPlayer.pause()
        isTranscribingSpeech = true
        sttStatusNote = "🎙 Собираю таймлайн и синхронизирую речь по звуковой волне FFmpeg + Gemini..."
        scope.launch {
            try {
                val flatClip = prepareFlattenedPrimaryClip()
                val (phrases, status) = subtitleTransformer.transcribePreparedClipToEditablePhrases(
                    preparedVideoFile = flatClip,
                    wordsPerPhrase = wordsPerPhraseSetting
                )
                if (phrases.isNotEmpty()) {
                    pushUndoSnapshot()
                    editablePhrases = phrases
                    subtitlesEnabled = true
                    selectedPhraseId = phrases.first().id
                    onShowMessage(status)
                } else {
                    onShowMessage(status)
                }
                sttStatusNote = status
            } catch (e: Exception) {
                sttStatusNote = "❌ Ошибка распознавания: ${e.message}"
                onShowMessage(sttStatusNote)
            } finally {
                isTranscribingSpeech = false
            }
        }
    }

    // Master Export (5s quick preview or FullHD 1080p to Gallery)
    fun launchExportRender(isPreview5s: Boolean) {
        val pFile = primaryFile
        if (pFile == null || !pFile.exists()) {
            onShowMessage("Сначала открой основное видео в Студии!")
            return
        }
        val needsFiller = layoutLayer == VideoLayoutLayer.SPLIT_VSTACK || layoutLayer == VideoLayoutLayer.BRAINROT_VOICEOVER
        if (needsFiller && (backgroundFile == null || !backgroundFile.exists())) {
            onShowMessage("Добавь фоновое видео на 2-ю дорожку или переключи «📐 Холст» на Весь экран 9:16!")
            activeTool = YouCutToolDrawer.BACKGROUND_TRACK
            return
        }

        primaryPlayer.pause()
        bgPlayer.pause()
        isRenderingMaster = true
        renderStatusLabel = if (isPreview5s) "⚡ Тестовая сборка 5с..." else "🚀 Склейка кусков таймлайна..."

        scope.launch {
            try {
                val flatPrimary = prepareFlattenedPrimaryClip()
                val flatDur = withContext(Dispatchers.IO) {
                    hardwareEngine.probeDurationSeconds(flatPrimary).coerceAtLeast(1.0)
                }
                val targetDur = if (isPreview5s) flatDur.coerceAtMost(5.0) else flatDur

                val preparedBgFile: File = if (needsFiller && backgroundFile != null) {
                    val bgCrop = zoomPanToCropRect(bgZoom, bgPanX, bgPanY)
                    if (bgCrop.isNonTrivial) {
                        val bgTotal = withContext(Dispatchers.IO) { hardwareEngine.probeDurationSeconds(backgroundFile).coerceAtLeast(1.0) }
                        hardwareEngine.bakePreparedClip(
                            inputFile = backgroundFile,
                            segments = listOf(ClipTimeSegment(startSec = 0.0, endSec = bgTotal, kept = true)),
                            manualCrop = bgCrop,
                            watermarkBoxes = emptyList(),
                            eraseMethod = WatermarkEraseMethod.DELOGO_INPAINT,
                            prefix = "youcut_bg"
                        )
                    } else {
                        backgroundFile
                    }
                } else {
                    flatPrimary
                }

                renderStatusLabel = "👁 Проверка лиц и шва 50/50..."
                val framingPlan = if (enableSmartFaceAndSeamCrop) {
                    faceHelper.analyzeAndBuildFramingPlan(
                        videoFile = flatPrimary,
                        durationSeconds = targetDur,
                        oldSubRemovalMode = oldSubRemovalMode
                    )
                } else {
                    faceHelper.buildFallbackCenterPlan(flatPrimary)
                }

                val subCfg = SubtitleLayerConfig(
                    enabled = subtitlesEnabled && editablePhrases.isNotEmpty(),
                    placementZone = SubtitlePlacementZone.CUSTOM,
                    customMarginV = subtitleCustomY,
                    fontSizePx = subtitleFontSizePx,
                    syncOffsetMs = subtitleSyncOffsetMs,
                    densityMode = SubtitleDensityMode.KARAOKE_3_WORDS
                )

                val compiledAss: File? = if (subCfg.enabled) {
                    renderStatusLabel = "💬 Запекание караоке-субтитров..."
                    val (cw, ch) = hardwareEngine.computeOutputCanvasDimensions(
                        flatPrimary,
                        ModularRenderConfig(layoutLayer = layoutLayer)
                    )
                    subtitleTransformer.compileEditablePhrasesToAssFile(
                        phrases = editablePhrases,
                        config = subCfg,
                        playResX = cw,
                        playResY = ch
                    )
                } else {
                    null
                }

                val effectiveVoiceover = importedVoiceoverFile ?: generatedVoiceoverFile
                val renderCfg = ModularRenderConfig(
                    layoutLayer = layoutLayer,
                    splitTopRatio = splitTopRatio,
                    manualCrop = ManualCropRect(enabled = false), // Already baked into flatPrimary!
                    watermarkBoxes = emptyList(), // Already baked into flatPrimary!
                    playbackSpeed = 1.0f, // Already baked into flatPrimary!
                    mainVolume = mainVolume,
                    bgVolume = bgVolume,
                    voiceoverVolume = voiceoverVolume,
                    musicVolume = musicVolume,
                    enableSmartFaceCrop = enableSmartFaceAndSeamCrop,
                    trimStartSec = 0.0,
                    trimEndSec = targetDur,
                    customVoiceoverAudioFile = effectiveVoiceover,
                    customReplacementMusicFile = replacementMusicFile,
                    enableReplacementMusicBed = replacementMusicFile != null,
                    separatedStems = separatedStems,
                    existingSubtitleRemovalMode = oldSubRemovalMode,
                    detectedHardsubRegion = framingPlan.detectedHardsubRegion,
                    subtitleLayer = subCfg,
                    dspLayer = AntiDetectDspLayerConfig(
                        enabled = antiDetectEnabled,
                        enableMicroZoom = antiDetectMicroZoom,
                        enableHorizontalFlip = antiDetectFlip,
                        enableAudioPitchTempoMicroJitter = antiDetectPitchShift,
                        musicRemovalMode = musicRemovalMode,
                        voiceoverDubMode = voiceoverDubMode,
                        stemRoutingMode = stemRoutingMode
                    ),
                    videoBitrateKbps = 12_000,
                    capBitrateForTelegram50Mb = false
                )

                renderStatusLabel = if (isPreview5s) "🎬 Аппаратный рендер 5с..." else "🎬 Финальный рендер FullHD 1080p..."
                val outMp4 = if (isPreview5s) {
                    hardwareEngine.renderQuickPreviewSample(
                        primaryVideoFile = flatPrimary,
                        clutterClipFile = preparedBgFile,
                        assSubtitleFile = compiledAss,
                        framingPlan = framingPlan,
                        config = renderCfg
                    )
                } else {
                    hardwareEngine.composeModularMaster(
                        primaryVideoFile = flatPrimary,
                        clutterClipFile = preparedBgFile,
                        assSubtitleFile = compiledAss,
                        framingPlan = framingPlan,
                        durationSeconds = targetDur,
                        config = renderCfg
                    )
                }

                if (isPreview5s) {
                    previewModalVideoFile = outMp4
                } else {
                    val saved = hardwareEngine.saveRenderedVideoToGallery(outMp4, primaryTitle.ifBlank { "Forge" })
                    onShowMessage("✅ Сохранено в Галерею: ${saved.name}")
                }
            } catch (e: Exception) {
                onShowMessage("❌ Ошибка экспорта: ${e.message}")
            } finally {
                isRenderingMaster = false
                renderStatusLabel = ""
            }
        }
    }

    // Active subtitle phrase at current playhead for live 60fps karaoke overlay
    val effectiveSubClock = timelinePosSec - (subtitleSyncOffsetMs / 1000.0)
    val activeLivePhrase = remember(editablePhrases, effectiveSubClock, subtitlesEnabled) {
        if (!subtitlesEnabled) null
        else editablePhrases.firstOrNull { effectiveSubClock >= it.startSec - 0.03 && effectiveSubClock <= it.endSec + 0.06 }
    }

    // =========================================================================
    // DIALOGS (Source Picker, Background Filler Picker, 5s Rendered Preview)
    // =========================================================================
    if (showSourcePickerDialog) {
        val inbox = remember { clutterFilter.listStudioInboxItems() }
        AlertDialog(
            onDismissRequest = { showSourcePickerDialog = false },
            title = { Text("🎬 Выбери основное видео", fontSize = 16.sp, fontWeight = FontWeight.Bold) },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = {
                            showSourcePickerDialog = false
                            onPickPrimaryVideoFromPhone()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2563EB)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("📱 Открыть видео из памяти телефона")
                    }
                    OutlinedButton(
                        onClick = {
                            showSourcePickerDialog = false
                            onJumpToSearchTab(false)
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("🔎 Найти свежее видео в Поиске")
                    }
                    if (inbox.isNotEmpty()) {
                        Text("Скачанные исходники (${inbox.size}):", fontSize = 12.sp, color = Color(0xFF9CA3AF))
                        inbox.forEach { item ->
                            Surface(
                                color = Color(0xFF1A2030),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        showSourcePickerDialog = false
                                        onSelectPrimaryFromInbox(item.videoFile, item.title, item.sourceUrl)
                                    }
                            ) {
                                Text(
                                    text = "🎬 ${item.title}",
                                    color = Color.White,
                                    fontSize = 12.sp,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(10.dp)
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showSourcePickerDialog = false }) { Text("Закрыть") }
            }
        )
    }

    if (showBgPickerDialog) {
        val poolClips = remember { clutterFilter.listLocalPoolClips() }
        AlertDialog(
            onDismissRequest = { showBgPickerDialog = false },
            title = { Text("🕹 Выбери фон-наполнитель (2-я дорожка)", fontSize = 16.sp, fontWeight = FontWeight.Bold) },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        Button(
                            onClick = {
                                showBgPickerDialog = false
                                onPickBackgroundFromPhone()
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("📱 С телефона", fontSize = 12.sp)
                        }
                        OutlinedButton(
                            onClick = {
                                showBgPickerDialog = false
                                onJumpToSearchTab(true)
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("🔎 Искать фоны", fontSize = 12.sp)
                        }
                    }
                    if (backgroundFile != null) {
                        TextButton(
                            onClick = {
                                showBgPickerDialog = false
                                onSelectBackgroundFile(null, "")
                            }
                        ) {
                            Text("🗑 Убрать фон со 2-й дорожки", color = Color(0xFFF87171), fontSize = 12.sp)
                        }
                    }
                    if (poolClips.isEmpty()) {
                        Text(
                            "Список сохранённых фонов пока пуст. Зайди в «🔎 Поиск -> Фоны до СТОПА» и сохрани чистые геймплеи/мыло.",
                            color = Color(0xFF9CA3AF),
                            fontSize = 12.sp
                        )
                    } else {
                        poolClips.forEach { clip ->
                            Surface(
                                color = Color(0xFF1A2030),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        showBgPickerDialog = false
                                        onSelectBackgroundFile(clip, clip.nameWithoutExtension)
                                    }
                            ) {
                                Row(
                                    modifier = Modifier.padding(10.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "🕹 ${clip.name}",
                                        color = Color.White,
                                        fontSize = 12.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Text(
                                        text = "${clip.length() / (1024 * 1024)} МБ",
                                        color = Color(0xFF6EE7B7),
                                        fontSize = 11.sp
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showBgPickerDialog = false }) { Text("Закрыть") }
            }
        )
    }

    previewModalVideoFile?.let { pvFile ->
        AlertDialog(
            onDismissRequest = { previewModalVideoFile = null },
            title = { Text("👁 Готовый тест 5с (с эффектами и караоке)", fontSize = 15.sp, fontWeight = FontWeight.Bold) },
            text = {
                val modalPlayer = remember(pvFile.absolutePath) {
                    ExoPlayer.Builder(context).build().apply {
                        setMediaItem(MediaItem.fromUri(Uri.fromFile(pvFile)))
                        repeatMode = Player.REPEAT_MODE_ONE
                        prepare()
                        playWhenReady = true
                    }
                }
                DisposableEffect(modalPlayer) { onDispose { modalPlayer.release() } }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(320.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color.Black)
                ) {
                    AndroidView(
                        factory = { ctx ->
                            PlayerView(ctx).apply {
                                useController = true
                                player = modalPlayer
                            }
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { previewModalVideoFile = null }) { Text("Закрыть") }
            }
        )
    }

    // =========================================================================
    // MAIN YOUCUT SINGLE-SCREEN LAYOUT
    // =========================================================================
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0A0C10))
    ) {
        // 1. TOP YOUCUT HEADER BAR
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF11141D))
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF1B2030))
                    .clickable { showSourcePickerDialog = true }
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            ) {
                Text(
                    text = if (primaryFile != null) "🎬 ${primaryTitle.ifBlank { primaryFile.name }}" else "➕ Открыть видео",
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(Modifier.width(8.dp))

            OutlinedButton(
                onClick = { launchExportRender(isPreview5s = true) },
                enabled = !isRenderingMaster && primaryFile != null,
                modifier = Modifier.height(34.dp)
            ) {
                Text("👁 5с", fontSize = 11.sp)
            }

            Spacer(Modifier.width(6.dp))

            Button(
                onClick = { launchExportRender(isPreview5s = false) },
                enabled = !isRenderingMaster && primaryFile != null,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF97316)), // Signature YouCut Orange Save button
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.height(34.dp)
            ) {
                Text("Сохранить 1080p", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White)
            }
        }

        if (isRenderingMaster) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF1E293B))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = Color(0xFFF97316))
                Text(renderStatusLabel, color = Color(0xFFFDE68A), fontSize = 12.sp, fontWeight = FontWeight.Medium)
            }
        }

        // 2. LIVE 9:16 INTERACTIVE CANVAS (Pinch-to-Zoom, Pan, Draggable Seam, Karaoke, Watermark Boxes)
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(vertical = 4.dp),
            contentAlignment = Alignment.Center
        ) {
            val availH = maxHeight
            val stageH = availH.coerceIn(190.dp, 390.dp)
            val stageW = stageH * (9f / 16f)
            val stageHeightPx = with(density) { stageH.toPx() }.coerceAtLeast(1f)
            var canvasIntSize by remember { mutableStateOf(IntSize(1, 1)) }

            if (primaryFile == null || !primaryFile.exists()) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                    modifier = Modifier
                        .size(width = stageW, height = stageH)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFF141824))
                        .border(1.dp, Color(0xFF2B3245), RoundedCornerShape(12.dp))
                        .clickable { showSourcePickerDialog = true }
                        .padding(16.dp)
                ) {
                    Text("➕", fontSize = 34.sp, color = Color(0xFFF97316))
                    Spacer(Modifier.height(6.dp))
                    Text("Новый проект", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Нажми сюда, чтобы выбрать скачанный исходник или видео с телефона",
                        color = Color(0xFF9CA3AF),
                        fontSize = 11.sp,
                        textAlign = TextAlign.Center
                    )
                }
            } else {
                Box(
                    modifier = Modifier
                        .size(width = stageW, height = stageH)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color.Black)
                        .border(1.dp, Color(0xFF2B3245), RoundedCornerShape(10.dp))
                        .onSizeChanged { canvasIntSize = it }
                ) {
                    if (layoutLayer == VideoLayoutLayer.SPLIT_VSTACK) {
                        Column(modifier = Modifier.fillMaxSize()) {
                            // UPPER SLOT: Primary Video with Pinch-to-Zoom & Pan
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(splitTopRatio.coerceIn(0.22f, 0.78f))
                                    .clipToBounds()
                                    .border(
                                        width = if (selectedCanvasSlot == 0) 1.5.dp else 0.dp,
                                        color = if (selectedCanvasSlot == 0) Color(0xFFF97316) else Color.Transparent
                                    )
                                    .pointerInput(activeTool == YouCutToolDrawer.WATERMARK) {
                                        if (activeTool != YouCutToolDrawer.WATERMARK) {
                                            detectTransformGestures { _, pan, zoom, _ ->
                                                selectedCanvasSlot = 0
                                                val newZoom = (primaryZoom * zoom).coerceIn(1.0f, 3.5f)
                                                primaryZoom = newZoom
                                                if (newZoom > 1.01f) {
                                                    val maxDx = (size.width * (newZoom - 1f) / 2f).coerceAtLeast(1f)
                                                    val maxDy = (size.height * (newZoom - 1f) / 2f).coerceAtLeast(1f)
                                                    primaryPanX = (primaryPanX + pan.x / maxDx).coerceIn(-1f, 1f)
                                                    primaryPanY = (primaryPanY + pan.y / maxDy).coerceIn(-1f, 1f)
                                                } else {
                                                    primaryPanX = 0f
                                                    primaryPanY = 0f
                                                }
                                            }
                                        }
                                    }
                            ) {
                                AndroidView(
                                    factory = { ctx ->
                                        PlayerView(ctx).apply {
                                            useController = false
                                            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                                            player = primaryPlayer
                                        }
                                    },
                                    update = { it.player = primaryPlayer },
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .graphicsLayer {
                                            scaleX = primaryZoom
                                            scaleY = primaryZoom
                                            translationX = primaryPanX * (size.width * (primaryZoom - 1f) / 2f)
                                            translationY = primaryPanY * (size.height * (primaryZoom - 1f) / 2f)
                                        }
                                )
                                if (primaryZoom > 1.03f) {
                                    Text(
                                        text = "${String.format(Locale.US, "%.1fx", primaryZoom)} ↺",
                                        color = Color.White,
                                        fontSize = 9.sp,
                                        modifier = Modifier
                                            .align(Alignment.TopEnd)
                                            .padding(4.dp)
                                            .clip(RoundedCornerShape(4.dp))
                                            .background(Color(0xBB000000))
                                            .clickable {
                                                primaryZoom = 1f
                                                primaryPanX = 0f
                                                primaryPanY = 0f
                                            }
                                            .padding(horizontal = 5.dp, vertical = 2.dp)
                                    )
                                }
                            }

                            // LOWER SLOT: Background Filler with Pinch-to-Zoom & Pan
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight((1f - splitTopRatio).coerceIn(0.22f, 0.78f))
                                    .clipToBounds()
                                    .background(Color(0xFF10131C))
                                    .border(
                                        width = if (selectedCanvasSlot == 1) 1.5.dp else 0.dp,
                                        color = if (selectedCanvasSlot == 1) Color(0xFF38BDF8) else Color.Transparent
                                    )
                                    .pointerInput(backgroundFile?.absolutePath, activeTool == YouCutToolDrawer.WATERMARK) {
                                        if (backgroundFile != null && activeTool != YouCutToolDrawer.WATERMARK) {
                                            detectTransformGestures { _, pan, zoom, _ ->
                                                selectedCanvasSlot = 1
                                                val newZoom = (bgZoom * zoom).coerceIn(1.0f, 3.5f)
                                                bgZoom = newZoom
                                                if (newZoom > 1.01f) {
                                                    val maxDx = (size.width * (newZoom - 1f) / 2f).coerceAtLeast(1f)
                                                    val maxDy = (size.height * (newZoom - 1f) / 2f).coerceAtLeast(1f)
                                                    bgPanX = (bgPanX + pan.x / maxDx).coerceIn(-1f, 1f)
                                                    bgPanY = (bgPanY + pan.y / maxDy).coerceIn(-1f, 1f)
                                                } else {
                                                    bgPanX = 0f
                                                    bgPanY = 0f
                                                }
                                            }
                                        }
                                    },
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
                                        update = { it.player = bgPlayer },
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .graphicsLayer {
                                                scaleX = bgZoom
                                                scaleY = bgZoom
                                                translationX = bgPanX * (size.width * (bgZoom - 1f) / 2f)
                                                translationY = bgPanY * (size.height * (bgZoom - 1f) / 2f)
                                            }
                                    )
                                } else {
                                    Text(
                                        text = "➕ Нажми, чтобы выбрать фон-наполнитель",
                                        color = Color(0xFF93C5FD),
                                        fontSize = 10.sp,
                                        textAlign = TextAlign.Center,
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .clickable { showBgPickerDialog = true }
                                            .padding(8.dp)
                                    )
                                }
                            }
                        }

                        // Draggable Split Seam Handle
                        val seamYPx = (splitTopRatio * stageHeightPx).roundToInt()
                        Box(
                            modifier = Modifier
                                .offset { IntOffset(0, seamYPx - with(density) { 10.dp.roundToPx() }) }
                                .fillMaxWidth()
                                .height(20.dp)
                                .pointerInput(stageHeightPx) {
                                    detectVerticalDragGestures { change, dragAmount ->
                                        change.consume()
                                        splitTopRatio = (splitTopRatio + dragAmount / stageHeightPx).coerceIn(0.25f, 0.75f)
                                    }
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Box(modifier = Modifier.fillMaxWidth().height(2.dp).background(Color(0xCCF97316)))
                            Text(
                                text = "══ ${(splitTopRatio * 100).roundToInt()}% ══",
                                color = Color.Black,
                                fontSize = 8.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(Color(0xFFF97316))
                                    .padding(horizontal = 5.dp, vertical = 1.dp)
                            )
                        }
                    } else {
                        // Single-frame 9:16 / YouCut Crop Canvas with Pinch-to-Zoom & Pan
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .clipToBounds()
                                .pointerInput(activeTool == YouCutToolDrawer.WATERMARK) {
                                    if (activeTool != YouCutToolDrawer.WATERMARK) {
                                        detectTransformGestures { _, pan, zoom, _ ->
                                            val newZoom = (primaryZoom * zoom).coerceIn(1.0f, 3.5f)
                                            primaryZoom = newZoom
                                            if (newZoom > 1.01f) {
                                                val maxDx = (size.width * (newZoom - 1f) / 2f).coerceAtLeast(1f)
                                                val maxDy = (size.height * (newZoom - 1f) / 2f).coerceAtLeast(1f)
                                                primaryPanX = (primaryPanX + pan.x / maxDx).coerceIn(-1f, 1f)
                                                primaryPanY = (primaryPanY + pan.y / maxDy).coerceIn(-1f, 1f)
                                            } else {
                                                primaryPanX = 0f
                                                primaryPanY = 0f
                                            }
                                        }
                                    }
                                }
                        ) {
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
                                update = { view ->
                                    view.player = primaryPlayer
                                    view.resizeMode = if (layoutLayer == VideoLayoutLayer.FULLSCREEN_EDIT) {
                                        AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                                    } else {
                                        AspectRatioFrameLayout.RESIZE_MODE_FIT
                                    }
                                },
                                modifier = Modifier
                                    .fillMaxSize()
                                    .graphicsLayer {
                                        scaleX = primaryZoom
                                        scaleY = primaryZoom
                                        translationX = primaryPanX * (size.width * (primaryZoom - 1f) / 2f)
                                        translationY = primaryPanY * (size.height * (primaryZoom - 1f) / 2f)
                                    }
                            )
                        }
                    }

                    // Interactive Watermark Erase Boxes Overlay
                    if (watermarkBoxes.isNotEmpty() || activeTool == YouCutToolDrawer.WATERMARK) {
                        val latestBoxes by rememberUpdatedState(watermarkBoxes)
                        val latestSel by rememberUpdatedState(selectedWmIdx)
                        Canvas(
                            modifier = Modifier
                                .fillMaxSize()
                                .then(
                                    if (activeTool == YouCutToolDrawer.WATERMARK) {
                                        Modifier.pointerInput(canvasIntSize) {
                                            var resizing = false
                                            detectDragGestures(
                                                onDragStart = { pos ->
                                                    val w = canvasIntSize.width.toFloat().coerceAtLeast(1f)
                                                    val h = canvasIntSize.height.toFloat().coerceAtLeast(1f)
                                                    val hit = latestBoxes.indexOfFirst { b ->
                                                        val bx = b.xNorm * w
                                                        val by = b.yNorm * h
                                                        val bw = b.wNorm * w
                                                        val bh = b.hNorm * h
                                                        pos.x in (bx - 24f)..(bx + bw + 24f) && pos.y in (by - 24f)..(by + bh + 24f)
                                                    }
                                                    if (hit >= 0) {
                                                        selectedWmIdx = hit
                                                        val b = latestBoxes[hit]
                                                        val brX = (b.xNorm + b.wNorm) * w
                                                        val brY = (b.yNorm + b.hNorm) * h
                                                        resizing = hypot(pos.x - brX, pos.y - brY) <= 38f
                                                    }
                                                },
                                                onDrag = { change, drag ->
                                                    change.consume()
                                                    val idx = latestSel
                                                    if (idx in latestBoxes.indices) {
                                                        val w = canvasIntSize.width.toFloat().coerceAtLeast(1f)
                                                        val h = canvasIntSize.height.toFloat().coerceAtLeast(1f)
                                                        val b = latestBoxes[idx]
                                                        val updated = if (resizing) {
                                                            b.copy(
                                                                wNorm = (b.wNorm + drag.x / w).coerceIn(0.06f, 1f - b.xNorm),
                                                                hNorm = (b.hNorm + drag.y / h).coerceIn(0.04f, 1f - b.yNorm)
                                                            )
                                                        } else {
                                                            b.copy(
                                                                xNorm = (b.xNorm + drag.x / w).coerceIn(0f, 1f - b.wNorm),
                                                                yNorm = (b.yNorm + drag.y / h).coerceIn(0f, 1f - b.hNorm)
                                                            )
                                                        }
                                                        watermarkBoxes = latestBoxes.toMutableList().apply { set(idx, updated) }
                                                    }
                                                }
                                            )
                                        }
                                    } else {
                                        Modifier
                                    }
                                )
                        ) {
                            watermarkBoxes.forEachIndexed { i, b ->
                                val bx = b.xNorm * size.width
                                val by = b.yNorm * size.height
                                val bw = b.wNorm * size.width
                                val bh = b.hNorm * size.height
                                val sel = i == selectedWmIdx
                                drawRect(
                                    color = if (sel) Color(0x55EF4444) else Color(0x33F59E0B),
                                    topLeft = Offset(bx, by),
                                    size = Size(bw, bh)
                                )
                                drawRect(
                                    color = if (sel) Color(0xFFEF4444) else Color(0xFFF59E0B),
                                    topLeft = Offset(bx, by),
                                    size = Size(bw, bh),
                                    style = Stroke(width = 2.dp.toPx())
                                )
                                if (sel && activeTool == YouCutToolDrawer.WATERMARK) {
                                    drawCircle(Color.White, radius = 6.dp.toPx(), center = Offset(bx + bw, by + bh))
                                }
                            }
                        }
                    }

                    // Live 60fps Karaoke Subtitles Overlay (Draggable vertically!)
                    if (activeLivePhrase != null) {
                        val subYPx = ((subtitleCustomY / 1920f).coerceIn(0.10f, 0.90f) * stageHeightPx).roundToInt()
                        Box(
                            modifier = Modifier
                                .offset { IntOffset(0, subYPx - with(density) { 18.dp.roundToPx() }) }
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp)
                                .pointerInput(stageHeightPx) {
                                    detectVerticalDragGestures { change, drag ->
                                        change.consume()
                                        val delta = ((drag / stageHeightPx) * 1920f).roundToInt()
                                        subtitleCustomY = (subtitleCustomY + delta).coerceIn(200, 1740)
                                    }
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            val styled = buildAnnotatedString {
                                activeLivePhrase.words.forEachIndexed { idx, w ->
                                    val active = effectiveSubClock >= w.startSec - 0.02 && effectiveSubClock <= w.endSec + 0.04
                                    withStyle(
                                        SpanStyle(
                                            color = if (active) Color(0xFFFFEA00) else Color.White,
                                            fontWeight = FontWeight.ExtraBold,
                                            background = Color(0xAA000000)
                                        )
                                    ) {
                                        append(w.text)
                                    }
                                    if (idx < activeLivePhrase.words.lastIndex) append(" ")
                                }
                            }
                            Text(
                                text = styled,
                                fontSize = (subtitleFontSizePx / 4.3f).coerceIn(11f, 22f).sp,
                                textAlign = TextAlign.Center,
                                lineHeight = 19.sp
                            )
                        }
                    }
                }
            }
        }

        // 3. SLIM YOUCUT TRANSPORT & UNDO/REDO STRIP
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF121620))
                .padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(Color(0xFFF97316))
                        .clickable(enabled = primaryFile != null) {
                            if (primaryPlayer.isPlaying) primaryPlayer.pause() else primaryPlayer.play()
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Text(if (isPlaying) "⏸" else "▶", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
                Text(
                    text = "${formatTimeShort(timelinePosSec)} / ${formatTimeShort(timelineTotalSec)}",
                    color = Color(0xFFE5E7EB),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(
                    onClick = { timelineDpPerSec = (timelineDpPerSec - 12f).coerceIn(24f, 96f) },
                    modifier = Modifier.height(28.dp)
                ) { Text("🔍-", fontSize = 11.sp) }
                TextButton(
                    onClick = { timelineDpPerSec = (timelineDpPerSec + 12f).coerceIn(24f, 96f) },
                    modifier = Modifier.height(28.dp)
                ) { Text("🔍+", fontSize = 11.sp) }
                TextButton(
                    onClick = { performUndo() },
                    enabled = undoStack.isNotEmpty(),
                    modifier = Modifier.height(28.dp)
                ) { Text("↩️", fontSize = 13.sp) }
                TextButton(
                    onClick = { performRedo() },
                    enabled = redoStack.isNotEmpty(),
                    modifier = Modifier.height(28.dp)
                ) { Text("↪️", fontSize = 13.sp) }
            }
        }

        // 4. REAL YOUCUT MULTI-TRACK SCROLLABLE FILMSTRIP TIMELINE WITH FIXED WHITE PLAYHEAD NEEDLE
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .height(146.dp)
                .background(Color(0xFF0D1017))
        ) {
            val halfScreenDp = maxWidth / 2
            val totalTimelineWidthDp = (timelineTotalSec.toFloat() * timelineDpPerSec).coerceAtLeast(40f).dp

            // Scrollable Multi-Track Content
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .horizontalScroll(timelineScrollState)
                    .pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent()
                                isUserScrubbingTimeline = event.changes.any { it.pressed }
                            }
                        }
                    }
            ) {
                Spacer(Modifier.width(halfScreenDp))

                Column(
                    modifier = Modifier
                        .width(totalTimelineWidthDp)
                        .fillMaxHeight()
                        .padding(vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    // TRACK 1: PRIMARY VIDEO FILMSTRIP WITH YOUCUT TRIM HANDLES
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (primaryFile == null) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(Color(0xFF1A2030))
                                    .clickable { showSourcePickerDialog = true },
                                contentAlignment = Alignment.Center
                            ) {
                                Text("➕ Основное видео", color = Color(0xFF9CA3AF), fontSize = 11.sp)
                            }
                        } else {
                            keptSegments.forEachIndexed { idx, seg ->
                                val segEffectiveDur = (seg.durationSec / playbackSpeed.coerceIn(0.5f, 2.0f)).toFloat()
                                val segW = (segEffectiveDur * timelineDpPerSec).coerceAtLeast(22f).dp
                                val isSel = idx == selectedSegIndex

                                Box(
                                    modifier = Modifier
                                        .width(segW)
                                        .fillMaxHeight()
                                        .padding(horizontal = 1.dp)
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(Color(0xFF1E293B))
                                        .border(
                                            width = if (isSel) 2.dp else 1.dp,
                                            color = if (isSel) Color.White else Color(0xFF334155),
                                            shape = RoundedCornerShape(6.dp)
                                        )
                                        .clickable { selectedSegIndex = idx }
                                ) {
                                    // Render actual video frame filmstrip inside segment!
                                    if (primaryFilmstrip.isNotEmpty() && sourceTotalDurSec > 0.1) {
                                        val numTiles = (segW.value / 38f).roundToInt().coerceIn(1, 16)
                                        Row(modifier = Modifier.fillMaxSize()) {
                                            for (t in 0 until numTiles) {
                                                val sampleSec = seg.startSec + (seg.durationSec * (t + 0.5) / numTiles)
                                                val fIdx = ((sampleSec / sourceTotalDurSec) * primaryFilmstrip.size)
                                                    .toInt()
                                                    .coerceIn(0, primaryFilmstrip.lastIndex)
                                                Image(
                                                    bitmap = primaryFilmstrip[fIdx],
                                                    contentDescription = null,
                                                    contentScale = ContentScale.Crop,
                                                    modifier = Modifier
                                                        .weight(1f)
                                                        .fillMaxHeight()
                                                )
                                            }
                                        }
                                    }

                                    Text(
                                        text = String.format(Locale.US, "%.1fс", segEffectiveDur),
                                        color = Color.White,
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier
                                            .align(Alignment.BottomStart)
                                            .padding(start = 10.dp, bottom = 2.dp)
                                            .background(Color(0x99000000), RoundedCornerShape(3.dp))
                                            .padding(horizontal = 3.dp)
                                    )

                                    // Left & Right White YouCut Trim Handles when selected!
                                    if (isSel) {
                                        val pxPerSec = with(density) { timelineDpPerSec.dp.toPx() }.coerceAtLeast(1f)
                                        Box(
                                            modifier = Modifier
                                                .align(Alignment.CenterStart)
                                                .width(10.dp)
                                                .fillMaxHeight()
                                                .background(Color.White)
                                                .pointerInput(idx, pxPerSec) {
                                                    detectDragGestures(
                                                        onDragStart = { pushUndoSnapshot() },
                                                        onDrag = { change, drag ->
                                                            change.consume()
                                                            val curSeg = keptSegments.getOrNull(idx) ?: return@detectDragGestures
                                                            val deltaSec = (drag.x / pxPerSec).toDouble() * playbackSpeed
                                                            val newStart = (curSeg.startSec + deltaSec)
                                                                .coerceIn(0.0, (curSeg.endSec - 0.35).coerceAtLeast(0.0))
                                                            segments = keptSegments.toMutableList().apply {
                                                                set(idx, curSeg.copy(startSec = newStart))
                                                            }
                                                        }
                                                    )
                                                },
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text("‹", color = Color.Black, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold)
                                        }

                                        Box(
                                            modifier = Modifier
                                                .align(Alignment.CenterEnd)
                                                .width(10.dp)
                                                .fillMaxHeight()
                                                .background(Color.White)
                                                .pointerInput(idx, pxPerSec) {
                                                    detectDragGestures(
                                                        onDragStart = { pushUndoSnapshot() },
                                                        onDrag = { change, drag ->
                                                            change.consume()
                                                            val curSeg = keptSegments.getOrNull(idx) ?: return@detectDragGestures
                                                            val deltaSec = (drag.x / pxPerSec).toDouble() * playbackSpeed
                                                            val newEnd = (curSeg.endSec + deltaSec)
                                                                .coerceIn(curSeg.startSec + 0.35, sourceTotalDurSec)
                                                            segments = keptSegments.toMutableList().apply {
                                                                set(idx, curSeg.copy(endSec = newEnd))
                                                            }
                                                        }
                                                    )
                                                },
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text("›", color = Color.Black, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold)
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // TRACK 2: BACKGROUND FILLER FILMSTRIP TRACK
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(36.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (backgroundFile != null) Color(0xFF132A3E) else Color(0xFF151924))
                            .border(1.dp, Color(0xFF1E3A5F), RoundedCornerShape(6.dp))
                            .clickable { showBgPickerDialog = true },
                        contentAlignment = Alignment.CenterStart
                    ) {
                        if (backgroundFile != null && bgFilmstrip.isNotEmpty()) {
                            val numTiles = (totalTimelineWidthDp.value / 42f).roundToInt().coerceIn(2, 30)
                            Row(modifier = Modifier.fillMaxSize()) {
                                for (t in 0 until numTiles) {
                                    Image(
                                        bitmap = bgFilmstrip[t % bgFilmstrip.size],
                                        contentDescription = null,
                                        contentScale = ContentScale.Crop,
                                        alpha = 0.72f,
                                        modifier = Modifier
                                            .weight(1f)
                                            .fillMaxHeight()
                                    )
                                }
                            }
                            Text(
                                text = "🕹 Фон: ${backgroundTitle.ifBlank { backgroundFile.name }} (Нажми для смены)",
                                color = Color.White,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                modifier = Modifier
                                    .padding(horizontal = 8.dp)
                                    .background(Color(0xAA000000), RoundedCornerShape(4.dp))
                                    .padding(horizontal = 5.dp, vertical = 1.dp)
                            )
                        } else {
                            Text(
                                text = "➕ Дорожка 2: Выбрать чистый фон-наполнитель",
                                color = Color(0xFF7DD3FC),
                                fontSize = 10.sp,
                                modifier = Modifier.padding(horizontal = 10.dp)
                            )
                        }
                    }

                    // TRACK 3: TIMED SUBTITLE PHRASES TRACK
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(30.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color(0xFF171426))
                            .border(1.dp, Color(0xFF312E81), RoundedCornerShape(6.dp))
                    ) {
                        if (editablePhrases.isEmpty()) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .clickable(enabled = !isTranscribingSpeech && primaryFile != null) {
                                        activeTool = YouCutToolDrawer.SUBTITLES
                                        runAutoSubtitlesFromVoice()
                                    },
                                contentAlignment = Alignment.CenterStart
                            ) {
                                Text(
                                    text = if (isTranscribingSpeech) {
                                        "⏳ Распознаю голос по звуковой волне..."
                                    } else {
                                        "✨ Дорожка 3: Авто-субтитры по звуку (Gemini + FFmpeg, 0 лишних ключей)"
                                    },
                                    color = Color(0xFFC4B5FD),
                                    fontSize = 10.sp,
                                    modifier = Modifier.padding(horizontal = 10.dp)
                                )
                            }
                        } else {
                            editablePhrases.forEach { phrase ->
                                val xDp = (phrase.startSec.toFloat() * timelineDpPerSec).coerceAtLeast(0f).dp
                                val wDp = ((phrase.endSec - phrase.startSec).toFloat() * timelineDpPerSec).coerceAtLeast(18f).dp
                                val isSel = phrase.id == selectedPhraseId
                                Box(
                                    modifier = Modifier
                                        .offset(x = xDp)
                                        .width(wDp)
                                        .fillMaxHeight()
                                        .padding(vertical = 2.dp, horizontal = 0.5.dp)
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(if (isSel) Color(0xFFF59E0B) else Color(0xFF4F46E5))
                                        .clickable {
                                            selectedPhraseId = phrase.id
                                            activeTool = YouCutToolDrawer.SUBTITLES
                                            seekRippleTimelineTo(phrase.startSec + 0.02)
                                        }
                                        .padding(horizontal = 4.dp),
                                    contentAlignment = Alignment.CenterStart
                                ) {
                                    Text(
                                        text = phrase.joinedText,
                                        color = if (isSel) Color.Black else Color.White,
                                        fontSize = 9.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.width(halfScreenDp))
            }

            // FIXED CENTER WHITE PLAYHEAD NEEDLE (Signature YouCut / CapCut Playhead)
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .width(2.5.dp)
                    .fillMaxHeight()
                    .background(Color.White)
            )
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .size(width = 10.dp, height = 6.dp)
                    .clip(RoundedCornerShape(bottomStart = 4.dp, bottomEnd = 4.dp))
                    .background(Color.White)
            )
        }

        // 5. COMPACT CONTEXTUAL DRAWER (Opens cleanly ONLY when a tool is tapped)
        if (activeTool != YouCutToolDrawer.NONE) {
            Surface(
                color = Color(0xFF141824),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(185.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "${activeTool.icon} ${activeTool.label}",
                            color = Color(0xFFF97316),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                        TextButton(onClick = { activeTool = YouCutToolDrawer.NONE }, modifier = Modifier.height(26.dp)) {
                            Text("Свернуть ✕", fontSize = 11.sp, color = Color(0xFF9CA3AF))
                        }
                    }

                    when (activeTool) {
                        YouCutToolDrawer.CANVAS_SPLIT -> {
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                listOf(
                                    VideoLayoutLayer.SPLIT_VSTACK to "Сплит (Верх + Фон)",
                                    VideoLayoutLayer.FULLSCREEN_EDIT to "Весь экран 9:16",
                                    VideoLayoutLayer.CLEAN_ORIGINAL_CROP to "Оригинал YouCut",
                                    VideoLayoutLayer.BLURRED_BG_FRAME to "Кино-рамка"
                                ).forEach { (mode, lbl) ->
                                    FilterChip(
                                        selected = layoutLayer == mode,
                                        onClick = { layoutLayer = mode },
                                        label = { Text(lbl, fontSize = 11.sp) }
                                    )
                                }
                            }
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                                Text("Умный фокус на лица + Авто-замена чужого сплита 50/50", color = Color.White, fontSize = 11.sp, modifier = Modifier.weight(1f))
                                Switch(checked = enableSmartFaceAndSeamCrop, onCheckedChange = { enableSmartFaceAndSeamCrop = it })
                            }
                            Text(
                                "Совет: тапни по видео сверху или фону снизу и зумируй/двигай кадр прямо двумя пальцами!",
                                color = Color(0xFF93C5FD),
                                fontSize = 11.sp
                            )
                        }

                        YouCutToolDrawer.BACKGROUND_TRACK -> {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                                Button(onClick = { showBgPickerDialog = true }, modifier = Modifier.weight(1f)) {
                                    Text("🗂 Выбрать из пула фонов", fontSize = 11.sp)
                                }
                                OutlinedButton(onClick = { onPickBackgroundFromPhone() }, modifier = Modifier.weight(1f)) {
                                    Text("📱 С телефона", fontSize = 11.sp)
                                }
                            }
                            OutlinedButton(onClick = { onJumpToSearchTab(true) }, modifier = Modifier.fillMaxWidth()) {
                                Text("🔎 Найти новые чистые фоны-наполнители (покадровый ИИ-отсев)", fontSize = 11.sp)
                            }
                        }

                        YouCutToolDrawer.SUBTITLES -> {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Button(
                                    onClick = { runAutoSubtitlesFromVoice() },
                                    enabled = !isTranscribingSpeech && primaryFile != null,
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4F46E5)),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text(if (isTranscribingSpeech) "⏳ Слушаю волну..." else "✨ Распознать речь (Gemini + FFmpeg)", fontSize = 11.sp)
                                }
                                Switch(checked = subtitlesEnabled, onCheckedChange = { subtitlesEnabled = it })
                            }
                            Text(sttStatusNote, color = Color(0xFF93C5FD), fontSize = 10.sp)

                            val curPhrase = editablePhrases.firstOrNull { it.id == selectedPhraseId } ?: editablePhrases.firstOrNull()
                            if (curPhrase != null) {
                                var draftText by remember(curPhrase.id, curPhrase.joinedText) { mutableStateOf(curPhrase.joinedText) }
                                OutlinedTextField(
                                    value = draftText,
                                    onValueChange = { txt ->
                                        draftText = txt
                                        val updated = curPhrase.withReplacedPhraseText(txt)
                                        editablePhrases = editablePhrases.map { if (it.id == curPhrase.id) updated else it }
                                    },
                                    label = {
                                        Text(
                                            String.format(Locale.US, "Фраза [%.2fс – %.2fс]", curPhrase.startSec, curPhrase.endSec),
                                            fontSize = 10.sp
                                        )
                                    },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth()
                                )
                                curPhrase.words.forEachIndexed { wIdx, w ->
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text("«${w.text}»", color = Color(0xFFFDE68A), fontSize = 11.sp, modifier = Modifier.weight(1f))
                                        Text(
                                            String.format(Locale.US, "%.2f–%.2fс", w.startSec, w.endSec),
                                            color = Color.LightGray,
                                            fontSize = 10.sp
                                        )
                                        TextButton(onClick = {
                                            val nw = curPhrase.words.toMutableList()
                                            nw[wIdx] = w.copy(startSec = (w.startSec - 0.05).coerceAtLeast(0.0), endSec = (w.endSec - 0.05).coerceAtLeast(0.08))
                                            editablePhrases = editablePhrases.map { if (it.id == curPhrase.id) curPhrase.copy(words = nw) else it }
                                        }) { Text("-0.05", fontSize = 10.sp) }
                                        TextButton(onClick = {
                                            val nw = curPhrase.words.toMutableList()
                                            nw[wIdx] = w.copy(startSec = w.startSec + 0.05, endSec = w.endSec + 0.05)
                                            editablePhrases = editablePhrases.map { if (it.id == curPhrase.id) curPhrase.copy(words = nw) else it }
                                        }) { Text("+0.05", fontSize = 10.sp) }
                                    }
                                }
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Удаление чужих вшитых сабов:", color = Color.LightGray, fontSize = 11.sp)
                                Spacer(Modifier.width(6.dp))
                                ExistingSubtitleRemovalMode.entries.forEach { m ->
                                    FilterChip(
                                        selected = oldSubRemovalMode == m,
                                        onClick = { oldSubRemovalMode = m },
                                        label = { Text(m.name.take(6), fontSize = 10.sp) }
                                    )
                                    Spacer(Modifier.width(4.dp))
                                }
                            }
                        }

                        YouCutToolDrawer.WATERMARK -> {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = {
                                    pushUndoSnapshot()
                                    val next = watermarkBoxes + WatermarkMaskBox(0.32f, 0.08f, 0.36f, 0.10f)
                                    watermarkBoxes = next
                                    selectedWmIdx = next.lastIndex
                                }) {
                                    Text("➕ Добавить зону на видео", fontSize = 11.sp)
                                }
                                if (watermarkBoxes.isNotEmpty()) {
                                    OutlinedButton(onClick = {
                                        pushUndoSnapshot()
                                        watermarkBoxes = emptyList()
                                        selectedWmIdx = -1
                                    }) {
                                        Text("Очистить (${watermarkBoxes.size})", fontSize = 11.sp)
                                    }
                                }
                            }
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                WatermarkEraseMethod.entries.forEach { em ->
                                    FilterChip(
                                        selected = eraseMethod == em,
                                        onClick = { eraseMethod = em },
                                        label = { Text(em.label.substringBefore(" ("), fontSize = 10.sp) }
                                    )
                                }
                            }
                        }

                        YouCutToolDrawer.AUDIO_MIX -> {
                            Text("Громкость видео: ${(mainVolume * 100).roundToInt()}% | Громкость фона: ${(bgVolume * 100).roundToInt()}%", color = Color.White, fontSize = 11.sp)
                            Slider(value = mainVolume, onValueChange = { mainVolume = it }, valueRange = 0f..1.5f)
                            Slider(value = bgVolume, onValueChange = { bgVolume = it }, valueRange = 0f..1.5f)
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                OutlinedButton(
                                    onClick = {
                                        val pf = primaryFile ?: return@OutlinedButton
                                        isSplittingStems = true
                                        scope.launch {
                                            try {
                                                separatedStems = hardwareEngine.separateIntoThreeAudioStems(pf, 0.0, timelineTotalSec)
                                                stemRoutingMode = AudioStemRoutingMode.VOCAL_PLUS_AMBIENCE_NO_MUSIC
                                                onShowMessage("✅ Звук разделён на Голос / Интершум / Музыку!")
                                            } catch (e: Exception) {
                                                onShowMessage("Ошибка разделения дорожек: ${e.message}")
                                            } finally {
                                                isSplittingStems = false
                                            }
                                        }
                                    },
                                    enabled = !isSplittingStems && primaryFile != null,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text(if (isSplittingStems) "⏳ Делю..." else "🎛 Срезать фоновую музыку", fontSize = 11.sp)
                                }
                                OutlinedButton(
                                    onClick = {
                                        val pf = primaryFile ?: return@OutlinedButton
                                        isRunningShazam = true
                                        scope.launch {
                                            try {
                                                val res = subtitleTransformer.identifyAndMatchMusicViaAiShazam(pf, 0.0, timelineTotalSec.coerceAtMost(35.0))
                                                shazamResult = res
                                                onShowMessage("🎵 Найдено: ${res.detectedTrackAndGenre}")
                                            } catch (_: Exception) {
                                            } finally {
                                                isRunningShazam = false
                                            }
                                        }
                                    },
                                    enabled = !isRunningShazam && primaryFile != null,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text(if (isRunningShazam) "⏳ Слушаю..." else "🎵 Определить трек", fontSize = 11.sp)
                                }
                            }
                            shazamResult?.let { sr ->
                                Text("🎵 ${sr.detectedTrackAndGenre} (${sr.moodAndBpm})", color = Color(0xFF6EE7B7), fontSize = 11.sp)
                            }
                        }

                        YouCutToolDrawer.VOICEOVER -> {
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                VoiceoverDubMode.entries.forEach { vm ->
                                    FilterChip(
                                        selected = voiceoverDubMode == vm,
                                        onClick = { voiceoverDubMode = vm },
                                        label = { Text(vm.label.substringBefore(":"), fontSize = 10.sp) }
                                    )
                                }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(
                                    onClick = {
                                        val pf = primaryFile ?: return@Button
                                        isGeneratingDub = true
                                        scope.launch {
                                            try {
                                                val script = dubScriptText.ifBlank {
                                                    subtitleTransformer.translateOrRewriteDubScript(
                                                        videoFile = pf,
                                                        trimStartSec = 0.0,
                                                        durationSec = timelineTotalSec,
                                                        sourceTranscriptHint = editablePhrases.joinToString(" ") { it.joinedText }
                                                    )
                                                }
                                                dubScriptText = script
                                                val wav = subtitleTransformer.synthesizeRussianVoiceoverAudio(
                                                    scriptText = script,
                                                    targetDurationSec = timelineTotalSec,
                                                    voiceProfile = selectedVoiceProfile
                                                )
                                                if (wav != null) {
                                                    generatedVoiceoverFile = wav
                                                    voiceoverDubMode = VoiceoverDubMode.DUB_VOICEOVER_15PCT_BG
                                                    onShowMessage("✅ Новая озвучка сгенерирована!")
                                                }
                                            } catch (e: Exception) {
                                                onShowMessage("Ошибка озвучки: ${e.message}")
                                            } finally {
                                                isGeneratingDub = false
                                            }
                                        }
                                    },
                                    enabled = !isGeneratingDub && primaryFile != null,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text(if (isGeneratingDub) "⏳ Озвучиваю..." else "🎙 Переозвучить ролик (ИИ)", fontSize = 11.sp)
                                }
                                OutlinedButton(onClick = onImportVoiceoverAudio) {
                                    Text("📂 Свой MP3", fontSize = 11.sp)
                                }
                            }
                        }

                        YouCutToolDrawer.SPEED -> {
                            Text("Скорость: ${String.format(Locale.US, "%.2fx", playbackSpeed)}", color = Color.White, fontSize = 12.sp)
                            Slider(
                                value = playbackSpeed,
                                onValueChange = { playbackSpeed = (it * 20f).roundToInt() / 20f },
                                valueRange = 0.5f..2.0f
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                listOf(0.75f, 1.0f, 1.15f, 1.25f, 1.5f).forEach { sp ->
                                    FilterChip(
                                        selected = abs(playbackSpeed - sp) < 0.03f,
                                        onClick = { playbackSpeed = sp },
                                        label = { Text("${sp}x", fontSize = 11.sp) }
                                    )
                                }
                            }
                        }

                        YouCutToolDrawer.ANTIDETECT -> {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                                Text("Анти-Детект уникализация (без рассинхрона речи)", color = Color.White, fontSize = 12.sp)
                                Switch(checked = antiDetectEnabled, onCheckedChange = { antiDetectEnabled = it })
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                FilterChip(selected = antiDetectMicroZoom, onClick = { antiDetectMicroZoom = !antiDetectMicroZoom }, label = { Text("Микро-зум + шум", fontSize = 11.sp) })
                                FilterChip(selected = antiDetectPitchShift, onClick = { antiDetectPitchShift = !antiDetectPitchShift }, label = { Text("Уникальный спектр звука", fontSize = 11.sp) })
                                FilterChip(selected = antiDetectFlip, onClick = { antiDetectFlip = !antiDetectFlip }, label = { Text("Отзеркалить", fontSize = 11.sp) })
                            }
                        }

                        YouCutToolDrawer.NONE -> {}
                    }
                }
            }
        }

        // 6. BOTTOM YOUCUT ICON TOOLBAR
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF11141D))
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Immediate 1-tap Split & Delete buttons (just like YouCut!)
            YouCutBottomActionChip(icon = "✂️", label = "Разделить", active = false) {
                splitAtWhitePlayhead()
            }
            YouCutBottomActionChip(icon = "🗑", label = "Удалить", active = false) {
                deleteSelectedSegment()
            }

            YouCutToolDrawer.entries.filter { it != YouCutToolDrawer.NONE }.forEach { tool ->
                YouCutBottomActionChip(
                    icon = tool.icon,
                    label = tool.label,
                    active = activeTool == tool
                ) {
                    activeTool = if (activeTool == tool) YouCutToolDrawer.NONE else tool
                }
            }
        }
    }
}

@Composable
private fun YouCutBottomActionChip(
    icon: String,
    label: String,
    active: Boolean,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (active) Color(0x33F97316) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 9.dp, vertical = 4.dp)
    ) {
        Text(icon, fontSize = 16.sp)
        Text(
            text = label,
            color = if (active) Color(0xFFF97316) else Color(0xFFD1D5DB),
            fontSize = 10.sp,
            fontWeight = if (active) FontWeight.Bold else FontWeight.Normal
        )
    }
}

private fun formatTimeShort(sec: Double): String {
    val safe = sec.coerceAtLeast(0.0)
    val mins = (safe / 60.0).toInt()
    val rem = safe - mins * 60
    return String.format(Locale.US, "%02d:%04.1f", mins, rem)
}

private suspend fun extractFilmstripThumbnails(
    videoFile: File,
    durationSec: Double,
    count: Int
): List<ImageBitmap> = withContext(Dispatchers.IO) {
    val list = mutableListOf<ImageBitmap>()
    val retriever = MediaMetadataRetriever()
    try {
        retriever.setDataSource(videoFile.absolutePath)
        val safeCount = count.coerceIn(4, 20)
        for (i in 0 until safeCount) {
            val tUs = (((i + 0.5) / safeCount) * durationSec * 1_000_000.0).roundToLong()
            val frame = retriever.getFrameAtTime(tUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: continue
            val scaled = Bitmap.createScaledBitmap(frame, 96, 96, true)
            if (scaled !== frame) frame.recycle()
            list.add(scaled.asImageBitmap())
        }
    } catch (_: Exception) {
    } finally {
        try {
            retriever.release()
        } catch (_: Exception) {
        }
    }
    list
}
