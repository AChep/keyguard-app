plugins {
    id("keyguard.quality")
    id("keyguard.kotlin-multiplatform")
    id("keyguard.compose-free")
    id("keyguard.rust-jni-library")
    alias(libs.plugins.kotlin.plugin.serialization)
}

kotlin {
    jvm("desktop")
    sourceSets {
        commonMain.dependencies {
            api(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.protobuf)
        }
        getByName("desktopMain").dependencies {
            implementation(project(":util:ffi"))
        }
        // The schema's shared encodings, fixtures/, which the Rust tests check as well.
        getByName("desktopTest").resources.srcDir("schema")
    }
}
