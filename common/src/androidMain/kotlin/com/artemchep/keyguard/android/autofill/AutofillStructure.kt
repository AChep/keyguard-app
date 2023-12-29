package com.artemchep.keyguard.android.autofill

import android.os.Parcelable
import android.view.autofill.AutofillId
import com.artemchep.keyguard.common.model.AutofillHint
import kotlinx.parcelize.Parcelize

@Parcelize
data class AutofillStructure2(
    val applicationId: String? = null,
    val webScheme: String? = null,
    val webDomain: String? = null,
    val webView: Boolean? = null,
    val items: List<Item>,
) : Parcelable {
    @Parcelize
    data class Item(
        val id: AutofillId,
        val hint: AutofillHint,
        val value: String? = null,
    ) : Parcelable
}
