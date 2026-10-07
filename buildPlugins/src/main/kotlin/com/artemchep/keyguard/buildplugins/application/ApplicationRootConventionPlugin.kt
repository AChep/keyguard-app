package com.artemchep.keyguard.buildplugins.application

import com.artemchep.keyguard.buildplugins.di.KoinConventionPlugin
import com.artemchep.keyguard.buildplugins.quality.LicensePolicyPlugin
import com.artemchep.keyguard.buildplugins.quality.QualityConventionPlugin
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.koin.compiler.plugin.KoinGradleExtension

/** The checks and DI wiring shared by the phone, Wear, desktop and Apple applications. */
class ApplicationRootConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply(QualityConventionPlugin::class.java)
        pluginManager.apply(LicensePolicyPlugin::class.java)
        pluginManager.apply(KoinConventionPlugin::class.java)
        // Application roots always revalidate the assembled dependency graph. The compiler plugin
        // finds roots by scanning Kotlin source directories, which misses src/main/java.
        extensions.configure<KoinGradleExtension> {
            strictSafety.set(true)
        }
    }
}
