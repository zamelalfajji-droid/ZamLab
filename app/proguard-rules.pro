# ZamLab Google Play Store Release ProGuard / R8 Configuration

# Preserve stack trace metadata for Play Console crash symbolication
-keepattributes SourceFile,LineNumberTable
-keepattributes Signature,InnerClasses,EnclosingMethod,*Annotation*

# ==============================================================================
# Native C++17 JNI Bridge (libzamlab_native.so)
# ==============================================================================
-keep class com.zamalfajji.zamlab.nativebridge.NativeBridge {
    *;
}
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}

# ==============================================================================
# OpenGL ES 2.0 Shaders, EGL Contexts & Renderers
# ==============================================================================
-keep class com.zamalfajji.zamlab.gl.** { *; }
-keep class android.opengl.** { *; }
-keep class javax.microedition.khronos.** { *; }
-dontwarn android.opengl.**

# ==============================================================================
# Jetpack Compose & AndroidX Lifecycle / ViewModels
# ==============================================================================
-keep class androidx.compose.runtime.** { *; }
-keep class androidx.compose.ui.** { *; }
-keepclassmembers class * extends androidx.lifecycle.ViewModel {
    <init>(...);
}
-keepclassmembers class * extends androidx.lifecycle.AndroidViewModel {
    <init>(android.app.Application);
}
-keepclassmembers class * {
    @androidx.compose.runtime.Composable <methods>;
}

# ==============================================================================
# JSON / Gson / Preset & State Serialization
# ==============================================================================
-keep class com.zamalfajji.zamlab.lut.UserPreset { *; }
-keep class com.zamalfajji.zamlab.lut.UserPresetStore { *; }
-keep class com.zamalfajji.zamlab.lut.XmpDevelopSettings { *; }
-keep class com.zamalfajji.zamlab.editor.GradingParams { *; }
-keep class com.zamalfajji.zamlab.editor.GradingParams$* { *; }
-keep class com.google.gson.** { *; }
-keepclassmembers class * {
    @com.google.gson.annotations.SerializedName <fields>;
}
-dontwarn com.google.gson.**
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod
-keep,includedescriptorclasses class kotlinx.serialization.** { *; }
-dontwarn kotlinx.serialization.**

# ==============================================================================
# ONNX Runtime Android (ai.onnxruntime.** + XNNPACK/NNAPI native JNI)
# ==============================================================================
-keep class ai.onnxruntime.** { *; }
-keepclassmembers class ai.onnxruntime.** { *; }
-dontwarn ai.onnxruntime.**

# ==============================================================================
# MediaPipe (defensive keep rules if native/vision tasks are linked)
# ==============================================================================
-keep class com.google.mediapipe.** { *; }
-keep class com.google.protobuf.** { *; }
-dontwarn com.google.mediapipe.**
-dontwarn com.google.protobuf.**
