package com.artemchep.keyguard.android.autofill

import android.app.assist.AssistStructure
import android.os.Build
import android.text.InputType
import android.util.Log
import android.view.View
import android.view.autofill.AutofillId
import androidx.autofill.HintConstants
import com.artemchep.keyguard.android.autofill.AutofillStructureParser.AutofillStructure
import com.artemchep.keyguard.common.model.AutofillHint
import java.util.Locale

/**
 * Parse AssistStructure into more usable
 * [AutofillStructure].
 */
class AutofillStructureParser {
    data class AutofillStructure(
        val applicationId: String? = null,
        val webScheme: String? = null,
        val webDomain: String? = null,
        val webView: Boolean = false,
        val items: List<AutofillStructureItem>,
    )

    data class AutofillStructureItem(
        val accuracy: Accuracy,
        val id: AutofillId,
        val hint: AutofillHint,
        val value: String? = null,
        val reason: String? = null,
    ) {
        enum class Accuracy(
            val value: Float,
        ) {
            LOWEST(0.3f),
            LOW(0.7f),
            MEDIUM(1.5f),
            HIGH(4f),
            HIGHEST(10f),
        }
    }

    data class AutofillStructureItemBuilder(
        val accuracy: AutofillStructureItem.Accuracy,
        val hint: AutofillHint,
        val value: String? = null,
        val reason: String? = null,
    )

    class AutofillHintMatcher(
        val hint: AutofillHint,
        val target: String,
        val partly: Boolean = false,
    ) {
        val accuracy =
            if (partly) AutofillStructureItem.Accuracy.MEDIUM else AutofillStructureItem.Accuracy.HIGH

        fun matches(value: String) = if (partly) {
            value.contains(target, ignoreCase = true)
        } else {
            value.equals(target, ignoreCase = true)
        }
    }

    private val autofillLabelPasswordTranslations = listOf(
        "password",
        "парол",
        "parol",
        "passwort",
        "passe",
        // https://github.com/AChep/keyguard-app/issues/15#issuecomment-1740598689
        "密码",
        "密碼",
    )

    private val autofillLabelEmailTranslations = listOf(
        "email",
        "e-mail",
        "почта",
        "пошта",
        "мейл",
        "мэйл",
        "майл",
        // https://github.com/AChep/keyguard-app/issues/15#issuecomment-1740598689
        "电子邮箱",
        "電子郵箱",
    )

    private val autofillLabelUsernameTranslations = listOf(
        "nickname",
        "username",
        "utilisateur",
        "login",
        "логин",
        "логін",
        "користувач",
        "пользовател",
        // https://github.com/AChep/keyguard-app/issues/15#issuecomment-1740598689
        "用户名",
        "用戶名",
    )

    private val autofillLabelCreditCardNumberTranslations = listOf(
        ".*(credit|debit|card)+.*number.*".toRegex(),
    )

