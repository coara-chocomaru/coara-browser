-verbose
-dontusemixedcaseclassnames
-dontskipnonpubliclibraryclasses
-dontskipnonpubliclibraryclassmembers
-dontpreverify 
-keepattributes SourceFile,LineNumberTable,*Annotation*,Exceptions,InnerClasses,Signature,Deprecated,EnclosingMethod,Record,PermittedSubclasses,NestHost,NestMembers,Module,ModuleMainClass,RuntimeVisibleAnnotations,RuntimeVisibleParameterAnnotations
-keepparameternames
-repackageclasses ''
-allowaccessmodification
-mergeinterfacesaggressively
-optimizationpasses 30
-optimizations !code/simplification/arithmetic,!code/simplification/cast,!field/*,!class/merging/*,!code/allocation/variable,!method/inlining/short,!method/inlining/unique,!method/inlining/tailrecursion,!method/removal/parameter,!class/unboxing/enum
-adaptresourcefilenames **.properties,**.gif,**.jpg,**.png,**.xml
-adaptresourcefilecontents **.properties,META-INF/MANIFEST.MF
-obfuscationdictionary obfuscation-dictionary.txt
-classobfuscationdictionary obfuscation-dictionary.txt
-packageobfuscationdictionary obfuscation-dictionary.txt
-dontwarn java.lang.invoke.**
-dontwarn java.lang.reflect.**
-dontwarn java.nio.file.**
-dontwarn javax.annotation.**
-dontwarn javax.management.**
-dontwarn sun.misc.**
-dontwarn sun.security.**
-dontwarn org.codehaus.mojo.animal_sniffer.*
-dontwarn com.google.android.material.internal.**
-dontwarn androidx.window.extensions.**
-dontwarn androidx.window.sidecar.**
-dontwarn android.window.**
-dontwarn kotlin.internal.**
-dontwarn kotlinx.coroutines.**
-assumenosideeffects class android.util.Log {
    public static boolean isLoggable(java.lang.String, int);
    public static int v(...);
    public static int i(...);
    public static int w(...);
    public static int d(...);
    public static int e(...);
}
-keep public class * extends android.app.Activity { *; }
-keep public class * extends android.app.Application { *; }
-keep public class * extends android.app.Service { *; }
-keep public class * extends android.content.BroadcastReceiver { *; }
-keep public class * extends android.content.ContentProvider { *; }
-keep public class * extends android.view.View { *; }
-keep public class * extends androidx.appcompat.app.AppCompatActivity { *; }
-keep public class * extends androidx.fragment.app.Fragment { *; }
-keep public class * extends androidx.recyclerview.widget.RecyclerView$Adapter { *; }
-keep public class * extends androidx.recyclerview.widget.RecyclerView$ViewHolder { *; }
-keep public class * extends android.os.AsyncTask { *; }
-keep class com.coara.browser.** { *; }  
-keep interface com.coara.browser.** { *; }
-keep enum com.coara.browser.** { *; }
-keep class com.coara.browser.MainActivity$** { *; } 
-keep class com.coara.browser.pagedl$** { *; } 
-keep class com.coara.browser.grepmd5appActivity$** { *; }  #
-keep class com.coara.browser.txtphoto { *; }
-keep class com.coara.browser.QrCodeActivity { *; }
-keep class com.coara.browser.DownloadHistoryManager { *; }
-keep class com.coara.browser.DownloadHistoryActivity { *; }
-keep class com.coara.browser.DownloadHistoryActivity$** { *; } 
-keep class android.webkit.WebView { *; }
-keep class android.webkit.WebSettings { *; }
-keep class android.webkit.WebViewClient { *; }
-keep class android.webkit.WebChromeClient { *; }
-keep class android.webkit.DownloadListener { *; }
-keep class android.webkit.ValueCallback { *; }
-keep class android.webkit.PermissionRequest { *; }
-keep class android.webkit.HttpAuthHandler { *; }
-keep class android.webkit.JsPromptResult { *; }
-keep class android.webkit.ConsoleMessage { *; }
-keep class androidx.webkit.WebSettingsCompat { *; }
-keep class androidx.webkit.WebViewFeature { *; }
-keepclassmembers class * extends android.webkit.WebViewClient {
    public *;
}
-keepclassmembers class * extends android.webkit.WebChromeClient {
    public *;
}
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
-keep class * implements android.view.View$OnClickListener { *; }
-keep class * implements android.view.View$OnLongClickListener { *; }
-keep class * implements android.text.TextWatcher { *; }
-keep class * implements android.widget.AdapterView$OnItemClickListener { *; }
-keep class * implements android.content.DialogInterface$OnClickListener { *; }
-keep class * implements android.content.DialogInterface$OnDismissListener { *; }
-keep class * implements java.lang.Runnable { *; }
-keep class * implements android.os.Handler$Callback { *; }
-keep class * implements androidx.activity.result.ActivityResultCallback { *; }
-keepnames class * implements java.io.Serializable
-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    private static final java.io.ObjectStreamField[] serialPersistentFields;
    !static !transient <fields>;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    java.lang.Object writeReplace();
    java.lang.Object readResolve();
}
-keep class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator CREATOR;
}
-keep class org.json.** { *; }
-keep class com.google.zxing.** { *; }
-keep class java.security.MessageDigest { *; } 
-keep class com.coara.browser.MainActivity {
    private static java.lang.reflect.Method sSetSaveFormDataMethod;
    private static java.lang.reflect.Method sSetDatabaseEnabledMethod;
    private static java.lang.reflect.Method sSetAppCacheEnabledMethod;
    private static java.lang.reflect.Method sSetAppCachePathMethod;
    *;
}
-keep class android.util.LruCache { *; }
-keep class java.util.concurrent.ExecutorService { *; }
-keep class android.graphics.Bitmap { *; }
-keepclassmembers class com.coara.browser.** {
    public static final java.lang.String PREF_NAME;
    public static final java.lang.String USER_AGENT;
    public static final java.lang.String ACCEPT_HEADER;
    public static final java.lang.String ACCEPT_LANGUAGE;
    public static final java.lang.String KEY_*;
}
-keep class **$$Lambda$* { *; }
-keep class kotlin.jvm.internal.Lambda { *; }
-keepclassmembers class * extends android.app.Activity {
    public void onCreate(android.os.Bundle);
    public void onActivityResult(int, int, android.content.Intent);
    public void onRequestPermissionsResult(int, java.lang.String[], int[]);
    public void onBackPressed();
    public void onPause();
    public void onResume();
    public void onDestroy();
}

-dontnote ** 
-flattenpackagehierarchy ''
