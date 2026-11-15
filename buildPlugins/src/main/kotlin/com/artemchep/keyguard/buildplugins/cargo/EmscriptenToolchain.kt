package com.artemchep.keyguard.buildplugins.cargo

import java.io.File

internal const val EMSCRIPTEN_TARGET = "wasm32-unknown-emscripten"

/** Explicit environment for the SDK drivers; no caller shell activation is needed. */
internal fun emscriptenEnvironment(sdk: File, python: String, inheritedPath: String): Map<String, String> = mapOf(
    "EMSDK" to sdk.absolutePath,
    "EM_CONFIG" to sdk.resolve(".emscripten").absolutePath,
    "EM_CACHE" to sdk.resolve("upstream/emscripten/cache").absolutePath,
    "EMSDK_PYTHON" to python,
    "PATH" to listOf(sdk.resolve("upstream/emscripten").absolutePath, sdk.resolve("upstream/bin").absolutePath, inheritedPath)
        .joinToString(File.pathSeparator),
)

/** Bindgen splits this environment value using shell-word rules, including on Windows. */
internal fun bindgenSysrootArgument(sdk: File): String {
    val argument = "--sysroot=" + sdk.resolve("upstream/emscripten/cache/sysroot").invariantSeparatorsPath
    return "'" + argument.replace("'", "'\\''") + "'"
}
