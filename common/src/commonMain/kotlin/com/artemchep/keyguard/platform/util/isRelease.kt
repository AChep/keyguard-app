package com.artemchep.keyguard.platform.util

import com.artemchep.keyguard.build.BuildKonfig

val isRelease: Boolean = BuildKonfig.buildType.lowercase() == "release"
