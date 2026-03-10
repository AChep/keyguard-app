import binaries.binaryName
import binaries.detectHostPlatform
import binaries.registerCargoPackagedArtifact

/**
 * Gradle build configuration for the desktopSshAgent module.
 *
 * This module contains a standalone Rust binary (keyguard-ssh-agent) that
 * implements the SSH agent protocol. The binary communicates with the main
 * Keyguard JVM application over a Protobuf-based IPC channel.
 *
 * The build delegates to Cargo (Rust's build system) and copies the resulting
 * binary into Compose-compatible app resources.
 */

val hostPlatform = detectHostPlatform()
val sshAgentBinaryName = hostPlatform.binaryName("keyguard-ssh-agent")

registerCargoPackagedArtifact(
    hostPlatform = hostPlatform,
    sourceDirPath = "src",
    rustTarget = hostPlatform.sshAgentRustTarget,
    cargoBinaryName = sshAgentBinaryName,
    packagedBinaryName = sshAgentBinaryName,
    compileTaskName = Tasks.compileSshAgentUniversal,
)
