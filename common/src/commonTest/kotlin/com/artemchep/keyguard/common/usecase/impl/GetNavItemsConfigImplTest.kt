package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.model.AccountId
import com.artemchep.keyguard.common.model.DAccount
import com.artemchep.keyguard.common.model.DProfile
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.model.NavItemRef
import com.artemchep.keyguard.common.model.NavItemsConfig
import com.artemchep.keyguard.common.model.NavItemsConfigDefaults
import com.artemchep.keyguard.common.service.gpgagent.GpgAgentKeyMetadata
import com.artemchep.keyguard.common.service.gpgagent.GpgAgentKeyMetadataKey
import com.artemchep.keyguard.common.usecase.GetAccounts
import com.artemchep.keyguard.common.usecase.GetCiphers
import com.artemchep.keyguard.common.usecase.GetProfiles
import com.artemchep.keyguard.common.usecase.WindowCoroutineScope
import com.artemchep.keyguard.core.store.bitwarden.BitwardenService
import com.artemchep.keyguard.feature.home.settings.accounts.model.AccountType
import com.artemchep.keyguard.ui.icons.generateAccentColors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class GetNavItemsConfigImplTest {
    @Test
    fun `conditional items are hidden before cached or upstream config emits`() = runTest {
        val fixture = fixture()

        val initialConfig = fixture.useCase().value

        assertFalse(initialConfig.sendsVisible())
        assertFalse(initialConfig.gpgToolsVisible())
        initialConfig.items
            .filterNot { item ->
                item.ref == sendsRef() || item.ref == gpgToolsRef()
            }
            .forEach { item ->
                assertTrue(item.visible, "Expected ${item.ref} to be initially visible")
            }
    }

    @Test
    fun `cached config emits before upstream availability`() = runTest {
        val cachedConfig = configWithSendsVisible(false)
        val fixture = fixture(
            cachedConfig = cachedConfig,
        )

        val cachedValue = async {
            fixture.useCase().first { config ->
                config == cachedConfig
            }
        }
        advanceUntilIdle()

        assertEquals(cachedConfig, cachedValue.await())
        assertEquals(emptyList(), fixture.cacheWrites)
    }

    @Test
    fun `upstream effective config replaces cache and is written`() = runTest {
        val cachedConfig = configWithSendsVisible(true)
        val fixture = fixture(
            cachedConfig = cachedConfig,
            accounts = listOf(
                createAccount(
                    id = "account-1",
                    type = AccountType.KEEPASS,
                ),
            ),
            profiles = listOf(
                createProfile(
                    accountId = "account-1",
                    hidden = false,
                ),
            ),
        )

        advanceTimeBy(2_001L)
        advanceUntilIdle()

        val effectiveConfig = fixture.useCase().value
        assertFalse(effectiveConfig.sendsVisible())
        assertEquals(effectiveConfig, fixture.cacheWrites.lastOrNull())
    }

    @Test
    fun `empty account reads do not overwrite cached config`() = runTest {
        val cachedConfig = configWithSendsVisible(false)
        val fixture = fixture(
            cachedConfig = cachedConfig,
            accounts = emptyList(),
            profiles = listOf(
                createProfile(
                    accountId = "account-1",
                    hidden = false,
                ),
            ),
        )

        advanceTimeBy(2_001L)
        advanceUntilIdle()

        assertEquals(cachedConfig, fixture.useCase().value)
        assertEquals(emptyList(), fixture.cacheWrites)
    }

    @Test
    fun `send availability only uses non-hidden profiles`() = runTest {
        val fixture = fixture(
            accounts = listOf(
                createAccount(
                    id = "bitwarden",
                    type = AccountType.BITWARDEN,
                ),
                createAccount(
                    id = "keepass",
                    type = AccountType.KEEPASS,
                ),
            ),
            profiles = listOf(
                createProfile(
                    accountId = "bitwarden",
                    hidden = true,
                ),
                createProfile(
                    accountId = "keepass",
                    hidden = false,
                ),
            ),
        )

        advanceTimeBy(2_001L)
        advanceUntilIdle()

        assertFalse(fixture.useCase().value.sendsVisible())
    }

    @Test
    fun `available sends preserve persisted visibility`() = runTest {
        val persistedConfig = configWithSendsVisible(false)
        val fixture = fixture(
            persistedConfig = persistedConfig,
            accounts = listOf(
                createAccount(
                    id = "account-1",
                    type = AccountType.BITWARDEN,
                ),
            ),
            profiles = listOf(
                createProfile(
                    accountId = "account-1",
                    hidden = false,
                ),
            ),
        )

        advanceTimeBy(2_001L)
        advanceUntilIdle()

        assertFalse(fixture.useCase().value.sendsVisible())
        assertEquals(fixture.useCase().value, fixture.cacheWrites.lastOrNull())
    }

    @Test
    fun `available sends stay visible when persisted visible`() = runTest {
        val fixture = fixture(
            accounts = listOf(
                createAccount(
                    id = "account-1",
                    type = AccountType.BITWARDEN,
                ),
            ),
            profiles = listOf(
                createProfile(
                    accountId = "account-1",
                    hidden = false,
                ),
            ),
        )

        advanceTimeBy(2_001L)
        advanceUntilIdle()

        assertTrue(fixture.useCase().value.sendsVisible())
    }

    @Test
    fun `gpg tools are hidden without usable gpg ciphers`() = runTest {
        val fixture = fixture(
            accounts = listOf(
                createAccount(
                    id = "account-1",
                    type = AccountType.BITWARDEN,
                ),
            ),
            profiles = listOf(
                createProfile(
                    accountId = "account-1",
                    hidden = false,
                ),
            ),
            ciphers = emptyList(),
        )

        advanceTimeBy(2_001L)
        advanceUntilIdle()

        assertFalse(fixture.useCase().value.gpgToolsVisible())
    }

    @Test
    fun `gpg tools are visible with usable gpg cipher`() = runTest {
        val fixture = fixture(
            accounts = listOf(
                createAccount(
                    id = "account-1",
                    type = AccountType.BITWARDEN,
                ),
            ),
            profiles = listOf(
                createProfile(
                    accountId = "account-1",
                    hidden = false,
                ),
            ),
            ciphers = listOf(
                createGpgCipher(
                    accountId = "account-1",
                ),
            ),
        )

        advanceTimeBy(2_001L)
        advanceUntilIdle()

        assertTrue(fixture.useCase().value.gpgToolsVisible())
    }

    @Test
    fun `available gpg tools preserve persisted visibility`() = runTest {
        val persistedConfig = configWithGpgToolsVisible(false)
        val fixture = fixture(
            persistedConfig = persistedConfig,
            accounts = listOf(
                createAccount(
                    id = "account-1",
                    type = AccountType.BITWARDEN,
                ),
            ),
            profiles = listOf(
                createProfile(
                    accountId = "account-1",
                    hidden = false,
                ),
            ),
            ciphers = listOf(
                createGpgCipher(
                    accountId = "account-1",
                ),
            ),
        )

        advanceTimeBy(2_001L)
        advanceUntilIdle()

        assertFalse(fixture.useCase().value.gpgToolsVisible())
    }

    @Test
    fun `gpg tools ignore ciphers from hidden profiles`() = runTest {
        val fixture = fixture(
            accounts = listOf(
                createAccount(
                    id = "gpg-account",
                    type = AccountType.BITWARDEN,
                ),
                createAccount(
                    id = "visible-account",
                    type = AccountType.KEEPASS,
                ),
            ),
            profiles = listOf(
                createProfile(
                    accountId = "gpg-account",
                    hidden = true,
                ),
                createProfile(
                    accountId = "visible-account",
                    hidden = false,
                ),
            ),
            ciphers = listOf(
                createGpgCipher(
                    accountId = "gpg-account",
                ),
            ),
        )

        advanceTimeBy(2_001L)
        advanceUntilIdle()

        assertFalse(fixture.useCase().value.gpgToolsVisible())
    }

    private fun TestScope.fixture(
        persistedConfig: NavItemsConfig? = NavItemsConfigDefaults.defaultConfig(),
        cachedConfig: NavItemsConfig? = null,
        accounts: List<DAccount> = emptyList(),
        profiles: List<DProfile> = emptyList(),
        ciphers: List<DSecret> = emptyList(),
    ): Fixture {
        val persistedConfigFlow = MutableStateFlow(persistedConfig)
        val cachedConfigFlow = MutableStateFlow(cachedConfig)
        val accountsFlow = MutableStateFlow(accounts)
        val profilesFlow = MutableStateFlow(profiles)
        val ciphersFlow = MutableStateFlow(ciphers)
        val cacheWrites = mutableListOf<NavItemsConfig>()
        val useCase = GetNavItemsConfigImpl(
            getAccounts = flowUseCase(accountsFlow),
            getProfiles = flowProfileUseCase(profilesFlow),
            getCiphers = flowCipherUseCase(ciphersFlow),
            getPersistedConfig = { persistedConfigFlow },
            getCachedConfig = { cachedConfigFlow },
            putCachedConfig = { config ->
                {
                    cacheWrites += config
                    cachedConfigFlow.value = config
                }
            },
            windowCoroutineScope = object : WindowCoroutineScope, CoroutineScope by backgroundScope {},
        )
        return Fixture(
            useCase = useCase,
            cacheWrites = cacheWrites,
        )
    }
}

