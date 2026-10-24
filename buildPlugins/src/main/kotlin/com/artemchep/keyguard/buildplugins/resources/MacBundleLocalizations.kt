package com.artemchep.keyguard.buildplugins.resources

import org.gradle.api.Project
import org.gradle.api.file.Directory
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Provider
import org.gradle.api.provider.ValueSource
import org.gradle.api.provider.ValueSourceParameters
import java.io.File

/**
 * The `CFBundleLocalizations` Info.plist entry that declares every locale of [resourcesDir].
 * A value source, so adding or removing a translation invalidates the configuration cache.
 */
fun Project.macBundleLocalizationsPlistXml(resourcesDir: Directory): Provider<String> =
    providers.of(MacBundleLocalizationsValueSource::class.java) {
        parameters.resourcesDir.set(resourcesDir)
    }

abstract class MacBundleLocalizationsValueSource :
    ValueSource<String, MacBundleLocalizationsValueSource.Parameters> {
    interface Parameters : ValueSourceParameters {
        val resourcesDir: DirectoryProperty
    }

    override fun obtain(): String = macBundleLocalizationsPlistXml(parameters.resourcesDir.get().asFile)
}

internal fun macBundleLocalizationsPlistXml(resourcesDir: File): String {
    val entries = macBundleLocalizations(resourcesDir)
        .joinToString(separator = "\n") { "    <string>$it</string>" }
    return """
  <key>CFBundleLocalizations</key>
  <array>
$entries
  </array>
"""
}

/**
 * The JVM matches the system languages against these identifiers, so they keep the
 * resource directory's codes (`iw_IL`, `no_NO`) instead of Apple's normalized ones.
 */
internal fun macBundleLocalizations(resourcesDir: File): List<String> =
    localeDirectoryNames(resourcesDir)
        .mapNotNull(::parseLocaleDirectory)
        .map { directory ->
            listOfNotNull(directory.language, directory.script, directory.region)
                .plus(directory.variants)
                .joinToString(separator = "_")
        }
        .distinct()
