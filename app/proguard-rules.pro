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
-keepclassmembers class * implements java.io.Serializable {
    private static final java.io.ObjectStreamField[] serialPersistentFields;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    java.lang.Object writeReplace();
    java.lang.Object readResolve();
}

# ── Удаление логов в release-сборке ──────────────────────────────────────────
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}

# ── sherpa-onnx (нейросетевой шумодав GTCRN) ─────────────────────────────────
# JNI-классы вызываются нативной либой по имени — сохраняем целиком.
-keep class com.k2fsa.sherpa.onnx.** { *; }
-dontwarn com.k2fsa.sherpa.onnx.**

# ── tor-android (Guardian Project) — Фаза 1/2, TOR_BRIDGES_CONTINUE.md ───────
# ⚠️ НАЙДЕНО НА УСТРОЙСТВЕ: без этого правила краш на реальном тесте —
# java.lang.NoSuchFieldError: no "J" field "torConfiguration" in class
# "Lorg/torproject/jni/TorService;". Причина: нативный код tor-android достаёт
# поля/методы TorService по ИМЕНИ через JNI (как sherpa-onnx выше) — R8 не видит
# эти строковые обращения из нативного кода и переименовывает/выпиливает поле
# как "неиспользуемое". Сохраняем целиком, как kmp-tor/sherpa-onnx.
-keep class org.torproject.jni.** { *; }
-dontwarn org.torproject.jni.**

# ── jtorctl (control-port клиент для tor-android) ────────────────────────────
-keep class net.freehaven.tor.control.** { *; }
-dontwarn net.freehaven.tor.control.**

# ── IPtProxy (Фаза 2 — Snowflake/obfs4 через gomobile) ───────────────────────
# Тот же класс проблемы: gomobile-сгенерированный нативный код (Go) обращается
# к Java-классам биндинга по имени/сигнатуре — превентивно сохраняем целиком,
# не дожидаясь такого же NoSuchFieldError/NoSuchMethodError при реальном тесте
# Snowflake/obfs4.
-keep class IPtProxy.** { *; }
-dontwarn IPtProxy.**
