package com.artemchep.keyguard.common.usecase

import androidx.compose.runtime.State
import com.artemchep.keyguard.common.model.ToastMessage
import com.artemchep.keyguard.common.service.clipboard.ClipboardEvent
import com.artemchep.keyguard.common.service.clipboard.ClipboardEventBus
import com.artemchep.keyguard.common.service.clipboard.ClipboardService
import com.artemchep.keyguard.feature.navigation.state.TranslatorScope
import com.artemchep.keyguard.platform.WindowId
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.StringResource

/**
 * @author Artem Chepurnyi
 */
class CopyText(
    private val clipboardService: ClipboardService,
    private val translator: TranslatorScope,
    private val onMessage: (ToastMessage) -> Unit,
    private val clipboardEventBus: ClipboardEventBus? = null,
    private val windowIdState: State<WindowId>? = null,
) {
    enum class Type(
       val res: StringResource,
       val actionRes: StringResource,
    ) {
        VALUE(Res.string.copied_value, Res.string.copy_value),
        URL(Res.string.copied_url, Res.string.copy_url),
        URI(Res.string.copied_uri, Res.string.copy_uri),
        PACKAGE_NAME(Res.string.copied_package_name, Res.string.copy_package_name),
        PRIVATE_KEY(Res.string.copied_key, Res.string.copy_ssh_unencrypted_private_key),
        PUBLIC_KEY(Res.string.copied_key, Res.string.copy_ssh_public_key),
        FINGERPRINT(Res.string.copied_fingerprint, Res.string.copy_ssh_fingerprint),
        PASSWORD(Res.string.copied_password, Res.string.copy_password),
        USERNAME(Res.string.copied_username, Res.string.copy_username),
        EMAIL(Res.string.copied_email, Res.string.copy_email),
        PHONE_NUMBER(Res.string.copied_phone_number, Res.string.copy_phone_number),
        PASSPORT_NUMBER(Res.string.copied_passport_number, Res.string.copy_passport_number),
        LICENSE_NUMBER(Res.string.copied_license_number, Res.string.copy_license_number),
        CARD_NUMBER(Res.string.copied_card_number, Res.string.copy_card_number),
        CARD_CARDHOLDER_NAME(Res.string.copied_cardholder_name, Res.string.copy_cardholder_name),
        CARD_EXP_YEAR(Res.string.copied_expiration_year, Res.string.copy_expiration_year),
        CARD_EXP_MONTH(Res.string.copied_expiration_month, Res.string.copy_expiration_month),
        CARD_CVV(Res.string.copied_cvv_code, Res.string.copy_cvv_code),
        OTP(Res.string.copied_otp_code, Res.string.copy_otp_code),
        OTP_SECRET(Res.string.copied_otp_secret_code, Res.string.copy_otp_secret_code),
    }

    fun copy(
        text: String,
        hidden: Boolean,
        type: Type = Type.VALUE,
    ) {
        val windowId = windowIdState?.value

        clipboardService.setPrimaryClip(text, concealed = hidden)
        if (!clipboardService.hasCopyNotification()) {
            GlobalScope.launch {
                val message = ToastMessage(
                    title = translator.translate(type.res),
                    text = text.takeUnless { hidden },
                    windowId = windowId,
                )
                onMessage(message)
            }
        }

        if (windowId != null) {
            val event = ClipboardEvent.Copy(
                windowId = windowId,
            )
            clipboardEventBus?.post(event)
        }
    }
}
