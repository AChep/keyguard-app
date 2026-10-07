import com.artemchep.keyguard.buildplugins.cargo.CargoBuildTask
import com.artemchep.keyguard.buildplugins.cargo.HostPlatform
import com.artemchep.keyguard.buildplugins.cargo.detectHostPlatform
import com.artemchep.keyguard.buildplugins.kotlin.sharedAppleMain
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

val desktopHost = detectHostPlatform()
if (desktopHost.isMacOs) {
    tasks.named<CargoBuildTask>("cargoBuildNativeYubikeyDesktop") {
        // Match Rust's desktop target baseline independently of the native Apple app.
        val minimumVersion = if (desktopHost == HostPlatform.MacosArm64) "11.0" else "10.12"
        environmentVariables.put("MACOSX_DEPLOYMENT_TARGET", minimumVersion)
    }
}

kotlin {
    sourceSets {
        commonMain.dependencies { api(libs.kotlinx.coroutines.core) }
        getByName("androidMain").dependencies {
            implementation(libs.yubico.yubikit.android)
            implementation(libs.yubico.yubikit.yubiotp)
        }
        sharedAppleMain()
        sharedNativeClient()
    }
}
