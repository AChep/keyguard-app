package com.artemchep.keyguard.buildplugins.optionalfeatures

import com.android.build.api.artifact.SingleArtifact
import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import org.gradle.api.DefaultTask
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.component.ComponentIdentifier
import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedComponentResult
import org.gradle.api.artifacts.result.ResolvedDependencyResult
import org.gradle.api.artifacts.result.UnresolvedDependencyResult
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.kotlin.dsl.getByType
import org.gradle.work.DisableCachingByDefault
import org.w3c.dom.Element
import java.io.File
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

/** Guards the optional features already extracted from Wear's shared dependency graph. */
class WearDependencyCheckPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        val projectPath = path
        val dependencyCheck = tasks.register(
            "checkOptionalFeatureDependencies",
            CheckWearDependenciesTask::class.java,
        ) {
            group = "verification"
            description = "Checks that phone-only optional feature dependencies stay outside the Wear app."
        }
        val manifestCheck = tasks.register(
            "checkOptionalFeatureManifests",
            CheckWearManifestCoverageTask::class.java,
        ) {
            group = "verification"
            description = "Checks that phone-only optional feature components stay outside the Wear app."
        }

        pluginManager.withPlugin("com.android.application") {
            val androidComponents = extensions.getByType<ApplicationAndroidComponentsExtension>()
            androidComponents.onVariants { variant ->
                val variantName = variant.name
                val checkVariantManifest = tasks.register(
                    "check${variantName.replaceFirstChar(Char::uppercaseChar)}OptionalFeatureManifest",
                    CheckWearManifestComponentsTask::class.java,
                ) {
                    this.variantName.set(variantName)
                    mergedManifest.set(variant.artifacts.get(SingleArtifact.MERGED_MANIFEST))
                }
                manifestCheck.configure {
                    variantNames.add(variantName)
                    dependsOn(checkVariantManifest)
                }
            }
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
            dependsOn(dependencyCheck, manifestCheck)
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

@DisableCachingByDefault(because = "Verifies manifest check coverage and has no outputs")
abstract class CheckWearManifestCoverageTask : DefaultTask() {
    @get:Input
    abstract val variantNames: ListProperty<String>

    init {
        variantNames.convention(emptyList())
    }

    @TaskAction
    fun checkCoverage() {
        check(variantNames.get().isNotEmpty()) {
            "No Wear production merged manifests were checked. " +
                "Apply com.android.application and enable at least one variant."
        }
        logger.lifecycle("Wear optional feature manifest policy checked ${variantNames.get().size} variants.")
    }
}

@DisableCachingByDefault(because = "Verifies an Android build artifact and has no outputs")
abstract class CheckWearManifestComponentsTask : DefaultTask() {
    @get:Input
    abstract val variantName: Property<String>

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val mergedManifest: RegularFileProperty

    @TaskAction
    fun checkManifest() {
        val problems = findForbiddenWearManifestComponents(mergedManifest.get().asFile)
        check(problems.isEmpty()) {
            "Wear optional feature manifest check failed for ${variantName.get()}:\n" +
                problems.joinToString("\n") { component -> "forbidden component $component" }
        }
        logger.lifecycle("Wear optional feature manifest policy checked ${variantName.get()}.")
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
        if (projectPath in forbiddenWearProjectPaths) {
            violations += "$configuration -> project $projectPath"
        }
        component.moduleVersion?.let { module ->
            if (
                module.group == "androidx.camera" ||
                module.group == "com.google.mlkit" ||
                module.group == "com.google.android.gms" && module.name.startsWith("play-services-mlkit-") ||
                module.group == openKeychainGroup && module.name in forbiddenOpenKeychainArtifacts
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

internal fun findForbiddenWearManifestComponents(manifest: File): List<String> {
    val documentBuilderFactory = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
        isXIncludeAware = false
        isExpandEntityReferences = false
        setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        setFeature("http://xml.org/sax/features/external-general-entities", false)
        setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
        setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
    }
    val document = documentBuilderFactory.newDocumentBuilder().parse(manifest)
    val packageName = document.documentElement.getAttribute("package")
    val application = checkNotNull(document.getElementsByTagName("application").item(0)) {
        "Merged manifest has no application element: $manifest"
    }
    return buildList {
        val elements = application.childNodes
        for (index in 0 until elements.length) {
            val element = elements.item(index) as? Element ?: continue
            val attribute = when (element.tagName) {
                "activity", "service", "receiver", "provider" -> "name"
                "activity-alias" -> "targetActivity"
                else -> continue
            }
            val componentName = resolveManifestComponentName(
                name = element.getAttributeNS(androidNamespace, attribute),
                packageName = packageName,
            )
            if (componentName.startsWith(forbiddenWearComponentPackagePrefix)) {
                val description = if (element.tagName == "activity-alias") {
                    "${element.getAttributeNS(androidNamespace, "name")} -> $componentName"
                } else {
                    componentName
                }
                add("${element.tagName} $description")
            }
        }
    }.distinct().sorted()
}

private fun resolveManifestComponentName(name: String, packageName: String): String {
    check(name.isNotBlank()) { "Missing component class name in merged manifest." }
    if (!name.startsWith('.') && '.' in name) return name
    check(packageName.isNotBlank()) {
        "Cannot resolve component '$name' without a merged manifest package."
    }
    return if (name.startsWith('.')) "$packageName$name" else "$packageName.$name"
}

private val forbiddenWearProjectPaths = setOf(
    ":feature:qr-scanner-android",
    ":feature:android-ipc-android",
    ":feature:android-ipc-presentation",
)

private const val openKeychainGroup = "com.github.open-keychain.open-keychain"

private val forbiddenOpenKeychainArtifacts = setOf(
    "openpgp-api",
    "sshauthentication-api",
)

private const val androidNamespace = "http://schemas.android.com/apk/res/android"

private const val forbiddenWearComponentPackagePrefix = "com.artemchep.keyguard.android.ipc."
