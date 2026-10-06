package com.artemchep.keyguard.test

import com.artemchep.keyguard.common.model.AccountId
import com.artemchep.keyguard.common.model.DAccount
import com.artemchep.keyguard.common.model.DProfile
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.model.DSend
import com.artemchep.keyguard.common.model.DWatchtowerAlertType
import com.artemchep.keyguard.core.store.bitwarden.BitwardenService
import com.artemchep.keyguard.feature.home.settings.accounts.model.AccountType
import com.artemchep.keyguard.ui.icons.generateAccentColors
import kotlin.time.Instant

internal val TEST_INSTANT: Instant = Instant.parse("2024-01-01T00:00:00Z")

internal fun createSecret(
    id: String,
    name: String = id,
    accountId: String = "account-id",
    folderId: String? = null,
    collectionIds: Set<String> = emptySet(),
    organizationId: String? = null,
    tags: List<String> = emptyList(),
    type: DSecret.Type = DSecret.Type.Login,
    favorite: Boolean = false,
    reprompt: Boolean = false,
    synced: Boolean = true,
    service: BitwardenService = BitwardenService(),
    ignoredAlerts: Map<DWatchtowerAlertType, Instant> = emptyMap(),
    notes: String = "",
    uris: List<DSecret.Uri> = emptyList(),
    fields: List<DSecret.Field> = emptyList(),
    attachments: List<DSecret.Attachment> = emptyList(),
    login: DSecret.Login? = null,
    card: DSecret.Card? = null,
    identity: DSecret.Identity? = null,
    revisionDate: Instant = TEST_INSTANT,
    createdDate: Instant? = TEST_INSTANT,
    archivedDate: Instant? = null,
    deletedDate: Instant? = null,
    keyBase64: String? = null,
    links: List<DSecret.Link> = emptyList(),
    passwordHistory: List<DSecret.Login.PasswordHistory> = emptyList(),
    sshKey: DSecret.SshKey? = null,
    gpgKey: DSecret.GpgKey? = null,
): DSecret = DSecret(
    id = id,
    accountId = accountId,
    folderId = folderId,
    organizationId = organizationId,
    collectionIds = collectionIds,
    revisionDate = revisionDate,
    createdDate = createdDate,
    archivedDate = archivedDate,
    deletedDate = deletedDate,
    service = service,
    keyBase64 = keyBase64,
    name = name,
    notes = notes,
    favorite = favorite,
    reprompt = reprompt,
    synced = synced,
    ignoredAlerts = ignoredAlerts,
    tags = tags,
    uris = uris,
    links = links,
    fields = fields,
    attachments = attachments,
    passwordHistory = passwordHistory,
    type = type,
    login = login,
    card = card,
    identity = identity,
    sshKey = sshKey,
    gpgKey = gpgKey,
)

internal fun createAccount(
    id: String,
    type: AccountType,
): DAccount = DAccount(
    id = AccountId(id),
    username = "user@example.com",
    host = "vault.example.com",
    webVaultUrl = "https://vault.example.com",
    localVaultUrl = null,
    type = type,
    faviconServer = null,
)

internal fun createProfile(
    accountId: String,
    name: String = "User $accountId",
    email: String = "$accountId@example.com",
    emailVerified: Boolean? = true,
    premium: Boolean? = null,
    hidden: Boolean = false,
): DProfile = DProfile(
    accountId = accountId,
    profileId = "profile-$accountId",
    keyBase64 = "key",
    privateKeyBase64 = "private-key",
    accountHost = "vault.example.com",
    email = email,
    emailVerified = emailVerified,
    accentColor = generateAccentColors(accountId),
    name = name,
    description = "",
    premium = premium,
    hidden = hidden,
    securityStamp = null,
    twoFactorEnabled = null,
    masterPasswordHint = null,
    masterPasswordHintEnabled = null,
    unofficialServer = false,
    serverVersion = null,
)

internal fun createSend(
    id: String = "send-1",
    accountId: String = "account-1",
    name: String = "Send",
    expirationDate: Instant? = null,
    type: DSend.Type = DSend.Type.Text,
    text: DSend.Text? = DSend.Text(
        text = "body",
        hidden = false,
    ),
    file: DSend.File? = null,
): DSend = DSend(
    id = id,
    accountId = accountId,
    accessId = "access-1",
    keyBase64 = "send-key",
    revisionDate = TEST_INSTANT,
    createdDate = TEST_INSTANT,
    deletedDate = null,
    expirationDate = expirationDate,
    service = BitwardenService(),
    authType = DSend.AuthType.None,
    name = name,
    notes = "",
    accessCount = 0,
    hasPassword = false,
    synced = true,
    disabled = false,
    hideEmail = false,
    emails = emptyList(),
    type = type,
    text = text,
    file = file,
)
