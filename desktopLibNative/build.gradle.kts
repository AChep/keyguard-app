import com.artemchep.keyguard.buildplugins.KeyguardTaskNames
import com.artemchep.keyguard.buildplugins.cargo.detectHostPlatform
import com.artemchep.keyguard.buildplugins.cargo.dynamicLibraryName

plugins {
    id("keyguard.quality")
    id("keyguard.cargo-common")
}

val hostPlatform = detectHostPlatform()
val desktopLibBinaryName = if (hostPlatform.isWindows) "keyguard-lib.dll" else "keyguard-lib"
val cargoLibraryBinaryName = hostPlatform.dynamicLibraryName("keyguard")

keyguardCargo {
    cargoBinaryName.set(cargoLibraryBinaryName)
    packagedBinaryName.set(desktopLibBinaryName)
    register(compileTaskName = KeyguardTaskNames.compileNativeUniversal)
}
