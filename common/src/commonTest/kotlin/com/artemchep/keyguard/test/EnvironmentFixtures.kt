package com.artemchep.keyguard.test

import com.artemchep.keyguard.feature.environments.EnvironmentDefinition
import com.artemchep.keyguard.feature.environments.EnvironmentFormat
import com.artemchep.keyguard.feature.environments.EnvironmentPayload

/** A newly written environment, as Keyguard stores one. */
fun environmentPayload(definition: EnvironmentDefinition) = EnvironmentPayload.Decoded(
    definition = definition,
    document = EnvironmentFormat.encode(definition),
    lossless = true,
)
