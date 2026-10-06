package com.artemchep.keyguard.buildplugins.cargo

/**
 * Builds the Rust libraries of a utility module that only runs on desktop operating systems:
 * the JNI library for the desktop JVM target and the static library for native macOS.
 *
 * Android and iOS get no Rust build, so the module implements those targets in Kotlin.
 */
class RustDesktopLibraryPlugin : RustMultiplatformLibraryPlugin() {
    override val mobileTargets: Boolean = false
}
