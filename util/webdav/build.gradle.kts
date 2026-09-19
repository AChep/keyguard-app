plugins {
    id("keyguard.quality")
    id("keyguard.kotlin-multiplatform-library")
    id("keyguard.detekt-custom-rules")
}

detektCustomRules {
    kmpCompilation(targetName = "android", compilationName = "main")
}

kotlin {
    android {
        namespace = "com.artemchep.keyguard.util.webdav"
    }

    sourceSets {
        getByName("commonMain") {
            dependencies {
                implementation(project(":util:io"))
                api(libs.ktor.ktor.client.core)
                api(libs.kotlinx.coroutines.core)
                api(libs.kotlinx.io.core)
            }
        }
        getByName("commonTest") {
            dependencies {
                implementation(libs.ktor.ktor.client.mock)
                implementation(libs.kotlinx.coroutines.test)
            }
        }
        all {
            languageSettings.optIn("kotlin.ExperimentalStdlibApi")
            languageSettings.optIn("kotlin.io.encoding.ExperimentalEncodingApi")
            languageSettings.optIn("kotlin.time.ExperimentalTime")
        }
    }
}
