# ONNX Runtime uses JNI and reflection.
-keep class ai.onnxruntime.** { *; }
# PdfBox-Android loads resources and fonts reflectively.
-keep class com.tom_roush.** { *; }
-dontwarn com.tom_roush.**
-dontwarn com.gemalto.jp2.**
-dontwarn org.bouncycastle.**
# JNI bridges for native engines.
-keepclasseswithmembernames class * { native <methods>; }
-keep class app.carnet.nativeai.** { *; }
