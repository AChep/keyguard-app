package binaries

import org.gradle.api.Project
import org.gradle.api.tasks.TaskProvider
import org.gradle.kotlin.dsl.register

fun Project.registerCargoPackagedArtifact(
    hostPlatform: HostPlatform,
    sourceDirPath: String,
    rustTarget: String,
    cargoBinaryName: String,
    packagedBinaryName: String,
    compileTaskName: String,
    cargoTaskName: String = "cargoBuild",
): TaskProvider<SignAndCopyBinaryTask> {
    val cargoTargetDir = layout.buildDirectory.dir("cargo-target")
    val cargoOutputBinary = cargoTargetDir.map { dir ->
        dir.file("$rustTarget/release/$cargoBinaryName")
    }

    val cargoBuild = tasks.register<CargoBuildTask>(cargoTaskName) {
        sourceDir.set(project.file(sourceDirPath))
        this.cargoTargetDir.set(cargoTargetDir)
        this.rustTarget.set(rustTarget)
        outputBinary.set(cargoOutputBinary)
    }

    return tasks.register<SignAndCopyBinaryTask>(compileTaskName) {
        dependsOn(cargoBuild)

        sourceBinary.set(cargoBuild.flatMap { it.outputBinary })
        destinationBinary.set(
            layout.buildDirectory.file("bin/${hostPlatform.composeResourceDir}/$packagedBinaryName"),
        )

        platformMacOs.set(hostPlatform.isMacOs)
        platformWindows.set(hostPlatform.isWindows)
        markExecutable.set(true)
        val macOsCertIdentity = findProperty("cert_identity") as String?
        certIdentity.set(macOsCertIdentity)
    }
}
