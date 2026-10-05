# WebRTC is reached through JNI; keep its public API intact.
-keep class org.webrtc.** { *; }
-dontwarn org.webrtc.**

# kotlinx.serialization: keep the serializers it generates.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class app.latchway.**$$serializer { *; }
-keepclassmembers class app.latchway.** { *** Companion; }
-keepclasseswithmembers class app.latchway.** { kotlinx.serialization.KSerializer serializer(...); }

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
