package com.artemchep.keyguard.common.model

import arrow.core.Either
import arrow.core.getOrElse
import arrow.core.partially1
import com.artemchep.keyguard.common.io.attempt
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.io.handleError
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.io.map
import com.artemchep.keyguard.common.io.measure
import com.artemchep.keyguard.common.service.hibp.breaches.all.BreachesRepository
import com.artemchep.keyguard.common.service.passkey.PassKeyService
import com.artemchep.keyguard.common.service.passkey.PassKeyServiceInfo
import com.artemchep.keyguard.common.service.twofa.TwoFaService
import com.artemchep.keyguard.common.service.twofa.TwoFaServiceInfo
import com.artemchep.keyguard.common.usecase.CheckPasswordSetLeak
import com.artemchep.keyguard.common.usecase.CipherBreachCheck
import com.artemchep.keyguard.common.usecase.CipherExpiringCheck
import com.artemchep.keyguard.common.usecase.CipherIncompleteCheck
import com.artemchep.keyguard.common.usecase.CipherUnsecureUrlCheck
import com.artemchep.keyguard.common.usecase.CipherUrlDuplicateCheck
import com.artemchep.keyguard.core.store.bitwarden.exists
import com.artemchep.keyguard.feature.crashlytics.crashlyticsTap
import com.artemchep.keyguard.provider.bitwarden.entity.HibpBreachGroup
import io.ktor.http.Url
import kotlinx.datetime.Clock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.kodein.di.DirectDI
import org.kodein.di.instance
import kotlin.collections.Collection
import kotlin.collections.List
import kotlin.collections.Map
import kotlin.collections.Set
import kotlin.collections.all
import kotlin.collections.any
import kotlin.collections.asSequence
import kotlin.collections.contains
import kotlin.collections.count
import kotlin.collections.emptyList
import kotlin.collections.emptyMap
import kotlin.collections.filter
import kotlin.collections.first
import kotlin.collections.firstOrNull
import kotlin.collections.forEach
import kotlin.collections.indices
import kotlin.collections.isNotEmpty
import kotlin.collections.isNullOrEmpty
import kotlin.collections.map
import kotlin.collections.mapNotNull
import kotlin.collections.mapValues
import kotlin.collections.mutableMapOf
import kotlin.collections.orEmpty
import kotlin.collections.toSet

@Serializable
sealed interface DFilter {
    companion object {
        inline fun <reified T> findOne(
            filter: DFilter,
            noinline predicate: (T) -> Boolean = { true },
        ): T? = findOne(
            filter = filter,
            target = T::class.java,
            predicate = predicate,
        )

        fun <T> findOne(
            filter: DFilter,
            target: Class<T>,
            predicate: (T) -> Boolean = { true },
        ): T? = _findOne(
            filter = filter,
            target = target,
            predicate = predicate,
        ).getOrNull()

        private fun <T> _findOne(
            filter: DFilter,
            target: Class<T>,
            predicate: (T) -> Boolean = { true },
        ): Either<Unit, T?> = when (filter) {
            is Or<*> -> {
                when (filter.filters.size) {
                    0 -> Either.Right(null)
                    1 -> {
                        val f = filter.filters.first()
                        _findOne(f, target, predicate)
                    }

                    else -> Either.Left(Unit)
                }
            }

            is And<*> -> {
                val results = filter
                    .filters
                    .map { f ->
                        _findOne(f, target, predicate)
                    }
                // If any of the variants has returned that
                // we must abort the search then abort the search.
                if (results.any { it.isLeft() }) {
                    // Propagate the error down the pipe.
                    Either.Left(Unit)
                } else {
                    val fs = results.mapNotNull { it.getOrNull() }
                    when (fs.size) {
                        0 -> Either.Right(null)
                        1 -> Either.Right(fs.first())
                        else -> Either.Left(Unit)
                    }
                }
            }

            else -> {
                if (filter.javaClass == target) {
                    val f = filter as T
                    val v = predicate(f)
                    if (v) {
                        Either.Right(f)
                    } else {
                        Either.Right(null)
                    }
                } else {
                    Either.Right(null)
                }
            }
        }
    }

    suspend fun prepare(
        directDI: DirectDI,
        ciphers: List<DSecret>,
    ): (DSecret) -> Boolean

    suspend fun prepareFolders(
        directDI: DirectDI,
        folders: List<DFolder>,
    ): (DFolder) -> Boolean = All.prepareFolders(directDI, folders)

