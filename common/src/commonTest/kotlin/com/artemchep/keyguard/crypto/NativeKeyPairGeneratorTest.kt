package com.artemchep.keyguard.crypto

import com.artemchep.keyguard.common.model.KeyPair
import com.artemchep.keyguard.common.model.KeyPairRaw
import com.artemchep.keyguard.common.service.crypto.KeyPairGenerator
import com.artemchep.keyguard.common.service.text.Base64Service
import com.artemchep.keyguard.common.service.text.impl.Base64ServiceImpl
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NativeKeyPairGeneratorTest {
    private val base64: Base64Service = Base64ServiceImpl()
    private val generator = NativeKeyPairGenerator(base64)

    @Test
    fun fixedEd25519MaterialKeepsOpenSshBytesAndFingerprint() {
        val raw = KeyPairRaw(
            type = KeyPair.Type.ED25519,
            privateKey = KeyPairRaw.KeyParameter(base64.decode(ED_PRIV_BLOB_B64)),
            publicKey = KeyPairRaw.KeyParameter(base64.decode(ED_PUB_TOKEN)),
        )

        val populated = generator.populate(raw)

        assertEquals("ssh-ed25519 $ED_PUB_TOKEN", populated.publicKey.ssh)
        assertEquals(ED_FINGERPRINT, populated.publicKey.fingerprint)
        assertEquals(
            "-----BEGIN OPENSSH PRIVATE KEY-----\n" +
                ED_PRIV_BLOB_B64.chunked(70).joinToString("\n") +
                "\n-----END OPENSSH PRIVATE KEY-----\n",
            populated.privateKey.ssh,
        )
    }

    @Test
    fun ed25519GenerationRoundTripsThroughPersistedRepresentation() {
        val generated = generator.populate(generator.ed25519())

        assertEquals(KeyPair.Type.ED25519, generated.type)
        assertTrue(generated.publicKey.ssh.startsWith("ssh-ed25519 "))
        assertTrue(generated.publicKey.fingerprint.startsWith("SHA256:"))
        assertTrue(generated.publicKey.fingerprint.endsWith("="))
        assertTrue(generated.privateKey.ssh.startsWith("-----BEGIN OPENSSH PRIVATE KEY-----\n"))
        assertNull(generator.getPrivateKeyLengthOrNull(generated))

        val parsed = generator.parse(generated.privateKey.ssh, generated.publicKey.ssh)
        assertEquals(KeyPair.Type.ED25519, parsed.type)
        assertContentEquals(generated.privateKey.encoded, parsed.privateKey.encoded)
        assertContentEquals(generated.publicKey.encoded, parsed.publicKey.encoded)
    }

    @Test
    fun rsaGenerationRoundTripsThroughPersistedRepresentation() {
        val generated = generator.populate(
            generator.rsa(KeyPairGenerator.RsaLength.B2048),
        )

        assertEquals(KeyPair.Type.RSA, generated.type)
        assertEquals(2048, generator.getPrivateKeyLengthOrNull(generated))
        assertEquals(2048, generator.getPrivateKeyLengthOrNull(generated.privateKey.ssh))
        assertTrue(generated.publicKey.ssh.startsWith("ssh-rsa "))
        assertTrue(generated.privateKey.ssh.startsWith("-----BEGIN RSA PRIVATE KEY-----\n"))

        val parsed = generator.parse(generated.privateKey.ssh, generated.publicKey.ssh)
        assertEquals(KeyPair.Type.RSA, parsed.type)
        assertContentEquals(generated.privateKey.encoded, parsed.privateKey.encoded)
        assertContentEquals(generated.publicKey.encoded, parsed.publicKey.encoded)
    }

    @Test
    fun malformedPrivateKeyReportsNoRsaLength() {
        assertNull(generator.getPrivateKeyLengthOrNull("not a private key"))
    }

    private companion object {
        const val ED_PUB_TOKEN = "AAAAC3NzaC1lZDI1NTE5AAAAILjiuaZ9B/nxuYP/fFCp59nJP3S0Rxxjk1VYA18FLXU/"
        const val ED_PRIV_BLOB_B64 =
            "b3BlbnNzaC1rZXktdjEAAAAABG5vbmUAAAAEbm9uZQAAAAAAAAABAAAAMwAAAAtzc2gtZWQyNTUxOQ" +
                "AAACC44rmmfQf58bmD/3xQqefZyT90tEccY5NVWANfBS11PwAAAIgDtbT+A7W0/gAAAAtzc2gtZWQ" +
                "yNTUxOQAAACC44rmmfQf58bmD/3xQqefZyT90tEccY5NVWANfBS11PwAAAEBmyVSflzmdIePYrjty" +
                "XgOu37oThPVL/WQ8cy6sP5Tk/LjiuaZ9B/nxuYP/fFCp59nJP3S0Rxxjk1VYA18FLXU/AAAAAAECAwQF"
        const val ED_FINGERPRINT = "SHA256:VMsjBVFrX4BnedkIhA3m9moiqwP6x2OcTGScAhS1TDw="
    }
}
