plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.spinecare.app"
    compileSdk = 34

    defaultConfig {
        // Installed package. The Kotlin code stays in com.spinecare.app (namespace below);
        // this id is what Android uses to identify the app, so it installs alongside
        // the old com.spinecare.app build (remove that once, from Settings in the app).
        applicationId = "com.spinecare.claude"
        minSdk = 26
        targetSdk = 34
        versionCode = 4
        versionName = "0.4.0"
        // Instrumented video-replay test (src/androidTest), run on an emulator by .github/workflows/video-evidence.yml
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // MediaPipe Tasks ships no x86_64 library. Without this filter an x86_64 device
        // (emulator, Chromebook) installs the x86_64 split (CameraX has one), cannot load
        // libmediapipe_tasks_vision_jni.so and silently loses on-device pose; with it,
        // such devices run the 32-bit x86 build. Found by the emulator video replay.
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86") }
    }
    // The exercise plan is shared with the server and iOS app (repo-root shared/).
    sourceSets["main"].assets.srcDirs("src/main/assets", "../../shared")
    // One committed debug key, so every CI and local debug APK is signed the same way
    // and installs over the previous one. Before, each CI runner generated its own
    // debug key and Android refused updates (INSTALL_FAILED_UPDATE_INCOMPATIBLE).
    // It is a debug key and public on purpose; real releases use the SPINECARE_KEYSTORE secret.
    signingConfigs {
        getByName("debug") {
            storeFile = rootProject.file("keystore/spinecare-debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }
    buildTypes {
        debug { isMinifyEnabled = false; signingConfig = signingConfigs.getByName("debug") }
        release {
            // Prototype release: R8 kept off so MediaPipe/OkHttp reflection stays safe.
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = if (System.getenv("SPINECARE_KEYSTORE") != null) {
                signingConfigs.create("ci") {
                    storeFile = file(System.getenv("SPINECARE_KEYSTORE"))
                    storePassword = System.getenv("SPINECARE_KEYSTORE_PASSWORD")
                    keyAlias = System.getenv("SPINECARE_KEY_ALIAS")
                    keyPassword = System.getenv("SPINECARE_KEY_PASSWORD")
                }
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { viewBinding = true }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    // Lint still runs and reports; it must not block prototype release builds.
    lint { abortOnError = false }
    testOptions { unitTests.isReturnDefaultValues = true }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.activity:activity-ktx:1.9.1")
    implementation("androidx.fragment:fragment-ktx:1.8.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    // Camera
    implementation("androidx.camera:camera-core:1.3.4")
    implementation("androidx.camera:camera-camera2:1.3.4")
    implementation("androidx.camera:camera-lifecycle:1.3.4")
    implementation("androidx.camera:camera-view:1.3.4")
    // On-device pose: MediaPipe Tasks Vision (BlazePose 33 landmarks)
    implementation("com.google.mediapipe:tasks-vision:0.10.14")
    // Health Connect
    implementation("androidx.health.connect:connect-client:1.1.0-alpha06")
    // HTTP/WS for the optional coaching server
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")  // real org.json for JVM tests (android.jar has stubs)
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:core:1.6.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
