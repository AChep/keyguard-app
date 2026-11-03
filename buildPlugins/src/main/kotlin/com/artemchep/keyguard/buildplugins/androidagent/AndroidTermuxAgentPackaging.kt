package com.artemchep.keyguard.buildplugins.androidagent

import org.gradle.api.GradleException

data class AndroidTermuxAgentTarget(
    val rustTarget: String,
    val termuxArch: String,
)

/**
 * A Keyguard agent helper shipped as a Termux package.
 *
 * @param taskStem names the aggregate tasks, e.g. `compile<taskStem>All`.
 * @param depends the Termux packages the helper runs, for the `Depends` field.
 */
data class AndroidTermuxAgent(
    val packageName: String,
    val taskStem: String,
    val description: List<String>,
    val depends: List<String> = emptyList(),
)

object AndroidTermuxAgentPackaging {
    const val TERMUX_PREFIX = "/data/data/com.termux/files/usr"
    const val MAINTAINER = "Artem Chepurnyi <mail@artemchep.com>"
    const val HOMEPAGE = "https://github.com/AChep/keyguard-app"

    private val debianVersionRegex = Regex("^[0-9][A-Za-z0-9.+~]*(?:-[A-Za-z0-9.+~]+)?$")

    val supportedTargets = listOf(
        AndroidTermuxAgentTarget(
            rustTarget = "aarch64-linux-android",
            termuxArch = "aarch64",
        ),
        AndroidTermuxAgentTarget(
            rustTarget = "armv7-linux-androideabi",
            termuxArch = "arm",
        ),
        AndroidTermuxAgentTarget(
            rustTarget = "x86_64-linux-android",
            termuxArch = "x86_64",
        ),
    )

    fun resolvePackageVersion(
        marketingVersion: String,
    ): String {
        requireDebianVersion(marketingVersion)
        return marketingVersion
    }

    fun requireDebianVersion(version: String) {
        val valid = debianVersionRegex.matches(version)
        if (!valid) {
            throw GradleException("Resolved package version is not Debian-compatible: $version")
        }
    }

    fun debFileName(
        packageName: String,
        version: String,
        termuxArch: String,
    ): String = "${packageName}_${version}_${termuxArch}.deb"
}
