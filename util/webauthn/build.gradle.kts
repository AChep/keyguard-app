import com.artemchep.keyguard.buildplugins.kotlin.sharedAppleMain

plugins {
    id("keyguard.quality")
    id("keyguard.kotlin-multiplatform-library")
    id("keyguard.compose-free")
    id("keyguard.crypto-dependency-check")
    id("keyguard.native-crypto-consumer")
    alias(libs.plugins.kotlin.plugin.serialization)
}

kotlin {
    android {
        namespace = "com.artemchep.keyguard.util.webauthn"
    }

    sourceSets {
        getByName("commonMain").dependencies {
            implementation(project(":util:crypto"))
            api(libs.ktor.ktor.client.core)
            api(libs.kotlinx.serialization.json)
        }
        getByName("commonTest").dependencies {
            implementation(libs.ktor.ktor.client.mock)
            implementation(libs.kotlinx.coroutines.test)
        }
        getByName("desktopMain").dependencies {
            implementation(libs.icu4j)
        }
        sharedAppleMain()

        all {
            languageSettings.optIn("kotlin.ExperimentalStdlibApi")
            languageSettings.optIn("kotlin.uuid.ExperimentalUuidApi")
        }
    }
}
