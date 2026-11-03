package com.artemchep.keyguard.feature.home.vault.util

import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.feature.confirmation.organization.AccountChangeWarning
import com.artemchep.keyguard.feature.environments.withoutReferences
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.ui.SimpleNote
import org.jetbrains.compose.resources.StringResource

internal suspend fun cipherCopyWarnings(
    ciphers: List<DSecret>,
    translate: suspend (StringResource) -> String,
): List<AccountChangeWarning> {
    val linkedAccounts = mutableSetOf<String>()
    val referencingAccounts = mutableSetOf<String>()
    val unsupportedAccounts = mutableSetOf<String>()
    ciphers.forEach { cipher ->
        if (cipher.links.isNotEmpty()) linkedAccounts += cipher.accountId
        if (cipher.type == DSecret.Type.Environment || cipher.environment != null) {
            val copy = cipher.environment.withoutReferences()
            when {
                copy == null -> unsupportedAccounts += cipher.accountId
                // The copy differs only if references became unset.
                copy != cipher.environment -> referencingAccounts += cipher.accountId
            }
        }
    }
    return buildList {
        if (linkedAccounts.isNotEmpty()) {
            add(AccountChangeWarning(
                sourceAccountIds = linkedAccounts,
                note = SimpleNote(
                    text = translate(Res.string.ciphers_copy_warning_links),
                    type = SimpleNote.Type.WARNING,
                ),
            ))
        }
        if (referencingAccounts.isNotEmpty()) {
            add(AccountChangeWarning(
                sourceAccountIds = referencingAccounts,
                note = SimpleNote(
                    text = translate(Res.string.ciphers_copy_warning_environment_references),
                    type = SimpleNote.Type.WARNING,
                ),
            ))
        }
        if (unsupportedAccounts.isNotEmpty()) {
            add(AccountChangeWarning(
                sourceAccountIds = unsupportedAccounts,
                note = SimpleNote(
                    text = translate(Res.string.ciphers_copy_error_unsupported_environment),
                    type = SimpleNote.Type.ERROR,
                ),
                blocksConfirmation = true,
            ))
        }
    }
}
