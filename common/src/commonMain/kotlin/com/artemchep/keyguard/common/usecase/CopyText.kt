package com.artemchep.keyguard.common.usecase

import com.artemchep.keyguard.common.model.ToastMessage
import com.artemchep.keyguard.common.service.clipboard.ClipboardService
import com.artemchep.keyguard.feature.navigation.state.TranslatorScope
import com.artemchep.keyguard.res.Res
import dev.icerock.moko.resources.StringResource

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
        VALUE(Res.strings.copied_value),
        URL(Res.strings.copied_url),
        URI(Res.strings.copied_uri),
        PACKAGE_NAME(Res.strings.copied_package_name),
        PASSWORD(Res.strings.copied_password),
        USERNAME(Res.strings.copied_username),
        EMAIL(Res.strings.copied_email),
        PHONE_NUMBER(Res.strings.copied_phone_number),
        PASSPORT_NUMBER(Res.strings.copied_passport_number),
        LICENSE_NUMBER(Res.strings.copied_license_number),
        CARD_NUMBER(Res.strings.copied_card_number),
        CARD_CARDHOLDER_NAME(Res.strings.copied_cardholder_name),
        CARD_EXP_YEAR(Res.strings.copied_expiration_year),
        CARD_EXP_MONTH(Res.strings.copied_expiration_month),
        CARD_CVV(Res.strings.copied_cvv_code),
        OTP(Res.strings.copied_otp_code),
        OTP_SECRET(Res.strings.copied_otp_secret_code),
    }

    fun copy(
        text: String,
        hidden: Boolean,
        type: Type = Type.VALUE,
    ) {
        clipboardService.setPrimaryClip(text, concealed = hidden)
        if (!clipboardService.hasCopyNotification()) {
            val message = ToastMessage(
                title = translator.translate(type.res),
                text = text.takeUnless { hidden },
            )
            onMessage(message)
        }
    }
}
