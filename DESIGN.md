# Project TimeLapse: Technical Detail

## 1. Synthesized Vision
**TimeLapse** is a professional-grade image processing application for Android designed for photographers and enthusiasts. It bridges the gap between mobile convenience and heavy computational photography.
- **Core Goal**: Provide high-performance "stacking" effects (Light Trails, Motion Extraction, Median Stacking/Noise Reduction) on mobile devices.
- **Architecture**: A **Pipe-and-Filter** pattern.
    - **Input**: `FramesInput` (Video/Images) abstracts the source.
    - **Pipe**: A chain of `FramesFilter` objects (e.g., `Scale` -> `Align` -> `Effect`).
    - **Consumer**: `FramesConsumer` (usually `VideoWriter` or `ImageWriter`) handles the final encoding.
    - **Performance**: Heavy pixel-math is offloaded to C++ via **JNI (`libtimelapse.so`)** and **OpenCV**.

## 2. Technical Constraints & Versions
- **Android OS**: Min SDK 29 (Android 10) | Target SDK 34 (Android 14).
- **Core Languages**: Kotlin (1.9.23), C++ (standard 17 via NDK).
- **Build System**: Gradle 8.4.2, AGP 8.4.2.
- **Native Environment**:
    - **OpenCV**: 4.x (Latest pre-built for `arm64-v8a` and `armeabi-v7a`).
    - **NDK**: 26.1.10909125.
    - **CMake**: 3.22.1.
- **Page Size Alignment**: **16KB page boundaries** are required for all `.so` libraries.
    - Linker Flags: `-Wl,-z,max-page-size=16384`.
    - APK Packaging: `useLegacyPackaging = true`.
- **Dependencies**:
    - `androidx.core:core-ktx:1.13.1`
    - `androidx.appcompat:appcompat:1.6.1`
    - `com.google.android.material:material:1.12.0`
    - `org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.0`
    - `androidx.work:work-runtime-ktx:2.9.0`
    - `com.google.code.gson:gson:2.10.1`

## 3. The 'Ground Truth' (Proven Functionality)
Before the recent week of regressions, the following was the stable baseline:
- **JNI Architecture**: Native bridge via `System.loadLibrary("opencv_java4")` and `System.loadLibrary("timelapse")`.
- **Working Algorithms**:
    - **LightestPixels**: Maximum intensity composite (light trails).
    - **DarkestPixels**: Minimum intensity composite (dark trails).
    - **Median Stack**: Median pixel selection across a buffer (noise reduction).
    - **Motion Extraction**: Sequential frame differencing.
- **Proven Video Input**: OpenCV `VideoCapture` using raw File Descriptors (`detachFd()`) via `":$fd"`.
- **Proven Image Input**: `BitmapFactory` with `inPreferredConfig = Bitmap.Config.ARGB_8888` and `inHardwareConfig = false`.
- **ABIs**: Flawless execution on `arm64-v8a` (Pixel 8 Pro) and `armeabi-v7a`.

## 4. Architectural Deep-Dive
### **The Frame Pipeline**
The processing engine uses a chain of responsibility:
1. **`FramesInput`**: Loads data.
2. **`ScaleFramesFilter`**: Resizes to target resolution (e.g., 4K, 1080p).
3. **`AlignFramesFilter`**: (Optional) Aligns frames using OpenCV feature detection.
4. **`Effect Filter`**: (e.g., `AddFramesFilter`, `MedianFramesFilter`) Applies the stacking logic.
5. **`FramesConsumer`**: Encodes the resulting `Mat` into a video or image file.

### **Media Access & Permissions**
- **Storage Access Framework (SAF)**: Always use `content://` URIs.
- **Persistability**: Request `takePersistableUriPermission` on selection.
- **FD Bridge**: Native code accesses SAF media only via raw File Descriptors.

## 5. Fresh Start Spec (The Project Constitution)
*Give this section to a new AI session to ensure zero "context poisoning".*

### **Identity**
You are developing a high-performance video processing engine for Android. The goal is to provide photography effects that normally require a desktop (stacking).

