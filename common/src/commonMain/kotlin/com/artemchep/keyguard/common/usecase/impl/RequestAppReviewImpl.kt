package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.effectTap
import com.artemchep.keyguard.common.io.flatMap
import com.artemchep.keyguard.common.io.flatTap
import com.artemchep.keyguard.common.io.ioUnit
import com.artemchep.keyguard.common.io.toIO
import com.artemchep.keyguard.common.service.logging.LogRepository
import com.artemchep.keyguard.common.service.logging.postDebug
import com.artemchep.keyguard.common.service.review.ReviewLog
import com.artemchep.keyguard.common.service.review.ReviewService
import com.artemchep.keyguard.common.usecase.RequestAppReview
import com.artemchep.keyguard.feature.crashlytics.crashlyticsTap
import com.artemchep.keyguard.platform.LeContext
import kotlinx.datetime.Clock
import org.kodein.di.DirectDI
import org.kodein.di.instance
import kotlin.time.Duration

class RequestAppReviewImpl(
    private val logRepository: LogRepository,
    private val reviewLog: ReviewLog,
    private val reviewService: ReviewService,
) : RequestAppReview {
    companion object {
        private const val TAG = "RequestAppReview"

        /**
         * After this period, the app will try
         * to request a user to review the app.
         */
        private val PERIOD = with(Duration) { 28.days }
    }

    constructor(directDI: DirectDI) : this(
        logRepository = directDI.instance(),
        reviewLog = directDI.instance(),
        reviewService = directDI.instance(),
    )

    override fun invoke(
        context: LeContext,
    ): IO<Unit> = reviewLog
        .getLastRequestedAt()
        .toIO()
        .flatMap { lastRequestedAt ->
            val canRequest = kotlin.run {
                lastRequestedAt
                    ?: return@run true
                val age = Clock.System.now() - lastRequestedAt
                age > PERIOD
            }
            if (canRequest) {
                reviewService
                    .request(context)
                    .flatTap {
                        val now = Clock.System.now()
                        reviewLog.setLastRequestedAt(now)
                    }
                    .effectTap {
                        logRepository.postDebug(TAG) {
                            "Sent app review request."
                        }
                    }
            } else {
                ioUnit()
                    .effectTap {
                        logRepository.postDebug(TAG) {
                            "Ignored app review request: " +
                                    "too little time has passed since the last request."
                        }
                    }
            }
        }
        .crashlyticsTap()
}
