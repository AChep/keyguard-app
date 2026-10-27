package com.artemchep.keyguard.buildplugins.resources

import org.gradle.api.GradleException
import java.io.File
import java.util.Locale

/**
 * The locale of a `values-*` resource directory, as written in the directory name.
 * Consumers apply their own platform normalization on top of it.
 */
internal data class LocaleDirectory(
    val language: String,
    val script: String? = null,
    val region: String? = null,
    val variants: List<String> = emptyList(),
)

private val LANGUAGE = Regex("[a-z]{2,8}")

/** Names of the `values*` directories in [resourcesDir], sorted. */
internal fun localeDirectoryNames(resourcesDir: File): List<String> =
    resourcesDir.listFiles()
        ?.filter { file -> file.isDirectory && file.name.startsWith("values") }
        ?.map { it.name }
        ?.sorted()
        .orEmpty()

/**
 * Parses a legacy (`values-en-rUS`) or BCP 47 (`values-b+sr+Latn`) resource directory name.
 * Returns `null` for the base `values` directory and for names that are not value directories.
 */
internal fun parseLocaleDirectory(name: String): LocaleDirectory? {
    if (!name.startsWith("values-")) {
        return null
    }

    val qualifier = name.removePrefix("values-")
    if (qualifier.startsWith("b+")) {
        return parseBcp47(name, qualifier.removePrefix("b+"))
    }

    val parts = qualifier.split('-')
    val language = parts.firstOrNull()
        ?: return null
    if (!language.matches(LANGUAGE)) {
        return null
    }

    var script: String? = null
    var region: String? = null
    parts.drop(1).forEach { part ->
        when {
            script == null && part.length == 4 && part.all(Char::isLetter) -> {
                script = canonicalizeScript(part)
            }

            region == null && part.startsWith("r") && part.length > 1 -> {
                region = canonicalizeRegion(part.removePrefix("r"))
            }

            else -> throw GradleException("Unsupported locale qualifier directory: $name")
        }
    }

    return LocaleDirectory(
        language = language.lowercase(),
        script = script,
        region = region,
    )
}

private fun parseBcp47(directoryName: String, rawTag: String): LocaleDirectory {
    val segments = rawTag.split('+')
        .filter(String::isNotBlank)
    if (segments.isEmpty()) {
        throw GradleException("Unsupported locale qualifier directory: $directoryName")
    }

    var rest = segments.drop(1)
    val script = rest.firstOrNull()
        ?.takeIf { it.length == 4 && it.all(Char::isLetter) }
        ?.let(::canonicalizeScript)
    if (script != null) rest = rest.drop(1)
    val region = rest.firstOrNull()
        ?.takeIf { isRegion(it) }
        ?.let(::canonicalizeRegion)
    if (region != null) rest = rest.drop(1)

    return LocaleDirectory(
        language = segments.first().lowercase(),
        script = script,
        region = region,
        variants = rest,
    )
}

private fun canonicalizeScript(script: String): String =
    script.lowercase().replaceFirstChar(Char::titlecase)

private fun isRegion(region: String): Boolean =
    region.length == 2 && region.all(Char::isLetter) ||
        region.length == 3 && region.all(Char::isDigit)

private fun canonicalizeRegion(region: String): String =
    when {
        isRegion(region) -> region.uppercase(Locale.ROOT)
        else -> throw GradleException("Unsupported locale region qualifier: $region")
    }
