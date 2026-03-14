package com.artemchep.keyguard.provider.bitwarden.usecase

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.flatMap
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.model.AccountId
import com.artemchep.keyguard.common.model.DSend
import com.artemchep.keyguard.common.model.create.CreateSendRequest
import com.artemchep.keyguard.common.service.crypto.CryptoGenerator
import com.artemchep.keyguard.common.service.text.Base64Service
import com.artemchep.keyguard.common.usecase.AddSend
import com.artemchep.keyguard.core.store.bitwarden.BitwardenOptionalStringNullable
import com.artemchep.keyguard.core.store.bitwarden.BitwardenSend
import com.artemchep.keyguard.core.store.bitwarden.BitwardenService
import com.artemchep.keyguard.feature.auth.common.util.ValidationEmail
import com.artemchep.keyguard.feature.auth.common.util.validateEmail
import com.artemchep.keyguard.provider.bitwarden.crypto.makeSendCryptoKeyMaterial
import com.artemchep.keyguard.provider.bitwarden.usecase.util.ModifyDatabase
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import org.kodein.di.DirectDI
import org.kodein.di.instance
import kotlin.time.Duration

/**
 * @author Artem Chepurnyi
 */
class AddSendImpl(
    private val modifyDatabase: ModifyDatabase,
    private val cryptoGenerator: CryptoGenerator,
    private val base64Service: Base64Service,
) : AddSend {
    companion object {
        private const val TAG = "AddSend.bitwarden"
    }

    constructor(directDI: DirectDI) : this(
        modifyDatabase = directDI.instance(),
        cryptoGenerator = directDI.instance(),
        base64Service = directDI.instance(),
    )

    override fun invoke(
        sendIdsToRequests: Map<String?, CreateSendRequest>,
    ): IO<List<String>> = ioEffect {
        sendIdsToRequests
    }.flatMap { map ->
        modifyDatabase { database ->
            val dao = database.sendQueries
            val now = Clock.System.now()

            val oldSendsMap = map
                .keys
                .filterNotNull()
                .mapNotNull { sendId ->
                    dao
                        .getBySendId(sendId)
                        .executeAsOneOrNull()
                }
                .associateBy { it.sendId }

            val models = map
                .map { (sendId, request) ->
                    val old = oldSendsMap[sendId]?.data_
                    BitwardenSend.of(
                        cryptoGenerator = cryptoGenerator,
                        base64Service = base64Service,
                        now = now,
                        request = request,
                        old = old,
                    )
                }
            if (models.isEmpty()) {
                return@modifyDatabase ModifyDatabase.Result(
                    changedAccountIds = emptySet(),
                    value = emptyList(),
                )
            }
            dao.transaction {
                models.forEach { send ->
                    dao.insert(
                        sendId = send.sendId,
                        accountId = send.accountId,
                        data = send,
                    )
                }
            }

            val changedAccountIds = models
                .map { AccountId(it.accountId) }
                .toSet()
            ModifyDatabase.Result(
                changedAccountIds = changedAccountIds,
                value = models
                    .map { it.sendId },
            )
        }
    }
}

