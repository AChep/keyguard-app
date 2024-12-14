package com.artemchep.keyguard.android.autofill

import android.os.Build
import android.service.autofill.Dataset
import android.service.autofill.Field
import android.service.autofill.InlinePresentation
import android.service.autofill.Presentations
import android.view.autofill.AutofillId
import android.view.autofill.AutofillValue
import android.widget.RemoteViews
import androidx.annotation.RequiresApi
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.model.AutofillHint
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.model.gett
import com.artemchep.keyguard.common.usecase.GetTotpCode

object DatasetBuilder {
    class FieldData(
        val value: String? = null,
    )

    suspend fun fieldsStructData(
        cipher: DSecret,
        structItems: List<AutofillStructure2.Item>,
        getTotpCode: GetTotpCode,
    ) = kotlin.run {
        val hints = structItems
            .asSequence()
            .map { it.hint }
            .toSet()
        cipher.gett(
            hints = hints,
            getTotpCode = getTotpCode,
        ).bind()
    }

    fun fields(
        structItems: List<AutofillStructure2.Item>,
        structData: Map<AutofillHint, String>,
    ) = structItems
        .asSequence()
        .mapNotNull { structItem ->
            val autofillId = structItem.id
            val autofillValue = structData[structItem.hint]
                ?: return@mapNotNull null

            val data = FieldData(
                value = autofillValue,
            )
            autofillId to data
        }
        .toMap()

    inline fun create(
        menuPresentation: RemoteViews,
        fields: Map<AutofillId, FieldData?>,
        provideInlinePresentation: () -> InlinePresentation?,
    ): Dataset.Builder {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            createSdkPostTiramisu(
                menuPresentation = menuPresentation,
                fields = fields,
                provideInlinePresentation = provideInlinePresentation,
            )
        } else {
            createSdkPreTiramisu(
                menuPresentation = menuPresentation,
                fields = fields,
                provideInlinePresentation = provideInlinePresentation,
            )
        }
        return builder
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    inline fun createSdkPostTiramisu(
        menuPresentation: RemoteViews,
        fields: Map<AutofillId, FieldData?>,
        provideInlinePresentation: () -> InlinePresentation?,
    ): Dataset.Builder {
        val presentations = Presentations.Builder().apply {
            setMenuPresentation(menuPresentation)

            val inlinePresentation = provideInlinePresentation()
            inlinePresentation?.let(::setInlinePresentation)
        }.build()
        return Dataset.Builder(presentations).apply {
            fields.forEach { (autofillId, fieldData) ->
                val field = if (fieldData != null) {
                    Field.Builder().apply {
                        if (fieldData.value != null) {
                            val autofillValue = AutofillValue.forText(fieldData.value)
                            setValue(autofillValue)
                        }
                    }.build()
                } else {
                    null
                }
                setField(autofillId, field)
            }
        }
    }

    inline fun createSdkPreTiramisu(
        menuPresentation: RemoteViews,
        fields: Map<AutofillId, FieldData?>,
        provideInlinePresentation: () -> InlinePresentation?,
    ): Dataset.Builder {
        return Dataset.Builder(menuPresentation).apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val inlinePresentation = provideInlinePresentation()
                inlinePresentation?.let(::setInlinePresentation)
            }

            fields.forEach { (autofillId, fieldData) ->
                val autofillValue = if (fieldData?.value != null) {
                    AutofillValue.forText(fieldData.value)
                } else {
                    null
                }
                setValue(autofillId, autofillValue)
            }
        }
    }
}
