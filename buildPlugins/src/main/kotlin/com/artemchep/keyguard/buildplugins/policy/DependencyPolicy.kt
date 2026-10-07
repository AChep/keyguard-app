package com.artemchep.keyguard.buildplugins.policy

import org.gradle.api.DefaultTask
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.component.ComponentIdentifier
import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedComponentResult
import org.gradle.api.artifacts.result.ResolvedDependencyResult
import org.gradle.api.artifacts.result.UnresolvedDependencyResult
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.TaskProvider
import org.gradle.kotlin.dsl.register
import org.gradle.work.DisableCachingByDefault

/** Fails when a dependency graph checked by [registerDependencyPolicy] breaks its policy. */
@DisableCachingByDefault(because = "Resolves dependency graphs and has no outputs")
abstract class CheckDependencyPolicyTask : DefaultTask() {
    /** Names the policy in the summary, e.g. `Compose-free policy`. */
    @get:Input
    abstract val policyName: Property<String>

    /** Describes what the summary counts, e.g. `compile/runtime classpaths in :util:dns`. */
    @get:Input
    abstract val checkedClasspaths: Property<String>

    @get:Input
    abstract val failureHeader: Property<String>

    /** When set, the check fails with this message unless at least one classpath was checked. */
    @get:Input
    @get:Optional
    abstract val nothingCheckedMessage: Property<String>

    @get:Input
    abstract val checkedConfigurationNames: ListProperty<String>

    @get:Input
    abstract val violations: ListProperty<String>

    init {
        group = "verification"
        checkedConfigurationNames.convention(emptyList())
        violations.convention(emptyList())
    }

    @TaskAction
    fun checkPolicy() {
        val violations = violations.get().distinct().sorted()
        check(violations.isEmpty()) {
            failureHeader.get() + "\n" + violations.joinToString("\n")
        }
        val checked = checkedConfigurationNames.get().distinct()
        nothingCheckedMessage.orNull?.let { message ->
            check(checked.isNotEmpty()) { message }
        }
        logger.lifecycle("${policyName.get()} checked ${checked.size} ${checkedClasspaths.get()}.")
    }
}

/**
 * Registers [taskName], which resolves every configuration accepted by [checksConfiguration] and
 * reports what [findViolations] returns for its dependency graph.
 */
internal fun Project.registerDependencyPolicy(
    taskName: String,
    checksConfiguration: (Configuration) -> Boolean,
    findViolations: (configurationName: String, root: ResolvedComponentResult) -> List<String>,
    configure: CheckDependencyPolicyTask.() -> Unit,
): TaskProvider<CheckDependencyPolicyTask> {
    val policyCheck = tasks.register<CheckDependencyPolicyTask>(taskName, configure)
    configurations.configureEach {
        if (!checksConfiguration(this)) return@configureEach

        val configurationName = name
        val configurationViolations = incoming.resolutionResult.rootComponent.map { root ->
            findViolations(configurationName, root)
        }
        policyCheck.configure {
            checkedConfigurationNames.add(configurationName)
            violations.addAll(configurationViolations)
        }
    }
    return policyCheck
}

/** Whether [name] is a JVM or Android compile/runtime classpath, test ones included. */
internal fun isJvmClasspathName(name: String): Boolean =
    name.endsWith("CompileClasspath") ||
        name.endsWith("RuntimeClasspath")

/** Runs [policyChecks] as part of the project's `check` task, whenever a plugin creates it. */
internal fun Project.checkWith(vararg policyChecks: TaskProvider<*>) {
    tasks.named { it == "check" }.configureEach {
        dependsOn(policyChecks)
    }
}

/**
 * Walks the dependency graph from [root] breadth first. [onResolved] gets every resolved edge
 * with the display names that lead to its component; [onUnresolved] gets every failed edge
 * with the names that lead to it. The names are only built when a callback asks for them.
 */
internal fun walkDependencyGraph(
    root: ResolvedComponentResult,
    onResolved: (component: ResolvedComponentResult, path: () -> List<String>) -> Unit,
    onUnresolved: (dependency: UnresolvedDependencyResult, path: () -> List<String>) -> Unit = { _, _ -> },
) {
    val visited = mutableSetOf<ComponentIdentifier>()
    val pending = ArrayDeque<GraphNode>()
    pending.add(GraphNode(root, parent = null))

    while (pending.isNotEmpty()) {
        val node = pending.removeFirst()
        if (!visited.add(node.component.id)) continue

        node.component.dependencies.forEach { dependency ->
            when (dependency) {
                is ResolvedDependencyResult -> {
                    val selected = GraphNode(dependency.selected, parent = node)
                    onResolved(selected.component, selected::path)
                    pending.add(selected)
                }

                is UnresolvedDependencyResult -> onUnresolved(dependency, node::path)
            }
        }
    }
}

/** A component reached from the root through [parent]. */
private class GraphNode(
    val component: ResolvedComponentResult,
    val parent: GraphNode?,
) {
    /** The display names from the first dependency of the root down to this component. */
    fun path(): List<String> = generateSequence(this) { it.parent }
        .takeWhile { it.parent != null }
        .map { it.component.displayName() }
        .toList()
        .asReversed()
}

private fun ResolvedComponentResult.displayName(): String = when (val componentId = id) {
    is ProjectComponentIdentifier -> "project ${componentId.projectPath}"
    else -> moduleVersion?.toString() ?: componentId.displayName
}
