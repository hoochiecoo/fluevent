package com.example.lane_detection_app

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.*
import android.view.Surface
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodChannel
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import kotlin.math.atan2

class MainActivity: FlutterActivity() {
    private val METHOD_CHANNEL = "com.example.camera/methods"
    private val EVENT_CHANNEL = "com.example.camera/events"
    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private var eventSink: EventChannel.EventSink? = null

    // Filter flags from Flutter
    private var useGrayscale = true
    private var useBlur = true
    private var useCanny = true

    init {
        // Initialize OpenCV
        OpenCVLoader.initLocal()
    }

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)

        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, METHOD_CHANNEL).setMethodCallHandler { call, result ->
            when (call.method) {
                "startCamera" -> {
                    if (checkPermissions()) {
                        val textureId = startCamera(flutterEngine)
                        result.success(textureId)
                    } else {
                        ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 101)
                        result.error("PERM", "Permissions not granted", null)
                    }
                }
                "updateSettings" -> {
                    useGrayscale = call.argument<Boolean>("grayscale") ?: true
                    useBlur = call.argument<Boolean>("blur") ?: true
                    useCanny = call.argument<Boolean>("canny") ?: true
                    result.success(null)
                }
                else -> result.notImplemented()
            }
        }

        EventChannel(flutterEngine.dartExecutor.binaryMessenger, EVENT_CHANNEL).setStreamHandler(
            object : EventChannel.StreamHandler {
                override fun onListen(args: Any?, events: EventChannel.EventSink?) { eventSink = events }
                override fun onCancel(args: Any?) { eventSink = null }
            }
        )
    }

    private fun startCamera(flutterEngine: FlutterEngine): Long {
        val textureEntry = flutterEngine.renderer.createSurfaceTexture()
        val surfaceTexture = textureEntry.surfaceTexture().apply {
            setDefaultBufferSize(640, 480)
        }
        val preview = Preview.Builder().build().apply {
            setSurfaceProvider { request ->
                val surface = Surface(surfaceTexture)
                request.provideSurface(surface, cameraExecutor) {}
            }
        }
        val imageAnalyzer = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build().apply {
                setAnalyzer(cameraExecutor, ::processImageProxy)
            }
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageAnalyzer)
            } catch (e: Exception) {
                runOnUiThread { eventSink?.error("CAMERA", "Failed to bind camera", e.message) }
            }
        }, ContextCompat.getMainExecutor(this))
        return textureEntry.id()
    }
    
    @androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)
    private fun processImageProxy(imageProxy: ImageProxy) {
        val bitmap = imageProxy.toBitmap() ?: return imageProxy.close()
        
        val mat = Mat()
        Utils.bitmapToMat(bitmap, mat)
        Imgproc.cvtColor(mat, mat, Imgproc.COLOR_RGBA2RGB)

        // --- Lane Detection Logic ---
        val grayMat = Mat()
        if (useGrayscale) {
            Imgproc.cvtColor(mat, grayMat, Imgproc.COLOR_RGB2GRAY)
        } else {
            // Canny needs a single channel image, so we default to gray
            Imgproc.cvtColor(mat, grayMat, Imgproc.COLOR_RGB2GRAY)
        }

        val blurMat = Mat()
        if (useBlur) {
            Imgproc.GaussianBlur(grayMat, blurMat, Size(5.0, 5.0), 0.0)
        } else {
            grayMat.copyTo(blurMat)
        }

        val cannyMat = Mat()
        if (useCanny) {
            Imgproc.Canny(blurMat, cannyMat, 50.0, 150.0)
        } else {
            // Without canny, we can't find lines
            runOnUiThread { eventSink?.success("Детектор Canny выключен") }
            mat.release()
            grayMat.release()
            blurMat.release()
            imageProxy.close()
            return
        }

        // Region of Interest (bottom half of the image)
        val height = cannyMat.height()
        val roi = Rect(0, height / 2, cannyMat.width(), height / 2)
        val roiMat = Mat(cannyMat, roi)

        val lines = Mat()
        Imgproc.HoughLinesP(roiMat, lines, 1.0, Math.PI / 180, 50, 50.0, 10.0)

        val (leftLine, rightLine) = averageLines(lines, height)

        val message = when {
            leftLine != null && rightLine != null -> "Обнаружены левая и правая линии"
            leftLine != null -> "Обнаружена только левая линия"
            rightLine != null -> "Обнаружена только правая линия"
            else -> "Линии не обнаружены"
        }
        
        runOnUiThread { eventSink?.success(message) }
        
        // Release memory
        mat.release()
        grayMat.release()
        blurMat.release()
        cannyMat.release()
        roiMat.release()
        lines.release()
        imageProxy.close()
    }
    
    private fun averageLines(lines: Mat, height: Int): Pair<DoubleArray?, DoubleArray?> {
        val leftLines = mutableListOf<DoubleArray>()
        val rightLines = mutableListOf<DoubleArray>()

        for (i in 0 until lines.rows()) {
            val line = lines.get(i, 0)
            val x1 = line[0]
            val y1 = line[1] + height / 2 // Adjust y for ROI
            val x2 = line[2]
            val y2 = line[3] + height / 2 // Adjust y for ROI

            if (x2 - x1 == 0.0) continue // Skip vertical lines

            val slope = (y2 - y1) / (x2 - x1)
            val angle = atan2(y2 - y1, x2 - x1) * 180 / Math.PI

            if (kotlin.math.abs(angle) < 20 || kotlin.math.abs(angle) > 160) continue // Skip horizontal-ish lines

            if (slope < 0) {
                leftLines.add(doubleArrayOf(x1, y1, x2, y2))
            } else {
                rightLines.add(doubleArrayOf(x1, y1, x2, y2))
            }
        }

        val avgLeft = if (leftLines.isNotEmpty()) calculateAverage(leftLines, height) else null
        val avgRight = if (rightLines.isNotEmpty()) calculateAverage(rightLines, height) else null
        
        return Pair(avgLeft, avgRight)
    }

    private fun calculateAverage(lines: List<DoubleArray>, height: Int): DoubleArray {
        var avgX1 = 0.0
        var avgY1 = 0.0
        var avgX2 = 0.0
        var avgY2 = 0.0
        lines.forEach {
            avgX1 += it[0]
            avgY1 += it[1]
            avgX2 += it[2]
            avgY2 += it[3]
        }
        avgX1 /= lines.size
        avgY1 /= lines.size
        avgX2 /= lines.size
        avgY2 /= lines.size

        val slope = (avgY2 - avgY1) / (avgX2 - avgX1)
        val intercept = avgY1 - slope * avgX1

        val y1 = height.toDouble()
        val x1 = (y1 - intercept) / slope
        val y2 = (height / 2).toDouble() + 50
        val x2 = (y2 - intercept) / slope

        return doubleArrayOf(x1, y1, x2, y2)
    }

    private fun checkPermissions() = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    // Extension function to convert ImageProxy to Bitmap
    private fun ImageProxy.toBitmap(): Bitmap? {
        val planeProxy = planes[0]
        val buffer = planeProxy.buffer
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }
}
