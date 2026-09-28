package com.artemchep.keyguard.apple.lists

import com.artemchep.keyguard.common.model.Password
import com.artemchep.keyguard.common.service.logging.LogLevel
import com.artemchep.keyguard.apple.KeyguardCore
import com.artemchep.keyguard.apple.model.VaultActionSnapshot
import com.artemchep.keyguard.res.*

/** One entry of the system AutoFill (QuickType) index. */
data class AutofillIdentitySnapshot(
    val recordId: String,
    val serviceIdentifier: String,
    val user: String,
    val cipherId: String,
    val accountId: String,
)

/** A resolved AutoFill credential (the appex fills these). */
data class AutofillCredentialSnapshot(
    val user: String,
    val password: String,
)

/**
 * One matched login for the AutoFill manual picker (`prepareCredentialList`), one
 * entry per cipher. [recordId] round-trips back to [KeyguardCore.loadAutofillCredential].
 */
data class AutofillSuggestionSnapshot(
    val recordId: String,
    val name: String,
    val user: String,
    val suggested: Boolean,
    val accountName: String,
)

/**
 * One passkey to register in the system index (`ASPasskeyCredentialIdentity`), one per
 * stored FIDO2 credential. [recordId] (`accountId|cipherId|credentialId`) round-trips
 * back to [KeyguardCore.assertPasskey]. [credentialId] / [userHandle] are raw bytes.
 */
data class PasskeyIdentitySnapshot(
    val recordId: String,
    val rpId: String,
    val userName: String,
    val credentialId: ByteArray,
    val userHandle: ByteArray,
    val accountName: String = "",
    val cipherName: String = "",
)

/** A computed WebAuthn assertion (the appex returns these as `ASPasskeyAssertionCredential`). */
data class PasskeyAssertionSnapshot(
    val credentialId: ByteArray,
    val authenticatorData: ByteArray,
    val signature: ByteArray,
    val userHandle: ByteArray,
    val rpId: String,
)

/** A computed WebAuthn registration (the appex returns these as `ASPasskeyRegistrationCredential`). */
data class PasskeyRegistrationSnapshot(
    val credentialId: ByteArray,
    val attestationObject: ByteArray,
    val rpId: String,
    val identity: PasskeyIdentitySnapshot,
)

// ---------------------------------------------------------------------------
// SSH agent snapshots.
// ---------------------------------------------------------------------------

enum class SshAgentHistoryItemKind {
    SECTION,
    VALUE,
}

/**
 * One row of the SSH agent signing history. For SECTION headers only [caller]
 * (the date label) is set. For VALUE rows [caller] is the requesting client,
 * [description] the request details line, and [response] the uppercase name of
 * the response category (e.g. APPROVED / DENIED) the SwiftUI layer maps to an
 * icon.
 */
data class SshAgentHistoryItemSnapshot(
    val id: String,
    val kind: SshAgentHistoryItemKind,
    val caller: String,
    val description: String,
    val date: String?,
    val responseText: String,
    val response: String?,
)

/** A flat projection of the SSH agent history. Built by [KeyguardCore.observeSshAgentHistory]. */
data class SshAgentHistorySnapshot(
    val loaded: Boolean,
    val subtitle: String?,
    val items: List<SshAgentHistoryItemSnapshot>,
) {
    companion object {
        val empty = SshAgentHistorySnapshot(loaded = false, subtitle = null, items = emptyList())
    }
}

// ---------------------------------------------------------------------------
// Password history snapshots.
// ---------------------------------------------------------------------------

/**
 * One previous password of a cipher. [date] is the localized change time (nullable).
 * [actions] are the row's own dropdown actions (copy password / remove from history /
 * show in large type / show-and-lock / check data breaches) the shared producer
 * attaches to the entry; each id routes back through
 * [KeyguardCore.invokePasswordHistoryItemAction]. [selected] / [selecting] mirror
 * the producer's per-item multi-selection handle.
 */
data class PasswordHistoryItemSnapshot(
    val id: String,
    val value: String,
    val date: String?,
    val monospace: Boolean,
    val actions: List<VaultActionSnapshot> = emptyList(),
    val selected: Boolean = false,
    val selecting: Boolean = false,
)

/**
 * A flat projection of a single cipher's password history. Built by
 * [KeyguardCore.observePasswordHistory]; [notFound] is true when the cipher id
 * no longer resolves. [selectionCount] / [selectionActions] mirror the active
 * multi-selection (the bulk Delete action). [actions] are the screen's top-level
 * overflow actions (the "Clear history" action); each id routes back through
 * [KeyguardCore.invokePasswordHistoryAction].
 */
