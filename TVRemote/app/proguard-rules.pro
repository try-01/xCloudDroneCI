-keep class com.tvremote.app.MainActivity$JavascriptBridge { *; }
-keepclassmembers class com.tvremote.app.MainActivity$JavascriptBridge {
    @android.webkit.JavascriptInterface <methods>;
}
