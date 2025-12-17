package com.example.camera_starter

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.util.Size
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
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc
import java.nio.ByteBuffer
import java.util.concurrent.Executors

class MainActivity : FlutterActivity() {
    private val METHOD_CHANNEL = "com.example.camera/methods"
    private val EVENT_CHANNEL = "com.example.camera/events"
    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private var eventSink: EventChannel.EventSink? = null

    // --- Tunable Parameters for OpenCV ---
    @Volatile private var cannyThreshold1 = 85.0
    @Volatile private var cannyThreshold2 = 255.0
    @Volatile private var houghThreshold = 50
    @Volatile private var houghMinLineLength = 50.0
    @Volatile private var houghMaxLineGap = 10.0
    @Volatile private var blurKernelSize = 5.0
    // --- End of Parameters ---

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)

        // Load OpenCV
        OpenCVLoader.initDebug()

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
                "updateSettings" -> {
                    cannyThreshold1 = call.argument<Double>("cannyThreshold1") ?: 85.0
                    cannyThreshold2 = call.argument<Double>("cannyThreshold2") ?: 255.0
                    houghThreshold = call.argument<Int>("houghThreshold") ?: 50
                    houghMinLineLength = call.argument<Double>("houghMinLineLength") ?: 50.0
                    houghMaxLineGap = call.argument<Double>("houghMaxLineGap") ?: 10.0
                    blurKernelSize = call.argument<Double>("blurKernelSize") ?: 5.0
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
        val surfaceTexture = textureEntry.surfaceTexture()
        val resolution = Size(640, 480)
        surfaceTexture.setDefaultBufferSize(resolution.width, resolution.height)
        val textureId = textureEntry.id()

        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            val preview = Preview.Builder().setTargetResolution(resolution).build()

            preview.setSurfaceProvider { request ->
                val surface = Surface(surfaceTexture)
                request.provideSurface(surface, cameraExecutor) {}
            }

            val imageAnalyzer = ImageAnalysis.Builder()
                .setTargetResolution(resolution)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()

            imageAnalyzer.setAnalyzer(cameraExecutor) { imageProxy ->
                processImageProxyWithOpenCV(imageProxy)
            }

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageAnalyzer)
            } catch (e: Exception) {
                // Handle exceptions
            }

        }, ContextCompat.getMainExecutor(this))

        return textureId
    }

    private fun processImageProxyWithOpenCV(imageProxy: ImageProxy) {
        if (imageProxy.format != ImageFormat.YUV_420_888) {
            imageProxy.close()
            return
        }

        val yBuffer = imageProxy.planes[0].buffer; val uBuffer = imageProxy.planes[1].buffer; val vBuffer = imageProxy.planes[2].buffer
        val ySize = yBuffer.remaining(); val uSize = uBuffer.remaining(); val vSize = vBuffer.remaining()
        val nv21 = ByteArray(ySize + uSize + vSize)
        yBuffer.get(nv21, 0, ySize); vBuffer.get(nv21, ySize, vSize); uBuffer.get(nv21, ySize + vSize, uSize)

        val yuvMat = Mat(imageProxy.height + imageProxy.height / 2, imageProxy.width, CvType.CV_8UC1)
        yuvMat.put(0, 0, nv21)
        val rgbaMat = Mat()
        Imgproc.cvtColor(yuvMat, rgbaMat, Imgproc.COLOR_YUV2RGBA_NV21)
        
        val grayMat = Mat(); Imgproc.cvtColor(rgbaMat, grayMat, Imgproc.COLOR_RGBA2GRAY)
        
        val kernelSize = blurKernelSize.let { if (it.toInt() % 2 == 0) it + 1 else it }.toInt()
        val blurredMat = Mat(); Imgproc.GaussianBlur(grayMat, blurredMat, org.opencv.core.Size(kernelSize.toDouble(), kernelSize.toDouble()), 0.0)
        
        val edgesMat = Mat(); Imgproc.Canny(blurredMat, edgesMat, cannyThreshold1, cannyThreshold2)
        
        val lines = Mat(); Imgproc.HoughLinesP(edgesMat, lines, 1.0, Math.PI / 180, houghThreshold, houghMinLineLength, houghMaxLineGap)
        
        val lineCount = lines.rows()
        runOnUiThread { eventSink?.success("Линий найдено: $lineCount") }

        yuvMat.release(); rgbaMat.release(); grayMat.release(); blurredMat.release(); edgesMat.release(); lines.release()
        imageProxy.close()
    }

    private fun checkPermissions() = ContextCompat.checkSelfPermission(baseContext, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }
}
