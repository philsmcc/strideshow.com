# WebRTC relies on JNI: native code looks these classes up by name, so they
# must survive any future shrinking even though minify is currently off.
-keep class org.webrtc.** { *; }
-keepclasseswithmembernames class org.webrtc.** {
    native <methods>;
}
-dontwarn org.webrtc.**

# OkHttp / Okio optional platform bits
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
