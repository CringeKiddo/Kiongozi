plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.kiongozi.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.kiongozi.app"
        minSdk = 27 // NNAPI (GPU/NPU) execution provider needs API 27+
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        ndk {
            // arm64 only keeps the APK small (ORT ships .so files for every ABI).
            // Add "armeabi-v7a" here if your phone is 32-bit.
            abiFilters += listOf("arm64-v8a")
        }
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

    buildFeatures {
        compose = true
    }

    // Keep the model uncompressed inside the APK (faster to read, no extra copy cost)
    androidResources {
        noCompress += listOf("onnx", "bin")
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    // Jetpack Compose
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")

    // AndroidX
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // Offline neural inference (CPU + NNAPI GPU/NPU execution providers)
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.20.0")
}
