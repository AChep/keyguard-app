package com.artemchep.keyguard.util.io

import platform.Foundation.NSURL

/**
 * Returns a `file://` [NSURL] referring to this path, the canonical bridge
 * for Foundation APIs that take URLs rather than path strings.
 */
fun LocalPath.toNSURL(): NSURL = NSURL.fileURLWithPath(value)
