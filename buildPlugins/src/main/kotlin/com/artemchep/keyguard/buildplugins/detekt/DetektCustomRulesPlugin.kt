package com.artemchep.keyguard.buildplugins.detekt

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.create
import org.gradle.kotlin.dsl.register
import org.gradle.language.base.plugins.LifecycleBasePlugin

/**
 * Runs this repository's custom Detekt rules (the `keyguard` rule set from `:detektRules`)
 * against a module.
 *
 * These rules get their own tasks rather than riding along on the shared `detekt` task for two
 * reasons: that task runs without an analysis classpath and Detekt silently skips rules needing
 * type resolution in that mode, and the shared baselines must never suppress a hand-written
 * project invariant.
 *
 * A module declares the compilations to analyse and the APIs whose call sites must be covered:
 *
 * ```
 * detektCustomRules {
 *     kmpCompilation(targetName = "android", compilationName = "main")
 *     requireCoverageFor("mutablePersistedFlow")
 * }
 * ```
 */
class DetektCustomRulesPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        // The rules jar is what carries the `keyguard` rule set into the Detekt run.
        dependencies.add(
            DETEKT_PLUGINS_CONFIGURATION,
            dependencies.project(mapOf("path" to RULES_PROJECT_PATH)),
        )

        val analysedSources = objects.fileCollection()
        val guardedApiMarkers = objects.setProperty(String::class.java)

        val coverage = tasks.register<VerifyDetektMarkerCoverageTask>(COVERAGE_TASK_NAME) {
            group = LifecycleBasePlugin.VERIFICATION_GROUP
            description = "Fails if a guarded API is used in ${target.path} without being " +
                "covered by a custom-rule Detekt task."
            markers.set(guardedApiMarkers)
            rootDirectory.set(target.rootProject.layout.projectDirectory)
            candidateFiles.from(
                target.fileTree(target.layout.projectDirectory.dir("src")) {
                    include("**/*.kt")
                },
            )
            analysedFiles.from(analysedSources)
            allowedPathPrefixes.convention(emptySet())
            expectsAnalysedSources.set(true)
            stamp.set(
                target.layout.buildDirectory.file("reports/detekt/custom-rules-coverage.txt"),
            )
        }

        val aggregate = tasks.register(AGGREGATE_TASK_NAME) {
            group = LifecycleBasePlugin.VERIFICATION_GROUP
            description = "Runs the custom keyguard Detekt rules for ${target.path}."
            dependsOn(coverage)
        }

        // So that a plain `check` on this module also runs the custom rules.
        plugins.withType(LifecycleBasePlugin::class.java) {
            tasks.named(LifecycleBasePlugin.CHECK_TASK_NAME) {
                dependsOn(aggregate)
            }
        }

        extensions.create<DetektCustomRulesExtension>(
            EXTENSION_NAME,
            target,
            analysedSources,
            guardedApiMarkers,
            aggregate,
        )
        Unit
    }

    internal companion object {
        const val AGGREGATE_TASK_NAME = "detektCustomRules"
        const val COVERAGE_TASK_NAME = "verifyDetektCustomRulesCoverage"
        const val TASK_PREFIX = "detektCustomRules"

        private const val EXTENSION_NAME = "detektCustomRules"
        private const val DETEKT_PLUGINS_CONFIGURATION = "detektPlugins"
        private const val RULES_PROJECT_PATH = ":detektRules"
    }
}
