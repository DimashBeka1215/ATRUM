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

// Сборка нативного модуля из src/main/cpp (требует NDK + CMake).
val enableNativeModule = true

android {
    namespace = "com.atrum.chat"
    // ⚠️ 35→36: понадобилось для info.guardianproject:tor-android (AAR metadata требует
    // компилироваться минимум против той версии API, что заявлена в его манифесте — иначе
    // "AAR metadata check" валит сборку). 36 — максимум, официально поддерживаемый текущим
    // AGP 8.13.2 (что и требуется). targetSdk/minSdk НЕ трогаем — рантайм-поведение то же.
    compileSdk = 36

    defaultConfig {
        applicationId = "com.atrum.chat"
        // Android 7.0 (API 24) — реальный минимум: библиотека info.guardianproject:tor-android
        // требует minSdk 24 (манифест-мердж падает на 23). Ниже без замены Tor-движка нельзя,
        // да и Tor/нативный стек на Android 6 не имеет смысла.
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
        versionCode = 571
        versionName = "3.49.27-beta485-rename-guards"

        // ЛИЧНАЯ СБОРКА: по умолчанию ВЫКЛючена (обычный релиз чист). Включается в build-типе
        // debug (см. buildTypes). Личные «фишки для себя» в коде прячутся за
        // BuildConfig.PERSONAL / PersonalFeatures.enabled — в release они не выполняются.
        buildConfigField("boolean", "PERSONAL", "false")

        // Имя приложения (лаунчер) — по умолчанию «ATRUM» (обычный релиз). Личная сборка
        // (debug) переопределяет на «ATRUM DEBUG», см. buildTypes. Подставляется в манифест
        // через android:label="${appName}".
        manifestPlaceholders["appName"] = "ATRUM"

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

            // ⚠️ КОНФЛИКТ ДВУХ TOR-ДВИЖКОВ (см. TorManager.USE_TOR_ANDROID_ENGINE /
            // TOR_BRIDGES_CONTINUE.md). И kmp-tor (resource-exec-tor), и tor-android
            // (Guardian Project) несут СВОЙ lib/arm64-v8a/libtor.so по ОДНОМУ И ТОМУ ЖЕ
            // пути в APK — Gradle не может упаковать оба и падает с "2 files found with
            // path 'lib/arm64-v8a/libtor.so'". Это НЕ два одинаковых файла — это два РАЗНЫХ
            // несовместимых бинаря (kmp-tor — exec-бинарь; tor-android — JNI-библиотека
            // под TorService, свои Java_org_torproject_jni_* символы). Смешивать нельзя:
            // если в APK попадёт "не тот" .so, соответствующий движок не запустится
            // (UnsatisfiedLinkError / неверный протокол запуска).
            //
            // pickFirst ниже — официальный способ AGP разрешить дубликат и НЕ падать со
            // сборкой. Он берёт файл от ПЕРВОЙ по порядку резолвинга зависимости — управляем
            // этим порядком блоков `implementation(...)` в dependencies{} ниже, а не выключением
            // одной из зависимостей (у обоих движков Kotlin-код живёт в TorManager.kt одновременно
            // и должен компилироваться независимо от флага — закомментировать Gradle-строку без
            // удаления соответствующего кода нельзя, будет ошибка компиляции на unresolved import).
            //
            // ⚠️ ТЕКУЩЕЕ СОСТОЯНИЕ (см. TorManager.USE_TOR_ANDROID_ENGINE = true — тест на
            // устройстве): блок tor-android объявлен ПЕРВЫМ в dependencies{} ниже — его .so
            // и победит. Когда закончишь тест и вернёшь флаг на false — верни kmp-tor-блок
            // обратно первым (порядок описан прямо там же, в dependencies{}).
            pickFirsts += "lib/*/libtor.so"
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
            // ── ЛИЧНАЯ СБОРКА (ATRUM Personal) ──────────────────────────────────────
            // Отдельный applicationId (…​.debug) → ставится РЯДОМ с обычным релизом, не
            // конфликтует, СВОИ данные/чаты. Функционально идентична релизу, но включает
            // личные фишки за BuildConfig.PERSONAL (см. PersonalFeatures).
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-personal"
            buildConfigField("boolean", "PERSONAL", "true")
            // Личная сборка называется «ATRUM DEBUG» (обычная — «ATRUM», см. defaultConfig).
            manifestPlaceholders["appName"] = "ATRUM DEBUG"
            // Подписываем тем же release-ключом (если есть keystore.properties) — чтобы это
            // была полноценная сборка на каждый день, а не одноразовая debug-заглушка.
            if (hasKeystore) signingConfig = signingConfigs.getByName("release")
        }
    }

    if (enableNativeModule) {
        externalNativeBuild {
            cmake {
                path = file("src/main/cpp/CMakeLists.txt")
            }
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
    // Pull-to-refresh для экранов статистики (GroupStatsActivity/UserStatsActivity).
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
    implementation("androidx.activity:activity-ktx:1.8.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")

    // OkHttp для запросов к GitHub API
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Корутины
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    // ⚠️ ПОРЯДОК ЭТИХ ДВУХ БЛОКОВ ВРЕМЕННО ПОМЕНЯН МЕСТАМИ (см. TorManager.USE_TOR_ANDROID_ENGINE
    // = true — тест на устройстве). packagingOptions.jniLibs.pickFirst берёт lib/arm64-v8a/libtor.so
    // от ПЕРВОЙ по порядку резолвинга зависимости — сейчас это tor-android (нужный движок для
    // теста). Верни kmp-tor-блок обратно ПЕРВЫМ, когда закончишь тест и вернёшь флаг на false.

    // Tor Android (Guardian Project, стек Orbot) — путь B из TOR_BRIDGES_CONTINUE.md.
    // Даёт мосты (Snowflake/obfs4) "из коробки" через обычный torrc + control-port.
    // Версии отслеживаются Dependabot'ом (.github/dependabot.yml) — при новом релизе
    // Guardian Project сюда прилетает Pull Request на GitHub, а не тихое авто-обновление
    // сборки (supply-chain risk недопустим для крипто-мессенджера).
    // ⚠️ ВЕРСИЯ ЗАФИКСИРОВАНА НА 0.4.8.22 (не последняя!). Самая свежая на день добавления
    // (0.4.9.11, ветка master) требует compileSdk 37 — это НОВЕЕ, чем официально поддерживает
    // сам AGP 8.13.2 (максимум 36), сборка падала с "AAR metadata check" ошибкой. 0.4.8.22 —
    // релиз до этого скачка требований, собирается на compileSdk 36 (см. выше). Когда
    // Dependabot предложит апдейт — ПЕРЕД мёржем свериться, что новая версия не требует
    // compileSdk выше того, что поддерживает актуальный на тот момент AGP.
    implementation("info.guardianproject:tor-android:0.4.8.22")
    implementation("info.guardianproject:jtorctl:0.4.5.7")

    // Tor (встроенный) — Nostr через Tor. runtime + бинарники tor (exec).
    // ⚠️ ПУТЬ ОТКАТА (см. TorManager.kt / TOR_BRIDGES_CONTINUE.md): kmp-tor не умеет
    // мосты (Snowflake/obfs4) — публичного API для Bridge/ClientTransportPlugin/UseBridges
    // нет ни в одной версии (проверено). Оставлен как fallback, пока новый TorManager
    // (на tor-android, см. выше) не обкатан на реальных устройствах.
    // ⚠️ Конфликтует по lib/arm64-v8a/libtor.so с tor-android выше — см. подробный
    // комментарий у packagingOptions.jniLibs.pickFirst выше по файлу перед тем, как
    // переключать движки.
    implementation("io.matthewnelson.kmp-tor:runtime:2.6.0")
    implementation("io.matthewnelson.kmp-tor:resource-exec-tor:409.5.0")

    // EncryptedSharedPreferences для безопасного хранения данных
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // WorkManager — резервное периодическое пробуждение доставки пушей (переживает Doze/kill).
    implementation("androidx.work:work-runtime-ktx:2.9.1")

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
