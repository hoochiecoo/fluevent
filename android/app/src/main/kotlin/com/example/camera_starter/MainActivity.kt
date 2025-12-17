package com.example.camera_starter

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
import kotlin.math.abs

// OpenCV imports
import org.opencv.android.OpenCVLoader
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.Point
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Canvas
import android.graphics.Paint
import java.io.File
import java.io.FileOutputStream
import org.opencv.android.Utils
import android.util.Log

class MainActivity : FlutterActivity() {

    private val METHOD_CHANNEL = "com.example.camera/methods"
    private val EVENT_CHANNEL = "com.example.camera/events"
    private val LOG_CHANNEL = "com.example.camera/logs"

    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private var eventSink: EventChannel.EventSink? = null
    private var logSink: EventChannel.EventSink? = null

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)

        // Initialize OpenCV (uses packaged native libs). Returns false if failed.
        val ok = OpenCVLoader.initDebug()
        Log.d("OpenCV", "configureFlutterEngine.initDebug=$ok")
        logSink?.success("OpenCV init (configure): $ok")

        MethodChannel(
            flutterEngine.dartExecutor.binaryMessenger,
            METHOD_CHANNEL
        ).setMethodCallHandler { call, result ->
            when (call.method) {
                "startCamera" -> {
                    if (checkPermissions()) {
                        result.success(startCamera(flutterEngine))
                    } else {
                        ActivityCompat.requestPermissions(
                            this,
                            arrayOf(Manifest.permission.CAMERA),
                            101
                        )
                        result.error("PERM", "Camera permission needed", null)
                    }
                }
                "processImage" -> {
                    try {
                        val path = processImage()
                        result.success(path)
                    } catch (e: Exception) {
                        result.error("NATIVE_ERR", e.message, null)
                    }
                }
                else -> result.notImplemented()
            }
        }

        EventChannel(
            flutterEngine.dartExecutor.binaryMessenger,
            EVENT_CHANNEL
        ).setStreamHandler(object : EventChannel.StreamHandler {
            override fun onListen(args: Any?, events: EventChannel.EventSink?) {
                eventSink = events
            }

            override fun onCancel(args: Any?) {
                eventSink = null
            }
        })

        // Log channel to push diagnostic messages to Flutter UI
        EventChannel(
            flutterEngine.dartExecutor.binaryMessenger,
            LOG_CHANNEL
        ).setStreamHandler(object : EventChannel.StreamHandler {
            override fun onListen(args: Any?, events: EventChannel.EventSink?) {
                logSink = events
            }

            override fun onCancel(args: Any?) {
                logSink = null
            }
        })
    }

    private fun startCamera(flutterEngine: FlutterEngine): Long {
        // ensure OpenCV static init in case called before configure
        val ok = OpenCVLoader.initDebug()
        Log.d("OpenCV", "startCamera.initDebug=$ok")
        logSink?.success("OpenCV init (camera): $ok")

        val textureEntry = flutterEngine.renderer.createSurfaceTexture()
        val surfaceTexture = textureEntry.surfaceTexture()
        surfaceTexture.setDefaultBufferSize(640, 480)
        val textureId = textureEntry.id()

        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({

            val cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder().build()
            preview.setSurfaceProvider { request ->
                request.provideSurface(
                    Surface(surfaceTexture),
                    cameraExecutor
                ) {}
            }

            val analyzer = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()

            analyzer.setAnalyzer(cameraExecutor) { image ->
                try {
                    val yPlane = image.planes[0]
                    val yBuffer = yPlane.buffer

                    val width = image.width
                    val height = image.height
                    val rowStride = yPlane.rowStride
                    val pixelStride = yPlane.pixelStride

                    // Build grayscale Mat from Y plane accounting for row/pixel stride
                    val gray = Mat(height, width, CvType.CV_8UC1)

                    // Copy Y plane into gray Mat
                    if (pixelStride == 1 && rowStride == width) {
                        // Fast path: contiguous
                        val data = ByteArray(width * height)
                        yBuffer.get(data)
                        gray.put(0, 0, data)
                    } else {
                        // General path: handle strides
                        val all = ByteArray(yBuffer.remaining())
                        yBuffer.get(all)
                        val rowTmp = ByteArray(width)
                        for (y in 0 until height) {
                            if (pixelStride == 1) {
                                val srcPos = y * rowStride
                                System.arraycopy(all, srcPos, rowTmp, 0, width)
                            } else {
                                var srcPos = y * rowStride
                                var i = 0
                                while (i < width) {
                                    rowTmp[i] = all[srcPos]
                                    srcPos += pixelStride
                                    i++
                                }
                            }
                            gray.put(y, 0, rowTmp)
                        }
                    }

                    // Apply OpenCV processing
                    val blurred = Mat()
                    Imgproc.GaussianBlur(gray, blurred, Size(5.0, 5.0), 0.0)

                    val edges = Mat()
                    Imgproc.Canny(blurred, edges, 80.0, 150.0)

                    // Extract non-zero (edge) points, downsample to a reasonable amount
                    val pointsList = mutableListOf<List<Int>>()
                    val stepY = (height / 200).coerceAtLeast(1) // adapt density
                    val stepX = (width / 200).coerceAtLeast(1)
                    val row = ByteArray(width)
                    for (y in 0 until height step stepY) {
                        edges.get(y, 0, row)
                        var x = 0
                        while (x < width) {
                            if (row[x].toInt() != 0) {
                                pointsList.add(listOf(x, y))
                            }
                            x += stepX
                        }
                    }

                    runOnUiThread {
                        eventSink?.success(
                            mapOf(
                                "width" to width,
                                "height" to height,
                                "points" to pointsList
                            )
                        )
                    }

                    // Release Mats
                    gray.release(); blurred.release(); edges.release()

                } catch (e: Exception) {
                    Log.e("OpenCV", "Analyzer error", e)
                } finally {
                    image.close()
                }
            }

            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(
                this,
                CameraSelector.DEFAULT_BACK_CAMERA,
                preview,
                analyzer
            )

        }, ContextCompat.getMainExecutor(this))

        return textureId
    }

    private fun checkPermissions(): Boolean =
        ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED

    private fun processImage(): String {
        try { /* scope for early failures */ } catch (_: Throwable) {}

        // Ensure OpenCV initialized
        val ok = OpenCVLoader.initDebug()
        Log.d("OpenCV", "processImage.initDebug=$ok")
        logSink?.success("OpenCV init (processImage): $ok")

        // Create a test bitmap
        val bmp = Bitmap.createBitmap(500, 500, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.BLUE)
        val paint = Paint().apply { color = Color.YELLOW }
        canvas.drawRect(100f, 100f, 400f, 400f, paint)

        // OpenCV processing with diagnostics
        var src: Mat? = null
        var gray: Mat? = null
        var edges: Mat? = null
        try {
            src = Mat()
            Utils.bitmapToMat(bmp, src)
            gray = Mat()
            Imgproc.cvtColor(src, gray, Imgproc.COLOR_RGB2GRAY)
            Imgproc.GaussianBlur(gray, gray, Size(5.0, 5.0), 0.0)
            edges = Mat()
            Imgproc.Canny(gray, edges, 80.0, 100.0)

            val resultBmp = Bitmap.createBitmap(edges.cols(), edges.rows(), Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(edges, resultBmp)

            // Save to cache directory
            val file = File(cacheDir, "opencv_result.png")
            FileOutputStream(file).use { out ->
                resultBmp.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            return file.absolutePath
        } catch (t: Throwable) {
            Log.e("OpenCV", "processImage error", t)
            throw t
        } finally {
            try { src?.release() } catch (_: Throwable) {}
            try { gray?.release() } catch (_: Throwable) {}
            try { edges?.release() } catch (_: Throwable) {}
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }
}
