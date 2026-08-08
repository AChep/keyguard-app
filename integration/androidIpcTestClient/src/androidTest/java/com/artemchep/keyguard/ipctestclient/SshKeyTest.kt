package com.artemchep.keyguard.ipctestclient

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.artemchep.keyguard.ipctestclient.ipc.SshOperation
import com.artemchep.keyguard.ipctestclient.ipc.SshRequestSpec
import com.artemchep.keyguard.ipctestclient.ipc.parseSshPublicKeyLine
import com.artemchep.keyguard.ipctestclient.ipc.sshKeyAlgorithmName
import com.artemchep.keyguard.ipctestclient.support.KeyguardProviderRule
import com.artemchep.keyguard.ipctestclient.support.LocalCrypto
import com.artemchep.keyguard.ipctestclient.support.SuiteState
import com.artemchep.keyguard.ipctestclient.support.requireSshSuccess
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.openintents.ssh.authentication.SshAuthenticationApi
import org.openintents.ssh.authentication.response.KeySelectionResponse
import org.openintents.ssh.authentication.response.PublicKeyResponse
import org.openintents.ssh.authentication.response.SshPublicKeyResponse
import java.security.KeyFactory
import java.security.spec.X509EncodedKeySpec

@RunWith(AndroidJUnit4::class)
class SshKeyTest {
    @get:Rule
    val provider = KeyguardProviderRule()

    private val state by lazy { SuiteState(provider) }

    @Test
    fun selectingAKeyReturnsAnIdentifierAndADescription() {
        val exchange = provider
            .sshRunner()
            .run(SshRequestSpec(SshOperation.SELECT_KEY))
        val response = KeySelectionResponse(exchange.requireSshSuccess())
        assertTrue("No key id in the selection", !response.keyId.isNullOrBlank())
        assertNotNull("No key description in the selection", response.keyDescription)
    }

    @Test
    fun theSshPublicKeyIsAnAuthorizedKeysLine() {
        val exchange = provider.sshRunner().run(
            SshRequestSpec(SshOperation.GET_SSH_PUBLIC_KEY, keyId = state.sshKeyId()),
        )
        val line = SshPublicKeyResponse(exchange.requireSshSuccess()).sshPublicKey
        assertTrue("No ssh public key returned", !line.isNullOrBlank())
        assertNotNull(
            "\"$line\" is not a <type> <base64> line whose blob names the same type",
            parseSshPublicKeyLine(line),
        )
    }

    @Test
    fun thePublicKeyIsSpkiDerMatchingTheAdvertisedAlgorithm() {
        val exchange = provider.sshRunner().run(
            SshRequestSpec(SshOperation.GET_PUBLIC_KEY, keyId = state.sshKeyId()),
        )
        val response = PublicKeyResponse(exchange.requireSshSuccess())
        val algorithm = response.keyAlgorithm
        assertTrue(
            "Unexpected algorithm ${sshKeyAlgorithmName(algorithm)}",
            algorithm in SUPPORTED_ALGORITHMS,
        )
        val jcaName = if (algorithm == SshAuthenticationApi.RSA) "RSA" else "Ed25519"
        assumeCanParse(jcaName)
        val publicKey = KeyFactory
            .getInstance(jcaName)
            .generatePublic(X509EncodedKeySpec(response.encodedPublicKey))
        assertEquals(jcaName, publicKey.algorithm)
    }

    private fun assumeCanParse(jcaName: String) = org.junit.Assume.assumeTrue(
        "This API level cannot parse $jcaName keys.",
        jcaName == "RSA" || LocalCrypto.canVerify(LocalCrypto.SSH_ED25519),
    )

    private companion object {
        val SUPPORTED_ALGORITHMS = setOf(
            SshAuthenticationApi.RSA,
            SshAuthenticationApi.EDDSA,
        )
    }
}
