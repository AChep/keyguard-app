package com.artemchep.keyguard.buildplugins.nativezxcvbn

import com.artemchep.keyguard.buildplugins.cargo.configureNativeLibraryTests
import org.gradle.api.Plugin
import org.gradle.api.Project

class NativeZxcvbnConsumerPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        configureNativeLibraryTests("zxcvbn")
    }
}
