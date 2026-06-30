import java.util.Properties
import java.io.FileInputStream

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
}

// Подгружаем keystore.properties из корня проекта (если есть)
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        load(FileInputStream(keystorePropertiesFile))
    }
}
val hasKeystore = keystorePropertiesFile.exists()

android {
    namespace = "com.atrum.chat"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.atrum.chat"
        minSdk = 24
        targetSdk = 34
        // ══════════════════════════════════════════════════════════════════
        // ⚠️  AI AGENT — ОБЯЗАТЕЛЬНО ЧИТАТЬ ПЕРЕД ЛЮБЫМ ИЗМЕНЕНИЕМ КОДА
        // ══════════════════════════════════════════════════════════════════
        // ПРАВИЛО 1 — ВЕРСИЯ: после КАЖДОГО изменения кода поднять оба поля:
        //   versionCode  — целое, +1 (144 → 145 → 146 ...)
        //   versionName  — патч-версия строкой (4.1.7 → 4.1.8 → 4.2.0 ...)
        //   Не пропускать даже для однострочных правок.
        //
        // ПРАВИЛО 2 — ПРОВЕРКА СБОРКИ: после изменений Kotlin-файлов выполнить
        //   ./gradlew compileReleaseKotlin   (или assembleRelease для полной сборки)
        //   Если gradlew недоступен — провести ручной ревью всех изменённых файлов
        //   и явно сообщить пользователю о результате. Не замалчивать.
        //
        // Подробнее: см. CLAUDE.md в корне проекта.
        // ══════════════════════════════════════════════════════════════════
        versionCode = 257
        versionName = "3.20.53-beta220-patch31"

        // Включаем multidex чтобы не упереться в лимит 65k методов
        // когда много AndroidX/Material/Room/uCrop/browser библиотек
        multiDexEnabled = true

        // Оставляем только русскую (default) и английскую локали. AppCompat/Material
        // тянут строковые ресурсы на ~80 языков — приложение их не использует.
        // Чистит resources.arsc без потери функций (UI только RU/EN).
        resourceConfigurations += setOf("en", "ru")

        // Только arm64-v8a: покрывает практически все телефоны с ~2017.
        // Отрезаем x86/x86_64 (эмуляторы) и armeabi-v7a (старые 32-бит) —
        // их нативные .so (libgojni/libtor и пр.) зря раздували APK на десятки МБ.
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    // Исключаем типичные duplicate-файлы из META-INF которые часто
    // дают конфликты при mergeDex (uCrop / kotlin libs / okhttp)
    packagingOptions {
        jniLibs {
            // true → .so СЖИМАЮТСЯ (DEFLATE) внутри APK → меньше размер скачивания.
            // (false означает несжатые page-aligned либы — APK раздувается: наши
            // libonnxruntime/libgojni/libtor лежали без сжатия ~68 МБ. Со сжатием
            // раздел lib/ ужимается примерно вдвое.)
            // Цена: при установке .so извлекаются на диск (extractNativeLibs=true) —
            // чуть больше места на устройстве и чуть медленнее первый старт. Для
            // прямой раздачи APK (не через Play) важен размер скачивания — берём true.
            useLegacyPackaging = true
        }
        resources {
            excludes += setOf(
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE",
                "META-INF/LICENSE.txt",
                "META-INF/license.txt",
                "META-INF/NOTICE",
                "META-INF/NOTICE.txt",
                "META-INF/notice.txt",
                "META-INF/ASL2.0",
                "META-INF/*.kotlin_module",
                "META-INF/AL2.0",
                "META-INF/LGPL2.1",
                // Мёртвые ресурсы BouncyCastle: используется ТОЛЬКО Argon2id
                // (org.bouncycastle.crypto.generators) — это чистый код без ресурсов.
                // Пост-квантовые таблицы (Picnic/lowmc и пр.) не используются, но R8
                // не вырезает .bin/.properties (это ресурсы, не классы) — режем тут (~1.3 МБ).
                "org/bouncycastle/pqc/**"
            )
        }
    }

    signingConfigs {
        if (hasKeystore) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties["storeFile"] as String)
                storePassword = keystoreProperties["storePassword"] as String
                keyAlias = keystoreProperties["keyAlias"] as String
                keyPassword = keystoreProperties["keyPassword"] as String
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (hasKeystore) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        debug {
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    applicationVariants.all {
        outputs.all {
            val output = this as com.android.build.gradle.internal.api.BaseVariantOutputImpl
            output.outputFileName = "AtrumChat-${versionName}.apk"
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.activity:activity-ktx:1.8.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")

    // OkHttp для запросов к GitHub API
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Корутины
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    // Tor (встроенный) — Nostr через Tor. runtime + бинарники tor (exec).
    implementation("io.matthewnelson.kmp-tor:runtime:2.6.0")
    implementation("io.matthewnelson.kmp-tor:resource-exec-tor:409.5.0")

    // EncryptedSharedPreferences для безопасного хранения данных
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // Room — локальная база данных для списка чатов
    val roomVersion = "2.7.0"
    implementation("androidx.room:room-runtime:$roomVersion")
    implementation("androidx.room:room-ktx:$roomVersion")
    ksp("androidx.room:room-compiler:$roomVersion")

    // uCrop — кроп фото с круглой маской (как в Telegram)
    implementation("com.github.yalantis:ucrop:2.2.8")

    // Chrome Custom Tabs — для встроенного браузера в OAuth flow
    implementation("androidx.browser:browser:1.7.0")

    // ViewPager2 — для intro/onboarding с свайпом
    implementation("androidx.viewpager2:viewpager2:1.0.0")

    // Bouncy Castle — Argon2id KDF для защищённой деривации ключа шифрования
    implementation("org.bouncycastle:bcprov-jdk18on:1.78.1")

    // Lottie — анимации и стикеры
    implementation("com.airbnb.android:lottie:6.3.0")

    // ZXing — генерация QR-кода сверки (SAS) для защиты от MITM.
    implementation("com.google.zxing:core:3.5.3")

    // Biometric — системный отпечаток (BiometricPrompt). Биометрия НЕ хранится
    // в приложении: запрос идёт в системную подсистему телефона (на Samsung — Knox/TEE).
    implementation("androidx.biometric:biometric:1.1.0")

    // IPtProxy — pluggable transports (Lyrebird=obfs4/meek/webtunnel + Snowflake) для
    // обхода блокировки Tor. Нативные бинари под все ABI уже внутри AAR. Используется
    // в TorManager для запуска мостов, когда обычный Tor не поднимается (цензура).
    implementation("com.netzarchitekten:IPtProxy:5.5.0")

    // CameraX — превью камеры + анализ кадров для QR-сканера (Bluetooth-подключение по QR).
    val cameraX = "1.3.4"
    implementation("androidx.camera:camera-core:$cameraX")
    implementation("androidx.camera:camera-camera2:$cameraX")
    implementation("androidx.camera:camera-lifecycle:$cameraX")
    implementation("androidx.camera:camera-view:$cameraX")

    testImplementation("junit:junit:4.13.2")
    testImplementation("io.mockk:mockk:1.13.11")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
}