private data class Fixture(
    val useCase: GetNavItemsConfigImpl,
    val cacheWrites: List<NavItemsConfig>,
)

private fun flowUseCase(
    flow: Flow<List<DAccount>>,
): GetAccounts = object : GetAccounts {
    override fun invoke(): Flow<List<DAccount>> = flow
}

private fun flowProfileUseCase(
    flow: Flow<List<DProfile>>,
): GetProfiles = object : GetProfiles {
    override fun invoke(): Flow<List<DProfile>> = flow
}

private fun flowCipherUseCase(
    flow: Flow<List<DSecret>>,
): GetCiphers = object : GetCiphers {
    override fun invoke(): Flow<List<DSecret>> = flow
}

private fun configWithSendsVisible(
    visible: Boolean,
): NavItemsConfig {
    val sendsRef = sendsRef()
    return NavItemsConfigDefaults.defaultConfig()
        .copy(
            items = NavItemsConfigDefaults.defaultItems()
                .map { item ->
                    if (item.ref == sendsRef) {
                        item.copy(
                            visible = visible,
                        )
                    } else {
                        item
                    }
                },
        )
}

private fun configWithGpgToolsVisible(
    visible: Boolean,
): NavItemsConfig {
    val gpgToolsRef = gpgToolsRef()
    return NavItemsConfigDefaults.defaultConfig()
        .copy(
            items = NavItemsConfigDefaults.defaultItems()
                .map { item ->
                    if (item.ref == gpgToolsRef) {
                        item.copy(
                            visible = visible,
                        )
                    } else {
                        item
                    }
                },
        )
}

