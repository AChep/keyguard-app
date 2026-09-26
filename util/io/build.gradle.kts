import com.artemchep.keyguard.buildplugins.kotlin.sharedAppleMain
import com.artemchep.keyguard.buildplugins.kotlin.sharedIosTest
import com.artemchep.keyguard.buildplugins.kotlin.sharedJvmMain

plugins {
    id("keyguard.quality")
    id("keyguard.kotlin-multiplatform-library")
    id("keyguard.rust-multiplatform-library")
    id("keyguard.native-io-consumer")
}

kotlin {
    android {
        namespace = "com.artemchep.keyguard.util.io"

        packaging {
            jniLibs.useLegacyPackaging = false
        }
    }

    sourceSets {
        getByName("commonMain") {
            dependencies {
                api(libs.kotlinx.io.core)
            }
        }
        getByName("commonTest") {
            dependencies {
                implementation(libs.kotlinx.coroutines.core)
            }
        }

        sharedJvmMain()
        sharedAppleMain()
        sharedIosTest()

        all {
            languageSettings.optIn("kotlin.ExperimentalStdlibApi")
            languageSettings.optIn("kotlin.uuid.ExperimentalUuidApi")
        }
    }
}
