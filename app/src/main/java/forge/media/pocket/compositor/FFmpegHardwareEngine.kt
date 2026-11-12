package forge.media.pocket.compositor

import android.content.Context
import android.media.MediaMetadataRetriever
import android.media.MediaScannerConnection
import android.os.Environment
import android.util.Log
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegKitConfig
import com.arthenica.ffmpegkit.ReturnCode
import forge.media.pocket.config.DiagnosticLogger
import forge.media.pocket.subtitles.SubtitleLayerConfig
import forge.media.pocket.vision.DetectedHardsubRegion
import forge.media.pocket.vision.ExistingSubtitleRemovalMode
import forge.media.pocket.vision.UpperBoxFramingPlan
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.File
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.random.Random

enum class VideoLayoutLayer(val label: String) {
    SPLIT_VSTACK("Сплит Верх + Залипалка (Настраиваемый шов)"),
    FULLSCREEN_EDIT("На весь экран 9:16 (Свободный кроп)"),
    CLEAN_ORIGINAL_CROP("✂️ Чистый Оригинал / Свободный Кроп (Как YouCut)"),
    BLURRED_BG_FRAME("Кино-рамка 9:16 (Размытый фон)"),
    BRAINROT_VOICEOVER("Залипалка 9:16 + Звук исходника"),
    DISTORTED_ORIGINAL("Оригинал 9:16 с полями + Анти-Детект")
}

enum class AdInsertionMode {
    NONE,
    BANNER_OVERLAY,
    MIDROLL_SPLICE,
    MIDROLL_PIP_OVERLAY,
    PINNED_COMMENT_ONLY
}

data class AdLayerConfig(
    val mode: AdInsertionMode = AdInsertionMode.NONE,
    val bannerImageFile: File? = null,
    val bannerText: String = "Подпишись • Ссылка в закрепе",
    val bannerStartSec: Int = 5,
    val bannerEndSec: Int = 15,
    val midrollClipFile: File? = null,
    val promoLinkForComment: String = ""
)

/**
 * Произвольное кадрирование исходника по горизонтали и вертикали (0..1 в долях исходного кадра).
 */
data class ManualCropRect(
    val enabled: Boolean = false,
    val leftNorm: Float = 0.0f,
    val topNorm: Float = 0.0f,
    val rightNorm: Float = 1.0f,
    val bottomNorm: Float = 1.0f
) {
    val isNonTrivial: Boolean
        get() = enabled && (leftNorm > 0.008f || topNorm > 0.008f || rightNorm < 0.992f || bottomNorm < 0.992f)
}

enum class WatermarkEraseMethod(val label: String) {
    DELOGO_INPAINT("Затирка Delogo (Интерполяция краёв под лого)"),
    GAUSSIAN_BLUR("Мягкое размытие зоны (Gaussian Blur)"),
    DARK_MATTE_BOX("Матовая плашка поверх")
}

/**
 * Интерактивная зона удаления вотермарки / логотипа / вшитых субтитров в нормализованных координатах [0..1] выходного холста.
 */
@Serializable
data class WatermarkMaskBox(
    val xNorm: Float,
    val yNorm: Float,
    val wNorm: Float,
    val hNorm: Float
)

/**
 * Сегмент мульти-разрезки таймлайна в Цехе 1 («Первичная Разделка»).
 * Пользователь может разрезать ролик в любых точках и выкинуть лишние куски (`kept = false`).
 */
@Serializable
data class ClipTimeSegment(
    val id: String = UUID.randomUUID().toString().take(8),
    val startSec: Double,
    val endSec: Double,
    val kept: Boolean = true
) {
    val durationSec: Double
        get() = (endSec - startSec).coerceAtLeast(0.0)
}

enum class MusicRemovalMode(val label: String) {
    OFF("Выкл (Оригинальный звук)"),
    VOICE_BANDPASS_GATE("Мягкий фильтр речи (110Hz–7.2kHz + FFT шумодав)"),
    STEREO_CENTER_VOCAL("Выделение голоса из центра (Mid-канал + срез баса)"),
    AGGRESSIVE_DE_MUSIC("Жёсткий Анти-Музыка (Подавление фонового гула)")
}

enum class AudioStemRoutingMode(val label: String) {
    FULL_ORIGINAL_MIX("Дорожки: Весь звук слитно (Без разделения)"),
    DUB_OVER_CLEAN_AMBIENCE("Дорожки: 🎙 Новая озвучка + Чистый интершум видео"),
    AMBIENCE_ONLY_NO_VOICE("Дорожки: 🏍 Только Интершум (Приглушить голос)"),
    VOCAL_PLUS_AMBIENCE_NO_MUSIC("Дорожки: 🗣 Голос + Интершум (Срезать музыку)"),
    ISOLATED_VOCAL_ONLY("Дорожки: 🗣 Только выделенный Голос")
}

data class SeparatedAudioStems(
    val vocalsOverlayFile: File,
    val ambienceSfxFile: File,
    val musicHarmonicsFile: File,
    val sourceStartSec: Double,
    val sourceDurationSec: Double
)

enum class VoiceoverDubMode(val label: String) {
    OFF("Выкл (Родной голос)"),
    TIMBRE_DEEP_BARITONE("Смена тембра: Глубокий баритон (-8% тон, 100% синхрон)"),
    TIMBRE_ENERGETIC_HIGH("Смена тембра: Высокий бодрый (+8% тон, 100% синхрон)"),
    TIMBRE_RADIO_COMPRESSOR("Смена тембра: Радио-диктор (Плотный компрессор)"),
    DUB_VOICEOVER_15PCT_BG("Закадр/Даббинг: Новая озвучка + Оригинал тихо"),
    DUB_REPLACE_100PCT("Полная переозвучка: Только новая озвучка (Оригинал 0%)")
}

data class AntiDetectDspLayerConfig(
    val enabled: Boolean = true,
    val enableMicroZoom: Boolean = true,
    val enableHorizontalFlip: Boolean = false,
    val enableVideoNoiseInjection: Boolean = true,
    val enableMicroColorEqShift: Boolean = true,
    val enableAudioPitchTempoMicroJitter: Boolean = true,
    val enableAudioEqVoicePresence: Boolean = true,
    val enableSilenceTrimming350ms: Boolean = false,
    val musicRemovalMode: MusicRemovalMode = MusicRemovalMode.OFF,
    val voiceoverDubMode: VoiceoverDubMode = VoiceoverDubMode.OFF,
    val stemRoutingMode: AudioStemRoutingMode = AudioStemRoutingMode.FULL_ORIGINAL_MIX
)

