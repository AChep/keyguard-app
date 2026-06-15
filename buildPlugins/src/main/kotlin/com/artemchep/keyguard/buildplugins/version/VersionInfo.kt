package com.artemchep.keyguard.buildplugins.version

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
            ?.toIntOrNull()
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
