# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile
# --- AudEBook ---

# TensorFlow Lite's native code looks up its Java classes by name. TFLite ships rules for most
# of them, but not for the support library, and the optional GPU delegate isn't bundled.
-keep class org.tensorflow.lite.** { *; }
-dontwarn org.tensorflow.lite.gpu.**

# Readium calls into its JavaScript bridge and parses JSON by field name
-keep class org.readium.** { *; }

# Optional dependencies these libraries reference but don't need at runtime: AutoValue is
# compile-time only (TFLite support), Conscrypt is an optional TLS provider (OkHttp), and
# joda-convert annotations are optional (Joda-Time)
-dontwarn com.google.auto.value.**
-dontwarn org.conscrypt.**
-dontwarn org.joda.convert.**
