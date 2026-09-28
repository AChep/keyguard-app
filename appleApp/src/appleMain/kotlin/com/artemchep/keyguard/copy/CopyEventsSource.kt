package com.artemchep.keyguard.copy

import kotlinx.coroutines.flow.Flow

interface CopyEventsSource {
    val copyEvents: Flow<Unit>
}
