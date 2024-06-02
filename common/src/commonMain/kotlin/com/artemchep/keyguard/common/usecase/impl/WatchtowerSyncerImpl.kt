package com.artemchep.keyguard.common.usecase.impl

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import arrow.core.getOrElse
import com.artemchep.keyguard.build.FileHashes
import com.artemchep.keyguard.common.io.attempt
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.io.handleError
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.io.map
import com.artemchep.keyguard.common.io.measure
import com.artemchep.keyguard.common.model.CheckPasswordSetLeakRequest
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.model.DWatchtowerAlertType
import com.artemchep.keyguard.common.model.MasterSession
import com.artemchep.keyguard.common.model.PasswordStrength
import com.artemchep.keyguard.common.model.ignores
import com.artemchep.keyguard.common.service.logging.LogRepository
import com.artemchep.keyguard.common.service.passkey.PassKeyServiceInfo
import com.artemchep.keyguard.common.service.twofa.TwoFaServiceInfo
import com.artemchep.keyguard.common.usecase.CheckPasswordSetLeak
import com.artemchep.keyguard.common.usecase.CipherBreachCheck
import com.artemchep.keyguard.common.usecase.CipherExpiringCheck
import com.artemchep.keyguard.common.usecase.CipherIncompleteCheck
import com.artemchep.keyguard.common.usecase.CipherUnsecureUrlCheck
import com.artemchep.keyguard.common.usecase.CipherUrlDuplicateCheck
import com.artemchep.keyguard.common.usecase.GetBreaches
import com.artemchep.keyguard.common.usecase.GetCheckPasskeys
import com.artemchep.keyguard.common.usecase.GetCheckPwnedPasswords
import com.artemchep.keyguard.common.usecase.GetCheckPwnedServices
import com.artemchep.keyguard.common.usecase.GetCheckTwoFA
import com.artemchep.keyguard.common.usecase.GetCiphers
import com.artemchep.keyguard.common.usecase.GetPasskeys
import com.artemchep.keyguard.common.usecase.GetTwoFa
import com.artemchep.keyguard.common.usecase.GetVaultSession
import com.artemchep.keyguard.common.usecase.WatchtowerSyncer
import com.artemchep.keyguard.common.util.int
import com.artemchep.keyguard.core.store.DatabaseDispatcher
import com.artemchep.keyguard.core.store.DatabaseManager
import com.artemchep.keyguard.data.Database
import com.artemchep.keyguard.feature.crashlytics.crashlyticsTap
import com.artemchep.keyguard.platform.lifecycle.LeLifecycleState
import com.artemchep.keyguard.platform.lifecycle.onState
import com.artemchep.keyguard.provider.bitwarden.entity.HibpBreachGroup
import io.ktor.http.*
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import org.kodein.di.DirectDI
import org.kodein.di.allInstances
import org.kodein.di.direct
import org.kodein.di.instance

class WatchtowerSyncerImpl(
    private val getVaultSession: GetVaultSession,
) : WatchtowerSyncer {
    constructor(directDI: DirectDI) : this(
        getVaultSession = directDI.instance(),
    )

    override fun start(
        scope: CoroutineScope,
        flow: Flow<LeLifecycleState>,
    ) {
        flow
            .onState {
                getVaultSession()
                    .map { session ->
                        val key = session as? MasterSession.Key
                        key?.di?.direct?.let(::WatchtowerClient)
                    }
                    .collectLatest { client ->
                        if (client != null) {
                            coroutineScope {
                                client.launch(this)
                            }
                        }
                    }
            }
            .launchIn(scope)
    }
}

