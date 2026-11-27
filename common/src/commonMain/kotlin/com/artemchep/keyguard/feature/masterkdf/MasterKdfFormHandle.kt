package com.artemchep.keyguard.feature.masterkdf

import com.artemchep.keyguard.common.model.MasterKdf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * The key derivation form of a screen, see [masterKdfFormHandle].
 */
class MasterKdfFormHandle(
    val stateFlow: Flow<MasterKdfFormState>,
    /**
     * `true` while a test derivation runs. The native derivation can not
     * be cancelled, so the screen must not start its own one meanwhile.
     */
    val isTestingFlow: StateFlow<Boolean>,
    /**
     * Reads the key derivation from the fields at this moment; `null` if
     * any field is not valid. Use it when the user submits, so a callback
     * of an older state still submits the latest values.
     */
    val currentKdf: () -> MasterKdf.Argon2id?,
)