private fun NavItemsConfig.sendsVisible(): Boolean {
    val sendsRef = sendsRef()
    return items.single { it.ref == sendsRef }
        .visible
}

private fun NavItemsConfig.gpgToolsVisible(): Boolean {
    val gpgToolsRef = gpgToolsRef()
    return items.single { it.ref == gpgToolsRef }
        .visible
}

private fun sendsRef() = NavItemRef.BuiltIn(NavItemsConfigDefaults.BUILT_IN_SENDS)

private fun gpgToolsRef() = NavItemRef.BuiltIn(NavItemsConfigDefaults.BUILT_IN_GPG_TOOLS)

private fun createAccount(
    id: String,
    type: AccountType,
) = DAccount(
    id = AccountId(id),
    username = "user@example.com",
    host = "vault.example.com",
    webVaultUrl = "https://vault.example.com",
    localVaultUrl = null,
    type = type,
    faviconServer = null,
)

private fun createProfile(
    accountId: String,
    hidden: Boolean,
) = DProfile(
    accountId = accountId,
    profileId = "profile-$accountId",
    keyBase64 = "key",
    privateKeyBase64 = "private-key",
    accountHost = "vault.example.com",
    email = "$accountId@example.com",
    emailVerified = true,
    accentColor = generateAccentColors(accountId),
    name = "User $accountId",
    description = "",
    premium = null,
    hidden = hidden,
    securityStamp = null,
    twoFactorEnabled = null,
    masterPasswordHint = null,
    masterPasswordHintEnabled = null,
    unofficialServer = false,
    serverVersion = null,
)

private fun createGpgCipher(
    accountId: String,
) = DSecret(
    id = "gpg-cipher",
    accountId = accountId,
    folderId = null,
    organizationId = null,
    collectionIds = emptySet(),
    revisionDate = Instant.fromEpochMilliseconds(0),
    createdDate = Instant.fromEpochMilliseconds(0),
    archivedDate = null,
    deletedDate = null,
    service = BitwardenService(),
    name = "GPG key",
    notes = "",
    favorite = false,
    reprompt = false,
    synced = true,
    type = DSecret.Type.GpgKey,
    gpgKey = DSecret.GpgKey(
        privateKeyArmored = "private",
        publicKeyArmored = "public",
        fingerprint = "fingerprint",
        metadata = GpgAgentKeyMetadata(
            keys = listOf(
                GpgAgentKeyMetadataKey(
                    keygrip = "keygrip",
                    fingerprint = "fingerprint",
                    algorithm = "ED25519",
                    capabilities = setOf("sign"),
                ),
            ),
        ),
    ),
)