private class WatchtowerClient(
    private val getCiphers: GetCiphers,
    private val databaseManager: DatabaseManager,
    private val logRepository: LogRepository,
    private val list: List<WatchtowerClientTyped>,
    private val dispatcher: CoroutineDispatcher,
) {
    companion object {
        private const val TAG = "WatchtowerAlertClient"
    }

    constructor(directDI: DirectDI) : this(
        getCiphers = directDI.instance(),
        databaseManager = directDI.instance(),
        logRepository = directDI.instance(),
        list = directDI.allInstances(),
        dispatcher = directDI.instance(tag = DatabaseDispatcher),
    )

    fun launch(scope: CoroutineScope) = scope.launch {
        val db = databaseManager.get()
            .bind()
        list.forEach { processor ->
            val type = processor.type

            val versionFlow = processor.version()
            val requestsFlow = versionFlow
                .distinctUntilChanged()
                .flatMapLatest { version ->
                    getPendingCiphersFlow(
                        db = db,
                        type = type,
                        version = version,
                    )
                        .map { ciphers -> version to ciphers }
                }
            requestsFlow
                .debounce(1000L)
                .onEach { (version, ciphers) ->
                    val message = "Processing watchtower alert [$type/$version]: " +
                            ciphers.joinToString { it.id }
                    logRepository.add(TAG, message)

                    val now = Clock.System.now()
                    val results = processor.process(ciphers)
                    db.transaction {
                        results.forEach { r ->
                            db.watchtowerThreatQueries.upsert(
                                value = r.value,
                                threat = r.threat && !r.cipher.deleted,
                                cipherId = r.cipher.id,
                                type = type,
                                reportedAt = now,
                                version = version,
                            )
                        }
                    }
                }
                .flowOn(Dispatchers.Default)
                .launchIn(this)
        }
    }

    private fun getPendingCiphersFlow(
        db: Database,
        type: Long,
        version: String,
    ): Flow<List<DSecret>> {
        val cipherIdsFlow = db.watchtowerThreatQueries
            .getPendingCipherIds(
                type = type,
                version = version,
            )
            .asFlow()
            .mapToList(dispatcher)
            .map { ids ->
                ids.toSet()
            }
        return getCiphers()
            .combine(cipherIdsFlow) { ciphers, ids ->
                ciphers
                    .filter { it.id in ids }
            }
    }
}

data class WatchtowerClientResult(
    val value: String? = null,
    val threat: Boolean,
    val cipher: DSecret,
)

interface WatchtowerClientTyped {
    val type: Long

    fun version(): Flow<String>

    suspend fun process(
        ciphers: List<DSecret>,
    ): List<WatchtowerClientResult>
}

class WatchtowerPasswordStrength(
) : WatchtowerClientTyped {
    override val type: Long
        get() = DWatchtowerAlertType.WEAK_PASSWORD.value

    constructor(directDI: DirectDI) : this(
    )

    override fun version() = flowOf("1")

    override suspend fun process(
        ciphers: List<DSecret>,
    ): List<WatchtowerClientResult> {
        return ciphers
            .map { cipher ->
                val value = cipher.login?.password
                val threat = cipher.login?.passwordStrength?.score == PasswordStrength.Score.Weak
                WatchtowerClientResult(
                    value = value,
                    threat = threat,
                    cipher = cipher,
                )
            }
    }
}

