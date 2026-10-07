import com.artemchep.keyguard.buildplugins.testing.E2eToolchain
import com.artemchep.keyguard.buildplugins.testing.forwardSystemProperties

plugins {
    id("keyguard.quality")
    id("keyguard.jvm-e2e")
    id("keyguard.native-crypto-consumer")
}

keyguardE2e {
    toolchain = E2eToolchain.SSH
}

dependencies {
    sshE2eTestImplementation(project(":common"))
    sshE2eTestImplementation(project(":util:crypto"))
    sshE2eTestImplementation(libs.kotlinx.coroutines.core)
    sshE2eTestImplementation(libs.kotlinx.coroutines.test)
}

tasks.named<Test>("sshE2eTest") {
    description = "Drives real OpenSSH clients against the real Keyguard SSH agent (Rust binary + " +
        "Kotlin IPC server), validating key listing and signing end to end."
    forwardSystemProperties(listOf("keyguard.sshE2e.verbose"))
}
