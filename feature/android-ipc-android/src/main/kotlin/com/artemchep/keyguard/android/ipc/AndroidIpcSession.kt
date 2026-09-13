package com.artemchep.keyguard.android.ipc

import com.artemchep.keyguard.common.model.MasterSession

internal fun androidIpcSessionIdentity(
    session: MasterSession?,
): String? = (session as? MasterSession.Key)?.let { key ->
    buildString {
        append(System.identityHashCode(key))
        append(':')
        append(key.createdAt.toEpochMilliseconds())
        append(':')
        append(key.origin::class.qualifiedName)
    }
}
