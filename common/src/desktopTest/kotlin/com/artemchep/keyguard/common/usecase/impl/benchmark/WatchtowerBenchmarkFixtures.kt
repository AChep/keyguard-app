package com.artemchep.keyguard.common.usecase.impl.benchmark

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.io
import com.artemchep.keyguard.common.model.CheckPasswordSetLeakRequest
import com.artemchep.keyguard.common.model.DEquivalentDomains
import com.artemchep.keyguard.common.model.DGpgKeyserverState
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.model.DSecretDuplicateGroup
import com.artemchep.keyguard.common.model.DWatchtowerAlertType
import com.artemchep.keyguard.common.model.DFilter
import com.artemchep.keyguard.common.model.FileResource
import com.artemchep.keyguard.common.model.GpgKeyserverVerificationStatus
import com.artemchep.keyguard.common.model.KeyPair
import com.artemchep.keyguard.common.model.KeyParameterRawZero
import com.artemchep.keyguard.common.model.PasswordPwnage
import com.artemchep.keyguard.common.model.PasswordStrength
import com.artemchep.keyguard.common.service.crypto.GpgPublicKeyInfo
import com.artemchep.keyguard.common.service.crypto.GpgPublicKeyParseResult
import com.artemchep.keyguard.common.service.crypto.GpgPublicKeyParser
import com.artemchep.keyguard.common.service.crypto.GpgPublicSubKeyInfo
import com.artemchep.keyguard.common.service.crypto.KeyPairGenerator
import com.artemchep.keyguard.common.service.gpgagent.GpgAgentKeyMetadata
import com.artemchep.keyguard.common.service.gpgagent.GpgAgentKeyMetadataKey
import com.artemchep.keyguard.common.service.gpgagent.normalizeGpgFingerprint
import com.artemchep.keyguard.common.service.gpgkeyserver.GpgKeyserverStateRepository
import com.artemchep.keyguard.common.service.logging.LogLevel
import com.artemchep.keyguard.common.service.logging.LogRepository
import com.artemchep.keyguard.common.service.passkey.PassKeyService
import com.artemchep.keyguard.common.service.passkey.PassKeyServiceInfo
import com.artemchep.keyguard.common.service.similarity.impl.SimilarityServiceImpl
import com.artemchep.keyguard.common.service.text.impl.Base64ServiceImpl
import com.artemchep.keyguard.common.service.text.TextService
import com.artemchep.keyguard.common.service.tld.TldService
import com.artemchep.keyguard.common.service.tld.impl.TldServiceImpl
import com.artemchep.keyguard.common.service.twofa.TwoFaService
import com.artemchep.keyguard.common.service.twofa.TwoFaServiceInfo
import com.artemchep.keyguard.common.usecase.CheckPasswordSetLeak
import com.artemchep.keyguard.common.usecase.CipherDuplicatesCheck
import com.artemchep.keyguard.common.usecase.GetAutofillDefaultMatchDetection
import com.artemchep.keyguard.common.usecase.GetBreaches
import com.artemchep.keyguard.common.usecase.GetBreachesLatestDate
import com.artemchep.keyguard.common.usecase.GetCheckPasskeys
import com.artemchep.keyguard.common.usecase.GetCheckPwnedPasswords
import com.artemchep.keyguard.common.usecase.GetCheckPwnedServices
import com.artemchep.keyguard.common.usecase.GetCheckTwoFA
import com.artemchep.keyguard.common.usecase.GetEquivalentDomains
import com.artemchep.keyguard.common.usecase.GetPasskeys
import com.artemchep.keyguard.common.usecase.GetTwoFa
import com.artemchep.keyguard.common.usecase.impl.GpgWatchtowerPolicy
import com.artemchep.keyguard.common.usecase.impl.WatchtowerBroadUris
import com.artemchep.keyguard.common.usecase.impl.WatchtowerClientResult
import com.artemchep.keyguard.common.usecase.impl.WatchtowerClientTyped
import com.artemchep.keyguard.common.usecase.impl.WatchtowerDuplicateUris
import com.artemchep.keyguard.common.usecase.impl.WatchtowerExpiring
import com.artemchep.keyguard.common.usecase.impl.WatchtowerGpgKeyPublishing
import com.artemchep.keyguard.common.usecase.impl.WatchtowerGpgKeyUnusable
import com.artemchep.keyguard.common.usecase.impl.WatchtowerInactivePasskey
import com.artemchep.keyguard.common.usecase.impl.WatchtowerInactiveTfa
import com.artemchep.keyguard.common.usecase.impl.WatchtowerIncomplete
import com.artemchep.keyguard.common.usecase.impl.WatchtowerPasswordPwned
import com.artemchep.keyguard.common.usecase.impl.WatchtowerPasswordStrength
import com.artemchep.keyguard.common.usecase.impl.WatchtowerSshKeyStrength
import com.artemchep.keyguard.common.usecase.impl.WatchtowerUnsecureWebsite
import com.artemchep.keyguard.common.usecase.impl.WatchtowerWeakGpgKey
import com.artemchep.keyguard.common.usecase.impl.WatchtowerWebsitePwned
import com.artemchep.keyguard.core.store.bitwarden.BitwardenService
import com.artemchep.keyguard.crypto.NativeCryptoGenerator
import com.artemchep.keyguard.util.io.toSource
import com.artemchep.keyguard.provider.bitwarden.entity.HibpBreachGroup
import com.artemchep.keyguard.provider.bitwarden.entity.HibpBreachResponse
import com.artemchep.keyguard.provider.bitwarden.usecase.CipherBreachCheckImpl
import com.artemchep.keyguard.provider.bitwarden.usecase.CipherDuplicatesCheckImpl
import com.artemchep.keyguard.provider.bitwarden.usecase.CipherExpiringCheckImpl
import com.artemchep.keyguard.provider.bitwarden.usecase.CipherIncompleteCheckImpl
import com.artemchep.keyguard.provider.bitwarden.usecase.CipherSshKeyWeakCheckImpl
import com.artemchep.keyguard.provider.bitwarden.usecase.CipherUnsecureUrlCheckImpl
import com.artemchep.keyguard.provider.bitwarden.usecase.CipherUrlBroadCheckImpl
import com.artemchep.keyguard.provider.bitwarden.usecase.CipherUrlCheckImpl
import com.artemchep.keyguard.provider.bitwarden.usecase.CipherUrlDuplicateCheckImpl
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.datetime.LocalDate
import kotlinx.io.Source
import org.kodein.di.DI
import org.kodein.di.direct
import kotlin.time.Instant

