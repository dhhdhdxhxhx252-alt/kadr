package dev.kadr.recorder

import android.content.Context
import org.json.JSONObject

/** Качество задаёт высоту кадра и битрейт; ширина всегда берётся из пропорций экрана. */
enum class Quality(val key: String, val label: String, val targetHeight: Int, val bitrateMbps: Int) {
    Smooth("smooth", "Плавная", 540, 4),
    Standard("standard", "Стандарт", 720, 8),
    High("high", "Высокая", 1080, 16),
    Max("max", "Максимум", 1440, 24);

    companion object {
        fun of(key: String?): Quality = entries.firstOrNull { it.key == key } ?: Standard
    }
}

enum class FrameRate(val key: String, val label: String, val fps: Int) {
    Fifteen("15", "15 к/с", 15),
    Thirty("30", "30 к/с", 30),
    Sixty("60", "60 к/с", 60);

    companion object {
        fun of(key: String?): FrameRate = entries.firstOrNull { it.key == key } ?: Thirty
    }
}

enum class AudioSource(val key: String, val label: String) {
    Silent("silent", "Без звука"),
    Mic("mic", "Микрофон");

    companion object {
        fun of(key: String?): AudioSource = entries.firstOrNull { it.key == key } ?: Silent
    }
}

/** 0 означает «без лимита». */
enum class TimeLimit(val key: String, val label: String, val minutes: Int) {
    Five("5", "5 минут", 5),
    Fifteen("15", "15 минут", 15),
    Thirty("30", "30 минут", 30),
    Sixty("60", "1 час", 60),
    None("0", "Без лимита", 0);

    companion object {
        fun of(key: String?): TimeLimit = entries.firstOrNull { it.key == key } ?: Fifteen
    }
}

data class RecordOptions(
    val quality: Quality = Quality.Standard,
    val frameRate: FrameRate = FrameRate.Thirty,
    val audio: AudioSource = AudioSource.Silent,
    val limit: TimeLimit = TimeLimit.Fifteen,
) {
    val maxDurationMs: Long get() = if (limit.minutes == 0) 0L else limit.minutes * 60_000L

    fun toJson(): String = JSONObject()
        .put("quality", quality.key)
        .put("frameRate", frameRate.key)
        .put("audio", audio.key)
        .put("limit", limit.key)
        .toString()

    companion object {
        val Default = RecordOptions()

        fun fromJson(raw: String?): RecordOptions {
            if (raw.isNullOrBlank()) return Default
            return try {
                val json = JSONObject(raw)
                RecordOptions(
                    quality = Quality.of(json.optString("quality")),
                    frameRate = FrameRate.of(json.optString("frameRate")),
                    audio = AudioSource.of(json.optString("audio")),
                    limit = TimeLimit.of(json.optString("limit")),
                )
            } catch (error: Exception) {
                Default
            }
        }
    }
}

object OptionStore {
    private const val PREFS = "kadr"
    private const val KEY_OPTIONS = "options"

    fun load(context: Context): RecordOptions =
        RecordOptions.fromJson(prefs(context).getString(KEY_OPTIONS, null))

    fun save(context: Context, options: RecordOptions) {
        prefs(context).edit().putString(KEY_OPTIONS, options.toJson()).apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
