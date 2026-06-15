import com.artemchep.keyguard.buildplugins.KeyguardTaskNames
import com.artemchep.keyguard.buildplugins.cargo.detectHostPlatform
import com.artemchep.keyguard.buildplugins.cargo.dynamicLibraryName

plugins {
    id("keyguard.cargo-common")
}

val hostPlatform = detectHostPlatform()
val desktopLibBinaryName = if (hostPlatform.isWindows) "keyguard-lib.dll" else "keyguard-lib"
val cargoLibraryBinaryName = hostPlatform.dynamicLibraryName("keyguard")

keyguardCargo {
    sourceDir.set(layout.projectDirectory.dir("src"))
    rustTarget.set(hostPlatform.desktopLibRustTarget)
    cargoBinaryName.set(cargoLibraryBinaryName)
    packagedBinaryName.set(desktopLibBinaryName)
    composeResourceDir.set(hostPlatform.composeResourceDir)
    compileTaskName.set(KeyguardTaskNames.compileNativeUniversal)
    platformMacOs.set(hostPlatform.isMacOs)
    platformWindows.set(hostPlatform.isWindows)
}
