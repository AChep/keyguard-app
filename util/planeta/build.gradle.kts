import com.artemchep.keyguard.buildplugins.kotlin.configureComposeIosSwiftRuntime

plugins {
    id("keyguard.quality")
    id("keyguard.kotlin-multiplatform-library")
    alias(libs.plugins.compose)
    alias(libs.plugins.kotlin.plugin.compose)
}

kotlin {
    android {
        namespace = "com.artemchep.keyguard.util.planeta"
    }

    sourceSets {
        getByName("commonMain") {
            dependencies {
                implementation(libs.jetbrains.compose.runtime)
                implementation(libs.jetbrains.compose.foundation)
            }
        }
    }
}

configureComposeIosSwiftRuntime()
