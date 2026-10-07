package com.artemchep.keyguard.buildplugins.quality

import com.artemchep.keyguard.buildplugins.libs
import com.artemchep.keyguard.buildplugins.version
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.jlleitschuh.gradle.ktlint.KtlintExtension

class KtlintConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("org.jlleitschuh.gradle.ktlint")
        extensions.configure<KtlintExtension> {
            version.set(libs.version("ktlint"))
        }
    }
}
