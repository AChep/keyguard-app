package com.artemchep.keyguard.test.transport

import android.security.keystore.KeyProperties
import android.security.keystore.KeyProtection
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import com.artemchep.keyguard.provider.bitwarden.ServerEnv
import com.artemchep.keyguard.provider.bitwarden.transport.MutualTlsIdentity
import com.artemchep.keyguard.provider.bitwarden.transport.mutualTlsJvmTestClients
import com.artemchep.keyguard.provider.bitwarden.transport.verifyMutualTlsTransport
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.encoding.Base64
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@LargeTest
class MutualTlsNetworkTest {
    @Test
    fun handshakesRedirectsAndWebSocketsUseOnlyConfiguredIdentities() = runBlocking {
        HttpClient(OkHttp).use { bootstrap ->
            verifyMutualTlsTransport(
                InstrumentationRegistry.getArguments().getString("mtlsPort"),
                bootstrap,
                ::mutualTlsJvmTestClients,
            )
        }
    }

    /**
     * A certificate of the system's store comes with a handle to a private
     * key that never leaves the Android keystore. Installing one needs the
     * user, so the test puts the fixture keys into the keystore of the app
     * instead, which hands out the same kind of handle, and runs the same
     * contract with them.
     */
    @Test
    fun systemStoreIdentitiesPassTheSameContract(): Unit = runBlocking {
        val keystore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val identities = ConcurrentHashMap<String, MutualTlsIdentity>()
        try {
            HttpClient(OkHttp).use { bootstrap ->
                verifyMutualTlsTransport(
                    InstrumentationRegistry.getArguments().getString("mtlsPort"),
                    bootstrap,
                    clients = { ca ->
                        mutualTlsJvmTestClients(ca) { certificate -> identities[certificate.alias] }
                    },
                    certificate = { name, imported ->
                        identities[name] = keystore.importIdentity(keystoreAlias(name), imported)
                        ServerEnv.MutualTls.SystemStore(name)
                    },
                )
            }
        } finally {
            identities.keys.forEach { name -> keystore.deleteEntry(keystoreAlias(name)) }
        }
    }

    private fun keystoreAlias(name: String) = "keyguard.mtls.test.$name"

    private fun KeyStore.importIdentity(
        alias: String,
        imported: ServerEnv.MutualTls.ImportedPkcs12,
    ): MutualTlsIdentity {
        val source = KeyStore.getInstance("PKCS12").apply {
            Base64.decode(imported.pkcs12Base64).inputStream().use { load(it, charArrayOf()) }
        }
        val sourceAlias = source.aliases().toList().single(source::isKeyEntry)
        val key = source.getKey(sourceAlias, charArrayOf()) as PrivateKey
        val chain = source.getCertificateChain(sourceAlias)
        setEntry(alias, KeyStore.PrivateKeyEntry(key, chain), keyChainProtection(key))
        return MutualTlsIdentity(
            privateKey = getKey(alias, null) as PrivateKey,
            chain = chain.filterIsInstance<X509Certificate>().toTypedArray(),
        )
    }

    /**
     * What the key chain of Android authorizes its keys for. The TLS stack
     * can not read such a key, so it pads the message itself and asks the
     * keystore for the raw private-key operation.
     */
    private fun keyChainProtection(key: PrivateKey): KeyProtection {
        val digests = arrayOf(
            KeyProperties.DIGEST_NONE,
            KeyProperties.DIGEST_SHA256,
            KeyProperties.DIGEST_SHA384,
            KeyProperties.DIGEST_SHA512,
        )
        if (key.algorithm != KeyProperties.KEY_ALGORITHM_RSA) {
            return KeyProtection.Builder(KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                .setDigests(*digests)
                .build()
        }
        val purposes = KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT or
            KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
        return KeyProtection.Builder(purposes)
            .setDigests(*digests)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setSignaturePaddings(
                KeyProperties.SIGNATURE_PADDING_RSA_PKCS1,
                KeyProperties.SIGNATURE_PADDING_RSA_PSS,
            )
            // Required by the keystore for encryption without padding.
            .setRandomizedEncryptionRequired(false)
            .build()
    }
}
