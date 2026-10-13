@file:OptIn(ExperimentalSerializationApi::class)

package com.artemchep.keyguard.util.windowspasskeys

import kotlin.time.Duration
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import kotlinx.serialization.protobuf.ProtoBuf
import kotlinx.serialization.protobuf.ProtoIntegerType
import kotlinx.serialization.protobuf.ProtoNumber
import kotlinx.serialization.protobuf.ProtoOneOf
import kotlinx.serialization.protobuf.ProtoPacked
import kotlinx.serialization.protobuf.ProtoType

// Mirrors schema/windows_passkeys.proto. Fields default to their proto3 defaults, so an
// omitted field decodes and a default value is not encoded, as with the Rust side. The
// shared encodings in schema/fixtures/ pin both sides.

@Serializable
internal data object EmptyProto

@Serializable
internal data class CommandProto(
    @ProtoOneOf
    val command: CommandVariantProto,
)

@Serializable
internal sealed interface CommandVariantProto {
    @Serializable
    @SerialName("status")
    data class Status(@ProtoNumber(1) val value: EmptyProto) : CommandVariantProto

    @Serializable
    @SerialName("register")
    data class Register(@ProtoNumber(2) val value: EmptyProto) : CommandVariantProto

    @Serializable
    @SerialName("unregister")
    data class Unregister(@ProtoNumber(3) val value: EmptyProto) : CommandVariantProto

    @Serializable
    @SerialName("clear")
    data class Clear(@ProtoNumber(4) val value: EmptyProto) : CommandVariantProto

    @Serializable
    @SerialName("stop")
    data class Stop(@ProtoNumber(5) val value: EmptyProto) : CommandVariantProto

    @Serializable
    @SerialName("retire")
    data class Retire(@ProtoNumber(6) val value: EmptyProto) : CommandVariantProto

    @Serializable
    @SerialName("limits")
    data class Limits(@ProtoNumber(7) val value: EmptyProto) : CommandVariantProto

    @Serializable
    @SerialName("next")
    data class Next(@ProtoNumber(8) val value: NextCommandProto) : CommandVariantProto

    @Serializable
    @SerialName("set_locked")
    data class SetLocked(@ProtoNumber(9) val value: SetLockedCommandProto) : CommandVariantProto

    @Serializable
    @SerialName("complete")
    data class Complete(@ProtoNumber(10) val value: CompleteCommandProto) : CommandVariantProto

    @Serializable
    @SerialName("verify")
    data class Verify(@ProtoNumber(11) val value: VerifyCommandProto) : CommandVariantProto

    @Serializable
    @SerialName("sync")
    data class Sync(@ProtoNumber(12) val value: SyncCommandProto) : CommandVariantProto

    @Serializable
    @SerialName("start")
    data class Start(@ProtoNumber(13) val value: EmptyProto) : CommandVariantProto
}

@Serializable
internal data class NextCommandProto(
    @ProtoNumber(1)
    val active: Long? = null,
    @ProtoNumber(2)
    val timeoutMs: Long? = null,
)

@Serializable
internal data class SetLockedCommandProto(
    @ProtoNumber(1)
    val locked: Boolean = false,
)

@Serializable
internal data class CompleteCommandProto(
    @ProtoNumber(1)
    val id: Long = 0,
    @ProtoNumber(2)
    val response: ResponseProto? = null,
    @ProtoNumber(3)
    val failure: FailureProto = FailureProto.UNSPECIFIED,
)

@Serializable
internal data class VerifyCommandProto(
    @ProtoNumber(1)
    val id: Long = 0,
    @ProtoNumber(2)
    val window: Long = 0,
    @ProtoNumber(3)
    val username: String = "",
    @ProtoNumber(4)
    val hint: String = "",
    @ProtoNumber(5)
    val unlocked: Boolean = false,
)

@Serializable
internal data class SyncCommandProto(
    @ProtoNumber(1)
    val identities: List<IdentityProto> = emptyList(),
)

@Serializable
internal data class ReplyProto(
    @ProtoOneOf
    val outcome: ReplyOutcomeProto,
)

@Serializable
internal sealed interface ReplyOutcomeProto {
    @Serializable
    @SerialName("error")
    data class Error(
        @ProtoNumber(1)
        @ProtoType(ProtoIntegerType.SIGNED)
        val value: Int,
    ) : ReplyOutcomeProto

    @Serializable
    @SerialName("done")
    data class Done(@ProtoNumber(2) val value: EmptyProto) : ReplyOutcomeProto

    @Serializable
    @SerialName("status")
    data class Status(@ProtoNumber(3) val value: StatusProto) : ReplyOutcomeProto

    @Serializable
    @SerialName("event")
    data class Event(@ProtoNumber(4) val value: EventProto) : ReplyOutcomeProto

