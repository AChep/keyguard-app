package com.artemchep.keyguard.feature.navigation

import com.artemchep.keyguard.platform.util.isRelease

object N {
    /** generic tag, so you can find all the navigation logs */
    const val TAG = "nav"

    val DEBUG = !isRelease

    fun tag(tag: String) = "$TAG:$tag"
}
