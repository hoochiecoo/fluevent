package com.example.flutter_opencv_lab

import java.nio.ByteBuffer

object NativeDetector {
    // Загрузка нашей библиотеки.
    // opencv_core и opencv_imgproc подтянутся автоматически как зависимости
    init {
        System.loadLibrary("native-lib")
    }

    external fun detectLine(
        yBuffer: ByteBuffer,
        width: Int,
        height: Int,
        rowStride: Int
    ): Boolean
}
