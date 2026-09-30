plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.image3d.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.image3d.app"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
        ndk {
            // Le modèle d'IA a besoin d'un processeur ARM 64 bits (tous les téléphones récents)
            abiFilters += listOf("arm64-v8a")
        }
        // Adresse par défaut des fichiers de l'IA (modifiable dans l'application)
        buildConfigField(
            "String",
            "MODELS_URL",
            "\"https://github.com/labakadu37/Discord-Bot/releases/download/image3d-models-v1/\"",
        )
    }

    // Clé de signature facultative (secrets GitHub) : permet d'installer les mises à jour
    // par-dessus l'ancienne version. Sans elle, l'APK est signé avec la clé de debug.
    val keystore = System.getenv("IMAGE3D_KEYSTORE")?.let { file(it) }?.takeIf { it.exists() }
    signingConfigs {
        if (keystore != null) {
            create("release") {
                storeFile = keystore
                storePassword = System.getenv("IMAGE3D_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("IMAGE3D_KEY_ALIAS")
                keyPassword = System.getenv("IMAGE3D_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
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
        compose = true
        buildConfig = true
    }
    packaging {
        jniLibs.useLegacyPackaging = false
    }
}

dependencies {
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.30.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.core:core-ktx:1.15.0")

    testImplementation("junit:junit:4.13.2")
    // ONNX Runtime pour PC : permet de tester le vrai pipeline d'IA sans téléphone (RealModelTest)
    testImplementation("com.microsoft.onnxruntime:onnxruntime:1.30.0")
}

tasks.withType<Test>().configureEach {
    for (p in listOf("glbOut", "image3d.models", "image3d.image", "image3d.encoder", "image3d.resolution", "image3d.glbOut")) {
        System.getProperty(p)?.let { systemProperty(p, it) }
    }
    maxHeapSize = "4g"
}
