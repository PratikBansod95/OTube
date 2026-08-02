# OTube ProGuard / R8 rules

# Keep line numbers for crash reports
-keepattributes SourceFile,LineNumberTable,InnerClasses,EnclosingMethod,Signature,*Annotation*
-renamesourcefileattribute SourceFile

# Native adblock JNI — method names must match Rust JNI exports
-keep class com.lightshield.adblock.NativeAdblock { *; }
-keepclassmembers class com.lightshield.adblock.NativeAdblock {
    native <methods>;
    static *;
}

# WebView JS bridges (if added later)
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# Kotlin
-keep class kotlin.Metadata { *; }
-dontwarn kotlin.**
-dontwarn kotlin.reflect.**

# Coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keep class kotlinx.coroutines.** { *; }
-dontwarn kotlinx.coroutines.**

# AndroidX / Compose consumer rules are pulled in automatically via AAR.
# Extra Compose keep for reflection-based preview / runtime safety:
-keep class androidx.compose.runtime.** { *; }
