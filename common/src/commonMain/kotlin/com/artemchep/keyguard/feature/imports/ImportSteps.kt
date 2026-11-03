package com.artemchep.keyguard.feature.imports

import com.artemchep.keyguard.common.io.runCatchingNonFatal
import com.artemchep.keyguard.common.service.file.FileService
import com.artemchep.keyguard.common.service.imports.OpenedImportSource
import com.artemchep.keyguard.feature.navigation.state.RememberStateFlowScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Something a step keeps open while it is current: the source a later step
 * reads from, the platform's copy of a picked file, or the open in flight.
 *
 * Steps are data classes, so a held resource never prints its contents.
 */
internal sealed class Held {
    /** An open source; equal only to itself. */
    class Source(
        val source: OpenedImportSource,
    ) : Held() {
        override fun toString() = "Held.Source"
    }

    /**
     * The copy the platform made of a picked file — on iOS a plaintext export
     * in the app's tmp. Equal per [uri], so that a password prompt that opens
     * the same file again keeps it.
     */
    data class FileCopy(
        val uri: String,
    ) : Held()

    /** An open or a parse in flight; releasing it cancels the [job]. */
    class Opening(
        val job: Job,
    ) : Held() {
        override fun toString() = "Held.Opening"
    }
}

/**
 * The step of the import flow, and the owner of whatever the steps hold.
 *
 * Every change of step goes through here: a step change releases what the
 * old step held and the new one does not, so a source lives exactly as long
 * as a step that may still read it.
 */
internal class ImportSteps(
    initial: Step = Step.Start,
    private val release: (Held) -> Unit,
) {
    private val sink = MutableStateFlow(initial)

    val flow: StateFlow<Step> get() = sink

    val value: Step get() = sink.value

    /**
     * Moves from [expected] to [next], unless something else has replaced
     * [expected]. The winner owns what [expected] held and [next] does not,
     * as a lease; the loser lets go of what [next] brought along.
     */
    fun claim(
        expected: Step,
        next: Step,
    ): ImportLease? {
        if (!sink.compareAndSet(expected, next)) {
            (next.held - expected.held).forEach(release)
            return null
        }
        return ImportLease(
            held = expected.held - next.held,
            release = release,
        )
    }

    /**
     * Moves from [expected] to [next], releasing what only [expected] held.
     */
    fun move(
        expected: Step,
        next: Step,
    ): Boolean {
        val lease = claim(expected, next)
            ?: return false
        lease.release()
        return true
    }

    /**
     * Moves to [next] from whatever step is current.
     */
    fun set(next: Step) {
        do {
            val current = sink.value
        } while (!move(current, next))
    }

    /**
     * Lets go of everything the current step holds, as the screen closes. A
     * commit has taken its source over by then.
     */
    fun close() = set(Step.Start)
}

/**
 * What a [claim][ImportSteps.claim] took over from the steps; the claimer
 * releases it once done.
 */
internal class ImportLease(
    private val held: Set<Held>,
    private val release: (Held) -> Unit,
) {
    /** The source of the claimed step, if it held one. */
    val source: OpenedImportSource? = held
        .firstNotNullOfOrNull { it as? Held.Source }
        ?.source

    fun release() = held.forEach(release)
}

/**
 * A review claimed for one commit: the importing step that replaced it, and
 * the source the commit now owns.
 */
internal data class ImportCommitClaim(
    val review: Step.Review,
    val lease: ImportLease,
)

/**
 * The steps of an import screen. Whatever the current step holds is released
 * as the screen closes.
 */
internal fun RememberStateFlowScope.importSteps(
    fileService: FileService,
): ImportSteps {
    val steps = ImportSteps { held ->
        when (held) {
            // In the background: closing may still talk to a backup location,
            // after the screen is gone.
            is Held.Source -> appScope.launch {
                runCatchingNonFatal {
                    held.source.close()
                }
            }

            is Held.FileCopy -> runCatchingNonFatal {
                fileService.deleteManagedSourceFile(held.uri)
            }

            is Held.Opening -> held.job.cancel()
        }
    }
    screenScope.coroutineContext[Job]?.invokeOnCompletion {
        steps.close()
    }
    return steps
}
