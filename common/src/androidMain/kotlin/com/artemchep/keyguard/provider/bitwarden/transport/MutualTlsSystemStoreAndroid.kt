package com.artemchep.keyguard.provider.bitwarden.transport

import android.content.Context
import android.net.http.SslCertificate
import android.security.KeyChain
import android.security.KeyChainException
import com.artemchep.keyguard.android.closestActivityOrNull
import com.artemchep.keyguard.platform.LeContext
import com.artemchep.keyguard.provider.bitwarden.ServerEnv
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/**
 * The certificates that the user installed in the Android settings. Their
 * private keys never leave the Android keystore: the app only gets a handle.
 */
class MutualTlsSystemStoreAndroid(
    private val context: Context,
) : MutualTlsSystemStore, MutualTlsSystemIdentities {
    // A state rather than an event: a collector that starts late still sees the change.
    private val version = MutableStateFlow(0)

    override val changes: Flow<Unit> = version.map { }

    override suspend fun choose(
        context: LeContext,
        current: ServerEnv.MutualTls.SystemStore?,
    ): ServerEnv.MutualTls.SystemStore? {
        val activity = context.context.closestActivityOrNull
            ?.takeUnless { it.isFinishing || it.isDestroyed }
        checkNotNull(activity) { "The certificate chooser needs a running activity." }
        val alias = withContext(Dispatchers.Main) {
            suspendCancellableCoroutine<String?> { continuation ->
                // The system keeps the callback for a while. It must not keep
                // the continuation with it, which holds on to the screen.
                val pending = AtomicReference<CancellableContinuation<String?>?>(continuation)
                continuation.invokeOnCancellation { pending.set(null) }
                // Android answers on a binder thread. The answer is `null` both if
                // the user declines and if there is no certificate to choose from.
                KeyChain.choosePrivateKeyAlias(
                    activity,
                    { alias -> pending.getAndSet(null)?.resume(alias) },
                    null, // any key type
                    null, // any issuer
                    null, // no host to show
                    -1,
                    current?.alias,
                )
            }
        }
            ?: return null
        // Choosing a certificate grants the access to it.
        version.update { it + 1 }
        return ServerEnv.MutualTls.SystemStore(alias)
    }

    override suspend fun describe(
        certificate: ServerEnv.MutualTls.SystemStore,
    ): MutualTlsCertificateMetadata? = withContext(Dispatchers.IO) {
        val chain = try {
            loadKeyChainValue { KeyChain.getCertificateChain(context, certificate.alias) }
        } catch (_: IOException) {
            null
        }
        val leaf = chain?.firstOrNull()
            ?: return@withContext null
        leaf.toMutualTlsMetadata(
            // The name that the user gave the certificate.
            displayName = certificate.alias,
            issuerName = SslCertificate(leaf).issuedBy?.cName,
        )
    }

    override fun load(certificate: ServerEnv.MutualTls.SystemStore): MutualTlsIdentity? =
        loadKeyChainIdentity(context, certificate.alias)
}

/**
 * Returns `null` if the certificate is gone or the app may no longer use it.
 * Blocks, and Android forbids it on the main thread.
 */
@Throws(IOException::class)
private fun loadKeyChainIdentity(context: Context, alias: String): MutualTlsIdentity? {
    val privateKey = loadKeyChainValue { KeyChain.getPrivateKey(context, alias) }
        ?: return null
    return loadKeyChainValue { KeyChain.getCertificateChain(context, alias) }
        ?.takeIf { it.isNotEmpty() }
        ?.let { chain -> MutualTlsIdentity(privateKey, chain) }
}

/**
 * Runs a blocking call of the key chain. Its failure must be an [IOException]:
 * the HTTP client handles those, and anything else ends the process.
 */
@Suppress("TooGenericExceptionCaught")
@Throws(IOException::class)
internal fun <T> loadKeyChainValue(block: () -> T?): T? = try {
    block()
} catch (_: KeyChainException) {
    // Older Android versions report a missing alias this way.
    null
} catch (e: InterruptedException) {
    Thread.currentThread().interrupt()
    throw InterruptedIOException(e.message)
} catch (e: RuntimeException) {
    throw IOException(e)
} catch (e: AssertionError) {
    // The key chain reports a failed service binding this way.
    throw IOException(e)
}
