import Foundation
import Observation

/// Owns one presentation's producer, snapshot, and action lifetime.
@MainActor
@Observable
final class DetailSessionModel<Target: Equatable, Snapshot, Session>: SnapshotObserving {
    private let makeSession: (Target) -> Session
    private let subscribe: (Session, @escaping (Snapshot) -> Void, @escaping () -> Void) -> BridgeObservation
    @ObservationIgnored private var session: Session?
    @ObservationIgnored private var subscription: BridgeObservation?

    private(set) var target: Target?
    private(set) var identity: UUID?
    private(set) var detail: Snapshot?
    private(set) var didComplete = false

    convenience init(
        makeSession: @escaping (Target) -> Session,
        subscribe: @escaping (Session, @escaping (Snapshot) -> Void) -> BridgeObservation
    ) {
        self.init(
            makeSession: makeSession,
            subscribeWithCompletion: { session, onChange, _ in subscribe(session, onChange) }
        )
    }

    init(
        makeSession: @escaping (Target) -> Session,
        subscribeWithCompletion:
            @escaping (Session, @escaping (Snapshot) -> Void, @escaping () -> Void) -> BridgeObservation
    ) {
        self.makeSession = makeSession
        self.subscribe = subscribeWithCompletion
    }

    func setTarget(_ target: Target?) {
        guard self.target != target else { return }
        stop()
        guard let target else { return }
        self.target = target
        identity = UUID()
        let session = makeSession(target)
        self.session = session
        startObservation(\.subscription) { deliver in
            subscribe(
                session,
                { snapshot in deliver { $0.detail = snapshot } },
                { deliver { $0.didComplete = true } }
            )
        }
    }

    func stop() {
        stopObservation(\.subscription)
        session = nil
        target = nil
        identity = nil
        detail = nil
        didComplete = false
    }

    /// An old form's captured action must never run against its replacement.
    func perform(owner: UUID, _ action: (Session) -> Void) {
        guard identity == owner, !didComplete, let session else { return }
        action(session)
    }

    /// The actions of the presentation [owner]; they run nothing once the model moves on.
    func actions(owner: UUID) -> SessionActions<Session> {
        SessionActions { [weak self] action in self?.perform(owner: owner, action) }
    }
}

extension DetailSessionModel where Target == Bool {
    /// A presentation with one fixed target: its session lives while the view is observed.
    convenience init(
        makeSession: @escaping () -> Session,
        subscribe: @escaping (Session, @escaping (Snapshot) -> Void) -> BridgeObservation
    ) {
        self.init(makeSession: { _ in makeSession() }, subscribe: subscribe)
    }

    convenience init(
        makeSession: @escaping () -> Session,
        subscribeWithCompletion:
            @escaping (Session, @escaping (Snapshot) -> Void, @escaping () -> Void) -> BridgeObservation
    ) {
        self.init(makeSession: { _ in makeSession() }, subscribeWithCompletion: subscribeWithCompletion)
    }
}

/// Runs actions on one presentation's session; screens capture it instead of an owner id.
struct SessionActions<Session> {
    fileprivate let run: ((Session) -> Void) -> Void

    /// A placeholder's actions: Cancel and chrome work before the producer emits, edits do nothing.
    static var none: SessionActions { SessionActions { _ in } }

    func callAsFunction(_ action: (Session) -> Void) { run(action) }
}
