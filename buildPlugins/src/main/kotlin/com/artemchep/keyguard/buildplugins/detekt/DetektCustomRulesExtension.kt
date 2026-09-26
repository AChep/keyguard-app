package com.artemchep.keyguard.buildplugins.detekt

import com.android.build.api.variant.AndroidComponentsExtension
import dev.detekt.gradle.Detekt
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.provider.Provider
import org.gradle.api.provider.SetProperty
import org.gradle.api.tasks.TaskProvider
import org.gradle.kotlin.dsl.register
import org.gradle.language.base.plugins.LifecycleBasePlugin
import org.jetbrains.kotlin.gradle.dsl.KotlinAndroidProjectExtension
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilation
import org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType
import org.jetbrains.kotlin.gradle.plugin.KotlinTargetsContainer
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile
import java.io.File
import javax.inject.Inject

/**
 * Declares which compilations the custom Detekt rules analyse.
 *
 * Compilations are named through the Kotlin/AGP model rather than through Detekt's own task
 * naming scheme, so a renamed target or flavor fails with the list of available names instead
 * of silently checking nothing.
 *
 * Every file in the module that mentions one of [DetektCustomRulesPlugin.GUARDED_API_MARKERS]
 * has to be analysed by a registered compilation, or `verifyDetektCustomRulesCoverage` fails.
 */
