package com.artemchep.keyguard.buildplugins.detekt

import com.artemchep.keyguard.buildplugins.policy.checkWith
import com.artemchep.keyguard.buildplugins.quality.QualityConventionPlugin
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.create
import org.gradle.kotlin.dsl.named

/**
 * Runs this repository's custom Detekt rules (the `keyguard` rule set from `:detektRules`)
 * against a module.
 *
 * These rules get their own tasks rather than riding along on the shared `detekt` task for two
 * reasons: that task runs without an analysis classpath and Detekt silently skips rules needing
 * type resolution in that mode, and the shared baselines must never suppress a hand-written
 * project invariant.
 *
 * A module declares the compilations to analyse; every file mentioning one of
 * [GUARDED_API_MARKERS] must be covered by one of them:
 *
 * ```
 * detektCustomRules {
 *     kmpCompilation(targetName = "android", compilationName = "main")
 * }
 * ```
 */
class DetektCustomRulesPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        // Brings Detekt, the rules jar with the `keyguard` rule set, the aggregate task and the
        // coverage task.
        pluginManager.apply(QualityConventionPlugin::class.java)

        val analysedSources = objects.fileCollection()
        val coverageExemptions = objects.setProperty(String::class.java)
        // This module runs the rules, so its guarded call sites must be among what they analyse.
        tasks.named<VerifyDetektMarkerCoverageTask>(COVERAGE_TASK_NAME) {
            analysedFiles.from(analysedSources)
            allowedPathPrefixes.set(coverageExemptions)
            expectsAnalysedSources.set(true)
        }

        // So that a plain `check` on this module also runs the custom rules.
        val aggregate = tasks.named(AGGREGATE_TASK_NAME)
        checkWith(aggregate)

        extensions.create<DetektCustomRulesExtension>(
            EXTENSION_NAME,
            target,
            analysedSources,
            coverageExemptions,
            aggregate,
        )
        Unit
    }

    companion object {
        /**
         * Text whose presence marks a file as using an API guarded by a custom rule. The rules
         * themselves are repository-wide, so the same list feeds the coverage check of every
         * module.
         */
        val GUARDED_API_MARKERS: Set<String> = setOf(
            // MutablePersistedFlowTypeSafety, MutablePersistedFlowDuplicateKey
            "mutablePersistedFlow",
            // AndroidIncompatibleListOperation. Match the name alone to include callable
            // references and implicit receivers; the rule resolves the actual operation.
            "removeFirst",
            "removeLast",
        )

        internal const val AGGREGATE_TASK_NAME = "detektCustomRules"
        internal const val COVERAGE_TASK_NAME = "verifyDetektCustomRulesCoverage"
        internal const val TASK_PREFIX = "detektCustomRules"

        private const val EXTENSION_NAME = "detektCustomRules"
    }
}