internal class WatchtowerBenchmarkFixtures(
    corpusSize: Int = DEFAULT_CORPUS_SIZE,
    duplicateCorpusSize: Int = DUPLICATE_CORPUS_SIZE,
    serviceCount: Int = DEFAULT_SERVICE_COUNT,
) {
    val corpus: List<DSecret> = List(corpusSize) { index ->
        createSecret(
            index = index,
            serviceCount = serviceCount,
        )
    }
    val duplicateCorpus: List<DSecret> = corpus.take(duplicateCorpusSize)

    private val tldService: TldService = TldServiceImpl(
        textService = BenchmarkTldTextService,
        logRepository = BenchmarkLogRepository,
    )
    private val equivalentDomains = BenchmarkEquivalentDomains()
    private val cipherUrlCheck = CipherUrlCheckImpl(tldService)
    private val passkeys = createPasskeyLibrary(serviceCount)
    private val twoFa = createTwoFaLibrary(serviceCount)
    private val breaches = createBreachLibrary()
    private val gpgStates = createGpgStates(corpus)

    private val clients: Map<DWatchtowerAlertType, WatchtowerClientTyped> = mapOf(
        DWatchtowerAlertType.WEAK_PASSWORD to WatchtowerPasswordStrength(),
        DWatchtowerAlertType.WEAK_SSH_KEY to WatchtowerSshKeyStrength(
            cipherSshKeyWeakCheck = CipherSshKeyWeakCheckImpl(BenchmarkKeyPairGenerator),
        ),
        DWatchtowerAlertType.GPG_KEY_UNUSABLE to WatchtowerGpgKeyUnusable(
            policy = GpgWatchtowerPolicy(BenchmarkGpgParser),
        ),
        DWatchtowerAlertType.WEAK_GPG_KEY to WatchtowerWeakGpgKey(
            policy = GpgWatchtowerPolicy(BenchmarkGpgParser),
        ),
        DWatchtowerAlertType.GPG_KEY_PUBLISHING to WatchtowerGpgKeyPublishing(
            keyserverStateRepository = BenchmarkGpgStateRepository(gpgStates),
        ),
        DWatchtowerAlertType.PWNED_PASSWORD to WatchtowerPasswordPwned(
            checkPasswordSetLeak = BenchmarkPasswordSetLeak,
            getBreachesLatestDate = BenchmarkBreachesLatestDate,
            getCheckPwnedPasswords = BenchmarkCheckPwnedPasswords,
        ),
        DWatchtowerAlertType.PWNED_WEBSITE to WatchtowerWebsitePwned(
            getAutofillDefaultMatchDetection = BenchmarkDefaultMatchDetection,
            cipherBreachCheck = CipherBreachCheckImpl(cipherUrlCheck),
            equivalentDomainsBuilderFactory = equivalentDomains.factory,
            getBreaches = BenchmarkGetBreaches(breaches),
            getBreachesLatestDate = BenchmarkBreachesLatestDate,
            getEquivalentDomains = equivalentDomains.get,
            getCheckPwnedServices = BenchmarkCheckPwnedServices,
        ),
        DWatchtowerAlertType.TWO_FA_WEBSITE to WatchtowerInactiveTfa(
            tldService = tldService,
            tfaService = BenchmarkTwoFaService(twoFa),
            getTwoFa = BenchmarkGetTwoFa(twoFa),
            equivalentDomainsBuilderFactory = equivalentDomains.factory,
            getEquivalentDomains = equivalentDomains.get,
            getCheckTwoFA = BenchmarkCheckTwoFa,
        ),
        DWatchtowerAlertType.PASSKEY_WEBSITE to WatchtowerInactivePasskey(
            tldService = tldService,
            passKeyService = BenchmarkPasskeyService(passkeys),
            getPasskeys = BenchmarkGetPasskeys(passkeys),
            equivalentDomainsBuilderFactory = equivalentDomains.factory,
            getEquivalentDomains = equivalentDomains.get,
            getCheckPasskeys = BenchmarkCheckPasskeys,
        ),
        DWatchtowerAlertType.UNSECURE_WEBSITE to WatchtowerUnsecureWebsite(
            cipherUnsecureUrlCheck = CipherUnsecureUrlCheckImpl(),
        ),
        DWatchtowerAlertType.DUPLICATE_URIS to WatchtowerDuplicateUris(
            getAutofillDefaultMatchDetection = BenchmarkDefaultMatchDetection,
            cipherUrlDuplicateCheck = CipherUrlDuplicateCheckImpl(cipherUrlCheck),
            equivalentDomainsBuilderFactory = equivalentDomains.factory,
        ),
        DWatchtowerAlertType.BROAD_URIS to WatchtowerBroadUris(
            getAutofillDefaultMatchDetection = BenchmarkDefaultMatchDetection,
            cipherUrlBroadCheck = CipherUrlBroadCheckImpl(tldService),
            equivalentDomainsBuilderFactory = equivalentDomains.factory,
        ),
        DWatchtowerAlertType.INCOMPLETE to WatchtowerIncomplete(
            cipherIncompleteCheck = CipherIncompleteCheckImpl(),
        ),
        DWatchtowerAlertType.EXPIRING to WatchtowerExpiring(
            cipherExpiringCheck = CipherExpiringCheckImpl(),
        ),
    )

    private val duplicateItemsCheck = CipherDuplicatesCheckImpl(
        cryptoGenerator = NativeCryptoGenerator(),
        base64Service = Base64ServiceImpl(),
        similarityService = SimilarityServiceImpl(),
        logRepository = BenchmarkLogRepository,
        includeDebugSummary = false,
    )
    private val emptyDirectDI = DI {}.direct

    fun cases(): List<WatchtowerBenchmarkCase> = listOf(
        clientCase("password-strength", DWatchtowerAlertType.WEAK_PASSWORD),
        clientCase("ssh-key-strength", DWatchtowerAlertType.WEAK_SSH_KEY),
        clientCase("gpg-key-unusable", DWatchtowerAlertType.GPG_KEY_UNUSABLE),
        clientCase("gpg-key-strength", DWatchtowerAlertType.WEAK_GPG_KEY),
        clientCase("gpg-key-publishing", DWatchtowerAlertType.GPG_KEY_PUBLISHING),
        clientCase("password-pwned", DWatchtowerAlertType.PWNED_PASSWORD),
        clientCase("website-pwned", DWatchtowerAlertType.PWNED_WEBSITE),
        case(
            name = "reused-password",
            alertType = DWatchtowerAlertType.REUSED_PASSWORD,
            ciphers = corpus,
            run = {
                DFilter.ByPasswordDuplicates.count(emptyDirectDI, corpus)
            },
            observe = { count ->
                WatchtowerBenchmarkObservation(
                    resultCount = corpus.size,
                    threatCount = count,
                    checksum = count.toLong(),
                )
            },
        ),
        clientCase("inactive-2fa", DWatchtowerAlertType.TWO_FA_WEBSITE),
        clientCase("inactive-passkey", DWatchtowerAlertType.PASSKEY_WEBSITE),
        clientCase("unsecure-website", DWatchtowerAlertType.UNSECURE_WEBSITE),
        case(
            name = "duplicate-items",
            alertType = DWatchtowerAlertType.DUPLICATE,
            ciphers = duplicateCorpus,
            run = {
                duplicateItemsCheck(duplicateCorpus, CipherDuplicatesCheck.Sensitivity.NORMAL)
            },
            observe = ::observeDuplicateGroups,
        ),
        clientCase("duplicate-uris", DWatchtowerAlertType.DUPLICATE_URIS),
        clientCase("broad-uris", DWatchtowerAlertType.BROAD_URIS),
        clientCase("incomplete", DWatchtowerAlertType.INCOMPLETE),
        clientCase("expiring", DWatchtowerAlertType.EXPIRING),
    )

    private fun clientCase(
        name: String,
        alertType: DWatchtowerAlertType,
    ): WatchtowerBenchmarkCase {
        val client = requireNotNull(clients[alertType])
        return case(
            name = name,
            alertType = alertType,
            ciphers = corpus,
            run = {
                client.process(corpus)
            },
            observe = ::observeClientResults,
        )
    }

    private fun observeClientResults(
        results: List<WatchtowerClientResult>,
    ): WatchtowerBenchmarkObservation {
        var threatCount = 0
        var checksum = 1L
        results.forEach { result ->
            if (result.threat) {
                threatCount += 1
            }
            checksum = 31L * checksum + result.cipher.id.hashCode()
            checksum = 31L * checksum + (result.value?.hashCode() ?: 0)
        }
        return WatchtowerBenchmarkObservation(
            resultCount = results.size,
            threatCount = threatCount,
            checksum = checksum,
        )
    }

    private fun observeDuplicateGroups(
        groups: List<DSecretDuplicateGroup>,
    ): WatchtowerBenchmarkObservation {
        val cipherIds = groups
            .asSequence()
            .flatMap { it.ciphers.asSequence() }
            .map { it.id }
            .toSet()
        return WatchtowerBenchmarkObservation(
            resultCount = groups.size,
            threatCount = cipherIds.size,
            checksum = groups.fold(1L) { checksum, group ->
                31L * checksum + group.id.hashCode()
            },
        )
    }

    private fun <T : Any> case(
        name: String,
        alertType: DWatchtowerAlertType,
        ciphers: List<DSecret>,
        run: suspend () -> T,
        observe: (T) -> WatchtowerBenchmarkObservation,
    ) = WatchtowerBenchmarkCase(
        name = name,
        alertType = alertType.name,
        cipherCount = ciphers.size,
        run = run,
        observe = { result ->
            @Suppress("UNCHECKED_CAST")
            observe(result as T)
        },
    )

    private fun createGpgStates(ciphers: List<DSecret>): List<DGpgKeyserverState> = ciphers
        .asSequence()
        .filter { it.type == DSecret.Type.GpgKey }
        .mapIndexed { index, cipher ->
            DGpgKeyserverState(
                fingerprint = BENCHMARK_GPG_FINGERPRINT,
                cipherId = cipher.id,
                verificationStatus = when (index % 4) {
                    0 -> GpgKeyserverVerificationStatus.VERIFIED
                    1 -> GpgKeyserverVerificationStatus.FOUND_UNVERIFIED
                    2 -> GpgKeyserverVerificationStatus.NOT_FOUND
                    else -> GpgKeyserverVerificationStatus.REVOKED
                },
                lastCheckedAt = FIXED_INSTANT,
            )
        }
        .toList()

    companion object {
        const val DEFAULT_CORPUS_SIZE = 10_000
        const val DUPLICATE_CORPUS_SIZE = 500
        const val DEFAULT_SERVICE_COUNT = 256
    }
}

