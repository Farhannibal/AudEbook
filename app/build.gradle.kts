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
    compileSdk = 35

    defaultConfig {
//        applicationId = "com.example.audebook"
        minSdk = 26
        targetSdk = 35
//        versionCode = 1
//        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
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
//    implementation "org.readium.kotlin-toolkit:readium-lcp:$readium_version"

//    implementation(project(":readium:readium-shared"))
    implementation("org.readium.kotlin-toolkit:readium-shared:3.0.3")
    implementation("org.readium.kotlin-toolkit:readium-streamer:3.0.3")
    implementation("org.readium.kotlin-toolkit:readium-navigator:3.0.3")
    implementation("org.readium.kotlin-toolkit:readium-navigator-media-audio:3.0.3")
    implementation("org.readium.kotlin-toolkit:readium-navigator-media-tts:3.0.3")
    // Only required if you want to support audiobooks using ExoPlayer.
    implementation("org.readium.kotlin-toolkit:readium-adapter-exoplayer:3.0.3")
    implementation("org.readium.kotlin-toolkit:readium-opds:3.0.3")
    implementation("org.readium.kotlin-toolkit:readium-lcp:3.0.3")
    // Only required if you want to support PDF files using PDFium.
    implementation("org.readium.kotlin-toolkit:readium-adapter-pdfium:3.0.3")

    // Main TensorFlow Lite library
    implementation("org.tensorflow:tensorflow-lite:2.14.0")
    implementation("org.tensorflow:tensorflow-lite-support:0.4.4")

    implementation("com.arthenica:ffmpeg-kit-full:6.0-2.LTS")



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