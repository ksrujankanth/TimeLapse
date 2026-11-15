cmake_minimum_required(VERSION 3.22.1)

project("timelapse")

# 1. Define the Path to OpenCV JNI Libraries
# We anchor this to CMAKE_CURRENT_SOURCE_DIR (your 'cpp' folder) 
# and move up to the project root to find the 'opencv' module.
set(OPENCV_LIB_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../../../opencv/src/main/jniLibs/${ANDROID_ABI}")

# 2. Add the main OpenCV shared library as an IMPORTED target
add_library(opencv_java4 SHARED IMPORTED)
set_target_properties(opencv_java4 PROPERTIES 
    IMPORTED_LOCATION "${OPENCV_LIB_DIR}/libopencv_java4.so"
)

# 3. Define your native timelapse library
add_library(timelapse SHARED
    timelapse.cpp
    mfsr_engine.cpp
    mfsr_jni.cpp
)

# 4. Mandatory 16KB Page Alignment for Pixel 8 Pro (Tensor G3)
# This prevents 'dcn' garbage values and memory crashes on Android 15/16.
set_target_properties(timelapse PROPERTIES 
    LINK_OPTIONS "-Wl,-z,max-page-size=16384;-Wl,-z,common-page-size=16384"
)

# 5. Add Header Include Paths
include_directories("${CMAKE_CURRENT_SOURCE_DIR}/../../../../opencv/src/main/cpp/include")

# 6. Link Libraries
target_link_libraries(
    timelapse
    opencv_java4  # Use the imported target defined above
    log           # For Android logging
    atomic        # For native atomic operations
    m             # Math library
)