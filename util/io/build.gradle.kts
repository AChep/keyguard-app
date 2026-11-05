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
                api(libs.kotlinx.io.core)
                implementation(project(":util:ffi"))
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
    }
}
