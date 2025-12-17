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

class MainActivity : FlutterActivity() {

    private val METHOD_CHANNEL = "com.example.camera/methods"
    private val EVENT_CHANNEL = "com.example.camera/events"

    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private var eventSink: EventChannel.EventSink? = null

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)

        MethodChannel(
            flutterEngine.dartExecutor.binaryMessenger,
            METHOD_CHANNEL
        ).setMethodCallHandler { call, result ->
            if (call.method == "startCamera") {
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
                    val plane = image.planes[0]
                    val buffer = plane.buffer

                    val width = image.width
                    val height = image.height
                    val rowStride = plane.rowStride
                    val pixelStride = plane.pixelStride

                    val cx = width / 2
                    val cy = height / 2
                    val radius = 50
                    val threshold = 30

                    val centerIndex = cy * rowStride + cx * pixelStride
                    val centerValue = buffer.get(centerIndex).toInt() and 0xFF

                    val points = mutableListOf<List<Int>>()

                    for (y in (cy - radius).coerceAtLeast(0)..(cy + radius).coerceAtMost(height - 1)) {
                        for (x in (cx - radius).coerceAtLeast(0)..(cx + radius).coerceAtMost(width - 1)) {

                            val dx = x - cx
                            val dy = y - cy
                            if (dx * dx + dy * dy > radius * radius) continue

                            val idx = y * rowStride + x * pixelStride
                            val v = buffer.get(idx).toInt() and 0xFF

                            if (abs(v - centerValue) < threshold) {
                                points.add(listOf(x, y))
                            }
                        }
                    }

                    runOnUiThread {
                        eventSink?.success(
                            mapOf(
                                "width" to width,
                                "height" to height,
                                "points" to points
                            )
                        )
                    }

                } catch (_: Exception) {
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

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }
}
