plugins {
    id("com.android.application")
}

android {
    namespace = "fr.projettango.camera"
    compileSdk = 36

    defaultConfig {
        applicationId = "fr.projettango.camera"
        minSdk = 24            // Android 7+: old phones make fine fixed cameras
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Sideloaded lab app: the release APK is signed with the debug key so it installs
            // without any keystore. Replace with a real signingConfig before any store upload.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    val camerax = "1.6.2"
    implementation("androidx.camera:camera-core:$camerax")
    implementation("androidx.camera:camera-camera2:$camerax")
    implementation("androidx.camera:camera-lifecycle:$camerax")
    implementation("androidx.lifecycle:lifecycle-service:2.11.0")
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // QR pairing through Google Play services (the scanner UI is provided by the system, no camera code here)
    implementation("com.google.android.gms:play-services-code-scanner:16.1.0")
}
