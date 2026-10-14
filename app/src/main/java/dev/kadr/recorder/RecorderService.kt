package dev.kadr.recorder

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Запись живёт в foreground-сервисе: сворачивание приложения не должно её рвать.
 * Порядок «сначала startForeground, потом getMediaProjection» обязателен на Android 14.
 */
class RecorderService : Service() {

    private var projection: MediaProjection? = null

    @Volatile
    private var session: RecordingSession? = null
    private var target: Library.Target? = null
    private var startedAt = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            shutdown()
            stopSelf(startId)
            return START_NOT_STICKY
        }
        if (session != null) return START_NOT_STICKY

        createChannel()
        promoteToForeground()

        val code = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        val data = intent?.captureData()
        if (data == null) {
            fail("Нет разрешения на запись экрана")
            return START_NOT_STICKY
        }

        val options = RecordOptions.fromJson(intent.getStringExtra(EXTRA_OPTIONS))
        val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val capture = manager.getMediaProjection(code, data)
        capture.registerCallback(projectionCallback, null)
        projection = capture

        val created = try {
            Library.create(this, Library.newDisplayName())
        } catch (error: IOException) {
            fail(error.message ?: "Не удалось создать файл")
            return START_NOT_STICKY
        } catch (error: SecurityException) {
            fail("Нет разрешения на запись во внешнее хранилище")
            return START_NOT_STICKY
        }

        val limits = EncoderLimits.of(MIME_VIDEO)
        target = created
        startedAt = System.currentTimeMillis()
        session = RecordingSession(
            context = applicationContext,
            projection = capture,
            frame = limits.shrink(Frame.of(displayMetrics(), options.quality)),
            descriptor = created.descriptor,
            options = options,
            listener = sessionListener,
        ).also { it.start() }
        if (options.audio == AudioSource.Mic && !granted(Manifest.permission.RECORD_AUDIO)) {
            RecorderState.publish(message = "Микрофон недоступен — запись без звука")
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        shutdown()
        super.onDestroy()
    }

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            session?.stop()
            RecorderState.publish(message = "Запись остановлена системой")
        }
    }

    private val sessionListener = object : RecordingSession.Listener {
        override fun onReady() {
            RecorderState.publish(recording = true)
            notificationManager().notify(NOTIFICATION_ID, buildNotification())
        }

        override fun onFinished(error: String?) {
            val saved = target
            when {
                error == null && saved != null -> {
                    Library.finish(applicationContext, saved)
                    RecorderState.publish(recording = false, message = SAVED_MESSAGE)
                }
                saved != null -> {
                    Library.discard(applicationContext, saved)
                    RecorderState.publish(recording = false, message = error)
                }
                else -> RecorderState.publish(recording = false, message = error)
            }
            session = null
            target = null
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun shutdown() {
        session?.let {
            it.stop()
            it.awaitStop(SESSION_JOIN_MS)
        }
        session = null
        releaseProjection()
    }

    private fun fail(message: String) {
        releaseProjection()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        RecorderState.publish(recording = false, message = message)
    }

    private fun releaseProjection() {
        runCatching { projection?.unregisterCallback(projectionCallback) }
        runCatching { projection?.stop() }
        projection = null
    }

    private fun promoteToForeground() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun granted(permission: String) =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun displayMetrics(): DisplayMetrics {
        val metrics = DisplayMetrics()
        val manager = getSystemService(WINDOW_SERVICE) as WindowManager
        @Suppress("DEPRECATION")
        manager.defaultDisplay.getRealMetrics(metrics)
        return metrics
    }

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.channel_recording),
            NotificationManager.IMPORTANCE_LOW,
        ).apply { setShowBadge(false) }
        notificationManager().createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val stop = PendingIntent.getService(
            this,
            0,
            Intent(this, RecorderService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val open = PendingIntent.getActivity(
            this,
            1,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_kadr)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.notification_recording))
            .setContentIntent(open)
            .setOngoing(true)
            .setWhen(startedAt)
            .setShowWhen(true)
            .setUsesChronometer(true)
            .setColor(getColor(R.color.accent))
            .addAction(R.drawable.ic_stop, getString(R.string.notification_stop), stop)
            .build()
    }

    private fun notificationManager() = getSystemService(NOTIFICATION_SERVICE) as NotificationManager

    companion object {
        private const val CHANNEL_ID = "kadr-recording"
        private const val NOTIFICATION_ID = 42
        private const val MIME_VIDEO = "video/avc"
        private const val SESSION_JOIN_MS = 6_000L
        private const val SAVED_MESSAGE = "Сохранено в Movies/Kadr"

        const val ACTION_STOP = "dev.kadr.recorder.action.STOP"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val EXTRA_OPTIONS = "options"

        fun start(context: Context, resultCode: Int, data: Intent, options: RecordOptions) {
            val intent = Intent(context, RecorderService::class.java)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_RESULT_DATA, data)
                .putExtra(EXTRA_OPTIONS, options.toJson())
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, RecorderService::class.java).setAction(ACTION_STOP))
        }
    }
}

/** То, что система вернула из диалога разрешения на показ экрана. */
@Suppress("DEPRECATION")
private fun Intent.captureData(): Intent? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getParcelableExtra(RecorderService.EXTRA_RESULT_DATA, Intent::class.java)
    } else {
        getParcelableExtra(RecorderService.EXTRA_RESULT_DATA)
    }

/** Связь сервиса с интерфейсом: состояние и последнее сообщение. */
object RecorderState {
    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    @Volatile
    var recording = false
        private set

    @Volatile
    var message: String? = null
        private set

    fun subscribe(listener: () -> Unit) {
        listeners += listener
    }

    fun unsubscribe(listener: () -> Unit) {
        listeners -= listener
    }

    fun consumeMessage(): String? = message.also { message = null }

    fun publish(recording: Boolean = this.recording, message: String? = null) {
        main.post {
            this.recording = recording
            if (message != null) this.message = message
            listeners.forEach { it() }
        }
    }
}