private fun createSecret(
    index: Int,
    serviceCount: Int,
): DSecret {
    val ignoredAlerts = if (index % 113 == 0) {
        DWatchtowerAlertType.entries.associateWith { FIXED_INSTANT }
    } else {
        emptyMap()
    }
    val kind = index % 20
    val serviceIndex = index % serviceCount
    val type = when (kind) {
        0 -> DSecret.Type.Card
        1 -> DSecret.Type.SshKey
        2 -> DSecret.Type.GpgKey
        3 -> DSecret.Type.SecureNote
        else -> DSecret.Type.Login
    }
    val passwordIndex = if (index == REUSED_PASSWORD_CIPHER_INDEX) {
        REUSED_PASSWORD_SOURCE_INDEX
    } else {
        index % PASSWORD_COUNT
    }
    val login = if (type == DSecret.Type.Login) {
        DSecret.Login(
            username = if (index % 37 == 0) null else "user-$index@benchmark.test",
            password = "password-$passwordIndex",
            passwordStrength = PasswordStrength(
                crackTimeSeconds = if (index % 4 == 0) 10L else 1_000_000_000L,
                version = 1L,
            ),
            passwordRevisionDate = FIXED_INSTANT,
        )
    } else {
        null
    }
    val uris = if (type == DSecret.Type.Login) {
        listOf(
            DSecret.Uri(
                uri = "https://service-$serviceIndex.benchmark.test/login/$index",
                match = DSecret.Uri.MatchType.Host,
            ),
            DSecret.Uri(
                uri = "https://account-$index.benchmark.test",
                match = DSecret.Uri.MatchType.Domain,
            ),
            DSecret.Uri(
                uri = if (index % 3 == 0) {
                    "https://service-$serviceIndex.benchmark.test/account/$index"
                } else {
                    "http://legacy-$serviceIndex.benchmark.test/login/$index"
                },
                match = DSecret.Uri.MatchType.Domain,
            ),
        )
    } else {
        emptyList()
    }
    val gpgKey = if (type == DSecret.Type.GpgKey) {
        DSecret.GpgKey(
            privateKeyArmored = "benchmark-private-key",
            publicKeyArmored = if (index % 40 == 2) {
                "benchmark-public-key-revoked"
            } else {
                "benchmark-public-key-weak"
            },
            fingerprint = BENCHMARK_GPG_FINGERPRINT,
            metadata = GpgAgentKeyMetadata(
                keys = listOf(
                    GpgAgentKeyMetadataKey(
                        keygrip = "benchmark-keygrip",
                        fingerprint = BENCHMARK_GPG_FINGERPRINT,
                        algorithm = "RSA",
                        capabilities = setOf("sign", "decrypt"),
                    ),
                ),
            ),
        )
    } else {
        null
    }
    return DSecret(
        id = "benchmark-cipher-$index",
        accountId = "benchmark-account-${index % 4}",
        folderId = null,
        organizationId = null,
        collectionIds = emptySet(),
        revisionDate = FIXED_INSTANT,
        createdDate = FIXED_INSTANT,
        archivedDate = null,
        deletedDate = null,
        service = BitwardenService(),
        name = if (index % 71 == 0) "Login" else "Benchmark item ${index % 250}",
        notes = if (type == DSecret.Type.SecureNote && index % 2 == 0) "" else "benchmark note",
        favorite = false,
        reprompt = false,
        synced = true,
        ignoredAlerts = ignoredAlerts,
        uris = uris,
        type = type,
        login = login,
        card = if (type == DSecret.Type.Card) {
            DSecret.Card(number = "4111111111111111", expMonth = "01", expYear = "2020")
        } else {
            null
        },
        sshKey = if (type == DSecret.Type.SshKey) {
            DSecret.SshKey(
                privateKey = if (index % 40 == 1) "weak-private-key" else "strong-private-key",
                publicKey = "ssh-rsa benchmark",
                fingerprint = "SHA256:benchmark-$index",
            )
        } else {
            null
        },
        gpgKey = gpgKey,
    )
}

