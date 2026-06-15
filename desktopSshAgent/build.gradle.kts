import com.artemchep.keyguard.buildplugins.KeyguardTaskNames
import com.artemchep.keyguard.buildplugins.cargo.binaryName
import com.artemchep.keyguard.buildplugins.cargo.detectHostPlatform

plugins {
    id("keyguard.cargo-common")
}

val hostPlatform = detectHostPlatform()
val sshAgentBinaryName = hostPlatform.binaryName("keyguard-ssh-agent")

keyguardCargo {
    sourceDir.set(layout.projectDirectory.dir("src"))
    extraSourceInputs.from(rootProject.file("commonSshAgent"))
    rustTarget.set(hostPlatform.sshAgentRustTarget)
    cargoBinaryName.set(sshAgentBinaryName)
    packagedBinaryName.set(sshAgentBinaryName)
    composeResourceDir.set(hostPlatform.composeResourceDir)
    compileTaskName.set(KeyguardTaskNames.compileSshAgentUniversal)
    platformMacOs.set(hostPlatform.isMacOs)
    platformWindows.set(hostPlatform.isWindows)
}
