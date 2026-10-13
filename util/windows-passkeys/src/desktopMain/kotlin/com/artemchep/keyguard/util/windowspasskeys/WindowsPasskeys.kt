package com.artemchep.keyguard.util.windowspasskeys

import com.artemchep.keyguard.util.ffi.JniLibrary
import com.artemchep.keyguard.util.ffi.runNativeOperation
import kotlin.time.Duration

enum class WindowsPasskeyStatus {
    UNSUPPORTED,
    UNPACKAGED,
    UNREGISTERED,
    DISABLED,
    ENABLED,
    UNAVAILABLE,
    ;

    val isRegistered get() = this == DISABLED || this == ENABLED
}

/**
 * A [code] is a failure HRESULT from Windows or one of the bridge's own codes below. The
 * shared reply fixtures pin each named code to the native bridge.
 */
class WindowsPasskeyException(val code: Int) : Exception("Windows passkey provider failed ($code)") {
    companion object {
        /** The bridge failed, or it or Keyguard sent a malformed message. */
        const val FAILED = -1

        /** Windows has no passkey provider API, or this build has no native bridge. */
        const val UNSUPPORTED = -2

        /** The process does not run from its MSIX package. */
        const val UNPACKAGED = -3

        /** The request has ended: it was answered, cancelled, or expired. Windows has its answer. */
        const val CANCELLED = 0x800704C7.toInt()

        /** The user closed the Windows Hello prompt. */
        const val DISMISSED = 0x80090036.toInt()
    }
}

// The binary fields compare by content, so equal values are equal models.

data class WindowsPasskeyDescriptor(val id: ByteArray, val type: String) {
    override fun equals(other: Any?) = other is WindowsPasskeyDescriptor &&
        id.contentEquals(other.id) &&
        type == other.type

    override fun hashCode() = 31 * id.contentHashCode() + type.hashCode()
}

data class WindowsPasskeyUser(val id: ByteArray, val name: String, val displayName: String) {
    override fun equals(other: Any?) = other is WindowsPasskeyUser &&
        id.contentEquals(other.id) &&
        name == other.name &&
        displayName == other.displayName

    override fun hashCode() = listOf(id.contentHashCode(), name, displayName).hashCode()
}

data class WindowsPasskeyRequest(
    val id: Long,
    val create: Boolean,
    val window: Long,
    val rpId: String,
    val clientDataHash: ByteArray,
    val userVerification: String,
    val credentials: List<WindowsPasskeyDescriptor>,
    val user: WindowsPasskeyUser?,
    val rpName: String?,
    val algorithms: List<Int>,
    val discoverable: Boolean,
    /** The time left before Windows stops waiting, as of this request's delivery. */
    val expiresInMs: Long,
) {
    override fun equals(other: Any?) = other is WindowsPasskeyRequest &&
        id == other.id &&
        create == other.create &&
        window == other.window &&
        rpId == other.rpId &&
        clientDataHash.contentEquals(other.clientDataHash) &&
        userVerification == other.userVerification &&
        credentials == other.credentials &&
        user == other.user &&
        rpName == other.rpName &&
        algorithms == other.algorithms &&
        discoverable == other.discoverable &&
        expiresInMs == other.expiresInMs

    override fun hashCode() = listOf(
        id, create, window, rpId, clientDataHash.contentHashCode(), userVerification,
        credentials, user, rpName, algorithms, discoverable, expiresInMs,
    ).hashCode()
}

data class WindowsPasskeyResponse(
    val authenticatorData: ByteArray,
    val credentialId: ByteArray? = null,
    val signature: ByteArray? = null,
    val userHandle: ByteArray? = null,
) {
    override fun equals(other: Any?) = other is WindowsPasskeyResponse &&
        authenticatorData.contentEquals(other.authenticatorData) &&
        credentialId.contentEquals(other.credentialId) &&
        signature.contentEquals(other.signature) &&
        userHandle.contentEquals(other.userHandle)

    override fun hashCode() = listOf(
        authenticatorData.contentHashCode(),
        credentialId.contentHashCode(),
        signature.contentHashCode(),
        userHandle.contentHashCode(),
    ).hashCode()
}

