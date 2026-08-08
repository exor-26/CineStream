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

# Preserve app activities referenced by the manifest and intent routing.
-keep class com.example.cinestream.** extends android.app.Activity { *; }
-keep class com.example.cinestream.** extends androidx.appcompat.app.AppCompatActivity { *; }

# Keep the FFmpeg metadata retriever entry points that bridge into native code.
-keep class wseemann.media.FFmpegMediaMetadataRetriever { *; }
-keep class wseemann.media.Metadata { *; }

# Media3 loads native video extension renderers by reflection. Keep the concrete classes
# so R8 cannot remove or rename the fallback entry points in release builds.
-keep class androidx.media3.decoder.av1.Libdav1dVideoRenderer { *; }
-keep class androidx.media3.decoder.vp9.LibvpxVideoRenderer { *; }

