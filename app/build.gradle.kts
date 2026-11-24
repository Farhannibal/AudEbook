import java.util.Properties

plugins {
//    alias(libs.plugins.android.application)
////    alias(libs.plugins.android.library)
//    alias(libs.plugins.kotlin.android)
//    alias(libs.plugins.kotlin.compose)
//
////    id("com.android.application")
//
////    kotlin("android")
//    kotlin("plugin.parcelize")
////    id("com.google.devtools.ksp")
//
//    alias(libs.plugins.ksp)
////    alias(libs.plugins.compose.compiler)


    id("com.android.application")
    kotlin("android")
    kotlin("plugin.parcelize")
//    kotlin("kapt")
    alias(libs.plugins.ksp)
    alias(libs.plugins.compose.compiler)

//    id("com.google.devtools.ksp")


}

android {
    namespace = "com.example.audebook"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.farhannibal.audebook"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Release signing comes from keystore.properties in the project root, which stays out of git:
    //   storeFile=C:/path/to/upload-keystore.jks
    //   storePassword=...
    //   keyAlias=upload
    //   keyPassword=...
    // Without it, release builds are left unsigned.
    val keystoreFile = rootProject.file("keystore.properties")
    val keystore = Properties().apply {
        if (keystoreFile.exists()) keystoreFile.inputStream().use { load(it) }
    }
    signingConfigs {
        if (keystoreFile.exists()) {
            create("upload") {
                storeFile = file(keystore.getProperty("storeFile"))
                storePassword = keystore.getProperty("storePassword")
                keyAlias = keystore.getProperty("keyAlias")
                keyPassword = keystore.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            // Phones, plus both emulator image types (the Pixel 3a API 30 image is 32-bit x86)
            ndk { abiFilters += listOf("arm64-v8a", "x86_64", "x86") }
        }
        release {
            // 64-bit ARM phones only; older 32-bit phones are too slow to keep up with Whisper
            ndk { abiFilters += listOf("arm64-v8a") }
            signingConfigs.findByName("upload")?.let { signingConfig = it }
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    androidResources {
        // Stored uncompressed so the model can be memory-mapped straight from the APK
        noCompress += "tflite"
    }
    compileOptions {
        isCoreLibraryDesugaringEnabled = true

        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
//        freeCompilerArgs = freeCompilerArgs + "-opt-in=kotlin.RequiresOptIn"
    }
    buildFeatures {
        viewBinding = true
        compose = true
        buildConfig = true
    }
}

// Collects the licenses of every dependency for the About screen. Applied here, after android {},
// because it needs the Android plugin's extension to exist already
apply(plugin = "com.google.android.gms.oss-licenses-plugin")

dependencies {

//    coreLibraryDesugaring(libs.desugar.jdk.libs)
//
//    implementation(libs.kotlin.stdlib)
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.0.4")

    implementation(libs.kotlin.stdlib)

//    implementation "org.readium.kotlin-toolkit:readium-shared:$readium_version"
//    implementation "org.readium.kotlin-toolkit:readium-streamer:$readium_version"
//    implementation "org.readium.kotlin-toolkit:readium-navigator:$readium_version"
//    implementation "org.readium.kotlin-toolkit:readium-opds:$readium_version"

//    implementation(project(":readium:readium-shared"))
    implementation("org.readium.kotlin-toolkit:readium-shared:3.0.3")
    implementation("org.readium.kotlin-toolkit:readium-streamer:3.0.3")
    implementation("org.readium.kotlin-toolkit:readium-navigator:3.0.3")
    implementation("org.readium.kotlin-toolkit:readium-navigator-media-audio:3.0.3")
    implementation("org.readium.kotlin-toolkit:readium-navigator-media-tts:3.0.3")
    // Only required if you want to support audiobooks using ExoPlayer.
    implementation("org.readium.kotlin-toolkit:readium-adapter-exoplayer:3.0.3")
    implementation("org.readium.kotlin-toolkit:readium-opds:3.0.3")

    // LiteRT is TensorFlow Lite under its new name, with the same org.tensorflow.lite API.
    // Unlike TensorFlow Lite 2.14, its native library supports 16 KB memory pages.
    implementation("com.google.ai.edge.litert:litert:1.4.2")
    implementation("com.google.ai.edge.litert:litert-support:1.4.2")
    // Pulled in by Compose; this version's native library supports 16 KB memory pages
    implementation("androidx.graphics:graphics-path:1.1.0")


    implementation("info.debatty:java-string-similarity:2.0.0")



    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)


    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.browser)
    implementation(libs.androidx.cardview)

    implementation(libs.bundles.compose)
//    implementation(libs.androidx.compose.ui)

    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.core)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.androidx.lifecycle.common)
    implementation(libs.androidx.navigation.fragment)
    implementation(libs.androidx.navigation.ui)
    implementation(libs.androidx.paging)
    implementation(libs.androidx.recyclerview)
    implementation(libs.androidx.viewpager2)
    implementation(libs.androidx.webkit)
    implementation(libs.google.material)
    implementation(libs.timber)
    implementation(libs.picasso)
    implementation(libs.joda.time)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.jsoup)
    implementation("com.google.android.gms:play-services-oss-licenses:17.1.0")


    implementation(libs.bundles.media3)

    // Room database
    implementation(libs.bundles.room)
    ksp(libs.androidx.room.compiler)

//    implementation("androidx.room:room-runtime:2.5.0")
//    implementation("androidx.room:room-ktx:2.5.0")
//    ksp("androidx.room:room-compiler:2.5.0")

//    implementation("androidx.room:room-runtime:2.4.2")
//    kapt("androidx.room:room-compiler:2.4.2")
//    implementation("androidx.room:room-ktx:2.4.2")

}