### **Hard Constraints**
- **Native Libraries**: Must be 16KB page-aligned. Linker flags `-Wl,-z,max-page-size=16384` are non-negotiable.
- **Bitmap Handling**: OpenCV **cannot** read Hardware Bitmaps. Always force software decoding (`Bitmap.Config.ARGB_8888`).
- **File System**: No direct file paths. Use `ParcelFileDescriptor` and `detachFd()`.
- **Concurrency**: Use `WorkManager` for encoding to prevent OS process killing during long renders.

### **Priority Technical Debt**
- Ensure `VideoFramesInput` uses a hybrid approach: native `VideoCapture(fd)` for speed, falling back to `MediaMetadataRetriever` if the codec fails.
- Synchronize the `RangeSlider` UI with the frame extraction logic to allow start/end trimming.
- Implement the `Mode Stack` algorithm in C++ for performance.





Here is the breakdown of the scenarios:

Cumulative "Endless" Bucket
   (Lightest, Darkest, Add, Average)
   • Logic: True Iterative Calculation. Each new frame is merged into a single "Running State" frame.
   • RAM Usage: Lowest (1 Frame).
   • Processing Time: Lowest (Fastest).
   • Crash/OOM Risk: Zero. It never stores a list of frames.
   • 1080p vs 4K: Same logic. 4K just takes slightly longer per frame due to pixel count.

Windowed "Slider" Bucket
   (Lightest, Darkest, Add, Average)
   • Logic: True Sliding Window.
   • RAM Usage: High/Variable. It stores [Slider Value] number of full-resolution frames in RAM.
   • Processing Time: Moderate. It must re-calculate the result from the stored list for every new frame.
   • Crash/OOM Risk: Medium/High. If the user sets the slider to 100 on a 4K video, the app will likely OOM on a mobile device.
   • 1080p vs 4K: Logic is the same, but the memory pressure is $\sim4\times$ higher in 4K.


Median Stack (Windowed)
   • Logic: True Median. It clones every frame in the sliding window and sorts pixel values.
   • RAM Usage: Highest. Storing a window of 4K frames is extremely memory-intensive.
   • Processing Time: High. Sorting pixels across multiple frames is computationally heavy.
   • Crash/OOM Risk: Extreme. Even a window size of 10-20 frames in 4K can crash the app.
   • 1080p vs 4K: Logic is the same, but OOM risk is significantly higher in 4K.

Endless Median Stack (Hybrid Logic)
   • Scenario A: 1080p (Standard)
   ◦ Logic: Running Histogram (Binning). It stores a "count" for every intensity (0-255) per pixel.
   ◦ RAM Usage: High but Constant. Uses about 600-800MB (fixed).
   ◦ Processing Time: Moderate.
   ◦ Result: True Median Approximation. Mathematically very close to a true median.
   ◦ Crash/OOM Risk: Low. RAM is allocated once at the start.
   • Scenario B: 4K (High Quality)
   ◦ Logic: Cumulative Tiling (Strip Processing).
   ◦ RAM Usage: Low. It offloads frame data to the Disk (.tmp file) and only processes small strips in RAM.
   ◦ Processing Time: Very High (Slow). Requires writing/reading the entire video to disk and multiple passes.
   ◦ Result: 100% Mathematically True Median.
   ◦ Crash/OOM Risk: Zero. It is designed specifically to never OOM, no matter how many frames or how high the resolution.

Comparison Matrix 

| Filter Type            | Result Quality    | RAM Usage    | Speed    | 4K Reliability |
| :--------------------- |:------------------|:-------------|:---------|:---------------| 
| Endless (Basic)        | True Cumulative   | Lowest       | Fastest  | Perfect        | 
| Windowed (Basic)       | True Moving       | High         | Moderate | Risky          | 
| Median (Windowed)      | True Moving       | Highest      | Slowest  | Poor           | 
| Endless Median (1080p) | Approximation     | High (Fixed) | Moderate | Good           | 
| Endless Median (4K)    | True Cumulative   | Low          | Slow     | Perfect        |

   