data class ModularRenderConfig(
    val layoutLayer: VideoLayoutLayer = VideoLayoutLayer.SPLIT_VSTACK,
    val splitTopRatio: Float = 0.50f,
    val manualCrop: ManualCropRect = ManualCropRect(),
    val watermarkBoxes: List<WatermarkMaskBox> = emptyList(),
    val watermarkEraseMethod: WatermarkEraseMethod = WatermarkEraseMethod.DELOGO_INPAINT,
    val playbackSpeed: Float = 1.0f,
    val mainVolume: Float = 1.0f,
    val bgVolume: Float = 0.0f,
    val voiceoverVolume: Float = 1.25f,
    val musicVolume: Float = 0.25f,
    val enableSmartFaceCrop: Boolean = true,
    val trimStartSec: Double = 0.0,
    val trimEndSec: Double = 0.0,
    val selectedPoolClipName: String = "AUTO",
    val preferredTopicTags: List<String> = emptyList(),
    val customVoiceoverAudioFile: File? = null,
    val customReplacementMusicFile: File? = null,
    val enableReplacementMusicBed: Boolean = false,
    val separatedStems: SeparatedAudioStems? = null,
    val existingSubtitleRemovalMode: ExistingSubtitleRemovalMode = ExistingSubtitleRemovalMode.OFF,
    val detectedHardsubRegion: DetectedHardsubRegion? = null,
    val subtitleLayer: SubtitleLayerConfig = SubtitleLayerConfig(enabled = false),
    val adLayer: AdLayerConfig = AdLayerConfig(mode = AdInsertionMode.NONE),
    val dspLayer: AntiDetectDspLayerConfig = AntiDetectDspLayerConfig(enabled = true),
    val enableAiMetadataGeneration: Boolean = true,
    val videoBitrateKbps: Int = 12000,
    val capBitrateForTelegram50Mb: Boolean = false
) {
    companion object {
        fun presetPodcastSplit() = ModularRenderConfig(
            layoutLayer = VideoLayoutLayer.SPLIT_VSTACK,
            enableSmartFaceCrop = true,
            subtitleLayer = SubtitleLayerConfig(enabled = false),
            dspLayer = AntiDetectDspLayerConfig(enabled = true)
        )
    }
}

class FFmpegHardwareEngine(private val context: Context) {

    companion object {
        private const val TAG = "FFmpegHwEngine"
        private const val PREVIEW_DURATION_SEC = 5.0
    }

    private val stagingDir = File(context.cacheDir, "work_staging").apply { mkdirs() }

    init {
        try {
            FFmpegKitConfig.setFontDirectoryList(
                context,
                listOf("/system/fonts", "/system/font"),
                emptyMap()
            )
        } catch (e: Exception) {
            Log.w(TAG, "Font directory registration warning: ${e.message}")
        }
    }

