package com.artemchep.keyguard.apple.lists

import com.artemchep.keyguard.common.service.logging.LogLevel
import com.artemchep.keyguard.apple.KeyguardCore
import com.artemchep.keyguard.apple.model.VaultActionSnapshot

/** One entry of the system AutoFill (QuickType) index. */
data class AutofillIdentitySnapshot(
    val recordId: String,
    val serviceIdentifier: String,
    val user: String,
    val cipherId: String,
    val accountId: String,
)

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

enum class SshAgentHistoryItemKind {
    SECTION,
    VALUE,
}

/**
 * For SECTION headers only [caller] (the date label) is set. For VALUE rows [caller] is the requesting client,
 * [description] the request details line, and [response] the uppercase
 * [com.artemchep.keyguard.common.model.SshUsageHistoryResponseType] name (e.g. SUCCESS / USER_DENIED).
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

data class SshAgentHistorySnapshot(
    val loaded: Boolean,
    val subtitle: String?,
    val items: List<SshAgentHistoryItemSnapshot>,
) {
    companion object {
        val empty = SshAgentHistorySnapshot(loaded = false, subtitle = null, items = emptyList())
    }
}

/**
 * [date] is the localized change time. Each [actions] id routes back through
 * [ListSession.invokeItemAction].
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
 * [notFound] is true when the cipher id no longer resolves. [actions] are the screen's top-level overflow
 * actions; each id routes back through [ListSession.invokeAction].
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

data class LicenseItemSnapshot(
    val id: String,
    val name: String,
    val version: String,
    val license: String,
    val url: String?,
)

data class LicenseListSnapshot(
    val loaded: Boolean,
    val items: List<LicenseItemSnapshot>,
) {
    companion object {
        val empty = LicenseListSnapshot(loaded = false, items = emptyList())
    }
}

/** [score] is the translated-strings count. */
data class LocalizationContributorItemSnapshot(
    val id: String,
    val name: String,
    val score: Int,
)

data class LocalizationContributorsSnapshot(
    val loaded: Boolean,
    val items: List<LocalizationContributorItemSnapshot>,
) {
    companion object {
        val empty = LocalizationContributorsSnapshot(loaded = false, items = emptyList())
    }
}

enum class LogsItemKind {
    SECTION,
    VALUE,
}

/**
 * For SECTION headers only [text] is set; for VALUE rows [level] is the uppercase [LogLevel] name and [time]
 * the formatted timestamp.
 */
data class LogsItemSnapshot(
    val id: String,
    val kind: LogsItemKind,
    val text: String,
    val level: String?,
    val time: String?,
)

data class LogsSnapshot(
    val loaded: Boolean,
    val items: List<LogsItemSnapshot>,
) {
    companion object {
        val empty = LogsSnapshot(loaded = false, items = emptyList())
    }
}

/**
 * For blocked URLs [subtitle] is the matched URI and [detail] the block mode; for URL overrides [subtitle] is
 * the regex and [detail] the command. Each [actions] id routes back through
 * [ListSession.invokeItemAction].
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
 * [hasPrimaryAction] is true when the producer offers a create-new ("+") action, run via
 * [ListSession.invokePrimaryAction].
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
