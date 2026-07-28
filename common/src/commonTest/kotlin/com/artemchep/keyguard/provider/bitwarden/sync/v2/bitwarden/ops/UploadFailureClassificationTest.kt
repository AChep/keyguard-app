package com.artemchep.keyguard.provider.bitwarden.sync.v2.bitwarden.ops

import com.artemchep.keyguard.common.exception.HttpException
import io.ktor.http.HttpStatusCode
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UploadFailureClassificationTest {
    @Test
    fun `bad request known phrase is terminal regardless of casing`() {
        val error = httpError(
            status = HttpStatusCode.BadRequest,
            message = "max FILE size is 500 mb.",
        )

        assertTrue(error.isNonRetryableCipherAttachmentUploadError())
        assertTrue(error.isNonRetryableSendFileUploadError())
    }

    @Test
    fun `forbidden known phrase is terminal`() {
        val cipherError = httpError(
            status = HttpStatusCode.Forbidden,
            message = "You do not have permissions to edit this.",
        )
        val sendError = httpError(
            status = HttpStatusCode.Forbidden,
            message = "You must have premium status to use file Sends.",
        )

        assertTrue(cipherError.isNonRetryableCipherAttachmentUploadError())
        assertTrue(sendError.isNonRetryableSendFileUploadError())
    }

    @Test
    fun `not found known phrase is terminal regardless of casing`() {
        val cipherError = httpError(
            status = HttpStatusCode.NotFound,
            message = "cipher ATTACHMENT does not exist.",
        )
        val sendError = httpError(
            status = HttpStatusCode.NotFound,
            message = "send DOES not HAVE file DATA.",
        )

        assertTrue(cipherError.isNonRetryableCipherAttachmentUploadError())
        assertTrue(sendError.isNonRetryableSendFileUploadError())
    }

    @Test
    fun `conflict known phrase is terminal`() {
        val error = httpError(
            status = HttpStatusCode.Conflict,
            message = "Invalid content.",
        )

        assertTrue(error.isNonRetryableCipherAttachmentUploadError())
        assertTrue(error.isNonRetryableSendFileUploadError())
    }

    @Test
    fun `direct upload terminal status is terminal without english message`() {
        val cipherError = httpError(
            status = HttpStatusCode.BadRequest,
            message = "localized server validation text",
            route = "post-cipher-attachment-data",
        )
        val sendError = httpError(
            status = HttpStatusCode.Conflict,
            message = "localized server validation text",
            route = "post-send-file-data",
        )

        assertTrue(cipherError.isNonRetryableCipherAttachmentUploadError())
        assertTrue(sendError.isNonRetryableSendFileUploadError())
    }

    @Test
    fun `azure terminal storage error code is terminal without english message`() {
        val error = httpError(
            status = HttpStatusCode.Conflict,
            message = "localized azure storage text",
            errorCode = "BlobAlreadyExists",
        )

        assertTrue(error.isNonRetryableCipherAttachmentUploadError())
        assertTrue(error.isNonRetryableSendFileUploadError())
    }

    @Test
    fun `azure authentication storage error code is not terminal`() {
        val error = httpError(
            status = HttpStatusCode.Forbidden,
            message = "localized azure storage text",
            errorCode = "AuthenticationFailed",
        )

        assertFalse(error.isNonRetryableCipherAttachmentUploadError())
        assertFalse(error.isNonRetryableSendFileUploadError())
    }

    @Test
    fun `forbidden and not found generic messages are not terminal`() {
        val forbiddenError = httpError(
            status = HttpStatusCode.Forbidden,
            message = "Forbidden",
        )
        val notFoundError = httpError(
            status = HttpStatusCode.NotFound,
            message = "Not Found",
        )
        val directNotFoundError = httpError(
            status = HttpStatusCode.NotFound,
            message = "Not Found",
            route = "post-send-file-data",
        )

        assertFalse(forbiddenError.isNonRetryableCipherAttachmentUploadError())
        assertFalse(notFoundError.isNonRetryableCipherAttachmentUploadError())
        assertFalse(directNotFoundError.isNonRetryableCipherAttachmentUploadError())
        assertFalse(forbiddenError.isNonRetryableSendFileUploadError())
        assertFalse(notFoundError.isNonRetryableSendFileUploadError())
        assertFalse(directNotFoundError.isNonRetryableSendFileUploadError())
    }

    @Test
    fun `retryable status codes are not terminal even with known phrase`() {
        val tooManyRequestsError = httpError(
            status = HttpStatusCode.TooManyRequests,
            message = "Max file size is 500 MB.",
        )
        val internalServerError = httpError(
            status = HttpStatusCode.InternalServerError,
            message = "Max file size is 500 MB.",
        )

        assertFalse(tooManyRequestsError.isNonRetryableCipherAttachmentUploadError())
        assertFalse(internalServerError.isNonRetryableCipherAttachmentUploadError())
        assertFalse(tooManyRequestsError.isNonRetryableSendFileUploadError())
        assertFalse(internalServerError.isNonRetryableSendFileUploadError())
    }

    private fun httpError(
        status: HttpStatusCode,
        message: String,
        errorCode: String? = null,
        route: String? = null,
    ) = HttpException(
        statusCode = status,
        m = message,
        e = null,
        errorCode = errorCode,
        route = route,
    )
}
