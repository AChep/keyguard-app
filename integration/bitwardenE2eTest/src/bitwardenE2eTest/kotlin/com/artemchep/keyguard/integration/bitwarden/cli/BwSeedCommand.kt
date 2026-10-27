package com.artemchep.keyguard.integration.bitwarden.cli

import com.artemchep.keyguard.common.service.text.url
import com.artemchep.keyguard.integration.bitwarden.TestAccount
import com.artemchep.keyguard.integration.bitwarden.VaultwardenApi
import com.artemchep.keyguard.integration.bitwarden.createFileSend
import com.artemchep.keyguard.integration.bitwarden.createTextSend
import com.artemchep.keyguard.integration.bitwarden.string
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

private class SeededItem(
    val id: String,
    val name: String,
)

private class SeededLogin(
    val id: String,
    val name: String,
    val username: String,
    val password: String,
    val uri: String,
    /** A base32 secret. */
    val totp: String,
    val folderId: String?,
)

/** Fills the vault of an existing account with folders, logins, notes, an attachment and a Send. */
internal suspend fun VaultwardenApi.seed(command: BwCommand.Seed): BwResult {
    val unlocked = unlock(command.account.email, command.account.password)
    val account = unlocked.account
    val token = unlocked.accessToken
    val folders = (1..command.folders).map { i ->
        val name = "Folder $i"
        SeededItem(id = accounts.createFolder(token, account, name), name = name)
    }
    val logins = (1..command.logins).map { i ->
        // Round-robin over the folders.
        val folderId = if (folders.isEmpty()) null else folders[(i - 1) % folders.size].id
        createLogin(token, account, index = i, folderId = folderId)
    }
    val notes = (1..command.notes).map { i ->
        val name = "Note $i"
        val text = "Secure note $i, seeded by bw."
        SeededItem(id = accounts.createSecureNote(token, account, name, text), name = name)
    }
    val attachment = if (command.attachment) {
        uploadAttachment(unlocked, logins.first().id, ATTACHMENT_NAME, attachmentContent())
    } else {
        null
    }
    val send = command.send?.let { type -> seedSend(account, token, type) }
    val vault = SeededVault(account.email, folders, logins, notes, attachment, send)
    return BwResult(vault.lines(baseUrl), vault.toJson(baseUrl))
}

private class SeededVault(
    val email: String,
    val folders: List<SeededItem>,
    val logins: List<SeededLogin>,
    val notes: List<SeededItem>,
    val attachment: SeededAttachment?,
    /** See [seedSend]. */
    val send: JsonObject?,
) {
    fun lines(server: String): List<String> = buildList {
        val counts = listOf(
            plural(folders.size, "folder"),
            plural(logins.size, "login"),
            plural(notes.size, "note"),
        )
        add("Seeded the vault of $email on $server: ${counts.joinToString()}.")
        logins.forEach { login ->
            add("  ${login.name}: ${login.username} / ${login.password}, ${login.uri}, TOTP ${login.totp}")
        }
        if (attachment != null) {
            add("Attached ${attachment.fileName} (${attachment.size} bytes) to ${logins.first().name}.")
        }
        if (send != null) {
            add("Created a ${send.string("type")} Send: ${send.string("url")}")
        }
        add("Sync Keyguard to see them.")
    }

    fun toJson(server: String): JsonObject = bwJson(server) {
        put("email", email)
        putJsonArray("folders") { folders.forEach { addJsonObject { putItem(it) } } }
        putJsonArray("logins") {
            logins.forEach { login ->
                addJsonObject {
                    putItem(SeededItem(login.id, login.name))
                    put("username", login.username)
                    put("password", login.password)
                    put("uri", login.uri)
                    put("totp", login.totp)
                    put("folderId", login.folderId)
                }
            }
        }
        putJsonArray("notes") { notes.forEach { addJsonObject { putItem(it) } } }
        if (attachment != null) {
            put("attachment", attachment.toJson())
        }
        if (send != null) {
            put("send", send)
        }
    }
}

private suspend fun VaultwardenApi.createLogin(
    token: String,
    account: TestAccount,
    index: Int,
    folderId: String?,
): SeededLogin {
    val name = "Example Site $index"
    val username = "user$index"
    val password = base64Service.url(base64Service.encodeToString(cryptoGenerator.seed(PASSWORD_BYTES)))
    val uri = "https://site$index.example"
    val totp = base32Service.encodeToString(cryptoGenerator.seed(TOTP_SECRET_BYTES))
    val id = accounts.createLoginCipher(
        accessToken = token,
        account = account,
        name = name,
        username = username,
        password = password,
        uri = uri,
        totp = totp,
        folderId = folderId,
    )
    return SeededLogin(id, name, username, password, uri, totp, folderId)
}

/** The JSON of a Send: its ids, its type and its share link. */
private suspend fun VaultwardenApi.seedSend(
    account: TestAccount,
    token: String,
    type: BwCommand.SendType,
): JsonObject {
    val send = when (type) {
        BwCommand.SendType.Text -> createTextSend(account, token, text = "Hello from bw.")

        BwCommand.SendType.File -> createFileSend(
            account = account,
            accessToken = token,
            fileName = SEND_FILE_NAME,
            content = attachmentContent(),
        )
    }
    return buildJsonObject {
        put("id", send.id)
        put("accessId", send.accessId)
        put("type", type.name.lowercase())
        put("url", sendUrl(send))
    }
}

private fun JsonObjectBuilder.putItem(item: SeededItem) {
    put("id", item.id)
    put("name", item.name)
}

/** About 2 KiB of text. */
private fun attachmentContent(): ByteArray = (1..ATTACHMENT_LINES)
    .joinToString(separator = "") { line -> "Keyguard test attachment, line ${line.toString().padStart(3, '0')}.\n" }
    .encodeToByteArray()

private const val ATTACHMENT_NAME = "attachment-1.txt"
private const val SEND_FILE_NAME = "send-1.txt"
private const val ATTACHMENT_LINES = 57
private const val PASSWORD_BYTES = 15
private const val TOTP_SECRET_BYTES = 20
