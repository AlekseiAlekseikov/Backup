import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
}

val platformStore = rootProject.file("platform-keys/platform.jks")

// ===== Конфигурация телеметрии (Capy Energy) =====
// Значения ищутся в порядке: local.properties → gradle.properties → переменные
// окружения → "". Пустые значения отключают облако/канал обновлений — сборка
// без проекта Supabase работает полностью автономно.
fun envProp(name: String): String {
    val props = Properties()
    val lp = rootProject.file("local.properties")
    if (lp.exists()) lp.inputStream().use { props.load(it) }
    return props.getProperty(name)
        ?: (findProperty(name) as String?)
        ?: System.getenv(name)
        ?: ""
}

// GeelyTools deviation: облачная выгрузка (Supabase) исключена из сборки —
// телеметрия строго локальная, companion-пара и cloud-sync удалены.
val appUpdateManifestUrl by lazy { envProp("APP_UPDATE_MANIFEST_URL") }
val chargeControlManifestUrl by lazy { envProp("CHARGE_CONTROL_MANIFEST_URL") }

// Нативная сборка roadcast_jni требует libroadcast_client.so, который не
// распространяется с исходниками (собирается из github.com/Timoteohss/roadcast
// и кладётся в app/src/main/jniLibs/arm64-v8a/). Пока либы нет — собираем
// без неё: Kotlin-часть roadcast компилируется, live-CAN включится после
// добавления библиотеки.
val roadcastClientLib = file("src/main/jniLibs/arm64-v8a/libroadcast_client.so")

android {
    namespace = "com.example.voiceapp3"
    compileSdk = 36

    // NDK нужен для roadcast_jni (live-CAN 60 Гц). В Android Studio
    // ставится автоматически; для CLI: sdkmanager "ndk;27.3.13750724"
    ndkVersion = "27.3.13750724"

    defaultConfig {
        applicationId = "com.example.voiceapp3"
        minSdk = 28
        targetSdk = 32
        versionCode = 23
        versionName = "1.0.23"

        buildConfigField("String", "APP_UPDATE_MANIFEST_URL", "\"$appUpdateManifestUrl\"")
        buildConfigField("String", "CHARGE_CONTROL_MANIFEST_URL", "\"$chargeControlManifestUrl\"")

        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    signingConfigs {
        create("platform") {
            storeFile = platformStore
            storePassword = "android"
            keyAlias = "platform"
            keyPassword = "android"
            storeType = "PKCS12"
        }
    }

    buildTypes {
        getByName("debug") {
            if (platformStore.exists()) {
                signingConfig = signingConfigs.getByName("platform")
            }
        }
        getByName("release") {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (platformStore.exists()) {
                signingConfig = signingConfigs.getByName("platform")
            }
        }
    }

    buildFeatures {
        buildConfig = true
        // Stage CPv2: AIDL-интерфейсы OEM (com.njda.*) — как в capy;
        // с AGP 8 генерация AIDL выключена по умолчанию.
        aidl = true
    }

    // Приложение не для Google Play: ставится на ГУ Geely (Android 9)
    // платформенной подписью. targetSdk 32 подобран под прошивку IHU629G,
    // поднимать его нельзя — отключаем только проверку политики Play,
    // остальные lintVital-проверки release-сборки остаются.
    lint {
        disable += "ExpiredTargetSdkVersion"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // этап 7 (fix8c): GraphHopper 11 использует Stream.toList() и др.
        // Java 16+ API — на Android 9 их нет, включаем десугаринг core-libs.
        isCoreLibraryDesugaringEnabled = true
    }

    useLibrary("android.car")

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    // Иначе aapt сжимает .onnx и sherpa читает «No graph was found in the protobuf»
    androidResources {
        noCompress += "onnx"
    }

    externalNativeBuild {
        cmake {
            // Только при наличии клиентской либы Roadcast (см. комментарий выше)
            if (roadcastClientLib.exists()) {
                path = file("src/main/cpp/CMakeLists.txt")
            }
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.incremental", "true")
}

dependencies {
    // ===== Основа / UI (GeelyTools) =====
    implementation("androidx.core:core:1.7.0")
    implementation("androidx.core:core-ktx:1.7.0")
    implementation("androidx.media:media:1.6.0")
    implementation("androidx.appcompat:appcompat:1.3.1")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.localbroadcastmanager:localbroadcastmanager:1.1.0")
    implementation("com.google.android.material:material:1.9.0")

    // ===== Голосовой стек (GeelyTools) =====
    implementation("net.java.dev.jna:jna:5.14.0@aar")
    implementation("com.alphacephei:vosk-android:0.3.47") {
        exclude(group = "net.java.dev.jna", module = "jna")
    }
    implementation("com.airbnb.android:lottie:6.0.0")
    implementation("com.microsoft.onnxruntime:onnxruntime-android:latest.release")
    implementation("com.google.code.gson:gson:2.10.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // ===== Навигация, этап 7: on-device маршрутизация (Apache 2.0) =====
    // Решение стратегической переоценки 2026-10-06: остаёмся на open-стеке;
    // движок без сети, ключей и квот. OSM-reader давно слит в core (зависимости
    // чисто Java: hppc, jackson, osmosis-osm-binary — по POM 11.1, Central).
    implementation("com.graphhopper:graphhopper-core:11.1") {
        // fix8d/f: Janino на Android неработоспособен (javax.lang.model.* в ART
        // отсутствует, .class на лету не грузится). Исключаем ВСЮ группу —
        // и janino, и commons-compiler (транзитив у janino; module-эксклюда
        // было недостаточно, по ней исключался только сам janino). Обход
        // парсера — AndroidGraphHopper (prepareImport + createWeightingFactory).
        exclude(group = "org.codehaus.janino")
    }
    // десугаринг Java 16+ API (Stream.toList и пр.) для Android 9
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")
    implementation(files("libs/sherpa-onnx-v1.12.9-java8.jar"))

    // ===== Flutter-приложение телеметрии (geelytools_telemetry) =====
    // AAR add-to-app: артефакты создаёт `flutter build aar` в flutter_telemetry/
    // (см. отчёт миграции). Приносит io.flutter:flutter_embedding_release и
    // arm64-движок транзитивно.
    implementation("com.alekseikov.geelytools.geelytools_telemetry:flutter_release:1.0")

    // ===== Навигация (Stage NAV-MAP, этап 4 переписи): MapLibre Native =====
    // Векторный рендер карты (BSD-2). Подложка — вендорные стили
    // assets/ui/nav/styles/*.json от публичного OpenFreeMap (online) до
    // этапа 5 (офлайн-пакет BY + каталог догружаемых регионов); атрибуция
    // OSM/OpenFreeMap/OpenMapTiles прописана в источниках стилей.
    implementation("org.maplibre.gl:android-sdk:13.4.1")

    // ===== Движок телеметрии (Capy Energy, Apache 2.0) =====
    val roomVersion = "2.8.4"
    implementation("androidx.room:room-runtime:$roomVersion")
    implementation("androidx.room:room-ktx:$roomVersion")
    ksp("androidx.room:room-compiler:$roomVersion")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
