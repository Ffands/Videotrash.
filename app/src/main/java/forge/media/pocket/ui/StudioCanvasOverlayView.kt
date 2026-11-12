package forge.media.pocket.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View
import forge.media.pocket.compositor.ManualCropRect
import forge.media.pocket.compositor.WatermarkMaskBox
import java.util.Locale
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

enum class CanvasTouchMode(val label: String) {
    PLAY_PAUSE_ONLY("Просмотр (Тап = Старт/Пауза)"),
    FREEFORM_CROP("✂️ Рамка Свободного Кропа (Горизонт + Вертикаль)"),
    SPLIT_SEAM("⇅ Двигать границу Сплита (30%..70%)"),
    WATERMARK_ERASER("🧹 Маски удаления Вотермарки / Субтитров")
}

/**
 * Интерактивный тач-оверлей поверх живого превью Студии (в стиле YouCut / CapCut):
 * 1) Свободный кроп по горизонтали и вертикали за углы/грани и перемещением центра.
 * 2) Перетаскивание линии шва между верхним исходником и нижней залипалкой (30%..70%).
 * 3) Интерактивные прямоугольники удаления вотермарок / логотипов / старых вшитых субтитров.
 */
class StudioCanvasOverlayView(context: Context) : View(context) {

    var touchMode: CanvasTouchMode = CanvasTouchMode.PLAY_PAUSE_ONLY
        set(value) {
            field = value
            invalidate()
        }

    var isSplitLayoutActive: Boolean = true
        set(value) {
            field = value
            invalidate()
        }

    var splitTopRatio: Float = 0.50f
        set(value) {
            field = value.coerceIn(0.30f, 0.70f)
            invalidate()
        }

    /**
     * Прямоугольник внутри холста (в пикселях View), в который вписан полный кадр исходника
     * в режиме редактирования кропа (с учётом его исходных пропорций).
     */
    var sourceVideoFitRect: RectF? = null
        set(value) {
            field = value
            invalidate()
        }

    var manualCrop: ManualCropRect = ManualCropRect()
        set(value) {
            field = value
            invalidate()
        }

    var watermarkBoxes: MutableList<WatermarkMaskBox> = mutableListOf()
        private set

    var selectedWatermarkIndex: Int = -1
        set(value) {
            field = value
            invalidate()
        }

    var onCanvasTapTogglePlay: (() -> Unit)? = null
    var onSplitRatioChanged: ((Float) -> Unit)? = null
    var onManualCropChanged: ((ManualCropRect) -> Unit)? = null
    var onWatermarkBoxesChanged: ((List<WatermarkMaskBox>, Int) -> Unit)? = null

    private val density = resources.displayMetrics.density
    private fun dpF(v: Float): Float = v * density

