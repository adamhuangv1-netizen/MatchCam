package com.adamhuang.splitshot

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.*
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.material.floatingactionbutton.FloatingActionButton
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private var videoCaptureA: VideoCapture<Recorder>? = null
    private var videoCaptureB: VideoCapture<Recorder>? = null
    private var dualRecorderSupported = false
    private var useRecorderA = true

    private var activeRecording: Recording? = null
    private var outgoingRecording: Recording? = null
    private var recordingGeneration = 0
    private var isTransitioning = false

    private lateinit var cameraExecutor: ExecutorService

    private val mainHandler = Handler(Looper.getMainLooper())
    private var cameraControl: CameraControl? = null
    private var cameraInfo: CameraInfo? = null
    private var hasFlashUnit: Boolean = false
    private var isRecording: Boolean = false
    private var stealthEnabled: Boolean = false

    private lateinit var prefs: SharedPreferences
    private lateinit var viewFinder: PreviewView

    private var currentZoomRatio: Float? = null
    private var cachedStorageBytes: Long = 0L
    private var cachedMaxStorageBytes: Long = 0L
    private var cachedFileSizeLimit: Long = 0L
    private var cachedFileSizeThreshold: Long = 0L

    // Timer variables
    private var secondsElapsed: Long = 0
    private var stealthTimerSeconds: Int = 0

    private val timerRunnable = object : Runnable {
        override fun run() {
            if (!isRecording) return
            secondsElapsed++
            updateTimerUI()

            if (!stealthEnabled) {
                stealthTimerSeconds++
                val autoStealthEnabled = findViewById<SwitchCompat>(R.id.switch_auto_stealth)?.isChecked ?: true
                if (autoStealthEnabled && stealthTimerSeconds >= 10) {
                    stealthEnabled = true
                    enablePowerSaveMode(true)
                }
            }
            mainHandler.postDelayed(this, 1000)
        }
    }

    private val requiredPermissions = arrayOf(
        Manifest.permission.CAMERA,
        Manifest.permission.RECORD_AUDIO
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR

        viewFinder = findViewById(R.id.viewFinder)

        prefs = getSharedPreferences("MatchCamSettings", Context.MODE_PRIVATE)
        cameraExecutor = Executors.newSingleThreadExecutor()

        setupQualitySpinner()
        setupSegmentSizeSpinner()
        setupMaxStorageToggle()
        loadSavedSettings()

        // Display app version
        try {
            val pInfo = packageManager.getPackageInfo(packageName, 0)
            val version = pInfo.versionName
            findViewById<TextView>(R.id.about_version)?.text = "Version $version"
        } catch (e: PackageManager.NameNotFoundException) {
            e.printStackTrace()
        }

        findViewById<Button>(R.id.video_capture_button)?.setOnClickListener { captureVideo() }

        val fabSettings = findViewById<FloatingActionButton>(R.id.fab_settings)
        val settingsLayout = findViewById<LinearLayout>(R.id.settings_layout)
        val zoomLayout = findViewById<LinearLayout>(R.id.zoom_layout)
        val interceptor = findViewById<View>(R.id.panel_click_interceptor)

        val closePanels = {
            settingsLayout?.visibility = View.GONE
            zoomLayout?.visibility = View.GONE
            interceptor?.visibility = View.GONE
            saveSettings()
        }

        interceptor?.setOnClickListener { closePanels() }

        fabSettings?.setOnClickListener {
            zoomLayout?.visibility = View.GONE
            if (settingsLayout?.visibility == View.VISIBLE) {
                closePanels()
            } else {
                settingsLayout?.visibility = View.VISIBLE
                interceptor?.visibility = View.VISIBLE
            }
        }

        findViewById<View?>(R.id.stealth_button)?.setOnClickListener {
            stealthEnabled = true
            enablePowerSaveMode(true)
            closePanels()
        }

        findViewById<View>(R.id.blackOverlay)?.setOnClickListener {
            if (stealthEnabled) {
                stealthEnabled = false
                stealthTimerSeconds = 0
                enablePowerSaveMode(false)
            }
        }

        findViewById<FloatingActionButton>(R.id.fab_zoom)?.setOnClickListener {
            settingsLayout?.visibility = View.GONE
            if (zoomLayout?.visibility == View.VISIBLE) {
                closePanels()
            } else {
                zoomLayout?.visibility = View.VISIBLE
                interceptor?.visibility = View.VISIBLE
            }
        }

        if (allPermissionsGranted()) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(this, requiredPermissions, 10)
        }
    }

    private fun saveSettings() {
        val editor = prefs.edit()
        editor.putInt("segment_size_pos", findViewById<Spinner>(R.id.spinner_segment_size).selectedItemPosition)
        editor.putBoolean("auto_stealth", findViewById<SwitchCompat>(R.id.switch_auto_stealth).isChecked)
        editor.putBoolean("flash_enabled", findViewById<SwitchCompat>(R.id.switch_flash_enabled).isChecked)
        editor.putString("flash_interval", findViewById<EditText>(R.id.edit_flash_interval).text.toString())
        editor.putInt("quality_pos", findViewById<Spinner>(R.id.spinner_quality).selectedItemPosition)
        editor.putBoolean("max_storage_enabled", findViewById<SwitchCompat>(R.id.switch_max_storage).isChecked)
        editor.putString("max_storage_gb", findViewById<EditText>(R.id.edit_max_storage).text.toString())
        editor.apply()
    }

    private fun loadSavedSettings() {
        findViewById<Spinner>(R.id.spinner_segment_size).setSelection(prefs.getInt("segment_size_pos", 1))
        findViewById<SwitchCompat>(R.id.switch_auto_stealth).isChecked = prefs.getBoolean("auto_stealth", true)
        findViewById<SwitchCompat>(R.id.switch_flash_enabled).isChecked = prefs.getBoolean("flash_enabled", true)
        findViewById<EditText>(R.id.edit_flash_interval).setText(prefs.getString("flash_interval", "10"))
        findViewById<Spinner>(R.id.spinner_quality).setSelection(prefs.getInt("quality_pos", 1))
        val maxStorageEnabled = prefs.getBoolean("max_storage_enabled", false)
        findViewById<SwitchCompat>(R.id.switch_max_storage).isChecked = maxStorageEnabled
        findViewById<EditText>(R.id.edit_max_storage).visibility = if (maxStorageEnabled) View.VISIBLE else View.GONE
        findViewById<EditText>(R.id.edit_max_storage).setText(prefs.getString("max_storage_gb", ""))
    }

    private fun updateZoomLabel(ratio: Float) {
        val label = findViewById<TextView>(R.id.zoom_label) ?: return
        label.text = "${String.format("%.1f", ratio)}x"
        label.visibility = View.VISIBLE
    }

    private fun updateZoomPanel(info: CameraInfo) {
        val container = findViewById<LinearLayout>(R.id.zoom_options_container) ?: return
        container.removeAllViews()
        container.orientation = LinearLayout.HORIZONTAL

        val zoomState = info.zoomState.value ?: return
        val minRatio = zoomState.minZoomRatio
        val maxRatio = zoomState.maxZoomRatio
        prefs.edit().putFloat("max_zoom_ratio", maxRatio).apply()
        val commonZooms = listOf(0.5f, 0.7f, 1.0f, 2.0f)

        val items = commonZooms.filter { it in minRatio..maxRatio }.toMutableList()
        if (minRatio !in items) items.add(0, minRatio)
        if (maxRatio !in items && maxRatio > minRatio) items.add(maxRatio)
        items.sort()

        val snapThreshold = (maxRatio - minRatio) * 0.04f

        fun snapToPreset(ratio: Float): Float {
            val nearest = items.minByOrNull { Math.abs(it - ratio) } ?: ratio
            return if (Math.abs(nearest - ratio) <= snapThreshold) nearest else ratio
        }

        val curRatio = currentZoomRatio?.coerceIn(minRatio, maxRatio) ?: minRatio
        val density = resources.displayMetrics.density
        val sliderLengthPx = (240 * density).toInt()
        val sliderThicknessPx = (36 * density).toInt()
        val seekSteps = 200

        val sliderLabel = TextView(this).apply {
            textSize = 13f
            setTextColor(Color.WHITE)
            gravity = android.view.Gravity.CENTER
            text = "${String.format("%.1f", curRatio)}x"
            setPadding(0, (4 * density).toInt(), 0, (4 * density).toInt())
        }

        // Rotated -90° so dragging up increases zoom (min at bottom, max at top)
        val seekBar = SeekBar(this).apply {
            max = seekSteps
            progress = ((curRatio - minRatio) / (maxRatio - minRatio) * seekSteps).toInt()
            rotation = -90f
            layoutParams = FrameLayout.LayoutParams(sliderLengthPx, sliderThicknessPx).apply {
                gravity = android.view.Gravity.CENTER
            }
        }

        // Wrapper swaps layout dimensions so the parent sees the visual (rotated) size
        val seekWrapper = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(sliderThicknessPx, sliderLengthPx)
        }
        seekWrapper.addView(seekBar)

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                val raw = minRatio + (maxRatio - minRatio) * progress / seekSteps
                val display = snapToPreset(raw)
                sliderLabel.text = "${String.format("%.1f", display)}x"
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {
                val raw = minRatio + (maxRatio - minRatio) * sb.progress / seekSteps
                val final = snapToPreset(raw)
                currentZoomRatio = final
                cameraControl?.setZoomRatio(final)
                updateZoomLabel(final)
                sliderLabel.text = "${String.format("%.1f", final)}x"
                sb.progress = ((final - minRatio) / (maxRatio - minRatio) * seekSteps).toInt()
            }
        })

        val sliderColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER_HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins((8 * density).toInt(), 0, (8 * density).toInt(), 0) }
        }
        sliderColumn.addView(sliderLabel)
        sliderColumn.addView(seekWrapper)
        container.addView(sliderColumn)

        val buttonsColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        items.reversed().forEach { ratio ->
            val btn = Button(this, null, android.R.attr.borderlessButtonStyle).apply {
                text = "${String.format("%.1f", ratio)}x"
                setTextColor(Color.WHITE)
                setOnClickListener {
                    currentZoomRatio = ratio
                    cameraControl?.setZoomRatio(ratio)
                    updateZoomLabel(ratio)
                    seekBar.progress = ((ratio - minRatio) / (maxRatio - minRatio) * seekSteps).toInt()
                    sliderLabel.text = "${String.format("%.1f", ratio)}x"
                    findViewById<View>(R.id.zoom_layout)?.visibility = View.GONE
                    findViewById<View>(R.id.panel_click_interceptor)?.visibility = View.GONE
                }
            }
            buttonsColumn.addView(btn)
        }
        container.addView(buttonsColumn)
    }

    private fun setupQualitySpinner() {
        val spinner = findViewById<Spinner>(R.id.spinner_quality) ?: return
        val qualities = listOf("UHD / 2160p", "FHD / 1080p", "HD / 720p", "SD / 480p")
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, qualities)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinner.adapter = adapter
        spinner.setSelection(1)

        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            private var lastPosition = spinner.selectedItemPosition
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (position == lastPosition) return
                lastPosition = position
                if (videoCaptureA != null && allPermissionsGranted() && !isRecording) startCamera()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun setupSegmentSizeSpinner() {
        val spinner = findViewById<Spinner>(R.id.spinner_segment_size) ?: return
        val sizes = listOf("1 GB", "5 GB", "10 GB", "20 GB", "Unlimited")
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, sizes)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinner.adapter = adapter
        spinner.setSelection(1)
    }

    private fun setupMaxStorageToggle() {
        val switch = findViewById<SwitchCompat>(R.id.switch_max_storage) ?: return
        val editText = findViewById<EditText>(R.id.edit_max_storage) ?: return
        switch.setOnCheckedChangeListener { _, isChecked ->
            editText.visibility = if (isChecked) View.VISIBLE else View.GONE
        }
    }

    private fun getSelectedQuality(): Quality {
        val spinner = findViewById<Spinner>(R.id.spinner_quality)
        val selected = spinner?.selectedItem?.toString() ?: ""
        return when {
            selected.contains("UHD") -> Quality.UHD
            selected.contains("FHD") -> Quality.FHD
            selected.contains("HD") -> Quality.HD
            else -> Quality.SD
        }
    }

    private fun getSelectedFileSizeLimit(): Long {
        val spinner = findViewById<Spinner>(R.id.spinner_segment_size)
        val selected = spinner?.selectedItem?.toString() ?: "5 GB"
        val gbValue = when (selected) {
            "1 GB" -> 1L
            "5 GB" -> 5L
            "10 GB" -> 10L
            "20 GB" -> 20L
            else -> 0L
        }
        return if (gbValue == 0L) 0L else gbValue * 1024 * 1024 * 1024
    }

    private fun getMaxStorageLimitBytes(): Long {
        val enabled = findViewById<SwitchCompat>(R.id.switch_max_storage)?.isChecked ?: false
        if (!enabled) return 0L
        val text = findViewById<EditText>(R.id.edit_max_storage)?.text?.toString() ?: ""
        val gbValue = text.toLongOrNull() ?: return 0L
        return if (gbValue <= 0) 0L else gbValue * 1024 * 1024 * 1024
    }

    private fun stopRecording() {
        isRecording = false
        activeRecording?.stop()
        outgoingRecording?.stop()
        outgoingRecording = null
    }

    private fun stopRecordingForStorageLimit() {
        Log.d("MatchCam", "Max storage limit reached, stopping recording")
        stopRecording()
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
        Toast.makeText(this, "Storage limit reached \u2014 recording stopped", Toast.LENGTH_LONG).show()
    }

    private fun allPermissionsGranted() = requiredPermissions.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onRequestPermissionsResult(rc: Int, p: Array<String>, gr: IntArray) {
        super.onRequestPermissionsResult(rc, p, gr)
        if (allPermissionsGranted()) startCamera() else finish()
    }

    private fun buildRecorder(): VideoCapture<Recorder> {
        val quality = getSelectedQuality()
        val recorder = Recorder.Builder()
            .setQualitySelector(QualitySelector.from(quality, FallbackStrategy.lowerQualityOrHigherThan(Quality.SD)))
            .build()
        return VideoCapture.withOutput(recorder)
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            try {
                val cameraProvider = cameraProviderFuture.get()
                val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

                videoCaptureA = buildRecorder()
                videoCaptureB = buildRecorder()

                val preview = Preview.Builder().build().also {
                    it.surfaceProvider = viewFinder.surfaceProvider
                }

                cameraProvider.unbindAll()

                // Try binding both recorders for gapless recording
                val camera = try {
                    val cam = cameraProvider.bindToLifecycle(
                        this, cameraSelector, preview, videoCaptureA, videoCaptureB
                    )
                    dualRecorderSupported = true
                    Log.d("MatchCam", "Dual recorder mode enabled")
                    cam
                } catch (e: Exception) {
                    // Device doesn't support 3 use cases; fall back to single recorder
                    Log.w("MatchCam", "Dual recorder not supported, falling back to single mode", e)
                    cameraProvider.unbindAll()
                    videoCaptureB = null
                    dualRecorderSupported = false
                    cameraProvider.bindToLifecycle(this, cameraSelector, preview, videoCaptureA)
                }

                cameraControl = camera.cameraControl
                cameraInfo = camera.cameraInfo
                cameraInfo?.let { updateZoomPanel(it) }
                hasFlashUnit = camera.cameraInfo.hasFlashUnit()
                val zoomState = camera.cameraInfo.zoomState.value
                val minZoom = zoomState?.minZoomRatio ?: 1.0f
                val maxZoom = zoomState?.maxZoomRatio ?: minZoom
                prefs.edit().putFloat("max_zoom_ratio", maxZoom).apply()
                val targetZoom = currentZoomRatio?.coerceIn(minZoom, maxZoom) ?: maxOf(0.5f, minZoom)
                cameraControl?.setZoomRatio(targetZoom)
                currentZoomRatio = targetZoom
                updateZoomLabel(targetZoom)
            } catch (e: Exception) {
                Log.e("MatchCam", "Camera startup failed", e)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun getActiveVideoCapture(): VideoCapture<Recorder>? {
        if (!dualRecorderSupported) return videoCaptureA
        return if (useRecorderA) videoCaptureA else videoCaptureB
    }

    @SuppressLint("MissingPermission")
    private fun createPendingRecording(capture: VideoCapture<Recorder>, withSizeLimit: Boolean): PendingRecording {
        val name = "MatchCam_${System.currentTimeMillis()}"
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "DCIM/MatchCam")
        }

        val maxFileSizeBytes = getSelectedFileSizeLimit()
        val optsBuilder = MediaStoreOutputOptions.Builder(contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
            .setContentValues(contentValues)

        // In dual mode, we self-manage file size via Status events (no OS limit).
        // In single mode, set the hard limit so we get ERROR_FILE_SIZE_LIMIT_REACHED.
        if (withSizeLimit && maxFileSizeBytes > 0) {
            optsBuilder.setFileSizeLimit(maxFileSizeBytes)
        }

        val opts = optsBuilder.build()
        return capture.output.prepareRecording(this, opts).withAudioEnabled()
    }

    @SuppressLint("MissingPermission")
    private fun captureVideo() {
        if (activeRecording != null) {
            stopRecording()
            return
        }

        findViewById<View>(R.id.settings_layout).visibility = View.GONE
        findViewById<View>(R.id.zoom_layout).visibility = View.GONE
        findViewById<View>(R.id.panel_click_interceptor).visibility = View.GONE
        saveSettings()

        cachedStorageBytes = 0L
        cachedMaxStorageBytes = getMaxStorageLimitBytes()
        cachedFileSizeLimit = getSelectedFileSizeLimit()
        cachedFileSizeThreshold = if (cachedFileSizeLimit > 0) (cachedFileSizeLimit * 0.9).toLong() else 0L

        useRecorderA = true
        recordingGeneration = 0
        isTransitioning = false
        outgoingRecording = null

        val capture = getActiveVideoCapture() ?: return
        val useSizeLimit = !dualRecorderSupported
        val pending = createPendingRecording(capture, withSizeLimit = useSizeLimit)
        val gen = recordingGeneration

        activeRecording = pending.start(ContextCompat.getMainExecutor(this)) { event ->
            onRecordingEvent(event, gen)
        }
    }

    @SuppressLint("MissingPermission")
    private fun transitionToNextRecorder() {
        if (isTransitioning) return
        isTransitioning = true

        val oldRecording = activeRecording
        useRecorderA = !useRecorderA
        recordingGeneration++
        val newGen = recordingGeneration

        val capture = getActiveVideoCapture() ?: run {
            isTransitioning = false
            return
        }

        Log.d("MatchCam", "Transitioning to recorder ${if (useRecorderA) "A" else "B"}, gen=$newGen")

        val pending = createPendingRecording(capture, withSizeLimit = false)
        activeRecording = pending.start(ContextCompat.getMainExecutor(this)) { event ->
            onRecordingEvent(event, newGen)
        }

        // The old recording becomes the outgoing recording.
        // We'll stop it once the new recording fires its Start event.
        outgoingRecording = oldRecording
    }

    private fun onRecordingEvent(event: VideoRecordEvent, generation: Int) {
        val btn = findViewById<Button>(R.id.video_capture_button)
        when (event) {
            is VideoRecordEvent.Start -> {
                if (generation == 0) {
                    // First segment: set up UI
                    isRecording = true
                    requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LOCKED
                    stealthTimerSeconds = 0
                    btn?.setText(R.string.stop_recording)
                    btn?.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.RED)
                    startTimer()
                    startFlashHeartbeat()
                } else if (isTransitioning) {
                    // New segment started during transition — stop the old one
                    Log.d("MatchCam", "New segment started (gen=$generation), stopping old recording")
                    outgoingRecording?.stop()
                    outgoingRecording = null
                    isTransitioning = false
                }
            }
            is VideoRecordEvent.Status -> {
                if (generation == recordingGeneration && isRecording) {
                    val bytesRecorded = event.recordingStats.numBytesRecorded

                    if (cachedMaxStorageBytes > 0 && (cachedStorageBytes + bytesRecorded) >= cachedMaxStorageBytes) {
                        stopRecordingForStorageLimit()
                        return
                    }

                    if (dualRecorderSupported && !isTransitioning && cachedFileSizeThreshold > 0
                        && bytesRecorded >= cachedFileSizeThreshold
                    ) {
                        Log.d("MatchCam", "Reached 90% of file size limit ($bytesRecorded / $cachedFileSizeLimit bytes), transitioning")
                        transitionToNextRecorder()
                    }
                }
            }
            is VideoRecordEvent.Finalize -> {
                val finalizedBytes = event.recordingStats.numBytesRecorded
                if (finalizedBytes > 0
                    && (event.error == VideoRecordEvent.Finalize.ERROR_NONE
                        || event.error == VideoRecordEvent.Finalize.ERROR_FILE_SIZE_LIMIT_REACHED)
                ) {
                    cachedStorageBytes += finalizedBytes
                }

                if (!dualRecorderSupported
                    && event.error == VideoRecordEvent.Finalize.ERROR_FILE_SIZE_LIMIT_REACHED
                    && isRecording
                ) {
                    if (cachedMaxStorageBytes > 0 && cachedStorageBytes >= cachedMaxStorageBytes) {
                        stopRecordingForStorageLimit()
                        return
                    }

                    // Single-recorder fallback: file limit reached, start new segment on same capture
                    Log.d("MatchCam", "File limit reached (single mode). Starting new segment...")
                    val capture = getActiveVideoCapture() ?: return
                    recordingGeneration++
                    val newGen = recordingGeneration
                    val pending = createPendingRecording(capture, withSizeLimit = true)
                    activeRecording = pending.start(ContextCompat.getMainExecutor(this)) { ev ->
                        onRecordingEvent(ev, newGen)
                    }
                    return
                }

                if (generation == recordingGeneration
                    && (!isRecording || event.error == VideoRecordEvent.Finalize.ERROR_INSUFFICIENT_STORAGE)
                ) {
                    isRecording = false
                    activeRecording = null
                    outgoingRecording = null
                    isTransitioning = false
                    requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
                    stopTimer()
                    stopFlashHeartbeat()
                    btn?.setText(R.string.start_recording)
                    btn?.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#4CAF50"))

                    if (event.error == VideoRecordEvent.Finalize.ERROR_INSUFFICIENT_STORAGE) {
                        Log.e("MatchCam", "Recording stopped: insufficient storage")
                        Toast.makeText(this, "Recording stopped \u2014 device storage is full", Toast.LENGTH_LONG).show()
                    } else if (event.error != VideoRecordEvent.Finalize.ERROR_NONE) {
                        Log.e("MatchCam", "Recording finalized with error: ${event.error}")
                    } else {
                        Toast.makeText(this, "Video saved to Photos!", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    // Old segment finalized (during transition or after user stop)
                    Log.d("MatchCam", "Old segment finalized (gen=$generation, current=${recordingGeneration})")
                    if (event.error != VideoRecordEvent.Finalize.ERROR_NONE
                        && event.error != VideoRecordEvent.Finalize.ERROR_FILE_SIZE_LIMIT_REACHED
                    ) {
                        Log.e("MatchCam", "Old segment error: ${event.error}")
                    }
                }
            }
            is VideoRecordEvent.Pause -> {}
            is VideoRecordEvent.Resume -> {}
        }
    }

    private fun startTimer() {
        mainHandler.removeCallbacks(timerRunnable)
        findViewById<TextView>(R.id.recording_timer).visibility = View.VISIBLE
        mainHandler.postDelayed(timerRunnable, 1000)
    }

    private fun stopTimer() {
        mainHandler.removeCallbacks(timerRunnable)
        findViewById<TextView>(R.id.recording_timer).visibility = View.GONE
        secondsElapsed = 0
    }

    private fun updateTimerUI() {
        val hours = secondsElapsed / 3600
        val minutes = (secondsElapsed % 3600) / 60
        val seconds = secondsElapsed % 60
        findViewById<TextView>(R.id.recording_timer).text = String.format("%02d:%02d:%02d", hours, minutes, seconds)
    }

    private fun enablePowerSaveMode(enable: Boolean) {
        val overlay = findViewById<View>(R.id.blackOverlay)
        val params = window.attributes
        if (enable) {
            overlay?.visibility = View.VISIBLE
            params.screenBrightness = 0.01f
        } else {
            overlay?.visibility = View.GONE
            params.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        }
        window.attributes = params
    }

    private val flashRunnable = object : Runnable {
        override fun run() {
            if (!isRecording) return
            val flashEnabled = findViewById<SwitchCompat>(R.id.switch_flash_enabled)?.isChecked ?: true
            if (hasFlashUnit && flashEnabled) {
                cameraControl?.enableTorch(true)
                mainHandler.postDelayed({ cameraControl?.enableTorch(false) }, 100)
            }
            val flashSec = findViewById<EditText>(R.id.edit_flash_interval).text.toString().toLongOrNull() ?: 10
            mainHandler.postDelayed(this, flashSec * 1000)
        }
    }

    private fun startFlashHeartbeat() {
        mainHandler.removeCallbacks(flashRunnable)
        mainHandler.post(flashRunnable)
    }

    private fun stopFlashHeartbeat() {
        mainHandler.removeCallbacks(flashRunnable)
        cameraControl?.enableTorch(false)
    }

    override fun onPause() {
        super.onPause()
        saveSettings()
    }

    override fun onDestroy() {
        super.onDestroy()
        saveSettings()
        cameraExecutor.shutdown()
        stopFlashHeartbeat()
    }
}
