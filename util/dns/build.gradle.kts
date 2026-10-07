import com.artemchep.keyguard.buildplugins.kotlin.sharedAppleMain
import com.artemchep.keyguard.buildplugins.kotlin.sharedAppleTest

plugins {
    id("keyguard.quality")
    id("keyguard.kotlin-multiplatform-library")
    id("keyguard.compose-free")
}

kotlin {
    sourceSets {
        getByName("commonMain").dependencies {
            api(libs.kotlinx.coroutines.core)
        }
        getByName("commonTest").dependencies {
            implementation(libs.kotlinx.coroutines.test)
        }
        getByName("androidMain").dependencies {
            implementation(libs.androidx.annotation)
        }
        sharedAppleMain()
        sharedAppleTest()
    }
}
