plugins {
    id("keyguard.quality")
    id("keyguard.kotlin-multiplatform-library")
    id("keyguard.compose-free")
    alias(libs.plugins.kotlin.plugin.serialization)
}

kotlin {
    sourceSets {
        getByName("commonMain") {
            dependencies {
                implementation(project(":standard:presentation"))
                api(libs.kotlinx.serialization.json)
                api(libs.kotlinx.coroutines.core)
            }
        }
        getByName("commonTest") {
            dependencies {
                implementation(libs.kotlinx.coroutines.test)
            }
        }
        // The golden messages of the protocol, fixtures/, which the
        // extension's tests check as well.
        getByName("desktopTest").resources.srcDir("../../browserExtension/protocol")
    }
}
