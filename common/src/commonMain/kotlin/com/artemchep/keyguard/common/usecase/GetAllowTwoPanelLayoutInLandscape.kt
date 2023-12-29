package com.artemchep.keyguard.common.usecase

import kotlinx.coroutines.flow.StateFlow

interface GetAllowTwoPanelLayoutInLandscape : () -> StateFlow<Boolean>
