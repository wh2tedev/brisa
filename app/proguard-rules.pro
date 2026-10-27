# Called from JavaScript by name.
-keepclassmembers class app.brisa.browser.BlobBridge {
    @android.webkit.JavascriptInterface <methods>;
}

# Settings screens are instantiated by class name from res/xml/preferences.xml.
-keep class app.brisa.browser.SettingsActivity$*Fragment {
    public <init>();
}
