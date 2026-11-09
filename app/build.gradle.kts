plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "app.carnet"
    compileSdk = 35
    // Needed so release builds strip debug symbols from native libraries.
    ndkVersion = "27.2.12479018"

    defaultConfig {
        applicationId = "app.carnet"
        minSdk = 26
        targetSdk = 35
        // CI passes the run number so every published build is newer than the previous one (in-app updates).
        val build = (project.findProperty("carnet.build") as String?)?.toIntOrNull() ?: 0
        versionCode = 100 + build
        versionName = if (build > 0) "1.2.$build" else "1.2-dev"
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    signingConfigs {
        // A fixed key in the repo so every CI build can update the previous install (debug-grade, not for store release).
        create("shared") {
            storeFile = file("carnet-shared.keystore")
            storePassword = "carnet-shared"
            keyAlias = "carnet"
            keyPassword = "carnet-shared"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("shared")
        }
        debug {
            signingConfig = signingConfigs.getByName("shared")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    buildFeatures { compose = true; buildConfig = true }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all {
                it.maxHeapSize = "3g"
                it.systemProperty("robolectric.dependency.repo.url", "https://maven-central.storage-download.googleapis.com/maven2")
            }
        }
    }

    androidResources {
        // Model files are memory-mapped/streamed; keep them uncompressed in the APK.
        noCompress += listOf("onnx", "pieces", "bin", "gguf")
    }

    packaging {
        resources.excludes += listOf("/META-INF/{AL2.0,LGPL2.1}", "META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*",
            // Post-quantum crypto tables pulled in by PdfBox's BouncyCastle; never used.
            "org/bouncycastle/pqc/**", "org/bouncycastle/x509/**")
        // Compressed native libraries: a much smaller download (updates), unpacked once at install.
        jniLibs.useLegacyPackaging = true
    }
}

// Translation models are downloaded in the app (Models screen), not bundled: updates stay small.
// The catalog (models.json) is bundled when available so the app knows the files even offline.
// The PDF translator is shared with the desktop test harness; rewrite its PDFBox imports for Android.
val genPdf = layout.buildDirectory.dir("generated/pdfshared")
val generatePdfShared by tasks.registering(Copy::class) {
    from(rootProject.file("pdfshared"))
    into(genPdf)
    filter { line: String -> line.replace("org.apache.pdfbox", "com.tom_roush.pdfbox") }
}
android.sourceSets["main"].kotlin.srcDir(genPdf)
tasks.named("preBuild") { dependsOn(generatePdfShared) }

dependencies {
    implementation(project(":core"))
    if (providers.gradleProperty("carnet.native").orNull == "true") {
        implementation(project(":native-llama"))
        implementation(project(":native-whisper"))
    }

    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.animation:animation")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.compose.ui:ui-tooling-preview")

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.navigation:navigation-compose:2.8.5")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-service:2.8.7")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    val camerax = "1.4.1"
    implementation("androidx.camera:camera-core:$camerax")
    implementation("androidx.camera:camera-camera2:$camerax")
    implementation("androidx.camera:camera-lifecycle:$camerax")
    implementation("androidx.camera:camera-view:$camerax")
    implementation("androidx.exifinterface:exifinterface:1.3.7")

    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.31.1")
    // Bundled (offline) ML Kit models — no Google Play download needed.
    implementation("com.google.mlkit:text-recognition:16.0.1")
    implementation("com.google.mlkit:barcode-scanning:17.3.0")
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")
    implementation("com.google.zxing:core:3.5.3")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    testImplementation("androidx.test:core-ktx:1.6.1")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
