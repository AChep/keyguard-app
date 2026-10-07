package com.artemchep.keyguard.buildplugins.cargo

import org.gradle.api.Plugin
import org.gradle.api.Project

/** Passes the desktop library of [producerPath] to the JVM tests of the applying project. */
abstract class NativeLibraryConsumerPlugin(
    private val producerPath: String,
) : Plugin<Project> {
    override fun apply(target: Project) {
        target.configureNativeLibraryTests(producerPath)
    }
}

class NativeCryptoConsumerPlugin : NativeLibraryConsumerPlugin(":util:crypto")

class NativeIoConsumerPlugin : NativeLibraryConsumerPlugin(":util:io")

class NativeZxcvbnConsumerPlugin : NativeLibraryConsumerPlugin(":util:zxcvbn")