    @Serializable
    sealed interface Primitive : DFilter {
        val key: String
    }

    @Serializable
    @SerialName("or")
    data class Or<out T : DFilter>(
        val filters: Collection<T>,
    ) : DFilter {
        override suspend fun prepare(
            directDI: DirectDI,
            ciphers: List<DSecret>,
        ) = kotlin.run {
            val list = filters
                .map { it.prepare(directDI, ciphers) }
            return@run { cipher: DSecret ->
                list.isEmpty() || list.any { predicate -> predicate(cipher) }
            }
        }

        override suspend fun prepareFolders(
            directDI: DirectDI,
            folders: List<DFolder>,
        ) = kotlin.run {
            val list = filters
                .map { it.prepareFolders(directDI, folders) }
            return@run { folder: DFolder ->
                list.isEmpty() || list.any { predicate -> predicate(folder) }
            }
        }
    }

    @Serializable
    @SerialName("and")
    data class And<out T : DFilter>(
        val filters: Collection<T>,
    ) : DFilter {
        override suspend fun prepare(
            directDI: DirectDI,
            ciphers: List<DSecret>,
        ) = kotlin.run {
            val list = filters
                .map { it.prepare(directDI, ciphers) }
            return@run { cipher: DSecret ->
                list.isEmpty() || list.all { predicate -> predicate(cipher) }
            }
        }

        override suspend fun prepareFolders(
            directDI: DirectDI,
            folders: List<DFolder>,
        ) = kotlin.run {
            val list = filters
                .map { it.prepareFolders(directDI, folders) }
            return@run { folder: DFolder ->
                list.isEmpty() || list.all { predicate -> predicate(folder) }
            }
        }
    }

    @Serializable
    @SerialName("all")
    data object All : DFilter {
        override suspend fun prepare(
            directDI: DirectDI,
            ciphers: List<DSecret>,
        ) = ::predicateCipher

        override suspend fun prepareFolders(
            directDI: DirectDI,
            folders: List<DFolder>,
        ) = ::predicateFolder

        private fun predicateCipher(cipher: DSecret) = true

        private fun predicateFolder(folder: DFolder) = true
    }

    // Primitives

    @Serializable
    @SerialName("by_id")
    data class ById(
        val id: String?,
        val what: What,
    ) : Primitive {
        override val key: String = "$id|$what"

        @Serializable
        enum class What {
            @SerialName("account")
            ACCOUNT,

            @SerialName("folder")
            FOLDER,

            @SerialName("collection")
            COLLECTION,

            @SerialName("organization")
            ORGANIZATION,
        }

        override suspend fun prepare(
            directDI: DirectDI,
            ciphers: List<DSecret>,
        ) = ::predicateCipher

        override suspend fun prepareFolders(
            directDI: DirectDI,
            folders: List<DFolder>,
        ) = ::predicateFolder

        private fun predicateCipher(
            cipher: DSecret,
        ) = kotlin.run {
            when (what) {
                What.COLLECTION -> {
                    // Special case: check in the set of
                    // collections.
                    return@run id in cipher.collectionIds
                }

                What.ACCOUNT -> cipher.accountId
                What.FOLDER -> cipher.folderId
                What.ORGANIZATION -> cipher.organizationId
            } == id
        }

        private fun predicateFolder(
            folder: DFolder,
        ) = kotlin.run {
            when (what) {
                What.FOLDER -> folder.id
                What.ACCOUNT -> folder.accountId
                What.COLLECTION,
                What.ORGANIZATION,
                -> {
                    return@run true
                }
            } == id
        }
    }

    @Serializable
    @SerialName("by_type")
    data class ByType(
        val type: DSecret.Type,
    ) : Primitive {
        override val key: String = "$type"

        override suspend fun prepare(
            directDI: DirectDI,
            ciphers: List<DSecret>,
        ) = ::predicate

        private fun predicate(
            cipher: DSecret,
        ) = cipher.type == type
    }

    @Serializable
    @SerialName("by_otp")
    data object ByOtp : Primitive {
        override val key: String = "otp"

        override suspend fun prepare(
            directDI: DirectDI,
            ciphers: List<DSecret>,
        ) = ::predicate

        private fun predicate(
            cipher: DSecret,
        ) = cipher.login?.totp != null
    }

    @Serializable
    @SerialName("by_attachments")
    data object ByAttachments : Primitive {
        override val key: String = "attachments"

        override suspend fun prepare(
            directDI: DirectDI,
            ciphers: List<DSecret>,
        ) = ::predicate

