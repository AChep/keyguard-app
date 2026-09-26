plugins {
    id("keyguard.quality")
    id("keyguard.kotlin-multiplatform-library")
    id("keyguard.compose-free")
}

kotlin {
    android {
        namespace = "com.artemchep.keyguard.standard.presentation"
    }

    sourceSets {
        getByName("commonMain") {
            dependencies {
                api(libs.kotlinx.coroutines.core)
            }
        }
        getByName("commonTest") {
            dependencies {
                implementation(libs.kotlinx.coroutines.test)
            }
        }
    }
}
