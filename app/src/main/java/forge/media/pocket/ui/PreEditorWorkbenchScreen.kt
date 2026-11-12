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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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
import forge.media.pocket.compositor.ClipTimeSegment
import forge.media.pocket.compositor.FFmpegHardwareEngine
import forge.media.pocket.compositor.ManualCropRect
import forge.media.pocket.compositor.WatermarkEraseMethod
import forge.media.pocket.compositor.WatermarkMaskBox
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale
import kotlin.math.hypot
import kotlin.math.roundToLong

enum class PreEditTargetRole(val label: String) {
    PRIMARY_SOURCE("🎬 Основное видео (Слот 1)"),
    BACKGROUND_CLIP("🕹 Фоновое видео (Слот 2)")
}

private enum class PreEditToolTab(val label: String) {
    TIMELINE_SPLIT("✂️ Разрезка кусков"),
    FREE_CROP("📐 Свободный Кроп"),
    WATERMARK_ERASE("🧼 Ластик лого"),
    SPEED("⚡ Скорость")
}

private enum class DragHandleTarget {
    NONE,
    CROP_TOP_LEFT,
    CROP_TOP_RIGHT,
    CROP_BOTTOM_LEFT,
    CROP_BOTTOM_RIGHT,
    CROP_INSIDE,
    WM_MOVE,
    WM_RESIZE_BR
}

/**
 * Цех 1 («Первичная Разделка Клипа»):
 * Полноценный однослойный видеоредактор на базе AndroidX Media3 ExoPlayer (60fps, точный покадровый Seek):
 * - Разрезка видео в любых точках (`✂️ Разрезать тут`), удаление ненужных кусков в середине/начале/конце (`🗑 Выкинуть`),
 *   мгновенный предпросмотр склейки без рендера через плейлист `ClippingConfiguration`!
 * - Плавный пальцевый кроп по углам и сторонам (`ManualCropRect`) + пресеты пропорций.
 * - Мульти-зонный ластик вотермарок (`Delogo / Блюр / Плашка`).
 * - Прямая отправка готового чистого клипа в Слот 1 (База) или Слот 2 (Фон) Главного Редактора-Сборщика!
 */