        private fun predicate(
            cipher: DSecret,
        ) = cipher.attachments.isNotEmpty()
    }

    @Serializable
    @SerialName("by_passkeys")
    data object ByPasskeys : Primitive {
        override val key: String = "passkeys"

        override suspend fun prepare(
            directDI: DirectDI,
            ciphers: List<DSecret>,
        ) = ::predicate

        private fun predicate(
            cipher: DSecret,
        ) = !cipher.login?.fido2Credentials.isNullOrEmpty()
    }

    @Serializable
    @SerialName("by_pwd_value")
    data class ByPasswordValue(
        val value: String?,
    ) : Primitive {
        override val key: String = "pwd_value|$value"

        override suspend fun prepare(
            directDI: DirectDI,
            ciphers: List<DSecret>,
        ) = ::predicate

        private fun predicate(
            cipher: DSecret,
        ) = cipher.login?.password == value
    }

    @Serializable
    @SerialName("by_pwd_strength")
    data class ByPasswordStrength(
        val score: PasswordStrength.Score,
    ) : Primitive {
        override val key: String = "$score"

        override suspend fun prepare(
            directDI: DirectDI,
            ciphers: List<DSecret>,
        ) = ::predicate

        private fun predicate(
            cipher: DSecret,
        ) = cipher.login?.passwordStrength?.score == score
    }

    @Serializable
    @SerialName("by_pwd_duplicates")
    data object ByPasswordDuplicates : Primitive {
        override val key: String = "pwd_duplicates"

        private data class DuplicatesState(
            var duplicate: Int,
            var ignored: Int,
        )

        override suspend fun prepare(
            directDI: DirectDI,
            ciphers: List<DSecret>,
        ) = kotlin.run {
            val set = buildDuplicatesState(ciphers = ciphers)
                .asSequence()
                .mapNotNull { entry ->
                    entry.key.takeIf { entry.value.duplicate > 1 }
                }
                .toSet()
            ::predicate.partially1(set)
        }

        /** Counts a number of ciphers with duplicate password */
        fun count(
            directDI: DirectDI,
            ciphers: List<DSecret>,
        ) = buildDuplicatesState(ciphers = ciphers)
            .asSequence()
            .sumOf { entry ->
                val state = entry.value
                if (state.duplicate > 1) {
                    // This is indeed a duplicate.
                    state.duplicate - state.ignored
                } else {
                    0
                }
            }

        private fun buildDuplicatesState(
            ciphers: List<DSecret>,
        ): Map<String, DuplicatesState> {
            val map = mutableMapOf<String, DuplicatesState>()
            ciphers.forEach { cipher ->
                val password = cipher.login?.password
                if (password != null) {
                    val holder = map.getOrPut(password) {
                        DuplicatesState(
                            duplicate = 0,
                            ignored = 0,
                        )
                    }
                    holder.duplicate += 1

                    val shouldIgnore = shouldIgnore(cipher)
                    if (shouldIgnore) {
                        holder.ignored += 1
                    }
                }
            }
            return map
        }

        private fun predicate(
            passwords: Set<String>,
            cipher: DSecret,
        ) = cipher.login?.password in passwords &&
                !shouldIgnore(cipher)

        private fun shouldIgnore(
            cipher: DSecret,
        ) = cipher.ignores(DWatchtowerAlert.REUSED_PASSWORD)
    }

    @Serializable
    @SerialName("by_pwd_pwned")
    data object ByPasswordPwned : Primitive {
        override val key: String = "pwd_pwned"

        override suspend fun prepare(
            directDI: DirectDI,
            ciphers: List<DSecret>,
        ) = kotlin.run {
            val set = buildPwnedPasswordsState(directDI = directDI, ciphers = ciphers)
                .asSequence()
                .mapNotNull { entry ->
                    entry.key.takeIf { entry.value > 1 }
                }
                .toSet()
            ::predicate.partially1(set)
        }

        /** Counts a number of ciphers with pwned password */
        suspend fun count(
            directDI: DirectDI,
            ciphers: List<DSecret>,
        ) = buildPwnedPasswordsState(directDI = directDI, ciphers = ciphers)
            .asSequence()
            .count { entry ->
                entry.value > 1
            }

