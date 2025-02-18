//import org.jetbrains.dokka.gradle.DokkaTaskPartial


// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false

    id("com.google.devtools.ksp") version "2.1.0-1.0.29" apply false

//    alias(libs.plugins.dokka)
//    alias(libs.plugins.ktlint)
//    alias(libs.plugins.compose.compiler) apply false

}

//buildscript {
//    ext.readium_version = '3.0.3'
//}
//
//allprojects {
//    repositories {
//        mavenCentral()
//    }
//}


//subprojects {
//    if (name != "test-app") {
//        apply(plugin = "org.jetbrains.dokka")
//    }
//    apply(plugin = "org.jlleitschuh.gradle.ktlint")
//
//    ktlint {
//        android.set(true)
//    }
//}
//
//tasks.register("cleanDocs", Delete::class).configure {
//    delete("${project.rootDir}/docs/readium", "${project.rootDir}/docs/index.md", "${project.rootDir}/site")
//}
//
//tasks.withType<DokkaTaskPartial>().configureEach {
//    dokkaSourceSets {
//        configureEach {
//            reportUndocumented.set(false)
//            skipEmptyPackages.set(false)
//            skipDeprecated.set(true)
//        }
//    }
//}
//
//tasks.named<org.jetbrains.dokka.gradle.DokkaMultiModuleTask>("dokkaGfmMultiModule").configure {
//    outputDirectory.set(file("${projectDir.path}/docs"))
//}