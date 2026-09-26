package com.artemchep.keyguard.buildplugins.nativecrypto

import com.artemchep.keyguard.buildplugins.cargo.configureNativeLibraryTests
import org.gradle.api.Plugin
import org.gradle.api.Project

class NativeCryptoConsumerPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        configureNativeLibraryTests("crypto")
    }
}