    private val autofillHintMatchers = listOf(
        AutofillHintMatcher(
            hint = AutofillHint.EMAIL_ADDRESS,
            target = HintConstants.AUTOFILL_HINT_EMAIL_ADDRESS,
        ),
        AutofillHintMatcher(
            hint = AutofillHint.EMAIL_ADDRESS,
            target = "email",
            partly = true,
        ),
        AutofillHintMatcher(
            hint = AutofillHint.USERNAME,
            target = HintConstants.AUTOFILL_HINT_USERNAME,
        ),
        AutofillHintMatcher(
            hint = AutofillHint.USERNAME,
            target = "nickname",
        ),
        AutofillHintMatcher(
            hint = AutofillHint.PASSWORD,
            target = HintConstants.AUTOFILL_HINT_PASSWORD,
        ),
        AutofillHintMatcher(
            hint = AutofillHint.PASSWORD,
            target = "password",
            partly = true,
        ),
        AutofillHintMatcher(
            hint = AutofillHint.WIFI_PASSWORD,
            target = HintConstants.AUTOFILL_HINT_WIFI_PASSWORD,
        ),
        AutofillHintMatcher(
            hint = AutofillHint.POSTAL_ADDRESS,
            target = HintConstants.AUTOFILL_HINT_POSTAL_ADDRESS,
        ),
        AutofillHintMatcher(
            hint = AutofillHint.POSTAL_CODE,
            target = HintConstants.AUTOFILL_HINT_POSTAL_CODE,
        ),
        AutofillHintMatcher(
            hint = AutofillHint.CREDIT_CARD_NUMBER,
            target = HintConstants.AUTOFILL_HINT_CREDIT_CARD_NUMBER,
        ),
        AutofillHintMatcher(
            hint = AutofillHint.CREDIT_CARD_NUMBER,
            target = "cc-number",
        ),
        AutofillHintMatcher(
            hint = AutofillHint.CREDIT_CARD_NUMBER,
            target = "credit_card_number",
        ),
        AutofillHintMatcher(
            hint = AutofillHint.CREDIT_CARD_SECURITY_CODE,
            target = HintConstants.AUTOFILL_HINT_CREDIT_CARD_SECURITY_CODE,
        ),
        AutofillHintMatcher(
            hint = AutofillHint.CREDIT_CARD_SECURITY_CODE,
            target = "cc-csc",
        ),
        AutofillHintMatcher(
            hint = AutofillHint.CREDIT_CARD_NUMBER,
            target = "credit_card_csv",
        ),
        AutofillHintMatcher(
            hint = AutofillHint.CREDIT_CARD_EXPIRATION_DATE,
            target = HintConstants.AUTOFILL_HINT_CREDIT_CARD_EXPIRATION_DATE,
        ),
        AutofillHintMatcher(
            hint = AutofillHint.CREDIT_CARD_EXPIRATION_DATE,
            target = "cc-exp",
        ),
        AutofillHintMatcher(
            hint = AutofillHint.CREDIT_CARD_EXPIRATION_MONTH,
            target = HintConstants.AUTOFILL_HINT_CREDIT_CARD_EXPIRATION_MONTH,
        ),
        AutofillHintMatcher(
            hint = AutofillHint.CREDIT_CARD_EXPIRATION_MONTH,
            target = "cc-exp-month",
        ),
        AutofillHintMatcher(
            hint = AutofillHint.CREDIT_CARD_EXPIRATION_YEAR,
            target = HintConstants.AUTOFILL_HINT_CREDIT_CARD_EXPIRATION_YEAR,
        ),
        AutofillHintMatcher(
            hint = AutofillHint.CREDIT_CARD_EXPIRATION_YEAR,
            target = "cc-exp-year",
        ),
        AutofillHintMatcher(
            hint = AutofillHint.CREDIT_CARD_EXPIRATION_DAY,
            target = HintConstants.AUTOFILL_HINT_CREDIT_CARD_EXPIRATION_DAY,
        ),
        AutofillHintMatcher(
            hint = AutofillHint.POSTAL_ADDRESS_COUNTRY,
            target = HintConstants.AUTOFILL_HINT_POSTAL_ADDRESS_COUNTRY,
        ),
        AutofillHintMatcher(
            hint = AutofillHint.POSTAL_ADDRESS_REGION,
            target = HintConstants.AUTOFILL_HINT_POSTAL_ADDRESS_REGION,
        ),
        AutofillHintMatcher(
            hint = AutofillHint.POSTAL_ADDRESS_LOCALITY,
            target = HintConstants.AUTOFILL_HINT_POSTAL_ADDRESS_LOCALITY,
        ),
        AutofillHintMatcher(
            hint = AutofillHint.POSTAL_ADDRESS_STREET_ADDRESS,
            target = HintConstants.AUTOFILL_HINT_POSTAL_ADDRESS_STREET_ADDRESS,
        ),
        AutofillHintMatcher(
            hint = AutofillHint.POSTAL_ADDRESS_EXTENDED_ADDRESS,
            target = HintConstants.AUTOFILL_HINT_POSTAL_ADDRESS_EXTENDED_ADDRESS,
        ),
        AutofillHintMatcher(
            hint = AutofillHint.POSTAL_ADDRESS_EXTENDED_POSTAL_CODE,
            target = HintConstants.AUTOFILL_HINT_POSTAL_ADDRESS_EXTENDED_POSTAL_CODE,
        ),
        AutofillHintMatcher(
            hint = AutofillHint.POSTAL_ADDRESS_APT_NUMBER,
            target = HintConstants.AUTOFILL_HINT_POSTAL_ADDRESS_APT_NUMBER,
        ),
        AutofillHintMatcher(
            hint = AutofillHint.POSTAL_ADDRESS_DEPENDENT_LOCALITY,
            target = HintConstants.AUTOFILL_HINT_POSTAL_ADDRESS_DEPENDENT_LOCALITY,
        ),
        AutofillHintMatcher(
            hint = AutofillHint.PERSON_NAME,
            target = HintConstants.AUTOFILL_HINT_PERSON_NAME,
        ),
        AutofillHintMatcher(
            hint = AutofillHint.PERSON_NAME_GIVEN,
            target = HintConstants.AUTOFILL_HINT_PERSON_NAME_GIVEN,
        ),
        AutofillHintMatcher(
            hint = AutofillHint.PERSON_NAME_FAMILY,
            target = HintConstants.AUTOFILL_HINT_PERSON_NAME_FAMILY,
        ),
        AutofillHintMatcher(
            hint = AutofillHint.PERSON_NAME_MIDDLE,
            target = HintConstants.AUTOFILL_HINT_PERSON_NAME_MIDDLE,
        ),
        AutofillHintMatcher(
            hint = AutofillHint.PERSON_NAME_MIDDLE_INITIAL,
            target = HintConstants.AUTOFILL_HINT_PERSON_NAME_MIDDLE_INITIAL,
        ),
        AutofillHintMatcher(
            hint = AutofillHint.PERSON_NAME_PREFIX,
            target = HintConstants.AUTOFILL_HINT_PERSON_NAME_PREFIX,
        ),
        AutofillHintMatcher(
            hint = AutofillHint.PERSON_NAME_SUFFIX,
            target = HintConstants.AUTOFILL_HINT_PERSON_NAME_SUFFIX,
        ),
        AutofillHintMatcher(
            hint = AutofillHint.PHONE_NUMBER,
            target = HintConstants.AUTOFILL_HINT_PHONE_NUMBER,
        ),
        AutofillHintMatcher(
            hint = AutofillHint.PHONE_NUMBER_DEVICE,
            target = HintConstants.AUTOFILL_HINT_PHONE_NUMBER_DEVICE,
        ),
        AutofillHintMatcher(
            hint = AutofillHint.PHONE_COUNTRY_CODE,
            target = HintConstants.AUTOFILL_HINT_PHONE_COUNTRY_CODE,
        ),
        AutofillHintMatcher(
            hint = AutofillHint.PHONE_NATIONAL,
            target = HintConstants.AUTOFILL_HINT_PHONE_NATIONAL,
        ),
        AutofillHintMatcher(
            hint = AutofillHint.PHONE_NUMBER,
            target = "phone",
        ),
        AutofillHintMatcher(
            hint = AutofillHint.NEW_USERNAME,
            target = HintConstants.AUTOFILL_HINT_NEW_USERNAME,
        ),
        AutofillHintMatcher(
            hint = AutofillHint.NEW_USERNAME,
            target = "new-username",
        ),
        AutofillHintMatcher(
            hint = AutofillHint.NEW_PASSWORD,
            target = HintConstants.AUTOFILL_HINT_NEW_PASSWORD,
        ),
        AutofillHintMatcher(
            hint = AutofillHint.NEW_PASSWORD,
            target = "new-password",
        ),
        AutofillHintMatcher(
            hint = AutofillHint.GENDER,
            target = HintConstants.AUTOFILL_HINT_GENDER,
        ),
        AutofillHintMatcher(
            hint = AutofillHint.BIRTH_DATE_FULL,
            target = HintConstants.AUTOFILL_HINT_BIRTH_DATE_FULL,
        ),
        AutofillHintMatcher(
            hint = AutofillHint.BIRTH_DATE_DAY,
            target = HintConstants.AUTOFILL_HINT_BIRTH_DATE_DAY,
        ),
        AutofillHintMatcher(
            hint = AutofillHint.BIRTH_DATE_MONTH,
            target = HintConstants.AUTOFILL_HINT_BIRTH_DATE_MONTH,
        ),
        AutofillHintMatcher(
            hint = AutofillHint.BIRTH_DATE_YEAR,
            target = HintConstants.AUTOFILL_HINT_BIRTH_DATE_YEAR,
        ),
        AutofillHintMatcher(
            hint = AutofillHint.SMS_OTP,
            target = HintConstants.AUTOFILL_HINT_SMS_OTP,
        ),
        AutofillHintMatcher(
            hint = AutofillHint.EMAIL_OTP,
            target = HintConstants.AUTOFILL_HINT_EMAIL_OTP,
        ),
        AutofillHintMatcher(
            hint = AutofillHint.APP_OTP,
            target = HintConstants.AUTOFILL_HINT_2FA_APP_OTP,
        ),
        AutofillHintMatcher(
            hint = AutofillHint.APP_OTP,
            target = "one-time-code",
        ),
        AutofillHintMatcher(
            hint = AutofillHint.NOT_APPLICABLE,
            target = HintConstants.AUTOFILL_HINT_NOT_APPLICABLE,
        ),
        AutofillHintMatcher(
            hint = AutofillHint.PROMO_CODE,
            target = HintConstants.AUTOFILL_HINT_PROMO_CODE,
        ),
        AutofillHintMatcher(
            hint = AutofillHint.UPI_VPA,
            target = HintConstants.AUTOFILL_HINT_UPI_VPA,
        ),
        // off
        AutofillHintMatcher(
            hint = AutofillHint.OFF,
            target = "chrome-off",
        ),
        AutofillHintMatcher(
            hint = AutofillHint.OFF,
            target = "off",
        ),
        AutofillHintMatcher(
            hint = AutofillHint.OFF,
            target = "no",
        ),
        AutofillHintMatcher(
            hint = AutofillHint.OFF,
            target = "nope",
        ),
    )

