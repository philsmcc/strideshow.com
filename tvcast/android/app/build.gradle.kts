import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Optional release signing. Create keystore.properties (gitignored) with
// storeFile / storePassword / keyAlias / keyPassword to sign release builds.
// Without it, only the debug build is usable - which is fine for sideloading.
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
}

android {
    namespace = "com.strideshow.panelcast"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.strideshow.panelcast"
        // API 21 (Android 5.0) covers the Android 9 panels with plenty of room
        // to spare, and is the floor the WebRTC prebuilt supports.
        minSdk = 21
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"

        // The default signaling host. Overridable at runtime in Settings, so
        // the APK does not need rebuilding to point at a different server.
        buildConfigField("String", "DEFAULT_SIGNALING_URL", "\"wss://www.strideshow.com/panelcast/ws\"")
    }

    signingConfigs {
        if (keystoreProps.isNotEmpty()) {
            create("release") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            // Distinct suffix so a debug build can sit alongside a release one.
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            // R8 off: the app is tiny, and keeping WebRTC's JNI-bound classes
            // intact avoids a class of subtle reflection/native crashes on the
            // old devices we cannot easily debug on.
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (keystoreProps.isNotEmpty()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    // Ship one APK per ABI plus a universal one. The universal APK is the safe
    // choice when you do not know a panel's architecture; the per-ABI ones are
    // ~8 MB smaller, which matters on devices with tiny internal storage.
    splits {
        abi {
            isEnable = true
            reset()
            // armeabi-v7a covers most older panels; arm64 the newer ones;
            // x86/x86_64 exist on a surprising number of cheap AOSP boxes.
            include("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
            isUniversalApk = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // Lets us use newer java.time/stream APIs on API 21 without crashing.
        isCoreLibraryDesugaringEnabled = true
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    packaging {
        resources.excludes += setOf(
            "META-INF/AL2.0", "META-INF/LGPL2.1",
            "META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*",
        )
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    // Native WebRTC. A WebView-based receiver would be less code, but old
    // panels often have a frozen/absent System WebView with broken WebRTC,
    // so we bundle our own libwebrtc and get hardware H.264 decode control.
    implementation("io.github.webrtc-sdk:android:125.6422.07")

    // QR rendering only (no camera scanning on the TV side). 3.3.0 is the last
    // release that stays on Java 7 bytecode and works cleanly on API 21.
    implementation("com.google.zxing:core:3.3.0")

    // OkHttp 4.12 is the newest line that still supports API 21.
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    implementation("org.jetbrains.kotlin:kotlin-stdlib:1.9.24")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // AndroidX versions pinned to the last API 21-compatible releases.
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")

    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.0.4")

    // Local JVM unit tests for the pure-Kotlin SDP logic.
    testImplementation("junit:junit:4.13.2")
}
