package com.artemchep.keyguard.common.model

import com.artemchep.keyguard.common.service.text.Base64Service
import com.artemchep.keyguard.util.fido2.FIDO2_INPUT_LENGTH
import com.artemchep.keyguard.util.fido2.FIDO2_MAX_CREDENTIAL_LENGTH
import com.artemchep.keyguard.util.fido2.FIDO2_RP_ID
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable

/** Public credential metadata and an authenticated, encrypted copy of the local vault key. */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class FingerprintFido2(
    val credentialId: String,
    val salt: String,
    val hkdfSalt: String,
    val encryptedMasterKey: String,
    @EncodeDefault val version: Int = 1,
    @EncodeDefault val rpId: String = FIDO2_RP_ID,
) {
    fun validate(base64: Base64Service) {
        require(version == 1 && rpId == FIDO2_RP_ID)
        require(base64.decode(credentialId).size in 1..FIDO2_MAX_CREDENTIAL_LENGTH)
        require(
            base64.decode(salt).size == FIDO2_INPUT_LENGTH &&
                base64.decode(hkdfSalt).size == FIDO2_INPUT_LENGTH
        )
        require(encryptedMasterKey.startsWith("2."))
    }
}
