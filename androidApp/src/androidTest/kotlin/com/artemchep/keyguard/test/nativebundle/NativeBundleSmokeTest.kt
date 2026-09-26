package com.artemchep.keyguard.test.nativebundle

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import androidx.test.platform.app.InstrumentationRegistry
import com.artemchep.keyguard.nativebundle.NativeBundleProbe
import com.artemchep.keyguard.nativecrypto.NativeCrypto
import com.artemchep.keyguard.nativecrypto.NativeOpenPgpKeyKind
import com.artemchep.keyguard.nativecrypto.NativeOpenPgpKeyVersion
import com.artemchep.keyguard.nativecrypto.NativeOpenPgpVerificationStatus
import com.artemchep.keyguard.util.io.LocalPath
import org.junit.Test
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.runner.RunWith
import java.nio.file.Files

@RunWith(AndroidJUnit4::class)
@SmallTest
class NativeBundleSmokeTest {
    @Test
    fun generatesAndUsesBothOpenPgpCertificateVersions() {
        val plaintext = "Android OpenPGP native smoke".encodeToByteArray()
        NativeOpenPgpKeyVersion.entries.forEach { version ->
            val material = NativeCrypto.openPgp.generateKey(
                kind = if (version == NativeOpenPgpKeyVersion.V4) NativeOpenPgpKeyKind.LEGACY_ED25519_X25519
                else NativeOpenPgpKeyKind.ED25519_X25519,
                version = version,
                userId = "Android smoke <android-smoke@test.invalid>",
                creationTimeEpochSeconds = 1_700_000_000L,
            )
            try {
                assertEquals(if (version == NativeOpenPgpKeyVersion.V4) 40 else 64, material.fingerprint.length)
                val signature = NativeCrypto.openPgp.signDetached(
                    content = plaintext,
                    privateKey = material.privateKeyArmored,
                    candidateRevocationKeys = emptyList(),
                )
                assertEquals(
                    NativeOpenPgpVerificationStatus.VALID,
                    NativeCrypto.openPgp.verifyDetached(plaintext, signature, listOf(material.publicKeyArmored)).status,
                )
                val encrypted = NativeCrypto.openPgp.encrypt(
                    content = plaintext,
                    publicKeys = listOf(material.publicKeyArmored),
                    candidateRevocationKeys = emptyList(),
                    fileName = "smoke.txt",
                    armored = false,
                )
                assertArrayEquals(
                    plaintext,
                    NativeCrypto.openPgp.decrypt(encrypted.data, listOf(material.privateKeyArmored)).data,
                )
            } finally {
                material.privateKeyArmored.fill(0)
            }
        }
    }

    @Test
    fun loadsPackagedLibrariesAndExecutesNativeOperations() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = Files.createTempDirectory(context.cacheDir.toPath(), "native-bundle-").toRealPath()
        try {
            NativeBundleProbe.run(LocalPath(directory.toString()))
        } finally {
            directory.toFile().deleteRecursively()
        }
    }
}
