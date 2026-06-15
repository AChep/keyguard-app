package com.artemchep.keyguard.provider.bitwarden.sync.v2.ops

import com.artemchep.keyguard.common.exception.HttpException
import io.ktor.http.HttpStatusCode

private val nonRetryableCipherAttachmentMessages =
    listOf(
        "Max file size is 500 MB",
        "You do not have permissions to edit this",
        "No data to attach",
        "Not enough storage available",
        "You must have premium status to use attachments",
        "This organization cannot use attachments",
        "Not enough storage available for this organization",
        "Invalid content",
        "Cipher attachment does not exist",
        "File received does not match expected file length",
        "Attachments are disabled",
        "Attachment storage limit reached",
        "Attachment storage limit exceeded with this file",
        "Attachment size mismatch",
        "Cipher is not write accessible",
        "Cipher doesn't exist",
    )

private val nonRetryableSendFileMessages =
    listOf(
        "Invalid content",
        "Invalid content. File size hint is required",
        "Max file size is 500 MB",
        "File metadata is required for file sends",
        "Email verified Sends require a premium membership",
        "Send is not of type \"file\"",
        "No file data",
        "Not enough storage available",
        "Due to an Enterprise Policy",
        "You must have premium status to use file Sends",
        "You must confirm your email to use file Sends",
        "This organization cannot use file sends",
        "You cannot create a Send that is already expired",
        "You cannot have a Send with a deletion date in the past",
        "You cannot have a Send with a deletion date that far",
        "You cannot have a Send with an expiration date in the past",
        "You cannot have a Send with an expiration date greater than the deletion date",
        "You cannot save a Send having an invalid AuthType",
        "Send does not have file data",
        "Not a File Type Send",
        "File has already been uploaded",
        "File received does not match expected file length",
        "File uploads are disabled",
        "Send storage limit reached",
        "Send storage limit exceeded with this file",
        "Send content is not a file",
        "Send is not a file type send",
        "Send file size does not match",
    )

internal fun Throwable.isNonRetryableCipherAttachmentUploadError(): Boolean =
    hasTerminalUploadMessage(nonRetryableCipherAttachmentMessages)

internal fun Throwable.isNonRetryableSendFileUploadError(): Boolean =
    hasTerminalUploadMessage(nonRetryableSendFileMessages)

private val terminalUploadFailureStatuses =
    setOf(
        HttpStatusCode.BadRequest,
        HttpStatusCode.Forbidden,
        HttpStatusCode.NotFound,
        HttpStatusCode.Conflict,
    )

private val terminalDirectUploadFailureStatuses =
    setOf(
        HttpStatusCode.BadRequest,
        HttpStatusCode.Conflict,
    )

private val directUploadDataRoutes =
    setOf(
        "post-cipher-attachment-data",
        "post-send-file-data",
    )

private val terminalAzureStorageErrorCodes =
    setOf(
        "BlobAlreadyExists",
        "BlobImmutableDueToLegalHold",
        "BlobImmutableDueToPolicy",
        "BlobTierInadequateForContentLength",
        "ContentLengthLargerThanTierLimit",
        "InvalidBlobOrBlock",
        "InvalidBlobType",
        "InvalidBlockList",
        "UnauthorizedBlobOverwrite",
        "UnsupportedHeader",
    )

private fun Throwable.hasTerminalUploadMessage(
    messages: List<String>,
): Boolean {
    val error = this as? HttpException
        ?: return false
    if (error.hasTerminalStructuredUploadFailure()) {
        return true
    }
    if (error.hasTerminalDirectUploadFailureStatus()) {
        return true
    }
    return error.statusCode in terminalUploadFailureStatuses &&
        error.hasKnownTerminalUploadMessage(messages)
}

private fun HttpException.hasTerminalStructuredUploadFailure(): Boolean =
    errorCode?.let { code ->
        terminalAzureStorageErrorCodes.any { terminalCode ->
            terminalCode.equals(code, ignoreCase = true)
        }
    } == true

private fun HttpException.hasTerminalDirectUploadFailureStatus(): Boolean =
    route in directUploadDataRoutes &&
        statusCode in terminalDirectUploadFailureStatuses

private fun HttpException.hasKnownTerminalUploadMessage(
    messages: List<String>,
): Boolean =
    listOfNotNull(message)
        .any { text ->
            messages.any { message ->
                text.contains(message, ignoreCase = true)
            }
        }
