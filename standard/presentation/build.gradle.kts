plugins {
    id("keyguard.quality")
    id("keyguard.kotlin-multiplatform-library")
    id("keyguard.compose-free")
}

kotlin {
    sourceSets {
        getByName("commonMain") {
            dependencies {
                api(libs.kotlinx.coroutines.core)
                implementation(libs.kotlinx.atomicfu)
            }
        }
        getByName("commonTest") {
            dependencies {
                implementation(libs.kotlinx.coroutines.test)
            }
        }
    }
}