private suspend fun BitwardenSend.Companion.of(
    cryptoGenerator: CryptoGenerator,
    base64Service: Base64Service,
    request: CreateSendRequest,
    now: Instant,
    old: BitwardenSend? = null,
): BitwardenSend {
    val accountId = request.ownership?.accountId
    require(old?.service?.deleted != true) {
        "Can not modify deleted send!"
    }
    requireNotNull(accountId) { "Send must have an account!" }

    val type = when (request.type) {
        DSend.Type.Text -> BitwardenSend.Type.Text
        DSend.Type.File -> BitwardenSend.Type.File
        null,
        DSend.Type.None,
        -> error("Send must have a type!")
    }

    var text: BitwardenSend.Text? = null
    var file: BitwardenSend.File? = null
    when (type) {
        BitwardenSend.Type.Text -> {
            text = BitwardenSend.Text.of(
                request = request,
                old = old,
            )
        }

        BitwardenSend.Type.File -> {
            file = BitwardenSend.File.of(
                request = request,
                old = old,
            )
        }

        BitwardenSend.Type.None -> {
            error("Send must have a type!")
        }
    }

    val keyBase64 = old?.keyBase64
        ?: run {
            val key = cryptoGenerator.makeSendCryptoKeyMaterial()
            base64Service.encodeToString(key)
        }
    val cipherId = old?.sendId
        ?: cryptoGenerator.uuid()

    val createdDate = old?.createdDate ?: request.now
    val deletedDate = kotlin.run {
        // Prioritize the duration parameter over the
        // date as a timestamp.
        val tmp = request.deletionDateAsDuration
            ?.let { duration -> request.now.plus(duration) }
        // Then focus on the deletion timestamp.
            ?: request.deletionDate
                ?.toInstant(TimeZone.currentSystemDefault())
            // Then just use existing timestamp.
            ?: old?.deletedDate
            // Should never happen: just add one day to the
            // current time.
            ?: request.now.plus(with(Duration) { 1.days })
        tmp.coerceIn(
            minimumValue = request.now.plus(with(Duration) { 1.minutes }),
            maximumValue = request.now.plus(with(Duration) { 31.days }),
        )
    }
    val expirationDate = kotlin.run {
        // Prioritize the duration parameter over the
        // date as a timestamp.
        val tmp = request.expirationDateAsDuration
            ?.also {
                // If it should never expire, then we just
                // send `null` instead of infinity.
                if (it == Duration.INFINITE) {
                    return@run null
                }
            }
            ?.let { duration -> request.now.plus(duration) }
        // Then focus on the expiration timestamp.
            ?: request.expirationDate
                ?.toInstant(TimeZone.currentSystemDefault())
            // Then just use existing timestamp.
            ?: old?.expirationDate
            // Should never happen: just add one day to the
            // current time.
            ?: request.now.plus(with(Duration) { 1.days })
        tmp
    }
        // If the expiration date exists, then the expiration date
        // must me less or equal to it.
        ?.coerceAtMost(deletedDate)

    val newPasswordBase64 = request.password
        ?.takeIf { it.isNotBlank() }
        ?.let {
            base64Service.encodeToString(it)
        }

    val authType = kotlin.run {
        request.authType?.toBitwarden()
            // infer the auth type
            ?: when {
                // new items
                newPasswordBase64 != null -> BitwardenSend.AuthType.Password
                request.emails.isNotEmpty() -> BitwardenSend.AuthType.Email
                // old items
                old?.password != null -> BitwardenSend.AuthType.Password
                !old?.emails.isNullOrEmpty() -> BitwardenSend.AuthType.Email
                else -> BitwardenSend.AuthType.None
            }
    }

    // Make sure we only take the email addresses that are valid.
    val emails = request.emails
        .filter { email ->
            val validation = validateEmail(email)
            validation == ValidationEmail.OK
        }
    if (authType == BitwardenSend.AuthType.Email && emails.isEmpty()) {
        val msg = "A Send must have at least one email for the 'Specific people' " +
                "access type!"
        throw IllegalStateException(msg)
    }

    // Make sure that the password is set
    if (authType == BitwardenSend.AuthType.Password && (newPasswordBase64 == null && old?.password == null)) {
        val msg = "A Send must have a password for the 'Password' access type!"
        throw IllegalStateException(msg)
    }

    val maxAccessCount = request.maxAccessCount
        ?.toIntOrNull()
    return BitwardenSend(
        accountId = accountId,
        sendId = cipherId,
        accessId = old?.accessId.orEmpty(),
        revisionDate = now,
        createdDate = createdDate,
        deletedDate = deletedDate,
        expirationDate = expirationDate,
        // service fields
        service = BitwardenService(
            remote = old?.service?.remote,
            deleted = false,
            version = BitwardenService.VERSION,
        ),
        // common
        keyBase64 = keyBase64,
        authType = authType,
        name = request.title,
        notes = request.note,
        maxAccessCount = maxAccessCount,
        // Doesn't make sense to make it anything but the old value,
        // because if it's a new item them no one has accessed it yet.
        accessCount = old?.accessCount ?: 0,
        // Password is only used to reflect the changed on remote.
        password = old?.password,
        changes = BitwardenSend.Changes(
            passwordBase64 = newPasswordBase64
                ?.let(BitwardenOptionalStringNullable::Some)
                ?: BitwardenOptionalStringNullable.None,
        ),
        disabled = request.disabled,
        hideEmail = request.hideEmail,
        emails = emails,
        // types
        type = type,
        text = text,
        file = file,
    )
}

private fun DSend.AuthType.toBitwarden() = when (this) {
    DSend.AuthType.None -> BitwardenSend.AuthType.None
    DSend.AuthType.Password -> BitwardenSend.AuthType.Password
    DSend.AuthType.Email -> BitwardenSend.AuthType.Email
}

private suspend fun BitwardenSend.Text.Companion.of(
    request: CreateSendRequest,
    old: BitwardenSend? = null,
): BitwardenSend.Text {
    return BitwardenSend.Text(
        text = request.text.text,
        hidden = request.text.hidden,
    )
}

private suspend fun BitwardenSend.File.Companion.of(
    request: CreateSendRequest,
    old: BitwardenSend? = null,
): BitwardenSend.File {
    return requireNotNull(old?.file) {
        "Creating a file send is not yet supported!"
    }
}
