package com.artemchep.keyguard.buildplugins.quality

import dev.detekt.gradle.extensions.DetektExtension
import dev.detekt.gradle.extensions.FailOnSeverity
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.getByType

class QualityConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply(KtlintConventionPlugin::class.java)
        pluginManager.apply("dev.detekt")
        val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")
        val repositoryDirectory = rootProject.layout.projectDirectory

        // Typed custom-rule tasks remain separately opt-in; the shared task needs
        // the rule set on its plugin classpath to recognize its configuration keys.
        dependencies.add("detektPlugins", dependencies.project(mapOf("path" to ":detektRules")))
        extensions.configure<DetektExtension> {
            toolVersion.set(libs.findVersion("detekt").get().requiredVersion)
            source.setFrom(
                fileTree("src") {
                    include("**/*.kt", "**/*.kts")
                },
            )
            config.setFrom(repositoryDirectory.file("config/detekt/detekt.yml"))
            buildUponDefaultConfig.set(true)
            baseline.set(
                repositoryDirectory.file(
                    "config/detekt/baseline/${path.removePrefix(":").replace(':', '-')}.xml",
                ),
            )
            basePath.set(repositoryDirectory)
            ignoreFailures.set(false)
            failOnSeverity.set(FailOnSeverity.Error)
        }
    }
}
