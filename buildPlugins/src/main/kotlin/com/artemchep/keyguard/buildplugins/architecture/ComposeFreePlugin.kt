package com.artemchep.keyguard.buildplugins.architecture

import org.gradle.api.DefaultTask
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.component.ComponentIdentifier
import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedComponentResult
import org.gradle.api.artifacts.result.ResolvedDependencyResult
import org.gradle.api.artifacts.result.UnresolvedDependencyResult
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault

private const val CHECK_TASK_NAME = "checkComposeFree"

private val composePluginIds = setOf(
    "com.android.compose",
    "org.jetbrains.compose",
    "org.jetbrains.kotlin.plugin.compose",
)

/** Keeps a presentation or domain module independent of Compose and application modules. */
class ComposeFreePlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        val projectPath = path
        val policyCheck = tasks.register(
            CHECK_TASK_NAME,
            CheckComposeFreeTask::class.java,
        ) {
            group = "verification"
            description =
                "Rejects Compose and application-layer dependencies from this pure Kotlin module."
            this.projectPath.set(projectPath)
        }

        composePluginIds.forEach { pluginId ->
            pluginManager.withPlugin(pluginId) {
                policyCheck.configure {
                    forbiddenPluginIds.add(pluginId)
                }
            }
        }

        configurations.configureEach {
            val configurationName = name
            if (!isComposeFreeClasspath(configurationName) || !isCanBeResolved) {
                return@configureEach
            }

            val configurationViolations = incoming.resolutionResult.rootComponent.map { rootComponent ->
                collectViolations(
                    rootComponent = rootComponent,
                    projectPath = projectPath,
                    configurationName = configurationName,
                )
            }
            policyCheck.configure {
                checkedConfigurationNames.add(configurationName)
                dependencyViolations.addAll(configurationViolations)
            }
        }

        // Kotlin and base plugins create this lifecycle task at different times.
        tasks.matching { it.name == "check" }.configureEach {
            dependsOn(policyCheck)
        }
    }
}

@DisableCachingByDefault(because = "Resolves dependency graphs and has no outputs")
abstract class CheckComposeFreeTask : DefaultTask() {
    @get:Input
    abstract val projectPath: Property<String>

    @get:Input
    abstract val checkedConfigurationNames: ListProperty<String>

    @get:Input
    abstract val forbiddenPluginIds: ListProperty<String>

    @get:Input
    abstract val dependencyViolations: ListProperty<String>

    init {
        checkedConfigurationNames.convention(emptyList())
        forbiddenPluginIds.convention(emptyList())
        dependencyViolations.convention(emptyList())
    }

    @TaskAction
    fun checkBoundary() {
        val violations = buildList {
            forbiddenPluginIds.get().forEach { pluginId ->
                add("${projectPath.get()} applies forbidden Compose plugin '$pluginId'")
            }
            addAll(dependencyViolations.get())
        }.distinct().sorted()

        check(violations.isEmpty()) {
            "Compose-free module boundary violations:\n" + violations.joinToString("\n")
        }
        check(checkedConfigurationNames.get().isNotEmpty()) {
            "No compile/runtime/metadata classpaths were checked in ${projectPath.get()}."
        }
        logger.lifecycle(
            "Compose-free policy checked ${checkedConfigurationNames.get().distinct().size} " +
                "compile/runtime/metadata classpaths in ${projectPath.get()}.",
        )
    }
}

private data class PendingComponent(
    val component: ResolvedComponentResult,
    val path: List<String>,
)

private fun collectViolations(
    rootComponent: ResolvedComponentResult,
    projectPath: String,
    configurationName: String,
): List<String> {
    val visited = mutableSetOf<ComponentIdentifier>()
    val pending = ArrayDeque<PendingComponent>()
    val violations = mutableListOf<String>()
    pending.add(PendingComponent(rootComponent, emptyList()))

    while (pending.isNotEmpty()) {
        val (component, path) = pending.removeFirst()
        if (!visited.add(component.id)) continue

        component.dependencies.forEach { dependency ->
            when (dependency) {
                is ResolvedDependencyResult -> {
                    val selected = dependency.selected
                    val selectedPath = path + selected.displayName()
                    forbiddenReason(selected)?.let { reason ->
                        violations +=
                            "$projectPath:$configurationName -> " +
                                "${selectedPath.joinToString(" -> ")} ($reason)"
                    }
                    pending.add(PendingComponent(selected, selectedPath))
                }

                is UnresolvedDependencyResult -> {
                    val selectedPath = path + dependency.attempted.displayName
                    violations +=
                        "$projectPath:$configurationName -> " +
                            "${selectedPath.joinToString(" -> ")} " +
                            "(dependency could not be resolved: ${dependency.failure.message})"
                }

                else -> Unit
            }
        }
    }

    return violations
}

private fun forbiddenReason(component: ResolvedComponentResult): String? {
    val componentId = component.id
    if (componentId is ProjectComponentIdentifier && isForbiddenProjectPath(componentId.projectPath)) {
        return "legacy/application project dependency is forbidden"
    }

    val group = component.moduleVersion?.group ?: return null
    if (isComposeArtifactGroup(group)) {
        return "Compose artifact is forbidden"
    }
    return null
}

private fun ResolvedComponentResult.displayName(): String = when (val componentId = id) {
    is ProjectComponentIdentifier -> "project ${componentId.projectPath}"
    else -> moduleVersion?.toString() ?: componentId.displayName
}

internal fun isComposeArtifactGroup(group: String): Boolean =
    group == "androidx.compose" ||
        group.startsWith("androidx.compose.") ||
        group == "org.jetbrains.compose" ||
        group.startsWith("org.jetbrains.compose.")

internal fun isForbiddenProjectPath(path: String): Boolean =
    path == ":common" || path.substringAfterLast(':').endsWith("App", ignoreCase = true)

internal fun isComposeFreeClasspath(name: String): Boolean =
    name.endsWith("CompileClasspath", ignoreCase = true) ||
        name.endsWith("RuntimeClasspath", ignoreCase = true) ||
        name.endsWith("CompileKlibraries", ignoreCase = true) ||
        name.endsWith("DependenciesMetadata", ignoreCase = true)
