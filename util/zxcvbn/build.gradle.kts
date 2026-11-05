import com.artemchep.keyguard.buildplugins.kotlin.sharedAppleMain
import com.artemchep.keyguard.buildplugins.kotlin.sharedIosTest
import com.artemchep.keyguard.buildplugins.kotlin.sharedJvmMain

plugins {
    id("keyguard.quality")
    id("keyguard.kotlin-multiplatform-library")
    id("keyguard.rust-multiplatform-library")
}

kotlin {
    android {
        packaging {
            jniLibs.useLegacyPackaging = false
        }
    }

    sourceSets {
        getByName("commonMain") {
            dependencies {
                implementation(project(":util:ffi"))
            }
        }
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
    }
}
