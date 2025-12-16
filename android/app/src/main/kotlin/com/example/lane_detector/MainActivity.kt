package com.example.lane_detector

import android.Manifest
import android.content.pm.PackageManager
import android.view.Surface
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.EventChannel

import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import java.util.concurrent.Executors
import java.nio.ByteBuffer
import kotlin.math.abs

class MainActivity: FlutterActivity() {
    private val METHOD_CHANNEL = "com.example.lane/methods"
    private val EVENT_CHANNEL = "com.example.lane/events"
    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private var eventSink: EventChannel.EventSink? = null

    // Algorithm Variables
    private var lastLeftX = 0.2f
    private var lastRightX = 0.8f

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)

        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, METHOD_CHANNEL).setMethodCallHandler { call, result ->
            if (call.method == "startCamera") {
                if (checkPermissions()) {
                    val tid = startCamera(flutterEngine)
                    result.success(tid)
                } else {
                    ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 101)
                    result.error("PERM", "Permissions needed", null)
                }
            } else {
                result.notImplemented()
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
        val surfaceTexture = textureEntry.surfaceTexture()
        surfaceTexture.setDefaultBufferSize(640, 480) // Low res for faster processing
        val textureId = textureEntry.id()

        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            val preview = Preview.Builder().build()
            
            preview.setSurfaceProvider { request ->
                val surface = Surface(surfaceTexture)
                request.provideSurface(surface, cameraExecutor) {}
            }

            // Custom Analyzer for Lanes
            val imageAnalyzer = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            
            imageAnalyzer.setAnalyzer(cameraExecutor) { imageProxy ->
                processImage(imageProxy)
            }

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageAnalyzer)
            } catch(e: Exception) {}

        }, ContextCompat.getMainExecutor(this))

        return textureId
    }

    private fun checkPermissions() = ContextCompat.checkSelfPermission(baseContext, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    
    // ==========================================
    // SIMPLE COMPUTER VISION ALGORITHM
    // ==========================================
    private fun processImage(image: ImageProxy) {
        // Access Y plane (Luminance/Grayscale)
        val plane = image.planes[0]
        val buffer = plane.buffer
        val width = image.width
        val height = image.height
        val rowStride = plane.rowStride
        // Since we are in portrait mode usually, the width/height might need logic adjustment depending on sensor orientation.
        // For simplicity in this demo, we assume raw buffer processing.
        
        // We only scan the bottom 25% of the image to find the road lines
        val startY = (height * 0.75).toInt()
        val endY = (height * 0.85).toInt()
        
        // Scan 5 horizontal lines within that area
        val scanLines = 5
        val stepY = (endY - startY) / scanLines

        var foundLeftSum = 0f
        var foundRightSum = 0f
        var countLeft = 0
        var countRight = 0
        
        val centerX = width / 2

        // Threshold for detecting "White" line vs "Dark" road
        // In YUV, Y is brightness (0-255). Road is usually < 100, Lines > 150
        val brightnessThreshold = 140

        for (i in 0 until scanLines) {
            val y = startY + i * stepY
            val rowOffset = y * rowStride
            
            // Scan Left Side (Center -> Left)
            // We look for the first pixel that is BRIGHT
            for (x in centerX downTo 0 step 4) { // step 4 for performance
                val pixel = buffer.get(rowOffset + x).toInt() and 0xFF
                if (pixel > brightnessThreshold) {
                    foundLeftSum += x
                    countLeft++
                    break // Found the inside edge of the line
                }
            }

            // Scan Right Side (Center -> Right)
            for (x in centerX until width step 4) {
                val pixel = buffer.get(rowOffset + x).toInt() and 0xFF
                if (pixel > brightnessThreshold) {
                    foundRightSum += x
                    countRight++
                    break
                }
            }
        }

        // Calculate Average Normalized X (0.0 to 1.0)
        var newLeft = if (countLeft > 0) (foundLeftSum / countLeft) / width else lastLeftX
        var newRight = if (countRight > 0) (foundRightSum / countRight) / width else lastRightX

        // Simple Smoothing (Linear Interpolation) to reduce jitter
        lastLeftX = lastLeftX * 0.7f + newLeft * 0.3f
        lastRightX = lastRightX * 0.7f + newRight * 0.3f

        // Send to Flutter
        val json = "{"left": $lastLeftX, "right": $lastRightX}"
        runOnUiThread { eventSink?.success(json) }

        image.close()
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }
}
