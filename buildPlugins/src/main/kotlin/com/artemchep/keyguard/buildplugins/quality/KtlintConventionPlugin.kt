package com.artemchep.keyguard.buildplugins.quality

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.getByType
import org.jlleitschuh.gradle.ktlint.KtlintExtension

class KtlintConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("org.jlleitschuh.gradle.ktlint")
        val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")
        extensions.configure<KtlintExtension> {
            version.set(libs.findVersion("ktlint").get().requiredVersion)
        }
    }
}
