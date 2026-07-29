package com.artemchep.keyguard.common.service.credentialexchange

import com.artemchep.keyguard.common.model.CryptoHashAlgorithm
import com.artemchep.keyguard.common.model.DFolder
import com.artemchep.keyguard.common.model.DProfile
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.model.FolderHierarchyMode
import com.artemchep.keyguard.common.model.TotpToken
import com.artemchep.keyguard.core.store.bitwarden.BitwardenService
import com.artemchep.keyguard.ui.icons.generateAccentColors
import kotlin.time.Instant

const val CXF_TEST_PASSKEY_KEY_URL: String =
    "MIGHAgEAMBMGByqGSM49AgEGCCqGSM49AwEHBG0wawIBAQQgARu_0sCt20EpgVxb" +
        "4Puq3Ga5VVLpuTY75ngvZlyq3X6hRANCAASmdk1xLsK0oOlhxIPp0d1ZuS0sT9nf" +
        "6BZtSelhqvLBW0fOL33l_bXgsr_STUHjCLn8l6gcRJwe7OQvbQubZ1dY"

const val CXF_TEST_PASSKEY_KEY_STANDARD: String =
    "MIGHAgEAMBMGByqGSM49AgEGCCqGSM49AwEHBG0wawIBAQQgARu/0sCt20EpgVxb" +
        "4Puq3Ga5VVLpuTY75ngvZlyq3X6hRANCAASmdk1xLsK0oOlhxIPp0d1ZuS0sT9nf" +
        "6BZtSelhqvLBW0fOL33l/bXgsr/STUHjCLn8l6gcRJwe7OQvbQubZ1dY"

fun cxfProfile(
    accountId: String = "acc-1",
    email: String = "alice@example.com",
    name: String = "Alice Example",
) = DProfile(
    accountId = accountId,
    profileId = "profile-$accountId",
    keyBase64 = "key",
    privateKeyBase64 = "private-key",
    accountHost = "vault.example.com",
    email = email,
    emailVerified = true,
    accentColor = generateAccentColors(accountId),
    name = name,
    description = "",
    premium = null,
    hidden = false,
    securityStamp = null,
    twoFactorEnabled = null,
    masterPasswordHint = null,
    masterPasswordHintEnabled = null,
    unofficialServer = false,
    serverVersion = null,
)

/**
 * The defaults are deliberately the *canonical* spellings — a lower-case UUID
 * credential id, a padded standard-base64 key, an unpadded base64url user
 * handle — because those are the forms a round trip returns, so a case that
 * does not care about encoding stays at identity.
 *
 * [keyType], [keyAlgorithm], [keyCurve], [rpName] and [discoverable] are
 * exposed even though the CXF wire format has no place for them: the importer
 * hard-codes them to exactly these defaults, so a test can only *observe* that
 * coercion by starting from something else.
 */
@Suppress("LongParameterList")
fun cxfFido2Credential(
    credentialId: String = "e8d88789-e916-e196-3cbd-81dafae71bbc",
    keyValue: String = CXF_TEST_PASSKEY_KEY_STANDARD,
    rpId: String = "example.com",
    userHandle: String? = "AAECAwQFBg",
    userName: String? = "alice",
    userDisplayName: String? = "Alice",
    counter: Int? = 0,
    creationDate: Instant = Instant.parse("2024-01-30T11:23:54Z"),
    keyType: String = "public-key",
    keyAlgorithm: String = "ECDSA",
    keyCurve: String = "P-256",
    rpName: String? = null,
    discoverable: Boolean = true,
) = DSecret.Login.Fido2Credentials(
    credentialId = credentialId,
    keyType = keyType,
    keyAlgorithm = keyAlgorithm,
    keyCurve = keyCurve,
    keyValue = keyValue,
    rpId = rpId,
    rpName = rpName,
    counter = counter,
    userHandle = userHandle,
    userName = userName,
    userDisplayName = userDisplayName,
    discoverable = discoverable,
    creationDate = creationDate,
)

