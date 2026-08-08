package com.artemchep.keyguard.ipctestclient

import android.os.Parcel
import android.os.Parcelable
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.openintents.openpgp.OpenPgpDecryptionResult
import org.openintents.openpgp.OpenPgpError
import org.openintents.openpgp.OpenPgpMetadata
import org.openintents.openpgp.OpenPgpSignatureResult
import org.openintents.ssh.authentication.SshAuthenticationApiError

/**
 * The result parcelables both APIs put on the wire.
 *
 * Device-state free: this is the smoke test that still runs when no vault, no
 * key and no provider is available.
 */
@RunWith(AndroidJUnit4::class)
class ResultParcelableTest {
    @Test
    fun officialResultParcelablesRoundTrip() {
        val metadata = OpenPgpMetadata(
            "message.txt",
            "text/plain",
            MODIFICATION_TIME,
            ORIGINAL_SIZE,
            "UTF-8",
        ).roundTrip(OpenPgpMetadata.CREATOR)
        assertEquals("message.txt", metadata.filename)
        assertEquals("text/plain", metadata.mimeType)
        assertEquals("UTF-8", metadata.charset)
        assertEquals(ORIGINAL_SIZE, metadata.originalSize)

        val decryption = OpenPgpDecryptionResult(OpenPgpDecryptionResult.RESULT_ENCRYPTED)
            .roundTrip(OpenPgpDecryptionResult.CREATOR)
        assertEquals(OpenPgpDecryptionResult.RESULT_ENCRYPTED, decryption.result)
        assertFalse(decryption.hasDecryptedSessionKey())

        val signature = OpenPgpSignatureResult
            .createWithKeyMissing(KEY_ID, null)
            .roundTrip(OpenPgpSignatureResult.CREATOR)
        assertEquals(OpenPgpSignatureResult.RESULT_KEY_MISSING, signature.result)
        assertEquals(KEY_ID, signature.keyId)

        val openPgpError = OpenPgpError(OpenPgpError.INCOMPATIBLE_API_VERSIONS, "version")
            .roundTrip(OpenPgpError.CREATOR)
        assertEquals(OpenPgpError.INCOMPATIBLE_API_VERSIONS, openPgpError.errorId)

        val sshError = SshAuthenticationApiError(
            SshAuthenticationApiError.INVALID_HASH_ALGORITHM,
            "hash",
        ).roundTrip(SshAuthenticationApiError.CREATOR)
        assertEquals(SshAuthenticationApiError.INVALID_HASH_ALGORITHM, sshError.error)
    }

    private fun <T : Parcelable> T.roundTrip(creator: Parcelable.Creator<T>): T {
        val parcel = Parcel.obtain()
        return try {
            writeToParcel(parcel, 0)
            parcel.setDataPosition(0)
            creator.createFromParcel(parcel)
        } finally {
            parcel.recycle()
        }
    }

    private companion object {
        const val MODIFICATION_TIME = 1_700_000_000_000L
        const val ORIGINAL_SIZE = 42L
        const val KEY_ID = 0x0123_4567_89AB_CDEFL
    }
}
