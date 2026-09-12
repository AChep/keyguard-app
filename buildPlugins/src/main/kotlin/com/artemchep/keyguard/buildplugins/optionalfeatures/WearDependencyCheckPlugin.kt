package com.artemchep.keyguard.buildplugins.optionalfeatures

import org.gradle.api.DefaultTask
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.component.ComponentIdentifier
import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedComponentResult
import org.gradle.api.artifacts.result.ResolvedDependencyResult
import org.gradle.api.artifacts.result.UnresolvedDependencyResult
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault

/** Guards the optional features already extracted from Wear's shared dependency graph. */
class WearDependencyCheckPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        val projectPath = path
        val dependencyCheck = tasks.register(
            "checkOptionalFeatureDependencies",
            CheckWearDependenciesTask::class.java,
        ) {
            group = "verification"
            description = "Checks that phone-only scanner dependencies stay outside the Wear app."
        }

        configurations.configureEach {
            val configurationName = name
            val isClasspath = configurationName.endsWith("CompileClasspath") ||
                configurationName.endsWith("RuntimeClasspath")
            if (!isClasspath || configurationName.contains("test", ignoreCase = true)) {
                return@configureEach
            }

            val problems = incoming.resolutionResult.rootComponent.map { root ->
                collectWearDependencyViolations(root, "$projectPath:$configurationName")
            }
            dependencyCheck.configure {
                configurationNames.add(configurationName)
                violations.addAll(problems)
            }
        }

        tasks.matching { it.name == "check" }.configureEach {
            dependsOn(dependencyCheck)
        }
    }
}

@DisableCachingByDefault(because = "Verifies resolved dependency graphs and has no outputs")
abstract class CheckWearDependenciesTask : DefaultTask() {
    @get:Input
    abstract val configurationNames: ListProperty<String>

    @get:Input
    abstract val violations: ListProperty<String>

    init {
        configurationNames.convention(emptyList())
        violations.convention(emptyList())
    }

    @TaskAction
    fun checkDependencies() {
        check(configurationNames.get().isNotEmpty()) {
            "No Wear production compile/runtime classpaths were checked."
        }
        val problems = violations.get().distinct().sorted()
        check(problems.isEmpty()) {
            "Wear optional feature dependency check failed:\n" + problems.joinToString("\n")
        }
        logger.lifecycle(
            "Wear optional feature policy checked ${configurationNames.get().size} " +
                "production compile/runtime classpaths.",
        )
    }
}

private fun collectWearDependencyViolations(
    root: ResolvedComponentResult,
    configuration: String,
): List<String> {
    val visited = mutableSetOf<ComponentIdentifier>()
    val pending = ArrayDeque<ResolvedComponentResult>()
    val violations = mutableListOf<String>()
    pending.add(root)
    while (pending.isNotEmpty()) {
        val component = pending.removeFirst()
        if (!visited.add(component.id)) continue

        val projectPath = (component.id as? ProjectComponentIdentifier)?.projectPath
        if (projectPath == ":feature:qr-scanner-android") {
            violations += "$configuration -> project $projectPath"
        }
        component.moduleVersion?.let { module ->
            if (
                module.group == "androidx.camera" ||
                module.group == "com.google.mlkit" ||
                module.group == "com.google.android.gms" && module.name.startsWith("play-services-mlkit-")
            ) {
                violations += "$configuration -> $module"
            }
        }
        component.dependencies.forEach { dependency ->
            when (dependency) {
                is ResolvedDependencyResult -> pending.add(dependency.selected)
                is UnresolvedDependencyResult ->
                    violations += "$configuration -> unresolved ${dependency.attempted.displayName}"
            }
        }
    }
    return violations
}
