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

# Add additional rules as needed for libraries (e.g., Gson, Retrofit, etc.)

# OkHttp Platform used when running on Java 8 or below
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# Keep security providers
-keep class org.bouncycastle.jsse.** { *; }
-keep class org.conscrypt.** { *; }
-keep class org.openjsse.** { *; }
