package com.artemchep.keyguard.buildplugins.detekt

import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.SetProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/**
 * Fails when a source file mentions one of [markers] but is not part of [analysedFiles].
 *
 * Detekt skips rules that need type resolution when it runs without an analysis classpath, and
 * it does so at debug log level. A call site that no custom-rule task looks at therefore
 * produces a green build that has checked nothing, which this task turns into a failure.
 */
@CacheableTask
abstract class VerifyDetektMarkerCoverageTask : DefaultTask() {
    /**
     * Text whose presence means a file needs analysis by the custom rules. One entry per
     * guarded API, so a single task can cover several of them.
     */
    @get:Input
    abstract val markers: SetProperty<String>

    /** Every source file that could mention a marker. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val candidateFiles: ConfigurableFileCollection

    /** Source files handed to a custom-rule Detekt task. May legitimately be empty. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val analysedFiles: ConfigurableFileCollection

    /** Root-relative path prefixes that are knowingly exempt. */
    @get:Input
    abstract val allowedPathPrefixes: SetProperty<String>

    /**
     * Whether [analysedFiles] is expected to be non-empty. True for a module that registers a
     * compilation, false for the repository-wide ownership check, which decides purely from
     * [allowedPathPrefixes].
     */
    @get:Input
    abstract val expectsAnalysedSources: Property<Boolean>

    @get:Internal
    abstract val rootDirectory: DirectoryProperty

    @get:OutputFile
    abstract val stamp: RegularFileProperty

    @TaskAction
    fun action() {
        val needles = markers.get()
        require(needles.isNotEmpty()) {
            "No markers were declared, so this task would silently verify nothing."
        }

        // The collection may hold either directories or individual files depending on how the
        // owning compilation reports its sources, so cover both.
        val analysed = (analysedFiles.asFileTree.files + analysedFiles.files)
            .mapTo(HashSet()) { it.canonicalFile }
        val root = rootDirectory.get().asFile.canonicalFile.toPath()
        val exempt = allowedPathPrefixes.get()

        // Files that mention a marker and that this task is responsible for. Exemptions are
        // applied first, because the root-level ownership check deliberately registers no
        // analysed sources and instead exempts the modules that check themselves.
        val markerFiles = candidateFiles.files
            .asSequence()
            .map { it.canonicalFile }
            .mapNotNull { file ->
                if (!file.isFile) return@mapNotNull null
                val text = file.readText()
                val found = needles.filter { it in text }
                if (found.isEmpty()) null else MarkerFile(file, found)
            }
            .map { it to root.relativize(it.file.toPath()).toString().replace('\\', '/') }
            .filterNot { (_, path) -> exempt.any { path == it || path.startsWith("$it/") } }
            .distinctBy { (_, path) -> path }
            .toList()

        // A Detekt task whose source set resolves to nothing reports NO-SOURCE and passes, so
        // an empty analysis set has to fail here instead.
        require(!expectsAnalysedSources.get() || markerFiles.isEmpty() || analysed.isNotEmpty()) {
            "No source files were registered for analysis by the custom Detekt rules, yet " +
                "${markerFiles.size} file(s) mention a guarded API. The Detekt task would " +
                "report NO-SOURCE and pass without checking anything. Verify the compilation " +
                "registered with `detektCustomRules { ... }`."
        }

        val uncovered = markerFiles
            .filter { (marker, _) -> marker.file !in analysed }
            .map { (marker, path) -> "$path (${marker.found.sorted().joinToString()})" }
            .sorted()

        require(uncovered.isEmpty()) {
            buildString {
                appendLine("A guarded API is used in files that no custom-rule Detekt task")
                appendLine("analyses, so the keyguard rules cannot see them:")
                uncovered.forEach { appendLine("  $it") }
                appendLine()
                appendLine("Register the owning compilation or variant with `detektCustomRules { ... }`")
                appendLine("in that module, or add the path to `allowedPathPrefixes` with a reason.")
            }
        }

        stamp.get().asFile.apply { parentFile.mkdirs() }.writeText("ok\n")
    }

    private class MarkerFile(
        val file: java.io.File,
        val found: List<String>,
    )
}
