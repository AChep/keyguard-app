plugins {
    id("keyguard.quality")
    id("keyguard.kotlin-multiplatform-library")
    alias(libs.plugins.kotlin.plugin.serialization)
}

kotlin {
    android {
        namespace = "com.artemchep.keyguard.util.messagepack"
    }

    sourceSets {
        getByName("commonMain") {
            dependencies {
                api(libs.kotlinx.serialization.json)
                api(libs.kotlinx.serialization.msgpack)
            }
        }
    }
}