private fun createPasskeyLibrary(serviceCount: Int): List<PassKeyServiceInfo> = List(serviceCount) { index ->
    PassKeyServiceInfo(
        id = "passkey-$index",
        name = "Passkey service $index",
        domain = "service-$index.benchmark.test",
        domains = setOf("service-$index.benchmark.test"),
        features = setOf("signin"),
    )
}

private fun createTwoFaLibrary(serviceCount: Int): List<TwoFaServiceInfo> = List(serviceCount) { index ->
    TwoFaServiceInfo(
        name = "2FA service $index",
        domain = "service-$index.benchmark.test",
        domains = setOf("service-$index.benchmark.test"),
        tfa = setOf("totp"),
    )
}

private fun createBreachLibrary(): HibpBreachGroup = HibpBreachGroup(
    breaches = List(32) { index ->
        HibpBreachResponse(
            name = "BenchmarkBreach$index",
            domain = if (index == 31) "benchmark.test" else "unmatched-$index.example.org",
            breachDate = LocalDate(2025, 1, 1),
            dataClasses = listOf("Passwords"),
        )
    },
)

private class BenchmarkEquivalentDomains {
    val get = object : GetEquivalentDomains {
        override fun invoke(): Flow<List<DEquivalentDomains>> = flowOf(emptyList())
    }
    val factory = com.artemchep.keyguard.common.model.EquivalentDomainsBuilderFactory(
        logRepository = BenchmarkLogRepository,
        getEquivalentDomains = get,
    )
}

