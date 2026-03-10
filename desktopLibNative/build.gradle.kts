import binaries.detectHostPlatform
import binaries.dynamicLibraryName
import binaries.registerCargoPackagedArtifact

val hostPlatform = detectHostPlatform()
val desktopLibBinaryName = if (hostPlatform.isWindows) "keyguard-lib.dll" else "keyguard-lib"
val cargoBinaryName = hostPlatform.dynamicLibraryName("keyguard")

registerCargoPackagedArtifact(
    hostPlatform = hostPlatform,
    sourceDirPath = "src",
    rustTarget = hostPlatform.desktopLibRustTarget,
    cargoBinaryName = cargoBinaryName,
    packagedBinaryName = desktopLibBinaryName,
    compileTaskName = Tasks.compileNativeUniversal,
)