class WatchtowerPasswordPwned(
    private val checkPasswordSetLeak: CheckPasswordSetLeak,
    private val getCheckPwnedPasswords: GetCheckPwnedPasswords,
) : WatchtowerClientTyped {
    override val type: Long
        get() = DWatchtowerAlertType.PWNED_PASSWORD.value

    constructor(directDI: DirectDI) : this(
        checkPasswordSetLeak = directDI.instance(),
        getCheckPwnedPasswords = directDI.instance(),
    )

    override fun version() = combineJoinToVersion(
        getDatabaseVersionFlow(),
        getCheckPwnedPasswords()
            .map {
                it.int.toString()
            },
    )

    private fun getDatabaseVersionFlow() = flow {
        // Refresh weekly
        val seconds = Clock.System.now().epochSeconds
        val weeks = seconds / 604800L
        emit(weeks.toString())
    }

    override suspend fun process(
        ciphers: List<DSecret>,
    ): List<WatchtowerClientResult> {
        val set = buildPwnedPasswordsState(
            ciphers = ciphers,
        )
            .asSequence()
            .mapNotNull { entry ->
                entry.key.takeIf { entry.value > 1 }
            }
            .toSet()
        return ciphers
            .map { cipher ->
                val value = cipher.login?.password
                val threat = cipher.login
                    ?.password
                    ?.let { password ->
                        password in set &&
                                !shouldIgnore(cipher)
                    } == true
                WatchtowerClientResult(
                    value = value,
                    threat = threat,
                    cipher = cipher,
                )
            }
    }

    private suspend fun buildPwnedPasswordsState(
        ciphers: List<DSecret>,
    ): Map<String, Int> = ioEffect {
        val passwords = ciphers
            .asSequence()
            .mapNotNull { cipher ->
                val shouldIgnore = shouldIgnore(cipher)
                if (shouldIgnore) {
                    return@mapNotNull null
                }

                cipher.login?.password
            }
            .toSet()
        return@ioEffect checkPasswordSetLeak(CheckPasswordSetLeakRequest(passwords))
            .map {
                it.mapValues { it.value?.occurrences ?: 0 }
            }
            .attempt()
            .bind()
            .getOrElse { emptyMap() }
    }
        .measure { duration, mutableMap ->
            println("pwned calculated in $duration")
        }
        .bind()

    private fun shouldIgnore(
        cipher: DSecret,
    ) = cipher.ignores(DWatchtowerAlertType.PWNED_PASSWORD)
}

class WatchtowerWebsitePwned(
    private val cipherBreachCheck: CipherBreachCheck,
    private val getBreaches: GetBreaches,
    private val getCheckPwnedServices: GetCheckPwnedServices,
) : WatchtowerClientTyped {
    override val type: Long
        get() = DWatchtowerAlertType.PWNED_WEBSITE.value

    constructor(directDI: DirectDI) : this(
        cipherBreachCheck = directDI.instance(),
        getBreaches = directDI.instance(),
        getCheckPwnedServices = directDI.instance(),
    )

    override fun version() = combineJoinToVersion(
        getDatabaseVersionFlow(),
        getCheckPwnedServices()
            .map {
                it.int.toString()
            },
    )

    private fun getDatabaseVersionFlow() = flow {
        // Refresh weekly
        val seconds = Clock.System.now().epochSeconds
        val weeks = seconds / 604800L
        emit(weeks.toString())
    }

    override suspend fun process(
        ciphers: List<DSecret>,
    ): List<WatchtowerClientResult> {
        val set = buildDuplicatesState(
            ciphers = ciphers,
        )
        return ciphers
            .map { cipher ->
                val value = sequence<String> {
                    val revisionDate = cipher.login?.passwordRevisionDate
                    if (revisionDate != null) {
                        yield(revisionDate.toString())
                    }

                    cipher.uris.forEach {
                        yield(it.uri)
                    }
                }.joinToString()
                val threat = cipher.id in set
                WatchtowerClientResult(
                    value = value,
                    threat = threat,
                    cipher = cipher,
                )
            }
    }

    private suspend fun buildDuplicatesState(
        ciphers: List<DSecret>,
    ): Set<String> = ioEffect {
        val breaches = getBreaches()
            .handleError {
                HibpBreachGroup(emptyList())
            }
            .bind()

        ciphers
            .filter { cipher ->
                val shouldIgnore = shouldIgnore(cipher)
                if (shouldIgnore) {
                    return@filter false
                }

                cipherBreachCheck(cipher, breaches)
                    .handleError { false }
                    .bind()
            }
            .map { cipher -> cipher.id }
            .toSet()
    }
        .measure { duration, mutableMap ->
            println("pwned accounts calculated in $duration")
        }
        .bind()

    private fun shouldIgnore(
        cipher: DSecret,
    ) = cipher.ignores(DWatchtowerAlertType.PWNED_WEBSITE)
}

