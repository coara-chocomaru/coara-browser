-keepattributes RuntimeVisibleAnnotations,RuntimeInvisibleAnnotations,RuntimeVisibleParameterAnnotations,RuntimeInvisibleParameterAnnotations,AnnotationDefault,InnerClasses,EnclosingMethod,Signature,Exceptions,SourceFile,LineNumberTable

-keep,allowoptimization public class * extends android.app.Activity {
    public <init>(...);
}

-keep,allowoptimization public class * extends android.app.Application {
    public <init>(...);
}

-keep,allowoptimization public class * extends android.app.Service {
    public <init>(...);
}

-keep,allowoptimization public class * extends android.content.BroadcastReceiver {
    public <init>(...);
}

-keep,allowoptimization public class * extends android.content.ContentProvider {
    public <init>(...);
}

-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

-keep,allowoptimization public class * extends androidx.appcompat.app.AppCompatActivity {
    public <init>(...);
}