        private suspend fun buildPwnedPasswordsState(
            directDI: DirectDI,
            ciphers: List<DSecret>,
        ): Map<String, Int> = ioEffect {
            val checkPasswordSetLeak: CheckPasswordSetLeak = directDI.instance()
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

        private fun predicate(
            passwords: Set<String>,
            cipher: DSecret,
        ) = cipher.login?.password in passwords &&
                !shouldIgnore(cipher)

        private fun shouldIgnore(
            cipher: DSecret,
        ) = cipher.ignores(DWatchtowerAlert.PWNED_PASSWORD)
    }

    @Serializable
    @SerialName("by_website_pwned")
    data object ByWebsitePwned : Primitive {
        override val key: String = "website_pwned"

        override suspend fun prepare(
            directDI: DirectDI,
            ciphers: List<DSecret>,
        ) = kotlin.run {
            val set = buildDuplicatesState(directDI = directDI, ciphers = ciphers)
            ::predicate.partially1(set)
        }

        /** Counts a number of ciphers with pwned password */
        suspend fun count(
            directDI: DirectDI,
            ciphers: List<DSecret>,
        ) = buildDuplicatesState(directDI = directDI, ciphers = ciphers)
            .size

        private suspend fun buildDuplicatesState(
            directDI: DirectDI,
            ciphers: List<DSecret>,
        ): Set<String> = ioEffect {
            val check: CipherBreachCheck = directDI.instance()
            val repo: BreachesRepository = directDI.instance()

            val breaches = repo.get()
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

                    check(cipher, breaches)
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

        private fun predicate(
            cipherIds: Set<String>,
            cipher: DSecret,
        ) = cipher.id in cipherIds

        private fun shouldIgnore(
            cipher: DSecret,
        ) = cipher.ignores(DWatchtowerAlert.PWNED_WEBSITE)
    }

    @Serializable
    @SerialName("by_incomplete")
    data object ByIncomplete : Primitive {
        override val key: String = "incomplete"

        override suspend fun prepare(
            directDI: DirectDI,
            ciphers: List<DSecret>,
        ) = kotlin.run {
            val ids = filterIncomplete(directDI, ciphers)
                .map { it.id }
                .toSet()
            ::predicate.partially1(ids)
        }

        private fun predicate(
            ids: Set<String>,
            cipher: DSecret,
        ) = cipher.id in ids

        /** Counts a number of ciphers that might be incomplete */
        fun count(
            directDI: DirectDI,
            ciphers: List<DSecret>,
        ) = filterIncomplete(directDI, ciphers).count()

        private fun filterIncomplete(
            directDI: DirectDI,
            ciphers: List<DSecret>,
        ) = kotlin.run {
            val cipherIncompleteCheck = directDI.instance<CipherIncompleteCheck>()
            ciphers
                .asSequence()
                .filter { cipher ->
                    val shouldIgnore = shouldIgnore(cipher)
                    if (shouldIgnore) {
                        return@filter false
                    }

                    val isIncomplete = cipherIncompleteCheck(cipher)
                    isIncomplete
                }
        }

        private fun shouldIgnore(
            cipher: DSecret,
        ) = cipher.ignores(DWatchtowerAlert.INCOMPLETE)
    }

    @Serializable
    @SerialName("by_expiring")
    data object ByExpiring : Primitive {
        override val key: String = "expiring"

        override suspend fun prepare(
            directDI: DirectDI,
            ciphers: List<DSecret>,
        ) = kotlin.run {
            val ids = filterExpiring(directDI, ciphers)
                .map { it.id }
                .toSet()
            ::predicate.partially1(ids)
        }

        private fun predicate(
            ids: Set<String>,
            cipher: DSecret,
        ) = cipher.id in ids

        /** Counts a number of ciphers that might be incomplete */
        fun count(
            directDI: DirectDI,
            ciphers: List<DSecret>,
        ) = filterExpiring(directDI, ciphers).count()

        private fun filterExpiring(
            directDI: DirectDI,
            ciphers: List<DSecret>,
        ) = kotlin.run {
            val now = Clock.System.now()
            val cipherExpiringCheck = directDI.instance<CipherExpiringCheck>()
            ciphers
                .asSequence()
                .filter { cipher ->
                    val shouldIgnore = shouldIgnore(cipher)
                    if (shouldIgnore) {
                        return@filter false
                    }

                    val isExpiring = cipherExpiringCheck(cipher, now) != null
                    isExpiring
                }
        }

        private fun shouldIgnore(
            cipher: DSecret,
        ) = cipher.ignores(DWatchtowerAlert.EXPIRING)
    }

