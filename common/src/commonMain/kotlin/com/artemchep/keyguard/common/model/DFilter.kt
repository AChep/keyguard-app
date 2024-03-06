package com.artemchep.keyguard.common.model

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Password
import androidx.compose.ui.graphics.vector.ImageVector
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
import com.artemchep.keyguard.feature.home.vault.component.obscurePassword
import com.artemchep.keyguard.feature.localization.TextHolder
import com.artemchep.keyguard.provider.bitwarden.entity.HibpBreachGroup
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.ui.icons.KeyguardAttachment
import com.artemchep.keyguard.ui.icons.KeyguardAuthReprompt
import com.artemchep.keyguard.ui.icons.KeyguardDuplicateWebsites
import com.artemchep.keyguard.ui.icons.KeyguardExpiringItems
import com.artemchep.keyguard.ui.icons.KeyguardFailedItems
import com.artemchep.keyguard.ui.icons.KeyguardIgnoredAlerts
import com.artemchep.keyguard.ui.icons.KeyguardIncompleteItems
import com.artemchep.keyguard.ui.icons.KeyguardPasskey
import com.artemchep.keyguard.ui.icons.KeyguardPendingSyncItems
import com.artemchep.keyguard.ui.icons.KeyguardPwnedPassword
import com.artemchep.keyguard.ui.icons.KeyguardPwnedWebsites
import com.artemchep.keyguard.ui.icons.KeyguardReusedPassword
import com.artemchep.keyguard.ui.icons.KeyguardTwoFa
import com.artemchep.keyguard.ui.icons.KeyguardUnsecureWebsites
import io.ktor.http.Url
import kotlinx.datetime.Clock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
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

                    else -> kotlin.run {
                        filter.filters.forEach { f ->
                            val r = _findOne(f, target, predicate)
                            r.fold(
                                ifLeft = {
                                    return@run r
                                },
                                ifRight = {
                                    if (it != null) {
                                        return@run Either.Left(Unit)
                                    }

                                    it
                                },
                            )
                        }
                        Either.Right(null)
                    }
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

        inline fun <reified T> findAny(
            filter: DFilter,
            noinline predicate: (T) -> Boolean = { true },
        ): T? = findAny(
            filter = filter,
            target = T::class.java,
            predicate = predicate,
        )

        fun <T> findAny(
            filter: DFilter,
            target: Class<T>,
            predicate: (T) -> Boolean = { true },
        ): T? = _findAny(
            filter = filter,
            target = target,
            predicate = predicate,
        )

        private fun <T> _findAny(
            filter: DFilter,
            target: Class<T>,
            predicate: (T) -> Boolean = { true },
        ): T? = when (filter) {
            is Or<*> -> filter
                .filters
                .firstNotNullOfOrNull { f ->
                    _findAny(f, target, predicate)
                }

            is And<*> -> filter
                .filters
                .firstNotNullOfOrNull { f ->
                    _findAny(f, target, predicate)
                }

            else -> {
                if (filter.javaClass == target) {
                    val f = filter as T
                    f.takeIf(predicate)
                } else {
                    null
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
    sealed interface PrimitiveSpecial : Primitive {
    }

    @Serializable
    sealed interface PrimitiveSimple : Primitive {
        data class Content(
            val title: TextHolder,
            val icon: ImageVector? = null,
        )

        @Transient
        val content: Content
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
    @SerialName("not")
    data class Not(
        val filter: DFilter,
    ) : DFilter {
        override suspend fun prepare(
            directDI: DirectDI,
            ciphers: List<DSecret>,
        ) = kotlin.run {
            val predicate = filter.prepare(
                directDI = directDI,
                ciphers = ciphers,
            )
            return@run { cipher: DSecret ->
                !predicate(cipher)
            }
        }

        override suspend fun prepareFolders(
            directDI: DirectDI,
            folders: List<DFolder>,
        ) = kotlin.run {
            val predicate = filter.prepareFolders(
                directDI = directDI,
                folders = folders,
            )
            return@run { folder: DFolder ->
                !predicate(folder)
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
    ) : PrimitiveSpecial {
        @Transient
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

            @SerialName("cipher")
            CIPHER,
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
                    if (id == null) {
                        return@run cipher.collectionIds.isEmpty()
                    }
                    return@run id in cipher.collectionIds
                }

                What.ACCOUNT -> cipher.accountId
                What.FOLDER -> cipher.folderId
                What.ORGANIZATION -> cipher.organizationId
                What.CIPHER -> cipher.id
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
                What.CIPHER,
                -> {
                    return@run true
                }
            } == id
        }
    }

    @Serializable
    @SerialName("by_type")
    data class ByType(
        @SerialName("cipherType")
        val type: DSecret.Type,
    ) : PrimitiveSimple {
        @Transient
        override val key: String = "$type"

        @Transient
        override val content = PrimitiveSimple.Content(
            title = type.titleH()
                .let(TextHolder::Res),
            icon = type.iconImageVector(),
        )

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
    data object ByOtp : PrimitiveSimple {
        @Transient
        override val key: String = "otp"

        @Transient
        override val content = PrimitiveSimple.Content(
            title = Res.strings.one_time_password
                .let(TextHolder::Res),
            icon = Icons.Outlined.KeyguardTwoFa,
        )

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
    data object ByAttachments : PrimitiveSimple {
        @Transient
        override val key: String = "attachments"

        @Transient
        override val content = PrimitiveSimple.Content(
            title = Res.strings.attachments
                .let(TextHolder::Res),
            icon = Icons.Outlined.KeyguardAttachment,
        )

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
    data object ByPasskeys : PrimitiveSimple {
        @Transient
        override val key: String = "passkeys"

        @Transient
        override val content = PrimitiveSimple.Content(
            title = Res.strings.passkeys
                .let(TextHolder::Res),
            icon = Icons.Outlined.Key,
        )

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
    ) : PrimitiveSimple {
        @Transient
        override val key: String = "pwd_value|$value"

        @Transient
        override val content = PrimitiveSimple.Content(
            title = value?.let(::obscurePassword).orEmpty()
                .let(TextHolder::Value),
            icon = Icons.Outlined.Password,
        )

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
    ) : PrimitiveSimple {
        @Transient
        override val key: String = "pwd_score|$score"

        @Transient
        override val content = PrimitiveSimple.Content(
            title = when (score) {
                PasswordStrength.Score.Weak -> Res.strings.passwords_weak_label
                PasswordStrength.Score.Fair -> Res.strings.passwords_fair_label
                PasswordStrength.Score.Good -> Res.strings.passwords_good_label
                PasswordStrength.Score.Strong -> Res.strings.passwords_strong_label
                PasswordStrength.Score.VeryStrong -> Res.strings.passwords_very_strong_label
            }
                .let(TextHolder::Res),
            icon = Icons.Outlined.Password,
        )

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
    data object ByPasswordDuplicates : PrimitiveSimple {
        @Transient
        override val key: String = "pwd_duplicates"

        @Transient
        override val content = PrimitiveSimple.Content(
            title = Res.strings.watchtower_item_reused_passwords_title
                .let(TextHolder::Res),
            icon = Icons.Outlined.KeyguardReusedPassword,
        )

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
    data object ByPasswordPwned : PrimitiveSimple {
        @Transient
        override val key: String = "pwd_pwned"

        @Transient
        override val content = PrimitiveSimple.Content(
            title = Res.strings.watchtower_item_pwned_passwords_title
                .let(TextHolder::Res),
            icon = Icons.Outlined.KeyguardPwnedPassword,
        )

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
    data object ByWebsitePwned : PrimitiveSimple {
        @Transient
        override val key: String = "website_pwned"

        @Transient
        override val content = PrimitiveSimple.Content(
            title = Res.strings.watchtower_item_vulnerable_accounts_title
                .let(TextHolder::Res),
            icon = Icons.Outlined.KeyguardPwnedWebsites,
        )

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
    data object ByIncomplete : PrimitiveSimple {
        @Transient
        override val key: String = "incomplete"

        @Transient
        override val content = PrimitiveSimple.Content(
            title = Res.strings.watchtower_item_incomplete_items_title
                .let(TextHolder::Res),
            icon = Icons.Outlined.KeyguardIncompleteItems,
        )

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
    data object ByExpiring : PrimitiveSimple {
        @Transient
        override val key: String = "expiring"

        @Transient
        override val content = PrimitiveSimple.Content(
            title = Res.strings.watchtower_item_expiring_items_title
                .let(TextHolder::Res),
            icon = Icons.Outlined.KeyguardExpiringItems,
        )

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
    data object ByUnsecureWebsites : PrimitiveSimple {
        @Transient
        override val key: String = "unsecure_websites"

        @Transient
        override val content = PrimitiveSimple.Content(
            title = Res.strings.watchtower_item_unsecure_websites_title
                .let(TextHolder::Res),
            icon = Icons.Outlined.KeyguardUnsecureWebsites,
        )

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
    data object ByTfaWebsites : PrimitiveSimple {
        @Transient
        override val key: String = "tfa_websites"

        @Transient
        override val content = PrimitiveSimple.Content(
            title = Res.strings.watchtower_item_inactive_2fa_title
                .let(TextHolder::Res),
            icon = Icons.Outlined.KeyguardTwoFa,
        )

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
    data object ByPasskeyWebsites : PrimitiveSimple {
        @Transient
        override val key: String = "passkey_websites"

        @Transient
        override val content = PrimitiveSimple.Content(
            title = Res.strings.watchtower_item_inactive_passkey_title
                .let(TextHolder::Res),
            icon = Icons.Outlined.KeyguardPasskey,
        )

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
    data object ByDuplicateWebsites : PrimitiveSimple {
        @Transient
        override val key: String = "duplicate_websites"

        @Transient
        override val content = PrimitiveSimple.Content(
            title = Res.strings.watchtower_item_duplicate_websites_title
                .let(TextHolder::Res),
            icon = Icons.Outlined.KeyguardDuplicateWebsites,
        )

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
    ) : PrimitiveSimple {
        @Transient
        override val key: String = "$synced"

        @Transient
        override val content = PrimitiveSimple.Content(
            title = Res.strings.filter_pending_items
                .let(TextHolder::Res),
            icon = Icons.Outlined.KeyguardPendingSyncItems,
        )

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
    ) : PrimitiveSimple {
        @Transient
        override val key: String = "$reprompt"

        @Transient
        override val content = PrimitiveSimple.Content(
            title = Res.strings.filter_auth_reprompt_items
                .let(TextHolder::Res),
            icon = Icons.Outlined.KeyguardAuthReprompt,
        )

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
    ) : PrimitiveSimple {
        @Transient
        override val key: String = "$error"

        @Transient
        override val content = PrimitiveSimple.Content(
            title = Res.strings.filter_failed_items
                .let(TextHolder::Res),
            icon = Icons.Outlined.KeyguardFailedItems,
        )

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
    data object ByIgnoredAlerts : PrimitiveSimple {
        @Transient
        override val key: String = "ignored_alerts"

        @Transient
        override val content = PrimitiveSimple.Content(
            title = Res.strings.ignored_alerts
                .let(TextHolder::Res),
            icon = Icons.Outlined.KeyguardIgnoredAlerts,
        )

        override suspend fun prepare(
            directDI: DirectDI,
            ciphers: List<DSecret>,
        ) = ::predicate

        private fun predicate(
            cipher: DSecret,
        ) = cipher.ignoredAlerts.isNotEmpty()
    }
}
