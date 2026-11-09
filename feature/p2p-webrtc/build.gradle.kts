import com.artemchep.keyguard.buildplugins.cargo.configureNativeLibraryTests

plugins {
    id("keyguard.quality")
    id("keyguard.kotlin-multiplatform-library")
    id("keyguard.compose-free")
    alias(libs.plugins.kotlin.plugin.serialization)
}

// Never a Wear OS dependency: Wear does not ship WebRTC. The
// phone, desktop and Apple roots bind this transport; `common` never sees it.

// The loopback tests run the real WebRTC stack on the JVM test tasks.
configureNativeLibraryTests(":util:webrtc")

kotlin {
    sourceSets {
        commonMain.dependencies {
            // The public API speaks the protocol's transport interfaces and
            // takes a WebRTC runtime and ICE servers.
            api(project(":feature:p2p-sync"))
            api(project(":util:webrtc"))
            api(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
        }
        commonTest.dependencies {
            // `runTest` is the only multiplatform way to await coroutines
            // from common test code.
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
