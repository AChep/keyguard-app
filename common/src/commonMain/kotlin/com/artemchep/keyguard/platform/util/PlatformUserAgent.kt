package com.artemchep.keyguard.platform.util

import com.artemchep.keyguard.platform.Platform

expect val Platform.userAgent: String

// Taken from:
// https://releases.electronjs.org/releases/stable
const val CHROME_MAJOR_VERSION = "126"
const val CHROME_FULL_VERSION = "$CHROME_MAJOR_VERSION.0.6478.114"

// Seems like desktop clients always use the Windows user-agents for
// privacy reasons.
val Platform.defaultUserAgent: String
    get() = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$CHROME_FULL_VERSION Safari/537.36"

