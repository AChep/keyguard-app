package com.artemchep.keyguard.common.usecase

import com.artemchep.keyguard.common.model.ToastMessage
import com.artemchep.keyguard.common.service.clipboard.ClipboardService
import com.artemchep.keyguard.feature.navigation.state.TranslatorScope
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
) {
    enum class Type(
       val res: StringResource,
    ) {
        VALUE(Res.string.copied_value),
        URL(Res.string.copied_url),
        URI(Res.string.copied_uri),
        PACKAGE_NAME(Res.string.copied_package_name),
        KEY(Res.string.copied_key),
        FINGERPRINT(Res.string.copied_fingerprint),
        PASSWORD(Res.string.copied_password),
        USERNAME(Res.string.copied_username),
        EMAIL(Res.string.copied_email),
        PHONE_NUMBER(Res.string.copied_phone_number),
        PASSPORT_NUMBER(Res.string.copied_passport_number),
        LICENSE_NUMBER(Res.string.copied_license_number),
        CARD_NUMBER(Res.string.copied_card_number),
        CARD_CARDHOLDER_NAME(Res.string.copied_cardholder_name),
        CARD_EXP_YEAR(Res.string.copied_expiration_year),
        CARD_EXP_MONTH(Res.string.copied_expiration_month),
        CARD_CVV(Res.string.copied_cvv_code),
        OTP(Res.string.copied_otp_code),
        OTP_SECRET(Res.string.copied_otp_secret_code),
    }

    fun copy(
        text: String,
        hidden: Boolean,
        type: Type = Type.VALUE,
    ) {
        clipboardService.setPrimaryClip(text, concealed = hidden)
        if (!clipboardService.hasCopyNotification()) {
            GlobalScope.launch {
                val message = ToastMessage(
                    title = translator.translate(type.res),
                    text = text.takeUnless { hidden },
                )
                onMessage(message)
            }
        }
    }
}
