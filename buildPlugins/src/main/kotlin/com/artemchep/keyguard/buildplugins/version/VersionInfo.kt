package com.artemchep.keyguard.buildplugins.version

import com.artemchep.keyguard.buildplugins.libs
import com.artemchep.keyguard.buildplugins.version
import com.artemchep.keyguard.buildplugins.versionInt
import org.gradle.api.GradleException
import org.gradle.api.Project
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

data class VersionInfo(
    val marketingVersion: String,
    val logicalVersion: Int,
    val buildDate: String,
    val buildRef: String,
)

/** The version of this Keyguard build: the catalog's app version plus the release properties. */
fun Project.keyguardVersionInfo(): VersionInfo = createVersionInfo(
    marketingVersion = libs.version("appVersionName"),
    logicalVersion = libs.versionInt("appVersionCode"),
)

fun Project.createVersionInfo(
    marketingVersion: String,
    logicalVersion: Int,
): VersionInfo {
    val buildRef = providers.gradleProperty("versionRef")
        .orNull
        .orEmpty()
    val buildDate = providers.gradleProperty("versionDate")
        .orNull
        ?.let { LocalDate.parse(it, DateTimeFormatter.BASIC_ISO_DATE) }
        ?: LocalDate.now(ZoneOffset.UTC)
    val codeVersion = kotlin.run {
        val providedVersionCode = providers.gradleProperty("versionCode")
            .orNull
            ?.let(::parseVersionCode)
        val defaultVersionCode =
            ((buildDate.year % 1000) * 500 + buildDate.dayOfYear) * 10000 + logicalVersion
        providedVersionCode ?: defaultVersionCode
    }

    return VersionInfo(
        marketingVersion = marketingVersion,
        logicalVersion = codeVersion,
        buildDate = buildDate.format(DateTimeFormatter.BASIC_ISO_DATE),
        buildRef = buildRef,
    )
}

/** Android requires a positive versionCode; a provided value that isn't one is a broken release setup. */
internal fun parseVersionCode(value: String): Int =
    value.trim().toIntOrNull()?.takeIf { it > 0 }
        ?: throw GradleException(
            "Gradle property 'versionCode' must be a positive integer, but was '$value'.",
        )