internal object BenchmarkLogRepository : LogRepository {
    override fun post(tag: String, message: String, level: LogLevel) = Unit

    override suspend fun add(tag: String, message: String, level: LogLevel) = Unit
}

internal object BenchmarkTldTextService : TextService {
    private val publicSuffixList = "com\nnet\norg\ntest\nuk\nco.uk\n"
        .encodeToByteArray()

    override suspend fun readFromResources(fileResource: FileResource): Source {
        require(fileResource == FileResource.publicSuffixList)
        return publicSuffixList.toSource()
    }

    override fun readFromFile(uri: String): Source = error("Not used by the benchmark")
}

private object BenchmarkDefaultMatchDetection : GetAutofillDefaultMatchDetection {
    override fun invoke(): Flow<DSecret.Uri.MatchType> = flowOf(DSecret.Uri.MatchType.Domain)
}

private object BenchmarkPasswordSetLeak : CheckPasswordSetLeak {
    override fun invoke(request: CheckPasswordSetLeakRequest): IO<Map<String, PasswordPwnage?>> = io(
        request.passwords.associateWith { password ->
            val index = password.substringAfterLast('-').toIntOrNull() ?: 0
            PasswordPwnage(if (index % 5 == 0) 1 else 0)
        },
    )
}

private object BenchmarkBreachesLatestDate : GetBreachesLatestDate {
    override fun invoke(): Flow<LocalDate?> = flowOf(LocalDate(2025, 1, 1))
}

