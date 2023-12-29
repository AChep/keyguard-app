package com.artemchep.keyguard.common.usecase

import kotlinx.coroutines.flow.Flow

interface GetAutofillInlineSuggestions : () -> Flow<Boolean>
