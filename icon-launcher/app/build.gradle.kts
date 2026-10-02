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
        // Comme Termux : depuis Android 10, une appli qui vise une version plus récente n'a plus le droit
        // de lancer les programmes qu'elle télécharge (ici proot + Alpine Linux du terminal Linux)
        targetSdk = 28
        versionCode = 3
        versionName = "2.1"

        // Détection ML Kit uniquement pour les téléphones récents (64 bits) : APK bien plus léger
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
    // Détection à l'écran (visages + objets, avec suivi), dans le téléphone, sans Internet
    implementation("com.google.mlkit:face-detection:16.1.7")
    implementation("com.google.mlkit:object-detection:17.0.2")

    testImplementation("junit:junit:4.13.2")
}
