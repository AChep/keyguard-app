plugins {
    id("keyguard.quality")
    id("keyguard.kotlin-multiplatform-library")
    id("keyguard.compose-free")
    alias(libs.plugins.kotlin.plugin.serialization)
}

kotlin {
    sourceSets {
        getByName("commonTest") {
            dependencies {
                implementation(libs.kotlinx.coroutines.test)
            }
        }
        getByName("commonMain") {
            dependencies {
                api(libs.kotlinx.serialization.json)
                api(libs.kotlinx.coroutines.core)
            }
        }
    }
}
