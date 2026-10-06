package com.artemchep.keyguard.util.ffi

import java.io.File

internal actual fun loadBundledJniLibrary(name: String): Boolean {
    val library = System.getProperty(COMPOSE_RESOURCES_DIRECTORY_PROPERTY)
        ?.takeIf(String::isNotBlank)
        ?.let { directory -> File(directory, System.mapLibraryName(name)) }
        ?.takeIf(File::isFile)
        ?: return false
    System.load(library.canonicalPath)
    return true
}

internal const val COMPOSE_RESOURCES_DIRECTORY_PROPERTY = "compose.application.resources.dir"
