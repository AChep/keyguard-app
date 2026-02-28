import binaries.SignAndCopyBinaryTask
import binaries.binaryName
import binaries.detectHostPlatform
import javax.inject.Inject

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

abstract class CargoBuild : DefaultTask() {
    @get:Inject
    abstract val execOperations: ExecOperations

    @get:InputDirectory
    abstract val sourceDir: DirectoryProperty

    @get:OutputFile
    abstract val outputBinary: RegularFileProperty

    @get:Input
    abstract val rustTarget: Property<String>

    @TaskAction
    fun action() {
        val srcDir = sourceDir.get().asFile
        logger.lifecycle("Building Rust SSH agent for target: ${rustTarget.get()}")

        execOperations.exec {
            workingDir = srcDir
            commandLine(
                "cargo", "build",
                "--release",
                "--target", rustTarget.get(),
            )
        }

        val output = outputBinary.get().asFile
        require(output.exists()) {
            "Cargo output binary was not produced at ${output.absolutePath}"
        }
    }
}

val cargoBuild = tasks.register<CargoBuild>("cargoBuild") {
    sourceDir.set(project.file("src"))
    rustTarget.set(hostPlatform.sshAgentRustTarget)

    val cargoOutputPath = "src/target/${hostPlatform.sshAgentRustTarget}/release/$sshAgentBinaryName"
    outputBinary.set(project.file(cargoOutputPath))
}

tasks.register<SignAndCopyBinaryTask>(Tasks.compileSshAgentUniversal) {
    dependsOn(cargoBuild)

    // Set Inputs
    val sourcePath = "src/target/${hostPlatform.sshAgentRustTarget}/release/$sshAgentBinaryName"
    sourceBinary.set(project.file(sourcePath))

    // Set Outputs
    destinationBinary.set(layout.buildDirectory.file("bin/${hostPlatform.composeResourceDir}/$sshAgentBinaryName"))

    // Set Properties
    platformMacOs.set(hostPlatform.isMacOs)
    platformWindows.set(hostPlatform.isWindows)
    markExecutable.set(true)
    val macOsCertIdentity = findProperty("cert_identity") as String?
    certIdentity.set(macOsCertIdentity)
}
