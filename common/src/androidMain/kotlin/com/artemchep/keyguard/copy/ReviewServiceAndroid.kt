package com.artemchep.keyguard.copy

import android.app.Application
import android.content.Context
import com.artemchep.keyguard.android.closestActivityOrNull
import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.service.review.ReviewService
import com.artemchep.keyguard.platform.LeContext
import com.google.android.gms.tasks.Task
import com.google.android.play.core.review.ReviewManagerFactory
import kotlinx.coroutines.Dispatchers
import org.kodein.di.DirectDI
import org.kodein.di.instance
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

class ReviewServiceAndroid(
    private val context: Context,
) : ReviewService {
    private val reviewManager = ReviewManagerFactory.create(context.applicationContext)

    constructor(
        directDI: DirectDI,
    ) : this(
        context = directDI.instance<Application>(),
    )

    override fun request(
        context: LeContext,
    ): IO<Unit> = ioEffect(Dispatchers.Main) {
        val activity = context.context.closestActivityOrNull
        requireNotNull(activity) {
            "Failed to find activity for App review."
        }

        val reviewInfo = reviewManager
            .requestReviewFlow()
            .await()
        reviewManager
            .launchReviewFlow(activity, reviewInfo)
            .await()
    }
}

private suspend fun <T> Task<T>.await() = suspendCoroutine<T> { continuation ->
    addOnCompleteListener { task ->
        if (task.isSuccessful) {
            continuation.resume(task.result as T)
        } else {
            continuation.resumeWithException(task.exception!!)
        }
    }
}
