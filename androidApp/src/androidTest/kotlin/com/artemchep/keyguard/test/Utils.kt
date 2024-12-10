package com.artemchep.keyguard.test

import com.artemchep.keyguard.BuildConfig

/**
 * Convenience parameter to use proper package name
 * with regards to build type and build flavor.
 */
val PACKAGE_NAME = StringBuilder("com.artemchep.keyguard").apply {
    val hasSuffix = when (BuildConfig.BUILD_TYPE) {
        "debug" -> true
        else -> false
    }
    if (hasSuffix) {
        append(".${BuildConfig.BUILD_TYPE}")
    }
}.toString()
