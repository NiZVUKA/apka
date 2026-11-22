package com.nizvuka.nizcamera

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    private lateinit var previewView: PreviewView
    private lateinit var status: TextView
    private lateinit var captureButton: Button
    private lateinit var recordButton: Button

    private var imageCapture: ImageCapture? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private var camera: Camera? = null
    private var lensFacing = CameraSelector.LENS_FACING_BACK
    private var torchOn = false

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            if (hasCameraPermission()) startCamera()
            else toast("Для работы NIZ Camera нужен доступ к камере")
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildInterface()
        if (hasCameraPermission()) startCamera() else {
            permissionLauncher.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO))
        }
    }

    private fun hasCameraPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun buildInterface() {
        val base = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF080B10.toInt())
        }

        previewView = PreviewView(this).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
        val previewWrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(previewView, LinearLayout.LayoutParams(-1, 0, 1f))
        }
        base.addView(previewWrap, LinearLayout.LayoutParams(-1, 0, 1f))

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(10, 10, 10, 10)
            setBackgroundColor(0xFF101722.toInt())
        }

        status = TextView(this).apply {
            text = "NIZ CAMERA  •  ГОТОВО"
            textSize = 12f
            setTextColor(0xFF7CCBFF.toInt())
            gravity = Gravity.CENTER_VERTICAL
            setPadding(12, 0, 12, 0)
        }
        controls.addView(status, LinearLayout.LayoutParams(0, 52, 1f))

        controls.addView(makeButton("−", 64) { changeZoom(-0.2f) })
        controls.addView(makeButton("+", 64) { changeZoom(0.2f) })
        controls.addView(makeButton("СВЕТ", 86) { toggleTorch() })
        controls.addView(makeButton("КАМЕРА", 100) { switchCamera() })
        captureButton = makeButton("ФОТО", 100) { takePhoto() }
        controls.addView(captureButton)
        recordButton = makeButton("ВИДЕО", 100) { toggleRecording() }
        controls.addView(recordButton)

        base.addView(controls, LinearLayout.LayoutParams(-1, -2))
        setContentView(base)
    }

    private fun makeButton(label: String, width: Int, action: () -> Unit): Button {
        return Button(this).apply {
            text = label
            textSize = 11f
            isAllCaps = false
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFF176B9B.toInt()))
            setOnClickListener { action() }
            layoutParams = LinearLayout.LayoutParams(width, 48).apply {
                setMargins(4, 2, 4, 2)
            }
        }
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }
            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build()

            val recorder = Recorder.Builder()
                .setQualitySelector(
                    QualitySelector.from(
                        Quality.FHD,
                        FallbackStrategy.lowerQualityOrHigherThan(Quality.SD)
                    )
                ).build()
            videoCapture = VideoCapture.withOutput(recorder)

            val selector = CameraSelector.Builder().requireLensFacing(lensFacing).build()
            try {
                provider.unbindAll()
                camera = provider.bindToLifecycle(this, selector, preview, imageCapture, videoCapture)
                status.text = "NIZ CAMERA  •  ${if (lensFacing == CameraSelector.LENS_FACING_BACK) "ОСНОВНАЯ" else "ФРОНТАЛЬНАЯ"}"
            } catch (e: Exception) {
                status.text = "КАМЕРА НЕДОСТУПНА"
                toast("Не удалось запустить камеру")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun takePhoto() {
        val capture = imageCapture ?: return
        val name = "NIZ_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.jpg"
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= 29) put(MediaStore.Images.Media.RELATIVE_PATH, "DCIM/NIZ Camera")
        }
        val output = ImageCapture.OutputFileOptions.Builder(
            contentResolver, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values
        ).build()
        capture.takePicture(output, ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(result: ImageCapture.OutputFileResults) {
                    toast("Фото сохранено")
                }
                override fun onError(exception: ImageCaptureException) {
                    toast("Ошибка сохранения фото")
                }
            })
    }

    private fun toggleRecording() {
        val current = recording
        if (current != null) {
            current.stop()
            recording = null
            recordButton.text = "ВИДЕО"
            status.text = "NIZ CAMERA  •  ЗАПИСЬ ОСТАНОВЛЕНА"
            return
        }
        val capture = videoCapture ?: return
        val name = "NIZ_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.mp4"
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= 29) put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/NIZ Camera")
        }
        val output = MediaStoreOutputOptions.Builder(
            contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        ).setContentValues(values).build()

        try {
            var pending = capture.output.prepareRecording(this, output)
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED) {
                pending = pending.withAudioEnabled()
            }
            recording = pending.start(ContextCompat.getMainExecutor(this)) { event ->
                when (event) {
                    is VideoRecordEvent.Start -> {
                        recordButton.text = "СТОП"
                        status.text = "NIZ CAMERA  •  ИДЁТ ЗАПИСЬ"
                    }
                    is VideoRecordEvent.Finalize -> {
                        recording = null
                        recordButton.text = "ВИДЕО"
                        if (!event.hasError()) toast("Видео сохранено")
                        else toast("Ошибка записи видео")
                    }
                }
            }
        } catch (e: Exception) {
            toast("Не удалось начать запись")
        }
    }

    private fun switchCamera() {
        lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK)
            CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK
        startCamera()
    }

    private fun changeZoom(delta: Float) {
        val cam = camera ?: return
        val state = cam.cameraInfo.zoomState.value ?: return
        val next = (state.zoomRatio + delta).coerceIn(state.minZoomRatio, state.maxZoomRatio)
        cam.cameraControl.setZoomRatio(next)
    }

    private fun toggleTorch() {
        val cam = camera ?: return
        if (!cam.cameraInfo.hasFlashUnit()) {
            toast("Вспышка недоступна")
            return
        }
        torchOn = !torchOn
        cam.cameraControl.enableTorch(torchOn)
    }

    private fun toast(message: String) =
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
}
