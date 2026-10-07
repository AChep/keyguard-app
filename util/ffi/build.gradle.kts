import com.artemchep.keyguard.buildplugins.kotlin.sharedJvmMain
import com.artemchep.keyguard.buildplugins.kotlin.sharedJvmTest

plugins {
    id("keyguard.quality")
    id("keyguard.kotlin-multiplatform-library")
    id("keyguard.compose-free")
}

// rust/ holds the keyguard-ffi crate that the other native workspaces use by
// path. This project builds no native library, so no keyguard.rust-* plugin.

kotlin {
    sourceSets {
        commonMain.dependencies { implementation(libs.kotlinx.coroutines.core) }
        commonTest.dependencies { implementation(libs.kotlinx.coroutines.test) }
        sharedJvmMain()
        sharedJvmTest()
    }
}