@androidx.annotation.OptIn(UnstableApi::class)
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PreEditorWorkbenchScreen(
    inputFile: File?,
    clipTitle: String,
    targetRole: PreEditTargetRole,
    initialRecommendedStartSec: Double = 0.0,
    initialRecommendedEndSec: Double = 0.0,
    hardwareEngine: FFmpegHardwareEngine,
    onPickVideoRequested: () -> Unit,
    onChangeTargetRole: (PreEditTargetRole) -> Unit,
    onBakeFinishedToAssembler: (bakedFile: File, role: PreEditTargetRole, title: String) -> Unit,
    onShowMessage: (String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    if (inputFile == null || !inputFile.exists()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(20.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "✂️ Цех 1: Первичная Разделка Клипа",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Сначала выбери исходник или фон во вкладке «🔎 Поиск» (либо загрузи видео с телефона), чтобы обрезать лишние куски, скадрировать и стереть чужие логотипы перед отправкой в Сборщик.",
                fontSize = 14.sp,
                color = Color(0xFFB0B6C6)
            )
            Spacer(Modifier.height(18.dp))
            Button(
                onClick = onPickVideoRequested,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4F46E5))
            ) {
                Text("📂 Выбрать видео для Разделки")
            }
        }
        return
    }

    val totalDurationSec = remember(inputFile.absolutePath) {
        hardwareEngine.probeDurationSeconds(inputFile).coerceAtLeast(1.0)
    }
    val videoDims = remember(inputFile.absolutePath) {
        hardwareEngine.probeVideoDimensions(inputFile)
    }

    var segments by remember(inputFile.absolutePath) {
        val initList = if (initialRecommendedEndSec > initialRecommendedStartSec + 0.8 &&
            initialRecommendedEndSec <= totalDurationSec + 0.5
        ) {
            buildList {
                if (initialRecommendedStartSec > 0.3) {
                    add(ClipTimeSegment(startSec = 0.0, endSec = initialRecommendedStartSec, kept = false))
                }
                add(
                    ClipTimeSegment(
                        startSec = initialRecommendedStartSec.coerceAtLeast(0.0),
                        endSec = initialRecommendedEndSec.coerceAtMost(totalDurationSec),
                        kept = true
                    )
                )
                if (initialRecommendedEndSec < totalDurationSec - 0.3) {
                    add(ClipTimeSegment(startSec = initialRecommendedEndSec, endSec = totalDurationSec, kept = false))
                }
            }
        } else {
            listOf(ClipTimeSegment(startSec = 0.0, endSec = totalDurationSec, kept = true))
        }
        mutableStateOf(initList)
    }

    var selectedSegmentIdx by remember(inputFile.absolutePath) { mutableIntStateOf(0) }
    var previewOnlyKeptPlaylist by remember(inputFile.absolutePath) { mutableStateOf(false) }
    var activeTool by remember { mutableStateOf(PreEditToolTab.TIMELINE_SPLIT) }

    var cropRect by remember(inputFile.absolutePath) { mutableStateOf(ManualCropRect(enabled = false)) }
    var watermarkBoxes by remember(inputFile.absolutePath) { mutableStateOf<List<WatermarkMaskBox>>(emptyList()) }
    var selectedWmIdx by remember(inputFile.absolutePath) { mutableIntStateOf(-1) }
    var eraseMethod by remember { mutableStateOf(WatermarkEraseMethod.DELOGO_INPAINT) }
    var playbackSpeed by remember(inputFile.absolutePath) { mutableFloatStateOf(1.0f) }

    var isPlaying by remember { mutableStateOf(false) }
    var currentSourcePosSec by remember { mutableDoubleStateOf(0.0) }
    var isBaking by remember { mutableStateOf(false) }
    var bakeStatusText by remember { mutableStateOf("") }

    val exoPlayer = remember(inputFile.absolutePath) {
        ExoPlayer.Builder(context).build().apply {
            setSeekParameters(SeekParameters.EXACT)
            repeatMode = Player.REPEAT_MODE_ALL
            val item = MediaItem.fromUri(Uri.fromFile(inputFile))
            setMediaItem(item)
            prepare()
        }
    }

    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
            }
        }
        exoPlayer.addListener(listener)
        onDispose {
            exoPlayer.removeListener(listener)
            exoPlayer.release()
        }
    }

    // Sync speed immediately to ExoPlayer
    LaunchedEffect(playbackSpeed) {
        exoPlayer.playbackParameters = PlaybackParameters(playbackSpeed)
    }

    // Rebuild Media3 playlist when toggling between Full Source scrubbing vs Kept-Only Splice Preview!
    LaunchedEffect(previewOnlyKeptPlaylist, segments, inputFile.absolutePath) {
        val wasPlaying = exoPlayer.isPlaying
        if (previewOnlyKeptPlaylist) {
            val keptSegs = segments.filter { it.kept && it.endSec > it.startSec + 0.15 }
            if (keptSegs.isNotEmpty()) {
                val mediaItems = keptSegs.map { seg ->
                    MediaItem.Builder()
                        .setUri(Uri.fromFile(inputFile))
                        .setClippingConfiguration(
                            MediaItem.ClippingConfiguration.Builder()
                                .setStartPositionMs((seg.startSec * 1000.0).roundToLong())
                                .setEndPositionMs((seg.endSec * 1000.0).roundToLong())
                                .build()
                        )
                        .build()
                }
                exoPlayer.setMediaItems(mediaItems, true)
                exoPlayer.prepare()
                if (wasPlaying) exoPlayer.play()
            }
        } else {
            exoPlayer.setMediaItem(MediaItem.fromUri(Uri.fromFile(inputFile)), false)
            exoPlayer.prepare()
            exoPlayer.seekTo((currentSourcePosSec * 1000.0).roundToLong())
            if (wasPlaying) exoPlayer.play()
        }
    }

    // 30fps smooth playhead tracker mapped back to real source seconds
    LaunchedEffect(exoPlayer, previewOnlyKeptPlaylist, segments) {
        while (isActive) {
            if (previewOnlyKeptPlaylist) {
                val keptSegs = segments.filter { it.kept && it.endSec > it.startSec + 0.15 }
                val winIdx = exoPlayer.currentMediaItemIndex
                if (winIdx in keptSegs.indices) {
                    val seg = keptSegs[winIdx]
                    currentSourcePosSec = (seg.startSec + exoPlayer.currentPosition / 1000.0)
                        .coerceIn(seg.startSec, seg.endSec)
                }
            } else {
                currentSourcePosSec = (exoPlayer.currentPosition / 1000.0).coerceIn(0.0, totalDurationSec)
            }
            delay(33L)
        }
    }

    val keptTotalDurationSec = segments.filter { it.kept }.sumOf { it.durationSec } / playbackSpeed.toDouble()

    fun seekToSourceSec(sec: Double) {
        val clamped = sec.coerceIn(0.0, totalDurationSec)
        if (previewOnlyKeptPlaylist) {
            previewOnlyKeptPlaylist = false
        }
        currentSourcePosSec = clamped
        exoPlayer.seekTo((clamped * 1000.0).roundToLong())
    }

    fun splitAtCurrentPlayhead() {
        val cutT = currentSourcePosSec
        val idx = segments.indexOfFirst { cutT > it.startSec + 0.25 && cutT < it.endSec - 0.25 }
        if (idx < 0) {
            onShowMessage("Поставь ползунок минимум в 0.3 сек от края куска, чтобы разрезать")
            return
        }
        val target = segments[idx]
        val left = ClipTimeSegment(startSec = target.startSec, endSec = cutT, kept = target.kept)
        val right = ClipTimeSegment(startSec = cutT, endSec = target.endSec, kept = target.kept)
        val updated = segments.toMutableList().apply {
            removeAt(idx)
            add(idx, right)
            add(idx, left)
        }
        segments = updated
        selectedSegmentIdx = idx + 1
        onShowMessage("✂️ Разрезано в точке ${formatSecPrecise(cutT)}")
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0B0D13))
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        // Top Header: Clip Info + Target Slot Selector (Primary vs Background)
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "✂️ Разделка: $clipTitle",
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "Исходник: ${videoDims.first}×${videoDims.second} • ${formatSecPrecise(totalDurationSec)} → Итог: ${formatSecPrecise(keptTotalDurationSec)}",
                    color = Color(0xFF9CA3AF),
                    fontSize = 12.sp
                )
            }
            OutlinedButton(
                onClick = onPickVideoRequested,
                modifier = Modifier.height(34.dp)
            ) {
                Text("📂 Другое", fontSize = 12.sp)
            }
        }

        Spacer(Modifier.height(6.dp))

        // Role switcher: sending to Slot 1 (Primary) or Slot 2 (Background)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            PreEditTargetRole.entries.forEach { role ->
                FilterChip(
                    selected = targetRole == role,
                    onClick = { onChangeTargetRole(role) },
                    label = { Text(role.label, fontSize = 12.sp) },
                    modifier = Modifier.weight(1f)
                )
            }
        }

        Spacer(Modifier.height(6.dp))

        // =====================================================================
        // HARDWARE 60FPS EXOPLAYER STAGE + TOUCH CROP / WATERMARK CANVAS
        // =====================================================================
        var stageSize by remember { mutableStateOf(IntSize.Zero) }
        val latestCrop by rememberUpdatedState(cropRect)
        val latestWmList by rememberUpdatedState(watermarkBoxes)
        val latestSelectedWm by rememberUpdatedState(selectedWmIdx)
        val latestTool by rememberUpdatedState(activeTool)

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(300.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(Color.Black)
                .border(1.dp, Color(0xFF262B38), RoundedCornerShape(14.dp))
                .onSizeChanged { stageSize = it },
            contentAlignment = Alignment.Center
        ) {
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        useController = false
                        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                        player = exoPlayer
                    }
                },
                update = { pv ->
                    if (pv.player !== exoPlayer) pv.player = exoPlayer
                },
                modifier = Modifier.fillMaxSize()
            )

            // Compute fitted video rectangle inside stageSize so crop & watermark coordinates map 1:1 to video pixels!
            val videoRectInStage = remember(stageSize, videoDims) {
                computeFittedVideoBounds(stageSize, videoDims.first, videoDims.second)
            }

            if ((cropRect.enabled || watermarkBoxes.isNotEmpty() || activeTool == PreEditToolTab.FREE_CROP || activeTool == PreEditToolTab.WATERMARK_ERASE) &&
                videoRectInStage.width > 20f && videoRectInStage.height > 20f
            ) {
                var dragTarget by remember { mutableStateOf(DragHandleTarget.NONE) }

                Canvas(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(videoRectInStage, activeTool) {
                            detectDragGestures(
                                onDragStart = { down ->
                                    val vx = ((down.x - videoRectInStage.left) / videoRectInStage.width).coerceIn(0f, 1f)
                                    val vy = ((down.y - videoRectInStage.top) / videoRectInStage.height).coerceIn(0f, 1f)
                                    val hitRadiusPx = 38f

                                    if (latestTool == PreEditToolTab.FREE_CROP && latestCrop.enabled) {
                                        val c = latestCrop
                                        val tl = Offset(
                                            videoRectInStage.left + c.leftNorm * videoRectInStage.width,
                                            videoRectInStage.top + c.topNorm * videoRectInStage.height
                                        )
                                        val tr = Offset(
                                            videoRectInStage.left + c.rightNorm * videoRectInStage.width,
                                            videoRectInStage.top + c.topNorm * videoRectInStage.height
                                        )
                                        val bl = Offset(
                                            videoRectInStage.left + c.leftNorm * videoRectInStage.width,
                                            videoRectInStage.top + c.bottomNorm * videoRectInStage.height
                                        )
                                        val br = Offset(
                                            videoRectInStage.left + c.rightNorm * videoRectInStage.width,
                                            videoRectInStage.top + c.bottomNorm * videoRectInStage.height
                                        )
                                        dragTarget = when {
                                            hypot(down.x - tl.x, down.y - tl.y) <= hitRadiusPx -> DragHandleTarget.CROP_TOP_LEFT
                                            hypot(down.x - tr.x, down.y - tr.y) <= hitRadiusPx -> DragHandleTarget.CROP_TOP_RIGHT
                                            hypot(down.x - bl.x, down.y - bl.y) <= hitRadiusPx -> DragHandleTarget.CROP_BOTTOM_LEFT
                                            hypot(down.x - br.x, down.y - br.y) <= hitRadiusPx -> DragHandleTarget.CROP_BOTTOM_RIGHT
                                            vx in c.leftNorm..c.rightNorm && vy in c.topNorm..c.bottomNorm -> DragHandleTarget.CROP_INSIDE
                                            else -> DragHandleTarget.NONE
                                        }
                                    } else if (latestTool == PreEditToolTab.WATERMARK_ERASE && latestWmList.isNotEmpty()) {
                                        val hitIdx = latestWmList.indexOfLast { b ->
                                            vx in (b.xNorm - 0.04f)..(b.xNorm + b.wNorm + 0.06f) &&
                                                vy in (b.yNorm - 0.04f)..(b.yNorm + b.hNorm + 0.06f)
                                        }
                                        if (hitIdx >= 0) {
                                            selectedWmIdx = hitIdx
                                            val b = latestWmList[hitIdx]
                                            val brX = videoRectInStage.left + (b.xNorm + b.wNorm) * videoRectInStage.width
                                            val brY = videoRectInStage.top + (b.yNorm + b.hNorm) * videoRectInStage.height
                                            dragTarget = if (hypot(down.x - brX, down.y - brY) <= hitRadiusPx) {
                                                DragHandleTarget.WM_RESIZE_BR
                                            } else {
                                                DragHandleTarget.WM_MOVE
                                            }
                                        } else {
                                            dragTarget = DragHandleTarget.NONE
                                        }
                                    }
                                },
                                onDrag = { change, dragAmount ->
                                    change.consume()
                                    val dx = dragAmount.x / videoRectInStage.width
                                    val dy = dragAmount.y / videoRectInStage.height
                                    when (dragTarget) {
                                        DragHandleTarget.CROP_TOP_LEFT -> {
                                            val c = latestCrop
                                            cropRect = c.copy(
                                                leftNorm = (c.leftNorm + dx).coerceIn(0f, c.rightNorm - 0.15f),
                                                topNorm = (c.topNorm + dy).coerceIn(0f, c.bottomNorm - 0.15f)
                                            )
                                        }
                                        DragHandleTarget.CROP_TOP_RIGHT -> {
                                            val c = latestCrop
                                            cropRect = c.copy(
                                                rightNorm = (c.rightNorm + dx).coerceIn(c.leftNorm + 0.15f, 1f),
                                                topNorm = (c.topNorm + dy).coerceIn(0f, c.bottomNorm - 0.15f)
                                            )
                                        }
                                        DragHandleTarget.CROP_BOTTOM_LEFT -> {
                                            val c = latestCrop
                                            cropRect = c.copy(
                                                leftNorm = (c.leftNorm + dx).coerceIn(0f, c.rightNorm - 0.15f),
                                                bottomNorm = (c.bottomNorm + dy).coerceIn(c.topNorm + 0.15f, 1f)
                                            )
                                        }
                                        DragHandleTarget.CROP_BOTTOM_RIGHT -> {
                                            val c = latestCrop
                                            cropRect = c.copy(
                                                rightNorm = (c.rightNorm + dx).coerceIn(c.leftNorm + 0.15f, 1f),
                                                bottomNorm = (c.bottomNorm + dy).coerceIn(c.topNorm + 0.15f, 1f)
                                            )
                                        }
                                        DragHandleTarget.CROP_INSIDE -> {
                                            val c = latestCrop
                                            val w = c.rightNorm - c.leftNorm
                                            val h = c.bottomNorm - c.topNorm
                                            val nl = (c.leftNorm + dx).coerceIn(0f, 1f - w)
                                            val nt = (c.topNorm + dy).coerceIn(0f, 1f - h)
                                            cropRect = c.copy(
                                                leftNorm = nl,
                                                topNorm = nt,
                                                rightNorm = nl + w,
                                                bottomNorm = nt + h
                                            )
                                        }
                                        DragHandleTarget.WM_MOVE -> {
                                            val idx = latestSelectedWm
                                            if (idx in latestWmList.indices) {
                                                val b = latestWmList[idx]
                                                val nx = (b.xNorm + dx).coerceIn(0.01f, 0.99f - b.wNorm)
                                                val ny = (b.yNorm + dy).coerceIn(0.01f, 0.99f - b.hNorm)
                                                watermarkBoxes = latestWmList.toMutableList().apply {
                                                    set(idx, b.copy(xNorm = nx, yNorm = ny))
                                                }
                                            }
                                        }
                                        DragHandleTarget.WM_RESIZE_BR -> {
                                            val idx = latestSelectedWm
                                            if (idx in latestWmList.indices) {
                                                val b = latestWmList[idx]
                                                val nw = (b.wNorm + dx).coerceIn(0.06f, 0.99f - b.xNorm)
                                                val nh = (b.hNorm + dy).coerceIn(0.05f, 0.99f - b.yNorm)
                                                watermarkBoxes = latestWmList.toMutableList().apply {
                                                    set(idx, b.copy(wNorm = nw, hNorm = nh))
                                                }
                                            }
                                        }
                                        DragHandleTarget.NONE -> {}
                                    }
                                },
                                onDragEnd = { dragTarget = DragHandleTarget.NONE },
                                onDragCancel = { dragTarget = DragHandleTarget.NONE }
                            )
                        }
                ) {
                    // Draw Freeform Crop Overlay
                    if (cropRect.enabled) {
                        val cl = videoRectInStage.left + cropRect.leftNorm * videoRectInStage.width
                        val ct = videoRectInStage.top + cropRect.topNorm * videoRectInStage.height
                        val cw = (cropRect.rightNorm - cropRect.leftNorm) * videoRectInStage.width
                        val ch = (cropRect.bottomNorm - cropRect.topNorm) * videoRectInStage.height

                        // Dim outside crop
                        drawRect(
                            color = Color(0x99000000),
                            topLeft = Offset(videoRectInStage.left, videoRectInStage.top),
                            size = Size(videoRectInStage.width, (ct - videoRectInStage.top).coerceAtLeast(0f))
                        )
                        drawRect(
                            color = Color(0x99000000),
                            topLeft = Offset(videoRectInStage.left, ct + ch),
                            size = Size(videoRectInStage.width, (videoRectInStage.bottom - (ct + ch)).coerceAtLeast(0f))
                        )
                        drawRect(
                            color = Color(0x99000000),
                            topLeft = Offset(videoRectInStage.left, ct),
                            size = Size((cl - videoRectInStage.left).coerceAtLeast(0f), ch)
                        )
                        drawRect(
                            color = Color(0x99000000),
                            topLeft = Offset(cl + cw, ct),
                            size = Size((videoRectInStage.right - (cl + cw)).coerceAtLeast(0f), ch)
                        )

                        // Crop border & rule-of-thirds grid
                        drawRect(
                            color = Color(0xFF22D3EE),
                            topLeft = Offset(cl, ct),
                            size = Size(cw, ch),
                            style = Stroke(width = 3f)
                        )
                        // 4 corner handles
                        val corners = listOf(
                            Offset(cl, ct),
                            Offset(cl + cw, ct),
                            Offset(cl, ct + ch),
                            Offset(cl + cw, ct + ch)
                        )
                        corners.forEach { pt ->
                            drawCircle(color = Color(0xFF22D3EE), radius = 12f, center = pt)
                            drawCircle(color = Color.Black, radius = 5f, center = pt)
                        }
                    }

                    // Draw Watermark Mask Boxes
                    watermarkBoxes.forEachIndexed { idx, b ->
                        val bx = videoRectInStage.left + b.xNorm * videoRectInStage.width
                        val by = videoRectInStage.top + b.yNorm * videoRectInStage.height
                        val bw = b.wNorm * videoRectInStage.width
                        val bh = b.hNorm * videoRectInStage.height
                        val isSel = idx == selectedWmIdx
                        val strokeCol = if (isSel) Color(0xFFF43F5E) else Color(0xFFFBBF24)
                        drawRect(
                            color = strokeCol.copy(alpha = 0.28f),
                            topLeft = Offset(bx, by),
                            size = Size(bw, bh)
                        )
                        drawRect(
                            color = strokeCol,
                            topLeft = Offset(bx, by),
                            size = Size(bw, bh),
                            style = Stroke(width = if (isSel) 3.5f else 2f)
                        )
                        drawCircle(
                            color = strokeCol,
                            radius = 10f,
                            center = Offset(bx + bw, by + bh)
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        // =====================================================================
        // PLAYBACK TRANSPORT + SCRUBBER + MULTI-SEGMENT VISUAL TIMELINE BAR
        // =====================================================================
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = { if (exoPlayer.isPlaying) exoPlayer.pause() else exoPlayer.play() },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2563EB)),
                    modifier = Modifier.height(36.dp)
                ) {
                    Text(if (isPlaying) "⏸ Пауза" else "▶ Пуск", fontSize = 13.sp)
                }
                OutlinedButton(
                    onClick = { seekToSourceSec(currentSourcePosSec - 0.25) },
                    modifier = Modifier.height(36.dp)
                ) {
                    Text("-0.25с", fontSize = 11.sp)
                }
                OutlinedButton(
                    onClick = { seekToSourceSec(currentSourcePosSec + 0.25) },
                    modifier = Modifier.height(36.dp)
                ) {
                    Text("+0.25с", fontSize = 11.sp)
                }
            }
            Text(
                text = "${formatSecPrecise(currentSourcePosSec)} / ${formatSecPrecise(totalDurationSec)}",
                color = Color(0xFFE5E7EB),
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold
            )
        }

        Slider(
            value = currentSourcePosSec.toFloat(),
            onValueChange = { v -> seekToSourceSec(v.toDouble()) },
            valueRange = 0f..totalDurationSec.toFloat().coerceAtLeast(1f),
            modifier = Modifier.fillMaxWidth()
        )

        // Visual Multi-Segment Ribbon (Green = Kept, Red/Dim = Cut Out)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(32.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0xFF181C26))
                .padding(2.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            segments.forEachIndexed { idx, seg ->
                val weightVal = (seg.durationSec / totalDurationSec).toFloat().coerceAtLeast(0.03f)
                val isSelected = idx == selectedSegmentIdx
                val bgCol = when {
                    !seg.kept -> Color(0xFF4B1D24)
                    isSelected -> Color(0xFF10B981)
                    else -> Color(0xFF059669).copy(alpha = 0.72f)
                }
                Box(
                    modifier = Modifier
                        .weight(weightVal)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(6.dp))
                        .background(bgCol)
                        .border(
                            width = if (isSelected) 2.dp else 0.dp,
                            color = if (isSelected) Color.White else Color.Transparent,
                            shape = RoundedCornerShape(6.dp)
                        )
                        .clickable {
                            selectedSegmentIdx = idx
                            seekToSourceSec(seg.startSec)
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (seg.kept) "#${idx + 1}" else "✖",
                        color = Color.White,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        // Tool Switcher Row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            PreEditToolTab.entries.forEach { tab ->
                FilterChip(
                    selected = activeTool == tab,
                    onClick = { activeTool = tab },
                    label = { Text(tab.label, fontSize = 12.sp) }
                )
            }
        }

        Spacer(Modifier.height(6.dp))

        // =====================================================================
        // TOOL WORKSPACE CARD
        // =====================================================================
        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF141824)),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                when (activeTool) {
                    PreEditToolTab.TIMELINE_SPLIT -> {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = { splitAtCurrentPlayhead() },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD97706)),
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("✂️ Разрезать тут (${formatSecPrecise(currentSourcePosSec)})", fontSize = 12.sp)
                            }
                            FilterChip(
                                selected = previewOnlyKeptPlaylist,
                                onClick = { previewOnlyKeptPlaylist = !previewOnlyKeptPlaylist },
                                label = {
                                    Text(
                                        if (previewOnlyKeptPlaylist) "▶ Склейка: ВКЛ" else "👁 Проверить склейку",
                                        fontSize = 12.sp
                                    )
                                }
                            )
                        }

                        Spacer(Modifier.height(8.dp))

                        val safeIdx = selectedSegmentIdx.coerceIn(0, (segments.size - 1).coerceAtLeast(0))
                        val curSeg = segments.getOrNull(safeIdx)
                        if (curSeg != null) {
                            Surface(
                                color = Color(0xFF1D2333),
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(10.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "Кусок #${safeIdx + 1}: ${formatSecPrecise(curSeg.startSec)} – ${formatSecPrecise(curSeg.endSec)} (${formatSecPrecise(curSeg.durationSec)})",
                                            color = Color.White,
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                        Button(
                                            onClick = {
                                                val toggled = !curSeg.kept
                                                segments = segments.toMutableList().apply {
                                                    set(safeIdx, curSeg.copy(kept = toggled))
                                                }
                                            },
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = if (curSeg.kept) Color(0xFFDC2626) else Color(0xFF10B981)
                                            ),
                                            modifier = Modifier.height(32.dp)
                                        ) {
                                            Text(
                                                if (curSeg.kept) "🗑 Выкинуть кусок" else "↩️ Вернуть кусок",
                                                fontSize = 11.sp
                                            )
                                        }
                                    }

                                    Spacer(Modifier.height(6.dp))

                                    // Fine edge trim buttons for current segment
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        OutlinedButton(
                                            onClick = {
                                                val ns = (curSeg.startSec - 0.20).coerceAtLeast(0.0)
                                                segments = segments.toMutableList().apply {
                                                    set(safeIdx, curSeg.copy(startSec = ns))
                                                }
                                                seekToSourceSec(ns)
                                            },
                                            modifier = Modifier.weight(1f)
                                        ) { Text("Старт -0.2с", fontSize = 11.sp) }

                                        OutlinedButton(
                                            onClick = {
                                                val ns = (curSeg.startSec + 0.20).coerceAtMost(curSeg.endSec - 0.3)
                                                segments = segments.toMutableList().apply {
                                                    set(safeIdx, curSeg.copy(startSec = ns))
                                                }
                                                seekToSourceSec(ns)
                                            },
                                            modifier = Modifier.weight(1f)
                                        ) { Text("Старт +0.2с", fontSize = 11.sp) }

                                        OutlinedButton(
                                            onClick = {
                                                val ne = (curSeg.endSec - 0.20).coerceAtLeast(curSeg.startSec + 0.3)
                                                segments = segments.toMutableList().apply {
                                                    set(safeIdx, curSeg.copy(endSec = ne))
                                                }
                                                seekToSourceSec(ne)
                                            },
                                            modifier = Modifier.weight(1f)
                                        ) { Text("Конец -0.2с", fontSize = 11.sp) }

                                        OutlinedButton(
                                            onClick = {
                                                val ne = (curSeg.endSec + 0.20).coerceAtMost(totalDurationSec)
                                                segments = segments.toMutableList().apply {
                                                    set(safeIdx, curSeg.copy(endSec = ne))
                                                }
                                                seekToSourceSec(ne)
                                            },
                                            modifier = Modifier.weight(1f)
                                        ) { Text("Конец +0.2с", fontSize = 11.sp) }
                                    }
                                }
                            }
                        }

                        if (segments.size > 1) {
                            Spacer(Modifier.height(6.dp))
                            OutlinedButton(
                                onClick = {
                                    segments = listOf(ClipTimeSegment(startSec = 0.0, endSec = totalDurationSec, kept = true))
                                    selectedSegmentIdx = 0
                                    previewOnlyKeptPlaylist = false
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("♻️ Сбросить все разрезы (вернуть целиком)", fontSize = 12.sp)
                            }
                        }
                    }

                    PreEditToolTab.FREE_CROP -> {
                        Text(
                            text = "Тяни голубые углы или саму рамку прямо пальцем на видео выше!",
                            color = Color(0xFF93C5FD),
                            fontSize = 12.sp
                        )
                        Spacer(Modifier.height(8.dp))
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            FilterChip(
                                selected = !cropRect.enabled,
                                onClick = { cropRect = ManualCropRect(enabled = false) },
                                label = { Text("Без кропа (100%)", fontSize = 12.sp) }
                            )
                            FilterChip(
                                selected = cropRect.enabled,
                                onClick = {
                                    cropRect = ManualCropRect(
                                        enabled = true,
                                        leftNorm = 0.06f,
                                        topNorm = 0.06f,
                                        rightNorm = 0.94f,
                                        bottomNorm = 0.94f
                                    )
                                },
                                label = { Text("✋ Свободная рамка", fontSize = 12.sp) }
                            )
                            OutlinedButton(
                                onClick = {
                                    // Top 50% crop (instant cut of old bottom half on pre-split Shorts!)
                                    cropRect = ManualCropRect(
                                        enabled = true,
                                        leftNorm = 0.0f,
                                        topNorm = 0.0f,
                                        rightNorm = 1.0f,
                                        bottomNorm = 0.49f
                                    )
                                }
                            ) { Text("⬆️ Оставить Верх 50%", fontSize = 12.sp) }

                            OutlinedButton(
                                onClick = {
                                    cropRect = ManualCropRect(
                                        enabled = true,
                                        leftNorm = 0.0f,
                                        topNorm = 0.51f,
                                        rightNorm = 1.0f,
                                        bottomNorm = 1.0f
                                    )
                                }
                            ) { Text("⬇️ Оставить Низ 50%", fontSize = 12.sp) }

                            OutlinedButton(
                                onClick = {
                                    // Center 9:16 vertical crop for horizontal 16:9 videos
                                    val srcAspect = videoDims.first.toFloat() / videoDims.second.toFloat().coerceAtLeast(1f)
                                    val targetAspect = 9f / 16f
                                    if (srcAspect > targetAspect) {
                                        val wNorm = (targetAspect / srcAspect).coerceIn(0.2f, 1f)
                                        val pad = (1f - wNorm) / 2f
                                        cropRect = ManualCropRect(true, pad, 0f, 1f - pad, 1f)
                                    } else {
                                        cropRect = ManualCropRect(true, 0f, 0.05f, 1f, 0.95f)
                                    }
                                }
                            ) { Text("📱 9:16 Центр", fontSize = 12.sp) }
                        }
                    }

                    PreEditToolTab.WATERMARK_ERASE -> {
                        Text(
                            text = "Добавь зону затирки и передвинь её пальцем на логотип/водяной знак:",
                            color = Color(0xFFFDE68A),
                            fontSize = 12.sp
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = {
                                    val added = WatermarkMaskBox(xNorm = 0.65f, yNorm = 0.06f, wNorm = 0.26f, hNorm = 0.10f)
                                    watermarkBoxes = watermarkBoxes + added
                                    selectedWmIdx = watermarkBoxes.lastIndex
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE11D48))
                            ) {
                                Text("➕ Добавить зону (${watermarkBoxes.size})", fontSize = 12.sp)
                            }
                            if (watermarkBoxes.isNotEmpty()) {
                                OutlinedButton(
                                    onClick = {
                                        if (selectedWmIdx in watermarkBoxes.indices) {
                                            watermarkBoxes = watermarkBoxes.toMutableList().apply { removeAt(selectedWmIdx) }
                                            selectedWmIdx = watermarkBoxes.lastIndex
                                        } else {
                                            watermarkBoxes = emptyList()
                                            selectedWmIdx = -1
                                        }
                                    }
                                ) {
                                    Text("🗑 Удалить выбранную", fontSize = 12.sp)
                                }
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            WatermarkEraseMethod.entries.forEach { m ->
                                FilterChip(
                                    selected = eraseMethod == m,
                                    onClick = { eraseMethod = m },
                                    label = { Text(m.label.substringBefore(" ("), fontSize = 11.sp) }
                                )
                            }
                        }
                    }

                    PreEditToolTab.SPEED -> {
                        Text(
                            text = String.format(Locale.US, "Скорость воспроизведения: %.2fx", playbackSpeed),
                            color = Color.White,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Slider(
                            value = playbackSpeed,
                            onValueChange = { playbackSpeed = (it * 20f).roundToLong() / 20f },
                            valueRange = 0.75f..1.50f
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(0.85f, 1.0f, 1.10f, 1.25f).forEach { sp ->
                                FilterChip(
                                    selected = kotlin.math.abs(playbackSpeed - sp) < 0.02f,
                                    onClick = { playbackSpeed = sp },
                                    label = { Text("${sp}x", fontSize = 12.sp) }
                                )
                            }
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        // =====================================================================
        // PRIMARY CTA: BAKE & SEND DIRECTLY TO MAIN FORGE ASSEMBLER!
        // =====================================================================
        if (isBaking) {
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
                    Text(bakeStatusText, color = Color.White, fontSize = 13.sp)
                }
            }
        } else {
            Button(
                onClick = {
                    exoPlayer.pause()
                    isBaking = true
                    bakeStatusText = "✂️ Собираю чистый клип (${segments.count { it.kept }} кусков) и передаю в Главный Редактор..."
                    scope.launch {
                        try {
                            val baked = hardwareEngine.bakePreparedClip(
                                inputFile = inputFile,
                                segments = segments,
                                manualCrop = cropRect,
                                watermarkBoxes = watermarkBoxes,
                                eraseMethod = eraseMethod,
                                playbackSpeed = playbackSpeed,
                                prefix = if (targetRole == PreEditTargetRole.PRIMARY_SOURCE) "prep_main" else "prep_bg"
                            )
                            isBaking = false
                            onBakeFinishedToAssembler(baked, targetRole, clipTitle)
                        } catch (e: Exception) {
                            isBaking = false
                            onShowMessage("❌ Ошибка разделки: ${e.message}")
                        }
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981)),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
            ) {
                Text(
                    text = if (targetRole == PreEditTargetRole.PRIMARY_SOURCE) {
                        "🚀 ГОТОВО → ЗАКИНУТЬ КАК БАЗУ В РЕДАКТОР"
                    } else {
                        "🚀 ГОТОВО → ЗАКИНУТЬ КАК ФОН В РЕДАКТОР"
                    },
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.Black
                )
            }

            Spacer(Modifier.height(8.dp))

            OutlinedButton(
                onClick = {
                    exoPlayer.pause()
                    isBaking = true
                    bakeStatusText = "💾 Сохраняю чистый разделанный ролик в Галерею..."
                    scope.launch {
                        try {
                            val baked = hardwareEngine.bakePreparedClip(
                                inputFile = inputFile,
                                segments = segments,
                                manualCrop = cropRect,
                                watermarkBoxes = watermarkBoxes,
                                eraseMethod = eraseMethod,
                                playbackSpeed = playbackSpeed,
                                prefix = "clean_cut"
                            )
                            val saved = hardwareEngine.saveRenderedVideoToGallery(baked, clipTitle)
                            isBaking = false
                            onShowMessage("✅ Чистый клип сохранён в Галерею: ${saved.name}")
                        } catch (e: Exception) {
                            isBaking = false
                            onShowMessage("❌ Ошибка сохранения: ${e.message}")
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("💾 Просто сохранить этот чистый ролик в Галерею", fontSize = 12.sp)
            }
        }

        Spacer(Modifier.height(24.dp))
    }
}

private data class VideoStageBounds(
    val left: Float,
    val top: Float,
    val width: Float,
    val height: Float
) {
    val right: Float get() = left + width
    val bottom: Float get() = top + height
}

private fun computeFittedVideoBounds(stage: IntSize, videoW: Int, videoH: Int): VideoStageBounds {
    if (stage.width <= 0 || stage.height <= 0 || videoW <= 0 || videoH <= 0) {
        return VideoStageBounds(0f, 0f, 0f, 0f)
    }
    val sw = stage.width.toFloat()
    val sh = stage.height.toFloat()
    val vAspect = videoW.toFloat() / videoH.toFloat()
    val sAspect = sw / sh
    return if (vAspect > sAspect) {
        val drawW = sw
        val drawH = sw / vAspect
        VideoStageBounds(0f, (sh - drawH) / 2f, drawW, drawH)
    } else {
        val drawH = sh
        val drawW = sh * vAspect
        VideoStageBounds((sw - drawW) / 2f, 0f, drawW, drawH)
    }
}

internal fun formatSecPrecise(sec: Double): String {
    val safe = sec.coerceAtLeast(0.0)
    val mins = (safe / 60.0).toInt()
    val rem = safe - mins * 60
    return String.format(Locale.US, "%02d:%05.2f", mins, rem)
}