private object BenchmarkCheckPwnedPasswords : GetCheckPwnedPasswords {
    override fun invoke(): Flow<Boolean> = flowOf(true)
}

private object BenchmarkCheckPwnedServices : GetCheckPwnedServices {
    override fun invoke(): Flow<Boolean> = flowOf(true)
}

private object BenchmarkCheckPasskeys : GetCheckPasskeys {
    override fun invoke(): Flow<Boolean> = flowOf(true)
}

private object BenchmarkCheckTwoFa : GetCheckTwoFA {
    override fun invoke(): Flow<Boolean> = flowOf(true)
}

private class BenchmarkGetBreaches(
    private val breaches: HibpBreachGroup,
) : GetBreaches {
    override fun invoke(force: Boolean): IO<HibpBreachGroup> = io(breaches)
}

private class BenchmarkGetPasskeys(
    private val passkeys: List<PassKeyServiceInfo>,
) : GetPasskeys {
    override fun invoke(): IO<List<PassKeyServiceInfo>> = io(passkeys)
}

private class BenchmarkPasskeyService(
    private val passkeys: List<PassKeyServiceInfo>,
) : PassKeyService {
    override val version: String = "benchmark"

    override fun get(): IO<List<PassKeyServiceInfo>> = io(passkeys)
}