class WatchtowerInactivePasskey(
    private val getPasskeys: GetPasskeys,
    private val getCheckPasskeys: GetCheckPasskeys,
) : WatchtowerClientTyped {
    override val type: Long
        get() = DWatchtowerAlertType.PASSKEY_WEBSITE.value

    constructor(directDI: DirectDI) : this(
        getPasskeys = directDI.instance(),
        getCheckPasskeys = directDI.instance(),
    )

    override fun version() = combineJoinToVersion(
        getDatabaseVersionFlow(),
        getCheckPasskeys()
            .map {
                it.int.toString()
            },
    )

    private fun getDatabaseVersionFlow() = flowOf(FileHashes.passkeys)

    override suspend fun process(
        ciphers: List<DSecret>,
    ): List<WatchtowerClientResult> {
        val passkeyLibrary = getPasskeys()
            .crashlyticsTap()
            .attempt()
            .bind()
            .getOrNull()
            .orEmpty()

        return ciphers
            .map { cipher ->
                val value = hasAlert(
                    cipher = cipher,
                    passkeyLibrary = passkeyLibrary,
                )
                val threat = value != null
                WatchtowerClientResult(
                    value = value,
                    threat = threat,
                    cipher = cipher,
                )
            }
    }

    private fun hasAlert(
        cipher: DSecret,
        passkeyLibrary: List<PassKeyServiceInfo>,
    ): String? {
        val shouldIgnore = shouldIgnore(cipher)
        if (shouldIgnore) {
            return null
        }

        if (!cipher.login?.fido2Credentials.isNullOrEmpty()) {
            return null
        }

        val group = match(cipher, passkeyLibrary)
            .map { info -> info.domain }
            .toSortedSet()
            .joinToString()
        return group.takeIf { it.isNotEmpty() }
    }

    private fun match(
        cipher: DSecret,
        passkeyLibrary: List<PassKeyServiceInfo>,
    ) = cipher
        .uris
        .asSequence()
        .mapNotNull { uri ->
            val host = parseHost(uri)
                ?: return@mapNotNull null
            val result = passkeyLibrary
                .firstOrNull {
                    host in it.domains || it.domains
                        .any { domain ->
                            val endsWith = host.endsWith(domain)
                            if (!endsWith) {
                                return@any false
                            }

                            val i = host.length - domain.length
                            if (i > 0) {
                                val leadingChar = host[i - 1]
                                leadingChar == '.'
                            } else {
                                false
                            }
                        }
                }
            result?.takeIf { "signin" in it.features }
        }

    private fun shouldIgnore(
        cipher: DSecret,
    ) = cipher.ignores(DWatchtowerAlertType.PASSKEY_WEBSITE)
}

class WatchtowerIncomplete(
    private val cipherIncompleteCheck: CipherIncompleteCheck,
) : WatchtowerClientTyped {
    override val type: Long
        get() = DWatchtowerAlertType.INCOMPLETE.value

    constructor(directDI: DirectDI) : this(
        cipherIncompleteCheck = directDI.instance(),
    )

    override fun version() = flowOf("1")

    override suspend fun process(
        ciphers: List<DSecret>,
    ): List<WatchtowerClientResult> {
        return ciphers
            .map { cipher ->
                val value = hasAlert(
                    cipher = cipher,
                )
                val threat = value != null
                WatchtowerClientResult(
                    value = value,
                    threat = threat,
                    cipher = cipher,
                )
            }
    }

    private fun hasAlert(
        cipher: DSecret,
    ): String? {
        val shouldIgnore = shouldIgnore(cipher)
        if (shouldIgnore) {
            return null
        }

        val isIncomplete = cipherIncompleteCheck(cipher)
        if (!isIncomplete) {
            return null
        }

        val group = listOfNotNull(
            cipher.login?.username,
            cipher.login?.password,
        ).joinToString(separator = "|")
        return group.takeIf { it.isNotEmpty() }
            ?: "empty"
    }

    private fun shouldIgnore(
        cipher: DSecret,
    ) = cipher.ignores(DWatchtowerAlertType.INCOMPLETE)
}