    fun probeVideoDimensions(videoFile: File): Pair<Int, Int> {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(videoFile.absolutePath)
            val w = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 1080
            val h = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 1920
            val rot = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            if (rot == 90 || rot == 270) (h to w) else (w to h)
        } catch (_: Exception) {
            1080 to 1920
        } finally {
            try {
                retriever.release()
            } catch (_: Exception) {
            }
        }
    }

    fun computeOutputCanvasDimensions(primaryVideoFile: File, config: ModularRenderConfig): Pair<Int, Int> {
        if (config.layoutLayer != VideoLayoutLayer.CLEAN_ORIGINAL_CROP) {
            return 1080 to 1920
        }
        val (srcW, srcH) = probeVideoDimensions(primaryVideoFile)
        val mc = config.manualCrop
        val rawW = if (mc.isNonTrivial) (srcW * (mc.rightNorm - mc.leftNorm).coerceIn(0.15f, 1.0f)).roundToInt() else srcW
        val rawH = if (mc.isNonTrivial) (srcH * (mc.bottomNorm - mc.topNorm).coerceIn(0.15f, 1.0f)).roundToInt() else srcH

        // Scale up/down cleanly so longest edge is up to 1920px and dimensions are multiples of 2
        val maxSide = maxOf(rawW, rawH).coerceAtLeast(2)
        val scale = if (maxSide > 1920) 1920.0 / maxSide.toDouble() else 1.0
        val outW = ((rawW * scale).roundToInt().coerceAtLeast(128)).let { it - (it % 2) }
        val outH = ((rawH * scale).roundToInt().coerceAtLeast(128)).let { it - (it % 2) }
        return outW to outH
    }

    suspend fun saveRenderedVideoToGallery(renderedFile: File, titlePrefix: String = "Forge"): File =
        withContext(Dispatchers.IO) {
            val galleryDir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
                "PocketMediaForge"
            ).apply { mkdirs() }
            val cleanPrefix = titlePrefix.replace(Regex("""[^a-zA-Zа-яА-Я0-9_-]"""), "_").take(24).ifBlank { "Video" }
            val targetFile = File(galleryDir, "${cleanPrefix}_${System.currentTimeMillis()}.mp4")
            renderedFile.copyTo(targetFile, overwrite = true)

            MediaScannerConnection.scanFile(
                context,
                arrayOf(targetFile.absolutePath),
                arrayOf("video/mp4"),
                null
            )
            DiagnosticLogger.log(
                "GALLERY",
                "✅ Ролик сохранён в Галерею: ${targetFile.absolutePath} (${targetFile.length() / 1024} KB)"
            )
            targetFile
        }

    fun hasAudioTrack(videoFile: File): Boolean {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(videoFile.absolutePath)
            val hasAudio = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO)
            hasAudio.equals("yes", ignoreCase = true) || hasAudio == "true"
        } catch (_: Exception) {
            true
        } finally {
            try {
                retriever.release()
            } catch (_: Exception) {
            }
        }
    }

    /**
     * Цех 1 («Первичная Разделка»): Склеивает все оставленные куски (`kept = true`),
     * применяет ручной свободный кроп, затирку вотермарок (`delogo`/блюр/плашка) и скорость,
     * выдавая чистый подготовленный MP4, готовый к загрузке в Главный Редактор-Сборщик (или сохранению в Галерею)!
     */
    suspend fun bakePreparedClip(
        inputFile: File,
        segments: List<ClipTimeSegment>,
        manualCrop: ManualCropRect,
        watermarkBoxes: List<WatermarkMaskBox>,
        eraseMethod: WatermarkEraseMethod,
        playbackSpeed: Float = 1.0f,
        outputDir: File? = null,
        prefix: String = "prepared"
    ): File = withContext(Dispatchers.IO) {
        if (!inputFile.exists() || inputFile.length() < 1000L) {
            throw IllegalStateException("Исходный видеофайл не найден (${inputFile.name})")
        }

        val totalDur = probeDurationSeconds(inputFile).coerceAtLeast(0.5)
        val kept = segments
            .filter { it.kept && it.endSec > it.startSec + 0.15 }
            .map {
                it.copy(
                    startSec = it.startSec.coerceIn(0.0, (totalDur - 0.1).coerceAtLeast(0.0)),
                    endSec = it.endSec.coerceIn(0.15, totalDur)
                )
            }
            .filter { it.endSec > it.startSec + 0.12 }

        if (kept.isEmpty()) {
            throw IllegalArgumentException("Все куски на таймлайне удалены! Оставьте хотя бы один фрагмент.")
        }

        val targetDir = (outputDir ?: stagingDir).apply { mkdirs() }
        val outFile = File(targetDir, "${prefix}_${System.currentTimeMillis()}.mp4")
        val speed = playbackSpeed.coerceIn(0.5f, 2.0f)

        // Zero-reencode fast path when user didn't cut, crop, mask, or speed-shift the clip
        val isUntouchedFullClip = kept.size == 1 &&
            kept[0].startSec <= 0.12 &&
            kept[0].endSec >= (totalDur - 0.25) &&
            !manualCrop.isNonTrivial &&
            watermarkBoxes.isEmpty() &&
            abs(speed - 1.0f) < 0.02f

        if (isUntouchedFullClip) {
            inputFile.copyTo(outFile, overwrite = true)
            DiagnosticLogger.log("PRE-EDITOR", "⚡ Без перекодирования: клип передан в Сборщик как есть (${outFile.length() / 1024} KB)")
            return@withContext outFile
        }

        val (srcW, srcH) = probeVideoDimensions(inputFile)
        val hasAudio = hasAudioTrack(inputFile)

        // Compute cropped canvas dimensions (even numbers)
        val cx = if (manualCrop.isNonTrivial) ((manualCrop.leftNorm.coerceIn(0f, 0.85f) * srcW).roundToInt()).let { it - (it % 2) } else 0
        val cy = if (manualCrop.isNonTrivial) ((manualCrop.topNorm.coerceIn(0f, 0.85f) * srcH).roundToInt()).let { it - (it % 2) } else 0
        val cw = if (manualCrop.isNonTrivial) {
            (((manualCrop.rightNorm - manualCrop.leftNorm).coerceIn(0.12f, 1.0f) * srcW).roundToInt())
                .coerceIn(64, (srcW - cx).coerceAtLeast(64))
                .let { it - (it % 2) }
        } else {
            srcW - (srcW % 2)
        }
        val ch = if (manualCrop.isNonTrivial) {
            (((manualCrop.bottomNorm - manualCrop.topNorm).coerceIn(0.12f, 1.0f) * srcH).roundToInt())
                .coerceIn(64, (srcH - cy).coerceAtLeast(64))
                .let { it - (it % 2) }
        } else {
            srcH - (srcH % 2)
        }

        fun buildBakeCmd(useHw: Boolean): String {
            val stages = mutableListOf<String>()

            if (kept.size == 1) {
                val seg = kept[0]
                stages.add(
                    String.format(
                        Locale.US,
                        "[0:v:0]trim=start=%.3f:end=%.3f,setpts=PTS-STARTPTS[v_cat]",
                        seg.startSec,
                        seg.endSec
                    )
                )
                if (hasAudio) {
                    stages.add(
                        String.format(
                            Locale.US,
                            "[0:a:0]atrim=start=%.3f:end=%.3f,asetpts=PTS-STARTPTS[a_cat]",
                            seg.startSec,
                            seg.endSec
                        )
                    )
                }
            } else {
                val concatInputs = StringBuilder()
                kept.forEachIndexed { idx, seg ->
                    stages.add(
                        String.format(
                            Locale.US,
                            "[0:v:0]trim=start=%.3f:end=%.3f,setpts=PTS-STARTPTS[vs%d]",
                            seg.startSec,
                            seg.endSec,
                            idx
                        )
                    )
                    concatInputs.append("[vs$idx]")
                    if (hasAudio) {
                        stages.add(
                            String.format(
                                Locale.US,
                                "[0:a:0]atrim=start=%.3f:end=%.3f,asetpts=PTS-STARTPTS[as%d]",
                                seg.startSec,
                                seg.endSec,
                                idx
                            )
                        )
                        concatInputs.append("[as$idx]")
                    }
                }
                if (hasAudio) {
                    stages.add("${concatInputs}concat=n=${kept.size}:v=1:a=1[v_cat][a_cat]")
                } else {
                    stages.add("${concatInputs}concat=n=${kept.size}:v=1:a=0[v_cat]")
                }
            }

            var curV = "[v_cat]"
            val postV = mutableListOf<String>()
            if (abs(speed - 1.0f) >= 0.02f) {
                postV.add(String.format(Locale.US, "setpts=PTS/%.4f", speed))
            }
            postV.add("fps=30")
            if (manualCrop.isNonTrivial) {
                postV.add("crop=$cw:$ch:$cx:$cy")
            }
            postV.add("setsar=1")
            stages.add("$curV${postV.joinToString(",")}[v_cropped]")
            curV = "[v_cropped]"

            watermarkBoxes.forEachIndexed { idx, box ->
                val wx = (box.xNorm * cw).roundToInt().coerceIn(4, (cw - 32).coerceAtLeast(4))
                val wy = (box.yNorm * ch).roundToInt().coerceIn(4, (ch - 32).coerceAtLeast(4))
                val ww = (box.wNorm * cw).roundToInt().coerceIn(20, (cw - 4 - wx).coerceAtLeast(20))
                val wh = (box.hNorm * ch).roundToInt().coerceIn(20, (ch - 4 - wy).coerceAtLeast(20))
                val nextV = "[v_wm_$idx]"
                when (eraseMethod) {
                    WatermarkEraseMethod.DELOGO_INPAINT -> {
                        stages.add("${curV}delogo=x=$wx:y=$wy:w=$ww:h=$wh:show=0$nextV")
                    }
                    WatermarkEraseMethod.GAUSSIAN_BLUR -> {
                        stages.add(
                            "${curV}split=2[wmb_$idx][wmc_$idx];" +
                                "[wmc_$idx]crop=$ww:$wh:$wx:$wy,gblur=sigma=22[wmbl_$idx];" +
                                "[wmb_$idx][wmbl_$idx]overlay=$wx:$wy$nextV"
                        )
                    }
                    WatermarkEraseMethod.DARK_MATTE_BOX -> {
                        stages.add("${curV}drawbox=x=$wx:y=$wy:w=$ww:h=$wh:color=#09090B@0.94:t=fill$nextV")
                    }
                }
                curV = nextV
            }

            val pixFmt = if (useHw) "nv12" else "yuv420p"
            stages.add("${curV}format=$pixFmt[v_out]")

            if (hasAudio) {
                val aChain = mutableListOf("aresample=48000", "aformat=sample_fmts=fltp:channel_layouts=stereo")
                if (abs(speed - 1.0f) >= 0.02f) {
                    aChain.add(String.format(Locale.US, "atempo=%.4f", speed))
                }
                stages.add("[a_cat]${aChain.joinToString(",")}[a_out]")
            } else {
                // Inject silent stereo track so downstream Main Assembler & Whisper never fail on muted clips!
                val totalKeptOutSec = (kept.sumOf { it.durationSec } / speed.toDouble()).coerceAtLeast(1.0)
                stages.add(
                    String.format(
                        Locale.US,
                        "aevalsrc=0:c=stereo:s=48000:d=%.2f[a_out]",
                        totalKeptOutSec
                    )
                )
            }

            val fc = stages.joinToString(";")
            return buildString {
                append("-hide_banner -loglevel warning -nostdin -y ")
                append("-i \"${inputFile.absolutePath}\" ")
                append("-filter_complex \"$fc\" ")
                append("-map \"[v_out]\" -map \"[a_out]\" ")
                if (useHw) {
                    append("-c:v h264_mediacodec -b:v 14000k ")
                } else {
                    append("-c:v libx264 -preset superfast -crf 18 ")
                }
                append("-c:a aac -b:a 192k -ar 48000 -movflags +faststart ")
                append("\"${outFile.absolutePath}\"")
            }
        }

        DiagnosticLogger.log(
            "PRE-EDITOR",
            "✂️ Сборка разделанного клипа: кусков=${kept.size}, кроп=${manualCrop.isNonTrivial}, вотермарок=${watermarkBoxes.size}, скорость=${speed}x"
        )

        if (executeFFmpegCommand(buildBakeCmd(useHw = true)) && outFile.exists() && outFile.length() > 8_000L) {
            DiagnosticLogger.log("PRE-EDITOR", "✅ Клип подготовлен аппаратно: ${outFile.name} (${outFile.length() / 1024} KB)")
            return@withContext outFile
        }

        outFile.delete()
        if (!executeFFmpegCommand(buildBakeCmd(useHw = false)) || !outFile.exists() || outFile.length() == 0L) {
            throw IllegalStateException("Ошибка первичной разделки FFmpeg — проверьте границы кусков и кропа.")
        }
        DiagnosticLogger.log("PRE-EDITOR", "✅ Клип подготовлен программно: ${outFile.name} (${outFile.length() / 1024} KB)")
        outFile
    }

    suspend fun renderPipeline(
        primaryVideoFile: File,
        clutterReelFile: File?,
        framingPlan: UpperBoxFramingPlan,
        assSubtitleFile: File?,
        config: ModularRenderConfig,
        isPreviewPass: Boolean
    ): File = withContext(Dispatchers.IO) {
        if (!primaryVideoFile.exists() || primaryVideoFile.length() < 1000L) {
            throw IllegalStateException("Исходный видеофайл не найден (${primaryVideoFile.name})")
        }

        val totalDurationSec = probeDurationSeconds(primaryVideoFile)
        val effectiveStartSec = config.trimStartSec.coerceIn(0.0, (totalDurationSec - 0.5).coerceAtLeast(0.0))
        val effectiveEndSec = if (config.trimEndSec > effectiveStartSec + 0.5) {
            config.trimEndSec.coerceAtMost(totalDurationSec)
        } else {
            totalDurationSec
        }
        val sourceSpanSec = (effectiveEndSec - effectiveStartSec).coerceIn(1.5, 240.0)
        val inputDurationSec = if (isPreviewPass) {
            (PREVIEW_DURATION_SEC * config.playbackSpeed.coerceIn(0.5f, 2.0f)).coerceAtMost(sourceSpanSec)
        } else {
            sourceSpanSec
        }

        val suffix = if (isPreviewPass) "preview_5s" else "final_master_1080p"
        val outFile = File(stagingDir, "render_${suffix}_${System.currentTimeMillis()}.mp4")

        val hwCommand = buildModularFFmpegCommand(
            primaryVideoFile = primaryVideoFile,
            clutterReelFile = clutterReelFile,
            framingPlan = framingPlan,
            assSubtitleFile = assSubtitleFile,
            config = config,
            startOffsetSec = effectiveStartSec,
            inputDurationSec = inputDurationSec,
            isPreviewPass = isPreviewPass,
            useHardwareCodec = true,
            outputFile = outFile
        )

        DiagnosticLogger.log(
            "RENDER",
            "Запуск рендера (hw=true, шаблон=${config.layoutLayer.name}, от ${effectiveStartSec}с длит. ${inputDurationSec}с, скорость=${config.playbackSpeed}x, вотермарок=${config.watermarkBoxes.size})"
        )

        val hwSuccess = executeFFmpegCommand(hwCommand)
        if (hwSuccess && outFile.exists() && outFile.length() > 10_000L) {
            DiagnosticLogger.log("RENDER", "✅ Аппаратный рендер готов: ${outFile.length() / 1024} KB")
            return@withContext outFile
        }

        outFile.delete()
        val swCommand = buildModularFFmpegCommand(
            primaryVideoFile = primaryVideoFile,
            clutterReelFile = clutterReelFile,
            framingPlan = framingPlan,
            assSubtitleFile = assSubtitleFile,
            config = config,
            startOffsetSec = effectiveStartSec,
            inputDurationSec = inputDurationSec,
            isPreviewPass = isPreviewPass,
            useHardwareCodec = false,
            outputFile = outFile
        )
        DiagnosticLogger.log("RENDER", "Переключение на программный рендер libx264 High-Quality (CRF 18)...")

        val swSuccess = executeFFmpegCommand(swCommand)
        if (!swSuccess || !outFile.exists() || outFile.length() == 0L) {
            throw IllegalStateException("Ошибка рендера FFmpeg (проверьте выбранную область кропа/вотермарки).")
        }

        DiagnosticLogger.log("RENDER", "✅ Программный рендер готов: ${outFile.length() / 1024} KB")
        outFile
    }

    /**
     * Улучшенный 3-дорожечный спектрально-фазовый разделитель (работает даже на dual-mono Shorts без тишины!):
     * 1) vocalsOverlayFile — чистая речь в естественной полосе 105..7800Hz с мягким FFT-шумоподавлением без «рации» и без съедания окончаний слов.
     * 2) ambienceSfxFile — гибрид стерео-разности (Side) + многополосного вычитания голосовых формант (320Hz, 1150Hz, 2550Hz), сохраняющий рык мотора, шаги и воздух даже в моно-видео!
     * 3) musicHarmonicsFile — басовая и гармоническая подложка с подавлением резких согласных речи.
     */
    suspend fun splitVideoAudioIntoStems(
        primaryVideoFile: File,
        startSec: Double,
        endSec: Double
    ): SeparatedAudioStems = withContext(Dispatchers.IO) {
        val totalDur = probeDurationSeconds(primaryVideoFile)
        val effStart = startSec.coerceIn(0.0, (totalDur - 1.0).coerceAtLeast(0.0))
        val effEnd = if (endSec > effStart + 1.0) endSec.coerceAtMost(totalDur) else totalDur
        val spanSec = (effEnd - effStart).coerceIn(2.0, 180.0)

        val stamp = System.currentTimeMillis()
        val vocalsFile = File(stagingDir, "stem_vocals_$stamp.m4a")
        val ambienceFile = File(stagingDir, "stem_ambience_$stamp.m4a")
        val musicFile = File(stagingDir, "stem_music_$stamp.m4a")

        val seekArg = if (effStart > 0.0) String.format(Locale.US, "-ss %.2f ", effStart) else ""
        val filterComplex = buildString {
            append("[0:a:0]aresample=48000,aformat=sample_fmts=fltp:channel_layouts=stereo,asplit=3[a_in1][a_in2][a_in3];")
            // Stem 1: Natural Wideband Vocal (Mid sum + 105..7800Hz + gentle FFT spectral denoise, zero choppy gate!)
            append("[a_in1]pan=stereo|c0=0.5*c0+0.5*c1|c1=0.5*c0+0.5*c1,highpass=f=105,lowpass=f=7800,afftdn=nr=14:nf=-30:tn=1,equalizer=f=2600:t=q:w=1.1:g=2.8,acompressor=threshold=-20dB:ratio=2.2:attack=8:release=140:makeup=1.4[a_voc];")
            // Stem 2: Hybrid Stereo-Side + Mono-Safe Formant-Notched Ambience (NEVER goes silent on mono Shorts!)
            append("[a_in2]asplit=2[amb_side_in][amb_mono_in];")
            append("[amb_side_in]pan=stereo|c0=0.75*c0-0.75*c1|c1=0.75*c1-0.75*c0[amb_side];")
            append("[amb_mono_in]equalizer=f=320:t=q:w=1.2:g=-11,equalizer=f=1150:t=q:w=0.9:g=-15,equalizer=f=2550:t=q:w=1.0:g=-14,equalizer=f=3800:t=q:w=1.2:g=-9,volume=0.55[amb_body];")
            append("[amb_side][amb_body]amix=inputs=2:duration=first:dropout_transition=0,volume=1.55[a_amb];")
            // Stem 3: Harmonic / Bass Music Bed (retains sub-220Hz bass + upper harmonic shimmer with speech formant dip)
            append("[a_in3]equalizer=f=550:t=q:w=1.0:g=-10,equalizer=f=1450:t=q:w=0.8:g=-14,equalizer=f=2800:t=q:w=1.0:g=-12,lowpass=f=12500,volume=1.35[a_mus]")
        }

        val cmd = buildString {
            append("-hide_banner -loglevel error -nostdin -y ")
            append("$seekArg-t ${String.format(Locale.US, "%.2f", spanSec)} -i \"${primaryVideoFile.absolutePath}\" ")
            append("-filter_complex \"$filterComplex\" ")
            append("-map \"[a_voc]\" -c:a aac -b:a 192k \"${vocalsFile.absolutePath}\" ")
            append("-map \"[a_amb]\" -c:a aac -b:a 192k \"${ambienceFile.absolutePath}\" ")
            append("-map \"[a_mus]\" -c:a aac -b:a 192k \"${musicFile.absolutePath}\"")
        }

        DiagnosticLogger.log("STEM-SPLIT", "🎚 Спектрально-фазовое разделение звука (${effStart}..${effEnd}с)...")

        val ok = executeFFmpegCommand(cmd)
        if (!ok || !vocalsFile.exists() || !ambienceFile.exists()) {
            throw IllegalStateException("Не удалось разделить дорожки (проверьте наличие аудио в исходнике).")
        }

        SeparatedAudioStems(
            vocalsOverlayFile = vocalsFile,
            ambienceSfxFile = ambienceFile,
            musicHarmonicsFile = musicFile,
            sourceStartSec = effStart,
            sourceDurationSec = spanSec
        )
    }

    private fun buildModularFFmpegCommand(
        primaryVideoFile: File,
        clutterReelFile: File?,
        framingPlan: UpperBoxFramingPlan,
        assSubtitleFile: File?,
        config: ModularRenderConfig,
        startOffsetSec: Double,
        inputDurationSec: Double,
        isPreviewPass: Boolean,
        useHardwareCodec: Boolean,
        outputFile: File
    ): String {
        val inputs = mutableListOf<String>()
        val filterStages = mutableListOf<String>()

        val speed = config.playbackSpeed.coerceIn(0.5f, 2.0f)
        val outputDurationSec = (inputDurationSec / speed.toDouble()).coerceAtLeast(1.0)

        val seekArg = if (startOffsetSec > 0.0) {
            String.format(Locale.US, "-ss %.2f ", startOffsetSec)
        } else {
            ""
        }
        inputs.add("$seekArg-t ${String.format(Locale.US, "%.2f", inputDurationSec)} -i \"${primaryVideoFile.absolutePath}\"")

        var nextInputIndex = 1
        var clutterInputIndex = -1
        val needsClutterStream =
            (config.layoutLayer == VideoLayoutLayer.SPLIT_VSTACK ||
                config.layoutLayer == VideoLayoutLayer.BRAINROT_VOICEOVER) &&
                clutterReelFile != null && clutterReelFile.exists()

        if (needsClutterStream) {
            clutterInputIndex = nextInputIndex++
            inputs.add("-stream_loop -1 -t ${String.format(Locale.US, "%.2f", outputDurationSec)} -i \"${clutterReelFile!!.absolutePath}\"")
        }

        var bannerInputIndex = -1
        if (config.adLayer.mode == AdInsertionMode.BANNER_OVERLAY &&
            config.adLayer.bannerImageFile?.exists() == true
        ) {
            bannerInputIndex = nextInputIndex++
            inputs.add("-i \"${config.adLayer.bannerImageFile.absolutePath}\"")
        }

        var voiceoverInputIndex = -1
        val hasCustomVoiceover = (config.dspLayer.voiceoverDubMode == VoiceoverDubMode.DUB_VOICEOVER_15PCT_BG ||
            config.dspLayer.voiceoverDubMode == VoiceoverDubMode.DUB_REPLACE_100PCT ||
            config.dspLayer.stemRoutingMode == AudioStemRoutingMode.DUB_OVER_CLEAN_AMBIENCE) &&
            config.customVoiceoverAudioFile?.exists() == true &&
            config.customVoiceoverAudioFile.length() > 500L
        if (hasCustomVoiceover) {
            voiceoverInputIndex = nextInputIndex++
            inputs.add("-t ${String.format(Locale.US, "%.2f", outputDurationSec)} -i \"${config.customVoiceoverAudioFile!!.absolutePath}\"")
        }

        var replacementMusicInputIndex = -1
        val hasReplacementMusic = config.enableReplacementMusicBed &&
            config.customReplacementMusicFile?.exists() == true &&
            config.customReplacementMusicFile.length() > 500L
        if (hasReplacementMusic) {
            replacementMusicInputIndex = nextInputIndex++
            inputs.add("-stream_loop -1 -t ${String.format(Locale.US, "%.2f", outputDurationSec)} -i \"${config.customReplacementMusicFile!!.absolutePath}\"")
        }

        // Stage 0: Optional Playback Speed + Freeform Manual Horizontal/Vertical Crop on Source Video [0:v:0]
        val (srcW, srcH) = probeVideoDimensions(primaryVideoFile)
        val preSourceChain = mutableListOf<String>()
        if (speed != 1.0f) {
            preSourceChain.add(String.format(Locale.US, "setpts=PTS/%.4f", speed))
        }
        preSourceChain.add("fps=30")

        val mc = config.manualCrop
        if (mc.isNonTrivial) {
            val cx = ((mc.leftNorm.coerceIn(0f, 0.85f) * srcW).roundToInt()).let { it - (it % 2) }
            val cy = ((mc.topNorm.coerceIn(0f, 0.85f) * srcH).roundToInt()).let { it - (it % 2) }
            val cw = (((mc.rightNorm - mc.leftNorm).coerceIn(0.12f, 1.0f) * srcW).roundToInt())
                .coerceIn(64, (srcW - cx).coerceAtLeast(64))
                .let { it - (it % 2) }
            val ch = (((mc.bottomNorm - mc.topNorm).coerceIn(0.12f, 1.0f) * srcH).roundToInt())
                .coerceIn(64, (srcH - cy).coerceAtLeast(64))
                .let { it - (it % 2) }
            preSourceChain.add("crop=$cw:$ch:$cx:$cy")
        }
        filterStages.add("[0:v:0]${preSourceChain.joinToString(",")}[v_src_prep]")

        val (canvasW, canvasH) = computeOutputCanvasDimensions(primaryVideoFile, config)

        when (config.layoutLayer) {
            VideoLayoutLayer.SPLIT_VSTACK -> {
                val clampedRatio = config.splitTopRatio.coerceIn(0.30f, 0.70f)
                val topH = ((1920 * clampedRatio).roundToInt()).let { it - (it % 2) }.coerceIn(480, 1440)
                val botH = (1920 - topH).coerceAtLeast(480)

                if (mc.isNonTrivial) {
                    // User manually framed exact region -> fill upper box cleanly with their crop!
                    filterStages.add(
                        "[v_src_prep]scale=1080:$topH:force_original_aspect_ratio=increase:flags=lanczos," +
                            "crop=1080:$topH:(iw-1080)/2:(ih-$topH)/2,setsar=1[v_top]"
                    )
                } else {
                    val adaptedUpperFilter = framingPlan.ffmpegVideoFilter
                        .replace("1080:960", "1080:$topH")
                        .replace("ih-960", "ih-$topH")
                    filterStages.add("[v_src_prep]$adaptedUpperFilter[v_top]")
                }

                if (clutterInputIndex >= 0) {
                    filterStages.add(
                        "[$clutterInputIndex:v:0]fps=30,scale=1080:$botH:force_original_aspect_ratio=increase:flags=lanczos," +
                            "crop=1080:$botH:(iw-1080)/2:(ih-$botH)/2,setsar=1[v_bot]"
                    )
                    filterStages.add("[v_top][v_bot]vstack=inputs=2[v_base]")
                } else {
                    filterStages.add("[v_top]pad=1080:1920:0:0:color=#08080C[v_base]")
                }
            }

            VideoLayoutLayer.CLEAN_ORIGINAL_CROP -> {
                // Pure YouCut-style single-video trim & freeform crop without forced 9:16 borders!
                filterStages.add(
                    "[v_src_prep]scale=$canvasW:$canvasH:flags=lanczos,setsar=1[v_base]"
                )
            }

            VideoLayoutLayer.BLURRED_BG_FRAME -> {
                filterStages.add(
                    "[v_src_prep]split=2[v_bg_in][v_fg_in];" +
                        "[v_bg_in]scale=1080:1920:force_original_aspect_ratio=increase:flags=fast_bilinear," +
                        "crop=1080:1920:(iw-1080)/2:(ih-1920)/2,boxblur=24:2,eq=brightness=-0.16:saturation=1.25[v_bg];" +
                        "[v_fg_in]scale=1080:1520:force_original_aspect_ratio=decrease:flags=lanczos[v_fg];" +
                        "[v_bg][v_fg]overlay=(W-w)/2:(H-h)/2,setsar=1[v_base]"
                )
            }

            VideoLayoutLayer.FULLSCREEN_EDIT -> {
                filterStages.add(
                    "[v_src_prep]scale=1080:1920:force_original_aspect_ratio=increase:flags=lanczos," +
                        "crop=1080:1920:(iw-1080)/2:(ih-1920)/2,setsar=1[v_base]"
                )
            }

            VideoLayoutLayer.BRAINROT_VOICEOVER -> {
                if (clutterInputIndex >= 0) {
                    filterStages.add(
                        "[$clutterInputIndex:v:0]fps=30,scale=1080:1920:force_original_aspect_ratio=increase:flags=lanczos," +
                            "crop=1080:1920:(iw-1080)/2:(ih-1920)/2,setsar=1[v_base]"
                    )
                } else {
                    filterStages.add(
                        "[v_src_prep]scale=1080:1920:force_original_aspect_ratio=increase:flags=lanczos," +
                            "crop=1080:1920:(iw-1080)/2:(ih-1920)/2,setsar=1[v_base]"
                    )
                }
            }

            VideoLayoutLayer.DISTORTED_ORIGINAL -> {
                filterStages.add(
                    "[v_src_prep]scale=1080:1920:force_original_aspect_ratio=decrease:flags=lanczos," +
                        "pad=1080:1920:(ow-iw)/2:(oh-ih)/2:color=#0A0A0E,setsar=1[v_base]"
                )
            }
        }

        var currentVideoLabel = "[v_base]"

        // Interactive Multi-Zone Watermark / Logo / Burned-in Subtitle Eraser on Output Canvas!
        if (config.watermarkBoxes.isNotEmpty()) {
            config.watermarkBoxes.forEachIndexed { idx, box ->
                // FFmpeg delogo requires x >= 2, y >= 2, x + w <= canvasW - 2, y + h <= canvasH - 2
                val wx = (box.xNorm * canvasW).roundToInt().coerceIn(4, (canvasW - 36).coerceAtLeast(4))
                val wy = (box.yNorm * canvasH).roundToInt().coerceIn(4, (canvasH - 36).coerceAtLeast(4))
                val ww = (box.wNorm * canvasW).roundToInt().coerceIn(24, (canvasW - 4 - wx).coerceAtLeast(24))
                val wh = (box.hNorm * canvasH).roundToInt().coerceIn(24, (canvasH - 4 - wy).coerceAtLeast(24))
                val nextLbl = "[v_wm_$idx]"

                when (config.watermarkEraseMethod) {
                    WatermarkEraseMethod.DELOGO_INPAINT -> {
                        filterStages.add("${currentVideoLabel}delogo=x=$wx:y=$wy:w=$ww:h=$wh:show=0$nextLbl")
                    }
                    WatermarkEraseMethod.GAUSSIAN_BLUR -> {
                        filterStages.add(
                            "${currentVideoLabel}split=2[wm_bg_$idx][wm_crop_$idx];" +
                                "[wm_crop_$idx]crop=$ww:$wh:$wx:$wy,gblur=sigma=22[wm_blur_$idx];" +
                                "[wm_bg_$idx][wm_blur_$idx]overlay=$wx:$wy$nextLbl"
                        )
                    }
                    WatermarkEraseMethod.DARK_MATTE_BOX -> {
                        filterStages.add("${currentVideoLabel}drawbox=x=$wx:y=$wy:w=$ww:h=$wh:color=#09090B@0.94:t=fill$nextLbl")
                    }
                }
                currentVideoLabel = nextLbl
            }
        } else if (config.existingSubtitleRemovalMode != ExistingSubtitleRemovalMode.OFF) {
            val reg = config.detectedHardsubRegion ?: DetectedHardsubRegion(detected = true)
            val topSplitPx = (1920 * config.splitTopRatio.coerceIn(0.30f, 0.70f)).roundToInt()
            val (boxTopY, boxHeight) = when (config.layoutLayer) {
                VideoLayoutLayer.SPLIT_VSTACK -> 0 to topSplitPx
                VideoLayoutLayer.BLURRED_BG_FRAME -> 200 to 1520
                else -> 0 to canvasH
            }
            val dx = (reg.xNorm * canvasW).toInt().coerceIn(6, (canvasW - 80).coerceAtLeast(6))
            val dw = (reg.widthNorm * canvasW).toInt().coerceIn(64, (canvasW - 6 - dx).coerceAtLeast(64))
            val dy = (boxTopY + (reg.yNorm * boxHeight).toInt()).coerceIn(6, (canvasH - 60).coerceAtLeast(6))
            val dh = (reg.heightNorm * boxHeight).toInt().coerceIn(40, (canvasH - 6 - dy).coerceAtLeast(40))

            when (config.existingSubtitleRemovalMode) {
                ExistingSubtitleRemovalMode.AUTO_DELOGO_INPAINT -> {
                    filterStages.add("${currentVideoLabel}delogo=x=$dx:y=$dy:w=$dw:h=$dh:show=0[v_nosub]")
                    currentVideoLabel = "[v_nosub]"
                }
                ExistingSubtitleRemovalMode.COVER_WITH_DARK_PLATE -> {
                    filterStages.add("${currentVideoLabel}drawbox=x=$dx:y=$dy:w=$dw:h=$dh:color=#09090B@0.92:t=fill[v_nosub]")
                    currentVideoLabel = "[v_nosub]"
                }
                ExistingSubtitleRemovalMode.AUTO_CROP_ABOVE_SUBS -> {
                    if (config.layoutLayer == VideoLayoutLayer.FULLSCREEN_EDIT) {
                        filterStages.add("${currentVideoLabel}scale=1240:2204:flags=lanczos,crop=1080:1920:80:0[v_nosub]")
                        currentVideoLabel = "[v_nosub]"
                    }
                }
                ExistingSubtitleRemovalMode.OFF -> {}
            }
        }

        if (config.dspLayer.enabled) {
            val videoDspFilters = mutableListOf<String>()
            if (config.dspLayer.enableHorizontalFlip) {
                videoDspFilters.add("hflip")
            }
            if (config.dspLayer.enableMicroZoom) {
                val zW = ((canvasW * 1.04).roundToInt()).let { it + (it % 2) }
                val zH = ((canvasH * 1.04).roundToInt()).let { it + (it % 2) }
                val offX = (zW - canvasW) / 2
                val offY = (zH - canvasH) / 2
                videoDspFilters.add("scale=$zW:$zH:flags=lanczos,crop=$canvasW:$canvasH:$offX:$offY")
            }
            if (config.dspLayer.enableVideoNoiseInjection) {
                videoDspFilters.add("noise=alls=2:allf=t+u")
            }
            if (config.dspLayer.enableMicroColorEqShift) {
                val contrast = String.format(Locale.US, "%.3f", 1.018 + Random.nextDouble(0.0, 0.012))
                val saturation = String.format(Locale.US, "%.3f", 1.022 + Random.nextDouble(0.0, 0.015))
                videoDspFilters.add("eq=contrast=$contrast:saturation=$saturation:gamma=1.010")
            }
            if (videoDspFilters.isNotEmpty()) {
                filterStages.add("$currentVideoLabel${videoDspFilters.joinToString(",")}[v_dsp]")
                currentVideoLabel = "[v_dsp]"
            }
        }

        if (config.subtitleLayer.enabled && assSubtitleFile != null && assSubtitleFile.exists()) {
            val escapedAssPath = assSubtitleFile.absolutePath
                .replace("\\", "/")
                .replace(":", "\\:")
                .replace("'", "\\'")
            filterStages.add("${currentVideoLabel}ass=filename='$escapedAssPath':fontsdir='/system/fonts'[v_sub]")
            currentVideoLabel = "[v_sub]"
        }

        // =====================================================================
        // AUDIO PIPELINE (100% DRIFT-FREE SYNC + PER-TRACK VOLUME MIXERS)
        // =====================================================================
        var currentAudioLabel = "[0:a:0]"
        val audioFilters = mutableListOf<String>()
        audioFilters.add("aresample=48000")
        audioFilters.add("aformat=sample_fmts=fltp:channel_layouts=stereo")

        if (speed != 1.0f) {
            audioFilters.add(String.format(Locale.US, "atempo=%.4f", speed))
        }

        when (config.dspLayer.stemRoutingMode) {
            AudioStemRoutingMode.FULL_ORIGINAL_MIX -> {
                if (replacementMusicInputIndex >= 0 && config.dspLayer.musicRemovalMode == MusicRemovalMode.OFF) {
                    audioFilters.add("highpass=f=115,lowpass=f=7500,afftdn=nr=12:nf=-28")
                }
            }
            AudioStemRoutingMode.AMBIENCE_ONLY_NO_VOICE,
            AudioStemRoutingMode.DUB_OVER_CLEAN_AMBIENCE -> {
                audioFilters.add(
                    "equalizer=f=320:t=q:w=1.2:g=-11," +
                        "equalizer=f=1150:t=q:w=0.9:g=-15," +
                        "equalizer=f=2550:t=q:w=1.0:g=-14," +
                        "equalizer=f=3800:t=q:w=1.2:g=-9"
                )
            }
            AudioStemRoutingMode.VOCAL_PLUS_AMBIENCE_NO_MUSIC -> {
                audioFilters.add("highpass=f=115,lowpass=f=7600,afftdn=nr=12:nf=-28,equalizer=f=2500:t=q:w=1.0:g=2.5")
            }
            AudioStemRoutingMode.ISOLATED_VOCAL_ONLY -> {
                audioFilters.add(
                    "pan=stereo|c0=0.5*c0+0.5*c1|c1=0.5*c0+0.5*c1," +
                        "highpass=f=110,lowpass=f=7800,afftdn=nr=14:nf=-30:tn=1,equalizer=f=2600:t=q:w=1.1:g=2.8"
                )
            }
        }

        when (config.dspLayer.musicRemovalMode) {
            MusicRemovalMode.OFF -> {}
            MusicRemovalMode.VOICE_BANDPASS_GATE -> {
                audioFilters.add("highpass=f=110,lowpass=f=7200,afftdn=nr=12:nf=-28,equalizer=f=2500:t=q:w=1.0:g=2.5")
            }
            MusicRemovalMode.STEREO_CENTER_VOCAL -> {
                audioFilters.add("pan=stereo|c0=0.5*c0+0.5*c1|c1=0.5*c0+0.5*c1,highpass=f=120,lowpass=f=7400,afftdn=nr=14:nf=-30")
            }
            MusicRemovalMode.AGGRESSIVE_DE_MUSIC -> {
                audioFilters.add("pan=stereo|c0=0.5*c0+0.5*c1|c1=0.5*c0+0.5*c1,highpass=f=145,lowpass=f=6400,afftdn=nr=18:nf=-32")
            }
        }

        when (config.dspLayer.voiceoverDubMode) {
            VoiceoverDubMode.TIMBRE_DEEP_BARITONE -> {
                audioFilters.add("asetrate=48000*0.92,aresample=48000,atempo=1.086956,equalizer=f=165:t=q:w=1.0:g=3.0")
            }
            VoiceoverDubMode.TIMBRE_ENERGETIC_HIGH -> {
                audioFilters.add("asetrate=48000*1.08,aresample=48000,atempo=0.925926,equalizer=f=3200:t=q:w=1.1:g=2.2")
            }
            VoiceoverDubMode.TIMBRE_RADIO_COMPRESSOR -> {
                audioFilters.add("asetrate=48000*0.96,aresample=48000,atempo=1.041667,highpass=f=85,acompressor=threshold=-18dB:ratio=3.0:attack=10:release=120:makeup=2.2")
            }
            else -> {}
        }

        if (config.dspLayer.enabled) {
            if (config.dspLayer.enableSilenceTrimming350ms) {
                audioFilters.add(
                    "silenceremove=start_periods=1:start_duration=0.15:start_threshold=-48dB:" +
                        "stop_periods=-1:stop_duration=0.45:stop_threshold=-48dB"
                )
            }
            if (config.dspLayer.enableAudioPitchTempoMicroJitter &&
                config.dspLayer.voiceoverDubMode == VoiceoverDubMode.OFF
            ) {
                // CRITICAL SYNC FIX: 1.022 * 0.978474 == 1.000000 -> shifts voice pitch +2.2% for Anti-Detect WITHOUT drifting from video & subtitles!
                audioFilters.add("asetrate=48000*1.022,aresample=48000,atempo=0.978474")
            }
            if (config.dspLayer.enableAudioEqVoicePresence) {
                audioFilters.add("highpass=f=65,equalizer=f=3100:t=q:w=1.2:g=1.6,extrastereo=m=1.08")
            }
        }

        val mainVol = config.mainVolume.coerceIn(0.0f, 2.5f)
        val voVol = config.voiceoverVolume.coerceIn(0.0f, 2.5f)
        val musVol = config.musicVolume.coerceIn(0.0f, 1.5f)

        if (voiceoverInputIndex >= 0) {
            when {
                config.dspLayer.stemRoutingMode == AudioStemRoutingMode.DUB_OVER_CLEAN_AMBIENCE -> {
                    val ambGain = String.format(Locale.US, "%.2f", (0.85f * mainVol).coerceIn(0f, 2f))
                    filterStages.add("$currentAudioLabel${audioFilters.joinToString(",")},volume=$ambGain[a_amb_clean]")
                    filterStages.add(
                        "[$voiceoverInputIndex:a:0]aresample=48000,aformat=sample_fmts=fltp:channel_layouts=stereo," +
                            "volume=${String.format(Locale.US, "%.2f", voVol)},apad=whole_dur=${String.format(Locale.US, "%.2f", outputDurationSec)}[a_vo]"
                    )
                    filterStages.add("[a_amb_clean][a_vo]amix=inputs=2:duration=first:dropout_transition=0[a_main]")
                    currentAudioLabel = "[a_main]"
                }
                config.dspLayer.voiceoverDubMode == VoiceoverDubMode.DUB_VOICEOVER_15PCT_BG -> {
                    val duckGain = String.format(Locale.US, "%.2f", (0.18f * mainVol).coerceIn(0f, 1f))
                    filterStages.add("$currentAudioLabel${audioFilters.joinToString(",")},volume=$duckGain[a_orig_ducked]")
                    filterStages.add(
                        "[$voiceoverInputIndex:a:0]aresample=48000,aformat=sample_fmts=fltp:channel_layouts=stereo," +
                            "volume=${String.format(Locale.US, "%.2f", voVol)},apad=whole_dur=${String.format(Locale.US, "%.2f", outputDurationSec)}[a_vo]"
                    )
                    filterStages.add("[a_orig_ducked][a_vo]amix=inputs=2:duration=first:dropout_transition=0[a_main]")
                    currentAudioLabel = "[a_main]"
                }
                config.dspLayer.voiceoverDubMode == VoiceoverDubMode.DUB_REPLACE_100PCT -> {
                    filterStages.add(
                        "[$voiceoverInputIndex:a:0]aresample=48000,aformat=sample_fmts=fltp:channel_layouts=stereo," +
                            "volume=${String.format(Locale.US, "%.2f", voVol)},apad=whole_dur=${String.format(Locale.US, "%.2f", outputDurationSec)}[a_main]"
                    )
                    currentAudioLabel = "[a_main]"
                }
                else -> {
                    audioFilters.add(String.format(Locale.US, "volume=%.2f", mainVol))
                    filterStages.add("$currentAudioLabel${audioFilters.joinToString(",")}[a_main]")
                    currentAudioLabel = "[a_main]"
                }
            }
        } else {
            audioFilters.add(String.format(Locale.US, "volume=%.2f", mainVol))
            filterStages.add("$currentAudioLabel${audioFilters.joinToString(",")}[a_main]")
            currentAudioLabel = "[a_main]"
        }

        if (replacementMusicInputIndex >= 0) {
            filterStages.add(
                "[$replacementMusicInputIndex:a:0]aresample=48000,aformat=sample_fmts=fltp:channel_layouts=stereo," +
                    "volume=${String.format(Locale.US, "%.2f", musVol)},afade=t=in:st=0:d=0.8[a_bgm_new]"
            )
            filterStages.add("$currentAudioLabel[a_bgm_new]amix=inputs=2:duration=first:dropout_transition=0[a_with_bgm]")
            currentAudioLabel = "[a_with_bgm]"
        }

        val bgVol = config.bgVolume.coerceIn(0.0f, 1.5f)
        if (clutterInputIndex >= 0 && bgVol > 0.02f && clutterReelFile != null && hasAudioTrack(clutterReelFile)) {
            filterStages.add(
                "[$clutterInputIndex:a:0]aresample=48000,aformat=sample_fmts=fltp:channel_layouts=stereo," +
                    "volume=${String.format(Locale.US, "%.2f", bgVol)}[a_clutter_bg]"
            )
            filterStages.add("$currentAudioLabel[a_clutter_bg]amix=inputs=2:duration=first:dropout_transition=0[a_with_clutter]")
            currentAudioLabel = "[a_with_clutter]"
        }

        if (config.adLayer.mode == AdInsertionMode.BANNER_OVERLAY) {
            val startT = if (isPreviewPass) 0 else config.adLayer.bannerStartSec
            val endT = if (isPreviewPass) 5 else config.adLayer.bannerEndSec
            if (bannerInputIndex >= 0) {
                filterStages.add(
                    "[$bannerInputIndex:v:0]scale=${(canvasW * 0.88).roundToInt()}:-2[v_banner];" +
                        "$currentVideoLabel[v_banner]overlay=(W-w)/2:120:enable='between(t,$startT,$endT)'[v_ad]"
                )
                currentVideoLabel = "[v_ad]"
            }
        }

        val targetPixFmt = if (useHardwareCodec) "nv12" else "yuv420p"
        filterStages.add("${currentVideoLabel}format=$targetPixFmt[v_out]")

        val filterComplex = filterStages.joinToString(";")

        val targetBitrateKbps = when {
            isPreviewPass -> 6500
            config.capBitrateForTelegram50Mb -> {
                val maxAllowedKbpsFor48Mb = ((47.0 * 8192.0) / outputDurationSec).toInt() - 192
                config.videoBitrateKbps.coerceAtMost(maxAllowedKbpsFor48Mb.coerceAtLeast(3500))
            }
            else -> config.videoBitrateKbps.coerceIn(6000, 18000)
        }

        return buildString {
            append("-hide_banner -loglevel warning -nostdin -y ")
            append(inputs.joinToString(" "))
            append(" -filter_complex \"$filterComplex\" ")
            append("-map \"[v_out]\" -map \"$currentAudioLabel\" ")
            append("-map_metadata -1 ")
            if (useHardwareCodec) {
                append("-c:v h264_mediacodec -b:v ${targetBitrateKbps}k ")
            } else {
                append("-c:v libx264 -preset superfast -crf 18 -maxrate ${targetBitrateKbps}k -bufsize ${targetBitrateKbps * 2}k ")
            }
            append("-c:a aac -b:a 192k -ar 48000 ")
            append("-movflags +faststart ")
            append("\"${outputFile.absolutePath}\"")
        }
    }

    private suspend fun executeFFmpegCommand(cmd: String): Boolean =
        suspendCancellableCoroutine { cont ->
            FFmpegKit.executeAsync(cmd) { session ->
                val ok = ReturnCode.isSuccess(session.returnCode)
                if (!ok) {
                    Log.e(TAG, "FFmpeg execution failed:\n${session.allLogsAsString}")
                    DiagnosticLogger.log("FFMPEG:ERR", "Ошибка FFmpeg: ${session.allLogsAsString?.takeLast(320)}")
                }
                if (cont.isActive) cont.resume(ok)
            }
        }

    fun probeDurationSeconds(videoFile: File): Double {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(videoFile.absolutePath)
            val ms = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 30_000L
            ms / 1000.0
        } catch (_: Exception) {
            30.0
        } finally {
            try {
                retriever.release()
            } catch (_: Exception) {
            }
        }
    }
}