private class BenchmarkGetTwoFa(
    private val twoFa: List<TwoFaServiceInfo>,
) : GetTwoFa {
    override fun invoke(): IO<List<TwoFaServiceInfo>> = io(twoFa)
}

private class BenchmarkTwoFaService(
    private val twoFa: List<TwoFaServiceInfo>,
) : TwoFaService {
    override val version: String = "benchmark"

    override fun get(): IO<List<TwoFaServiceInfo>> = io(twoFa)
}

private object BenchmarkKeyPairGenerator : KeyPairGenerator {
    override fun rsa(length: KeyPairGenerator.RsaLength): KeyParameterRawZero = unsupported()

    override fun ed25519(): KeyParameterRawZero = unsupported()

    override fun parse(privateKey: String, publicKey: String): KeyParameterRawZero = unsupported()

    override fun populate(keyPair: KeyParameterRawZero): KeyPair = unsupported()

    override fun getPrivateKeyLengthOrNull(keyPair: KeyParameterRawZero): Int? = null

    override fun getPrivateKeyLengthOrNull(privateKey: String): Int? =
        if (privateKey.startsWith("weak")) 1024 else 4096

    private fun <T> unsupported(): T = error("Not used by the Watchtower benchmark")
}

private object BenchmarkGpgParser : GpgPublicKeyParser {
    override fun parse(armored: String): GpgPublicKeyParseResult = GpgPublicKeyParseResult.Success(
        keys = listOf(
            GpgPublicKeyInfo(
                fingerprint = BENCHMARK_GPG_FINGERPRINT,
                keyId = BENCHMARK_GPG_FINGERPRINT.takeLast(16),
                algorithm = "RSA",
                bitStrength = 1024,
                userIds = listOf("Benchmark <benchmark@example.com>"),
                emails = listOf("benchmark@example.com"),
                createdAt = FIXED_INSTANT,
                expiresAt = null,
                revoked = armored.endsWith("revoked"),
                canSign = true,
                canEncrypt = true,
                publicKeyArmored = armored,
                subKeys = listOf(
                    GpgPublicSubKeyInfo(
                        fingerprint = BENCHMARK_GPG_FINGERPRINT,
                        keyId = BENCHMARK_GPG_FINGERPRINT.takeLast(16),
                        algorithm = "RSA",
                        bitStrength = 1024,
                        canSign = false,
                        canEncrypt = true,
                        revoked = false,
                        createdAt = FIXED_INSTANT,
                        expiresAt = null,
                    ),
                ),
            ),
        ),
    )
}

private class BenchmarkGpgStateRepository(
    private val states: List<DGpgKeyserverState>,
) : GpgKeyserverStateRepository {
    override fun getAll(): Flow<List<DGpgKeyserverState>> = flowOf(states)

    override fun getByFingerprint(fingerprint: String): Flow<DGpgKeyserverState?> = flowOf(
        states.firstOrNull {
            it.fingerprint.normalizeGpgFingerprint() == fingerprint.normalizeGpgFingerprint()
        },
    )

    override fun getByCipherId(cipherId: String): Flow<List<DGpgKeyserverState>> = flowOf(
        states.filter { it.cipherId == cipherId },
    )

    override fun put(model: DGpgKeyserverState): IO<Unit> = io(Unit)

    override fun removeByFingerprint(fingerprint: String): IO<Unit> = io(Unit)

    override fun removeAll(): IO<Unit> = io(Unit)
}

private val FIXED_INSTANT = Instant.parse("2024-01-01T00:00:00Z")
private const val PASSWORD_COUNT = 2_048
private const val REUSED_PASSWORD_SOURCE_INDEX = 4
private const val REUSED_PASSWORD_CIPHER_INDEX = 57
private const val BENCHMARK_GPG_FINGERPRINT = "D0BBCFBB250D3BB0658E5384F83D947D29EFECF7"
