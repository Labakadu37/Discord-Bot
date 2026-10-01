plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.monimage.launcher"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.monimage.launcher"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        // Moteur d'IA uniquement pour les téléphones récents (64 bits) : APK beaucoup plus léger
        ndk { abiFilters += "arm64-v8a" }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("com.google.mediapipe:tasks-genai:0.10.24")
}