class WatchtowerExpiring(
    private val cipherExpiringCheck: CipherExpiringCheck,
) : WatchtowerClientTyped {
    override val type: Long
        get() = DWatchtowerAlertType.EXPIRING.value

    constructor(directDI: DirectDI) : this(
        cipherExpiringCheck = directDI.instance(),
    )

    override fun version() = flow {
        // Refresh daily
        val seconds = Clock.System.now().epochSeconds
        val days = seconds / 86400L
        emit(days.toString())
    }

    override suspend fun process(
        ciphers: List<DSecret>,
    ): List<WatchtowerClientResult> {
        val now = Clock.System.now()
        return ciphers
            .map { cipher ->
                val value = hasAlert(
                    cipher = cipher,
                    now = now,
                )
                val threat = value != null
                WatchtowerClientResult(
                    value = value,
                    threat = threat,
                    cipher = cipher,
                )
            }
    }

    private fun hasAlert(
        cipher: DSecret,
        now: Instant,
    ): String? {
        val shouldIgnore = shouldIgnore(cipher)
        if (shouldIgnore) {
            return null
        }

        val isExpiring = cipherExpiringCheck(cipher, now) != null
        if (!isExpiring) {
            return null
        }

        val group = listOfNotNull(
            cipher.login?.username,
            cipher.login?.password,
        ).joinToString(separator = "|")
        return group.takeIf { it.isNotEmpty() }
            ?: "empty"
    }

    private fun shouldIgnore(
        cipher: DSecret,
    ) = cipher.ignores(DWatchtowerAlertType.EXPIRING)
}

class WatchtowerUnsecureWebsite(
    private val cipherUnsecureUrlCheck: CipherUnsecureUrlCheck,
) : WatchtowerClientTyped {
    override val type: Long
        get() = DWatchtowerAlertType.UNSECURE_WEBSITE.value

    constructor(directDI: DirectDI) : this(
        cipherUnsecureUrlCheck = directDI.instance(),
    )

    override fun version() = flowOf(FileHashes.public_suffix_list)

    override suspend fun process(
        ciphers: List<DSecret>,
    ): List<WatchtowerClientResult> {
        return ciphers
            .map { cipher ->
                val value = hasAlert(
                    cipher = cipher,
                )
                val threat = value != null
                WatchtowerClientResult(
                    value = value,
                    threat = threat,
                    cipher = cipher,
                )
            }
    }

    private fun hasAlert(
        cipher: DSecret,
    ): String? {
        val shouldIgnore = shouldIgnore(cipher)
        if (shouldIgnore) {
            return null
        }

        val value = cipher
            .uris
            .filter { uri -> cipherUnsecureUrlCheck(uri.uri) }
            .joinToString()
        return value.takeIf { it.isNotEmpty() }
    }

    private fun shouldIgnore(
        cipher: DSecret,
    ) = cipher.ignores(DWatchtowerAlertType.UNSECURE_WEBSITE)
}

