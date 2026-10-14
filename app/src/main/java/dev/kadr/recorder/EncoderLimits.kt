package dev.kadr.recorder

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.util.DisplayMetrics
import kotlin.math.min
import kotlin.math.roundToInt

/** Размер виртуального экрана под запись. Всегда портретный: короткая сторона — высота. */
data class Frame(val width: Int, val height: Int, val densityDpi: Int) {
    companion object {
        fun of(metrics: DisplayMetrics, quality: Quality): Frame {
            val shortSide = min(metrics.widthPixels, metrics.heightPixels)
            val longSide = maxOf(metrics.widthPixels, metrics.heightPixels)
            val scale = min(1f, quality.targetHeight.toFloat() / shortSide.toFloat())
            return Frame(
                width = even(longSide * scale),
                height = even(shortSide * scale),
                densityDpi = metrics.densityDpi,
            )
        }

        /** Кодеки не любят нечётные грани. */
        private fun even(pixels: Float): Int = (pixels.roundToInt() / 2) * 2
    }
}

/**
 * Верхние границы энкодера конкретного устройства. Нули означают «у кодека нет
 * видео-лимитов», и тогда значения берутся из пресета как есть.
 */
data class EncoderLimits(val maxWidth: Int, val maxHeight: Int, val maxBitrate: Int) {

    fun fits(frame: Frame): Boolean =
        (maxWidth == 0 || frame.width <= maxWidth) && (maxHeight == 0 || frame.height <= maxHeight)

    /** Пресет задан для 1080p; для других разрешений битрейт пересчитывается по числу пикселей. */
    fun bitrate(frame: Frame, quality: Quality): Int {
        val reference = 1_080L * 1_920L
        val requested = quality.bitrateMbps * 1_000_000L
        val pixels = frame.width.toLong() * frame.height.toLong()
        val scaled = (requested * pixels / reference).coerceAtLeast(requested / 4)
        val ceiling = if (maxBitrate > 0) maxBitrate.toLong() else Long.MAX_VALUE
        return min(scaled, ceiling).toInt()
    }

    /** Крупные кадры режем по возможностям кодека, чтобы configure() не упал. */
    fun shrink(frame: Frame): Frame {
        if (fits(frame)) return frame
        var width = frame.width
        var height = frame.height
        if (maxWidth in 1..width - 1) {
            height = (height.toLong() * maxWidth / width).toInt()
            width = maxWidth
        }
        if (maxHeight in 1..height - 1) {
            width = (width.toLong() * maxHeight / height).toInt()
            height = maxHeight
        }
        return Frame(evenDown(width), evenDown(height), frame.densityDpi)
    }

    companion object {
        fun of(mime: String): EncoderLimits {
            val info = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.firstOrNull { candidate ->
                candidate.isEncoder && candidate.supportedTypes.any { it.equals(mime, ignoreCase = true) }
            } ?: return NONE
            val caps = info.getCapabilitiesForType(mime)
            val video = caps.videoCapabilities
            return if (video != null) {
                EncoderLimits(
                    maxWidth = video.supportedWidths?.upper ?: 0,
                    maxHeight = video.supportedHeights?.upper ?: 0,
                    maxBitrate = video.bitrateRange?.upper ?: 0,
                )
            } else {
                EncoderLimits(0, 0, caps.audioCapabilities?.bitrateRange?.upper ?: 0)
            }
        }

        private val NONE = EncoderLimits(0, 0, 0)

        private fun evenDown(value: Int): Int = (value.coerceAtLeast(2) / 2) * 2
    }
}