    fun parse(
        assistStructure: AssistStructure,
        respectAutofillOff: Boolean,
    ): AutofillStructure2 {
        var applicationId: String? = null
        var autofillStructure: AutofillStructure? = null

        for (i in 0 until assistStructure.windowNodeCount) {
            val windowNode = assistStructure.getWindowNodeAt(i)
            val appIdCandidate = windowNode.title.toString().split("/").first()
            if (appIdCandidate.contains(":")) {
                continue
            }

            applicationId = appIdCandidate
            // Parse view node
            val nodeAutofillStructure = parseViewNode(windowNode.rootViewNode)
            val nodeAutofillStructureHasItems = nodeAutofillStructure.items
                .any { it.hint != AutofillHint.OFF }
            if (nodeAutofillStructureHasItems) {
                autofillStructure = nodeAutofillStructure
                break
            }
        }

        class TempItem(
            val score: Float,
            val hint: AutofillHint,
            val value: String?,
        )

        val items = mutableListOf<AutofillStructure2.Item>()
        autofillStructure?.items.orEmpty()
            .let { list ->
                // We are solving the problem that the app actually detect message fields in
                // Slack/Signal/other apps as username fields. If the accuracy is low and we
                // do not have a password, then this is most likely a mistake.
                val onlyLowAccuracy = list.all {
                    it.accuracy <= AutofillStructureItem.Accuracy.LOW ||
                            it.hint == AutofillHint.OFF
                }
                if (onlyLowAccuracy) {
                    val hasUsernameAndPassword =
                        list.any {
                            val username = it.hint == AutofillHint.USERNAME ||
                                    it.hint == AutofillHint.EMAIL_ADDRESS ||
                                    it.hint == AutofillHint.PHONE_NUMBER
                            username && it.accuracy > AutofillStructureItem.Accuracy.LOWEST
                        } && list.any {
                            val password = it.hint == AutofillHint.PASSWORD
                            password && it.accuracy > AutofillStructureItem.Accuracy.LOWEST
                        }
                    if (!hasUsernameAndPassword) {
                        return@let emptyList()
                    }
                }
                list
            }
            .groupBy { it.id }
            .forEach {
                // If the autofill-off flag is marked with a highest
                // accuracy then we always respect it.
                val forceAutofillOff = it.value.any {
                    it.hint == AutofillHint.OFF &&
                            it.accuracy == AutofillStructureItem.Accuracy.HIGHEST
                }
                var structureItems = if (forceAutofillOff) {
                    return@forEach
                } else if (respectAutofillOff) {
                    // Ignore the field if it has explicitly disabled
                    // autofill support.
                    if (it.value.any { it.hint == AutofillHint.OFF }) {
                        return@forEach
                    }
                    it.value
                } else {
                    it.value
                        .filter { it.hint != AutofillHint.OFF }
                }

                // Password type is often used on the fields that have other
                // types too, for the reason that a password field masks the input.
                // In that case, wipe out the password hints:
                val derivesOfPassword = structureItems
                    .any {
                        it.hint == AutofillHint.CREDIT_CARD_SECURITY_CODE ||
                                it.hint == AutofillHint.SMS_OTP ||
                                it.hint == AutofillHint.EMAIL_OTP ||
                                it.hint == AutofillHint.APP_OTP
                    }
                if (derivesOfPassword) {
                    structureItems = structureItems
                        .filter { it.hint != AutofillHint.PASSWORD }
                }
                val derivesOfUsername = structureItems
                    .any {
                        it.hint == AutofillHint.CREDIT_CARD_NUMBER ||
                                it.hint == AutofillHint.CREDIT_CARD_EXPIRATION_DATE ||
                                it.hint == AutofillHint.CREDIT_CARD_EXPIRATION_MONTH ||
                                it.hint == AutofillHint.CREDIT_CARD_EXPIRATION_YEAR ||
                                it.hint == AutofillHint.CREDIT_CARD_EXPIRATION_DAY
                    }
                if (derivesOfUsername) {
                    structureItems = structureItems
                        .filter { it.hint != AutofillHint.USERNAME }
                }

                val selectedItem = structureItems
                    .groupBy { it.hint }
                    .map {
                        val score = it.value.fold(0f) { y, x -> y + x.accuracy.value }
                        val value = it.value
                            .sortedByDescending { it.accuracy.value }
                            .asSequence()
                            .mapNotNull { it.value }
                            .firstOrNull()
                        TempItem(
                            score = score,
                            value = value,
                            hint = it.key,
                        )
                    }
                    .maxByOrNull { it.score }
                    ?: return@forEach
                if (selectedItem.score <= AutofillStructureItem.Accuracy.LOWEST.value + 0.1f) {
                    // If there's an item with the same hint but higher score, then
                    // skip this node.
                    val shouldSkip = autofillStructure?.items.orEmpty().any {
                        it.hint == selectedItem.hint &&
                                it.accuracy > AutofillStructureItem.Accuracy.LOWEST
                    }
                    if (shouldSkip) {
                        // For now, do not do a skip, it seems to work
                        // just fine.
                        return@forEach
                    }
                }

                val item = AutofillStructure2.Item(
                    id = it.key,
                    hint = selectedItem.hint,
                    value = selectedItem.value,
                )
                items += item
            }
        return AutofillStructure2(
            applicationId = applicationId,
            webDomain = autofillStructure?.webDomain,
            webScheme = autofillStructure?.webScheme,
            webView = autofillStructure?.webView,
            items = items,
        )
    }

