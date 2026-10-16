package com.artemchep.keyguard.integration.bitwarden.cli

import com.artemchep.keyguard.common.service.totp.impl.TotpServiceImpl
import com.artemchep.keyguard.copy.Base32ServiceJvm
import com.artemchep.keyguard.crypto.CryptoGeneratorJvm
import com.artemchep.keyguard.integration.bitwarden.VaultwardenApi
import com.artemchep.keyguard.integration.bitwarden.totpCode
import com.artemchep.keyguard.integration.bitwarden.totpSecondsLeft
import kotlinx.serialization.json.put

/** Registers an account and tells how to add it to Keyguard. */
internal suspend fun VaultwardenApi.register(command: BwCommand.Register): BwResult {
    val email = command.account.email
    val password = command.account.password
    val account = accounts.register(email = email, password = password, name = command.name, kdf = command.kdf)
    val userId = accounts.userId(accounts.login(account).accessToken)
    val lines = listOf(
        "Registered $email on $baseUrl (${command.kdf.describe()}).",
        "To add it to Keyguard:",
        "  1. Add a Bitwarden account.",
        "  2. Choose Custom (self-hosted) and enter the server URL: $baseUrl",
        "  3. Enter the email and the password:",
        "     $email",
        "     $password",
    )
    val json = bwJson(baseUrl) {
        put("email", email)
        put("password", password)
        put("kdf", command.kdf.toJson())
        put("userId", userId)
    }
    return BwResult(lines, json)
}

/** Turns on two-step login with an authenticator app. */
internal suspend fun VaultwardenApi.totp(command: BwCommand.Totp): BwResult {
    val unlocked = unlock(command.account.email, command.account.password)
    val email = unlocked.account.email
    val secret = twoFactor.enableTotp(unlocked.account, unlocked.accessToken)
    val otpauth = "otpauth://totp/Keyguard:$email?secret=$secret&issuer=Keyguard"
    val code = twoFactor.totpCode(secret)
    val lines = listOf(
        "Turned on two-step login for $email.",
        "Secret: $secret",
        "URI: $otpauth",
        "Turning it on used the code $code. The next login needs the code of the next 30 s step, " +
            "in ${totpSecondsLeft()} s: run code --secret $secret.",
    )
    val json = bwJson(baseUrl) {
        put("email", email)
        put("secret", secret)
        put("otpauth", otpauth)
        put("code", code)
    }
    return BwResult(lines, json)
}

/** The current TOTP code of a secret; needs no server. */
internal fun code(command: BwCommand.Code): BwResult {
    val totpService = TotpServiceImpl(
        base32Service = Base32ServiceJvm(),
        cryptoGenerator = CryptoGeneratorJvm(),
    )
    val code = totpCode(totpService, command.secret)
    val secondsLeft = totpSecondsLeft()
    val json = bwJson(server = null) {
        put("code", code)
        put("secondsLeft", secondsLeft)
    }
    return BwResult(listOf("$code, valid for $secondsLeft s."), json)
}
