package com.artemchep.keyguard.apple.settings

import com.artemchep.keyguard.common.io.launchIn
import com.artemchep.keyguard.common.usecase.GetAutofillCopyTotp
import com.artemchep.keyguard.common.usecase.GetAutofillDefaultMatchDetection
import com.artemchep.keyguard.common.usecase.GetAutofillSaveRequest
import com.artemchep.keyguard.common.usecase.GetAutofillSaveUri
import com.artemchep.keyguard.common.usecase.PutAutofillCopyTotp
import com.artemchep.keyguard.common.usecase.PutAutofillDefaultMatchDetection
import com.artemchep.keyguard.common.usecase.PutAutofillSaveRequest
import com.artemchep.keyguard.common.usecase.PutAutofillSaveUri
import com.artemchep.keyguard.apple.core.KeyguardCancellable
import com.artemchep.keyguard.apple.core.collectOnMain
import com.artemchep.keyguard.apple.model.SettingOptionSnapshot
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.model.titleH
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.StringResource

/** Global preferences, so no session is required. */
internal class AutofillSettingsController(
    private val getAutofillCopyTotp: GetAutofillCopyTotp,
    private val putAutofillCopyTotp: PutAutofillCopyTotp,
    private val getAutofillSaveRequest: GetAutofillSaveRequest,
    private val putAutofillSaveRequest: PutAutofillSaveRequest,
    private val getAutofillSaveUri: GetAutofillSaveUri,
    private val putAutofillSaveUri: PutAutofillSaveUri,
    private val getAutofillDefaultMatchDetection: GetAutofillDefaultMatchDetection,
    private val putAutofillDefaultMatchDetection: PutAutofillDefaultMatchDetection,
    private val scope: CoroutineScope,
    private val text: suspend (StringResource) -> String,
) {
    fun observeAutofillSettings(
        onChange: (AutofillSettingsSnapshot) -> Unit,
    ): KeyguardCancellable {
        val job = scope.launch {
            combine(
                getAutofillCopyTotp(),
                getAutofillSaveRequest(),
                getAutofillSaveUri(),
                getAutofillDefaultMatchDetection(),
            ) { copyTotp, saveRequest, saveUri, defaultMatchDetection ->
                val options = DSecret.Uri.MatchType.entries.map { value ->
                    SettingOptionSnapshot(
                        id = value.name,
                        title = text(value.titleH()),
                        selected = value == defaultMatchDetection,
                    )
                }
                AutofillSettingsSnapshot(
                    loaded = true,
                    copyTotp = copyTotp,
                    saveRequest = saveRequest,
                    saveUri = saveUri,
                    defaultMatchDetectionTitle = options.first { it.selected }.title,
                    defaultMatchDetectionOptions = options,
                )
            }.collectOnMain { onChange(it) }
        }
        return KeyguardCancellable(job)
    }

    fun setCopyTotp(value: Boolean) {
        putAutofillCopyTotp(value).launchIn(scope)
    }

    fun setSaveRequest(value: Boolean) {
        putAutofillSaveRequest(value).launchIn(scope)
    }

    fun setSaveUri(value: Boolean) {
        putAutofillSaveUri(value).launchIn(scope)
    }

    fun setDefaultMatchDetection(optionId: String) {
        val value = DSecret.Uri.MatchType.entries.firstOrNull { it.name == optionId } ?: return
        putAutofillDefaultMatchDetection(value).launchIn(scope)
    }
}
