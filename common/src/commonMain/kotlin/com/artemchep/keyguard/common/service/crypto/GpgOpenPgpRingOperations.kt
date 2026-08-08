package com.artemchep.keyguard.common.service.crypto

import com.artemchep.keyguard.util.io.CopyingRawSource
import kotlinx.io.Buffer
import kotlinx.io.Sink
import kotlinx.io.Source
import kotlinx.io.buffered
import kotlinx.io.readByteArray
import kotlinx.io.write

internal data class GpgOpenPgpDetachedVerification(
    val verification: GpgOpenPgpVerification,
    val bodySize: Long,
)

/**
 * The OpenPGP file operations expressed over selected key rings,
 * independent of any transport: callers hand in plain streams and get
 * domain results back.
 */
internal class GpgOpenPgpRingOperations(
    private val service: GpgOpenPgpService,
) {
    fun exportPublicKey(
        ring: GpgOpenPgpRing,
        output: Sink,
        armored: Boolean,
    ) {
        service.exportPublicKey(
            GpgOpenPgpExportPublicKeyRequest(
                publicKey = ring.publicKey(),
                output = output,
                armored = armored,
            ),
        )
    }

    fun clearSign(
        privateKey: GpgOpenPgpPrivateKey,
        input: Source,
        output: Sink,
    ) {
        service.clearSignFile(
            GpgOpenPgpClearSignFileRequest(
                input = input,
                output = output,
                privateKey = privateKey,
            ),
        )
    }

    fun detachedSign(
        privateKey: GpgOpenPgpPrivateKey,
        input: Source,
        armored: Boolean,
    ): ByteArray {
        val signatureOutput = Buffer()
        service.signFile(
            GpgOpenPgpSignFileRequest(
                input = input,
                signatureOutput = signatureOutput,
                privateKey = privateKey,
                armored = armored,
            ),
        )
        return signatureOutput.readByteArray()
    }

    @Suppress("LongParameterList")
    fun encrypt(
        recipients: List<GpgOpenPgpRing>,
        signingPrivateKey: GpgOpenPgpPrivateKey?,
        input: Source,
        output: Sink,
        fileName: String?,
        armored: Boolean,
        enableCompression: Boolean,
    ) {
        service.encryptFile(
            GpgOpenPgpEncryptFileRequest(
                input = input,
                output = output,
                publicKeys = recipients.map { it.publicKey() },
                fileName = GpgOpenPgpLiteralFileName.fromUntrusted(fileName),
                armored = armored,
                signingPrivateKey = signingPrivateKey,
                enableCompression = enableCompression,
            ),
        )
    }

    fun read(
        rings: List<GpgOpenPgpRing>,
        input: Source,
        output: Sink,
    ): GpgOpenPgpReadFileResult = service.readFile(
        GpgOpenPgpReadFileRequest(
            input = input,
            output = output,
            privateKeys = rings
                .filter(GpgOpenPgpRing::canDecrypt)
                .mapNotNull(GpgOpenPgpRing::privateKey),
            publicKeys = rings.map { it.publicKey() },
            allowSignedOnly = true,
        ),
    )

    fun verifyDetached(
        rings: List<GpgOpenPgpRing>,
        input: Source,
        output: Sink,
        signature: ByteArray,
    ): GpgOpenPgpDetachedVerification {
        val copyingSource = CopyingRawSource(
            input = input,
            output = output,
        )
        val verification = service.verifyFile(
            GpgOpenPgpVerifyFileRequest(
                input = copyingSource.buffered(),
                signatureInput = Buffer().apply {
                    write(signature)
                },
                publicKeys = rings.map { it.publicKey() },
            ),
        )
        output.flush()
        return GpgOpenPgpDetachedVerification(
            verification = verification,
            bodySize = copyingSource.size,
        )
    }
}
