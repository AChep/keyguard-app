package com.artemchep.keyguard.common.usecase

import kotlinx.coroutines.flow.StateFlow

interface GetAllowTwoPanelLayoutInPortrait : () -> StateFlow<Boolean>
