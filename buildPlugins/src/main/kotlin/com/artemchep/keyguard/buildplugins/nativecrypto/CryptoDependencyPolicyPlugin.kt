package com.artemchep.keyguard.buildplugins.nativecrypto

import org.gradle.api.DefaultTask
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.component.ComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedComponentResult
import org.gradle.api.artifacts.result.ResolvedDependencyResult
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault

private const val CHECK_TASK_NAME = "checkBouncyCastleProductionDependencies"
private const val LEGACY_SSHJ_TASK_NAME = "checkSshjDependencies"

private const val BOUNCY_CASTLE_GROUP = "org.bouncycastle"

private val forbiddenSshGroups = setOf(
    "com.hierynomus",
    "net.schmizz",
)

private val policyProjectPaths = listOf(
    ":util:foundation",
    ":util:kdbx",
    ":common",
    ":androidApp",
    ":wearApp",
    ":desktopApp",
)

/**
 * Owns the repository-wide production dependency boundary for the retired JVM
 * crypto providers. Bouncy Castle remains available to test configurations as
 * an independent differential oracle.
 */
class CryptoDependencyPolicyPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        require(target == target.rootProject) {
            "keyguard.crypto-dependency-policy must be applied to the root project"
        }

        val policyCheck = target.tasks.register(CHECK_TASK_NAME) {
            group = "verification"
            description =
                "Rejects Bouncy Castle, SSHJ, and ASN.1 artifacts from production classpaths."
        }

        // Gradle requires each project to resolve its own configurations. The
        // root task aggregates those checks while this plugin owns their one
        // shared implementation.
        policyProjectPaths.forEach { projectPath ->
            val policyProject = target.project(projectPath)
            val projectCheck = policyProject.tasks.register(
                CHECK_TASK_NAME,
                CheckCryptoDependencyPolicyTask::class.java,
            ) {
                group = "verification"
                description =
                    "Rejects Bouncy Castle, SSHJ, and ASN.1 artifacts from production classpaths."
                this.projectPath.set(projectPath)
            }
            policyProject.configurations.configureEach {
                val configurationName = name
                if (!isJvmClasspathName(configurationName)) return@configureEach

                val isProduction = !configurationName.contains("test", ignoreCase = true)
                val configurationViolations = incoming
                    .resolutionResult
                    .rootComponent
                    .map { rootComponent ->
                        collectViolations(
                            rootComponent = rootComponent,
                            projectPath = projectPath,
                            configurationName = configurationName,
                            isProduction = isProduction,
                        )
                    }
                projectCheck.configure {
                    checkedConfigurationNames.add(configurationName)
                    violations.addAll(configurationViolations)
                }
            }
            policyCheck.configure { dependsOn(projectCheck) }
        }
        target.project(":common").tasks.register(LEGACY_SSHJ_TASK_NAME) {
            group = "verification"
            description = "Compatibility alias for the root crypto dependency policy."
            dependsOn(target.project(":common").tasks.named(CHECK_TASK_NAME))
        }
    }
}

@DisableCachingByDefault(because = "Resolves production dependency graphs and has no outputs")
abstract class CheckCryptoDependencyPolicyTask : DefaultTask() {
    @get:Input
    abstract val projectPath: Property<String>

    @get:Input
    abstract val checkedConfigurationNames: ListProperty<String>

    @get:Input
    abstract val violations: ListProperty<String>

    init {
        checkedConfigurationNames.convention(emptyList())
        violations.convention(emptyList())
    }

    @TaskAction
    fun checkDependencies() {
        val violations = violations.get()
            .distinct()
            .sorted()

        check(violations.isEmpty()) {
            "Retired crypto dependencies escaped test-only configurations:\n" +
                violations.joinToString("\n")
        }
        logger.lifecycle(
            "Crypto dependency policy checked ${checkedConfigurationNames.get().size} " +
                "compile/runtime classpaths in ${projectPath.get()}.",
        )
    }
}

private fun collectViolations(
    rootComponent: ResolvedComponentResult,
    projectPath: String,
    configurationName: String,
    isProduction: Boolean,
): List<String> {
    val visited = mutableSetOf<ComponentIdentifier>()
    val pending = ArrayDeque<ResolvedComponentResult>()
    val violations = mutableListOf<String>()
    pending.add(rootComponent)

    while (pending.isNotEmpty()) {
        val component = pending.removeFirst()
        if (!visited.add(component.id)) continue

        component.moduleVersion
            ?.takeIf { module ->
                isForbiddenSshDependency(module.group, module.name) ||
                    isProduction && isBouncyCastleDependency(module.group)
            }
            ?.let { module ->
                violations += "$projectPath:$configurationName -> $module"
            }
        component.dependencies
            .filterIsInstance<ResolvedDependencyResult>()
            .forEach { dependency -> pending.add(dependency.selected) }
    }

    return violations
}

internal fun isJvmClasspath(
    name: String,
    canBeResolved: Boolean,
): Boolean = canBeResolved && isJvmClasspathName(name)

private fun isJvmClasspathName(name: String): Boolean =
    name.endsWith("CompileClasspath") ||
        name.endsWith("RuntimeClasspath")

internal fun isProductionClasspath(
    name: String,
    canBeResolved: Boolean,
): Boolean = isJvmClasspath(name, canBeResolved) &&
    !name.contains("test", ignoreCase = true)

internal fun isBouncyCastleDependency(group: String): Boolean =
    group == BOUNCY_CASTLE_GROUP

internal fun isForbiddenSshDependency(group: String, name: String): Boolean =
    group in forbiddenSshGroups ||
        name.equals("sshj", ignoreCase = true) ||
        name.equals("asn-one", ignoreCase = true)
