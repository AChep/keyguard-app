package com.artemchep.keyguard.apple.auth

import com.artemchep.keyguard.common.exception.Readable
import com.artemchep.keyguard.common.io.ioRaise
import com.artemchep.keyguard.common.model.MasterKdf
import com.artemchep.keyguard.common.model.NoAnalytics
import com.artemchep.keyguard.common.model.VaultState
import com.artemchep.keyguard.feature.localization.TextHolder
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.autofill_unlock_password_out_of_memory

/**
 * The memory, on top of the key derivation, the extension needs to finish
 * the unlock: the derivation buffers, the database and the UI.
 */
internal const val AUTOFILL_KDF_MEMORY_MARGIN_BYTES: Long = 32L * 1024L * 1024L // 32 MiB

/**
 * Returns `true` if the AutoFill extension may run [kdf].
 *
 * A derivation of up to the memory of [MasterKdf.Argon2id.DEFAULT], which
 * unlocked there before custom parameters existed, always runs. A larger one
 * runs only if [availableBytes], the memory the system still lets the process
 * allocate, covers it plus [AUTOFILL_KDF_MEMORY_MARGIN_BYTES]. It is queried
 * only then; `null` means the limit is unknown, so the derivation does not run.
 */
internal fun isKdfWithinAutofillBudget(
    kdf: MasterKdf,
    availableBytes: () -> Long?,
): Boolean {
    // PBKDF2 needs no memory, and an unsupported
    // derivation fails without running.
    val memoryBytes = (kdf as? MasterKdf.Argon2id)?.memoryBytes
        ?: return true
    return memoryBytes <= MasterKdf.Argon2id.DEFAULT.memoryBytes ||
            availableBytes()
                ?.let { it >= memoryBytes + AUTOFILL_KDF_MEMORY_MARGIN_BYTES }
                ?: false
}

/**
 * Returns whether the AutoFill extension on this platform may run a key
 * derivation, or `null` if the extension has no tight memory limit. The
 * system kills an extension over its memory limit without an error, so a
 * derivation over the budget must not start.
 */
internal expect fun platformAutofillKdfAllows(): ((MasterKdf) -> Boolean)?

/**
 * Returns the password unlock that refuses a key derivation [allows] rejects,
 * with a message to use the biometric unlock or the app instead.
 */
internal fun VaultState.Unlock.WithPassword.guardedBy(
    allows: ((MasterKdf) -> Boolean)?,
): VaultState.Unlock.WithPassword {
    if (allows == null) return this
    val delegate = getCreateIo
    return VaultState.Unlock.WithPassword(
        kdf = kdf,
        getCreateIo = { password ->
            // Check on submit: the available memory changes.
            if (allows(kdf)) {
                delegate(password)
            } else {
                ioRaise(AutofillKdfOutOfMemoryException())
            }
        },
    )
}

internal class AutofillKdfOutOfMemoryException :
    RuntimeException("The key derivation exceeds the memory of the AutoFill extension"),
    Readable,
    NoAnalytics {
    override val title: TextHolder
        get() = TextHolder.Res(Res.string.autofill_unlock_password_out_of_memory)
}
