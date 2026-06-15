# ═══════════════════════════════════════════════════════════════════════════════
# Atrum Chat — ProGuard rules
# ═══════════════════════════════════════════════════════════════════════════════

# ── Kotlin ────────────────────────────────────────────────────────────────────
-keep class kotlin.Metadata { *; }
-dontwarn kotlin.**
-keepclassmembers class **$WhenMappings { <fields>; }
-keepclassmembers class kotlin.coroutines.** { *; }

# ── Наши data-классы (Room entities, Profile, Message, Chat) ─────────────────
-keep class com.atrum.chat.data.** { *; }
-keep class com.atrum.chat.Profile { *; }
-keep class com.atrum.chat.Message { *; }
-keep class com.atrum.chat.Supporter { *; }

# ── Room ──────────────────────────────────────────────────────────────────────
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao interface * { *; }
-keep @androidx.room.Database class * { *; }
-keep @androidx.room.TypeConverter class * { *; }
-keepclassmembers class * extends androidx.room.RoomDatabase {
    public static ** INSTANCE;
}
-dontwarn androidx.room.**

# ── OkHttp / Okio ─────────────────────────────────────────────────────────────
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }
-keepnames class okhttp3.internal.publicsuffix.PublicSuffixDatabase

# ── Bouncy Castle (Argon2id, X25519, HKDF, SHA-256) ──────────────────────────
# Все крипто-классы должны остаться — они вызываются через reflection/SPI
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**

# ── kmp-tor (встроенный Tor) ─────────────────────────────────────────────────
# Desktop-JVM классы отсутствуют на Android — глушим предупреждения R8.
-dontwarn java.lang.management.ManagementFactory
-dontwarn java.lang.management.RuntimeMXBean
-dontwarn java.lang.management.**
# kmp-tor грузит нативный бинарь tor и работает через JNI/reflection — сохраняем классы.
-keep class io.matthewnelson.** { *; }
-dontwarn io.matthewnelson.**

# ── AndroidX Security (EncryptedSharedPreferences) ───────────────────────────
-keep class androidx.security.crypto.** { *; }
-dontwarn androidx.security.crypto.**

# ── Material Components / BottomSheetDialog ───────────────────────────────────
-keep class com.google.android.material.** { *; }
-dontwarn com.google.android.material.**

# ── uCrop ──────────────────────────────────────────────────────────────────────
-keep class com.yalantis.ucrop.** { *; }
-dontwarn com.yalantis.ucrop.**
-keep interface com.yalantis.ucrop.** { *; }

# ── ViewBinding — сгенерированные классы ─────────────────────────────────────
-keep class com.atrum.chat.databinding.** { *; }

# ── AndroidX / AppCompat / Lifecycle ─────────────────────────────────────────
-dontwarn androidx.**
-keep class androidx.appcompat.** { *; }
-keep class androidx.lifecycle.** { *; }
-keepclassmembers class * implements androidx.lifecycle.LifecycleObserver {
    <init>(...);
}
-keepclassmembers class * extends androidx.lifecycle.ViewModel {
    <init>(...);
}

# ── JSON (org.json) ───────────────────────────────────────────────────────────
-keep class org.json.** { *; }

# ── Enum значения (используются в when-выражениях) ────────────────────────────
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# ── Сериализация через Parcelable/Serializable ────────────────────────────────
-keepclass