/** Why a request ends without a response. Windows reports it to the site. */
enum class WindowsPasskeyFailure {
    /** The user dismissed the request. */
    DISMISSED,

    /** The vault holds a passkey that the site excludes. */
    EXCLUDED,

    /** The vault holds none of the passkeys that the site asked for. */
    NOT_FOUND,
}

/** Only public metadata may be passed here. Windows retains it while the vault is locked. */
data class WindowsPasskeyIdentity(
    val credentialId: ByteArray,
    val rpId: String,
    val rpName: String,
    val userId: ByteArray,
    val userName: String,
    val userDisplayName: String,
) {
    override fun equals(other: Any?) = other is WindowsPasskeyIdentity &&
        credentialId.contentEquals(other.credentialId) &&
        rpId == other.rpId &&
        rpName == other.rpName &&
        userId.contentEquals(other.userId) &&
        userName == other.userName &&
        userDisplayName == other.userDisplayName

    override fun hashCode() = listOf(
        credentialId.contentHashCode(), rpId, rpName, userId.contentHashCode(), userName, userDisplayName,
    ).hashCode()
}

/**
 * The [request] that Windows is still waiting on, until it is passed back as known; then
 * only its [activeId].
 * While [watching], Windows reports status changes, which then end the wait.
 */
data class WindowsPasskeyEvent(
    val request: WindowsPasskeyRequest?,
    val activeId: Long?,
    val closed: Boolean = false,
    val watching: Boolean = false,
)

/**
 * The provider calls of a running process. [WindowsPasskeys] is the native implementation.
 * Cancelling a caller cancels its native call, which then discards its outcome.
 */
interface WindowsPasskeyBridge {
    suspend fun readStatus(): WindowsPasskeyStatus

    suspend fun register()

    suspend fun unregister()

    suspend fun clear()

    /**
     * Waits until a new request, the end of the [active] request, a provider status change,
     * a stop, or the [timeout]; without one, until any of the others. Cancelling the caller
     * ends the wait at once.
     */
    suspend fun next(active: Long?, timeout: Duration?): WindowsPasskeyEvent

    suspend fun setLocked(locked: Boolean)

    /**
     * Answers the request with the [response], or else ends it with the [failure]. Without
     * either, Keyguard abandoned the request. A [response] needs a [verify] first: otherwise
     * the request ends, and this fails.
     */
    suspend fun complete(id: Long, response: WindowsPasskeyResponse?, failure: WindowsPasskeyFailure? = null)

    /**
     * Verifies the user for the request with Windows Hello. It has no dismiss API, so this
     * returns only once the prompt closes, even for a cancelled caller. Ending the request
     * invalidates the result of a prompt that is still open. When the user [unlocked] the
     * vault during this request, that verified them, and no prompt is shown.
     */
    suspend fun verify(id: Long, window: Long, username: String, hint: String, unlocked: Boolean = false)

    suspend fun sync(identities: List<WindowsPasskeyIdentity>)

    /**
     * Serves Windows' requests until a stop or [retire]; serving already is no change. Only
     * this starts serving, and it can be called again after a failed start.
     */
    suspend fun serve()

    /**
     * Stops serving, but only if Windows is not waiting on a request. Then COM starts a new
     * process for the next request, so the caller should exit. False means a request is live.
     */
    suspend fun retire(): Boolean
}

/**
 * The process's native COM server. [start] it before the desktop UI or vault is created, and
 * before anything else in the process uses COM: the first native call sets the COM security
 * of the whole process.
 */
