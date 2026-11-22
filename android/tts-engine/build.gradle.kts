plugins {
    id("com.android.application") version "9.1.1"
    id("org.jetbrains.kotlin.android") version "2.2.20"
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.20"
}

// The engine speaks through the same Kotlin wrapper apps copy, android/PhononTTS.kt, so it is
// compiled from there rather than kept as a second copy.
abstract class ShareWrapper : DefaultTask() {
    @get:InputFile abstract val source: RegularFileProperty
    @get:OutputDirectory abstract val output: DirectoryProperty

    @TaskAction
    fun copy() {
        // The NPU library ships its own ai.gradium.phonon.PhononTTS, so the CPU wrapper moves to .cpu.
        val text = source.get().asFile.readText()
        val moved = text.replaceFirst("package ai.gradium.phonon\n", "package ai.gradium.phonon.cpu\n")
        check(moved != text) { "PhononTTS.kt package line not found" }
        output.file("PhononTTS.kt").get().asFile.writeText(moved)
    }
}
val shareWrapper = tasks.register<ShareWrapper>("shareWrapper") { source.set(file("../PhononTTS.kt")) }
androidComponents.onVariants { it.sources.java?.addGeneratedSourceDirectory(shareWrapper, ShareWrapper::output) }

android {
    namespace = "ai.gradium.phonon.tts"
    compileSdk = 37
    ndkVersion = "27.2.12479018"
    defaultConfig {
        applicationId = "io.github.zwiebelrostbraten.phonontts"
        minSdk = 24
        targetSdk = 36
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
        resourceConfigurations += listOf("en", "de", "fr", "es", "pt")
        // Fork builds (see .github/workflows/build-s23.yml) install beside the upstream app:
        // a distinct application id and label, and a version code that rises with every build.
        providers.gradleProperty("phononIdSuffix").orNull?.let { applicationIdSuffix = it }
        versionCode = providers.gradleProperty("phononVersionCode").orNull?.toInt() ?: 14
        versionName = "1.1.0" + (providers.gradleProperty("phononVersionSuffix").orNull ?: "")
        manifestPlaceholders["appLabel"] = providers.gradleProperty("phononLabel").orNull ?: "@string/app_name"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    // What android/build.sh writes.
    sourceSets["main"].jniLibs.srcDir("../jniLibs")
    buildTypes {
        release {
            isMinifyEnabled = true
            // Deliberately unsigned. Distribution repositories sign their own APKs.
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    buildFeatures { compose = true }
    packaging {
        jniLibs {
            // PhononTTS.load (NPU) needs the QNN libraries extracted to the native library directory.
            useLegacyPackaging = true
            // qnn-runtime ships every backend and every Hexagon version; the S23 bundle needs only the
            // HTP backend and the V73 stub/skel. HtpPrepare (85 MB) compiles graphs on the phone, which a
            // precompiled context binary does not need.
            excludes += listOf("libQnnGpu.so", "libQnnDsp*", "libQnnCpu.so", "libQnnSaver.so", "libQnnHtpPrepare.so",
                "libQnnHtpV68*", "libQnnHtpV69*", "libQnnHtpV75*", "libQnnHtpV79*", "libQnnHtpV81*").map { "**/$it" }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.ui:ui")
    testImplementation("junit:junit:4.13.2")
    implementation("net.java.dev.jna:jna:5.17.0@aar")
    // Phonon on the Snapdragon NPU (see libs/README.md); the local AAR does not bring its POM dependency.
    implementation(files("libs/ptts-release.aar"))
    implementation("com.qualcomm.qti:qnn-runtime:2.50.0")
    // Kokoro-82M on the Snapdragon NPU (vocoder on HTP v73, int8 front end on ORT CPU, Misaki + eSpeak NG G2P).
    implementation(files("libs/kokoro-npu-release.aar"))
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.32.0")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}

// Keep focused-test temporary files in the caller's scratch directory.
tasks.withType<Test>().configureEach {
    System.getenv("TMPDIR")?.let { systemProperty("java.io.tmpdir", it) }
}
