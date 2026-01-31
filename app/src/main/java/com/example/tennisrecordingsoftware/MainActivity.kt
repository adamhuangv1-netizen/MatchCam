package com.example.tennisrecordingsoftware

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
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

    private var videoCapture: VideoCapture<Recorder>? = null
    private var activeRecording: Recording? = null
    private var pendingRecording: PendingRecording? = null
    private lateinit var cameraExecutor: ExecutorService

    private val mainHandler = Handler(Looper.getMainLooper())
    private var cameraControl: CameraControl? = null
    private var cameraInfo: CameraInfo? = null
    private var hasFlashUnit: Boolean = false
    private var isRecording: Boolean = false
    private var stealthEnabled: Boolean = false

    private lateinit var prefs: SharedPreferences
    private lateinit var viewFinder: PreviewView

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

        viewFinder = findViewById(R.id.viewFinder)

        prefs = getSharedPreferences("MatchCamSettings", Context.MODE_PRIVATE)
        cameraExecutor = Executors.newSingleThreadExecutor()

        setupQualitySpinner()
        setupSegmentSizeSpinner()
        loadSavedSettings()

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
        editor.apply()
    }

    private fun loadSavedSettings() {
        findViewById<Spinner>(R.id.spinner_segment_size).setSelection(prefs.getInt("segment_size_pos", 1))
        findViewById<SwitchCompat>(R.id.switch_auto_stealth).isChecked = prefs.getBoolean("auto_stealth", true)
        findViewById<SwitchCompat>(R.id.switch_flash_enabled).isChecked = prefs.getBoolean("flash_enabled", true)
        findViewById<EditText>(R.id.edit_flash_interval).setText(prefs.getString("flash_interval", "10"))
        findViewById<Spinner>(R.id.spinner_quality).setSelection(prefs.getInt("quality_pos", 1))
    }

    private fun updateZoomPanel(info: CameraInfo) {
        val container = findViewById<LinearLayout>(R.id.zoom_options_container) ?: return
        container.removeAllViews()

        val zoomState = info.zoomState.value ?: return
        val minRatio = zoomState.minZoomRatio
        val maxRatio = zoomState.maxZoomRatio
        val commonZooms = listOf(0.5f, 0.6f, 1.0f, 2.0f, 3.0f, 5.0f)

        val items = commonZooms.filter { it in minRatio..maxRatio }.toMutableList()
        if (minRatio !in items) items.add(0, minRatio)
        if (maxRatio !in items && maxRatio > minRatio) items.add(maxRatio)

        items.forEach { ratio ->
            val btn = Button(this, null, android.R.attr.borderlessButtonStyle).apply {
                text = "${String.format("%.1f", ratio)}x"
                setTextColor(Color.WHITE)
                setOnClickListener {
                    cameraControl?.setZoomRatio(ratio)
                    findViewById<View>(R.id.zoom_layout)?.visibility = View.GONE
                    findViewById<View>(R.id.panel_click_interceptor)?.visibility = View.GONE
                }
            }
            container.addView(btn)
        }
    }

    private fun setupQualitySpinner() {
        val spinner = findViewById<Spinner>(R.id.spinner_quality) ?: return
        val qualities = listOf("UHD / 2160p", "FHD / 1080p", "HD / 720p", "SD / 480p")
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, qualities)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinner.adapter = adapter
        spinner.setSelection(1)

        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (allPermissionsGranted() && !isRecording) startCamera()
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

    private fun allPermissionsGranted() = requiredPermissions.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onRequestPermissionsResult(rc: Int, p: Array<String>, gr: IntArray) {
        super.onRequestPermissionsResult(rc, p, gr)
        if (allPermissionsGranted()) startCamera() else finish()
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            try {
                val cameraProvider = cameraProviderFuture.get()
                val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
                val quality = getSelectedQuality()
                val recorder = Recorder.Builder()
                    .setQualitySelector(QualitySelector.from(quality, FallbackStrategy.lowerQualityOrHigherThan(Quality.SD)))
                    .build()
                videoCapture = VideoCapture.withOutput(recorder)
                val preview = Preview.Builder().build().also {
                    it.surfaceProvider = viewFinder.surfaceProvider
                }
                cameraProvider.unbindAll()
                val camera = cameraProvider.bindToLifecycle(this, cameraSelector, preview, videoCapture)
                cameraControl = camera.cameraControl
                cameraInfo = camera.cameraInfo
                cameraInfo?.let { updateZoomPanel(it) }
                hasFlashUnit = camera.cameraInfo.hasFlashUnit()
            } catch (e: Exception) {
                Log.e("MatchCam", "Camera startup failed", e)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    @SuppressLint("MissingPermission")
    private fun captureVideo() {
        if (activeRecording != null) {
            isRecording = false
            activeRecording?.stop()
            return
        }

        findViewById<View>(R.id.settings_layout).visibility = View.GONE
        findViewById<View>(R.id.zoom_layout).visibility = View.GONE
        findViewById<View>(R.id.panel_click_interceptor).visibility = View.GONE
        saveSettings()

        pendingRecording = createPendingRecording()
        startNewRecording()
    }

    @SuppressLint("MissingPermission")
    private fun createPendingRecording(): PendingRecording {
        val capture = videoCapture ?: throw IllegalStateException("VideoCapture is not initialized")
        val name = "MatchCam_${System.currentTimeMillis()}"
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "DCIM/MatchCam")
        }

        val maxFileSizeBytes = getSelectedFileSizeLimit()
        val optsBuilder = MediaStoreOutputOptions.Builder(contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
            .setContentValues(contentValues)
        if (maxFileSizeBytes > 0) optsBuilder.setFileSizeLimit(maxFileSizeBytes)
        val opts = optsBuilder.build()

        return capture.output.prepareRecording(this, opts).withAudioEnabled()
    }

    private fun startNewRecording() {
        val pending = pendingRecording ?: return
        activeRecording = pending.start(ContextCompat.getMainExecutor(this), videoRecordEventListener)
        pendingRecording = createPendingRecording()
    }

    private val videoRecordEventListener = { event: VideoRecordEvent ->
        val btn = findViewById<Button>(R.id.video_capture_button)
        when (event) {
            is VideoRecordEvent.Start -> {
                isRecording = true
                stealthTimerSeconds = 0
                btn?.setText(R.string.stop_recording)
                btn?.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.RED)
                startTimer()
                startFlashHeartbeat()
            }
            is VideoRecordEvent.Finalize -> {
                if (event.error == VideoRecordEvent.Finalize.ERROR_FILE_SIZE_LIMIT_REACHED && isRecording) {
                    Log.d("MatchCam", "File limit reached. Starting new segment...")
                    startNewRecording()
                } else {
                    val hadError = isRecording && event.error != VideoRecordEvent.Finalize.ERROR_NONE
                    activeRecording = null
                    pendingRecording = null
                    isRecording = false
                    stopTimer()
                    stopFlashHeartbeat()

                    btn?.setText(R.string.start_recording)
                    btn?.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#4CAF50"))

                    if (hadError) {
                        Log.e("MatchCam", "Recording failed with error: ${event.error}")
                    } else {
                        Toast.makeText(this, "Video saved to Photos!", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            is VideoRecordEvent.Pause, is VideoRecordEvent.Resume, is VideoRecordEvent.Status -> {
                // No-op for now
            }
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
