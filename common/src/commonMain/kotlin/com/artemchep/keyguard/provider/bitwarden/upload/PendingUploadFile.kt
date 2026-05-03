package com.artemchep.keyguard.provider.bitwarden.upload

import kotlinx.serialization.Serializable

/**
 * Metadata for an encrypted file that has been staged locally and is waiting
 * for a future sync upload.
 *
 * The file at [path] already contains encrypted attachment/send bytes. Sync
 * uploads this file as-is and uses [encryptedSize] as the HTTP body length. UI
 * and Bitwarden metadata use [plainSize] when they need the original file size.
 *
 * This object is serializable because it can be stored in local cipher/send
 * models while the actual upload is deferred or retried.
 *
 * Example:
 * ```
 * val pendingUpload = PendingUploadFile(
 *     path = "/data/user/0/com.example/files/pending/account-1/send_uploads/send-1.bin",
 *     plainSize = 4096L,
 *     encryptedSize = 4145L,
 * )
 *
 * uploadSendFile(
 *     filePath = pendingUpload.path,
 *     fileLength = pendingUpload.encryptedSize,
 * )
 * ```
 *
 * @property path Absolute local filesystem path to the encrypted staged file.
 * This is a local path, not a `file:` URI.
 * @property plainSize Size of the original source file before encryption.
 * @property encryptedSize Size of the staged encrypted file on disk.
 * @property remoteId Optional remote upload reservation id. Cipher attachment
 * uploads use this to renew an interrupted server-side reservation instead of
 * creating a duplicate placeholder attachment on retry.
 *
 * @see PendingUploadCoordinator.stage
 */
@Serializable
data class PendingUploadFile(
    val path: String,
    val plainSize: Long,
    val encryptedSize: Long,
    val remoteId: String? = null,
)
