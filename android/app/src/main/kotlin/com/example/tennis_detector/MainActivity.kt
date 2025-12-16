package com.example.tennis_detector

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

// ML Kit Imports
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import com.google.mlkit.vision.objects.ObjectDetection
import com.google.mlkit.vision.objects.defaults.ObjectDetectorOptions

import java.util.concurrent.Executors

class MainActivity: FlutterActivity() {
    private val METHOD_CHANNEL = "com.example.camera/methods"
    private val EVENT_CHANNEL = "com.example.camera/events"
    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private var eventSink: EventChannel.EventSink? = null

    // 1. Image Labeler (For Court detection)
    private val labeler = ImageLabeling.getClient(ImageLabelerOptions.DEFAULT_OPTIONS)

    // 2. Object Detector (For Balls/Round objects)
    // Stream mode is faster but less accurate, Single Image mode is accurate.
    // We enable classification to find "Ball" category.
    private val objectOptions = ObjectDetectorOptions.Builder()
        .setDetectorMode(ObjectDetectorOptions.STREAM_MODE)
        .enableClassification()
        .build()
    private val objectDetector = ObjectDetection.getClient(objectOptions)

    // To prevent spamming Flutter channel
    private var lastUpdate = 0L

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
        surfaceTexture.setDefaultBufferSize(640, 480) 
        val textureId = textureEntry.id()

        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            val preview = Preview.Builder().build()
            
            preview.setSurfaceProvider { request ->
                val surface = Surface(surfaceTexture)
                request.provideSurface(surface, cameraExecutor) {}
            }

            val imageAnalyzer = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            
            imageAnalyzer.setAnalyzer(cameraExecutor) { imageProxy ->
                processImageProxy(imageProxy)
            }

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageAnalyzer)
            } catch(e: Exception) {}

        }, ContextCompat.getMainExecutor(this))

        return textureId
    }

    @androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)
    private fun processImageProxy(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image
        if (mediaImage != null) {
            val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
            
            // We run two detectors. Note: This is heavy operation.
            // 1. Detect Objects
            objectDetector.process(image)
                .addOnSuccessListener { objects ->
                    
                    val roundObjects = ArrayList<String>()
                    
                    for (obj in objects) {
                        val bounds = obj.boundingBox
                        // LOGIC: Check Aspect Ratio (Width / Height)
                        // If it is close to 1.0 (e.g., 0.8 to 1.2), it is likely square or circular.
                        val ratio = bounds.width().toFloat() / bounds.height().toFloat()
                        val isGeometricCircle = ratio > 0.8 && ratio < 1.2

                        // LOGIC: Check Labels
                        var labelText = "Unknown"
                        if (obj.labels.isNotEmpty()) {
                            labelText = obj.labels[0].text
                        }

                        // Filter for relevant items
                        if (isGeometricCircle || labelText.contains("Ball", true)) {
                            roundObjects.add("$labelText (Ratio: ${String.format("%.2f", ratio)})")
                        }
                    }

                    // 2. Detect Scene (Nested to ensure sequential execution or use Tasks.whenAll)
                    labeler.process(image)
                        .addOnSuccessListener { labels ->
                            // Filter scene labels
                            val relevantScenes = labels
                                .filter { it.confidence > 0.6 }
                                .map { it.text }
                                .take(3)
                                .joinToString(", ")

                            val objStr = if(roundObjects.isEmpty()) "None" else roundObjects.joinToString(", ")

                            // Send to Flutter
                            val currentTime = System.currentTimeMillis()
                            if (currentTime - lastUpdate > 200) { // Limit updates to 5fps for UI
                                lastUpdate = currentTime
                                runOnUiThread {
                                    val map = HashMap<String, String>()
                                    map["scene"] = relevantScenes
                                    map["objects"] = objStr
                                    eventSink?.success(map)
                                }
                            }
                        }
                        .addOnCompleteListener { imageProxy.close() }
                }
                .addOnFailureListener { 
                    imageProxy.close() 
                }
        } else {
            imageProxy.close()
        }
    }

    private fun checkPermissions() = ContextCompat.checkSelfPermission(baseContext, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    
    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }
}
