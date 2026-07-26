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
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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
    // 通信するための材料
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // 魔法を非同期で唱えるための材料
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("com.google.android.material:material:1.11.0") 

    // UIアニメーション用の材料
    implementation("com.airbnb.android:lottie:6.4.0")
    // 画像読み込み用の材料
    implementation("com.github.bumptech.glide:glide:4.16.0")

    // VOICEVOX ローカルエンジン (ローカルプロジェクトとして読み込む)
    implementation(project(":lib"))
}