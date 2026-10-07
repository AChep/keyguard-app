import com.artemchep.keyguard.buildplugins.cargo.CargoBuildTask
import com.artemchep.keyguard.buildplugins.kotlin.sharedAppleMain
import com.artemchep.keyguard.buildplugins.kotlin.sharedJvmMain
import com.artemchep.keyguard.buildplugins.kotlin.sharedNativeClient

plugins {
    id("keyguard.quality")
    id("keyguard.kotlin-multiplatform-library")
    id("keyguard.compose-free")
    id("keyguard.rust-desktop-library")
}

// Keep the Rust runtime in separate objects when linking several static Rust libraries.
tasks.withType<CargoBuildTask>().configureEach {
    environmentVariables.put("CARGO_PROFILE_RELEASE_LTO", "false")
}

kotlin {
    sourceSets {
        commonMain.dependencies { api(libs.kotlinx.coroutines.core) }
        sharedJvmMain()
        sharedAppleMain()
        sharedNativeClient()
    }
}
