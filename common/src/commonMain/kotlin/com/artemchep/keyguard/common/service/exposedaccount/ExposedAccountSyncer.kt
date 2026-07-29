package com.artemchep.keyguard.common.service.exposedaccount

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job

/**
 * Keeps the exposed database's account mirror in step with the unlocked vault.
 *
 * Unlike the SSH and GPG public-key syncers this is **not** gated on a setting: the
 * mirror is infrastructure that pre-unlock features read, so it is always current
 * while the vault is unlocked.
 */
interface ExposedAccountSyncer {
    fun launch(scope: CoroutineScope): Job
}
