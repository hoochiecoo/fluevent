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

echo "✅ OpenCV Setup Complete"}},{