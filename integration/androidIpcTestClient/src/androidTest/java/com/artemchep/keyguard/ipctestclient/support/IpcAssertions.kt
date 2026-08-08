package com.artemchep.keyguard.ipctestclient.support

import android.content.Intent
import com.artemchep.keyguard.ipctestclient.ipc.IpcExchange
import com.artemchep.keyguard.ipctestclient.ipc.IpcExchangeLog
import com.artemchep.keyguard.ipctestclient.ipc.describe
import com.artemchep.keyguard.ipctestclient.ipc.openPgpErrorName
import com.artemchep.keyguard.ipctestclient.ipc.openPgpResultCodeName
import com.artemchep.keyguard.ipctestclient.ipc.sshErrorName
import com.artemchep.keyguard.ipctestclient.ipc.sshResultCodeName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.openintents.openpgp.OpenPgpError
import org.openintents.openpgp.util.OpenPgpApi
import org.openintents.ssh.authentication.SshAuthenticationApi
import org.openintents.ssh.authentication.SshAuthenticationApiError

private const val LOG_TAIL_ENTRIES = 3

/** Fails with the tail of the exchange log, so the failure explains itself. */
fun failWithExchangeLog(message: String): Nothing =
    throw AssertionError("$message\n\n${IpcExchangeLog.tail(LOG_TAIL_ENTRIES)}")

fun IpcExchange.requireResult(): Intent = result
    ?: failWithExchangeLog("The provider returned no result\n${describe()}")

fun IpcExchange.requireOpenPgpSuccess(): Intent {
    val result = requireResult()
    val code = result.getIntExtra(OpenPgpApi.RESULT_CODE, IpcExchange.UNKNOWN_RESULT_CODE)
    if (code != OpenPgpApi.RESULT_CODE_SUCCESS) {
        failWithExchangeLog(
            "Expected SUCCESS but got ${openPgpResultCodeName(code)}\n${describe()}",
        )
    }
    return result
}

fun IpcExchange.requireSshSuccess(): Intent {
    val result = requireResult()
    val code = result.getIntExtra(
        SshAuthenticationApi.EXTRA_RESULT_CODE,
        IpcExchange.UNKNOWN_RESULT_CODE,
    )
    if (code != SshAuthenticationApi.RESULT_CODE_SUCCESS) {
        failWithExchangeLog(
            "Expected SUCCESS but got ${sshResultCodeName(code)}\n${describe()}",
        )
    }
    return result
}

@Suppress("DEPRECATION")
fun IpcExchange.assertOpenPgpError(errorId: Int, messageContains: String? = null) {
    val result = requireResult()
    assertEquals(
        "result code\n${describe()}",
        OpenPgpApi.RESULT_CODE_ERROR,
        result.getIntExtra(OpenPgpApi.RESULT_CODE, IpcExchange.UNKNOWN_RESULT_CODE),
    )
    val error = result.getParcelableExtra<OpenPgpError>(OpenPgpApi.RESULT_ERROR)
        ?: failWithExchangeLog("No OpenPgpError in the result\n${describe()}")
    assertEquals(
        "error id (message was \"${error.message}\")",
        openPgpErrorName(errorId),
        openPgpErrorName(error.errorId),
    )
    messageContains?.let {
        assertTrue(
            "Expected the message to contain \"$it\" but it was \"${error.message}\"",
            error.message.orEmpty().contains(it),
        )
    }
}

@Suppress("DEPRECATION")
fun IpcExchange.assertSshError(errorCode: Int, messageContains: String? = null) {
    val result = requireResult()
    assertEquals(
        "result code\n${describe()}",
        SshAuthenticationApi.RESULT_CODE_ERROR,
        result.getIntExtra(
            SshAuthenticationApi.EXTRA_RESULT_CODE,
            IpcExchange.UNKNOWN_RESULT_CODE,
        ),
    )
    val error = result
        .getParcelableExtra<SshAuthenticationApiError>(SshAuthenticationApi.EXTRA_ERROR)
        ?: failWithExchangeLog("No SshAuthenticationApiError in the result\n${describe()}")
    assertEquals(
        "error code (message was \"${error.message}\")",
        sshErrorName(errorCode),
        sshErrorName(error.error),
    )
    messageContains?.let {
        assertTrue(
            "Expected the message to contain \"$it\" but it was \"${error.message}\"",
            error.message.orEmpty().contains(it),
        )
    }
}
