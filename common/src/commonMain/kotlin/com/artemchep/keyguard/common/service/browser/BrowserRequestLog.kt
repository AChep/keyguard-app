package com.artemchep.keyguard.common.service.browser

import com.artemchep.keyguard.common.io.throwIfFatalOrCancellation
import com.artemchep.keyguard.common.model.CliAgentUsageDetails
import com.artemchep.keyguard.common.model.CliAgentUsageHistoryClient
import com.artemchep.keyguard.common.model.CliAgentUsageHistoryRequestType
import com.artemchep.keyguard.common.service.agent.CallerIdentity
import com.artemchep.keyguard.common.service.agent.sanitizedAgentDisplayValue
import com.artemchep.keyguard.common.service.cli.agent.CliAgentUsageRecord
import com.artemchep.keyguard.common.service.cli.agent.CliAgentUsageRecorder
import com.artemchep.keyguard.common.service.logging.LogLevel
import com.artemchep.keyguard.common.service.logging.LogRepository
import com.artemchep.keyguard.feature.browserextension.protocol.BrowserError
import com.artemchep.keyguard.feature.browserextension.protocol.BrowserReply
import com.artemchep.keyguard.feature.browserextension.protocol.BrowserRequestException
import com.artemchep.keyguard.feature.cli.MAX_CLI_COMMAND_DISPLAY_LENGTH
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** The request log of the sessions of the extensions. App-scoped. */
internal class BrowserRequestLog(
    /** Writes the request log; `null` keeps none. */
    private val recorder: CliAgentUsageRecorder?,
    private val scope: CoroutineScope,
    private val logRepository: LogRepository,
) {
    /**
     * Writes a request of [session] to the request log: [request] failed
     * with the [error], succeeded for `null`, or was [cancelled] when the
     * session ended before it was answered.
     *
     * @param pairing The pairing of the extension, `null` before it pairs.
     * @param cipherId The item the request reached.
     * @param origin The origin of the page.
     */
    @Suppress("LongParameterList")
    fun record(
        session: BrowserExtensionSession,
        pairing: BrowserPairing?,
        request: CliAgentUsageHistoryRequestType,
        cipherId: String? = null,
        origin: String? = null,
        error: BrowserError? = null,
        cancelled: Boolean = false,
    ) {
        val recorder = recorder
            ?: return
        val usage = CliAgentUsageRecord(
            client = CliAgentUsageHistoryClient.BROWSER,
            request = request,
            caller = session.caller.toCallerIdentity(),
            // The name when the request was made; the id outlives a rename.
            clientName = pairing?.name,
            clientId = pairing?.id,
            details = CliAgentUsageDetails.Browser(
                extensionOrigin = session.origin
                    .sanitizedAgentDisplayValue(MAX_CLI_COMMAND_DISPLAY_LENGTH)
                    .orEmpty(),
                pageOrigin = origin
                    .sanitizedAgentDisplayValue(MAX_CLI_COMMAND_DISPLAY_LENGTH),
            ),
        )
        usage.cipherId = cipherId
        scope.launch {
            recorder.record(usage, errorCode = error?.code, cancelled = cancelled)
        }
    }

    /**
     * Answers a request of the [session] with [block], and records the answer
     * of the [type] in the request log. A [BrowserRequestException] of [block]
     * answers with its error; any other failure answers `not_allowed`, so
     * that the session goes on.
     *
     * @param privateWindow The request comes from a private window: the log keeps nothing of it.
     */
    @Suppress("TooGenericExceptionCaught") // An unexpected failure must not end the session.
    suspend fun recorded(
        session: () -> BrowserExtensionSession,
        pairing: () -> BrowserPairing?,
        type: CliAgentUsageHistoryRequestType,
        privateWindow: Boolean = false,
        block: suspend (BrowserUsage) -> BrowserReply,
    ): BrowserReply {
        val usage = BrowserUsage()
        var reply: BrowserReply? = null
        try {
            val answer = try {
                block(usage)
            } catch (e: BrowserRequestException) {
                BrowserReply.Error(e.code)
            } catch (e: Exception) {
                e.throwIfFatalOrCancellation()
                // The class only: the message may hold a secret.
                logRepository.post(TAG, "Failed to answer a request: ${e::class.simpleName}", LogLevel.ERROR)
                BrowserReply.Error(BrowserError.NOT_ALLOWED)
            }
            return answer.also { reply = it }
        } finally {
            if (!privateWindow) {
                record(
                    session = session(),
                    pairing = pairing(),
                    request = type,
                    cipherId = usage.cipherId,
                    origin = usage.origin,
                    error = (reply as? BrowserReply.Error)?.error,
                    // The session ended before the request was answered.
                    cancelled = reply == null,
                )
            }
        }
    }

    private companion object {
        const val TAG = "BrowserRequestLog"
    }
}

/** What the request log records of a request. */
internal class BrowserUsage {
    var cipherId: String? = null
    var origin: String? = null
}

/** The browser, as the request log records the caller and the prompts show it. */
internal fun BrowserCaller.toCallerIdentity(): CallerIdentity = CallerIdentity(
    pid = pid,
    processName = processName,
    executablePath = executablePath,
    appName = appName,
    appBundlePath = appBundlePath,
)