    private val dimMaskPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(155, 0, 0, 0)
        style = Paint.Style.FILL
    }

    private val cropBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#10B981")
        style = Paint.Style.STROKE
        strokeWidth = dpF(2.2f)
    }

    private val cropGridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(120, 255, 255, 255)
        style = Paint.Style.STROKE
        strokeWidth = dpF(0.9f)
    }

    private val handleFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#10B981")
        style = Paint.Style.FILL
    }

    private val seamLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#F59E0B")
        style = Paint.Style.STROKE
        strokeWidth = dpF(2.2f)
    }

    private val seamSubtlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(140, 255, 255, 255)
        style = Paint.Style.STROKE
        strokeWidth = dpF(1.0f)
        pathEffect = DashPathEffect(floatArrayOf(dpF(6f), dpF(4f)), 0f)
    }

    private val wmFillSelectedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(95, 244, 63, 94)
        style = Paint.Style.FILL
    }

    private val wmFillIdlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(55, 251, 191, 36)
        style = Paint.Style.FILL
    }

    private val wmStrokeSelectedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#F43F5E")
        style = Paint.Style.STROKE
        strokeWidth = dpF(2.2f)
    }

    private val wmStrokeIdlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FBBF24")
        style = Paint.Style.STROKE
        strokeWidth = dpF(1.5f)
        pathEffect = DashPathEffect(floatArrayOf(dpF(5f), dpF(4f)), 0f)
    }

    private val wmHandlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#F43F5E")
        style = Paint.Style.FILL
    }

    private val badgeBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(205, 9, 9, 11)
        style = Paint.Style.FILL
    }

    private val badgeTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = dpF(10.5f)
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    }

    private enum class CropDragTarget {
        NONE, TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT, LEFT, TOP, RIGHT, BOTTOM, INSIDE_PAN
    }

    private enum class WmDragTarget {
        NONE, MOVE, RESIZE_BR, RESIZE_TL, RESIZE_TR, RESIZE_BL
    }

    private var activeCropDrag = CropDragTarget.NONE
    private var activeWmDrag = WmDragTarget.NONE
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f

    fun setWatermarkList(boxes: List<WatermarkMaskBox>, selectIdx: Int = boxes.lastIndex) {
        watermarkBoxes.clear()
        watermarkBoxes.addAll(boxes)
        selectedWatermarkIndex = if (watermarkBoxes.isEmpty()) -1 else selectIdx.coerceIn(0, watermarkBoxes.lastIndex)
        invalidate()
    }

    private fun resolveActiveCropArea(): RectF {
        sourceVideoFitRect?.let { if (it.width() > 20f && it.height() > 20f) return it }
        val bottomY = if (isSplitLayoutActive) height * splitTopRatio else height.toFloat()
        return RectF(0f, 0f, width.toFloat(), bottomY.coerceAtLeast(40f))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 4 || height <= 4) return

        // 1. Draw Split Seam indicator if Split layout is active
        if (isSplitLayoutActive) {
            val seamY = height * splitTopRatio
            if (touchMode == CanvasTouchMode.SPLIT_SEAM) {
                canvas.drawLine(0f, seamY, width.toFloat(), seamY, seamLinePaint)
                val topPct = (splitTopRatio * 100f).roundToInt()
                val botPct = 100 - topPct
                drawCenteredPillBadge(canvas, width / 2f, seamY, "⇅ ШОВ СПЛИТА: $topPct% / $botPct%")
            } else if (touchMode == CanvasTouchMode.PLAY_PAUSE_ONLY) {
                canvas.drawLine(0f, seamY, width.toFloat(), seamY, seamSubtlePaint)
            }
        }

        // 2. Draw Watermark Eraser boxes (subtle dashed in preview mode, interactive in WATERMARK_ERASER mode)
        if (watermarkBoxes.isNotEmpty()) {
            watermarkBoxes.forEachIndexed { idx, box ->
                val r = RectF(
                    box.xNorm * width,
                    box.yNorm * height,
                    (box.xNorm + box.wNorm) * width,
                    (box.yNorm + box.hNorm) * height
                )
                val isSel = (idx == selectedWatermarkIndex) && (touchMode == CanvasTouchMode.WATERMARK_ERASER)
                if (touchMode == CanvasTouchMode.WATERMARK_ERASER) {
                    canvas.drawRect(r, if (isSel) wmFillSelectedPaint else wmFillIdlePaint)
                    canvas.drawRect(r, if (isSel) wmStrokeSelectedPaint else wmStrokeIdlePaint)
                    drawCornerLabel(canvas, r.left, (r.top - dpF(4f)).coerceAtLeast(dpF(14f)), "🧹 #${idx + 1}")
                    if (isSel) {
                        val hr = dpF(6.5f)
                        canvas.drawCircle(r.left, r.top, hr, wmHandlePaint)
                        canvas.drawCircle(r.right, r.top, hr, wmHandlePaint)
                        canvas.drawCircle(r.left, r.bottom, hr, wmHandlePaint)
                        canvas.drawCircle(r.right, r.bottom, hr, wmHandlePaint)
                    }
                } else {
                    canvas.drawRect(r, wmStrokeIdlePaint)
                }
            }
        }

        // 3. Draw Interactive Freeform Crop UI when in FREEFORM_CROP mode
        if (touchMode == CanvasTouchMode.FREEFORM_CROP) {
            val base = resolveActiveCropArea()
            val cLeft = base.left + manualCrop.leftNorm * base.width()
            val cTop = base.top + manualCrop.topNorm * base.height()
            val cRight = base.left + manualCrop.rightNorm * base.width()
            val cBottom = base.top + manualCrop.bottomNorm * base.height()
            val cropRect = RectF(cLeft, cTop, cRight, cBottom)

            // Dim outside crop rectangle within the stage
            canvas.drawRect(0f, 0f, width.toFloat(), cropRect.top, dimMaskPaint)
            canvas.drawRect(0f, cropRect.bottom, width.toFloat(), height.toFloat(), dimMaskPaint)
            canvas.drawRect(0f, cropRect.top, cropRect.left, cropRect.bottom, dimMaskPaint)
            canvas.drawRect(cropRect.right, cropRect.top, width.toFloat(), cropRect.bottom, dimMaskPaint)

            // Rule of thirds grid
            val thirdW = cropRect.width() / 3f
            val thirdH = cropRect.height() / 3f
            canvas.drawLine(cropRect.left + thirdW, cropRect.top, cropRect.left + thirdW, cropRect.bottom, cropGridPaint)
            canvas.drawLine(cropRect.left + thirdW * 2f, cropRect.top, cropRect.left + thirdW * 2f, cropRect.bottom, cropGridPaint)
            canvas.drawLine(cropRect.left, cropRect.top + thirdH, cropRect.right, cropRect.top + thirdH, cropGridPaint)
            canvas.drawLine(cropRect.left, cropRect.top + thirdH * 2f, cropRect.right, cropRect.top + thirdH * 2f, cropGridPaint)

            // Main green crop frame + 8 handles
            canvas.drawRect(cropRect, cropBorderPaint)
            val hr = dpF(6.5f)
            val cx = cropRect.centerX()
            val cy = cropRect.centerY()
            canvas.drawCircle(cropRect.left, cropRect.top, hr, handleFillPaint)
            canvas.drawCircle(cropRect.right, cropRect.top, hr, handleFillPaint)
            canvas.drawCircle(cropRect.left, cropRect.bottom, hr, handleFillPaint)
            canvas.drawCircle(cropRect.right, cropRect.bottom, hr, handleFillPaint)
            canvas.drawCircle(cx, cropRect.top, hr * 0.8f, handleFillPaint)
            canvas.drawCircle(cx, cropRect.bottom, hr * 0.8f, handleFillPaint)
            canvas.drawCircle(cropRect.left, cy, hr * 0.8f, handleFillPaint)
            canvas.drawCircle(cropRect.right, cy, hr * 0.8f, handleFillPaint)

            val wPct = ((manualCrop.rightNorm - manualCrop.leftNorm) * 100f).roundToInt()
            val hPct = ((manualCrop.bottomNorm - manualCrop.topNorm) * 100f).roundToInt()
            drawCenteredPillBadge(
                canvas,
                cx,
                (cropRect.top - dpF(12f)).coerceAtLeast(dpF(14f)),
                String.format(Locale.US, "КРОП: Ш %d%% × В %d%%", wPct, hPct)
            )
        }
    }

    private fun drawCenteredPillBadge(canvas: Canvas, cx: Float, cy: Float, text: String) {
        val tw = badgeTextPaint.measureText(text)
        val padH = dpF(8f)
        val padV = dpF(5f)
        val rect = RectF(cx - tw / 2f - padH, cy - dpF(8f) - padV, cx + tw / 2f + padH, cy + dpF(6f) + padV)
        canvas.drawRoundRect(rect, dpF(6f), dpF(6f), badgeBgPaint)
        canvas.drawText(text, cx - tw / 2f, cy + dpF(3.5f), badgeTextPaint)
    }

    private fun drawCornerLabel(canvas: Canvas, left: Float, baselineY: Float, text: String) {
        val tw = badgeTextPaint.measureText(text)
        val rect = RectF(left, baselineY - dpF(12f), left + tw + dpF(8f), baselineY + dpF(3f))
        canvas.drawRoundRect(rect, dpF(4f), dpF(4f), badgeBgPaint)
        canvas.drawText(text, left + dpF(4f), baselineY, badgeTextPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (width <= 4 || height <= 4) return false

        when (touchMode) {
            CanvasTouchMode.PLAY_PAUSE_ONLY -> {
                if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                    downX = event.x
                    downY = event.y
                    return true
                }
                if (event.actionMasked == MotionEvent.ACTION_UP) {
                    if (hypot(event.x - downX, event.y - downY) < dpF(14f)) {
                        onCanvasTapTogglePlay?.invoke()
                    }
                    return true
                }
                return true
            }

            CanvasTouchMode.SPLIT_SEAM -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                if (event.actionMasked == MotionEvent.ACTION_DOWN || event.actionMasked == MotionEvent.ACTION_MOVE) {
                    val newRatio = (event.y / height.toFloat()).coerceIn(0.30f, 0.70f)
                    splitTopRatio = newRatio
                    onSplitRatioChanged?.invoke(newRatio)
                    return true
                }
                return true
            }

            CanvasTouchMode.FREEFORM_CROP -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                return handleCropTouch(event)
            }

            CanvasTouchMode.WATERMARK_ERASER -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                return handleWatermarkTouch(event)
            }
        }
    }

    private fun handleCropTouch(event: MotionEvent): Boolean {
        val base = resolveActiveCropArea()
        val cLeft = base.left + manualCrop.leftNorm * base.width()
        val cTop = base.top + manualCrop.topNorm * base.height()
        val cRight = base.left + manualCrop.rightNorm * base.width()
        val cBottom = base.top + manualCrop.bottomNorm * base.height()
        val touchRadius = dpF(24f)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = event.x
                lastY = event.y
                activeCropDrag = when {
                    hypot(event.x - cLeft, event.y - cTop) <= touchRadius -> CropDragTarget.TOP_LEFT
                    hypot(event.x - cRight, event.y - cTop) <= touchRadius -> CropDragTarget.TOP_RIGHT
                    hypot(event.x - cLeft, event.y - cBottom) <= touchRadius -> CropDragTarget.BOTTOM_LEFT
                    hypot(event.x - cRight, event.y - cBottom) <= touchRadius -> CropDragTarget.BOTTOM_RIGHT
                    abs(event.x - cLeft) <= touchRadius && event.y in (cTop - touchRadius)..(cBottom + touchRadius) -> CropDragTarget.LEFT
                    abs(event.x - cRight) <= touchRadius && event.y in (cTop - touchRadius)..(cBottom + touchRadius) -> CropDragTarget.RIGHT
                    abs(event.y - cTop) <= touchRadius && event.x in (cLeft - touchRadius)..(cRight + touchRadius) -> CropDragTarget.TOP
                    abs(event.y - cBottom) <= touchRadius && event.x in (cLeft - touchRadius)..(cRight + touchRadius) -> CropDragTarget.BOTTOM
                    event.x in cLeft..cRight && event.y in cTop..cBottom -> CropDragTarget.INSIDE_PAN
                    else -> CropDragTarget.NONE
                }
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (activeCropDrag == CropDragTarget.NONE) return true
                val dxNorm = (event.x - lastX) / base.width().coerceAtLeast(1f)
                val dyNorm = (event.y - lastY) / base.height().coerceAtLeast(1f)
                lastX = event.x
                lastY = event.y

                val minSpan = 0.16f
                var l = manualCrop.leftNorm
                var t = manualCrop.topNorm
                var r = manualCrop.rightNorm
                var b = manualCrop.bottomNorm

                when (activeCropDrag) {
                    CropDragTarget.TOP_LEFT -> {
                        l = (l + dxNorm).coerceIn(0f, r - minSpan)
                        t = (t + dyNorm).coerceIn(0f, b - minSpan)
                    }
                    CropDragTarget.TOP_RIGHT -> {
                        r = (r + dxNorm).coerceIn(l + minSpan, 1f)
                        t = (t + dyNorm).coerceIn(0f, b - minSpan)
                    }
                    CropDragTarget.BOTTOM_LEFT -> {
                        l = (l + dxNorm).coerceIn(0f, r - minSpan)
                        b = (b + dyNorm).coerceIn(t + minSpan, 1f)
                    }
                    CropDragTarget.BOTTOM_RIGHT -> {
                        r = (r + dxNorm).coerceIn(l + minSpan, 1f)
                        b = (b + dyNorm).coerceIn(t + minSpan, 1f)
                    }
                    CropDragTarget.LEFT -> l = (l + dxNorm).coerceIn(0f, r - minSpan)
                    CropDragTarget.RIGHT -> r = (r + dxNorm).coerceIn(l + minSpan, 1f)
                    CropDragTarget.TOP -> t = (t + dyNorm).coerceIn(0f, b - minSpan)
                    CropDragTarget.BOTTOM -> b = (b + dyNorm).coerceIn(t + minSpan, 1f)
                    CropDragTarget.INSIDE_PAN -> {
                        val w = r - l
                        val h = b - t
                        l = (l + dxNorm).coerceIn(0f, 1f - w)
                        t = (t + dyNorm).coerceIn(0f, 1f - h)
                        r = l + w
                        b = t + h
                    }
                    CropDragTarget.NONE -> {}
                }

                manualCrop = ManualCropRect(
                    enabled = true,
                    leftNorm = l,
                    topNorm = t,
                    rightNorm = r,
                    bottomNorm = b
                )
                onManualCropChanged?.invoke(manualCrop)
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                activeCropDrag = CropDragTarget.NONE
                return true
            }
        }
        return true
    }

    private fun handleWatermarkTouch(event: MotionEvent): Boolean {
        val touchRadius = dpF(24f)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = event.x
                lastY = event.y
                activeWmDrag = WmDragTarget.NONE

                // Check selected box handles first
                if (selectedWatermarkIndex in watermarkBoxes.indices) {
                    val sel = watermarkBoxes[selectedWatermarkIndex]
                    val r = RectF(sel.xNorm * width, sel.yNorm * height, (sel.xNorm + sel.wNorm) * width, (sel.yNorm + sel.hNorm) * height)
                    activeWmDrag = when {
                        hypot(event.x - r.right, event.y - r.bottom) <= touchRadius -> WmDragTarget.RESIZE_BR
                        hypot(event.x - r.left, event.y - r.top) <= touchRadius -> WmDragTarget.RESIZE_TL
                        hypot(event.x - r.right, event.y - r.top) <= touchRadius -> WmDragTarget.RESIZE_TR
                        hypot(event.x - r.left, event.y - r.bottom) <= touchRadius -> WmDragTarget.RESIZE_BL
                        r.contains(event.x, event.y) -> WmDragTarget.MOVE
                        else -> WmDragTarget.NONE
                    }
                    if (activeWmDrag != WmDragTarget.NONE) return true
                }

                // Otherwise see if user tapped another box
                for (i in watermarkBoxes.indices.reversed()) {
                    val b = watermarkBoxes[i]
                    val r = RectF(b.xNorm * width, b.yNorm * height, (b.xNorm + b.wNorm) * width, (b.yNorm + b.hNorm) * height)
                    if (r.contains(event.x, event.y)) {
                        selectedWatermarkIndex = i
                        activeWmDrag = WmDragTarget.MOVE
                        onWatermarkBoxesChanged?.invoke(watermarkBoxes.toList(), selectedWatermarkIndex)
                        invalidate()
                        return true
                    }
                }
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (activeWmDrag == WmDragTarget.NONE || selectedWatermarkIndex !in watermarkBoxes.indices) return true
                val dxNorm = (event.x - lastX) / width.toFloat().coerceAtLeast(1f)
                val dyNorm = (event.y - lastY) / height.toFloat().coerceAtLeast(1f)
                lastX = event.x
                lastY = event.y

                val cur = watermarkBoxes[selectedWatermarkIndex]
                var left = cur.xNorm
                var top = cur.yNorm
                var right = cur.xNorm + cur.wNorm
                var bottom = cur.yNorm + cur.hNorm
                val minW = 0.05f
                val minH = 0.03f

                when (activeWmDrag) {
                    WmDragTarget.MOVE -> {
                        val w = right - left
                        val h = bottom - top
                        left = (left + dxNorm).coerceIn(0f, 1f - w)
                        top = (top + dyNorm).coerceIn(0f, 1f - h)
                        right = left + w
                        bottom = top + h
                    }
                    WmDragTarget.RESIZE_BR -> {
                        right = (right + dxNorm).coerceIn(left + minW, 1f)
                        bottom = (bottom + dyNorm).coerceIn(top + minH, 1f)
                    }
                    WmDragTarget.RESIZE_TL -> {
                        left = (left + dxNorm).coerceIn(0f, right - minW)
                        top = (top + dyNorm).coerceIn(0f, bottom - minH)
                    }
                    WmDragTarget.RESIZE_TR -> {
                        right = (right + dxNorm).coerceIn(left + minW, 1f)
                        top = (top + dyNorm).coerceIn(0f, bottom - minH)
                    }
                    WmDragTarget.RESIZE_BL -> {
                        left = (left + dxNorm).coerceIn(0f, right - minW)
                        bottom = (bottom + dyNorm).coerceIn(top + minH, 1f)
                    }
                    WmDragTarget.NONE -> {}
                }

                watermarkBoxes[selectedWatermarkIndex] = WatermarkMaskBox(
                    xNorm = left,
                    yNorm = top,
                    wNorm = (right - left).coerceAtLeast(minW),
                    hNorm = (bottom - top).coerceAtLeast(minH)
                )
                onWatermarkBoxesChanged?.invoke(watermarkBoxes.toList(), selectedWatermarkIndex)
                invalidate()
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                activeWmDrag = WmDragTarget.NONE
                return true
            }
        }
        return true
    }
}
