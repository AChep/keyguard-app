package com.artemchep.keyguard.buildplugins.quality

import com.artemchep.keyguard.buildplugins.libs
import com.artemchep.keyguard.buildplugins.version
import com.artemchep.keyguard.buildplugins.detekt.DetektCustomRulesPlugin.Companion.AGGREGATE_TASK_NAME
import com.artemchep.keyguard.buildplugins.detekt.DetektCustomRulesPlugin.Companion.COVERAGE_TASK_NAME
import com.artemchep.keyguard.buildplugins.detekt.DetektCustomRulesPlugin.Companion.GUARDED_API_MARKERS
import com.artemchep.keyguard.buildplugins.detekt.VerifyDetektMarkerCoverageTask
import dev.detekt.gradle.extensions.DetektExtension
import dev.detekt.gradle.extensions.FailOnSeverity
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.register
import org.gradle.language.base.plugins.LifecycleBasePlugin

class QualityConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply(KtlintConventionPlugin::class.java)
        pluginManager.apply("dev.detekt")
        val repositoryDirectory = rootProject.layout.projectDirectory

        // Typed custom-rule tasks remain separately opt-in; the shared task needs
        // the rule set on its plugin classpath to recognize its configuration keys.
        dependencies.add("detektPlugins", dependencies.project(mapOf("path" to ":detektRules")))
        extensions.configure<DetektExtension> {
            toolVersion.set(libs.version("detekt"))
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

        // Fails when this module mentions an API guarded by the custom Detekt rules without
        // running them. `keyguard.detekt-custom-rules` also requires the rules to analyse it.
        val projectPath = path
        val coverage = tasks.register<VerifyDetektMarkerCoverageTask>(COVERAGE_TASK_NAME) {
            group = LifecycleBasePlugin.VERIFICATION_GROUP
            description = "Fails if a guarded API is used in $projectPath without being " +
                "covered by a custom-rule Detekt task."
            markers.set(GUARDED_API_MARKERS)
            rootDirectory.set(repositoryDirectory)
            candidateFiles.from(fileTree("src") { include("**/*.kt") })
            expectsAnalysedSources.set(false)
            stamp.set(layout.buildDirectory.file("reports/detekt/custom-rules-coverage.txt"))
        }
        tasks.register(AGGREGATE_TASK_NAME) {
            group = LifecycleBasePlugin.VERIFICATION_GROUP
            description = "Runs the custom keyguard Detekt rules for $projectPath."
            dependsOn(coverage)
        }
        Unit
    }
}
