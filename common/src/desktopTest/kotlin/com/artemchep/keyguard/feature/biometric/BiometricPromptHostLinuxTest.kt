package com.artemchep.keyguard.feature.biometric

import arrow.core.right
import com.artemchep.autotype.BiometricsException
import com.artemchep.autotype.BiometricsStatus
import com.artemchep.keyguard.common.model.BiometricBindingException
import com.artemchep.keyguard.common.model.BiometricPurpose
import com.artemchep.keyguard.common.model.DKey
import com.artemchep.keyguard.platform.LeBiometricCipherLinux
import com.artemchep.keyguard.feature.keyguard.unlock.UnlockVaultWithBiometric
import com.artemchep.keyguard.feature.loading.LoadingTask
import com.artemchep.keyguard.feature.loading.ReadableExceptionMessage
import com.artemchep.keyguard.feature.navigation.state.TranslatorScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.jetbrains.compose.resources.PluralStringResource
import org.jetbrains.compose.resources.StringResource
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class BiometricPromptHostLinuxTest {
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun `busy executor erases the authenticated key instead of caching it`() = runTest {
        val task = loadingTask(this)
        val gate = CompletableDeferred<Unit>()
        task.execute(io = { gate.await() })
        val cipher = LeBiometricCipherLinux.forDecryption(HANDLE)
        BiometricPromptHostLinux(FakeOperations()).materialize(REQUEST, cipher)
        val pending = pendingPlaintext(cipher)
        unlockTask(task, cipher) { error("Busy executor must not start another unlock") }.invoke(cipher)
        assertTrue(pending.all { it == 0.toByte() })
        assertFailsWith<IllegalStateException> { cipher.encode(HANDLE) }
        gate.complete(Unit)
        advanceUntilIdle()
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun `cancellation before the unlock starts erases pending key`() = runTest {
        val owner = Job()
        val scope = CoroutineScope(coroutineContext + owner)
        val cipher = LeBiometricCipherLinux.forDecryption(HANDLE)
        cipher.completeDecryption(SECRET)
        val pending = pendingPlaintext(cipher)
        unlockTask(loadingTask(scope), cipher) { error("Cancelled unlock must not run") }.invoke(cipher)
        scope.cancel()
        runCurrent()
        assertTrue(pending.all { it == 0.toByte() })
        assertFailsWith<IllegalStateException> { cipher.encode(HANDLE) }
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun `accepted unlock consumes key without erasing the session copy`() = runTest {
        val cipher = LeBiometricCipherLinux.forDecryption(HANDLE)
        cipher.completeDecryption(SECRET)
        var sessionKey: ByteArray? = null
        unlockTask(loadingTask(this), cipher) {
            sessionKey = cipher.encode(HANDLE)
        }.invoke(cipher)
        advanceUntilIdle()
        assertContentEquals(SECRET, sessionKey)
        assertFailsWith<IllegalStateException> { cipher.encode(HANDLE) }
    }

    @Test
    fun `enrollment verifies and persists only the native handle`() = runTest {
        val operations = FakeOperations()
        val host = BiometricPromptHostLinux(operations)
        val cipher = host.createCipher(BiometricPurpose.Encrypt)
        host.materialize(REQUEST, cipher)
        val encoded = cipher.encode(SECRET)
        assertEquals(1, operations.preparations)
        assertEquals(1, operations.verifications)
        assertContentEquals(HANDLE, encoded)
        assertContentEquals(HANDLE, cipher.iv)
        assertFalse(encoded.contentEquals(SECRET))
    }

    @Test
    fun `password rearm does not prompt and unlock consumes plaintext`() = runTest {
        val operations = FakeOperations()
        val host = BiometricPromptHostLinux(operations)
        val encryption = host.createCipher(BiometricPurpose.Encrypt)
        val encoded = encryption.encode(SECRET)
        assertEquals(0, operations.verifications)
        val decryption = host.createCipher(BiometricPurpose.Decrypt(DKey(encryption.iv)))
        assertFailsWith<IllegalStateException> { decryption.encode(encoded) }
        host.materialize(REQUEST, decryption)
        assertEquals(0, operations.preparations)
        assertEquals(1, operations.releases)
        assertContentEquals(ByteArray(SECRET.size), operations.returnedPlaintext)
        assertContentEquals(SECRET, decryption.encode(encoded))
        assertFailsWith<IllegalStateException> { decryption.encode(encoded) }
    }

    @Test
    fun `cancelled authentication cannot materialize a cipher`() = runTest {
        val cancelled = BiometricsException(BiometricsStatus.USER_CANCELED, "Cancelled")
        val operations = FakeOperations(failure = cancelled)
        val host = BiometricPromptHostLinux(operations)
        val cipher = host.createCipher(BiometricPurpose.Decrypt(DKey(HANDLE)))
        assertSame(cancelled, assertFailsWith<BiometricsException> { host.materialize(REQUEST, cipher) })
        assertFailsWith<IllegalStateException> { cipher.encode(HANDLE) }
        val encryption = host.createCipher(BiometricPurpose.Encrypt)
        assertSame(cancelled, assertFailsWith<BiometricsException> { host.materialize(REQUEST, encryption) })
        assertFalse(operations.provisioned)
    }

    @Test
    fun `a queued prompt does not erase a plaintext awaiting its unlock`() = runTest {
        val cipher = LeBiometricCipherLinux.forDecryption(HANDLE)
        cipher.completeDecryption(SECRET)
        BiometricPromptHostLinux(FakeOperations()).materialize(REQUEST, cipher)
        assertContentEquals(SECRET, cipher.encode(HANDLE))
    }

    @Test
    fun `mismatched binding erases the pending plaintext`() {
        val cipher = LeBiometricCipherLinux.forDecryption(HANDLE)
        cipher.completeDecryption(SECRET)
        val field = cipher.javaClass.getDeclaredField("plaintext").apply { isAccessible = true }
        val pending = field.get(cipher) as ByteArray
        assertFailsWith<BiometricBindingException> { cipher.encode(byteArrayOf(0)) }
        assertTrue(pending.all { it == 0.toByte() })
        assertFailsWith<IllegalStateException> { cipher.encode(HANDLE) }
    }

    @Test
    fun `old truncated and unsupported handles are rejected`() {
        for (invalid in listOf(ByteArray(16), HANDLE.dropLast(1).toByteArray(), HANDLE + 0)) {
            assertFailsWith<BiometricBindingException> { LeBiometricCipherLinux.forDecryption(invalid) }
        }
        val unsupported = HANDLE.copyOf().also { it[4] = 2 }
        assertFailsWith<BiometricBindingException> { LeBiometricCipherLinux.forDecryption(unsupported) }
    }

    private class FakeOperations(private val failure: Throwable? = null) : LinuxBiometricOperations {
        var preparations = 0
        var verifications = 0
        var releases = 0
        var provisioned = false
        var returnedPlaintext: ByteArray? = null

        override suspend fun prepareEnrollment() {
            preparations++
        }

        override suspend fun verify(request: BiometricPromptRequest) {
            verifications++
            failure?.let { throw it }
        }

        override suspend fun protect(secret: ByteArray): ByteArray {
            assertContentEquals(SECRET, secret)
            provisioned = true
            return HANDLE.copyOf()
        }

        override suspend fun release(request: BiometricPromptRequest, handle: ByteArray): ByteArray {
            releases++
            failure?.let { throw it }
            assertContentEquals(HANDLE, handle)
            return SECRET.copyOf().also { returnedPlaintext = it }
        }
    }

    private fun pendingPlaintext(cipher: LeBiometricCipherLinux) =
        cipher.javaClass.getDeclaredField("plaintext").apply { isAccessible = true }.get(cipher) as ByteArray

    private fun loadingTask(scope: CoroutineScope) = LoadingTask(
        translator = TestTranslator,
        scope = scope,
        exceptionHandler = { ReadableExceptionMessage(it.message.orEmpty()) },
    )

    private fun unlockTask(
        task: LoadingTask,
        cipher: LeBiometricCipherLinux,
        action: suspend () -> Unit,
    ) = UnlockVaultWithBiometric(
        executor = task,
        getCipher = { cipher.right() },
        getCreateIo = { action },
        getFailureIo = { { throw it } },
        requireConfirmation = false,
    )

    companion object {
        private val SECRET = ByteArray(32) { it.toByte() }
        private val HANDLE = byteArrayOf(75, 71, 76, 88, 1) + ByteArray(32) { (it + 10).toByte() }
        private val REQUEST = BiometricPromptRequest(title = "Unlock", windowHandle = 0L)
    }
}

private object TestTranslator : TranslatorScope {
    override suspend fun translate(res: StringResource): String = res.toString()
    override suspend fun translate(res: StringResource, vararg args: Any): String = res.toString()
    override suspend fun translate(res: PluralStringResource, quantity: Int, vararg args: Any): String =
        res.toString()
}
