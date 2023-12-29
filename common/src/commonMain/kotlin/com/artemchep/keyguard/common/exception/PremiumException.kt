package com.artemchep.keyguard.common.exception

import com.artemchep.keyguard.common.model.NoAnalytics

class PremiumException : RuntimeException("The call requires Premium subscription."), NoAnalytics
