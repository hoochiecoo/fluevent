Вот полная структура и содержание файлов для создания минимального, высокопроизводительного Flutter проекта, готового к сборке на GitHub CI.

Имя проекта (Package Name) я выбрал: `com.example.flutter_opencv_lab`.
**Важно:** Если вы меняете имя пакета, не забудьте изменить имя функции в C++ (`Java_com_example_...`).

### 📂 1. Структура проекта (File Tree)

```text
flutter_opencv_lab/
├── .github/
│   └── workflows/
│       └── build_android.yml    <-- CI конфиг
├── android/
│   ├── app/
│   │   ├── build.gradle         <-- Настройка NDK и CMake
│   │   ├── src/
│   │   │   ├── main/
│   │   │   │   ├── AndroidManifest.xml
│   │   │   │   ├── cpp/
│   │   │   │   │   ├── include/     <-- Сюда кладем .hpp заголовки OpenCV
│   │   │   │   │   │   └── opencv2/ ...
│   │   │   │   │   ├── CMakeLists.txt
│   │   │   │   │   └── native-lib.cpp
│   │   │   │   ├── jniLibs/
│   │   │   │   │   └── arm64-v8a/   <-- Сюда кладем .so библиотеки
│   │   │   │   │       ├── libopencv_core.so
│   │   │   │   │       └── libopencv_imgproc.so
│   │   │   │   ├── kotlin/com/example/flutter_opencv_lab/
│   │   │   │   │   ├── MainActivity.kt
│   │   │   │   │   ├── NativeDetector.kt
│   │   │   │   │   └── CameraHelper.kt
│   │   │   │   └── res/ ...
│   └── build.gradle
├── lib/
│   └── main.dart                <-- Flutter UI
├── pubspec.yaml
└── setup_opencv.sh              <-- Скрипт для скачивания OpenCV (для CI)
```

---

### 📄 2. Файлы настройки Android

#### `android/app/build.gradle`
Здесь мы жестко задаем архитектуру `arm64-v8a` и C++17.

```groovy
plugins {
    id "com.android.application"
    id "kotlin-android"
    id "dev.flutter.flutter-gradle-plugin"
}

def localProperties = new Properties()
def localPropertiesFile = rootProject.file('local.properties')
if (localPropertiesFile.exists()) {
    localPropertiesFile.withReader('UTF-8') { reader ->
        localProperties.load(reader)
    }
}

android {
    namespace "com.example.flutter_opencv_lab"
    compileSdkVersion 34
    ndkVersion "25.1.8937393" // Укажите установленную версию NDK

    defaultConfig {
        applicationId "com.example.flutter_opencv_lab"
        minSdkVersion 24 // CameraX требует минимум 21, лучше 24+
        targetSdkVersion 34
        versionCode flutterVersionCode.toInteger()
        versionName flutterVersionName

        // ✅ Ограничиваем архитектуру только arm64
        ndk {
            abiFilters "arm64-v8a"
        }
        
        // ✅ C++ флаги
        externalNativeBuild {
            cmake {
                cppFlags "-std=c++17 -fvisibility=hidden"
                arguments "-DANDROID_STL=c++_shared"
            }
        }
    }

    // ✅ Подключение CMake
    externalNativeBuild {
        cmake {
            path "src/main/cpp/CMakeLists.txt"
            version "3.22.1"
        }
    }

    buildTypes {
        release {
            signingConfig signingConfigs.debug
            minifyEnabled true // R8 оптимизация
            shrinkResources true
            proguardFiles getDefaultProguardFile('proguard-android-optimize.txt'), 'proguard-rules.pro'
        }
    }
    
    // CameraX зависимости
    dependencies {
        def camerax_version = "1.3.1"
        implementation "androidx.camera:camera-core:${camerax_version}"
        implementation "androidx.camera:camera-camera2:${camerax_version}"
        implementation "androidx.camera:camera-lifecycle:${camerax_version}"
        implementation "androidx.camera:camera-view:${camerax_version}"
    }
}

flutter {
    source '../..'
}
```

#### `android/app/src/main/AndroidManifest.xml`
Необходимые права.

```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <uses-permission android:name="android.permission.CAMERA" />
    <uses-feature android:name="android.hardware.camera.any" />

    <application
        android:label="OpenCV Native"
        android:name="${applicationName}"
        android:icon="@mipmap/ic_launcher">
        <activity
            android:name=".MainActivity"
            android:exported="true"
            android:launchMode="singleTop"
            android:theme="@style/LaunchTheme"
            android:configChanges="orientation|keyboardHidden|keyboard|screenSize|smallestScreenSize|locale|layoutDirection|fontScale|screenLayout|density|uiMode"
            android:hardwareAccelerated="true"
            android:windowSoftInputMode="adjustResize">
            <meta-data
              android:name="io.flutter.embedding.android.NormalTheme"
              android:resource="@style/NormalTheme"
              />
            <intent-filter>
                <action android:name="android.intent.action.MAIN"/>
                <category android:name="android.intent.category.LAUNCHER"/>
            </intent-filter>
        </activity>
    </application>
</manifest>
```

