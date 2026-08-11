plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.example.voicevox"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.voicevox"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "2.0"

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

    // UIアニメーション用の材料
    implementation("com.airbnb.android:lottie:6.4.0")
    // 画像読み込み用の材料
    implementation(libs.glide)
    implementation(libs.kotlinx.serialization.json)

    // VOICEVOX ローカルエンジン (ローカルプロジェクトとして読み込む)
    implementation(project(":lib"))
}

// リリースビルド時の依存関係解決の順序を修正
tasks.matching { it.name.contains("collect") && it.name.contains("Dependencies") }.configureEach {
    dependsOn(":lib:downloadVoicevoxCore")
}