-dontusemixedcaseclassnames
-renamesourcefileattribute SourceFile
-keepattributes SourceFile,LineNumberTable,*Annotation*,Exceptions,InnerClasses,Signature,Deprecated,EnclosingMethod,Record,PermittedSubclasses,NestHost,NestMembers,Module,ModuleMainClass,RuntimeVisibleAnnotations,RuntimeVisibleParameterAnnotations
-dontwarn java.lang.**
-dontwarn android.**
-dontwarn androidx.**
-dontwarn com.google.android.material.**
-dontwarn org.codehaus.mojo.animal_sniffer.IgnoreJRERequirement
-dontwarn javax.annotation.**
-dontwarn sun.misc.**
-keep class android.** { *; }
-keep interface android.** { *; }
-keep class androidx.** { *; }
-keep interface androidx.** { *; }
-keep class com.google.android.material.** { *; }
-keep interface com.google.android.material.** { *; }
-keep class com.coara.browser.MainActivity { *; }
-keep class com.coara.browser.** { *; }
-keep class com.coara.browser.MainActivity$AndroidBridge { *; }
-keep class com.coara.browser.MainActivity$BlobDownloadInterface { *; }
-keep class com.coara.browser.MainActivity$Bookmark { *; }
-keep class com.coara.browser.MainActivity$HistoryItem { *; }
-keep class com.coara.browser.MainActivity$TabAdapter { *; }
-keep class com.coara.browser.MainActivity$TabAdapter$TabViewHolder { *; }
-keep class com.coara.browser.MainActivity$TabAdapter$AddTabViewHolder { *; }
-keep class com.coara.browser.MainActivity$HistoryAdapter { *; }
-keep class com.coara.browser.MainActivity$HistoryAdapter$HistoryViewHolder { *; }
-keep class com.coara.browser.MainActivity$BookmarkAdapter { *; }
-keep class com.coara.browser.MainActivity$BookmarkAdapter$BookmarkViewHolder { *; }
-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
    public static *** i(...);
    public static *** w(...);
    public static *** e(...);
}
-adaptresourcefilecontents **.xml
-adaptresourcefilenames **.png,**/*.png
-classobfuscationdictionary obfuscation-dictionary.txt
-optimizationpasses 25
-optimizations !code/simplification/arithmetic,!code/simplification/cast,!field/*,!class/merging/*
-mergeinterfacesaggressively
-allowaccessmodification
-adaptclassstrings
-repackageclasses ''
-dontpreverify
-dontoptimize
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
-keepclassmembers class * extends android.webkit.WebViewClient {
    <methods>;
    <fields>;
}
-keepclassmembers class * extends android.webkit.WebChromeClient {
    <methods>;
    <fields>;
}
-keepclassmembers class * {
    void addTextChangedListener(android.text.TextWatcher);
}
-keepclassmembers class * {
    public void requestPermissions(androidx.core.app.ActivityCompat, java.lang.String[], int);
    public void onRequestPermissionsResult(int, java.lang.String[], int[]);
}
-keep class **$$Lambda$* { *; }
-keep class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator CREATOR;
    <fields>;
    <methods>;
}
-keepnames class * implements java.io.Serializable
-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    private static final java.io.ObjectStreamField[] serialPersistentFields;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    java.lang.Object writeReplace();
    java.lang.Object readResolve();
}
-keepclassmembers class * {
    public void set*(...);
    public void get*(...);
    public void *(...);
    public boolean *(...);
}
-keep class * implements android.view.View.OnClickListener { <methods>; }
-keep class * implements android.widget.AdapterView.OnItemClickListener { <methods>; }
-keep class * implements android.text.TextWatcher { <methods>; }
-keep class * implements android.view.View.OnLongClickListener { <methods>; }
-keep class * implements androidx.activity.result.ActivityResultCallback { <methods>; }
-keep class * implements android.webkit.ValueCallback { <methods>; }
-keep class * implements android.webkit.WebViewClient { <methods>; }
-keep class * implements android.webkit.WebChromeClient { <methods>; }
-keep class * implements android.webkit.DownloadListener { <methods>; }
-keep class * implements android.webkit.WebView$FindListener { <methods>; }
-keep class * implements android.webkit.PermissionRequest { <methods>; }
-keep class * implements android.webkit.HttpAuthHandler { <methods>; }
-keep class * implements android.webkit.JsPromptResult { <methods>; }
-keep class * implements android.webkit.ConsoleMessage { <methods>; }
-keep class * implements android.content.DialogInterface$OnClickListener { <methods>; }
-keep class * implements android.content.DialogInterface$OnDismissListener { <methods>; }
-keep class * implements java.lang.Runnable { <methods>; }
-keep class * implements android.os.Handler$Callback { <methods>; }
-keep class * extends androidx.appcompat.app.AppCompatActivity { <methods>; }
-keep class * extends android.app.Activity { <methods>; }
-keep class * extends androidx.fragment.app.Fragment { <methods>; }
-keep class * extends android.webkit.WebView { <methods>; <fields>; }
-keepclasseswithmembernames class * {
    native <methods>;
}
-keepclasseswithmembers class * {
    public <init>(android.content.Context, android.util.AttributeSet);
}
-keepclasseswithmembers class * {
    public <init>(android.content.Context, android.util.AttributeSet, int);
}
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
-keepclassmembers class * {
    public void *(android.view.View);
}
-keepclassmembers class * {
    @org.jetbrains.annotations.NotNull <fields>;
    @org.jetbrains.annotations.Nullable <fields>;
    @org.jetbrains.annotations.NotNull <methods>;
    @org.jetbrains.annotations.Nullable <methods>;
}
