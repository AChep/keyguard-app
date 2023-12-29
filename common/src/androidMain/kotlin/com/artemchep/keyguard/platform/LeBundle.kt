package com.artemchep.keyguard.platform

import android.os.Bundle
import androidx.core.os.bundleOf

actual typealias LeBundle = Bundle

actual operator fun LeBundle.contains(key: String) = containsKey(key)

actual operator fun LeBundle.get(key: String) = get(key)

actual fun leBundleOf(
    vararg map: Pair<String, Any?>,
): LeBundle = bundleOf(*map)