@Suppress("TooManyFunctions")
object WindowsPasskeys : WindowsPasskeyBridge {
    val isWindows = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)

    /** Blocks; [serve] for callers outside a coroutine. */
    fun start() {
        if (isWindows) invoke(WindowsPasskeyCommands.start).expect<ReplyOutcomeProto.Done>()
    }

    /** Blocks; reads [WindowsPasskeyStatus.UNSUPPORTED] off Windows without loading the library. */
    fun status(): WindowsPasskeyStatus {
        if (!isWindows) return WindowsPasskeyStatus.UNSUPPORTED
        return invoke(WindowsPasskeyCommands.status).expect<ReplyOutcomeProto.Status>().value.toModel()
    }

    override suspend fun readStatus(): WindowsPasskeyStatus {
        if (!isWindows) return WindowsPasskeyStatus.UNSUPPORTED
        return operation(WindowsPasskeyCommands.status).expect<ReplyOutcomeProto.Status>().value.toModel()
    }

    override suspend fun register() = command(WindowsPasskeyCommands.register)

    override suspend fun unregister() = command(WindowsPasskeyCommands.unregister)

    override suspend fun clear() = command(WindowsPasskeyCommands.clear)

    override suspend fun next(active: Long?, timeout: Duration?): WindowsPasskeyEvent =
        operation(WindowsPasskeyCommands.next(active, timeout)).expect<ReplyOutcomeProto.Event>().value.toModel()

    override suspend fun setLocked(locked: Boolean) = command(WindowsPasskeyCommands.setLocked(locked))

    override suspend fun complete(id: Long, response: WindowsPasskeyResponse?, failure: WindowsPasskeyFailure?) =
        command(WindowsPasskeyCommands.complete(id, response, failure))

    override suspend fun verify(id: Long, window: Long, username: String, hint: String, unlocked: Boolean) =
        command(WindowsPasskeyCommands.verify(id, window, username, hint, unlocked))

    override suspend fun sync(identities: List<WindowsPasskeyIdentity>) =
        command(WindowsPasskeyCommands.sync(identities))

    override suspend fun serve() {
        if (isWindows) command(WindowsPasskeyCommands.start)
    }

    fun stop() {
        if (isWindows) invoke(WindowsPasskeyCommands.stop).expect<ReplyOutcomeProto.Done>()
    }

    override suspend fun retire(): Boolean {
        if (!isWindows) return true
        return operation(WindowsPasskeyCommands.retire).expect<ReplyOutcomeProto.Retired>().value
    }
}

/** Also covers incompatible changes to schema/windows_passkeys.proto. */
internal const val WINDOWS_PASSKEYS_ABI_VERSION = 6

internal val library = JniLibrary(
    name = "keyguard_windows_passkeys_jni",
    pathProperty = "keyguard.nativeWindowsPasskeys.libraryPath",
    unavailable = { WindowsPasskeyException(WindowsPasskeyException.UNSUPPORTED) },
    verify = { check(NativeWindowsPasskeys.abiVersion() == WINDOWS_PASSKEYS_ABI_VERSION) },
)

/** Answered on every platform, without a provider. */
internal fun nativeLimits(): LimitsProto =
    invoke(WindowsPasskeyCommands.limits).expect<ReplyOutcomeProto.Limits>().value

private suspend fun command(command: CommandProto) {
    operation(command).expect<ReplyOutcomeProto.Done>()
}

/** Runs the [command] as a native operation of its own; cancelling the caller cancels it. */
private suspend fun operation(command: CommandProto): ReplyOutcomeProto {
    val request = encodeCommand(command)
    return runNativeOperation(
        create = ::createOperation,
        cancel = NativeWindowsPasskeys::cancel,
        close = NativeWindowsPasskeys::close,
        // Too many operations are live.
        busy = ::bridgeFailure,
        // The bridge carries public metadata and signatures only, so its buffers are not wiped.
        clear = {},
    ) { handle -> decodeReply(NativeWindowsPasskeys.execute(handle, request)) }
}

/** Blocks; for callers outside a coroutine, which cannot cancel. */
private fun invoke(command: CommandProto): ReplyOutcomeProto {
    val handle = createOperation()
    if (handle == 0L) throw bridgeFailure()
    try {
        return decodeReply(NativeWindowsPasskeys.execute(handle, encodeCommand(command)))
    } finally {
        NativeWindowsPasskeys.close(handle)
    }
}

private fun createOperation(): Long {
    library.ensureLoaded()
    return NativeWindowsPasskeys.create()
}

/** The operation protocol of util/ffi, which the fido2 and yubikey bridges also use. */
internal object NativeWindowsPasskeys {
    external fun abiVersion(): Int

    /** Zero while too many operations are live. */
    external fun create(): Long

    external fun cancel(handle: Long)

    external fun close(handle: Long)

    external fun execute(handle: Long, request: ByteArray): ByteArray
}