/**
 * A stored TOTP. [DSecret.Login.Totp.raw] is built from the same parameters as
 * the token so the fixture is self-consistent, but nothing on the export path
 * reads it and the round trip rebuilds it from scratch — only the parsed
 * configuration survives, so `raw` is never worth asserting on.
 */
@Suppress("LongParameterList")
fun cxfTotpAuth(
    keyBase32: String = "JBSWY3DPEHPK3PXP",
    algorithm: CryptoHashAlgorithm = CryptoHashAlgorithm.SHA_1,
    digits: Int = 6,
    period: Long = 30L,
    username: String? = null,
    issuer: String? = null,
): DSecret.Login.Totp {
    val label = listOfNotNull(issuer, username ?: "test").joinToString(separator = ":")
    val raw = buildString {
        append("otpauth://totp/$label?secret=$keyBase32")
        append("&algorithm=${algorithm.name.replace("_", "")}")
        append("&digits=$digits")
        append("&period=$period")
        if (issuer != null) append("&issuer=$issuer")
    }
    return DSecret.Login.Totp(
        raw = raw,
        token = TotpToken.TotpAuth(
            algorithm = algorithm,
            keyBase32 = keyBase32,
            raw = raw,
            digits = digits,
            period = period,
            username = username,
            issuer = issuer,
        ),
    )
}

/**
 * An HOTP token. CXF models only time-based OTP, so this is always an export
 * skip — it exists to exercise that branch.
 */
fun cxfHotpAuth(
    keyBase32: String = "JBSWY3DPEHPK3PXP",
    counter: Long = 1L,
    digits: Int = 6,
): DSecret.Login.Totp {
    val raw = "otpauth://hotp/test?secret=$keyBase32&counter=$counter"
    return DSecret.Login.Totp(
        raw = raw,
        token = TotpToken.HotpAuth(
            algorithm = CryptoHashAlgorithm.SHA_1,
            keyBase32 = keyBase32,
            raw = raw,
            digits = digits,
            counter = counter,
        ),
    )
}

/**
 * A Steam token. Representable on the wire as the `steam` extension value
 * (pinned deviation D8), so unlike [cxfHotpAuth] this one round-trips: the
 * secret survives, the username and issuer do not, because
 * [TotpToken.SteamAuth] has nowhere to hold them.
 */
fun cxfSteamTotp(
    keyBase32: String = "JBSWY3DPEHPK3PXP",
): DSecret.Login.Totp {
    val raw = "steam://$keyBase32"
    return DSecret.Login.Totp(
        raw = raw,
        token = TotpToken.SteamAuth(
            algorithm = CryptoHashAlgorithm.SHA_1,
            keyBase32 = keyBase32,
            raw = raw,
        ),
    )
}

/**
 * A canonical SHA-256 certificate fingerprint: 32 bytes, upper-case hex,
 * colon-separated. That is the exact shape the importer normalizes back to, so
 * a fixture built with this survives a round trip unchanged.
 */
fun cxfCertFingerprint(seed: Int = 0): String = (0 until 32)
    .joinToString(separator = ":") { index ->
        ((index + seed) % 256).toString(16).padStart(2, '0').uppercase()
    }

/**
 * Stamps an item with every member the CXF wire format has nowhere to put, so
 * one round-trip case can prove the whole "never on the wire" group at once
 * instead of one member per test.
 *
 * `archivedDate` is deliberately not one of them. It has nowhere to go on the
 * wire either, but it no longer *travels and is lost* — it stops the item from
 * being exported at all, which is a different outcome and belongs to its own
 * case rather than hiding inside this group.
 */
@Suppress("LongParameterList")
fun DSecret.withUnexportableMembers(
    reprompt: Boolean = true,
    organizationId: String? = "org-1",
    collectionIds: Set<String> = setOf("col-1"),
    attachments: List<DSecret.Attachment> = listOf(cxfAttachment()),
    passwordHistory: List<DSecret.Login.PasswordHistory> = listOf(cxfPasswordHistory()),
    gpgKey: DSecret.GpgKey? = cxfGpgKey(),
): DSecret = copy(
    reprompt = reprompt,
    organizationId = organizationId,
    collectionIds = collectionIds,
    attachments = attachments,
    passwordHistory = passwordHistory,
    gpgKey = gpgKey,
)

