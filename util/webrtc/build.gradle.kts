import com.artemchep.keyguard.buildplugins.kotlin.sharedAppleMain
import com.artemchep.keyguard.buildplugins.kotlin.sharedJvmMain

plugins {
    id("keyguard.quality")
    id("keyguard.kotlin-multiplatform-library")
    id("keyguard.compose-free")
    id("keyguard.rust-multiplatform-library")
}

// Never a Wear OS dependency: Wear does not ship WebRTC.

kotlin {
    android {
        packaging {
            jniLibs.useLegacyPackaging = false
        }
    }

    sourceSets {
        commonMain.dependencies {
            api(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(project(":util:ffi"))
        }
        commonTest.dependencies {
            // `runTest` is the only multiplatform way to await coroutines
            // from common test code.
            implementation(libs.kotlinx.coroutines.test)
        }

        sharedJvmMain()
        sharedAppleMain()
    }
}
