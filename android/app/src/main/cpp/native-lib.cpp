#include <jni.h>
#include <opencv2/core.hpp>
#include <opencv2/imgproc.hpp>
#include <android/log.h>

#define TAG "NativeDetector"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_example_flutter_1opencv_1lab_NativeDetector_detectLine(
        JNIEnv* env,
        jobject,
        jobject yBuffer,
        jint width,
        jint height,
        jint rowStride
) {
    // ✅ Zero-copy доступ к памяти ByteBuffer
    uint8_t* y = (uint8_t*) env->GetDirectBufferAddress(yBuffer);
    
    if (!y) {
        LOGD("Error: Buffer is null");
        return JNI_FALSE;
    }

    // Создаем Mat, используя указатель на память из Java (без копирования)
    // Важно использовать rowStride, так как у камеры может быть padding
    cv::Mat gray(height, width, CV_8UC1, y, rowStride);

    // Пример логики: Проверка средней яркости строки (имитация линии)
    // Чтобы было супер быстро, работаем с уменьшенной версией или ROI
    cv::Mat row;
    cv::reduce(gray, row, 0, cv::REDUCE_AVG); // Схлопываем в одну строку

    cv::threshold(row, row, 160, 255, cv::THRESH_BINARY);

    int count = cv::countNonZero(row);
    
    // Если > 30% пикселей белые - считаем что линия есть
    return count > (width * 0.3);
}
