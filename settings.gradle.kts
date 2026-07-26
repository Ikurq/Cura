pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // JitPack（GitHub上のライブラリを直接読み込むための場所）
        maven { url = uri("https://jitpack.io") }
        // VOICEVOX CORE のローカルバイナリ
        maven { url = uri("android/local-maven") }
    }
}

rootProject.name = "Cura"
include(":app")
include(":lib")
project(":lib").projectDir = file("android/lib")
