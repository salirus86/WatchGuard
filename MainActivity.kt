package com.example.watchguard

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.*
import android.media.AudioManager
import android.media.ToneGenerator
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.provider.MediaStore
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetector
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max

class MainActivity : AppCompatActivity() {
    private lateinit var previewView: PreviewView
    private lateinit var overlay: DetectionOverlay
    private lateinit var status: TextView
    private lateinit var cameraExecutor: ExecutorService
    private var imageCapture: ImageCapture? = null
    private var detector: ObjectDetector? = null
    private val processing = AtomicBoolean(false)
    private val tone by lazy { ToneGenerator(AudioManager.STREAM_NOTIFICATION, 80) }
    private var soundEnabled = true
    private var saveEnabled = true
    private var lastAlertAt = 0L
    private var lastSavedAt = 0L
    private var lastFrameAt = 0L
    private var cameraStarted = false

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startCamera() else status.text = "Для работы нужна камера. Разрешите доступ в настройках."
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cameraExecutor = Executors.newSingleThreadExecutor()
        buildScreen()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun buildScreen() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(16, 24, 32))
        }
        val header = TextView(this).apply {
            text = "WATCHGUARD  •  КАМЕРА И ОБНАРУЖЕНИЕ"
            setTextColor(Color.WHITE)
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(12, 18, 12, 18)
        }
        root.addView(header, LinearLayout.LayoutParams(-1, -2))

        val frame = FrameLayout(this)
        previewView = PreviewView(this).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
        overlay = DetectionOverlay(this)
        frame.addView(previewView, FrameLayout.LayoutParams(-1, -1))
        frame.addView(overlay, FrameLayout.LayoutParams(-1, -1))
        root.addView(frame, LinearLayout.LayoutParams(-1, 0, 1f))

        status = TextView(this).apply {
            text = "Запуск камеры…"
            setTextColor(Color.WHITE)
            textSize = 15f
            gravity = Gravity.CENTER
            setPadding(12, 14, 12, 14)
        }
        root.addView(status, LinearLayout.LayoutParams(-1, -2))

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(8, 4, 8, 16)
        }
        val soundButton = Button(this).apply {
            text = "Звук: ВКЛ"
            setOnClickListener {
                soundEnabled = !soundEnabled
                text = if (soundEnabled) "Звук: ВКЛ" else "Звук: ВЫКЛ"
            }
        }
        val saveButton = Button(this).apply {
            text = "Фото: ВКЛ"
            setOnClickListener {
                saveEnabled = !saveEnabled
                text = if (saveEnabled) "Фото: ВКЛ" else "Фото: ВЫКЛ"
            }
        }
        controls.addView(soundButton, LinearLayout.LayoutParams(0, -2, 1f))
        controls.addView(saveButton, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(controls)
        setContentView(root)
    }

    private fun startCamera() {
        if (cameraStarted) return
        cameraStarted = true
        status.text = "Загружаю модель обнаружения…"
        try {
            val options = ObjectDetector.ObjectDetectorOptions.builder()
                .setBaseOptions(BaseOptions.builder().setModelAssetPath("efficientdet_lite0.tflite").build())
                .setRunningMode(RunningMode.VIDEO)
                .setScoreThreshold(0.45f)
                .setMaxResults(15)
                .build()
            detector = ObjectDetector.createFromOptions(this, options)
        } catch (e: Exception) {
            status.text = "Не удалось загрузить модель: ${e.localizedMessage}"
            cameraStarted = false
            return
        }

        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            try {
                val provider = providerFuture.get()
                val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
                imageCapture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .build()
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setTargetResolution(android.util.Size(640, 480))
                    .build()
                analysis.setAnalyzer(cameraExecutor) { proxy -> analyzeFrame(proxy) }
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture, analysis)
                status.text = "Ищу людей и транспорт…"
            } catch (e: Exception) {
                status.text = "Ошибка запуска камеры: ${e.localizedMessage}"
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun analyzeFrame(proxy: ImageProxy) {
        if (!processing.compareAndSet(false, true)) {
            proxy.close()
            return
        }
        var frameBitmap: Bitmap? = null
        try {
            val now = SystemClock.elapsedRealtime()
            if (now - lastFrameAt < 250) return
            lastFrameAt = now

            var bitmap = proxy.toBitmap()
            if (proxy.imageInfo.rotationDegrees != 0) {
                val matrix = Matrix().apply { postRotate(proxy.imageInfo.rotationDegrees.toFloat()) }
                val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                bitmap.recycle()
                bitmap = rotated
            }
            frameBitmap = bitmap
            val mpImage = BitmapImageBuilder(bitmap).build()
            val result = detector?.detectForVideo(mpImage, SystemClock.uptimeMillis())
            val matches = mutableListOf<DetectionBox>()
            result?.detections()?.forEach { detection ->
                val category = detection.categories().maxByOrNull { it.score() } ?: return@forEach
                val label = category.categoryName().lowercase(Locale.ROOT)
                val isPerson = label == "person"
                val isVehicle = label in setOf("car", "truck", "bus", "motorcycle", "bicycle", "train")
                if (isPerson || isVehicle) {
                    val rect = detection.boundingBox()
                    matches.add(
                        DetectionBox(
                            label = if (isPerson) "Человек" else vehicleName(label),
                            confidence = category.score(),
                            rect = RectF(rect.left, rect.top, rect.right, rect.bottom)
                        )
                    )
                }
            }
            runOnUiThread {
                overlay.setDetections(matches, bitmap.width, bitmap.height)
                status.text = if (matches.isEmpty()) {
                    "Никого и транспорта не обнаружено"
                } else {
                    val people = matches.count { it.label == "Человек" }
                    val vehicles = matches.size - people
                    "Обнаружено: людей — $people, транспорта — $vehicles"
                }
            }
            if (matches.isNotEmpty()) {
                if (soundEnabled && now - lastAlertAt > 2500) {
                    lastAlertAt = now
                    tone.startTone(ToneGenerator.TONE_PROP_BEEP, 180)
                }
                if (saveEnabled && now - lastSavedAt > 5000) {
                    lastSavedAt = now
                    saveDetectionPhoto()
                }
            }
            frameBitmap?.recycle()
            frameBitmap = null
        } catch (_: Exception) {
            // A bad camera frame is skipped; the next frame will be processed.
        } finally {
            frameBitmap?.recycle()
            proxy.close()
            processing.set(false)
        }
    }

    private fun vehicleName(label: String): String = when (label) {
        "car" -> "Автомобиль"
        "truck" -> "Грузовик"
        "bus" -> "Автобус"
        "motorcycle" -> "Мотоцикл"
        "bicycle" -> "Велосипед"
        "train" -> "Поезд"
        else -> "Транспорт"
    }

    private fun saveDetectionPhoto() {
        val capture = imageCapture ?: return
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "WatchGuard_$stamp.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/WatchGuard")
            }
        }
        val output = ImageCapture.OutputFileOptions.Builder(
            contentResolver, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values
        ).build()
        capture.takePicture(output, ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(result: ImageCapture.OutputFileResults) {
                    runOnUiThread { status.text = "${status.text} • Фото сохранено" }
                }
                override fun onError(exception: ImageCaptureException) { }
            })
    }

    override fun onDestroy() {
        super.onDestroy()
        try { detector?.close() } catch (_: Exception) {}
        cameraExecutor.shutdown()
        tone.release()
    }
}

