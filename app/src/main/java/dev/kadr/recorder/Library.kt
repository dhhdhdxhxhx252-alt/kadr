package dev.kadr.recorder

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class Clip(
    val id: Long,
    val uri: Uri,
    val name: String,
    val sizeBytes: Long,
    val durationMs: Long,
    val addedAt: Long,
)

/**
 * Записи живут в Movies/Kadr. На Android 10+ это MediaStore с RELATIVE_PATH,
 * на 8–9 — тот же каталог, но через прямой путь и разрешение на запись.
 */
object Library {
    const val FOLDER = "Kadr"
    private const val MIME = "video/mp4"
    private val NAME_FORMAT = SimpleDateFormat("yyyy-MM-dd-HHmmss", Locale.US)

    fun newDisplayName(): String = "Kadr-${NAME_FORMAT.format(Date())}.mp4"

    @Throws(IOException::class)
    fun create(context: Context, displayName: String): Target {
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Video.Media.MIME_TYPE, MIME)
        }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/$FOLDER")
            values.put(MediaStore.Video.Media.IS_PENDING, 1)
            val uri = context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw IOException("MediaStore не вернул uri")
            val descriptor = context.contentResolver.openFileDescriptor(uri, "w")
                ?: throw IOException("Не удалось открыть файл записи")
            Target(uri, descriptor, pending = true)
        } else {
            val directory = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
                FOLDER,
            )
            if (!directory.exists() && !directory.mkdirs()) {
                throw IOException("Не удалось создать каталог ${directory.absolutePath}")
            }
            val file = File(directory, displayName)
            if (!file.exists() && !file.createNewFile()) {
                throw IOException("Не удалось создать ${file.absolutePath}")
            }
            Target(
                uri = Uri.fromFile(file),
                descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_WRITE_ONLY),
                pending = false,
            )
        }
    }

    fun finish(context: Context, target: Target) {
        if (!target.pending) return
        val values = ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }
        runCatching { context.contentResolver.update(target.uri, values, null, null) }
    }

    /** Запись не состоялась — убираем пустой файл, чтобы не засорять галерею. */
    fun discard(context: Context, target: Target) {
        runCatching { target.descriptor.close() }
        runCatching { context.contentResolver.delete(target.uri, null, null) }
        if (!target.pending) {
            runCatching { (target.uri.path?.let { File(it) })?.takeIf { it.exists() }?.delete() }
        }
    }

    fun query(context: Context): List<Clip> {
        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.DATE_ADDED,
        )
        val selection = "${MediaStore.Video.Media.BUCKET_DISPLAY_NAME}=?"
        val args = arrayOf(FOLDER)
        val sort = "${MediaStore.Video.Media.DATE_ADDED} DESC"
        val result = ArrayList<Clip>()
        context.contentResolver.query(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            args,
            sort,
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
            val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
            val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
            val durationColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
            val addedColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idColumn)
                result += Clip(
                    id = id,
                    uri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id),
                    name = cursor.getString(nameColumn).orEmpty(),
                    sizeBytes = cursor.getLong(sizeColumn),
                    durationMs = cursor.getLong(durationColumn),
                    addedAt = cursor.getLong(addedColumn) * 1000L,
                )
            }
        }
        return result
    }

    fun delete(context: Context, clip: Clip): Boolean =
        runCatching { context.contentResolver.delete(clip.uri, null, null) > 0 }.getOrDefault(false)

    class Target(
        val uri: Uri,
        val descriptor: ParcelFileDescriptor,
        val pending: Boolean,
    )
}
