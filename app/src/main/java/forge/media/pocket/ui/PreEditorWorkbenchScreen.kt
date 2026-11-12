package forge.media.pocket.ui

import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import forge.media.pocket.clutter.ClutterEntropyFilter
import forge.media.pocket.compositor.AudioStemRoutingMode
import forge.media.pocket.compositor.ClipTimeSegment
import forge.media.pocket.compositor.FFmpegHardwareEngine
import forge.media.pocket.compositor.ManualCropRect
import forge.media.pocket.compositor.MusicRemovalMode
import forge.media.pocket.compositor.SeparatedAudioStems
import forge.media.pocket.compositor.VoiceoverDubMode
import forge.media.pocket.compositor.WatermarkEraseMethod
import forge.media.pocket.compositor.WatermarkMaskBox
import forge.media.pocket.subtitles.MusicShazamMatchResult
import forge.media.pocket.subtitles.SubtitleAssTransformer
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
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * Пакет подготовленного исходника из Цеха 1 («✂️ Разделка»), передаваемый в Цех 2 («🎛 Редактор»).
 */
data class PreparedWorkbenchPayload(
    val bakedVideoFile: File,
    val title: String,
    val sourceUrl: String,
    val separatedStems: SeparatedAudioStems? = null,
    val stemRoutingMode: AudioStemRoutingMode = AudioStemRoutingMode.FULL_ORIGINAL_MIX,
    val musicRemovalMode: MusicRemovalMode = MusicRemovalMode.OFF,
    val replacementMusicFile: File? = null,
    val voiceoverDubMode: VoiceoverDubMode = VoiceoverDubMode.OFF,
    val customVoiceoverFile: File? = null,
    val existingSubtitleRemovalMode: ExistingSubtitleRemovalMode = ExistingSubtitleRemovalMode.OFF,
    val detectedHardsubRegion: DetectedHardsubRegion? = null
)

/**
 * Цех 1 («✂️ Разделка исходника»):
 * Полноценный верстак подготовки основного видео перед отправкой в Сборщик:
 * - Интерактивный свободный кроп за 4 жёлтых угловых маркера прямо на кадре;
 * - Несколько красных рамок затирки вотермарок / логотипов (Delogo, Gaussian Blur, Матовая плашка);
 * - Авто-сканер старых вшитых субтитров (ML Kit) + авто-кроп выше старых субтитров;
 * - Мульти-разрезка таймлайна на куски с удалением воды и склейкой;
 * - 3-дорожечное разделение звука (Голос / Интершум / Музыка), AI Shazam и ИИ-Озвучка;
 * - Сохранение чистого исходника в Галерею или передача в 🎛 Редактор в 1 тап.
 */
