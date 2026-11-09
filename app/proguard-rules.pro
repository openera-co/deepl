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

# Apache POI (old .doc/.xls files): desktop-only references that are never used on Android.
-dontwarn java.awt.**
-dontwarn javax.imageio.**
-dontwarn javax.xml.stream.**
-dontwarn javax.swing.**
-dontwarn aQute.bnd.annotation.**
-dontwarn org.osgi.framework.**
-dontwarn org.apache.logging.log4j.**
# POI builds Excel/Word records by reflection.
-keep class org.apache.poi.** { *; }