    @Serializable
    @SerialName("limits")
    data class Limits(@ProtoNumber(5) val value: LimitsProto) : ReplyOutcomeProto

    @Serializable
    @SerialName("retired")
    data class Retired(@ProtoNumber(6) val value: Boolean) : ReplyOutcomeProto
}

@Serializable
internal enum class StatusProto {
    @ProtoNumber(0)
    UNSPECIFIED,

    @ProtoNumber(1)
    UNSUPPORTED,

    @ProtoNumber(2)
    UNPACKAGED,

    @ProtoNumber(3)
    UNAVAILABLE,

    @ProtoNumber(4)
    UNREGISTERED,

    @ProtoNumber(5)
    DISABLED,

    @ProtoNumber(6)
    ENABLED,
}

@Serializable
internal enum class UserVerificationProto {
    @ProtoNumber(0)
    UNSPECIFIED,

    @ProtoNumber(1)
    REQUIRED,

    @ProtoNumber(2)
    PREFERRED,

    @ProtoNumber(3)
    DISCOURAGED,
}

@Serializable
internal enum class FailureProto {
    @ProtoNumber(0)
    UNSPECIFIED,

    @ProtoNumber(1)
    DISMISSED,

    @ProtoNumber(2)
    EXCLUDED,

    @ProtoNumber(3)
    NOT_FOUND,
}

@Serializable
internal data class EventProto(
    @ProtoNumber(1)
    val request: RequestProto? = null,
    @ProtoNumber(2)
    val activeId: Long? = null,
    @ProtoNumber(3)
    val closed: Boolean = false,
    @ProtoNumber(4)
    val watching: Boolean = false,
)

@Serializable
internal class RequestProto(
    @ProtoNumber(1)
    val id: Long = 0,
    @ProtoNumber(2)
    val create: Boolean = false,
    @ProtoNumber(3)
    val window: Long = 0,
    @ProtoNumber(4)
    val rpId: String = "",
    @ProtoNumber(5)
    val clientDataHash: ByteArray = byteArrayOf(),
    @ProtoNumber(6)
    val userVerification: UserVerificationProto = UserVerificationProto.UNSPECIFIED,
    @ProtoNumber(7)
    val credentials: List<DescriptorProto> = emptyList(),
    @ProtoNumber(8)
    val user: UserProto? = null,
    @ProtoNumber(9)
    val rpName: String? = null,
    @ProtoNumber(10)
    @ProtoPacked
    val algorithms: List<Int> = emptyList(),
    @ProtoNumber(11)
    val discoverable: Boolean = false,
    @ProtoNumber(12)
    val expiresInMs: Long = 0,
)

@Serializable
internal class DescriptorProto(
    @ProtoNumber(1)
    val id: ByteArray = byteArrayOf(),
    @ProtoNumber(2)
    val type: String = "",
)

@Serializable
internal class UserProto(
    @ProtoNumber(1)
    val id: ByteArray = byteArrayOf(),
    @ProtoNumber(2)
    val name: String = "",
    @ProtoNumber(3)
    val displayName: String = "",
)

@Serializable
internal class ResponseProto(
    @ProtoNumber(1)
    val authenticatorData: ByteArray = byteArrayOf(),
    @ProtoNumber(2)
    val credentialId: ByteArray? = null,
    @ProtoNumber(3)
    val signature: ByteArray? = null,
    @ProtoNumber(4)
    val userHandle: ByteArray? = null,
)

@Serializable
internal class IdentityProto(
    @ProtoNumber(1)
    val credentialId: ByteArray = byteArrayOf(),
    @ProtoNumber(2)
    val rpId: String = "",
    @ProtoNumber(3)
    val rpName: String = "",
    @ProtoNumber(4)
    val userId: ByteArray = byteArrayOf(),
    @ProtoNumber(5)
    val userName: String = "",
    @ProtoNumber(6)
    val userDisplayName: String = "",
)

@Serializable
internal data class LimitsProto(
    @ProtoNumber(1)
    val credentialIdBytes: Int = 0,
    @ProtoNumber(2)
    val userIdBytes: Int = 0,
    @ProtoNumber(3)
    val textBytes: Int = 0,
)

/** The commands of [WindowsPasskeys], built from its arguments. */
internal object WindowsPasskeyCommands {
    val status = command(CommandVariantProto.Status(EmptyProto))
    val register = command(CommandVariantProto.Register(EmptyProto))
    val unregister = command(CommandVariantProto.Unregister(EmptyProto))
    val clear = command(CommandVariantProto.Clear(EmptyProto))
    val start = command(CommandVariantProto.Start(EmptyProto))
    val stop = command(CommandVariantProto.Stop(EmptyProto))
    val retire = command(CommandVariantProto.Retire(EmptyProto))
    val limits = command(CommandVariantProto.Limits(EmptyProto))

    fun next(active: Long?, timeout: Duration?) = command(
        CommandVariantProto.Next(NextCommandProto(active, timeout?.inWholeMilliseconds)),
    )

