package com.artemchep.keyguard.buildplugins.nativecrypto

import com.artemchep.keyguard.buildplugins.policy.isJvmClasspathName
import com.artemchep.keyguard.buildplugins.policy.registerDependencyPolicy
import com.artemchep.keyguard.buildplugins.policy.walkDependencyGraph
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.result.ResolvedComponentResult

private const val CHECK_TASK_NAME = "checkBouncyCastleProductionDependencies"

private const val BOUNCY_CASTLE_GROUP = "org.bouncycastle"

private val forbiddenSshGroups = setOf(
    "com.hierynomus",
    "net.schmizz",
)

private val policyProjectPaths = listOf(
    ":util:foundation",
    ":util:kdbx",
    ":util:webauthn",
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

        target.tasks.register(CHECK_TASK_NAME) {
            group = "verification"
            description =
                "Rejects Bouncy Castle, SSHJ, and ASN.1 artifacts from production classpaths."
            dependsOn(policyProjectPaths.map { "$it:$CHECK_TASK_NAME" })
        }
    }
}

/** Each project owns resolution of its own classpaths; the root only aggregates. */
class CryptoDependencyCheckPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        val projectPath = path
        registerDependencyPolicy(
            taskName = CHECK_TASK_NAME,
            checksConfiguration = { configuration -> isJvmClasspathName(configuration.name) },
            findViolations = { configurationName, root ->
                collectViolations(
                    rootComponent = root,
                    projectPath = projectPath,
                    configurationName = configurationName,
                    isProduction = !configurationName.contains("test", ignoreCase = true),
                )
            },
        ) {
            description =
                "Rejects Bouncy Castle, SSHJ, and ASN.1 artifacts from production classpaths."
            policyName.set("Crypto dependency policy")
            checkedClasspaths.set("compile/runtime classpaths in $projectPath")
            failureHeader.set("Retired crypto dependencies escaped test-only configurations:")
        }
        Unit
    }
}

private fun collectViolations(
    rootComponent: ResolvedComponentResult,
    projectPath: String,
    configurationName: String,
    isProduction: Boolean,
): List<String> = buildList {
    walkDependencyGraph(rootComponent, onResolved = { component, _ ->
        component.moduleVersion
            ?.takeIf { module ->
                isForbiddenSshDependency(module.group, module.name) ||
                    isProduction && isBouncyCastleDependency(module.group)
            }
            ?.let { module -> add("$projectPath:$configurationName -> $module") }
    })
}

internal fun isBouncyCastleDependency(group: String): Boolean =
    group == BOUNCY_CASTLE_GROUP

internal fun isForbiddenSshDependency(group: String, name: String): Boolean =
    group in forbiddenSshGroups ||
        name.equals("sshj", ignoreCase = true) ||
        name.equals("asn-one", ignoreCase = true)
