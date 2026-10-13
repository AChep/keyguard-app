package com.artemchep.keyguard.common.service.session

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.model.LockReason
import com.artemchep.keyguard.common.usecase.ClearVaultSession
import com.artemchep.keyguard.common.usecase.GetVaultLockAfterTimeout
import com.artemchep.keyguard.feature.localization.TextHolder
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.flowOf

/** The real inactivity timer; [onLock] stands in for locking the vault. */
internal fun testVaultSessionLocker(
    scope: CoroutineScope,
    timeout: Duration = 10.seconds,
    onLock: (LockReason) -> Unit,
) = VaultSessionLocker(
    getVaultLockAfterTimeout = object : GetVaultLockAfterTimeout {
        override fun invoke() = flowOf(timeout)
    },
    clearVaultSession = object : ClearVaultSession {
        override fun invoke(reason: LockReason, text: TextHolder): IO<Unit> = {
            onLock(reason)
        }
    },
    scope = scope,
)
