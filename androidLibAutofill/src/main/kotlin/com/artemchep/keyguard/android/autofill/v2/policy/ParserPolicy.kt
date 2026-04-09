package com.artemchep.keyguard.android.autofill.v2.policy

import android.view.autofill.AutofillId
import com.artemchep.keyguard.android.autofill.v2.model.FieldCluster
import com.artemchep.keyguard.android.autofill.v2.model.FieldProposal
import com.artemchep.keyguard.android.autofill.v2.model.FormIntent
import com.artemchep.keyguard.android.autofill.v2.model.FormProposal
import com.artemchep.keyguard.android.autofill.v2.model.SemanticType

/**
 * Applies suppression or boost rules after analyzers propose field and form
 * classifications but before the final resolver chooses auth hints.
 */
interface ParserPolicy {
    val id: String

    fun apply(
        cluster: FieldCluster,
        fieldProposals: Map<AutofillId, List<FieldProposal>>,
        formProposals: List<FormProposal>,
    ): PolicyResult
}

data class PolicyResult(
    val suppressedFieldTypes: Map<AutofillId, Set<SemanticType>> = emptyMap(),
    val boostedFieldTypes: Map<AutofillId, Map<SemanticType, Float>> = emptyMap(),
    val suppressedFormIntents: Set<FormIntent> = emptySet(),
    val boostedFormIntents: Map<FormIntent, Float> = emptyMap(),
    val reasons: List<String> = emptyList(),
)
