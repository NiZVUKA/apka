package com.nizvuka.nizcamera

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.*
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {
    private lateinit var previewView: PreviewView
    private lateinit var status: TextView
    private lateinit var recordButton: TextView
    private lateinit var qualityLabel: TextView
    private lateinit var cameraExecutor: ExecutorService
    private var cameraProvider: ProcessCameraProvider? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private var lens = CameraSelector.LENS_FACING_BACK
    private var selectedQuality = Quality.FHD
    private var recordingNow = false

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        if (grants[Manifest.permission.CAMERA] == true) startCamera()
        else status.text = "CAMERA PERMISSION REQUIRED"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        cameraExecutor = Executors.newSingleThreadExecutor()
        buildInterface()
        if (hasCameraPermission()) startCamera() else permissionLauncher.launch(
            arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO).filter {
                ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
            }.toTypedArray()
        )
    }

    private fun buildInterface() {
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        previewView = PreviewView(this).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
        root.addView(previewView, FrameLayout.LayoutParams(-1, -1))

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(22), dp(12), dp(22), dp(12))
            setBackgroundColor(0x99090A0C.toInt())
        }
        val brand = TextView(this).apply { text = "NIZ  /  CAMERA"; setTextColor(Color.WHITE); textSize = 16f; letterSpacing = .14f }
        status = TextView(this).apply { text = "CAMERA STARTING"; setTextColor(0xFFE5B94B.toInt()); textSize = 10f; letterSpacing = .12f; gravity = Gravity.CENTER }
        top.addView(brand, LinearLayout.LayoutParams(0, dp(38), 1f))
        top.addView(status, LinearLayout.LayoutParams(-2, dp(38)))
        root.addView(top, FrameLayout.LayoutParams(-1, dp(62), Gravity.TOP))

        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(18), dp(10), dp(18), dp(14))
            setBackgroundColor(0xCC090A0C.toInt())
        }
        val info = LinearLayout(this).apply { gravity = Gravity.CENTER; orientation = LinearLayout.HORIZONTAL }
        qualityLabel = TextView(this).apply { text = "FHD  ·  30 FPS*"; setTextColor(Color.WHITE); textSize = 12f; gravity = Gravity.CENTER; setPadding(dp(12), dp(8), dp(12), dp(8)) }
        qualityLabel.setOnClickListener { showQualityMenu() }
        val switch = TextView(this).apply { text = "  ↺  FLIP  "; setTextColor(Color.WHITE); textSize = 12f; gravity = Gravity.CENTER; setPadding(dp(12), dp(8), dp(12), dp(8)) }
        switch.setOnClickListener { if (!recordingNow) { lens = if (lens == CameraSelector.LENS_FACING_BACK) CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK; startCamera() } }
        info.addView(qualityLabel); info.addView(switch)
        bottom.addView(info)
        val controls = FrameLayout(this).apply { minimumHeight = dp(66) }
        recordButton = TextView(this).apply {
            text = "●"
            textSize = 35f
            gravity = Gravity.CENTER
            setTextColor(0xFFFF4545.toInt())
            setOnClickListener { if (recordingNow) stopRecording() else startRecording() }
        }
        controls.addView(recordButton, FrameLayout.LayoutParams(dp(72), dp(62), Gravity.CENTER))
        val recHint = TextView(this).apply { text = "REC"; textSize = 10f; letterSpacing = .18f; setTextColor(0xFFB8B8B8.toInt()); gravity = Gravity.CENTER }
        controls.addView(recHint, FrameLayout.LayoutParams(dp(54), dp(62), Gravity.CENTER_VERTICAL or Gravity.RIGHT))
        bottom.addView(controls, LinearLayout.LayoutParams(-1, dp(66)))
        root.addView(bottom, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        setContentView(root)
    }

    private fun showQualityMenu() {
        val options = arrayOf("4K (if supported)", "FHD 1080p", "HD 720p", "SD 480p")
        AlertDialogBuilder().show(options) { index ->
            selectedQuality = when (index) { 0 -> Quality.UHD; 1 -> Quality.FHD; 2 -> Quality.HD; else -> Quality.SD }
            qualityLabel.text = options[index].substringBefore(" (").uppercase(Locale.US) + "  ·  AUTO FPS"
            startCamera()
        }
    }

    private inner class AlertDialogBuilder {
        fun show(items: Array<String>, choose: (Int) -> Unit) {
            android.app.AlertDialog.Builder(this@MainActivity).setTitle("Recording quality").setItems(items) { _, which -> choose(which) }.show()
        }
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val provider = future.get()
                cameraProvider = provider
                val selector = CameraSelector.Builder().requireLensFacing(lens).build()
                val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
                val recorder = Recorder.Builder().setQualitySelector(
                    QualitySelector.from(selectedQuality, FallbackStrategy.lowerQualityOrHigherThan(Quality.SD))
                ).build()
                val capture = VideoCapture.withOutput(recorder)
                provider.unbindAll()
                provider.bindToLifecycle(this, selector, preview, capture)
                videoCapture = capture
                status.text = if (lens == CameraSelector.LENS_FACING_BACK) "BACK CAMERA  ·  READY" else "FRONT CAMERA  ·  READY"
            } catch (e: Exception) {
                status.text = "CAMERA NOT AVAILABLE"
                Toast.makeText(this, "Не удалось запустить камеру: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun startRecording() {
        val capture = videoCapture ?: run { Toast.makeText(this, "Камера ещё запускается", Toast.LENGTH_SHORT).show(); return }
        val name = "NIZ_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(System.currentTimeMillis())}.mp4"
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= 29) put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/NIZ Camera")
        }
        val output = MediaStoreOutputOptions.Builder(contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI).setContentValues(values).build()
        var pending = capture.output.prepareRecording(this, output)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) pending = pending.withAudioEnabled()
        recording = pending.start(ContextCompat.getMainExecutor(this)) { event ->
            when (event) {
                is VideoRecordEvent.Start -> {
                    recordingNow = true; recordButton.text = "■"; recordButton.setTextColor(0xFFFF4545.toInt()); status.text = "●  RECORDING"
                }
                is VideoRecordEvent.Finalize -> {
                    recordingNow = false; recording = null; recordButton.text = "●"; status.text = if (event.hasError()) "RECORDING ERROR" else "SAVED  ·  READY"
                    if (event.hasError()) Toast.makeText(this, "Ошибка записи: ${event.error}", Toast.LENGTH_LONG).show()
                    else Toast.makeText(this, "Видео сохранено в Movies/NIZ Camera", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun stopRecording() { recording?.stop(); status.text = "SAVING…" }
    private fun hasCameraPermission() = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        recording?.stop()
        cameraExecutor.shutdown()
        super.onDestroy()
    }
}
