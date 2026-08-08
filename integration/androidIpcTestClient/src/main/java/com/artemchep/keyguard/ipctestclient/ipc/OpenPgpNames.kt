package com.artemchep.keyguard.ipctestclient.ipc

import org.openintents.openpgp.OpenPgpDecryptionResult
import org.openintents.openpgp.OpenPgpError
import org.openintents.openpgp.OpenPgpSignatureResult
import org.openintents.openpgp.util.OpenPgpApi

fun openPgpResultCodeName(code: Int): String = when (code) {
    OpenPgpApi.RESULT_CODE_ERROR -> "ERROR"
    OpenPgpApi.RESULT_CODE_SUCCESS -> "SUCCESS"
    OpenPgpApi.RESULT_CODE_USER_INTERACTION_REQUIRED -> "USER_INTERACTION_REQUIRED"
    else -> "UNKNOWN($code)"
}

fun openPgpErrorName(errorId: Int): String = when (errorId) {
    OpenPgpError.CLIENT_SIDE_ERROR -> "CLIENT_SIDE_ERROR"
    OpenPgpError.GENERIC_ERROR -> "GENERIC_ERROR"
    OpenPgpError.INCOMPATIBLE_API_VERSIONS -> "INCOMPATIBLE_API_VERSIONS"
    OpenPgpError.NO_OR_WRONG_PASSPHRASE -> "NO_OR_WRONG_PASSPHRASE"
    OpenPgpError.NO_USER_IDS -> "NO_USER_IDS"
    OpenPgpError.OPPORTUNISTIC_MISSING_KEYS -> "OPPORTUNISTIC_MISSING_KEYS"
    else -> "UNKNOWN($errorId)"
}

fun openPgpAutocryptStatusName(status: Int): String = when (status) {
    OpenPgpApi.AUTOCRYPT_STATUS_UNAVAILABLE -> "UNAVAILABLE"
    OpenPgpApi.AUTOCRYPT_STATUS_DISCOURAGE -> "DISCOURAGE"
    OpenPgpApi.AUTOCRYPT_STATUS_AVAILABLE -> "AVAILABLE"
    OpenPgpApi.AUTOCRYPT_STATUS_MUTUAL -> "MUTUAL"
    else -> "UNKNOWN($status)"
}

@Suppress("CyclomaticComplexMethod")
fun openPgpSignatureStatusName(result: Int): String = when (result) {
    OpenPgpSignatureResult.RESULT_NO_SIGNATURE -> "NO_SIGNATURE"
    OpenPgpSignatureResult.RESULT_INVALID_SIGNATURE -> "INVALID_SIGNATURE"
    OpenPgpSignatureResult.RESULT_VALID_KEY_CONFIRMED -> "VALID_KEY_CONFIRMED"
    OpenPgpSignatureResult.RESULT_KEY_MISSING -> "KEY_MISSING"
    OpenPgpSignatureResult.RESULT_VALID_KEY_UNCONFIRMED -> "VALID_KEY_UNCONFIRMED"
    OpenPgpSignatureResult.RESULT_INVALID_KEY_REVOKED -> "INVALID_KEY_REVOKED"
    OpenPgpSignatureResult.RESULT_INVALID_KEY_EXPIRED -> "INVALID_KEY_EXPIRED"
    OpenPgpSignatureResult.RESULT_INVALID_KEY_INSECURE -> "INVALID_KEY_INSECURE"
    OpenPgpSignatureResult.RESULT_INVALID_NOT_INTENDED_RECIPIENT ->
        "INVALID_NOT_INTENDED_RECIPIENT"

    else -> "UNKNOWN($result)"
}

fun openPgpDecryptionStatusName(result: Int): String = when (result) {
    OpenPgpDecryptionResult.RESULT_NOT_ENCRYPTED -> "NOT_ENCRYPTED"
    OpenPgpDecryptionResult.RESULT_INSECURE -> "INSECURE"
    OpenPgpDecryptionResult.RESULT_ENCRYPTED -> "ENCRYPTED"
    else -> "UNKNOWN($result)"
}

/** Armor headers the provider can emit, used to tell the outputs apart. */
object PgpArmor {
    const val PUBLIC_KEY = "-----BEGIN PGP PUBLIC KEY BLOCK-----"
    const val SIGNATURE = "-----BEGIN PGP SIGNATURE-----"
    const val SIGNED_MESSAGE = "-----BEGIN PGP SIGNED MESSAGE-----"
    const val MESSAGE = "-----BEGIN PGP MESSAGE-----"
}
