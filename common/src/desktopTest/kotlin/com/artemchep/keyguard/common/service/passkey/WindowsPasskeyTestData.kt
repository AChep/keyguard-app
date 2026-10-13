package com.artemchep.keyguard.common.service.passkey

import com.artemchep.keyguard.common.model.DProfile
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.core.store.bitwarden.BitwardenService
import com.artemchep.keyguard.ui.icons.generateAccentColors
import com.artemchep.keyguard.util.webauthn.PasskeyCredentialId
import com.artemchep.keyguard.util.windowspasskeys.WindowsPasskeyDescriptor
import com.artemchep.keyguard.util.windowspasskeys.WindowsPasskeyRequest
import com.artemchep.keyguard.util.windowspasskeys.WindowsPasskeyUser
import kotlin.time.Instant

internal val TEST_NOW: Instant = Instant.fromEpochMilliseconds(0)

internal const val TEST_RP_ID = "example.com"

internal const val TEST_WINDOW = 42L

/** The client data hash Windows hands the provider; the signature covers it. */
internal val TEST_CLIENT_DATA_HASH = ByteArray(32) { 7 }

/** The bytes of the test passkeys' stored user handle, `dXNlcg`. */
internal val TEST_USER_HANDLE = "user".encodeToByteArray()

/** What is left of Windows' 120-second deadline when a request reaches Kotlin. */
internal const val TEST_EXPIRES_IN_MS = 110_000L

internal fun testPasskey(
    credentialId: String = "11223344-5566-7788-9900-aabbccddeeff",
) = DSecret.Login.Fido2Credentials(
    credentialId = credentialId, keyType = "public-key", keyAlgorithm = "ECDSA",
    keyCurve = "P-256", keyValue = "cHJpdmF0ZQ", rpId = TEST_RP_ID, rpName = null, counter = 0,
    userHandle = "dXNlcg", userName = null, userDisplayName = null, discoverable = true, creationDate = TEST_NOW,
)

internal fun testCipher(vararg passkeys: DSecret.Login.Fido2Credentials) = DSecret(
    id = "cipher", accountId = "account", folderId = null, organizationId = null, collectionIds = emptySet(),
    revisionDate = TEST_NOW, createdDate = TEST_NOW, archivedDate = null, deletedDate = null,
    service = BitwardenService(), name = "Imported login", notes = "", favorite = false, reprompt = false,
    synced = true, type = DSecret.Type.Login,
    login = DSecret.Login(username = "login@example.com", fido2Credentials = passkeys.toList()),
)

/** The profile of an account the user hid from the main screens and autofill. */
internal fun testHiddenProfile(accountId: String) = DProfile(
    accountId = accountId, profileId = "profile", keyBase64 = "", privateKeyBase64 = "",
    accountHost = TEST_RP_ID, email = "alice@example.com", emailVerified = true,
    accentColor = generateAccentColors("alice"), name = "Alice", description = "", premium = true,
    hidden = true, securityStamp = null, twoFactorEnabled = null, masterPasswordHint = null,
    masterPasswordHintEnabled = null, unofficialServer = false, serverVersion = null,
)

internal fun DSecret.Login.Fido2Credentials.descriptor() = WindowsPasskeyDescriptor(
    id = PasskeyCredentialId.encode(credentialId),
    type = "public-key",
)

internal fun testSignInRequest(
    allowed: List<WindowsPasskeyDescriptor> = emptyList(),
) = WindowsPasskeyRequest(
    id = 1, create = false, window = TEST_WINDOW, rpId = TEST_RP_ID,
    clientDataHash = TEST_CLIENT_DATA_HASH, userVerification = "required",
    credentials = allowed, user = null, rpName = null, algorithms = emptyList(), discoverable = false,
    expiresInMs = TEST_EXPIRES_IN_MS,
)

internal fun testRegistrationRequest(
    excluded: List<WindowsPasskeyDescriptor> = emptyList(),
) = testSignInRequest(excluded).copy(
    create = true, user = WindowsPasskeyUser(id = TEST_USER_HANDLE, name = "alice", displayName = "Alice"),
    rpName = "Example", algorithms = listOf(-7), discoverable = true,
)
