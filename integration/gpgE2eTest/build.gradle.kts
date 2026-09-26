import com.artemchep.keyguard.buildplugins.testing.E2eToolchain
import com.artemchep.keyguard.buildplugins.testing.VerifyE2eEnvironmentTask
import com.artemchep.keyguard.buildplugins.testing.forwardSystemProperties
import org.gradle.api.tasks.testing.Test

plugins {
    id("keyguard.quality")
    id("keyguard.jvm-e2e")
    id("keyguard.native-crypto-consumer")
}

dependencies {
    "gpgE2eTestImplementation"(project(":common"))
    "gpgE2eTestImplementation"(libs.kotlinx.coroutines.core)
    "gpgE2eTestImplementation"(libs.kotlinx.coroutines.test)
}

val verifyE2eEnvironment = tasks.register<VerifyE2eEnvironmentTask>("verifyE2eEnvironment") {
    toolchain.set(E2eToolchain.GPG)
}

tasks.named<Test>("gpgE2eTest") {
    description = "Drives a real gpg client against the real Keyguard GPG agent (Rust binary + " +
        "Kotlin IPC server), validating signing and decryption end to end."
    dependsOn(verifyE2eEnvironment)

    // Tests build the agent with Cargo and use short temporary GNUPGHOME paths for Unix sockets.
    systemProperty("keyguard.repoRoot", rootDir.absolutePath)
    forwardSystemProperties(listOf("keyguard.gpgE2e.verbose", "keyguard.gpg.binDir"))
}
