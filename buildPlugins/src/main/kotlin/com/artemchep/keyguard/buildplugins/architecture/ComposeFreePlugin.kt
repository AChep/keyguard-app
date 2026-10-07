package com.artemchep.keyguard.buildplugins.architecture

import com.artemchep.keyguard.buildplugins.policy.checkWith
import com.artemchep.keyguard.buildplugins.policy.registerDependencyPolicy
import com.artemchep.keyguard.buildplugins.policy.walkDependencyGraph
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedComponentResult

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
        val policyCheck = registerDependencyPolicy(
            taskName = CHECK_TASK_NAME,
            checksConfiguration = { configuration ->
                isComposeFreeClasspath(configuration.name) && configuration.isCanBeResolved
            },
            findViolations = { configurationName, root ->
                collectViolations(root, projectPath, configurationName)
            },
        ) {
            description =
                "Rejects Compose and application-layer dependencies from this pure Kotlin module."
            policyName.set("Compose-free policy")
            checkedClasspaths.set("compile/runtime/metadata classpaths in $projectPath")
            failureHeader.set("Compose-free module boundary violations:")
            nothingCheckedMessage.set("No compile/runtime/metadata classpaths were checked in $projectPath.")
        }

        composePluginIds.forEach { pluginId ->
            pluginManager.withPlugin(pluginId) {
                policyCheck.configure {
                    violations.add("$projectPath applies forbidden Compose plugin '$pluginId'")
                }
            }
        }

        checkWith(policyCheck)
    }
}

private fun collectViolations(
    rootComponent: ResolvedComponentResult,
    projectPath: String,
    configurationName: String,
): List<String> = buildList {
    val prefix = "$projectPath:$configurationName -> "
    walkDependencyGraph(
        root = rootComponent,
        onResolved = { component, path ->
            forbiddenReason(component)?.let { reason ->
                add(prefix + "${path().joinToString(" -> ")} ($reason)")
            }
        },
        onUnresolved = { dependency, path ->
            val attemptedPath = path() + dependency.attempted.displayName
            add(
                prefix + "${attemptedPath.joinToString(" -> ")} " +
                    "(dependency could not be resolved: ${dependency.failure.message})",
            )
        },
    )
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
