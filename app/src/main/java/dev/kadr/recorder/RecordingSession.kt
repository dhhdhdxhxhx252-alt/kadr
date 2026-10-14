package dev.kadr.recorder

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.core.content.ContextCompat
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.min

/**
 * Труба записи: MediaProjection -> VirtualDisplay -> surface видеокодека,
 * плюс отдельный поток для микрофона. Оба потока пишут в один MediaMuxer
 * под общим замком, поэтому дорожки стартуют вместе и EOS ждём с обеих сторон.
 */
class RecordingSession(
    private val context: Context,
    private val projection: MediaProjection,
    private val frame: Frame,
    private val descriptor: ParcelFileDescriptor,
    private val options: RecordOptions,
    private val listener: Listener,
) {
    interface Listener {
        fun onReady()

        /** [error] равен null, когда файл записан целиком. */
        fun onFinished(error: String?)
    }

    private enum class Track { Video, Audio }

    private val lock = Any()
    private val audioLatch = CountDownLatch(1)
    private val videoInfo = MediaCodec.BufferInfo()
    private val audioInfo = MediaCodec.BufferInfo()

    private var muxer: MediaMuxer? = null
    private var videoCodec: MediaCodec? = null
    private var audioCodec: MediaCodec? = null
    private var audioRecord: AudioRecord? = null
    private var display: VirtualDisplay? = null
    private var worker: Thread? = null
    private var videoTrack = -1
    private var audioTrack = -1
    private var tracksAdded = 0
    private var muxerStarted = false
    private var deadline = 0L

    @Volatile
    private var stopRequested = false
    private var videoEosSent = false
    private var audioEosSent = false
    private var audioSamples = 0L

    /** Готовится в prepare(): микрофон могли отключить в настройках между запросом и стартом. */
    private var audioEnabled = false
    private val tracksExpected get() = if (audioEnabled) 2 else 1

    fun start() {
        worker = Thread(::videoLoop, "kadr-video").apply { isDaemon = true; start() }
    }

    fun stop() {
        stopRequested = true
    }

    fun awaitStop(millis: Long) {
        worker?.join(millis)
    }

    private fun videoLoop() {
        var error: String? = null
        try {
            prepare()
            var eos = false
            while (!eos) {
                if (!stopRequested && deadline > 0L && SystemClock.elapsedRealtime() >= deadline) {
                    stopRequested = true
                }
                if (!videoEosSent && stopRequested) {
                    videoCodec?.signalEndOfInputStream()
                    videoEosSent = true
                }
                eos = drain(videoCodec, videoInfo, Track.Video)
            }
            if (audioEnabled) audioLatch.await(EOS_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            error = "Запись прервана"
        } catch (failure: Exception) {
            error = failure.message ?: "Не удалось записать видео"
        } finally {
            release()
            listener.onFinished(error)
        }
    }

    private fun audioLoop() {
        var eos = false
        try {
            val codec = audioCodec ?: return
            val record = audioRecord ?: return
            val bytes = ByteArray(AUDIO_BYTES)
            while (!eos) {
                if (!stopRequested) {
                    val read = record.read(bytes, 0, bytes.size)
                    if (read > 0) queueAudio(codec, bytes, read)
                } else if (!audioEosSent) {
                    val read = record.read(bytes, 0, bytes.size)
                    if (read > 0) queueAudio(codec, bytes, read)
                    codec.signalEndOfInputStream()
                    audioEosSent = true
                }
                eos = drain(codec, audioInfo, Track.Audio) || eos
            }
        } catch (failure: Exception) {
            stopRequested = true
        } finally {
            audioLatch.countDown()
        }
    }

    private fun drain(codec: MediaCodec?, info: MediaCodec.BufferInfo, track: Track): Boolean {
        if (codec == null) return true
        return when (val index = codec.dequeueOutputBuffer(info, OUTPUT_TIMEOUT_US)) {
            MediaCodec.INFO_TRY_AGAIN_LATER -> false
            MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                addTrack(track, codec.outputFormat)
                false
            }
            else -> {
                if (index < 0) {
                    false
                } else {
                    val payload = codec.getOutputBuffer(index)?.expandTo(info.size)
                    write(payload, track, info)
                    codec.releaseOutputBuffer(index, false)
                    info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                }
            }
        }
    }

    private fun prepare() {
        muxer = MediaMuxer(descriptor.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

        val video = MediaCodec.createEncoderByType(MIME_VIDEO)
        video.configure(videoFormat(), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        videoCodec = video

        val surface = video.createInputSurface()
        video.start()
        display = projection.createVirtualDisplay(
            "kadr",
            frame.width,
            frame.height,
            frame.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            surface,
            null,
            null,
        )

        if (options.audio == AudioSource.Mic) {
            audioRecord = buildAudioRecord()
            audioEnabled = audioRecord != null
        }
        if (audioEnabled) {
            audioCodec = MediaCodec.createEncoderByType(MIME_AUDIO).apply {
                configure(audioFormat(), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                start()
            }
            audioRecord?.startRecording()
            Thread(::audioLoop, "kadr-audio").apply { isDaemon = true; start() }
        }

        deadline = if (options.maxDurationMs > 0L) {
            SystemClock.elapsedRealtime() + options.maxDurationMs
        } else {
            0L
        }
        listener.onReady()
    }

    private fun videoFormat(): MediaFormat {
        val limits = EncoderLimits.of(MIME_VIDEO)
        return MediaFormat.createVideoFormat(MIME_VIDEO, frame.width, frame.height).apply {
            setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface,
            )
            setInteger(MediaFormat.KEY_BIT_RATE, limits.bitrate(frame, options.quality))
            setInteger(MediaFormat.KEY_FRAME_RATE, options.frameRate.fps)
            setInteger(MediaFormat.KEY_CAPTURE_RATE, options.frameRate.fps)
            setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 1_000_000L / options.frameRate.fps)
            setFloat(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_SECONDS)
            setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
        }
    }

    private fun audioFormat(): MediaFormat =
        MediaFormat.createAudioFormat(MIME_AUDIO, SAMPLE_RATE, CHANNEL_COUNT).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, AUDIO_BITRATE)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, AUDIO_BYTES)
        }

    /** null, если разрешения на микрофон нет — тогда пишем без звука. */
    private fun buildAudioRecord(): AudioRecord? {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return null
        }
        val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_IN, ENCODING)
        val size = maxOf(minBuffer, AUDIO_BYTES * 2)
        return AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE, ENCODING, CHANNEL_IN, size)
    }

    private fun queueAudio(codec: MediaCodec, bytes: ByteArray, read: Int) {
        val index = codec.dequeueInputBuffer(OUTPUT_TIMEOUT_US)
        if (index < 0) return
        val buffer = codec.getInputBuffer(index) ?: return
        buffer.clear()
        buffer.put(bytes, 0, read)
        codec.queueInputBuffer(index, 0, read, audioSamples * 1_000_000L / SAMPLE_RATE, 0)
        audioSamples += read / (2L * CHANNEL_COUNT)
    }

    private fun addTrack(track: Track, format: MediaFormat) {
        synchronized(lock) {
            val muxer = muxer ?: return
            val index = muxer.addTrack(format)
            when (track) {
                Track.Video -> videoTrack = index
                Track.Audio -> audioTrack = index
            }
            tracksAdded += 1
            if (tracksAdded == tracksExpected && !muxerStarted) {
                muxer.start()
                muxerStarted = true
            }
        }
    }

    private fun write(payload: ByteBuffer?, track: Track, info: MediaCodec.BufferInfo) {
        if (payload == null || info.size <= 0) return
        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) return
        synchronized(lock) {
            if (!muxerStarted) return
            val index = if (track == Track.Video) videoTrack else audioTrack
            if (index < 0) return
            payload.position(info.offset)
            payload.limit(min(info.offset + info.size, payload.capacity()))
            muxer?.writeSampleData(index, payload, info)
        }
    }

    private fun release() {
        runCatching { display?.release() }
        display = null
        runCatching { videoCodec?.stop() }
        runCatching { videoCodec?.release() }
        videoCodec = null
        runCatching { audioCodec?.stop() }
        runCatching { audioCodec?.release() }
        audioCodec = null
        runCatching { audioRecord?.stop() }
        runCatching { audioRecord?.release() }
        audioRecord = null
        synchronized(lock) {
            if (muxerStarted) runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
            muxer = null
            muxerStarted = false
        }
        runCatching { descriptor.close() }
        runCatching { projection.stop() }
    }

    /** Некоторые кодеки отдают буфер меньше, чем заявлено в BufferInfo. */
    private fun ByteBuffer.expandTo(size: Int): ByteBuffer {
        if (capacity() >= size) return this
        val copy = ByteBuffer.allocateDirect(size)
        position(0)
        limit(size)
        copy.put(this)
        copy.flip()
        return copy
    }

    private companion object {
        const val MIME_VIDEO = "video/avc"
        const val MIME_AUDIO = "audio/mp4a-latm"
        const val OUTPUT_TIMEOUT_US = 10_000L
        const val EOS_TIMEOUT_MS = 4_000L
        const val I_FRAME_SECONDS = 2f
        const val SAMPLE_RATE = 44_100
        const val CHANNEL_COUNT = 1
        const val CHANNEL_IN = AudioFormat.CHANNEL_IN_MONO
        const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
        const val AUDIO_BYTES = 8_192
        const val AUDIO_BITRATE = 128_000
    }
}