data class DetectionBox(val label: String, val confidence: Float, val rect: RectF)

class DetectionOverlay(context: android.content.Context) : View(context) {
    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.GREEN
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 34f
        style = Paint.Style.FILL
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(180, 0, 0, 0)
        style = Paint.Style.FILL
    }
    private var detections: List<DetectionBox> = emptyList()
    private var sourceWidth = 1
    private var sourceHeight = 1

    fun setDetections(items: List<DetectionBox>, width: Int, height: Int) {
        detections = items
        sourceWidth = max(1, width)
        sourceHeight = max(1, height)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val scale = max(width.toFloat() / sourceWidth, height.toFloat() / sourceHeight)
        val dx = (width - sourceWidth * scale) / 2f
        val dy = (height - sourceHeight * scale) / 2f
        detections.forEach { item ->
            val r = RectF(
                item.rect.left * scale + dx,
                item.rect.top * scale + dy,
                item.rect.right * scale + dx,
                item.rect.bottom * scale + dy
            )
            canvas.drawRect(r, boxPaint)
            val caption = "${item.label} ${(item.confidence * 100).toInt()}%"
            val tw = textPaint.measureText(caption)
            val top = max(0f, r.top - 42f)
            canvas.drawRect(r.left, top, r.left + tw + 20f, top + 42f, bgPaint)
            canvas.drawText(caption, r.left + 10f, top + 31f, textPaint)
        }
    }
}
