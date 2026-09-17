package com.artemchep.keyguard.buildplugins.di

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.koin.compiler.plugin.KoinGradleExtension

/** Compiler configuration for modules that declare or consume application DI. */
class KoinConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("io.insert-koin.compiler.plugin")
        extensions.configure<KoinGradleExtension> {
            compileSafety.set(true)
            unsafeDslChecks.set(true)
            logSeverity.set("info")
            versionCheckSeverity.set("warning")
            // The compiler plugin enables mandatory strict safety at detected application
            // roots. Keep that detection enabled so graph edits invalidate root compilation
            // while library modules retain incremental compilation.
            strictSafetyForceOff.set(false)
        }
    }
}
