package com.artemchep.keyguard.integration.bitwarden.cli

import com.artemchep.keyguard.common.service.text.url
import com.artemchep.keyguard.integration.bitwarden.SeededSend
import com.artemchep.keyguard.integration.bitwarden.VaultwardenApi
import com.artemchep.keyguard.integration.bitwarden.createTextSend
import com.artemchep.keyguard.integration.bitwarden.uploadAttachment
import com.artemchep.keyguard.provider.bitwarden.api.builder.api
import com.artemchep.keyguard.provider.bitwarden.api.builder.buildSendUrl
import com.artemchep.keyguard.provider.bitwarden.api.builder.get
import com.artemchep.keyguard.provider.bitwarden.crypto.AsymmetricCryptoKey
import com.artemchep.keyguard.provider.bitwarden.crypto.SymmetricCryptoKey2
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File

/** Creates a text Send and prints its share link. */
internal suspend fun VaultwardenApi.send(command: BwCommand.Send): BwResult {
    val unlocked = unlock(command.account.email, command.account.password)
    val send = createTextSend(
        account = unlocked.account,
        accessToken = unlocked.accessToken,
        text = command.text,
        password = command.sendPassword,
    )
    val url = sendUrl(send)
    val lines = buildList {
        add("Created a text Send: $url")
        if (command.sendPassword != null) {
            add("It opens with the password ${command.sendPassword}.")
        }
    }
    val json = bwJson(baseUrl) {
        put("id", send.id)
        put("accessId", send.accessId)
        put("url", url)
    }
    return BwResult(lines, json)
}

/** Uploads a file as an attachment of a personal or organization cipher. */
internal suspend fun VaultwardenApi.attach(command: BwCommand.Attach): BwResult {
    val file = File(command.file)
    check(file.isFile) { "No such file: ${command.file}" }
    val unlocked = unlock(command.account.email, command.account.password)
    val attachment = uploadAttachment(unlocked, command.cipherId, file.name, file.readBytes())
    val lines = listOf(
        "Attached ${attachment.fileName} (${attachment.size} bytes) to the cipher ${attachment.cipherId}.",
    )
    val json = bwJson(baseUrl) { putAttachment(attachment) }
    return BwResult(lines, json)
}

/** Uploads [content] as an attachment of [cipherId], encrypted with a new attachment key. */
internal suspend fun VaultwardenApi.uploadAttachment(
    unlocked: UnlockedAccount,
    cipherId: String,
    fileName: String,
    content: ByteArray,
): SeededAttachment {
    val ownerKey = attachmentCipherKey(unlocked, cipherId)
    val attachment = uploadAttachment(unlocked.accessToken, cipherId, ownerKey, fileName, content)
    return SeededAttachment(cipherId, attachment.id, fileName, content.size)
}

/** Resolves the cipher's own key, falling back to its organization or account key. */
private suspend fun VaultwardenApi.attachmentCipherKey(unlocked: UnlockedAccount, cipherId: String): ByteArray {
    val cipher = env.api.ciphers.focus(cipherId).get(httpClient, env, unlocked.accessToken)
    val ownerKey = cipher.organizationId?.let { organizationId ->
        val encryptedKey = requireNotNull(unlocked.organizationKeys[organizationId]) {
            "No key for organization $organizationId in the account's sync profile."
        }
        cipherEncryptor.decode2(
            cipher = encryptedKey,
            symmetricCryptoKey = SymmetricCryptoKey2(unlocked.account.userKey),
            asymmetricCryptoKey = AsymmetricCryptoKey(unlocked.account.privateKey),
        ).data
    } ?: unlocked.account.userKey
    return cipher.key?.let { accounts.decrypt(ownerKey, it) } ?: ownerKey
}

/** The share link, built like Keyguard and Bitwarden do: `<web vault>/#/send/<access id>/<key material>`. */
internal fun VaultwardenApi.sendUrl(send: SeededSend): String {
    val material = base64Service.url(base64Service.encodeToString(send.material))
    return env.buildSendUrl() + send.accessId + "/" + material
}

/** An attachment that [uploadAttachment] created. */
internal class SeededAttachment(
    val cipherId: String,
    val attachmentId: String,
    val fileName: String,
    /** Of the plain file. */
    val size: Int,
) {
    fun toJson(): JsonObject = buildJsonObject { putAttachment(this@SeededAttachment) }
}

private fun JsonObjectBuilder.putAttachment(attachment: SeededAttachment) {
    put("cipherId", attachment.cipherId)
    put("attachmentId", attachment.attachmentId)
    put("fileName", attachment.fileName)
    put("size", attachment.size)
}
