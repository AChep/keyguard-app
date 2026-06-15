package com.artemchep.keyguard.buildplugins.androidssh

import org.gradle.api.GradleException

data class AndroidSshAgentTermuxTarget(
    val rustTarget: String,
    val termuxArch: String,
)

object AndroidSshAgentTermuxPackaging {
    const val PACKAGE_NAME = "keyguard-android-ssh-agent"
    const val TERMUX_PREFIX = "/data/data/com.termux/files/usr"
    const val MAINTAINER = "Artem Chepurnyi <mail@artemchep.com>"
    const val HOMEPAGE = "https://github.com/AChep/keyguard-app"

    private val debianVersionRegex = Regex("^[0-9][A-Za-z0-9.+~]*(?:-[A-Za-z0-9.+~]+)?$")

    val supportedTargets = listOf(
        AndroidSshAgentTermuxTarget(
            rustTarget = "aarch64-linux-android",
            termuxArch = "aarch64",
        ),
        AndroidSshAgentTermuxTarget(
            rustTarget = "armv7-linux-androideabi",
            termuxArch = "arm",
        ),
        AndroidSshAgentTermuxTarget(
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
        version: String,
        termuxArch: String,
    ): String = "${PACKAGE_NAME}_${version}_${termuxArch}.deb"
}
