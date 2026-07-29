package com.artemchep.keyguard.crypto

import com.artemchep.keyguard.platform.LocalPath
import platform.Foundation.NSTemporaryDirectory

internal actual fun privateTemporaryStorageDirectory(): LocalPath =
    LocalPath(NSTemporaryDirectory())
