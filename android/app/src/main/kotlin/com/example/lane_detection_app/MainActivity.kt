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

    private var useGrayscale = true
    private var useBlur = true
    private var useCanny = true

    init {
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

        val grayMat = Mat()
        Imgproc.cvtColor(mat, grayMat, Imgproc.COLOR_RGB2GRAY)
        
        val processedMat = when {
            !useCanny -> grayMat 
            !useBlur -> { 
                val cannyMat = Mat()
                Imgproc.Canny(grayMat, cannyMat, 50.0, 150.0)
                cannyMat
            }
            else -> { 
                val blurMat = Mat()
                Imgproc.GaussianBlur(grayMat, blurMat, Size(5.0, 5.0), 0.0)
                val cannyMat = Mat()
                Imgproc.Canny(blurMat, cannyMat, 50.0, 150.0)
                blurMat.release()
                cannyMat
            }
        }

        if (!useCanny) {
             runOnUiThread { eventSink?.success("Детектор Canny выключен") }
        } else {
            val height = processedMat.height()
            val roi = Rect(0, height / 2, processedMat.width(), height / 2)
            val roiMat = Mat(processedMat, roi)

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
            roiMat.release()
            lines.release()
        }
        
        mat.release()
        grayMat.release()
        processedMat.release()
        imageProxy.close()
    }
    
    private fun averageLines(lines: Mat, height: Int): Pair<DoubleArray?, DoubleArray?> {
        val leftLines = mutableListOf<DoubleArray>()
        val rightLines = mutableListOf<DoubleArray>()

        for (i in 0 until lines.rows()) {
            val line = lines.get(i, 0)
            val x1 = line[0]; val y1 = line[1] + height / 2
            val x2 = line[2]; val y2 = line[3] + height / 2
            if (x2 - x1 == 0.0) continue
            val slope = (y2 - y1) / (x2 - x1)
            if (kotlin.math.abs(slope) < 0.5) continue
            if (slope < 0) leftLines.add(doubleArrayOf(x1, y1, x2, y2))
            else rightLines.add(doubleArrayOf(x1, y1, x2, y2))
        }

        val avgLeft = if (leftLines.isNotEmpty()) makeCoordinates(leftLines, height) else null
        val avgRight = if (rightLines.isNotEmpty()) makeCoordinates(rightLines, height) else null
        
        return Pair(avgLeft, avgRight)
    }

    private fun makeCoordinates(lines: List<DoubleArray>, height: Int): DoubleArray {
        var avgSlope = 0.0
        var avgIntercept = 0.0
        lines.forEach { line ->
            val x1 = line[0]; val y1 = line[1]; val x2 = line[2]; val y2 = line[3]
            val slope = (y2 - y1) / (x2 - x1)
            avgSlope += slope
            avgIntercept += y1 - slope * x1
        }
        avgSlope /= lines.size
        avgIntercept /= lines.size
        
        val y1 = height.toDouble()
        val x1 = (y1 - avgIntercept) / avgSlope
        val y2 = (height / 2).toDouble() + 50
        val x2 = (y2 - avgIntercept) / avgSlope

        return doubleArrayOf(x1, y1, x2, y2)
    }

    private fun checkPermissions() = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

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
