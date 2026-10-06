import SwiftUI

extension View {
    /// Runs a shared producer's observation for as long as this view is on screen.
    ///
    /// Pass `enabled: false` to skip both ends: the shape a screen takes when it can
    /// also be rendered from a navigation-stack entry's inline snapshot and must not
    /// own the single-slot observation.
    ///
    /// - Note: deliberately built on `onAppear` / `onDisappear` rather than `.task`.
    ///   Most of these producers write a *single-slot* snapshot on the shared model,
    ///   and `stop()` resets that slot to `.empty`. `onDisappear` runs synchronously
    ///   during the transition, so an outgoing screen's stop is ordered before the
    ///   incoming screen's start; a `.task` teardown resumes on a later turn and
    ///   could blank the screen that just started. Moving to `.task` means giving
    ///   each observation its own slot first.
    func observing(
        enabled: Bool = true,
        start: @escaping () -> Void,
        stop: @escaping () -> Void
    ) -> some View {
        onAppear { if enabled { start() } }
            .onDisappear { if enabled { stop() } }
    }
}