    fun setLocked(locked: Boolean) = command(
        CommandVariantProto.SetLocked(SetLockedCommandProto(locked)),
    )

    fun complete(id: Long, response: WindowsPasskeyResponse?, failure: WindowsPasskeyFailure?) = command(
        CommandVariantProto.Complete(
            CompleteCommandProto(id, response?.toProto(), failure.toProto()),
        ),
    )

    fun verify(id: Long, window: Long, username: String, hint: String, unlocked: Boolean) = command(
        CommandVariantProto.Verify(VerifyCommandProto(id, window, username, hint, unlocked)),
    )

    fun sync(identities: List<WindowsPasskeyIdentity>) = command(
        CommandVariantProto.Sync(SyncCommandProto(identities.map { it.toProto() })),
    )

    private fun command(variant: CommandVariantProto) = CommandProto(variant)
}

internal fun encodeCommand(command: CommandProto): ByteArray = ProtoBuf.encodeToByteArray(command)

/** The outcome of a successful command; a failed one throws a [WindowsPasskeyException]. */
internal fun decodeReply(response: ByteArray): ReplyOutcomeProto {
    val outcome = decodeOutcome(response) ?: throw bridgeFailure()
    if (outcome is ReplyOutcomeProto.Error) throw WindowsPasskeyException(outcome.value)
    return outcome
}

/**
 * The shared JNI helper frames every reply: a zero status byte and the core's [ReplyProto],
 * or a single non-zero status byte when it rejected the command or the core failed. Null
 * for the latter, or for a malformed reply.
 */
private fun decodeOutcome(response: ByteArray): ReplyOutcomeProto? {
    if (response.firstOrNull() != 0.toByte()) return null
    return try {
        ProtoBuf.decodeFromByteArray<ReplyProto>(response.copyOfRange(1, response.size)).outcome
    } catch (_: SerializationException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }
}

/** The [T] outcome a command replies with on success. */
internal inline fun <reified T : ReplyOutcomeProto> ReplyOutcomeProto.expect(): T =
    this as? T ?: throw bridgeFailure()

/** The native bridge failed, or its reply is malformed. */
internal fun bridgeFailure() = WindowsPasskeyException(WindowsPasskeyException.FAILED)

internal fun StatusProto.toModel() = when (this) {
    StatusProto.UNSUPPORTED -> WindowsPasskeyStatus.UNSUPPORTED
    StatusProto.UNPACKAGED -> WindowsPasskeyStatus.UNPACKAGED
    StatusProto.UNAVAILABLE -> WindowsPasskeyStatus.UNAVAILABLE
    StatusProto.UNREGISTERED -> WindowsPasskeyStatus.UNREGISTERED
    StatusProto.DISABLED -> WindowsPasskeyStatus.DISABLED
    StatusProto.ENABLED -> WindowsPasskeyStatus.ENABLED
    StatusProto.UNSPECIFIED -> throw bridgeFailure()
}

internal fun EventProto.toModel() = WindowsPasskeyEvent(
    request = request?.toModel(),
    activeId = activeId,
    closed = closed,
    watching = watching,
)

private fun RequestProto.toModel() = WindowsPasskeyRequest(
    id = id,
    create = create,
    window = window,
    rpId = rpId,
    clientDataHash = clientDataHash,
    userVerification = when (userVerification) {
        UserVerificationProto.REQUIRED -> "required"
        UserVerificationProto.DISCOURAGED -> "discouraged"
        // WebAuthn's default.
        UserVerificationProto.PREFERRED,
        UserVerificationProto.UNSPECIFIED,
        -> "preferred"
    },
    credentials = credentials.map { WindowsPasskeyDescriptor(id = it.id, type = it.type) },
    user = user?.let { WindowsPasskeyUser(id = it.id, name = it.name, displayName = it.displayName) },
    rpName = rpName,
    algorithms = algorithms,
    discoverable = discoverable,
    expiresInMs = expiresInMs,
)

private fun WindowsPasskeyResponse.toProto() = ResponseProto(
    authenticatorData = authenticatorData,
    credentialId = credentialId,
    signature = signature,
    userHandle = userHandle,
)

private fun WindowsPasskeyFailure?.toProto() = when (this) {
    null -> FailureProto.UNSPECIFIED
    WindowsPasskeyFailure.DISMISSED -> FailureProto.DISMISSED
    WindowsPasskeyFailure.EXCLUDED -> FailureProto.EXCLUDED
    WindowsPasskeyFailure.NOT_FOUND -> FailureProto.NOT_FOUND
}

private fun WindowsPasskeyIdentity.toProto() = IdentityProto(
    credentialId = credentialId,
    rpId = rpId,
    rpName = rpName,
    userId = userId,
    userName = userName,
    userDisplayName = userDisplayName,
)
