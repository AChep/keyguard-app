import com.artemchep.keyguard.buildplugins.KeyguardTaskNames
import com.artemchep.keyguard.buildplugins.cargo.binaryName
import com.artemchep.keyguard.buildplugins.cargo.detectHostPlatform

plugins {
    id("keyguard.quality")
    id("keyguard.cargo-common")
}

val hostPlatform = detectHostPlatform()
val gpgAgentBinaryName = hostPlatform.binaryName("keyguard-gpg-agent")

keyguardCargo {
    cargoBinaryName.set(gpgAgentBinaryName)
    register(compileTaskName = KeyguardTaskNames.compileGpgAgentUniversal)
}
