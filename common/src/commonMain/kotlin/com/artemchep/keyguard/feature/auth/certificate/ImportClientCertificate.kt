package com.artemchep.keyguard.feature.auth.certificate

import com.artemchep.keyguard.common.io.runCatchingNonFatal
import com.artemchep.keyguard.common.io.throwIfFatalOrCancellation
import com.artemchep.keyguard.common.model.ToastMessage
import com.artemchep.keyguard.common.service.file.FileAccessToken
import com.artemchep.keyguard.common.service.file.FileService
import com.artemchep.keyguard.feature.confirmation.ConfirmationRoute
import com.artemchep.keyguard.feature.confirmation.ConfirmationRouteFactory
import com.artemchep.keyguard.feature.confirmation.awaitConfirmationDialog
import com.artemchep.keyguard.feature.filepicker.FilePickerResult
import com.artemchep.keyguard.feature.navigation.state.RememberStateFlowScope
import com.artemchep.keyguard.nativecrypto.NativeCryptoPkcs12
import com.artemchep.keyguard.platform.recordException
import com.artemchep.keyguard.provider.bitwarden.transport.MutualTlsImportResult
import com.artemchep.keyguard.provider.bitwarden.transport.MutualTlsService
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.addaccount_mutual_tls_status_failed
import com.artemchep.keyguard.res.client_certificate_too_large
import com.artemchep.keyguard.res.error_incorrect_password
import com.artemchep.keyguard.res.pkcs12_password
import com.artemchep.keyguard.res.pkcs12_password_note
import com.artemchep.keyguard.res.pkcs12_password_title
import com.artemchep.keyguard.util.io.SourceTooLargeException
import com.artemchep.keyguard.util.io.limitedTo
import kotlinx.coroutines.IO
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.io.readByteArray

/** Keeps file access and passwords inside the producer, never in saved screen state. */
@Suppress("TooGenericExceptionCaught") // File providers and native parsers can fail with platform exceptions.
suspend fun RememberStateFlowScope.importClientCertificate(
    info: FilePickerResult,
    fileService: FileService,
    mutualTlsService: MutualTlsService,
    confirmationRouteFactory: ConfirmationRouteFactory,
): MutualTlsImportResult.Success? {
    var bytes: ByteArray? = null
    return try {
        withContext(Dispatchers.IO) {
            val maxBytes = NativeCryptoPkcs12.MAX_BUNDLE_BYTES.toLong()
            if (info.size != null && info.size > maxBytes) {
                throw SourceTooLargeException(maxBytes)
            }
            fileService.readFromFile(info.uri.toString(), info.accessToken?.let(::FileAccessToken)) { source ->
                bytes = source.limitedTo(maxBytes).readByteArray()
            }
        }
        val source = requireNotNull(bytes)
        var result = withContext(Dispatchers.Default) { mutualTlsService.importPkcs12(source, null) }
        var passwordAttempt = 0
        while (result is MutualTlsImportResult.PasswordRequired || result is MutualTlsImportResult.InvalidPassword) {
            val password = awaitClientCertificatePassword(
                confirmationRouteFactory = confirmationRouteFactory,
                attempt = passwordAttempt++,
                incorrect = result is MutualTlsImportResult.InvalidPassword,
            ) ?: return null
            result = withContext(Dispatchers.Default) { mutualTlsService.importPkcs12(source, password) }
        }
        if (result is MutualTlsImportResult.Success) {
            result
        } else {
            message(ToastMessage(
                type = ToastMessage.Type.ERROR,
                title = translate(Res.string.addaccount_mutual_tls_status_failed),
            ))
            null
        }
    } catch (e: Exception) {
        e.throwIfFatalOrCancellation()
        message(ToastMessage(
            type = ToastMessage.Type.ERROR,
            title = translate(
                if (e is SourceTooLargeException) Res.string.client_certificate_too_large
                else Res.string.addaccount_mutual_tls_status_failed,
            ),
        ))
        null
    } finally {
        bytes?.fill(0)
        // iOS drops arrive as managed copies. They must not survive the import,
        // including cancellation while waiting for the bundle's password.
        withContext(NonCancellable + Dispatchers.IO) {
            runCatchingNonFatal {
                fileService.deleteManagedSourceFile(info.uri.toString())
            }.onFailure(::recordException)
        }
    }
}

private suspend fun RememberStateFlowScope.awaitClientCertificatePassword(
    confirmationRouteFactory: ConfirmationRouteFactory,
    attempt: Int,
    incorrect: Boolean,
): String? = awaitConfirmationDialog(
    confirmationRouteFactory = confirmationRouteFactory,
    item = ConfirmationRoute.Args.Item.StringItem(
        key = "client_certificate_password:$attempt",
        title = translate(Res.string.pkcs12_password),
        hint = translate(Res.string.pkcs12_password),
        type = ConfirmationRoute.Args.Item.StringItem.Type.Password,
        canBeEmpty = true,
        ephemeral = true,
    ),
    title = translate(Res.string.pkcs12_password_title),
    message = translate(
        if (incorrect) Res.string.error_incorrect_password else Res.string.pkcs12_password_note,
    ),
)
