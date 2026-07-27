plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.example.voicevox"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.example.voicevox"
        // VOICEVOX CORE (voicevox-core-android) が minSdk 26 を要求する
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // voicevox_core / ONNX Runtime の .so は arm64-v8a と x86_64 のみ
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }
    buildFeatures {
        viewBinding = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    // 端末内で音声合成するための材料 (VOICEVOX CORE)
    implementation("com.github.Shakenokirimi12.vv-mobile:voicevox-core-android:android-v0.1.3")
    // 魔法を非同期で唱えるための材料
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("com.google.android.material:material:1.11.0") 

    // UIアニメーション用の材料
    implementation("com.airbnb.android:lottie:6.4.0")
    // 画像読み込み用の材料
    implementation("com.github.bumptech.glide:glide:4.16.0")
}