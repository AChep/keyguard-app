package com.artemchep.keyguard.buildplugins.apple

/** The xcconfig with the Apple deployment targets from the version catalog. */
fun appleDeploymentConfigContent(
    macosVersion: String,
    iosVersion: String,
): String {
    val macos = validateAppleDeploymentTarget("appleMacosDeploymentTarget", macosVersion)
    val ios = validateAppleDeploymentTarget("appleIosDeploymentTarget", iosVersion)
    return "// Generated from gradle/libs.versions.toml. Do not edit.\n" +
        "// Regenerate with ./gradlew generateAppleConfiguration.\n" +
        "MACOSX_DEPLOYMENT_TARGET = $macos\n" +
        "IPHONEOS_DEPLOYMENT_TARGET = $ios\n"
}

/** Replaces the platform versions in appleUi/Package.swift with the catalog's deployment targets. */
fun updateSwiftPackageDeploymentTargets(
    source: String,
    macosVersion: String,
    iosVersion: String,
): String {
    fun replaceSingle(
        input: String,
        pattern: Regex,
        replacement: String,
        platform: String,
    ): String {
        val matches = pattern.findAll(input).toList()
        check(matches.size == 1) {
            "Expected exactly one $platform platform declaration in appleUi/Package.swift, " +
                "found ${matches.size}."
        }
        return input.replaceRange(matches.single().range, replacement)
    }

    val macos = validateAppleDeploymentTarget("appleMacosDeploymentTarget", macosVersion)
    val ios = validateAppleDeploymentTarget("appleIosDeploymentTarget", iosVersion)
    return replaceSingle(
        input = source,
        pattern = Regex("""\.macOS\("[^"]+"\)"""),
        replacement = ".macOS(\"$macos\")",
        platform = "macOS",
    ).let { updated ->
        replaceSingle(
            input = updated,
            pattern = Regex("""\.iOS\("[^"]+"\)"""),
            replacement = ".iOS(\"$ios\")",
            platform = "iOS",
        )
    }
}

private fun validateAppleDeploymentTarget(
    name: String,
    version: String,
): String {
    require(version.matches(Regex("[0-9]+\\.[0-9]+"))) {
        "Apple deployment targets must use major.minor; $name is '$version'."
    }
    return version
}