---

### 🏛 3. Native Layer (C++ / CMake)

#### `android/app/src/main/cpp/CMakeLists.txt`
Обратите внимание на строку `include_directories`.

```cmake
cmake_minimum_required(VERSION 3.22)
project(native_detector)

# ✅ Указываем путь к заголовкам (.hpp)
include_directories(${CMAKE_SOURCE_DIR}/include)

add_library(native-lib SHARED native-lib.cpp)

# ✅ Подключаем prebuilt библиотеки OpenCV
add_library(opencv_core SHARED IMPORTED)
set_target_properties(opencv_core PROPERTIES
    IMPORTED_LOCATION
    ${CMAKE_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libopencv_core.so)

add_library(opencv_imgproc SHARED IMPORTED)
set_target_properties(opencv_imgproc PROPERTIES
    IMPORTED_LOCATION
    ${CMAKE_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libopencv_imgproc.so)

find_library(log-lib log)

target_link_libraries(
    native-lib
    opencv_core
    opencv_imgproc
    ${log-lib}
)
```

#### `android/app/src/main/cpp/native-lib.cpp`
Имя функции изменено под package name: `com.example.flutter_opencv_lab`.

```cpp
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
```

---

### 🤖 4. Kotlin Layer

#### `android/app/src/main/kotlin/com/example/flutter_opencv_lab/NativeDetector.kt`

```kotlin
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
```

#### `android/app/src/main/kotlin/com/example/flutter_opencv_lab/CameraHelper.kt`
Здесь вся логика CameraX.

```kotlin
package com.example.flutter_opencv_lab

import android.content.Context
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.util.concurrent.Executors

class CameraHelper(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val onDetectionResult: (Boolean) -> Unit
) {
    private val executor = Executors.newSingleThreadExecutor()

    fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)

        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            
            // ✅ Только 640x480 для скорости (достаточно для CV)
            val targetSize = Size(640, 480)

            val preview = Preview.Builder().build()

            val imageAnalysis = ImageAnalysis.Builder()
                .setTargetResolution(targetSize)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()

            imageAnalysis.setAnalyzer(executor) { image ->
                // ✅ YUV_420_888 -> Берем только Y плоскость (Gray)
                val yPlane = image.planes[0]
                
                try {
                    val detected = NativeDetector.detectLine(
                        yPlane.buffer,
                        image.width,
                        image.height,
                        yPlane.rowStride
                    )
                    onDetectionResult(detected)
                } catch (e: Exception) {
                    e.printStackTrace()
                } finally {
                    image.close()
                }
            }

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview, // Можно убрать preview, если экран не нужен
                    imageAnalysis
                )
            } catch (exc: Exception) {
                exc.printStackTrace()
            }

        }, ContextCompat.getMainExecutor(context))
    }
}
```

#### `android/app/src/main/kotlin/com/example/flutter_opencv_lab/MainActivity.kt`
Связь с Flutter.

```kotlin
package com.example.flutter_opencv_lab

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.EventChannel

class MainActivity : FlutterActivity() {
    private val EVENT_CHANNEL = "com.example.flutter_opencv_lab/detection"
    private var eventSink: EventChannel.EventSink? = null
    private lateinit var cameraHelper: CameraHelper
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)

        EventChannel(flutterEngine.dartExecutor.binaryMessenger, EVENT_CHANNEL)
            .setStreamHandler(object : EventChannel.StreamHandler {
                override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
                    eventSink = events
                    checkPermissionAndStart()
                }

                override fun onCancel(arguments: Any?) {
                    eventSink = null
                }
            })
    }

    private fun checkPermissionAndStart() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.CAMERA), 101
            )
        } else {
            startCamera()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 101 && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        }
    }

    private fun startCamera() {
        cameraHelper = CameraHelper(this, this) { detected ->
            // Отправляем результат во Flutter (обязательно на UI потоке)
            mainHandler.post {
                eventSink?.success(detected)
            }
        }
        cameraHelper.startCamera()
    }
}
```

---

### 🦋 5. Flutter (Dart)

#### `lib/main.dart`

