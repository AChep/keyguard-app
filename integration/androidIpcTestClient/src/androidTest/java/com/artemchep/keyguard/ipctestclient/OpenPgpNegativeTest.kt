package com.artemchep.keyguard.ipctestclient

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.artemchep.keyguard.ipctestclient.ipc.IpcExchange
import com.artemchep.keyguard.ipctestclient.ipc.OpenPgpOperation
import com.artemchep.keyguard.ipctestclient.ipc.OpenPgpRequestSpec
import com.artemchep.keyguard.ipctestclient.ipc.openPgpErrorName
import com.artemchep.keyguard.ipctestclient.support.KeyguardProviderRule
import com.artemchep.keyguard.ipctestclient.support.assertOpenPgpError
import com.artemchep.keyguard.ipctestclient.support.requireResult
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.openintents.openpgp.OpenPgpError
import org.openintents.openpgp.util.OpenPgpApi

/**
 * The rejection paths.
 *
 * Every case here is decided before the caller is admitted, so none of these
 * tests needs a registration, a key or an approval - which makes this the part
 * of the suite that never skips.
 */
@RunWith(AndroidJUnit4::class)
class OpenPgpNegativeTest {
    @get:Rule
    val provider = KeyguardProviderRule()

    @Test
    fun apiVersionsOutsideTheSupportedRangeAreRejected() {
        val versions = listOf(
            OpenPgpOperation.MIN_API_VERSION - 1,
            OpenPgpOperation.MAX_API_VERSION + 1,
            null,
        )
        versions.forEach { version ->
            send(
                OpenPgpRequestSpec(
                    operation = OpenPgpOperation.CHECK_PERMISSION,
                    apiVersion = version,
                ),
            ).assertOpenPgpError(
                errorId = OpenPgpError.INCOMPATIBLE_API_VERSIONS,
                messageContains = "Supported OpenPGP API versions",
            )
        }
    }

    @Test
    fun theApiVersionRangeBoundsAreAccepted() {
        listOf(
            OpenPgpOperation.MIN_API_VERSION,
            OpenPgpOperation.MAX_API_VERSION,
        ).forEach { version ->
            assertNotIncompatible(
                send(
                    OpenPgpRequestSpec(
                        operation = OpenPgpOperation.CHECK_PERMISSION,
                        apiVersion = version,
                    ),
                ),
            )
        }
    }

    /** BACKUP, UPDATE_AUTOCRYPT_PEER and AUTOCRYPT_KEY_TRANSFER. */
    @Test
    fun actionsKeyguardDoesNotImplementAreRejected() {
        OpenPgpOperation.UNSUPPORTED.forEach { operation ->
            send(OpenPgpRequestSpec(operation)).assertOpenPgpError(
                errorId = OpenPgpError.GENERIC_ERROR,
                messageContains = "Unsupported OpenPGP action.",
            )
        }
    }

    @Test
    fun anUnknownActionIsRejected() {
        send(
            OpenPgpRequestSpec(
                operation = OpenPgpOperation.CHECK_PERMISSION,
                actionOverride = "org.openintents.openpgp.action.NOT_A_REAL_ACTION",
            ),
        ).assertOpenPgpError(
            errorId = OpenPgpError.GENERIC_ERROR,
            messageContains = "Unsupported OpenPGP action.",
        )
    }

    @Test
    fun aRequestWithNoActionIsRejected() {
        send(
            OpenPgpRequestSpec(
                operation = OpenPgpOperation.CHECK_PERMISSION,
                omitAction = true,
            ),
        ).assertOpenPgpError(
            errorId = OpenPgpError.GENERIC_ERROR,
            messageContains = "A supported action is required.",
        )
    }

    /** Keyguard cannot honour either extra, so it refuses rather than ignores. */
    @Test
    fun customHeadersAndMinimizeAreRejectedOutright() {
        listOf(
            OpenPgpRequestSpec(OpenPgpOperation.CHECK_PERMISSION, customHeaders = true),
            OpenPgpRequestSpec(OpenPgpOperation.CHECK_PERMISSION, minimize = true),
        ).forEach {
            send(it).assertOpenPgpError(
                errorId = OpenPgpError.GENERIC_ERROR,
                messageContains = "invalid or unsupported extras",
            )
        }
    }

    @Test
    fun oversizedRecipientExtrasAreRejected() {
        listOf(
            OpenPgpRequestSpec(
                operation = OpenPgpOperation.QUERY_AUTOCRYPT_STATUS,
                userIds = List(MAX_USER_IDS + 1) { "user$it@example.invalid" },
            ),
            OpenPgpRequestSpec(
                operation = OpenPgpOperation.QUERY_AUTOCRYPT_STATUS,
                userIds = listOf("a".repeat(MAX_USER_ID_LENGTH + 1)),
            ),
            OpenPgpRequestSpec(
                operation = OpenPgpOperation.QUERY_AUTOCRYPT_STATUS,
                keyIds = List(MAX_KEY_IDS + 1) { it.toLong() + 1L },
            ),
        ).forEach {
            send(it).assertOpenPgpError(
                errorId = OpenPgpError.GENERIC_ERROR,
                messageContains = "invalid or unsupported extras",
            )
        }
    }

    @Test
    fun oversizedPayloadExtrasAreRejected() {
        listOf(
            OpenPgpRequestSpec(
                operation = OpenPgpOperation.ENCRYPT,
                originalFilename = "f".repeat(MAX_FILENAME_LENGTH + 1),
            ),
            // The provider also caps a detached signature at 1 MiB, but a
            // request that big does not survive the Binder transaction buffer,
            // so that bound is not reachable from a client at all.
            OpenPgpRequestSpec(
                operation = OpenPgpOperation.DECRYPT_VERIFY,
                detachedSignature = ByteArray(0),
            ),
        ).forEach {
            send(it).assertOpenPgpError(
                errorId = OpenPgpError.GENERIC_ERROR,
                messageContains = "invalid or unsupported extras",
            )
        }
    }

    /** A detached signature only means something to the two decrypt actions. */
    @Test
    fun aDetachedSignatureOnANonDecryptActionIsRejected() {
        send(
            OpenPgpRequestSpec(
                operation = OpenPgpOperation.DETACHED_SIGN,
                detachedSignature = "not a signature".encodeToByteArray(),
            ),
        ).assertOpenPgpError(
            errorId = OpenPgpError.GENERIC_ERROR,
            messageContains = "extras for another action",
        )
    }

    private fun send(spec: OpenPgpRequestSpec) = provider.openPgpRunner().runOnce(spec)

    @Suppress("DEPRECATION")
    private fun assertNotIncompatible(exchange: IpcExchange) {
        val error = exchange
            .requireResult()
            .getParcelableExtra<OpenPgpError>(OpenPgpApi.RESULT_ERROR)
            ?: return
        assertNotEquals(
            openPgpErrorName(OpenPgpError.INCOMPATIBLE_API_VERSIONS),
            openPgpErrorName(error.errorId),
        )
    }

    private companion object {
        const val MAX_USER_IDS = 64
        const val MAX_USER_ID_LENGTH = 320
        const val MAX_KEY_IDS = 64
        const val MAX_FILENAME_LENGTH = 255
    }
}
