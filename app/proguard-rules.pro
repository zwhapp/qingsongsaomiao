# --- 网络库（仅 TFLite support 等间接依赖可能引用）---
-dontwarn okhttp3.**
-dontwarn okio.**

# --- OpenCV（官方 AAR）---
-keep class org.opencv.** { *; }
-dontwarn org.opencv.**

# --- TensorFlow Lite ---
-keep class org.tensorflow.lite.** { *; }
-keep class org.tensorflow.lite.support.** { *; }
-dontwarn org.tensorflow.lite.**

# --- ML Kit 文字识别 ---
# ML Kit 通过反射加载 bundled 模型与内部的 native 桥接类，
# 混淆掉这些包会导致运行时 "Recognizer not available" 或直接崩溃。
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.internal.mlkit_vision_text** { *; }
-keep class com.google.android.gms.internal.mlkit_vision_text_common** { *; }
-keep class com.google.android.gms.internal.mlkit_vision_common** { *; }
-dontwarn com.google.mlkit.**
-dontwarn com.google.android.gms.**

# ML Kit 依赖的 protobuf-lite 由反射访问字段
-keep class com.google.protobuf.** { *; }
-dontwarn com.google.protobuf.**

# --- Room ---
-keep class * extends androidx.room.RoomDatabase
-dontwarn androidx.room.paging.**

# --- Kotlin ---
-dontwarn kotlin.**

# 保留数据模型（Room 实体）
-keep class com.openscan.docscan.data.** { *; }

# 保留 Compose 相关（AGP 会自动处理，这里兜底）
-keep class androidx.compose.** { *; }
-dontwarn androidx.compose.**

# --- release 包剥离调试日志（2026-10-24 合规整改）---
# 源码里只有 5 处 Log.e/i（OpenCV 初始化、OCR 消歧、模型加载状态），
# 不含任何用户数据；这里把 verbose/debug/info 在 release 包中整体删掉，
# 保留 warn/error 便于线上问题排查。
-assumenosideeffects class android.util.Log {
    public static *** v(...);
    public static *** d(...);
    public static *** i(...);
}
