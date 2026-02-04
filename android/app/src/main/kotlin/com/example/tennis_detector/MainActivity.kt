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

import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import com.google.mlkit.vision.objects.ObjectDetection
import com.google.mlkit.vision.objects.defaults.ObjectDetectorOptions
import org.tensorflow.lite.Interpreter

import java.util.concurrent.Executors

class MainActivity: FlutterActivity() {
    // Минимальный запуск модели для теста
    private fun runTFLiteModel(): String {
        return try {
            val assetManager = this.assets
            val fileDescriptor = assetManager.openFd("yolov8n_float16.tflite")
            val fileInputStream = fileDescriptor.createInputStream()
            val fileChannel = fileInputStream.channel
            val startOffset = fileDescriptor.startOffset
            val declaredLength = fileDescriptor.length
            val modelBuffer = fileChannel.map(java.nio.channels.FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
            fileInputStream.close()
            fileDescriptor.close()
            val interpreter = Interpreter(modelBuffer)
            // Dummy input: [1, 640, 640, 3] float32 (или float16, если требуется)
            val input = Array(1) { Array(640) { Array(640) { FloatArray(3) } } }
            // Dummy output: YOLOv8 обычно [1, 84, 8400]
            val output = Array(1) { Array(84) { FloatArray(8400) } }
            interpreter.run(input, output)
            "Output shape: [${output.size}, ${output[0].size}, ${output[0][0].size}]"
        } catch (e: Exception) {
            "Model run error: ${e.message}"
        }
    }
    private fun isTFLiteAvailable(): Boolean {
        return try {
            Class.forName("org.tensorflow.lite.Interpreter")
            true
        } catch (e: ClassNotFoundException) {
            false
        }
    }

    private fun isModelLoadable(): Boolean {
        return try {
            val assetManager = this.assets
            val fileDescriptor = assetManager.openFd("yolov8n_float16.tflite")
            fileDescriptor.close()
            true
        } catch (e: Exception) {
            false
        }
    }

    private val METHOD_CHANNEL = "com.example.camera/methods"
    private val EVENT_CHANNEL = "com.example.camera/events"
    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private var eventSink: EventChannel.EventSink? = null

    private val labeler = ImageLabeling.getClient(ImageLabelerOptions.DEFAULT_OPTIONS)

    private val objectOptions = ObjectDetectorOptions.Builder()
        .setDetectorMode(ObjectDetectorOptions.STREAM_MODE)
        .enableClassification()
        .build()
    private val objectDetector = ObjectDetection.getClient(objectOptions)

    private var lastUpdate = 0L

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)

        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, METHOD_CHANNEL).setMethodCallHandler { call, result ->
            when (call.method) {
                "startCamera" -> {
                    if (checkPermissions()) {
                        val tid = startCamera(flutterEngine)
                        result.success(tid)
                    } else {
                        ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 101)
                        result.error("PERM", "Permissions needed", null)
                    }
                }
                "isTFLiteAvailable" -> {
                    val tfliteOk = isTFLiteAvailable()
                    val modelOk = isModelLoadable()
                    result.success(mapOf("tflite" to tfliteOk, "model" to modelOk))
                }
                "runTFLiteModel" -> {
                    val output = runTFLiteModel()
                    result.success(output)
                }
                else -> {
                    result.notImplemented()
                }
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
            // --- TFLite инференс на реальном кадре ---
            try {
                val assetManager = this.assets
                val fileDescriptor = assetManager.openFd("yolov8n_float16.tflite")
                val fileInputStream = fileDescriptor.createInputStream()
                val fileChannel = fileInputStream.channel
                val startOffset = fileDescriptor.startOffset
                val declaredLength = fileDescriptor.length
                val modelBuffer = fileChannel.map(java.nio.channels.FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
                fileInputStream.close()
                fileDescriptor.close()
                val interpreter = Interpreter(modelBuffer)
                // Преобразуем кадр в 640x640 float32
                val bitmap = imageProxy.toBitmap(640, 640)
                val input = bitmapToInputArray(bitmap)
                val output = Array(1) { Array(84) { FloatArray(8400) } }
                interpreter.run(input, output)
                val objectCount = countObjectsFromOutput(output)
                runOnUiThread {
                    val map = HashMap<String, String>()
                    map["objects"] = "TFLite objects: $objectCount"
                    eventSink?.success(map)
                }
            } catch (_: Exception) {}
            // --- MLKit (старый код) ---
            val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
            
            objectDetector.process(image)
                .addOnSuccessListener { objects ->
                    val roundObjects = ArrayList<String>()
                    
                    for (obj in objects) {
                        val bounds = obj.boundingBox
                        val ratio = bounds.width().toFloat() / bounds.height().toFloat()
                        val isGeometricCircle = ratio > 0.8 && ratio < 1.2

                        var labelText = "Unknown"
                        if (obj.labels.isNotEmpty()) {
                            labelText = obj.labels[0].text
                        }

                        if (isGeometricCircle || labelText.contains("Ball", true)) {
                            roundObjects.add("$labelText (Ratio: ${String.format("%.2f", ratio)})")
                        }
                    }

                    labeler.process(image)
                        .addOnSuccessListener { labels ->
                            val relevantScenes = labels
                                .filter { it.confidence > 0.6 }
                                .map { it.text }
                                .take(3)
                                .joinToString(", ")

                            val objStr = if(roundObjects.isEmpty()) "None" else roundObjects.joinToString(", ")

                            val currentTime = System.currentTimeMillis()
                            if (currentTime - lastUpdate > 200) {
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

    // Преобразование imageProxy в Bitmap нужного размера
    private fun ImageProxy.toBitmap(width: Int, height: Int): android.graphics.Bitmap {
        val yBuffer = planes[0].buffer
        val uBuffer = planes[1].buffer
        val vBuffer = planes[2].buffer
        val ySize = yBuffer.remaining()
        val uSize = uBuffer.remaining()
        val vSize = vBuffer.remaining()
        val nv21 = ByteArray(ySize + uSize + vSize)
        yBuffer.get(nv21, 0, ySize)
        vBuffer.get(nv21, ySize, vSize)
        uBuffer.get(nv21, ySize + vSize, uSize)
        val yuvImage = android.graphics.YuvImage(nv21, android.graphics.ImageFormat.NV21, this.width, this.height, null)
        val out = java.io.ByteArrayOutputStream()
        yuvImage.compressToJpeg(android.graphics.Rect(0, 0, this.width, this.height), 100, out)
        val imageBytes = out.toByteArray()
        val bitmap = android.graphics.BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
        return android.graphics.Bitmap.createScaledBitmap(bitmap, width, height, true)
    }

    // Преобразование Bitmap в float32 input для модели
    private fun bitmapToInputArray(bitmap: android.graphics.Bitmap): Array<Array<Array<FloatArray>>> {
        val input = Array(1) { Array(640) { Array(640) { FloatArray(3) } } }
        for (y in 0 until 640) {
            for (x in 0 until 640) {
                val pixel = bitmap.getPixel(x, y)
                input[0][y][x][0] = ((pixel shr 16) and 0xFF) / 255.0f
                input[0][y][x][1] = ((pixel shr 8) and 0xFF) / 255.0f
                input[0][y][x][2] = (pixel and 0xFF) / 255.0f
            }
        }
        return input
    }

    // Подсчёт объектов по выходу модели (очень грубо: confidence > 0.3)
    private fun countObjectsFromOutput(output: Array<Array<FloatArray>>): Int {
        var count = 0
        for (i in 0 until 8400) {
            val conf = output[0][4][i]
            if (conf > 0.3f) count++
        }
        return count
    }

    // Извлечение боксов из выхода модели (YOLOv8: x, y, w, h)
    private fun extractBoxesFromOutput(output: Array<Array<FloatArray>>): List<List<Double>> {
        val boxes = mutableListOf<List<Double>>()
        for (i in 0 until 8400) {
            val conf = output[0][4][i]
            if (conf > 0.3f) {
                val x = output[0][0][i]
                val y = output[0][1][i]
                val w = output[0][2][i]
                val h = output[0][3][i]
                boxes.add(listOf(x, y, w, h))
            }
        }
        return boxes
    }
}

