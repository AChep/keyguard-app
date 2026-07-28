package com.artemchep.keyguard.common.model

import com.artemchep.keyguard.core.store.bitwarden.exists

/**
 * A per-attribute presence index of a list of [DSecret]s, built in a single
 * pass. It answers "does any cipher in the list match this primitive?" via set membership.
 */
data class DFilterCipherPresence(
    val cipherIds: Set<String?>,
    val accountIds: Set<String?>,
    // Contains null when a cipher has no folder.
    val folderIds: Set<String?>,
    val organizationIds: Set<String?>,
    // Contains null when a cipher has empty collectionIds.
    val collectionIds: Set<String?>,
    // Contains null when a cipher has empty tags.
    val tags: Set<String?>,
    val types: Set<DSecret.Type>,
    val favorite: Set<Boolean>,
    val reprompt: Set<Boolean>,
    val synced: Set<Boolean>,
    val error: Set<Boolean>,
    val passwordScores: Set<PasswordStrength.Score>,
    val hasOtp: Boolean,
    val hasAttachments: Boolean,
    val hasPasskeys: Boolean,
    val hasIgnoredAlerts: Boolean,
) {
    companion object {
        fun <T> of(
            items: List<T>,
            getter: (T) -> DSecret,
        ): DFilterCipherPresence {
            val cipherIds = mutableSetOf<String?>()
            val accountIds = mutableSetOf<String?>()
            val folderIds = mutableSetOf<String?>()
            val organizationIds = mutableSetOf<String?>()
            val collectionIds = mutableSetOf<String?>()
            val tags = mutableSetOf<String?>()
            val types = mutableSetOf<DSecret.Type>()
            val favorite = mutableSetOf<Boolean>()
            val reprompt = mutableSetOf<Boolean>()
            val synced = mutableSetOf<Boolean>()
            val error = mutableSetOf<Boolean>()
            val passwordScores = mutableSetOf<PasswordStrength.Score>()
            var hasOtp = false
            var hasAttachments = false
            var hasPasskeys = false
            var hasIgnoredAlerts = false
            items.forEach { item ->
                val cipher = getter(item)
                cipherIds += cipher.id
                accountIds += cipher.accountId
                folderIds += cipher.folderId
                organizationIds += cipher.organizationId
                // Mirror the null special-case used by the ById predicate: a
                // cipher with no collections is represented by a null marker.
                if (cipher.collectionIds.isEmpty()) {
                    collectionIds += null
                } else {
                    collectionIds += cipher.collectionIds
                }
                if (cipher.tags.isEmpty()) {
                    tags += null
                } else {
                    tags += cipher.tags
                }
                types += cipher.type
                favorite += cipher.favorite
                reprompt += cipher.reprompt
                synced += cipher.synced
                error += cipher.service.error.exists(cipher.revisionDate)
                cipher.login?.passwordStrength?.score
                    ?.let(passwordScores::add)
                if (cipher.login?.totp != null) {
                    hasOtp = true
                }
                if (cipher.attachments.isNotEmpty()) {
                    hasAttachments = true
                }
                if (!cipher.login?.fido2Credentials.isNullOrEmpty()) {
                    hasPasskeys = true
                }
                if (cipher.ignoredAlerts.isNotEmpty()) {
                    hasIgnoredAlerts = true
                }
            }
            return DFilterCipherPresence(
                cipherIds = cipherIds,
                accountIds = accountIds,
                folderIds = folderIds,
                organizationIds = organizationIds,
                collectionIds = collectionIds,
                tags = tags,
                types = types,
                favorite = favorite,
                reprompt = reprompt,
                synced = synced,
                error = error,
                passwordScores = passwordScores,
                hasOtp = hasOtp,
                hasAttachments = hasAttachments,
                hasPasskeys = hasPasskeys,
                hasIgnoredAlerts = hasIgnoredAlerts,
            )
        }
    }
}

fun DFilter.Primitive.existsIn(
    presence: DFilterCipherPresence,
): Boolean? = when (this) {
    is DFilter.ById -> when (what) {
        DFilter.ById.What.ACCOUNT -> id in presence.accountIds
        DFilter.ById.What.FOLDER -> id in presence.folderIds
        DFilter.ById.What.ORGANIZATION -> id in presence.organizationIds
        DFilter.ById.What.CIPHER -> id in presence.cipherIds
        DFilter.ById.What.TAG -> id in presence.tags
        DFilter.ById.What.COLLECTION -> id in presence.collectionIds
    }

    is DFilter.ByType -> type in presence.types
    is DFilter.ByFavorite -> true in presence.favorite
    is DFilter.ByOtp -> presence.hasOtp
    is DFilter.ByAttachments -> presence.hasAttachments
    is DFilter.ByPasskeys -> presence.hasPasskeys
    is DFilter.ByPasswordStrength -> score in presence.passwordScores
    is DFilter.BySync -> synced in presence.synced
    is DFilter.ByReprompt -> reprompt in presence.reprompt
    is DFilter.ByError -> error in presence.error
    is DFilter.ByIgnoredAlerts -> presence.hasIgnoredAlerts

    // Not answerable from a local attribute index (needs DI/network or a
    // materialized raw-password set); the caller must fall back.
    is DFilter.ByPasswordValue,
    is DFilter.ByWeakSshKeys,
    is DFilter.ByUnusableGpgKeys,
    is DFilter.ByWeakGpgKeys,
    is DFilter.ByGpgKeyPublishing,
    is DFilter.ByPasswordDuplicates,
    is DFilter.ByPasswordPwned,
    is DFilter.ByWebsitePwned,
    is DFilter.ByIncomplete,
    is DFilter.ByExpiring,
    is DFilter.ByUnsecureWebsites,
    is DFilter.ByTfaWebsites,
    is DFilter.ByPasskeyWebsites,
    is DFilter.ByDuplicateWebsites,
    is DFilter.ByBroadWebsites,
    -> null
}