data class PasswordHistorySnapshot(
    val loaded: Boolean,
    val notFound: Boolean,
    val items: List<PasswordHistoryItemSnapshot>,
    val selectionCount: Int = 0,
    val selectionActions: List<VaultActionSnapshot> = emptyList(),
    val actions: List<VaultActionSnapshot> = emptyList(),
) {
    companion object {
        val empty = PasswordHistorySnapshot(
            loaded = false,
            notFound = false,
            items = emptyList(),
            selectionCount = 0,
            selectionActions = emptyList(),
            actions = emptyList(),
        )
    }
}

// ---------------------------------------------------------------------------
// Open-source licenses snapshots.
// ---------------------------------------------------------------------------

/** One dependency in the open-source licenses list. */
data class LicenseItemSnapshot(
    val id: String,
    val name: String,
    val version: String,
    val license: String,
    val url: String?,
)

/** A flat projection of the open-source licenses list. Built by [KeyguardCore.observeLicense]. */
data class LicenseListSnapshot(
    val loaded: Boolean,
    val items: List<LicenseItemSnapshot>,
) {
    companion object {
        val empty = LicenseListSnapshot(loaded = false, items = emptyList())
    }
}

// ---------------------------------------------------------------------------
// Localization contributors snapshots.
// ---------------------------------------------------------------------------

/** One contributor in the localization contributors list. [score] is the translated-strings count. */
data class LocalizationContributorItemSnapshot(
    val id: String,
    val name: String,
    val score: Int,
)

/** A flat projection of the localization contributors. Built by [KeyguardCore.observeLocalizationContributors]. */
data class LocalizationContributorsSnapshot(
    val loaded: Boolean,
    val items: List<LocalizationContributorItemSnapshot>,
) {
    companion object {
        val empty = LocalizationContributorsSnapshot(loaded = false, items = emptyList())
    }
}

// ---------------------------------------------------------------------------
// Logs snapshots.
// ---------------------------------------------------------------------------

enum class LogsItemKind {
    SECTION,
    VALUE,
}

/**
 * One row of the in-memory logs. For SECTION headers only [text] is set; for
 * VALUE rows [level] is the uppercase [com.artemchep.keyguard.common.service.logging.LogLevel]
 * name and [time] the formatted timestamp.
 */
data class LogsItemSnapshot(
    val id: String,
    val kind: LogsItemKind,
    val text: String,
    val level: String?,
    val time: String?,
)

/** A flat projection of the in-memory logs. Built by [KeyguardCore.observeLogs]. */
data class LogsSnapshot(
    val loaded: Boolean,
    val items: List<LogsItemSnapshot>,
) {
    companion object {
        val empty = LogsSnapshot(loaded = false, items = emptyList())
    }
}

// ---------------------------------------------------------------------------
// URL block / URL override snapshots (shared shape).
// ---------------------------------------------------------------------------

/**
 * One row of a URL rule list. For blocked URLs [subtitle] is the matched URI and
 * [detail] the block mode; for URL overrides [subtitle] is the regex and [detail]
 * the command. [actions] are the per-row dropdown actions (edit / duplicate /
 * delete) the shared producer attaches to the row; each id routes back through
 * [KeyguardCore.invokeUrlBlockListItemAction] / [KeyguardCore.invokeUrlOverrideListItemAction].
 * [selected] / [selecting] mirror the producer's per-item multi-selection handle.
 */
data class UrlRuleItemSnapshot(
    val id: String,
    val title: String,
    val subtitle: String,
    val detail: String,
    val active: Boolean,
    val actions: List<VaultActionSnapshot> = emptyList(),
    val selected: Boolean = false,
    val selecting: Boolean = false,
)

/**
 * A flat projection of a URL rule list (blocked URLs or URL overrides). Built by
 * [KeyguardCore.observeUrlBlockList] / [KeyguardCore.observeUrlOverrideList].
 * [hasPrimaryAction] is true when the producer offers a create-new ("+") action;
 * the SwiftUI screen surfaces it via [KeyguardCore.invokeUrlBlockListPrimaryAction]
 * / [KeyguardCore.invokeUrlOverrideListPrimaryAction]. [selectionCount] /
 * [selectionActions] mirror the active multi-selection (the bulk Delete action).
 */
data class UrlRuleListSnapshot(
    val loaded: Boolean,
    val items: List<UrlRuleItemSnapshot>,
    val hasPrimaryAction: Boolean = false,
    val selectionCount: Int = 0,
    val selectionActions: List<VaultActionSnapshot> = emptyList(),
) {
    companion object {
        val empty = UrlRuleListSnapshot(
            loaded = false,
            items = emptyList(),
            hasPrimaryAction = false,
            selectionCount = 0,
            selectionActions = emptyList(),
        )
    }
}

// ---------------------------------------------------------------------------
// Watchtower alerts snapshots.
// ---------------------------------------------------------------------------