@androidx.annotation.OptIn(UnstableApi::class)
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PreEditorWorkbenchScreen(
    primaryFile: File?,
    primaryTitle: String,
    primarySourceUrl: String,
    initialStartSec: Double = 0.0,
    initialEndSec: Double = 0.0,
    clutterFilter: ClutterEntropyFilter,
    hardwareEngine: FFmpegHardwareEngine,
    faceHelper: FaceCenteringHelper,
    subtitleTransformer: SubtitleAssTransformer,
    importedVoiceoverFile: File?,
    onPickPrimaryVideoFromPhone: () -> Unit,
    onSelectPrimaryFromInbox: (File, String, String, Double, Double) -> Unit,
    onImportVoiceoverAudio: () -> Unit,
    onJumpToSearchTab: () -> Unit,
    onSendPreparedToAssembler: (PreparedWorkbenchPayload) -> Unit,
    onShowMessage: (String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current

    var showInboxDialog by remember { mutableStateOf(false) }
    var sourceTotalDurSec by remember(primaryFile?.absolutePath) { mutableDoubleStateOf(15.0) }

    // Multi-segment cutting state
    var segments by remember(primaryFile?.absolutePath) {
        mutableStateOf(listOf(ClipTimeSegment(startSec = 0.0, endSec = 15.0, kept = true)))
    }
    var selectedSegIdx by remember(primaryFile?.absolutePath) { mutableIntStateOf(0) }

    // Interactive 4-handle visual crop
    var manualCrop by remember(primaryFile?.absolutePath) {
        mutableStateOf(ManualCropRect(enabled = false, leftNorm = 0f, topNorm = 0f, rightNorm = 1f, bottomNorm = 1f))
    }

    // Watermark eraser boxes
    var watermarkBoxes by remember(primaryFile?.absolutePath) { mutableStateOf<List<WatermarkMaskBox>>(emptyList()) }
    var selectedWmIdx by remember { mutableIntStateOf(-1) }
    var eraseMethod by remember { mutableStateOf(WatermarkEraseMethod.DELOGO_INPAINT) }

    // Old burned-in subtitle scanner
    var detectedHardsub by remember(primaryFile?.absolutePath) { mutableStateOf<DetectedHardsubRegion?>(null) }
    var oldSubRemovalMode by remember(primaryFile?.absolutePath) { mutableStateOf(ExistingSubtitleRemovalMode.OFF) }
    var isScanningHardsubs by remember { mutableStateOf(false) }

    // Speed & Audio stem tools
    var playbackSpeed by remember(primaryFile?.absolutePath) { mutableFloatStateOf(1.0f) }
    var musicRemovalMode by remember { mutableStateOf(MusicRemovalMode.OFF) }
    var stemRoutingMode by remember { mutableStateOf(AudioStemRoutingMode.FULL_ORIGINAL_MIX) }
    var separatedStems by remember(primaryFile?.absolutePath) { mutableStateOf<SeparatedAudioStems?>(null) }
    var isSplittingStems by remember { mutableStateOf(false) }

    // AI Shazam & replacement music
    var shazamResult by remember(primaryFile?.absolutePath) { mutableStateOf<MusicShazamMatchResult?>(null) }
    var isRunningShazam by remember { mutableStateOf(false) }
    var replacementMusicFile by remember(primaryFile?.absolutePath) { mutableStateOf<File?>(null) }

    // Voiceover / Dubbing
    var voiceoverDubMode by remember { mutableStateOf(VoiceoverDubMode.OFF) }
    var selectedVoiceProfile by remember { mutableStateOf(TtsVoiceProfile.CHARON_DEEP) }
    var dubScriptText by remember { mutableStateOf("") }
    var generatedVoiceoverFile by remember(primaryFile?.absolutePath) { mutableStateOf<File?>(null) }
    var isGeneratingDub by remember { mutableStateOf(false) }

    var isBaking by remember { mutableStateOf(false) }
    var bakeStatusText by remember { mutableStateOf("") }

    // ExoPlayer for source video + stem preview player
    val exoPlayer = remember {
        ExoPlayer.Builder(context).build().apply {
            repeatMode = Player.REPEAT_MODE_ONE
            playWhenReady = true
        }
    }
    var isPlaying by remember { mutableStateOf(true) }
    var currentPosSec by remember { mutableDoubleStateOf(0.0) }

    DisposableEffect(Unit) {
        onDispose {
            try {
                exoPlayer.release()
            } catch (_: Exception) {
            }
        }
    }

    LaunchedEffect(primaryFile?.absolutePath) {
        val file = primaryFile
        if (file != null && file.exists()) {
            val dur = withContext(Dispatchers.IO) {
                hardwareEngine.probeDurationSeconds(file).coerceAtLeast(1.0)
            }
            sourceTotalDurSec = dur
            val s = initialStartSec.coerceIn(0.0, (dur - 0.5).coerceAtLeast(0.0))
            val e = if (initialEndSec > s + 0.5) initialEndSec.coerceAtMost(dur) else dur
            segments = listOf(ClipTimeSegment(startSec = s, endSec = e, kept = true))
            selectedSegIdx = 0

            exoPlayer.setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
            exoPlayer.prepare()
            exoPlayer.seekTo((s * 1000.0).roundToLong())
            exoPlayer.playWhenReady = true
            isPlaying = true
        } else {
            exoPlayer.clearMediaItems()
        }
    }

    LaunchedEffect(playbackSpeed) {
        try {
            exoPlayer.playbackParameters = PlaybackParameters(playbackSpeed.coerceIn(0.5f, 2.0f))
        } catch (_: Exception) {
        }
    }

    // Playhead loop across kept segments
    LaunchedEffect(exoPlayer, primaryFile?.absolutePath, segments) {
        while (isActive) {
            if (primaryFile != null && exoPlayer.duration > 0) {
                val pos = exoPlayer.currentPosition / 1000.0
                currentPosSec = pos
                isPlaying = exoPlayer.isPlaying

                val keptList = segments.filter { it.kept && it.endSec > it.startSec + 0.12 }
                if (keptList.isNotEmpty() && exoPlayer.isPlaying) {
                    val insideKept = keptList.any { pos >= it.startSec - 0.08 && pos <= it.endSec + 0.08 }
                    if (!insideKept) {
                        val nextSeg = keptList.firstOrNull { it.startSec >= pos } ?: keptList.first()
                        exoPlayer.seekTo((nextSeg.startSec * 1000.0).roundToLong())
                    }
                }
            }
            delay(45L)
        }
    }

    val keptTotalDurationSec = remember(segments, playbackSpeed) {
        (segments.filter { it.kept }.sumOf { it.durationSec } / playbackSpeed.coerceAtLeast(0.5f)).coerceAtLeast(0.0)
    }

    fun splitAtCurrentPlayhead() {
        val t = currentPosSec
        val idx = segments.indexOfFirst { t > it.startSec + 0.35 && t < it.endSec - 0.35 }
        if (idx < 0) {
            onShowMessage("⚠️ Передвинь бегунок внутрь куска (минимум 0.4с от края), чтобы разрезать")
            return
        }
        val target = segments[idx]
        val left = ClipTimeSegment(startSec = target.startSec, endSec = t, kept = target.kept)
        val right = ClipTimeSegment(startSec = t, endSec = target.endSec, kept = target.kept)
        val updated = segments.toMutableList().apply {
            removeAt(idx)
            add(idx, left)
            add(idx + 1, right)
        }
        segments = updated
        selectedSegIdx = idx + 1
        onShowMessage("✂️ Разрезано в точке ${String.format(Locale.US, "%.1fс", t)}")
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0A0C10))
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 1. TOP SOURCE BAR
        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF141824)),
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "✂️ Цех 1: Первичная Разделка Исходника",
                            color = Color(0xFFF97316),
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp
                        )
                        Text(
                            text = if (primaryFile != null) {
                                "${primaryTitle.ifBlank { primaryFile.name }} • Оставлено: ${String.format(Locale.US, "%.1fс", keptTotalDurationSec)}"
                            } else {
                                "Сначала выбери исходное видео для разделки"
                            },
                            color = Color(0xFF94A3B8),
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = { showInboxDialog = true },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("📥 Загрузки", fontSize = 12.sp, maxLines = 1)
                    }
                    OutlinedButton(
                        onClick = onPickPrimaryVideoFromPhone,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("📱 С телефона", fontSize = 12.sp, maxLines = 1)
                    }
                    OutlinedButton(
                        onClick = onJumpToSearchTab,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("🔎 В Поиск", fontSize = 12.sp, maxLines = 1)
                    }
                }
            }
        }

        if (primaryFile == null || !primaryFile.exists()) {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF141824)),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text("🎬 Исходник ещё не выбран", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Text(
                        "Выбери скачанный ролик из Загрузок, открой видео с телефона или найди свежий исходник в Поиске.",
                        color = Color(0xFF94A3B8),
                        fontSize = 13.sp
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(
                            onClick = { showInboxDialog = true },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF97316))
                        ) {
                            Text("📥 Выбрать из Загрузок")
                        }
                        OutlinedButton(onClick = onPickPrimaryVideoFromPhone) {
                            Text("📱 Открыть с телефона")
                        }
                    }
                }
            }
        } else {
            // 2. VISUAL PLAYER + 4-HANDLE CROP + WATERMARK MASKS
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
                            "🖥 Кадр исходника (Кроп и Ластик лого)",
                            color = Color.White,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 14.sp
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                            FilterChip(
                                selected = manualCrop.enabled,
                                onClick = {
                                    manualCrop = if (!manualCrop.enabled) {
                                        ManualCropRect(enabled = true, leftNorm = 0.06f, topNorm = 0.06f, rightNorm = 0.94f, bottomNorm = 0.94f)
                                    } else {
                                        ManualCropRect(enabled = false)
                                    }
                                },
                                label = { Text(if (manualCrop.enabled) "✂️ Свободный кроп: ВКЛ" else "✂️ Свободный кроп", fontSize = 11.sp) }
                            )
                            OutlinedButton(
                                onClick = {
                                    val updated = watermarkBoxes + WatermarkMaskBox(xNorm = 0.65f, yNorm = 0.08f, wNorm = 0.26f, hNorm = 0.12f)
                                    watermarkBoxes = updated
                                    selectedWmIdx = updated.lastIndex
                                    onShowMessage("🧼 Добавлена красная рамка затирки — перетащи её на логотип!")
                                }
                            ) {
                                Text("🧼 +Ластик", fontSize = 11.sp)
                            }
                        }
                    }

                    var canvasSize by remember { mutableStateOf(IntSize.Zero) }

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(290.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color.Black)
                            .onSizeChanged { canvasSize = it }
                    ) {
                        AndroidView(
                            factory = { ctx ->
                                PlayerView(ctx).apply {
                                    player = exoPlayer
                                    useController = false
                                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                                }
                            },
                            update = { pv -> pv.player = exoPlayer },
                            modifier = Modifier.fillMaxSize()
                        )

                        // Detected old burned-in subtitle overlay preview
                        val hardsub = detectedHardsub
                        if (hardsub != null && hardsub.detected && oldSubRemovalMode != ExistingSubtitleRemovalMode.OFF) {
                            Canvas(modifier = Modifier.fillMaxSize()) {
                                val rx = (hardsub.xNorm * size.width).toFloat()
                                val ry = (hardsub.yNorm * size.height).toFloat()
                                val rw = (hardsub.widthNorm * size.width).toFloat()
                                val rh = (hardsub.heightNorm * size.height).toFloat()
                                drawRect(
                                    color = Color(0x66EF4444),
                                    topLeft = Offset(rx, ry),
                                    size = Size(rw, rh)
                                )
                                drawRect(
                                    color = Color(0xFFEF4444),
                                    topLeft = Offset(rx, ry),
                                    size = Size(rw, rh),
                                    style = Stroke(width = 3f)
                                )
                            }
                        }

                        // 4-Handle Freeform Yellow Crop Box
                        if (manualCrop.enabled && canvasSize.width > 20 && canvasSize.height > 20) {
                            val cw = canvasSize.width.toFloat()
                            val ch = canvasSize.height.toFloat()

                            Canvas(modifier = Modifier.fillMaxSize()) {
                                val lx = manualCrop.leftNorm * size.width
                                val ty = manualCrop.topNorm * size.height
                                val rx = manualCrop.rightNorm * size.width
                                val by = manualCrop.bottomNorm * size.height

                                drawRect(
                                    color = Color(0xFFFACC15),
                                    topLeft = Offset(lx, ty),
                                    size = Size((rx - lx).coerceAtLeast(10f), (by - ty).coerceAtLeast(10f)),
                                    style = Stroke(width = 5f)
                                )
                            }

                            // Top-Left Handle
                            Box(
                                modifier = Modifier
                                    .offset {
                                        IntOffset(
                                            (manualCrop.leftNorm * cw - with(density) { 12.dp.toPx() }).roundToInt(),
                                            (manualCrop.topNorm * ch - with(density) { 12.dp.toPx() }).roundToInt()
                                        )
                                    }
                                    .size(24.dp)
                                    .background(Color(0xFFFACC15), CircleShape)
                                    .border(2.dp, Color.Black, CircleShape)
                                    .pointerInput(cw, ch) {
                                        detectDragGestures { change, drag ->
                                            change.consume()
                                            manualCrop = manualCrop.copy(
                                                leftNorm = (manualCrop.leftNorm + drag.x / cw).coerceIn(0f, manualCrop.rightNorm - 0.15f),
                                                topNorm = (manualCrop.topNorm + drag.y / ch).coerceIn(0f, manualCrop.bottomNorm - 0.15f)
                                            )
                                        }
                                    }
                            )

                            // Bottom-Right Handle
                            Box(
                                modifier = Modifier
                                    .offset {
                                        IntOffset(
                                            (manualCrop.rightNorm * cw - with(density) { 12.dp.toPx() }).roundToInt(),
                                            (manualCrop.bottomNorm * ch - with(density) { 12.dp.toPx() }).roundToInt()
                                        )
                                    }
                                    .size(24.dp)
                                    .background(Color(0xFFFACC15), CircleShape)
                                    .border(2.dp, Color.Black, CircleShape)
                                    .pointerInput(cw, ch) {
                                        detectDragGestures { change, drag ->
                                            change.consume()
                                            manualCrop = manualCrop.copy(
                                                rightNorm = (manualCrop.rightNorm + drag.x / cw).coerceIn(manualCrop.leftNorm + 0.15f, 1f),
                                                bottomNorm = (manualCrop.bottomNorm + drag.y / ch).coerceIn(manualCrop.topNorm + 0.15f, 1f)
                                            )
                                        }
                                    }
                            )
                        }

                        // Interactive Red Watermark Eraser Boxes
                        if (watermarkBoxes.isNotEmpty() && canvasSize.width > 20 && canvasSize.height > 20) {
                            val cw = canvasSize.width.toFloat()
                            val ch = canvasSize.height.toFloat()

                            watermarkBoxes.forEachIndexed { idx, box ->
                                val isSel = idx == selectedWmIdx
                                val bx = (box.xNorm * cw).roundToInt()
                                val by = (box.yNorm * ch).roundToInt()
                                val bwDp = with(density) { (box.wNorm * cw).toDp() }
                                val bhDp = with(density) { (box.hNorm * ch).toDp() }

                                Box(
                                    modifier = Modifier
                                        .offset { IntOffset(bx, by) }
                                        .size(bwDp, bhDp)
                                        .background(Color(0x44EF4444), RoundedCornerShape(4.dp))
                                        .border(if (isSel) 2.dp else 1.dp, Color(0xFFEF4444), RoundedCornerShape(4.dp))
                                        .clickable { selectedWmIdx = idx }
                                        .pointerInput(idx, cw, ch) {
                                            detectDragGestures { change, drag ->
                                                change.consume()
                                                selectedWmIdx = idx
                                                val cur = watermarkBoxes.getOrNull(idx) ?: return@detectDragGestures
                                                val nx = (cur.xNorm + drag.x / cw).coerceIn(0f, 1f - cur.wNorm)
                                                val ny = (cur.yNorm + drag.y / ch).coerceIn(0f, 1f - cur.hNorm)
                                                watermarkBoxes = watermarkBoxes.toMutableList().apply {
                                                    set(idx, cur.copy(xNorm = nx, yNorm = ny))
                                                }
                                            }
                                        }
                                ) {
                                    Text(
                                        text = "Лого #${idx + 1}",
                                        color = Color.White,
                                        fontSize = 9.sp,
                                        modifier = Modifier
                                            .align(Alignment.TopStart)
                                            .background(Color(0xCCEF4444))
                                            .padding(horizontal = 3.dp)
                                    )
                                    if (isSel) {
                                        Box(
                                            modifier = Modifier
                                                .align(Alignment.BottomEnd)
                                                .size(16.dp)
                                                .background(Color.White, CircleShape)
                                                .border(2.dp, Color(0xFFEF4444), CircleShape)
                                                .pointerInput(idx, cw, ch) {
                                                    detectDragGestures { change, drag ->
                                                        change.consume()
                                                        val cur = watermarkBoxes.getOrNull(idx) ?: return@detectDragGestures
                                                        val nw = (cur.wNorm + drag.x / cw).coerceIn(0.06f, 1f - cur.xNorm)
                                                        val nh = (cur.hNorm + drag.y / ch).coerceIn(0.05f, 1f - cur.yNorm)
                                                        watermarkBoxes = watermarkBoxes.toMutableList().apply {
                                                            set(idx, cur.copy(wNorm = nw, hNorm = nh))
                                                        }
                                                    }
                                                }
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Transport & timeline scrubber
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                if (exoPlayer.isPlaying) {
                                    exoPlayer.pause()
                                    isPlaying = false
                                } else {
                                    exoPlayer.play()
                                    isPlaying = true
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E293B))
                        ) {
                            Text(if (isPlaying) "⏸ Пауза" else "▶️ Пуск", fontSize = 12.sp)
                        }

                        Slider(
                            value = currentPosSec.toFloat().coerceIn(0f, sourceTotalDurSec.toFloat().coerceAtLeast(1f)),
                            onValueChange = { v ->
                                currentPosSec = v.toDouble()
                                exoPlayer.seekTo((v * 1000).roundToLong())
                            },
                            valueRange = 0f..sourceTotalDurSec.toFloat().coerceAtLeast(1f),
                            modifier = Modifier.weight(1f)
                        )

                        Text(
                            text = "${String.format(Locale.US, "%.1f", currentPosSec)} / ${String.format(Locale.US, "%.1fс", sourceTotalDurSec)}",
                            color = Color(0xFFCBD5E1),
                            fontSize = 11.sp
                        )
                    }

                    if (watermarkBoxes.isNotEmpty()) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                WatermarkEraseMethod.entries.forEach { m ->
                                    FilterChip(
                                        selected = eraseMethod == m,
                                        onClick = { eraseMethod = m },
                                        label = { Text(m.label.substringBefore(" ("), fontSize = 11.sp) }
                                    )
                                }
                            }
                            TextButton(
                                onClick = {
                                    if (selectedWmIdx in watermarkBoxes.indices) {
                                        watermarkBoxes = watermarkBoxes.toMutableList().apply { removeAt(selectedWmIdx) }
                                        selectedWmIdx = watermarkBoxes.lastIndex
                                    } else {
                                        watermarkBoxes = emptyList()
                                    }
                                }
                            ) {
                                Text("🗑 Удалить рамку", color = Color(0xFFEF4444), fontSize = 11.sp)
                            }
                        }
                    }
                }
            }

            // 3. MULTI-SEGMENT TIMELINE CUTTER
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF141824)),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "✂️ Нарезка кусков и Скорость (${String.format(Locale.US, "%.2fx", playbackSpeed)})",
                            color = Color.White,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 14.sp
                        )
                        Button(
                            onClick = { splitAtCurrentPlayhead() },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF97316))
                        ) {
                            Text("✂️ Разрезать здесь (${String.format(Locale.US, "%.1fс", currentPosSec)})", fontSize = 12.sp)
                        }
                    }

                    // Horizontal segment chips
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        segments.forEachIndexed { idx, seg ->
                            val isSel = idx == selectedSegIdx
                            Surface(
                                color = when {
                                    !seg.kept -> Color(0xFF27171A)
                                    isSel -> Color(0xFF1E3A8A)
                                    else -> Color(0xFF1E293B)
                                },
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier
                                    .border(
                                        width = if (isSel) 2.dp else 1.dp,
                                        color = if (isSel) Color(0xFF38BDF8) else Color(0xFF334155),
                                        shape = RoundedCornerShape(10.dp)
                                    )
                                    .clickable {
                                        selectedSegIdx = idx
                                        exoPlayer.seekTo((seg.startSec * 1000.0).roundToLong())
                                    }
                            ) {
                                Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                                    Text(
                                        text = "Кусок #${idx + 1} ${if (seg.kept) "✅" else "🗑 ВЫРЕЗАН"}",
                                        color = Color.White,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 12.sp
                                    )
                                    Text(
                                        text = "${String.format(Locale.US, "%.1f", seg.startSec)}с .. ${String.format(Locale.US, "%.1f", seg.endSec)}с (${String.format(Locale.US, "%.1fс", seg.durationSec)})",
                                        color = Color(0xFFCBD5E1),
                                        fontSize = 11.sp
                                    )
                                }
                            }
                        }
                    }

                    // Selected segment controls
                    val activeSeg = segments.getOrNull(selectedSegIdx)
                    if (activeSeg != null) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedButton(
                                onClick = {
                                    val ns = (activeSeg.startSec - 0.5).coerceAtLeast(0.0)
                                    segments = segments.toMutableList().apply {
                                        set(selectedSegIdx, activeSeg.copy(startSec = ns))
                                    }
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("Нач -0.5с", fontSize = 11.sp)
                            }
                            OutlinedButton(
                                onClick = {
                                    val ns = (activeSeg.startSec + 0.5).coerceAtMost(activeSeg.endSec - 0.4)
                                    segments = segments.toMutableList().apply {
                                        set(selectedSegIdx, activeSeg.copy(startSec = ns))
                                    }
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("Нач +0.5с", fontSize = 11.sp)
                            }
                            OutlinedButton(
                                onClick = {
                                    val ne = (activeSeg.endSec - 0.5).coerceAtLeast(activeSeg.startSec + 0.4)
                                    segments = segments.toMutableList().apply {
                                        set(selectedSegIdx, activeSeg.copy(endSec = ne))
                                    }
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("Кон -0.5с", fontSize = 11.sp)
                            }
                            OutlinedButton(
                                onClick = {
                                    val ne = (activeSeg.endSec + 0.5).coerceAtMost(sourceTotalDurSec)
                                    segments = segments.toMutableList().apply {
                                        set(selectedSegIdx, activeSeg.copy(endSec = ne))
                                    }
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("Кон +0.5с", fontSize = 11.sp)
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Button(
                                onClick = {
                                    segments = segments.toMutableList().apply {
                                        set(selectedSegIdx, activeSeg.copy(kept = !activeSeg.kept))
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (activeSeg.kept) Color(0xFFDC2626) else Color(0xFF16A34A)
                                )
                            ) {
                                Text(
                                    if (activeSeg.kept) "🗑 Выкинуть кусок #${selectedSegIdx + 1}" else "♻️ Вернуть кусок #${selectedSegIdx + 1}",
                                    fontSize = 12.sp
                                )
                            }

                            TextButton(
                                onClick = {
                                    segments = listOf(ClipTimeSegment(startSec = 0.0, endSec = sourceTotalDurSec, kept = true))
                                    selectedSegIdx = 0
                                }
                            ) {
                                Text("Сбросить нарезку", fontSize = 12.sp)
                            }
                        }
                    }

                    // Speed presets
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(0.85f, 1.0f, 1.08f, 1.15f, 1.25f, 1.5f).forEach { sp ->
                            FilterChip(
                                selected = kotlin.math.abs(playbackSpeed - sp) < 0.02f,
                                onClick = { playbackSpeed = sp },
                                label = { Text("${sp}x", fontSize = 11.sp) }
                            )
                        }
                    }
                }
            }

            // 4. OLD BURNED-IN SUBTITLE SCANNER
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF141824)),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "🔍 Старые вшитые субтитры на исходнике",
                            color = Color.White,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 14.sp
                        )
                        OutlinedButton(
                            enabled = !isScanningHardsubs,
                            onClick = {
                                scope.launch {
                                    isScanningHardsubs = true
                                    try {
                                        val res = faceHelper.detectBurnedInSubtitleRegion(primaryFile)
                                        detectedHardsub = res
                                        if (res.detected) {
                                            oldSubRemovalMode = ExistingSubtitleRemovalMode.AUTO_CROP_ABOVE_SUBS
                                            onShowMessage("✅ Найдены старые субтитры: ${res.summary}")
                                        } else {
                                            onShowMessage("👌 Вшитых старых субтитров не обнаружено")
                                        }
                                    } catch (e: Exception) {
                                        onShowMessage("⚠️ Ошибка сканера: ${e.message}")
                                    } finally {
                                        isScanningHardsubs = false
                                    }
                                }
                            }
                        ) {
                            Text(if (isScanningHardsubs) "⏳ Сканирую..." else "🔍 Проверить кадр", fontSize = 12.sp)
                        }
                    }

                    if (detectedHardsub != null) {
                        Text(
                            text = detectedHardsub?.summary.orEmpty(),
                            color = if (detectedHardsub?.detected == true) Color(0xFFFBBF24) else Color(0xFF94A3B8),
                            fontSize = 12.sp
                        )
                    }

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

            // 5. AUDIO STEMS, SHAZAM & DUBBING
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF141824)),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "🎚 Звуковой Цех: Разделение дорожек, AI Shazam и Озвучка",
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 14.sp
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            enabled = !isSplittingStems,
                            onClick = {
                                scope.launch {
                                    isSplittingStems = true
                                    try {
                                        val firstKept = segments.firstOrNull { it.kept } ?: segments.first()
                                        val stems = hardwareEngine.splitVideoAudioIntoStems(
                                            primaryVideoFile = primaryFile,
                                            startSec = firstKept.startSec,
                                            endSec = firstKept.endSec
                                        )
                                        separatedStems = stems
                                        stemRoutingMode = AudioStemRoutingMode.VOCAL_PLUS_AMBIENCE_NO_MUSIC
                                        onShowMessage("✅ Звук разделён на 3 дорожки: Голос, Интершум и Музыка!")
                                    } catch (e: Exception) {
                                        onShowMessage("❌ Ошибка разделения: ${e.message}")
                                    } finally {
                                        isSplittingStems = false
                                    }
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0284C7)),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(if (isSplittingStems) "⏳ Делю звук..." else "🎚 Разделить на 3 дорожки", fontSize = 12.sp)
                        }

                        OutlinedButton(
                            enabled = !isRunningShazam,
                            onClick = {
                                scope.launch {
                                    isRunningShazam = true
                                    try {
                                        val firstKept = segments.firstOrNull { it.kept } ?: segments.first()
                                        val res = subtitleTransformer.analyzeMusicStemAndFindSimilar(
                                            primaryVideoFile = primaryFile,
                                            separatedMusicStem = separatedStems?.musicHarmonicsFile,
                                            startSec = firstKept.startSec,
                                            durationSec = firstKept.durationSec
                                        )
                                        shazamResult = res
                                        onShowMessage("🎵 Трек определён: ${res.detectedTrackAndGenre}")
                                    } catch (e: Exception) {
                                        onShowMessage("❌ Ошибка AI Shazam: ${e.message}")
                                    } finally {
                                        isRunningShazam = false
                                    }
                                }
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(if (isRunningShazam) "⏳ Слушаю..." else "🎵 AI Shazam (Без АП)", fontSize = 12.sp)
                        }
                    }

                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        AudioStemRoutingMode.entries.forEach { mode ->
                            FilterChip(
                                selected = stemRoutingMode == mode,
                                onClick = { stemRoutingMode = mode },
                                label = { Text(mode.label.removePrefix("Дорожки: "), fontSize = 11.sp) }
                            )
                        }
                    }

                    val shazam = shazamResult
                    if (shazam != null) {
                        Surface(
                            color = Color(0xFF1E293B),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text("🎵 Распознано: ${shazam.detectedTrackAndGenre}", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                Text("Настроение/Ритм: ${shazam.moodAndBpm}", color = Color(0xFF94A3B8), fontSize = 11.sp)
                                shazam.candidateTracks.take(3).forEach { (title, url) ->
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(title, color = Color(0xFF38BDF8), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                        TextButton(
                                            onClick = {
                                                scope.launch {
                                                    onShowMessage("📥 Скачиваю фоновый трек без АП...")
                                                    val f = subtitleTransformer.downloadReplacementMusicCandidate(url)
                                                    if (f != null && f.exists()) {
                                                        replacementMusicFile = f
                                                        onShowMessage("✅ Музыкальная подложка готова: ${f.name}")
                                                    } else {
                                                        onShowMessage("⚠️ Не удалось скачать трек")
                                                    }
                                                }
                                            }
                                        ) {
                                            Text("Подставить", fontSize = 11.sp)
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // Voiceover / Dubbing controls
                    Text("🎙 Режим голоса / Переозвучка:", color = Color(0xFFCBD5E1), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        VoiceoverDubMode.entries.forEach { mode ->
                            FilterChip(
                                selected = voiceoverDubMode == mode,
                                onClick = { voiceoverDubMode = mode },
                                label = { Text(mode.label.substringBefore(":"), fontSize = 11.sp) }
                            )
                        }
                    }

                    if (voiceoverDubMode == VoiceoverDubMode.DUB_VOICEOVER_15PCT_BG ||
                        voiceoverDubMode == VoiceoverDubMode.DUB_REPLACE_100PCT
                    ) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            TtsVoiceProfile.entries.forEach { vp ->
                                FilterChip(
                                    selected = selectedVoiceProfile == vp,
                                    onClick = { selectedVoiceProfile = vp },
                                    label = { Text(vp.label, fontSize = 11.sp) }
                                )
                            }
                        }

                        OutlinedTextField(
                            value = dubScriptText,
                            onValueChange = { dubScriptText = it },
                            label = { Text("Текст для новой озвучки (или нажми «Снять текст с видео»)") },
                            modifier = Modifier.fillMaxWidth(),
                            minLines = 2,
                            maxLines = 4
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                enabled = !isGeneratingDub,
                                onClick = {
                                    scope.launch {
                                        isGeneratingDub = true
                                        try {
                                            val firstKept = segments.firstOrNull { it.kept } ?: segments.first()
                                            val script = subtitleTransformer.generateDubbingScriptFromVideo(
                                                primaryVideoFile = primaryFile,
                                                sourceUrl = primarySourceUrl,
                                                startSec = firstKept.startSec,
                                                durationSec = firstKept.durationSec
                                            )
                                            if (!script.isNullOrBlank()) {
                                                dubScriptText = script
                                                onShowMessage("📝 Текст для озвучки готов!")
                                            }
                                        } catch (e: Exception) {
                                            onShowMessage("❌ Ошибка генерации текста: ${e.message}")
                                        } finally {
                                            isGeneratingDub = false
                                        }
                                    }
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("📝 Снять текст с видео", fontSize = 11.sp)
                            }

                            Button(
                                enabled = !isGeneratingDub && dubScriptText.isNotBlank(),
                                onClick = {
                                    scope.launch {
                                        isGeneratingDub = true
                                        try {
                                            val firstKept = segments.firstOrNull { it.kept } ?: segments.first()
                                            val f = subtitleTransformer.synthesizeVoiceoverToAudioFile(
                                                scriptText = dubScriptText,
                                                voiceProfile = selectedVoiceProfile,
                                                targetDurationSec = firstKept.durationSec
                                            )
                                            if (f != null && f.exists()) {
                                                generatedVoiceoverFile = f
                                                onShowMessage("🎙 Озвучка синтезирована!")
                                            } else {
                                                onShowMessage("⚠️ Не удалось синтезировать озвучку")
                                            }
                                        } catch (e: Exception) {
                                            onShowMessage("❌ Ошибка озвучки: ${e.message}")
                                        } finally {
                                            isGeneratingDub = false
                                        }
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF7C3AED)),
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(if (isGeneratingDub) "⏳ Озвучиваю..." else "🎙 Озвучить ИИ", fontSize = 11.sp)
                            }

                            OutlinedButton(onClick = onImportVoiceoverAudio) {
                                Text("📂 MP3", fontSize = 11.sp)
                            }
                        }
                    }
                }
            }

            // 6. BOTTOM ACTION BAR: SAVE CLEAN OR SEND TO ASSEMBLER EDITOR
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF141824)),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (isBaking) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), color = Color(0xFFF97316))
                            Text(bakeStatusText.ifBlank { "Обработка исходника..." }, color = Color.White, fontSize = 13.sp)
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedButton(
                            enabled = !isBaking,
                            onClick = {
                                scope.launch {
                                    isBaking = true
                                    bakeStatusText = "Склеиваю и сохраняю чистый исходник в Галерею..."
                                    try {
                                        exoPlayer.pause()
                                        val baked = hardwareEngine.bakePreparedClip(
                                            inputFile = primaryFile,
                                            segments = segments,
                                            manualCrop = manualCrop,
                                            watermarkBoxes = watermarkBoxes,
                                            eraseMethod = eraseMethod,
                                            playbackSpeed = playbackSpeed
                                        )
                                        val saved = hardwareEngine.saveRenderedVideoToGallery(baked, primaryTitle.ifBlank { "CleanClip" })
                                        onShowMessage("✅ Сохранено в Галерею: ${saved.name}")
                                    } catch (e: Exception) {
                                        onShowMessage("❌ Ошибка сохранения: ${e.message}")
                                    } finally {
                                        isBaking = false
                                    }
                                }
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("💾 В Галерею (Чистый)", fontSize = 12.sp)
                        }

                        Button(
                            enabled = !isBaking,
                            onClick = {
                                scope.launch {
                                    isBaking = true
                                    bakeStatusText = "Подготавливаю разделанный клип и передаю в 🎛 Редактор..."
                                    try {
                                        exoPlayer.pause()
                                        val baked = hardwareEngine.bakePreparedClip(
                                            inputFile = primaryFile,
                                            segments = segments,
                                            manualCrop = manualCrop,
                                            watermarkBoxes = watermarkBoxes,
                                            eraseMethod = eraseMethod,
                                            playbackSpeed = playbackSpeed
                                        )
                                        onSendPreparedToAssembler(
                                            PreparedWorkbenchPayload(
                                                bakedVideoFile = baked,
                                                title = primaryTitle,
                                                sourceUrl = primarySourceUrl,
                                                separatedStems = separatedStems,
                                                stemRoutingMode = stemRoutingMode,
                                                musicRemovalMode = musicRemovalMode,
                                                replacementMusicFile = replacementMusicFile,
                                                voiceoverDubMode = voiceoverDubMode,
                                                customVoiceoverFile = generatedVoiceoverFile ?: importedVoiceoverFile,
                                                existingSubtitleRemovalMode = oldSubRemovalMode,
                                                detectedHardsubRegion = detectedHardsub
                                            )
                                        )
                                    } catch (e: Exception) {
                                        onShowMessage("❌ Ошибка подготовки: ${e.message}")
                                    } finally {
                                        isBaking = false
                                    }
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF97316)),
                            modifier = Modifier.weight(1.4f)
                        ) {
                            Text("🚀 В 🎛 Редактор-Сборщик ->", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        }
                    }
                }
            }
        }
    }

    if (showInboxDialog) {
        val inboxItems = remember { clutterFilter.listStudioInboxItems() }
        AlertDialog(
            onDismissRequest = { showInboxDialog = false },
            title = { Text("📥 Скачанные исходники (${inboxItems.size})") },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (inboxItems.isEmpty()) {
                        Text("Папка загрузок пуста. Скачай ролик из вкладки Поиск или открой с телефона.")
                    } else {
                        inboxItems.forEach { item ->
                            val file = clutterFilter.getInboxVideoFile(item)
                            Card(
                                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        if (file.exists()) {
                                            onSelectPrimaryFromInbox(
                                                file,
                                                item.title,
                                                item.sourceUrl,
                                                item.recommendedStartSec,
                                                item.recommendedEndSec
                                            )
                                            showInboxDialog = false
                                        }
                                    }
                            ) {
                                Column(modifier = Modifier.padding(10.dp)) {
                                    Text(item.title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp, maxLines = 2)
                                    Text("${item.author} • ${item.durationSec.roundToInt()}с • ${item.sizeKb / 1024} МБ", color = Color(0xFF94A3B8), fontSize = 11.sp)
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showInboxDialog = false }) {
                    Text("Закрыть")
                }
            }
        )
    }
}