    @Serializable
    @SerialName("by_unsecure_websites")
    data object ByUnsecureWebsites : Primitive {
        override val key: String = "unsecure_websites"

        override suspend fun prepare(
            directDI: DirectDI,
            ciphers: List<DSecret>,
        ) = kotlin.run {
            val ids = filterUnsecure(directDI, ciphers)
                .map { it.id }
                .toSet()
            ::predicate.partially1(ids)
        }

        private fun predicate(
            ids: Set<String>,
            cipher: DSecret,
        ) = cipher.id in ids

        /** Counts a number of ciphers that contain unsecure websites */
        fun count(
            directDI: DirectDI,
            ciphers: List<DSecret>,
        ) = filterUnsecure(directDI, ciphers).count()

        private fun filterUnsecure(
            directDI: DirectDI,
            ciphers: List<DSecret>,
        ) = kotlin.run {
            val cipherUnsecureUrlCheck = directDI.instance<CipherUnsecureUrlCheck>()
            ciphers
                .asSequence()
                .filter { cipher ->
                    val shouldIgnore = shouldIgnore(cipher)
                    if (shouldIgnore) {
                        return@filter false
                    }

                    val isUnsecure = cipher
                        .uris
                        .any { uri -> cipherUnsecureUrlCheck(uri.uri) }
                    isUnsecure
                }
        }

        private fun shouldIgnore(
            cipher: DSecret,
        ) = cipher.ignores(DWatchtowerAlert.UNSECURE_WEBSITE)
    }

    @Serializable
    @SerialName("by_tfa_websites")
    data object ByTfaWebsites : Primitive {
        override val key: String = "tfa_websites"

        override suspend fun prepare(
            directDI: DirectDI,
            ciphers: List<DSecret>,
        ) = kotlin.run {
            val ids = filterTfa(
                directDI = directDI,
                ciphers = ciphers,
            )
                .map { it.id }
                .toSet()
            ::predicate.partially1(ids)
        }

        private fun predicate(
            ids: Set<String>,
            cipher: DSecret,
        ) = cipher.id in ids

        /** Counts a number of ciphers that contain unsecure websites */
        suspend fun count(
            directDI: DirectDI,
            ciphers: List<DSecret>,
        ) = filterTfa(directDI, ciphers).count()

        private suspend fun filterTfa(
            directDI: DirectDI,
            ciphers: List<DSecret>,
        ) = kotlin.run {
            val tfaService = directDI.instance<TwoFaService>()
            val tfa = tfaService.get()
                .crashlyticsTap()
                .attempt()
                .bind()
                .getOrNull()
                .orEmpty()
            ciphers
                .asSequence()
                .filter { cipher ->
                    val shouldIgnore = shouldIgnore(cipher)
                    if (shouldIgnore) {
                        return@filter false
                    }
                    if (
                        cipher.login?.totp != null ||
                        !cipher.login?.fido2Credentials.isNullOrEmpty() &&
                        cipher.login?.password.isNullOrEmpty()
                    ) {
                        return@filter false
                    }

                    val isUnsecure = match(cipher, tfa).any()
                    isUnsecure
                }
        }

        fun match(cipher: DSecret, tfa: List<TwoFaServiceInfo>) = cipher
            .uris
            .asSequence()
            .mapNotNull { uri ->
                val host = parseHost(uri)
                    ?: return@mapNotNull null
                val result = tfa
                    .firstOrNull { host in it.domains }
                result?.takeIf { "totp" in it.tfa }
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

        private fun shouldIgnore(
            cipher: DSecret,
        ) = cipher.ignores(DWatchtowerAlert.TWO_FA_WEBSITE)
    }

    @Serializable
    @SerialName("by_passkey_websites")
    data object ByPasskeyWebsites : Primitive {
        override val key: String = "passkey_websites"

        override suspend fun prepare(
            directDI: DirectDI,
            ciphers: List<DSecret>,
        ) = kotlin.run {
            val ids = filterTfa(
                directDI = directDI,
                ciphers = ciphers,
            )
                .map { it.id }
                .toSet()
            ::predicate.partially1(ids)
        }

        private fun predicate(
            ids: Set<String>,
            cipher: DSecret,
        ) = cipher.id in ids

        /** Counts a number of ciphers that contain unsecure websites */
        suspend fun count(
            directDI: DirectDI,
            ciphers: List<DSecret>,
        ) = filterTfa(directDI, ciphers).count()

