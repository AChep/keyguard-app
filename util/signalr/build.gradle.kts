plugins {
    id("keyguard.quality")
    id("keyguard.kotlin-multiplatform-library")
    alias(libs.plugins.kotlin.plugin.serialization)
}

kotlin {
    android {
        namespace = "com.artemchep.keyguard.util.signalr"
    }

    sourceSets {
        getByName("commonMain") {
            dependencies {
                api(project(":util:messagepack"))
                api(libs.ktor.ktor.client.core)
                api(libs.ktor.ktor.client.websockets)
                api(libs.kotlinx.coroutines.core)
                api(libs.kotlinx.serialization.json)
            }
        }
        getByName("commonTest") {
            dependencies {
                implementation(libs.ktor.ktor.client.mock)
                implementation(libs.kotlinx.coroutines.test)
            }
        }
    }
}
