package com.artemchep.keyguard.feature.credentialexchange.export

import com.artemchep.keyguard.common.model.AccountId
import com.artemchep.keyguard.common.model.DProfile

/**
 * The single profile a transfer is scoped to, or `null` when the account the picked
 * picker entry addresses cannot be exported from.
 *
 * The transfer names its account in the system picker, so exactly one account may be
 * read. Filtering here — *upstream* of `buildProfileAccounts` — is what keeps that
 * function's signature, and the multi-account failure-isolation tests that drive it,
 * untouched: everything downstream is list-shaped and degenerates to one element.
 *
 * The [DProfile.hidden] check has to be explicit rather than left to the cipher
 * filter. Starving a hidden account of items would produce an empty *review* reading
 * "There are no credentials to transfer" — the right sentence for a visible account
 * that happens to be empty, and the wrong one for an account that is not on offer.
 */
internal fun scopeProfiles(
    profiles: List<DProfile>,
    accountId: AccountId,
): List<DProfile>? = profiles
    .firstOrNull { it.accountId == accountId.id }
    ?.takeIf { !it.hidden }
    ?.let { profile -> listOf(profile) }
