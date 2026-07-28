package db_key_value.datastore.encrypted

import android.security.KeyStoreException
import com.google.crypto.tink.shaded.protobuf.InvalidProtocolBufferException
import kotlinx.coroutines.CancellationException
import java.io.CharConversionException
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.ProviderException
import javax.crypto.AEADBadTagException
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SecureStorageFailureClassifierTest {
    @Test
    fun `ciphertext authentication failure is a wipe`() {
        val disposition =
            classifySecureStorageFailure(
                throwable =
                    GeneralSecurityException(
                        "wrapper",
                        AEADBadTagException("invalid ciphertext"),
                    ),
                stage = SecureStorageStage.PAYLOAD_READ,
                attempt = 1,
                sdkInt = 32,
            )

        assertEquals(
            SecureStorageFailureCode.INTEGRITY_FAILURE,
            assertIs<SecureStorageDisposition.Wipe>(disposition).code,
        )
    }

    @Test
    fun `legacy provider failure receives one compatibility retry`() {
        val disposition =
            classifySecureStorageFailure(
                throwable = ProviderException("temporarily unavailable"),
                stage = SecureStorageStage.MASTER_KEY,
                attempt = 1,
                sdkInt = 32,
                random = Random(0),
            )

        val retry = assertIs<SecureStorageDisposition.Retry>(disposition)
        assertEquals(SecureStorageFailureCode.KEYSTORE_BUSY, retry.code)
        assertEquals(2, retry.maxAttempts)
        assertEquals(false, retry.wipeOnExhaustion)
        assertTrue(retry.delayMillis in 100L..400L)
    }

    @Test
    fun `io failure retries without ever wiping data`() {
        val disposition =
            classifySecureStorageFailure(
                throwable = IOException("storage unavailable"),
                stage = SecureStorageStage.ARTIFACT_INVENTORY,
                attempt = 1,
                sdkInt = 32,
            )

        val retry = assertIs<SecureStorageDisposition.Retry>(disposition)
        assertEquals(SecureStorageFailureCode.STORAGE_UNAVAILABLE, retry.code)
        assertEquals(false, retry.wipeOnExhaustion)
    }

    @Test
    fun `malformed keyset encoding is definitive corruption`() {
        val disposition =
            classifySecureStorageFailure(
                throwable = CharConversionException("keyset is not valid hex"),
                stage = SecureStorageStage.KEYSET,
                attempt = 1,
                sdkInt = 32,
            )

        assertEquals(
            SecureStorageFailureCode.INTEGRITY_FAILURE,
            assertIs<SecureStorageDisposition.Wipe>(disposition).code,
        )
    }

    @Test
    fun `malformed keyset protobuf is definitive corruption`() {
        val disposition =
            classifySecureStorageFailure(
                throwable = InvalidProtocolBufferException("keyset protobuf is truncated"),
                stage = SecureStorageStage.KEYSET,
                attempt = 1,
                sdkInt = 32,
            )

        assertEquals(
            SecureStorageFailureCode.INTEGRITY_FAILURE,
            assertIs<SecureStorageDisposition.Wipe>(disposition).code,
        )
    }

    @Test
    fun `generic crypto failure retries then wipes as a last resort`() {
        val disposition =
            classifySecureStorageFailure(
                throwable = GeneralSecurityException("keystore hiccup"),
                stage = SecureStorageStage.MASTER_KEY,
                attempt = 1,
                sdkInt = 32,
            )

        val retry = assertIs<SecureStorageDisposition.Retry>(disposition)
        assertEquals(SecureStorageFailureCode.KEY_UNAVAILABLE, retry.code)
        assertEquals(true, retry.wipeOnExhaustion)
    }

    @Test
    fun `cancellation always escapes classification`() {
        assertFailsWith<CancellationException> {
            classifySecureStorageFailure(
                throwable = GeneralSecurityException(CancellationException("cancelled")),
                stage = SecureStorageStage.MASTER_KEY,
                attempt = 1,
                sdkInt = 32,
            )
        }
    }

    @Test
    fun `api 33 corrupted key is wiped`() {
        val disposition =
            classifyKeyStoreFailure(
                details =
                    KeyStoreFailureDetails(
                        numericErrorCode = KeyStoreException.ERROR_KEY_CORRUPTED,
                        requiresUserAuthentication = false,
                        transientFailure = false,
                        retryPolicy = KeyStoreException.RETRY_NEVER,
                    ),
                stage = SecureStorageStage.MASTER_KEY,
                attempt = 1,
                random = Random(0),
            )

        assertEquals(
            SecureStorageFailureCode.KEY_UNAVAILABLE,
            assertIs<SecureStorageDisposition.Wipe>(disposition).code,
        )
    }

    @Test
    fun `api 33 authentication requirement degrades`() {
        val disposition =
            classifyKeyStoreFailure(
                details =
                    KeyStoreFailureDetails(
                        numericErrorCode = KeyStoreException.ERROR_USER_AUTHENTICATION_REQUIRED,
                        requiresUserAuthentication = true,
                        transientFailure = false,
                        retryPolicy = KeyStoreException.RETRY_NEVER,
                    ),
                stage = SecureStorageStage.MASTER_KEY,
                attempt = 1,
                random = Random(0),
            )

        assertIs<SecureStorageDisposition.Degrade>(disposition)
    }

    @Test
    fun `api 33 permanent unknown error falls through to caller policy`() {
        val disposition =
            classifyKeyStoreFailure(
                details =
                    KeyStoreFailureDetails(
                        numericErrorCode = KeyStoreException.ERROR_INCORRECT_USAGE,
                        requiresUserAuthentication = false,
                        transientFailure = false,
                        retryPolicy = KeyStoreException.RETRY_NEVER,
                    ),
                stage = SecureStorageStage.MASTER_KEY,
                attempt = 1,
                random = Random(0),
            )

        assertNull(disposition)
    }
}
