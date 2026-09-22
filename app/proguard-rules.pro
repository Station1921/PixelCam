# OpenCV：JNI 绑定依赖反射，必须整体保留，否则 release 包一运行就 UnsatisfiedLinkError
-keep class org.opencv.** { *; }
-dontwarn org.opencv.**
-keepclasseswithmembernames class * {
    native <methods>;
}

# ML Kit 人脸检测
-keep class com.google.mlkit.** { *; }
-dontwarn com.google.mlkit.**

# Apache Commons Net（FTP）
-keep class org.apache.commons.net.** { *; }
-dontwarn org.apache.commons.net.**

# 数据类：预设序列化时可能用到
-keepclassmembers class com.station1921.pixelcam.** {
    public <init>(...);
}
