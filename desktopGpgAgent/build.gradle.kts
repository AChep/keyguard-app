import com.artemchep.keyguard.buildplugins.KeyguardTaskNames
import com.artemchep.keyguard.buildplugins.cargo.binaryName
import com.artemchep.keyguard.buildplugins.cargo.detectHostPlatform

plugins {
    id("keyguard.cargo-common")
}

val hostPlatform = detectHostPlatform()
val gpgAgentBinaryName = hostPlatform.binaryName("keyguard-gpg-agent")

keyguardCargo {
    sourceDir.set(layout.projectDirectory.dir("src"))
    extraSourceInputs.from(rootProject.file("commonGpgAgent"))
    extraSourceInputs.from(rootProject.file("commonAgent"))
    rustTarget.set(hostPlatform.sshAgentRustTarget)
    cargoBinaryName.set(gpgAgentBinaryName)
    packagedBinaryName.set(gpgAgentBinaryName)
    composeResourceDir.set(hostPlatform.composeResourceDir)
    compileTaskName.set(KeyguardTaskNames.compileGpgAgentUniversal)
    platformMacOs.set(hostPlatform.isMacOs)
    platformWindows.set(hostPlatform.isWindows)
}
