# Keep Application class
-keep class **.kitchen { *; }

# Keep all Activities, Services, BroadcastReceivers, and ContentProviders
-keep public class * extends android.app.Activity
-keep public class * extends android.app.Service
-keep public class * extends android.content.BroadcastReceiver
-keep public class * extends android.content.ContentProvider

# Keep custom views (with constructors)
-keep public class * extends android.view.View {
    public <init>(android.content.Context);
    public <init>(android.content.Context, android.util.AttributeSet);
    public <init>(android.content.Context, android.util.AttributeSet, int);
}

# Keep all native method names
-keepclasseswithmembernames class * {
    native <methods>;
}
