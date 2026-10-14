package dev.kadr.recorder

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.CountDownTimer
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import dev.kadr.recorder.databinding.ActivityMainBinding
import dev.kadr.recorder.databinding.ItemRecordingBinding
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var adapter: RecordingsAdapter
    private var options = RecordOptions.Default
    private var countdown: CountDownTimer? = null

    private val projectionManager: MediaProjectionManager
        get() = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val data = result.data
        if (result.resultCode == RESULT_OK && data != null) {
            startCountdownThenRecord(result.resultCode, data)
        } else {
            say(getString(R.string.denied_projection))
            render()
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        if (grants.values.all { it }) {
            requestProjection()
        } else {
            say(getString(R.string.denied_permissions))
            render()
        }
    }

    private val stateListener: () -> Unit = { render() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        options = OptionStore.load(this)
        buildChips()

        adapter = RecordingsAdapter(onShare = ::share, onDelete = ::confirmDelete)
        binding.recordings.layoutManager = LinearLayoutManager(this)
        binding.recordings.adapter = adapter
        binding.recordings.isNestedScrollingEnabled = false

        binding.recordButton.setOnClickListener { toggle() }
        binding.refresh.setOnClickListener { reload() }

        RecorderState.subscribe(stateListener)
        render()
    }

    override fun onResume() {
        super.onResume()
        reload()
        render()
    }

    override fun onDestroy() {
        countdown?.cancel()
        RecorderState.unsubscribe(stateListener)
        super.onDestroy()
    }

    private fun toggle() {
        if (RecorderState.recording) {
            RecorderService.stop(this)
        } else {
            val missing = requiredPermissions()
            if (missing.isEmpty()) requestProjection() else permissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun requiredPermissions(): List<String> {
        val list = mutableListOf<String>()
        if (options.audio == AudioSource.Mic && !granted(Manifest.permission.RECORD_AUDIO)) {
            list += Manifest.permission.RECORD_AUDIO
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !granted(Manifest.permission.POST_NOTIFICATIONS)) {
            list += Manifest.permission.POST_NOTIFICATIONS
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q && !granted(Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
            list += Manifest.permission.WRITE_EXTERNAL_STORAGE
        }
        return list
    }

    private fun granted(permission: String) =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun requestProjection() {
        projectionLauncher.launch(projectionManager.createScreenCaptureIntent())
    }

    private fun startCountdownThenRecord(code: Int, data: Intent) {
        countdown?.cancel()
        binding.countdown.visibility = View.VISIBLE
        binding.countdown.text = "3"
        countdown = object : CountDownTimer(3_000L, 1_000L) {
            override fun onTick(millisUntilFinished: Long) {
                binding.countdown.text = (millisUntilFinished / 1_000L + 1).toString()
            }

            override fun onFinish() {
                binding.countdown.visibility = View.GONE
                OptionStore.save(this@MainActivity, options)
                RecorderService.start(this@MainActivity, code, data, options)
                render()
            }
        }.start()
        render()
    }

    private fun buildChips() {
        chipRow(binding.qualityGroup, Quality.entries.map { it.label }, options.quality.ordinal) {
            options = options.copy(quality = Quality.entries[it])
        }
        chipRow(binding.frameGroup, FrameRate.entries.map { it.label }, options.frameRate.ordinal) {
            options = options.copy(frameRate = FrameRate.entries[it])
        }
        chipRow(binding.audioGroup, AudioSource.entries.map { it.label }, options.audio.ordinal) {
            options = options.copy(audio = AudioSource.entries[it])
        }
        chipRow(binding.limitGroup, TimeLimit.entries.map { it.label }, options.limit.ordinal) {
            options = options.copy(limit = TimeLimit.entries[it])
        }
    }

    private fun chipRow(group: ChipGroup, labels: List<String>, selected: Int, onPick: (Int) -> Unit) {
        group.isSingleLine = false
        group.removeAllViews()
        labels.forEachIndexed { index, label ->
            val chip = layoutInflater.inflate(R.layout.chip_option, group, false) as Chip
            chip.text = label
            chip.isCheckable = true
            chip.isChecked = index == selected
            chip.setOnClickListener {
                onPick(index)
                OptionStore.save(this, options)
                buildChips()
            }
            group.addView(chip)
        }
    }

    private fun reload() {
        val clips = runCatching { Library.query(this) }.getOrDefault(emptyList())
        adapter.submit(clips)
        binding.libraryEmpty.visibility = if (clips.isEmpty()) View.VISIBLE else View.GONE
        binding.libraryTitle.text = getString(R.string.library_title, clips.size)
    }

    private fun share(clip: Clip) {
        val intent = Intent(Intent.ACTION_SEND)
            .setType("video/mp4")
            .putExtra(Intent.EXTRA_STREAM, clip.uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(intent, getString(R.string.share)))
    }

    private fun confirmDelete(clip: Clip) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.delete_title)
            .setMessage(clip.name)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ ->
                if (Library.delete(this, clip)) reload() else say(getString(R.string.delete_failed))
            }
            .show()
    }

    private fun render() {
        val recording = RecorderState.recording
        binding.recordButton.setText(if (recording) R.string.stop else R.string.record)
        binding.recordButton.setIconResource(if (recording) R.drawable.ic_stop else R.drawable.ic_record)
        binding.statusPill.setText(if (recording) R.string.status_recording else R.string.status_ready)
        binding.statusPill.setBackgroundResource(if (recording) R.drawable.bg_pill_active else R.drawable.bg_pill)
        binding.hint.setText(if (recording) R.string.hint_recording else R.string.hint_ready)
        RecorderState.consumeMessage()?.let { say(it) }
    }

    private fun say(text: String) {
        Snackbar.make(binding.root, text, Snackbar.LENGTH_SHORT).show()
    }
}

private class RecordingsAdapter(
    private val onShare: (Clip) -> Unit,
    private val onDelete: (Clip) -> Unit,
) : RecyclerView.Adapter<RecordingsAdapter.Holder>() {

    private val items = mutableListOf<Clip>()

    fun submit(clips: List<Clip>) {
        items.clear()
        items += clips
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(ItemRecordingBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(items[position])

    override fun getItemCount(): Int = items.size

    inner class Holder(private val item: ItemRecordingBinding) : RecyclerView.ViewHolder(item.root) {
        fun bind(clip: Clip) {
            item.title.text = clip.name
            item.meta.text = meta(item.root.context, clip)
            item.share.setOnClickListener { onShare(clip) }
            item.delete.setOnClickListener { onDelete(clip) }
        }
    }
}

private fun meta(context: Context, clip: Clip): String {
    val size = android.text.format.Formatter.formatShortFileSize(context, clip.sizeBytes)
    val seconds = clip.durationMs / 1_000
    val duration = String.format(Locale.US, "%02d:%02d", seconds / 60, seconds % 60)
    return "$size · $duration"
}
