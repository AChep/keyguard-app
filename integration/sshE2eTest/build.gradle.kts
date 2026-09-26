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
    "sshE2eTestImplementation"(project(":common"))
    "sshE2eTestImplementation"(project(":util:crypto"))
    "sshE2eTestImplementation"(libs.kotlinx.coroutines.core)
    "sshE2eTestImplementation"(libs.kotlinx.coroutines.test)
}

val verifyE2eEnvironment = tasks.register<VerifyE2eEnvironmentTask>("verifyE2eEnvironment") {
    toolchain.set(E2eToolchain.SSH)
}

tasks.named<Test>("sshE2eTest") {
    description = "Drives real OpenSSH clients against the real Keyguard SSH agent (Rust binary + " +
        "Kotlin IPC server), validating key listing and signing end to end."
    dependsOn(verifyE2eEnvironment)

    // Tests build the agent with Cargo and use short temporary paths for Unix sockets.
    systemProperty("keyguard.repoRoot", rootDir.absolutePath)
    forwardSystemProperties(listOf("keyguard.sshE2e.verbose"))
}
