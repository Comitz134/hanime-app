# The JS bridge is reached by name from JavaScript through
# @JavascriptInterface. R8 cannot see those calls, so without these rules the
# methods are renamed and the page's window.Shell becomes an empty object.
-keepclassmembers class app.hanime.shell.MainActivity$ShellBridge {
    public *;
}
-keepattributes JavascriptInterface
-keepattributes *Annotation*

# Keep the manifest-declared entry points reachable by name.
-keep class app.hanime.shell.MainActivity { public <init>(...); }
-keep class app.hanime.shell.Updater { *; }
-keep class app.hanime.shell.UpdateInfo { *; }

# WebView callbacks arrive through reflection.
-keepclassmembers class * extends android.webkit.WebViewClient {
    public *;
}