        private suspend fun filterTfa(
            directDI: DirectDI,
            ciphers: List<DSecret>,
        ) = kotlin.run {
            val passkeyService = directDI.instance<PassKeyService>()
            val tfa = passkeyService.get()
                .crashlyticsTap()
                .attempt()
                .bind()
                .getOrNull()
                .orEmpty()
            ciphers
                .asSequence()
                .filter { cipher ->
                    val shouldIgnore = shouldIgnore(cipher)
                    if (shouldIgnore) {
                        return@filter false
                    }
                    if (!cipher.login?.fido2Credentials.isNullOrEmpty()) {
                        return@filter false
                    }

                    val isUnsecure = match(cipher, tfa).any()
                    isUnsecure
                }
        }

        fun match(cipher: DSecret, tfa: List<PassKeyServiceInfo>) = cipher
            .uris
            .asSequence()
            .mapNotNull { uri ->
                val host = parseHost(uri)
                    ?: return@mapNotNull null
                val result = tfa
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

        private fun shouldIgnore(
            cipher: DSecret,
        ) = cipher.ignores(DWatchtowerAlert.PASSKEY_WEBSITE)
    }

    @Serializable
    @SerialName("by_duplicate_websites")
    data object ByDuplicateWebsites : Primitive {
        override val key: String = "duplicate_websites"

        override suspend fun prepare(
            directDI: DirectDI,
            ciphers: List<DSecret>,
        ) = kotlin.run {
            val ids = filterDuplicateWebsites(
                directDI = directDI,
                ciphers = ciphers,
            )
                .map { it.id }
                .toSet()
            ::predicate.partially1(ids)
        }

        private fun predicate(
            ids: Set<String>,
            cipher: DSecret,
        ) = cipher.id in ids

        /** Counts a number of ciphers that contain unsecure websites */
        suspend fun count(
            directDI: DirectDI,
            ciphers: List<DSecret>,
        ) = filterDuplicateWebsites(directDI, ciphers).count()

        private suspend fun filterDuplicateWebsites(
            directDI: DirectDI,
            ciphers: List<DSecret>,
        ) = kotlin.run {
            val cipherUrlDuplicateCheck = directDI.instance<CipherUrlDuplicateCheck>()
            ciphers
                .filter { cipher ->
                    val uris = cipher.uris
                    if (uris.isEmpty()) {
                        return@filter false
                    }

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
                                return@filter true
                            }
                        }
                    }

                    false
                }
        }
    }

    @Serializable
    @SerialName("by_sync")
    data class BySync(
        val synced: Boolean,
    ) : Primitive {
        override val key: String = "$synced"

        override suspend fun prepare(
            directDI: DirectDI,
            ciphers: List<DSecret>,
        ) = ::predicateCipher

        override suspend fun prepareFolders(
            directDI: DirectDI,
            folders: List<DFolder>,
        ) = ::predicateFolder

        private fun predicateCipher(
            cipher: DSecret,
        ) = cipher.synced == synced

        private fun predicateFolder(
            folder: DFolder,
        ) = folder.synced == synced
    }

    @Serializable
    @SerialName("by_repromt")
    data class ByReprompt(
        val reprompt: Boolean,
    ) : Primitive {
        override val key: String = "$reprompt"

        override suspend fun prepare(
            directDI: DirectDI,
            ciphers: List<DSecret>,
        ) = ::predicateCipher

        private fun predicateCipher(
            cipher: DSecret,
        ) = cipher.reprompt == reprompt
    }

    @Serializable
    @SerialName("by_error")
    data class ByError(
        val error: Boolean,
    ) : Primitive {
        override val key: String = "$error"

        override suspend fun prepare(
            directDI: DirectDI,
            ciphers: List<DSecret>,
        ) = ::predicateCipher

        override suspend fun prepareFolders(
            directDI: DirectDI,
            folders: List<DFolder>,
        ) = ::predicateFolder

        private fun predicateCipher(
            cipher: DSecret,
        ) = cipher.service.error.exists(cipher.revisionDate) == error

        private fun predicateFolder(
            folder: DFolder,
        ) = folder.service.error.exists(folder.revisionDate) == error
    }

    @Serializable
    @SerialName("by_ignored_alerts")
    data object ByIgnoredAlerts : Primitive {
        override val key: String = "ignored_alerts"

        override suspend fun prepare(
            directDI: DirectDI,
            ciphers: List<DSecret>,
        ) = ::predicate

        private fun predicate(
            cipher: DSecret,
        ) = cipher.ignoredAlerts.isNotEmpty()
    }
}
