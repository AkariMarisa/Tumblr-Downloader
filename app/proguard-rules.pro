# ── Retrofit + OkHttp ────────────────────────────────────────────────
-dontwarn okhttp3.**
-dontwarn retrofit2.**
-keepattributes Signature, *Annotation*, EnclosingMethod, InnerClasses
-keep class retrofit2.** { *; }
-keepclasseswithmembers class * {
    @retrofit2.http.* <methods>;
}
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }

# ── Gson (declared dependency, keep default handling) ────────────────
-keep class com.google.gson.** { *; }
-keepattributes Signature
-keepattributes *Annotation*
-dontwarn com.google.gson.**

# ── Glide ────────────────────────────────────────────────────────────
-keep public class * implements com.bumptech.glide.module.GlideModule
-keep class com.bumptech.glide.** { *; }
-keep public enum com.bumptech.glide.load.ImageHeaderParser$** {
    **[] $VALUES;
    public *;
}
-dontwarn com.bumptech.glide.**

# ── ExoPlayer ────────────────────────────────────────────────────────
-keep class com.google.android.exoplayer2.** { *; }
-dontwarn com.google.android.exoplayer2.**

# ── Room ─────────────────────────────────────────────────────────────
-keep class * extends androidx.room.RoomDatabase
-dontwarn androidx.room.**
-keepclassmembers class * {
    @androidx.room.* <fields>;
}

# ── Kotlin coroutines ────────────────────────────────────────────────
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-dontwarn kotlinx.coroutines.**

# ── App model / data classes — keep fields for JSONObject reflection ─
-keep class com.example.tumblrdownloader.model.** { *; }
-keep class com.example.tumblrdownloader.db.** { *; }
-keep class com.example.tumblrdownloader.utils.ParsedTumblrMedia { *; }
-keep class com.example.tumblrdownloader.utils.TumblrShareParseResult { *; }