class WatchtowerInactiveTfa(
    private val tfaService: GetTwoFa,
    private val getCheckTwoFA: GetCheckTwoFA,
) : WatchtowerClientTyped {
    override val type: Long
        get() = DWatchtowerAlertType.TWO_FA_WEBSITE.value

    constructor(directDI: DirectDI) : this(
        tfaService = directDI.instance(),
        getCheckTwoFA = directDI.instance(),
    )

    override fun version() = combineJoinToVersion(
        getDatabaseVersionFlow(),
        getCheckTwoFA()
            .map {
                it.int.toString()
            },
    )

    private fun getDatabaseVersionFlow() = flowOf(FileHashes.tfa)

    override suspend fun process(
        ciphers: List<DSecret>,
    ): List<WatchtowerClientResult> {
        val tfaLibrary = tfaService()
            .crashlyticsTap()
            .attempt()
            .bind()
            .getOrNull()
            .orEmpty()

        return ciphers
            .map { cipher ->
                val value = hasAlert(
                    cipher = cipher,
                    tfaLibrary = tfaLibrary,
                )
                val threat = value != null
                WatchtowerClientResult(
                    value = value,
                    threat = threat,
                    cipher = cipher,
                )
            }
    }

    private fun hasAlert(
        cipher: DSecret,
        tfaLibrary: List<TwoFaServiceInfo>,
    ): String? {
        val shouldIgnore = shouldIgnore(cipher)
        if (shouldIgnore) {
            return null
        }

        if (
            cipher.login?.totp != null ||
            !cipher.login?.fido2Credentials.isNullOrEmpty() &&
            cipher.login?.password.isNullOrEmpty()
        ) {
            return null
        }

        val group = match(cipher, tfaLibrary)
            .map { info -> info.domain }
            .toSortedSet()
            .joinToString()
        return group.takeIf { it.isNotEmpty() }
    }

    private fun match(
        cipher: DSecret,
        tfaLibrary: List<TwoFaServiceInfo>,
    ) = cipher
        .uris
        .asSequence()
        .mapNotNull { uri ->
            val host = parseHost(uri)
                ?: return@mapNotNull null
            val result = tfaLibrary
                .firstOrNull { host in it.domains }
            result?.takeIf { "totp" in it.tfa }
        }

    private fun shouldIgnore(
        cipher: DSecret,
    ) = cipher.ignores(DWatchtowerAlertType.TWO_FA_WEBSITE)
}

class WatchtowerDuplicateUris(
    private val cipherUrlDuplicateCheck: CipherUrlDuplicateCheck,
) : WatchtowerClientTyped {
    override val type: Long
        get() = DWatchtowerAlertType.DUPLICATE_URIS.value

    constructor(directDI: DirectDI) : this(
        cipherUrlDuplicateCheck = directDI.instance(),
    )

    override fun version() = flowOf("1")

    override suspend fun process(
        ciphers: List<DSecret>,
    ): List<WatchtowerClientResult> {
        return ciphers
            .map { cipher ->
                val value = hasAlert(
                    cipher = cipher,
                )
                val threat = value != null
                WatchtowerClientResult(
                    value = value,
                    threat = threat,
                    cipher = cipher,
                )
            }
    }

    private suspend fun hasAlert(
        cipher: DSecret,
    ): String? {
        val shouldIgnore = shouldIgnore(cipher)
        if (shouldIgnore) {
            return null
        }

        return match(cipher)
    }

    private suspend fun match(
        cipher: DSecret,
    ) = kotlin.run {
        val uris = cipher.uris
        if (uris.isEmpty()) {
            return null
        }

        val out = mutableSetOf<String>()
        for (i in uris.indices) {
            for (j in uris.indices) {
                if (i == j) {
                    continue
                }

                val a = uris[i]
                val b = uris[j]
                val duplicate = cipherUrlDuplicateCheck(a, b)
                    .attempt()
                    .bind()
                    .isRight { it != null }
                if (duplicate) {
                    out += a.uri
                    out += b.uri
                }
            }
        }

        out
            .takeIf { it.isNotEmpty() }
            ?.joinToString()
    }

    private fun shouldIgnore(
        cipher: DSecret,
    ) = cipher.ignores(DWatchtowerAlertType.DUPLICATE_URIS)
}

private fun parseHost(uri: DSecret.Uri) = if (
    uri.uri.startsWith("http://") ||
    uri.uri.startsWith("https://")
) {
    val parsedUri = kotlin.runCatching {
        Url(uri.uri)
    }.getOrElse {
        // can not get the domain
        null
    }
    parsedUri
        ?.host
        // The "www" subdomain is ignored in the database, however
        // it's only "www". Other subdomains, such as "photos",
        // should be respected.
        ?.removePrefix("www.")
} else {
    // can not get the domain
    null
}

private fun combineJoinToVersion(
    vararg flows: Flow<String>,
): Flow<String> = combine(
    flows = flows,
) {
    it.joinToString(separator = "|")
}
