import com.artemchep.keyguard.buildplugins.kotlin.sharedAppleMain
import com.artemchep.keyguard.buildplugins.kotlin.sharedIosTest
import com.artemchep.keyguard.buildplugins.kotlin.sharedJvmMain

plugins {
    id("keyguard.quality")
    id("keyguard.kotlin-multiplatform-library")
    id("keyguard.rust-multiplatform-library")
    id("keyguard.native-zxcvbn-consumer")
}

kotlin {
    android {
        namespace = "com.artemchep.keyguard.util.zxcvbn"

        packaging {
            jniLibs.useLegacyPackaging = false
        }
    }

    sourceSets {
        getByName("commonTest") {
            dependencies {
                implementation(libs.kotlinx.coroutines.core)
                // `runTest` is the only multiplatform way to await coroutines
                // from common test code: `runBlocking` is not part of the
                // common surface of kotlinx-coroutines-core.
                implementation(libs.kotlinx.coroutines.test)
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