    private fun parseViewNode(
        node: AssistStructure.ViewNode,
    ): AutofillStructure {
        var webView = node.className == "android.webkit.WebView"
        var webDomain: String? = node.webDomain?.takeIf { it.isNotEmpty() }
        var webScheme: String? =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                node.webScheme?.takeIf { it.isNotEmpty() }
            } else {
                null
            }

        val out = mutableListOf<AutofillStructureItem>()
        // Only parse visible nodes
        if (node.visibility == View.VISIBLE) {
            if (node.autofillId != null) {
                val outBuilders = mutableListOf<AutofillStructureItemBuilder>()
                // Parse methods
                val hints = node.autofillHints
                if (hints != null && hints.isNotEmpty()) {
                    val hintOut = parseNodeByAutofillHint(node)
                    outBuilders += hintOut
                }

                val htmlOut = parseNodeByHtmlAttributes(node)
                outBuilders += htmlOut

                val inputOut = parseNodeByAndroidInput(node)
                val hintOut = parseNodeByLabel(node)
                outBuilders += inputOut + hintOut

                out += outBuilders.map {
                    AutofillStructureItem(
                        id = node.autofillId!!,
                        accuracy = it.accuracy,
                        hint = it.hint,
                        value = it.value
                            ?: node.autofillValue?.takeIf { it.isText }?.textValue?.toString(),
                        reason = it.reason,
                    )
                }
            }
            // Recursive method to process each node
            for (i in 0 until node.childCount) {
                val childStructure = parseViewNode(node.getChildAt(i))
                if (childStructure.webView) {
                    webView = childStructure.webView
                }

                webDomain = webDomain ?: childStructure.webDomain
                webScheme = webScheme ?: childStructure.webScheme

                out += childStructure.items
            }
        }
        return AutofillStructure(
            webScheme = webScheme,
            webDomain = webDomain,
            webView = webView,
            items = out,
        )
    }

    private fun parseNodeByAutofillHint(
        node: AssistStructure.ViewNode,
    ): List<AutofillStructureItemBuilder> = kotlin.run {
        val out = mutableListOf<AutofillStructureItemBuilder>()
        node.autofillHints?.forEach { value ->
            val matchers = autofillHintMatchers
                .filter { matcher -> matcher.matches(value) }
            matchers.forEach { matcher ->
                val item = AutofillStructureItemBuilder(
                    accuracy = matcher.accuracy,
                    hint = matcher.hint,
                    reason = "autofill-hint",
                )
                out += item
            }
        }
        out
    }

    private fun parseNodeByHtmlAttributes(
        node: AssistStructure.ViewNode,
    ): List<AutofillStructureItemBuilder> = kotlin.run {
        val out = mutableListOf<AutofillStructureItemBuilder>()
        val nodHtml = node.htmlInfo
        when (nodHtml?.tag?.lowercase(Locale.ENGLISH)) {
            "input" -> {
                val attributes = kotlin.run {
                    nodHtml.attributes
                        ?.map { it.first to it.second }
                        ?.takeUnless { it.isEmpty() }
                    // For some reason some browsers do not provide the
                    // list of attributes, although I see that there's
                    // mValues and mNames in there that do contain them!
                        ?: kotlin.runCatching {
                            val values = nodHtml.javaClass.getDeclaredField("mValues")
                                .apply { isAccessible = true }
                                .get(nodHtml)
                            val names = nodHtml.javaClass.getDeclaredField("mNames")
                                .apply { isAccessible = true }
                                .get(nodHtml)
                            if (values is Array<*> && names is Array<*>) {
                                values
                                    .mapIndexed { i, v -> v.toString() to names[i].toString() }
                            } else if (values is Collection<*> && names is Collection<*>) {
                                val namesList = names.toList()
                                values
                                    .mapIndexed { i, v -> v.toString() to namesList[i].toString() }
                            } else {
                                null
                            }
                        }.getOrNull()
                }
                attributes?.forEach { pairAttribute ->
                    when (val key = pairAttribute.first.lowercase(Locale.ENGLISH)) {
                        "autocomplete",
                        "ua-autofill-hints",
                        -> {
                            // https://developer.mozilla.org/en-US/docs/Web/HTML/Attributes/autocomplete
                            val value = pairAttribute.second
                                ?.lowercase(Locale.ENGLISH)
                                .orEmpty()
                            val matchers = autofillHintMatchers
                                .filter { matcher -> matcher.matches(value) }
                            matchers.forEach { matcher ->
                                val item = AutofillStructureItemBuilder(
                                    accuracy = matcher.accuracy,
                                    hint = matcher.hint,
                                    reason = key,
                                )
                                out += item
                            }
                        }

                        "type" -> {
                            val type = pairAttribute.second.orEmpty()
                            extractOfType(type).let(out::addAll)
                        }

                        // Sometimes we can extract useful info from
                        // the name of the input. Although it is kinda random because
                        // it's only used in the site's JavaScript.
                        "name" -> {
                            val type = pairAttribute.second.orEmpty()
                            extractOfId(type).let(out::addAll)
                        }

                        "id" -> {
                            val type = pairAttribute.second.orEmpty()
                            extractOfId(type).let(out::addAll)
                        }

                        "label" -> {
                            val label = pairAttribute.second.orEmpty()
                            extractOfLabel(label).let(out::addAll)
                        }
                    }
                }
            }
        }
        out
    }

    private fun parseNodeByLabel(
        node: AssistStructure.ViewNode,
    ): List<AutofillStructureItemBuilder> {
        val hint = node.hint
            ?: return emptyList()
        return extractOfLabel(hint)
    }

    private fun extractOfType(
        value: String,
    ): List<AutofillStructureItemBuilder> = when (value.lowercase(Locale.ENGLISH)) {
        "tel" -> AutofillStructureItemBuilder(
            accuracy = AutofillStructureItem.Accuracy.MEDIUM,
            hint = AutofillHint.PHONE_NUMBER,
            reason = "type",
        )

        "email" -> AutofillStructureItemBuilder(
            accuracy = AutofillStructureItem.Accuracy.MEDIUM,
            hint = AutofillHint.EMAIL_ADDRESS,
            reason = "type",
        )

        "username" -> AutofillStructureItemBuilder(
            accuracy = AutofillStructureItem.Accuracy.MEDIUM,
            hint = AutofillHint.USERNAME,
            reason = "type",
        )

        "text" -> AutofillStructureItemBuilder(
            accuracy = AutofillStructureItem.Accuracy.LOWEST,
            hint = AutofillHint.USERNAME,
            reason = "type",
        )

        "password" -> AutofillStructureItemBuilder(
            accuracy = AutofillStructureItem.Accuracy.HIGH,
            hint = AutofillHint.PASSWORD,
            reason = "type",
        )

        // custom

        "expdate" -> AutofillStructureItemBuilder(
            accuracy = AutofillStructureItem.Accuracy.HIGH,
            hint = AutofillHint.CREDIT_CARD_EXPIRATION_DATE,
            reason = "type",
        )

        else -> null
    }.let { listOfNotNull(it) }

    private fun extractOfId(
        value: String,
    ): List<AutofillStructureItemBuilder> = kotlin.run {
        val id = value.lowercase(Locale.ENGLISH)
        when {
            "email" in id -> AutofillStructureItemBuilder(
                accuracy = AutofillStructureItem.Accuracy.MEDIUM,
                hint = AutofillHint.EMAIL_ADDRESS,
                reason = "id",
            )

            "username" in id -> AutofillStructureItemBuilder(
                accuracy = AutofillStructureItem.Accuracy.MEDIUM,
                hint = AutofillHint.USERNAME,
                reason = "id",
            )

            "password" in id -> AutofillStructureItemBuilder(
                accuracy = AutofillStructureItem.Accuracy.HIGH,
                hint = AutofillHint.PASSWORD,
                reason = "type",
            )

            else -> null
        }.let { listOfNotNull(it) }
    }

    private fun extractOfLabel(
        value: String,
    ): List<AutofillStructureItemBuilder> {
        val hint = value
            .lowercase(Locale.ENGLISH)
            .trim()
        if (hint.isBlank()) {
            return emptyList()
        }
        val out = when {
            autofillLabelEmailTranslations.any { it in hint } ->
                AutofillStructureItemBuilder(
                    accuracy = AutofillStructureItem.Accuracy.MEDIUM,
                    hint = AutofillHint.EMAIL_ADDRESS,
                    reason = "label:$hint",
                )

            autofillLabelUsernameTranslations.any { it in hint } ->
                AutofillStructureItemBuilder(
                    accuracy = AutofillStructureItem.Accuracy.MEDIUM,
                    hint = AutofillHint.USERNAME,
                    reason = "label:$hint",
                )

            autofillLabelPasswordTranslations.any { it in hint } ->
                AutofillStructureItemBuilder(
                    accuracy = AutofillStructureItem.Accuracy.MEDIUM,
                    hint = AutofillHint.PASSWORD,
                    reason = "label:$hint",
                )

            autofillLabelCreditCardNumberTranslations.any { it.matches(hint) } ->
                AutofillStructureItemBuilder(
                    accuracy = AutofillStructureItem.Accuracy.MEDIUM,
                    hint = AutofillHint.CREDIT_CARD_NUMBER,
                    reason = "label:$hint",
                )

            else -> null
        }
        return listOfNotNull(out)
    }

    private fun parseNodeByAndroidInput(
        node: AssistStructure.ViewNode,
    ): List<AutofillStructureItemBuilder> {
        val out = mutableListOf<AutofillStructureItemBuilder>()

        // For some reason the URL bar is not marked as not important for
        // autofill. To prevent it we explicitly check for the 'url' in the
        // node's entry id.
        //
        // See:
        // https://github.com/AChep/keyguard-app/issues/31
        if (
            node.idEntry.orEmpty().contains("url", ignoreCase = true) &&
            node.idType.orEmpty().equals("id", ignoreCase = true)
        ) {
            out += AutofillStructureItemBuilder(
                accuracy = AutofillStructureItem.Accuracy.HIGHEST,
                hint = AutofillHint.OFF,
            )
            return out
        }

        val importance = if (Build.VERSION.SDK_INT >= 28) {
            node.importantForAutofill
        } else {
            View.IMPORTANT_FOR_AUTOFILL_AUTO
        }
        if (importance == View.IMPORTANT_FOR_AUTOFILL_NO) {
            out += AutofillStructureItemBuilder(
                accuracy = AutofillStructureItem.Accuracy.HIGHEST,
                hint = AutofillHint.OFF,
            )
            return out
        }

        val inputType = node.inputType
        when (inputType and InputType.TYPE_MASK_CLASS) {
            InputType.TYPE_CLASS_TEXT -> {
                when {
                    inputIsVariationType(
                        inputType,
                        InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
                        InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS,
                    ) -> {
                        out += AutofillStructureItemBuilder(
                            accuracy = AutofillStructureItem.Accuracy.HIGH,
                            hint = AutofillHint.EMAIL_ADDRESS,
                        )
                    }

                    inputIsVariationType(
                        inputType,
                        InputType.TYPE_TEXT_VARIATION_PERSON_NAME,
                    ) -> {
                        out += AutofillStructureItemBuilder(
                            accuracy = AutofillStructureItem.Accuracy.LOW,
                            hint = AutofillHint.PERSON_NAME,
                        )
                    }

                    inputIsVariationType(
                        inputType,
                        InputType.TYPE_TEXT_VARIATION_NORMAL,
                        InputType.TYPE_TEXT_VARIATION_PERSON_NAME,
                        InputType.TYPE_TEXT_VARIATION_WEB_EDIT_TEXT,
                    ) -> {
                        out += AutofillStructureItemBuilder(
                            accuracy = AutofillStructureItem.Accuracy.LOWEST,
                            hint = AutofillHint.USERNAME,
                        )
                    }

                    inputIsVariationType(
                        inputType,
                        InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
                    ) -> {
                        // Some forms use visible password as username, which is
                        // unfortunate.
                        val hasUsername = out.any {
                            it.hint == AutofillHint.USERNAME ||
                                    it.hint == AutofillHint.EMAIL_ADDRESS ||
                                    it.hint == AutofillHint.PHONE_NUMBER
                        }
                        if (hasUsername) {
                            out += AutofillStructureItemBuilder(
                                accuracy = AutofillStructureItem.Accuracy.LOWEST,
                                hint = AutofillHint.PASSWORD,
                            )
                        } else {
                            out += AutofillStructureItemBuilder(
                                accuracy = AutofillStructureItem.Accuracy.LOWEST,
                                hint = AutofillHint.USERNAME,
                            )
                        }
                    }

                    inputIsVariationType(
                        inputType,
                        InputType.TYPE_TEXT_VARIATION_PASSWORD,
                        InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
                    ) -> {
                        out += AutofillStructureItemBuilder(
                            accuracy = AutofillStructureItem.Accuracy.HIGH,
                            hint = AutofillHint.PASSWORD,
                        )
                    }

                    inputIsVariationType(
                        inputType,
                        InputType.TYPE_TEXT_VARIATION_EMAIL_SUBJECT,
                        InputType.TYPE_TEXT_VARIATION_FILTER,
                        InputType.TYPE_TEXT_VARIATION_LONG_MESSAGE,
                        InputType.TYPE_TEXT_VARIATION_PHONETIC,
                        InputType.TYPE_TEXT_VARIATION_POSTAL_ADDRESS,
                        InputType.TYPE_TEXT_VARIATION_SHORT_MESSAGE,
                        InputType.TYPE_TEXT_VARIATION_URI,
                    ) -> {
                        // Type not used
                    }

                    else -> {
                    }
                }
            }

            InputType.TYPE_CLASS_NUMBER -> {
                when {
                    inputIsVariationType(
                        inputType,
                        InputType.TYPE_NUMBER_VARIATION_NORMAL,
                    ) -> {
                        out += AutofillStructureItemBuilder(
                            accuracy = if (importance == View.IMPORTANT_FOR_AUTOFILL_YES) {
                                AutofillStructureItem.Accuracy.MEDIUM
                            } else {
                                AutofillStructureItem.Accuracy.LOW
                            },
                            hint = AutofillHint.USERNAME,
                        )
                    }

                    inputIsVariationType(
                        inputType,
                        InputType.TYPE_NUMBER_VARIATION_PASSWORD,
                    ) -> {
                        out += AutofillStructureItemBuilder(
                            accuracy = AutofillStructureItem.Accuracy.LOW,
                            hint = AutofillHint.PASSWORD,
                        )
                    }

                    else -> {
                    }
                }
            }
        }
        return out
    }

    private fun inputIsVariationType(inputType: Int, vararg type: Int): Boolean {
        type.forEach {
            if (inputType and InputType.TYPE_MASK_VARIATION == it) {
                return true
            }
        }
        return false
    }
}
