# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

-keepclassmembers class com.dan.timelapse.utils.Settings {
   public *;
}

# OpenCV native methods and classes
-keep class org.opencv.** { *; }
-keepclassmembers class org.opencv.** { *; }

# Keep our own JNI bridge methods
-keep class com.dan.timelapse.images.ImageTools** { *; }
-keepclassmembers class com.dan.timelapse.images.ImageTools** { *; }

# Keep MediaCodec and other hardware related classes
-keep class android.media.** { *; }
-keepclassmembers class android.media.** { *; }
