import com.artemchep.keyguard.buildplugins.cargo.configureNativeLibraryTests
import com.artemchep.keyguard.buildplugins.testing.E2eToolchain
import com.artemchep.keyguard.buildplugins.testing.forwardSystemProperties

plugins {
    id("keyguard.quality")
    id("keyguard.jvm-e2e")
    id("keyguard.native-crypto-consumer")
}

keyguardE2e {
    toolchain = E2eToolchain.GPG
}

// The Kotlin side of the private channel runs over util/agent-ipc.
configureNativeLibraryTests(":util:agent-ipc")

dependencies {
    gpgE2eTestImplementation(project(":integration:testProcess"))
    gpgE2eTestImplementation(project(":common"))
    gpgE2eTestImplementation(libs.kotlinx.coroutines.core)
    gpgE2eTestImplementation(libs.kotlinx.coroutines.test)
}

tasks.named<Test>("gpgE2eTest") {
    description = "Drives a real gpg client against the real Keyguard GPG agent (Rust binary + " +
        "Kotlin IPC server), validating signing and decryption end to end."
    forwardSystemProperties(listOf("keyguard.gpgE2e.verbose", "keyguard.gpg.binDir"))
}