fun cxfAttachment(
    id: String = "att-1",
    fileName: String = "notes.txt",
): DSecret.Attachment = DSecret.Attachment.Remote(
    id = id,
    url = "https://example.com/$id",
    remoteCipherId = "item-1",
    fileName = fileName,
    keyBase64 = null,
    size = 1024L,
)

fun cxfPasswordHistory(
    password: String = "old-s3cr3t",
    lastUsedDate: Instant? = Instant.parse("2024-01-01T00:00:00Z"),
): DSecret.Login.PasswordHistory = DSecret.Login.PasswordHistory(
    password = password,
    lastUsedDate = lastUsedDate,
)

fun cxfGpgKey(
    fingerprint: String = "ABCD",
): DSecret.GpgKey = DSecret.GpgKey(
    privateKeyArmored = "-----BEGIN PGP PRIVATE KEY BLOCK-----",
    publicKeyArmored = "-----BEGIN PGP PUBLIC KEY BLOCK-----",
    fingerprint = fingerprint,
)

@Suppress("LongParameterList")
fun cxfSecret(
    id: String = "item-1",
    accountId: String = "acc-1",
    name: String = "Example",
    favorite: Boolean = true,
    tags: List<String> = listOf("work"),
    uris: List<DSecret.Uri> = emptyList(),
    notes: String = "",
    fields: List<DSecret.Field> = emptyList(),
    folderId: String? = null,
    type: DSecret.Type = DSecret.Type.Login,
    login: DSecret.Login? = null,
    card: DSecret.Card? = null,
    identity: DSecret.Identity? = null,
    sshKey: DSecret.SshKey? = null,
    createdDate: Instant? = Instant.parse("2024-01-30T11:23:54Z"),
    revisionDate: Instant = Instant.parse("2024-01-30T14:09:33Z"),
    deletedDate: Instant? = null,
) = DSecret(
    id = id,
    accountId = accountId,
    folderId = folderId,
    organizationId = null,
    collectionIds = emptySet(),
    revisionDate = revisionDate,
    createdDate = createdDate,
    archivedDate = null,
    deletedDate = deletedDate,
    service = BitwardenService(),
    name = name,
    notes = notes,
    favorite = favorite,
    reprompt = false,
    synced = true,
    tags = tags,
    uris = uris,
    fields = fields,
    type = type,
    login = login,
    card = card,
    identity = identity,
    sshKey = sshKey,
)

@Suppress("LongParameterList")
fun cxfLoginSecret(
    id: String = "item-1",
    accountId: String = "acc-1",
    name: String = "Example",
    favorite: Boolean = true,
    tags: List<String> = listOf("work"),
    uris: List<DSecret.Uri> = emptyList(),
    notes: String = "",
    fields: List<DSecret.Field> = emptyList(),
    folderId: String? = null,
    login: DSecret.Login,
    createdDate: Instant? = Instant.parse("2024-01-30T11:23:54Z"),
    revisionDate: Instant = Instant.parse("2024-01-30T14:09:33Z"),
    deletedDate: Instant? = null,
) = cxfSecret(
    id = id,
    accountId = accountId,
    name = name,
    favorite = favorite,
    tags = tags,
    uris = uris,
    notes = notes,
    fields = fields,
    folderId = folderId,
    type = DSecret.Type.Login,
    login = login,
    createdDate = createdDate,
    revisionDate = revisionDate,
    deletedDate = deletedDate,
)

fun cxfFolder(
    id: String,
    name: String,
    accountId: String = "acc-1",
    parentId: String? = null,
    hierarchyMode: FolderHierarchyMode = FolderHierarchyMode.Path,
    deleted: Boolean = false,
    revisionDate: Instant = Instant.parse("2024-01-30T14:09:33Z"),
) = DFolder(
    id = id,
    accountId = accountId,
    revisionDate = revisionDate,
    service = BitwardenService(),
    deleted = deleted,
    synced = true,
    name = name,
    parentId = parentId,
    hierarchyMode = hierarchyMode,
)