abstract class DetektCustomRulesExtension @Inject constructor(
    private val project: Project,
    private val analysedSources: ConfigurableFileCollection,
    private val allowedPathPrefixes: SetProperty<String>,
    private val aggregate: TaskProvider<Task>,
) {
    /** Exempts a module-relative source path that cannot run in the guarded environment. */
    fun excludeSourcePathFromCoverage(pathPrefix: String) {
        require(pathPrefix.isNotBlank()) { "The excluded source path must not be blank." }
        val excluded = project.file(pathPrefix)
        require(excluded.startsWith(project.rootDir)) {
            "The excluded source path '$pathPrefix' must be inside ${project.rootDir}."
        }
        allowedPathPrefixes.add(excluded.relativeTo(project.rootDir).invariantSeparatorsPath)
    }

    /** Analyses [compilationName] of the Kotlin Multiplatform target [targetName]. */
    fun kmpCompilation(targetName: String, compilationName: String = "main") {
        val kotlin = project.extensions.getByType(KotlinTargetsContainer::class.java)
        val compilation = project.provider {
            val target = requireNotNull(kotlin.targets.findByName(targetName)) {
                "No Kotlin target '$targetName' in ${project.path}. " +
                    "Available: ${kotlin.targets.names}"
            }
            require(target.platformType in JVM_PLATFORM_TYPES) {
                "Kotlin target '$targetName' in ${project.path} is ${target.platformType}. " +
                    "Detekt only offers type resolution for JVM and Android targets."
            }
            requireNotNull(target.compilations.findByName(compilationName)) {
                "No compilation '$compilationName' on target '$targetName' in ${project.path}. " +
                    "Available: ${target.compilations.names}"
            }
        }
        register(
            suffix = targetName.replaceFirstChar { it.uppercase() } +
                compilationName.replaceFirstChar { it.uppercase() },
            compilation = compilation,
        )
    }

    /**
     * Analyses the Kotlin compilation of the Android variant [variantName] of an application
     * or library module.
     */
    fun androidVariant(variantName: String) {
        val kotlin = project.extensions.getByType(KotlinAndroidProjectExtension::class.java)
        val compilation = project.provider {
            requireNotNull(kotlin.target.compilations.findByName(variantName)) {
                "No Android Kotlin compilation '$variantName' in ${project.path}. " +
                    "Available: ${kotlin.target.compilations.names}"
            }
        }
        val sources = project.objects.fileCollection()
        // AGP's built-in Kotlin support exposes no source directories through
        // KotlinCompilation.allKotlinSourceSets. The variant API remains authoritative and
        // includes Kotlin files kept in both conventional `java` and `kotlin` source directories.
        // Use only static directories: generated declarations are resolved from the successfully
        // compiled output below.
        val androidComponents =
            project.extensions.getByType(AndroidComponentsExtension::class.java)
        androidComponents.onVariants(
            androidComponents.selector().withName(variantName),
        ) { variant ->
            val kotlinSources = requireNotNull(variant.sources.kotlin) {
                "Android variant '$variantName' in ${project.path} has no Kotlin sources."
            }
            sources.from(kotlinSources.static)
        }
        // Preserve the previous validation of the requested Kotlin compilation even though its
        // legacy source-set model is no longer used to discover Android source directories.
        sources.from(
            compilation.map { emptyList<File>() },
        )
        register(
            suffix = variantName.replaceFirstChar { it.uppercase() },
            compilation = compilation,
            declaredSources = sources,
        )
    }

    private fun register(
        suffix: String,
        compilation: Provider<out KotlinCompilation<*>>,
        declaredSources: ConfigurableFileCollection? = null,
    ) {
        val compileTask = compilation.flatMap { it.compileTaskProvider }
            .map { it as KotlinJvmCompile }

        // Generated-source providers such as KSP cannot be queried until their producer has run.
        // The guarded API lives in authored sources, so select from the compilation's declared
        // Kotlin source directories and resolve generated declarations from compiled output.
        val sources = project.objects.fileCollection()
        if (declaredSources != null) {
            sources.from(declaredSources)
        } else {
            sources.from(
                project.provider {
                    compilation.get().allKotlinSourceSets.map { sourceSet ->
                        sourceSet.kotlin.sourceDirectories
                    }
                },
            )
        }
        // Detekt's standalone analysis cannot load all compiler plugins used by the full KMP
        // compilation. Analyse only files that can contain guarded calls and resolve everything
        // else from the successfully compiled output on the analysis classpath.
        val guardedSources = project.objects.fileCollection().from(
            project.provider {
                sources.asFileTree.files.filter { file ->
                    file.isFile &&
                        file.extension == "kt" &&
                        file.readText().let { text ->
                            GUARDED_API_MARKERS.any { containsDetektApiMarker(text, it) }
                        }
                }
            },
        )
        analysedSources.from(guardedSources)

        val task = project.tasks.register<Detekt>("$TASK_PREFIX$suffix") {
            group = LifecycleBasePlugin.VERIFICATION_GROUP
            description = "Runs the custom keyguard Detekt rules over $suffix with " +
                "type resolution."

            source(guardedSources)
            setIncludes(listOf("**/*.kt"))

            // A non-empty classpath is what makes Detekt pass `--analysis-mode full`, which
            // is the only mode where rules that need type resolution actually run.
            classpath.from(
                project.provider { compilation.get().output.classesDirs },
                compileTask.map { it.libraries },
            )
            friendPaths.from(
                project.provider { compilation.get().output.classesDirs },
                compileTask.map { it.friendPaths },
            )

            apiVersion.set(
                compileTask.flatMap { t -> t.compilerOptions.apiVersion.map { it.version } },
            )
            languageVersion.set(
                compileTask.flatMap { t -> t.compilerOptions.languageVersion.map { it.version } },
            )
            jvmTarget.set(
                compileTask.flatMap { t -> t.compilerOptions.jvmTarget.map { it.target } },
            )
            optIn.set(compileTask.flatMap { it.compilerOptions.optIn })
            freeCompilerArgs.set(compileTask.flatMap { it.compilerOptions.freeCompilerArgs })
            noJdk.set(compileTask.flatMap { it.compilerOptions.noJdk })
            multiPlatformEnabled.set(compileTask.flatMap { it.multiPlatformEnabled })

            // Rules that only matter on Android are switched off for desktop JVM
            // compilations through a second config file, which Detekt layers over the first.
            val configDir = project.rootProject.layout.projectDirectory.dir("config/detekt")
            config.setFrom(
                compilation.map { c ->
                    listOfNotNull(
                        configDir.file("detekt-custom-rules.yml"),
                        configDir.file("detekt-custom-rules-jvm.yml")
                            .takeIf { c.platformType == KotlinPlatformType.jvm },
                    )
                },
            )
            buildUponDefaultConfig.set(false)
            disableDefaultRuleSets.set(true)
            parallel.set(true)

            // Intentionally no baseline. Self-registered Detekt tasks get no baseline
            // convention, and the shared baselines must never suppress these rules.

            reports.html.required.set(false)
            reports.markdown.required.set(false)

            // Guards against a wiring regression that would leave the classpath empty: Detekt
            // would then run in `--analysis-mode light` and skip the rules without failing.
            val analysisClasspath = classpath
            val taskPath = path
            doFirst {
                check(!analysisClasspath.isEmpty) {
                    "$taskPath has an empty analysis classpath, so Detekt would fall back to " +
                        "`--analysis-mode light` and silently skip the custom keyguard rules."
                }
            }
        }
        aggregate.configure { dependsOn(task) }
    }

    private companion object {
        val TASK_PREFIX = DetektCustomRulesPlugin.TASK_PREFIX
        val GUARDED_API_MARKERS = DetektCustomRulesPlugin.GUARDED_API_MARKERS
        val JVM_PLATFORM_TYPES = setOf(KotlinPlatformType.jvm, KotlinPlatformType.androidJvm)
    }
}
