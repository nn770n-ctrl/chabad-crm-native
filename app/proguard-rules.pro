# גשר JavaScript חייב לשמור את שמות המתודות המסומנות.
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
-keepattributes JavascriptInterface
