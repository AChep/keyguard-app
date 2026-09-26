import com.artemchep.keyguard.buildplugins.KeyguardTaskNames
import com.artemchep.keyguard.buildplugins.cargo.binaryName
import com.artemchep.keyguard.buildplugins.cargo.detectHostPlatform

plugins {
    id("keyguard.quality")
    id("keyguard.cargo-common")
}

val hostPlatform = detectHostPlatform()
val sshAgentBinaryName = hostPlatform.binaryName("keyguard-ssh-agent")

keyguardCargo {
    extraSourceInputs.from(rootProject.file("commonSshAgent"))
    extraSourceInputs.from(rootProject.file("commonAgent"))
    extraSourceInputs.from(rootProject.file("thirdParty/rust"))
    cargoBinaryName.set(sshAgentBinaryName)
    register(compileTaskName = KeyguardTaskNames.compileSshAgentUniversal)
}
