package com.artemchep.keyguard.apple.settings

import com.artemchep.keyguard.common.io.launchIn
import com.artemchep.keyguard.common.usecase.GetAutofillCopyTotp
import com.artemchep.keyguard.common.usecase.GetAutofillSaveRequest
import com.artemchep.keyguard.common.usecase.GetAutofillSaveUri
import com.artemchep.keyguard.common.usecase.PutAutofillCopyTotp
import com.artemchep.keyguard.common.usecase.PutAutofillSaveRequest
import com.artemchep.keyguard.common.usecase.PutAutofillSaveUri
import com.artemchep.keyguard.apple.core.CoreContext
import com.artemchep.keyguard.apple.core.KeyguardCancellable
import com.artemchep.keyguard.apple.core.collectOnMain
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.combine

/**
 * The Apple-applicable AutoFill preferences: copy-TOTP-to-clipboard, save-
 * credential prompts (save request), and save-URI-to-existing-item prompts.
 * Thin bridge over the shared Get/Put use cases (global preferences, so no
 * session is required); mirrors [SecurityController]. The Android-only toggles
 * the common screen exposes (inline suggestions, manual selection, respect
 * autofill-off, default match detection) and the credential-provider
 * registration row are intentionally not surfaced — they are no-ops or N/A on
 * Apple.
 */
internal class AutofillSettingsController(
    private val ctx: CoreContext,
) {
    private val getAutofillCopyTotp: GetAutofillCopyTotp by lazy { ctx.koin.get() }
    private val putAutofillCopyTotp: PutAutofillCopyTotp by lazy { ctx.koin.get() }
    private val getAutofillSaveRequest: GetAutofillSaveRequest by lazy { ctx.koin.get() }
    private val putAutofillSaveRequest: PutAutofillSaveRequest by lazy { ctx.koin.get() }
    private val getAutofillSaveUri: GetAutofillSaveUri by lazy { ctx.koin.get() }
    private val putAutofillSaveUri: PutAutofillSaveUri by lazy { ctx.koin.get() }

    fun observeAutofillSettings(
        onChange: (AutofillSettingsSnapshot) -> Unit,
    ): KeyguardCancellable {
        return ctx.launchObserver {
            coroutineScope {
                combine(
                    getAutofillCopyTotp(),
                    getAutofillSaveRequest(),
                    getAutofillSaveUri(),
                ) { copyTotp, saveRequest, saveUri ->
                    AutofillSettingsSnapshot(
                        loaded = true,
                        copyTotp = copyTotp,
                        saveRequest = saveRequest,
                        saveUri = saveUri,
                    )
                }.collectOnMain { onChange(it) }
            }
        }
    }

    fun setCopyTotp(value: Boolean) {
        putAutofillCopyTotp(value).launchIn(ctx.scope)
    }

    fun setSaveRequest(value: Boolean) {
        putAutofillSaveRequest(value).launchIn(ctx.scope)
    }

    fun setSaveUri(value: Boolean) {
        putAutofillSaveUri(value).launchIn(ctx.scope)
    }
}
