plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.android.kmp.library)
}

kotlin {
    // AGP 9 以降、com.android.library は KMP プラグインと併用できない。
    // KMP から Android ライブラリを作るには専用プラグイン + この DSL を使う。
    androidLibrary {
        namespace = "com.example.voicevox.core"
        compileSdk = 36
        // app と揃える。kotlinx-datetime は API 26 未満だと java.time が無いので、
        // 利用側(app)で core library desugaring を有効にすること。
        minSdk = 24
    }

    // iosX64 は Rosetta 前提の Intel シミュレータ用。実機と Apple Silicon の
    // シミュレータはこの2つで足りるので、ビルド時間を優先して外している。
    listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.binaries.framework {
            baseName = "Shared"
            isStatic = true
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            api(libs.kotlinx.datetime)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
