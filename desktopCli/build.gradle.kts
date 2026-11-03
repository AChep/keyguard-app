import com.artemchep.keyguard.buildplugins.KeyguardTaskNames
import com.artemchep.keyguard.buildplugins.cargo.binaryName
import com.artemchep.keyguard.buildplugins.cargo.detectHostPlatform

plugins {
    id("keyguard.quality")
    id("keyguard.cargo-common")
}

val hostPlatform = detectHostPlatform()

keyguardCargo {
    cargoBinaryName.set(hostPlatform.binaryName("keyguard"))
    cargoPackage.set("keyguard-cli")
    register(compileTaskName = KeyguardTaskNames.compileCliUniversal)
}
