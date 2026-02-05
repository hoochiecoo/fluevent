package com.example.tennis_detector

import android.Manifest
import android.content.pm.PackageManager
import android.util.Log
import android.view.Surface
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.EventChannel

import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import org.tensorflow.lite.Interpreter

import java.util.concurrent.Executors

private const val TAG = "TFLiteDetector"

class MainActivity: FlutterActivity() {
    private fun initTFLiteModel() {
        if (tfliteInterpreter != null) return
        try {
            addDebugLog("▶ Loading TFLite model...")
            val assetManager = this.assets
            val fileDescriptor = assetManager.openFd("yolov8n_float16.tflite")
            val fileInputStream = fileDescriptor.createInputStream()
            val fileChannel = fileInputStream.channel
            val startOffset = fileDescriptor.startOffset
            val declaredLength = fileDescriptor.length
            val modelBuffer = fileChannel.map(java.nio.channels.FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
            fileInputStream.close()
            fileDescriptor.close()
            tfliteInterpreter = Interpreter(modelBuffer)
            addDebugLog("✓ TFLite model loaded (${declaredLength/1024/1024}MB)")
        } catch (e: Exception) {
            addDebugLog("✗ Model load failed: ${e.javaClass.simpleName}")
            sendError("Model Error: ${e.message}")
        }
    }

    private fun sendError(message: String) {
        try {
            eventSink?.success(mapOf("error" to message, "detections" to emptyList<Map<String, Any>>()))
        } catch (e: Exception) {
            Log.e(TAG, "✗ Error channel failed: ${e.message}")
        }
    }
    
    private fun addDebugLog(message: String) {
        debugLogs.add("[${System.currentTimeMillis() % 100000}] $message")
        if (debugLogs.size > 50) debugLogs.removeAt(0)
        Log.i(TAG, message)
    }
    
    private fun sendDebugInfo(detections: List<Map<String, Any>>) {
        try {
            val map = HashMap<String, Any>()
            map["detections"] = detections
            map["count"] = detections.size
            map["logs"] = debugLogs.toList()
            map["inferenceTime"] = inferenceTime
            map["frameCount"] = frameCount
            eventSink?.success(map)
        } catch (e: Exception) {
            Log.e(TAG, "✗ Debug send failed: ${e.message}")
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
    private var tfliteInterpreter: Interpreter? = null
    private var lastUpdate = 0L
    
    private val debugLogs = mutableListOf<String>()
    private var inferenceTime = 0L
    private var frameCount = 0

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        
        initTFLiteModel()

        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, METHOD_CHANNEL).setMethodCallHandler { call, result ->
            when (call.method) {
                "startCamera" -> {
                    addDebugLog("▶ Camera start requested...")
                    if (checkPermissions()) {
                        val tid = startCamera(flutterEngine)
                        addDebugLog("✓ Camera texture created: $tid")
                        result.success(tid)
                    } else {
                        addDebugLog("✗ Camera permission denied")
                        ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 101)
                        result.error("PERM", "Permissions needed", null)
                    }
                }
                "isTFLiteAvailable" -> {
                    val modelOk = isModelLoadable()
                    result.success(mapOf("model" to modelOk))
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
                addDebugLog("✓ Camera bound successfully")
            } catch(e: Exception) {
                addDebugLog("✗ Camera bind failed: ${e.javaClass.simpleName}")
                sendError("Camera: ${e.message}")
            }

        }, ContextCompat.getMainExecutor(this))

        return textureId
    }

    @androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)
    private fun processImageProxy(imageProxy: ImageProxy) {
        try {
            frameCount++
            val startTime = System.currentTimeMillis()
            
            val mediaImage = imageProxy.image
            if (mediaImage == null) {
                addDebugLog("⚠ Frame $frameCount: null image")
                return
            }
            
            if (tfliteInterpreter == null) {
                addDebugLog("⚠ Interpreter not ready")
                return
            }
            
            val bitmap = imageProxy.toBitmap(640, 640)
            val input = bitmapToInputArray(bitmap)
            val output = Array(1) { Array(84) { FloatArray(8400) } }
            
            tfliteInterpreter!!.run(input, output)
            
            val detections = parseDetections(output)
            inferenceTime = System.currentTimeMillis() - startTime
            val currentTime = System.currentTimeMillis()
            
            if (currentTime - lastUpdate > 200) {
                lastUpdate = currentTime
                sendDebugInfo(detections)
            }
        } catch (e: Exception) {
            addDebugLog("✗ Process error: ${e.javaClass.simpleName}")
            sendError("Inference: ${e.message}")
        } finally {
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

    // Подсчёт объектов по выходу модели (confidence > 0.3)
    private fun countObjectsFromOutput(output: Array<Array<FloatArray>>): Int {
        var count = 0
        for (i in 0 until 8400) {
            val conf = output[0][4][i]
            if (conf > 0.3f) count++
        }
        return count
    }

    // Парсинг детекций из выхода YOLOv8
    private fun parseDetections(output: Array<Array<FloatArray>>): List<Map<String, Any>> {
        val detections = mutableListOf<Map<String, Any>>()
        val COCO_CLASSES = arrayOf(
            "person", "bicycle", "car", "motorbike", "aeroplane", "bus", "train", "truck",
            "boat", "traffic light", "fire hydrant", "stop sign", "parking meter", "bench",
            "cat", "dog", "horse", "sheep", "cow", "elephant", "bear", "zebra", "giraffe",
            "backpack", "umbrella", "handbag", "tie", "suitcase", "frisbee", "skis",
            "snowboard", "sports ball", "kite", "baseball bat", "baseball glove", "skateboard",
            "surfboard", "tennis racket", "bottle", "wine glass", "cup", "fork", "knife",
            "spoon", "bowl", "banana", "apple", "sandwich", "orange", "broccoli", "carrot",
            "hot dog", "pizza", "donut", "cake", "chair", "sofa", "pottedplant", "bed",
            "diningtable", "toilet", "tvmonitor", "laptop", "mouse", "remote", "keyboard",
            "microwave", "oven", "toaster", "sink", "refrigerator", "book", "clock", "vase",
            "scissors", "teddy bear", "hair drier", "toothbrush"
        )
        
        for (i in 0 until 8400) {
            val conf = output[0][4][i]
            if (conf > 0.3f) {
                val x = output[0][0][i]
                val y = output[0][1][i]
                val w = output[0][2][i]
                val h = output[0][3][i]
                
                var classId = 0
                var maxClassProb = 0f
                for (classIdx in 5 until 85) {
                    val prob = output[0][classIdx][i]
                    if (prob > maxClassProb) {
                        maxClassProb = prob
                        classId = classIdx - 5
                    }
                }
                
                val className = if (classId < COCO_CLASSES.size) COCO_CLASSES[classId] else "unknown"
                
                detections.add(mapOf(
                    "class" to className,
                    "confidence" to String.format("%.2f", (conf * 100).toInt()),
                    "x" to x.toDouble(),
                    "y" to y.toDouble(),
                    "w" to w.toDouble(),
                    "h" to h.toDouble()
                ))
            }
        }
        return detections
    }
}
