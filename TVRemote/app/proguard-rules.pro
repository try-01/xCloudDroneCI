# TV Remote - ProGuard/R8 Rules

# Keep JavascriptInterface methods
-keep class com.tvremote.app.MainActivity$JavascriptBridge { *; }
-keepclassmembers class com.tvremote.app.MainActivity$JavascriptBridge {
    @android.webkit.JavascriptInterface <methods>;
}

# Keep WebSocket library
-keep class org.java_websocket.** { *; }
-dontwarn org.java_websocket.**

# Keep JSON parsing
-keep class org.json.** { *; }
-dontwarn org.json.**

# Obfuscate everything else
-repackageclasses ''
-allowaccessmodification
-optimizationpasses 5

# Remove logging in release
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}

# Keep line numbers for crash reports
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