```dart
import 'dart:async';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

void main() {
  runApp(const MyApp());
}

class MyApp extends StatefulWidget {
  const MyApp({super.key});

  @override
  State<MyApp> createState() => _MyAppState();
}

class _MyAppState extends State<MyApp> {
  // Канал событий должен совпадать с Kotlin
  static const eventChannel = EventChannel('com.example.flutter_opencv_lab/detection');
  
  bool _isLineDetected = false;

  @override
  void initState() {
    super.initState();
    // Слушаем поток данных от C++
    eventChannel.receiveBroadcastStream().listen((event) {
      if (event is bool) {
        setState(() {
          _isLineDetected = event;
        });
      }
    }, onError: (dynamic error) {
      debugPrint('Error: ${error.message}');
    });
  }

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      home: Scaffold(
        backgroundColor: _isLineDetected ? Colors.green : Colors.red,
        body: Center(
          child: Column(
            mainAxisAlignment: MainAxisAlignment.center,
            children: [
              Text(
                _isLineDetected ? "LINE DETECTED!" : "NO LINE",
                style: const TextStyle(
                  fontSize: 32, 
                  color: Colors.white, 
                  fontWeight: FontWeight.bold
                ),
              ),
              const SizedBox(height: 20),
              const Text(
                "Native C++ OpenCV Processing",
                style: TextStyle(color: Colors.white70),
              )
            ],
          ),
        ),
      ),
    );
  }
}
```

---

### 🚀 6. Подготовка к GitHub CI

GitHub Actions не хранит большие бинарные файлы. Вам нужен скрипт, который скачает заголовки и библиотеки OpenCV и разложит их по папкам перед сборкой.

#### `setup_opencv.sh` (В корне проекта)
Этот скрипт скачивает Android Pack OpenCV (версия 4.8.0), распаковывает и копирует только нужные файлы.

```bash
#!/bin/bash
set -e

OPENCV_VER="4.8.0"
OPENCV_ZIP="opencv-${OPENCV_VER}-android-sdk.zip"
URL="https://github.com/opencv/opencv/releases/download/${OPENCV_VER}/${OPENCV_ZIP}"

echo "Downloading OpenCV..."
curl -L -o ${OPENCV_ZIP} ${URL}

echo "Unzipping..."
unzip -q ${OPENCV_ZIP}

echo "Setting up Directories..."
mkdir -p android/app/src/main/cpp/include
mkdir -p android/app/src/main/jniLibs/arm64-v8a

echo "Copying Headers..."
# Копируем заголовки
cp -r OpenCV-android-sdk/sdk/native/jni/include/opencv2 android/app/src/main/cpp/include/

echo "Copying Libs (arm64-v8a)..."
# Копируем только нужные .so
cp OpenCV-android-sdk/sdk/native/libs/arm64-v8a/libopencv_core.so android/app/src/main/jniLibs/arm64-v8a/
cp OpenCV-android-sdk/sdk/native/libs/arm64-v8a/libopencv_imgproc.so android/app/src/main/jniLibs/arm64-v8a/

echo "Cleanup..."
rm -rf OpenCV-android-sdk
rm ${OPENCV_ZIP}

echo "✅ OpenCV Setup Complete"
```

#### `.github/workflows/build_android.yml`

```yaml
name: Build Android App

on:
  push:
    branches: [ "main" ]
  pull_request:
    branches: [ "main" ]

jobs:
  build:
    runs-on: ubuntu-latest

    steps:
      - uses: actions/checkout@v4

      - name: Set up Java
        uses: actions/setup-java@v4
        with:
          distribution: 'zulu'
          java-version: '17'

      - name: Setup Flutter
        uses: subosito/flutter-action@v2
        with:
          channel: 'stable'

      - name: Setup OpenCV Libs
        run: chmod +x setup_opencv.sh && ./setup_opencv.sh

      - name: Get Dependencies
        run: flutter pub get

      - name: Build APK (Release)
        run: flutter build apk --release --no-shrink

      - name: Upload APK
        uses: actions/upload-artifact@v4
        with:
          name: app-release
          path: build/app/outputs/flutter-apk/app-release.apk
```

### Итог
1.  **Архитектура:** Максимально плоская и быстрая. Камера пишет в буфер -> C++ читает адрес памяти -> OpenCV считает -> Bool летит во Flutter.
2.  **CI/CD:** Скрипт `setup_opencv.sh` решает проблему отсутствия библиотек в репозитории.
3.  **Безопасность Play Store:** Используются только стандартные API и публичные библиотеки без динамической подгрузки кода.
4.  **Размер:** В финальный APK попадут только `libopencv_core.so`, `libopencv_imgproc.so` и ваша `libnative-lib.so`. Никакого Java-кода OpenCV SDK.