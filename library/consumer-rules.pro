# Consumer ProGuard rules for android-google-drive-sync library

# Keep public API classes
-keep public class com.vanespark.googledrivesync.api.** { *; }

# Keep all sealed class subclasses
-keep class com.vanespark.googledrivesync.api.SyncResult$* { *; }
-keep class com.vanespark.googledrivesync.auth.AuthState$* { *; }
-keep class com.vanespark.googledrivesync.sync.ConflictResolution$* { *; }

# Keep data classes used in public API
-keepclassmembers class com.vanespark.googledrivesync.api.** {
    <init>(...);
    <fields>;
}

# Keep enums
-keepclassmembers enum com.vanespark.googledrivesync.** {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# Google API client
-keep class com.google.api.** { *; }
-keep class com.google.apis.** { *; }
-dontwarn com.google.api.**
-dontwarn com.google.apis.**

# Kotlin serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt

-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Hilt
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }
-keep class * extends dagger.hilt.android.internal.managers.ViewComponentManager$FragmentContextWrapper { *; }
