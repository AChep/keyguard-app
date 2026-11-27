package com.artemchep.keyguard.apple.auth

import com.artemchep.keyguard.common.model.MasterKdf

// The macOS AutoFill extension has no tight memory limit.
internal actual fun platformAutofillKdfAllows(): ((MasterKdf) -> Boolean)? = null
