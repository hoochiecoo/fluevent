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
