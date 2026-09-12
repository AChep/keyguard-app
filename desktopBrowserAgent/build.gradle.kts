import com.artemchep.keyguard.buildplugins.KeyguardTaskNames
import com.artemchep.keyguard.buildplugins.cargo.binaryName
import com.artemchep.keyguard.buildplugins.cargo.detectHostPlatform

plugins {
    id("keyguard.cargo-common")
}

val hostPlatform = detectHostPlatform()
val browserAgentBinaryName = hostPlatform.binaryName("keyguard-browser-agent")

keyguardCargo {
    sourceDir.set(layout.projectDirectory.dir("src"))
    extraSourceInputs.from(rootProject.file("commonAgent"))
    extraSourceInputs.from(rootProject.file("thirdParty/rust"))
    rustTarget.set(hostPlatform.sshAgentRustTarget)
    cargoBinaryName.set(browserAgentBinaryName)
    packagedBinaryName.set(browserAgentBinaryName)
    composeResourceDir.set(hostPlatform.composeResourceDir)
    compileTaskName.set(KeyguardTaskNames.compileBrowserAgentUniversal)
    platformMacOs.set(hostPlatform.isMacOs)
    platformWindows.set(hostPlatform.isWindows)
}
