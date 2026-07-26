pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        // 公式 java_packages.zip を scripts/prepare-binaries.sh が展開した
        // ローカル Maven リポジトリ(voicevoxcore-android AAR)
        maven { url = uri("local-maven") }
    }
}

rootProject.name = "voicevox-core-android"
include(":lib")
include(":example")
