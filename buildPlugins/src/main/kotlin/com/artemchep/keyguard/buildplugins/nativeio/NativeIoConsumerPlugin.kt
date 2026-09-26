package com.artemchep.keyguard.buildplugins.nativeio

import com.artemchep.keyguard.buildplugins.cargo.configureNativeLibraryTests
import org.gradle.api.Plugin
import org.gradle.api.Project

class NativeIoConsumerPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        configureNativeLibraryTests("io")
    }
}
