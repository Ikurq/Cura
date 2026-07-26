import java.net.URL
import java.io.InputStream
import java.io.OutputStream
import org.gradle.api.DefaultTask
import org.gradle.api.file.ArchiveOperations
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import javax.inject.Inject

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
    `maven-publish`
}

android {
    namespace = "jp.voicevox.android"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

dependencies {
    // 公式 Java API(JNIブリッジ内蔵、local-maven から解決)
    api("jp.hiroshiba.voicevoxcore:voicevoxcore-android:0.16.4")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")

    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}

abstract class DownloadVoicevoxCoreTask : DefaultTask() {
    @get:Inject
    abstract val fs: FileSystemOperations

    @get:Inject
    abstract val archiveOps: ArchiveOperations

    @get:Internal
    abstract val localMavenDir: DirectoryProperty

    @get:Internal
    abstract val zipFile: RegularFileProperty

    @get:OutputDirectory
    val outputDir: DirectoryProperty get() = localMavenDir

    @TaskAction
    fun action() {
        val dir = localMavenDir.get().asFile
        val zip = zipFile.get().asFile
        if (!dir.exists()) {
            val downloadUrl = "https://github.com/VOICEVOX/voicevox_core/releases/download/0.16.4/java_packages.zip"
            println("Downloading Voicevox Core Java packages from $downloadUrl...")
            try {
                URL(downloadUrl).openStream().use { input ->
                    zip.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                println("Extracting to ${dir.absolutePath}...")
                fs.copy {
                    from(archiveOps.zipTree(zip))
                    into(dir)
                }
                zip.delete()
                println("Download and extraction complete.")
            } catch (e: Exception) {
                println("Failed to download Voicevox Core: ${e.message}")
                if (zip.exists()) zip.delete()
                throw e
            }
        }
    }
}

val downloadVoicevoxCore by tasks.registering(DownloadVoicevoxCoreTask::class) {
    group = "setup"
    description = "Downloads and extracts the official voicevoxcore-android AAR"
    localMavenDir.set(layout.projectDirectory.dir("../local-maven"))
    zipFile.set(layout.projectDirectory.file("../java_packages.zip"))
}

tasks.named("preBuild") {
    dependsOn(downloadVoicevoxCore)
}

publishing {
    publications {
        register<MavenPublication>("release") {
            groupId = "jp.voicevox"
            artifactId = "voicevox-core-android"
            // JitPack はタグ名を VERSION 環境変数として渡す。
            // 例: タグ android-v0.1.0 → 生成される artifact も同名バージョンに揃うため
            // 利用者は `com.github.Shakenokirimi12:vv-mobile:android-v0.1.0` で解決できる。
            version = System.getenv("VERSION") ?: "0.1.0"
            afterEvaluate {
                from(components["release"])
            }
        }
    }
}
