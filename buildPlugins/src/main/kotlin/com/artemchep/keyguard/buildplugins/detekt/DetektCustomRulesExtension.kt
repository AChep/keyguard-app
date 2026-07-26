package com.artemchep.keyguard.buildplugins.detekt

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
import javax.inject.Inject

/**
 * Declares which compilations the custom Detekt rules analyse, and which APIs must be covered.
 *
 * Compilations are named through the Kotlin/AGP model rather than through Detekt's own task
 * naming scheme, so a renamed target or flavor fails with the list of available names instead
 * of silently checking nothing.
 */
abstract class DetektCustomRulesExtension @Inject constructor(
    private val project: Project,
    private val analysedSources: ConfigurableFileCollection,
    private val guardedApiMarkers: SetProperty<String>,
    private val aggregate: TaskProvider<Task>,
) {
    /**
     * Declares that every file mentioning one of [markers] in this module has to be analysed.
     *
     * Without this the rules would still run, but a call site in a source set that no
     * registered compilation covers would go unchecked and the build would stay green. Use the
     * function name that the rule guards, for example `"mutablePersistedFlow"`.
     */
    fun requireCoverageFor(vararg markers: String) {
        guardedApiMarkers.addAll(markers.toList())
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

    /** Analyses the Kotlin compilation of the Android variant [variantName]. */
    fun androidVariant(variantName: String) {
        val kotlin = project.extensions.getByType(KotlinAndroidProjectExtension::class.java)
        val compilation = project.provider {
            requireNotNull(kotlin.target.compilations.findByName(variantName)) {
                "No Android Kotlin compilation '$variantName' in ${project.path}. " +
                    "Available: ${kotlin.target.compilations.names}"
            }
        }
        register(
            suffix = variantName.replaceFirstChar { it.uppercase() },
            compilation = compilation,
        )
    }

    private fun register(suffix: String, compilation: Provider<out KotlinCompilation<*>>) {
        val compileTask = compilation.flatMap { it.compileTaskProvider }
            .map { it as KotlinJvmCompile }

        // Generated-source providers such as KSP cannot be queried until their producer has run.
        // The guarded API lives in authored sources, so select from the compilation's declared
        // Kotlin source directories and resolve generated declarations from compiled output.
        val sources = project.objects.fileCollection().from(
            project.provider {
                compilation.get().allKotlinSourceSets.map { sourceSet ->
                    sourceSet.kotlin.sourceDirectories
                }
            },
        )
        // Detekt's standalone analysis cannot load all compiler plugins used by the full KMP
        // compilation. Analyse only files that can contain guarded calls and resolve everything
        // else from the successfully compiled output on the analysis classpath.
        val guardedSources = project.objects.fileCollection().from(
            project.provider {
                val markers = guardedApiMarkers.get()
                sources.asFileTree.files.filter { file ->
                    file.isFile &&
                        file.extension == "kt" &&
                        markers.any { marker -> file.readText().contains(marker) }
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

            config.setFrom(
                project.rootProject.layout.projectDirectory
                    .file("config/detekt/detekt-custom-rules.yml"),
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
        val JVM_PLATFORM_TYPES = setOf(KotlinPlatformType.jvm, KotlinPlatformType.androidJvm)
    }
}
