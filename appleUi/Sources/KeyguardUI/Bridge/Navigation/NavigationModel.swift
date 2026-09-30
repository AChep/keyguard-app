import Foundation
import Observation
import KeyguardShared

@MainActor
@Observable
final class NavigationModel: SnapshotObserving {
    private let core: KeyguardCore

    init(core: KeyguardCore) {
        self.core = core
    }

    @ObservationIgnored private var started = false

    func start() {
        guard !started else { return }
        started = true
        // A `keyguard://` deep link targets a screen in a specific section; the core pushes
        // it onto that section's stack and asks the visible tab to switch to it.
        core.setSelectScopeHandler { [weak self] scope in
            Task { @MainActor [weak self] in
                self?.pendingDeepLinkScope = scope
            }
        }
        // The generator's "create login / SSH key" action emits an AddRoute; present
        // the native create-item sheet prefilled with the generated value.
        core.setAddCipherHandler { [weak self] type, name, username, password in
            Task { @MainActor [weak self] in
                self?.pendingAddCipher = AddCipherPrefill(
                    type: type,
                    name: name,
                    username: username,
                    password: password
                )
            }
        }
        // A producer "edit" / "clone" action emits an AddRoute / SendAddRoute with a
        // stashed initialValue; surface it as an app-level edit sheet (works from the
        // cipher / Send detail and the vault list regardless of which screen is up).
        core.setEditFormRequestHandler { [weak self] request in
            Task { @MainActor [weak self] in
                self?.pendingEditItem = AddEditPrefill(
                    requestId: request.requestId,
                    isSend: request.isSend
                )
            }
        }
        // A shared producer navigated to an add-account login route (quick search's
        // zero-accounts action, account-list add options); present the native login
        // flow for the requested provider from the root.
        core.setAddAccountHandler { [weak self] typeName in
            Task { @MainActor [weak self] in
                self?.addAccountRequest = typeName == "KEEPASS" ? .keepass : .bitwarden
            }
        }
    }

    /// Navigation stacks keyed by section scope.
    private(set) var navStacks: [String: [ScreenEntrySnapshot]] = [:]

    /// The navigation stack for one section/tab scope (empty if none).
    func navStack(_ scope: String) -> [ScreenEntrySnapshot] { navStacks[scope] ?? [] }

    /// Live TOTP badges of the stacked cipher details, keyed by cipher id. Kept
    /// off `navStacks` so the per-second countdown does not re-render the stacks.
    private(set) var entryTotp: [String: VaultDetailTotpSnapshot] = [:]

    /// Set when the generator's "create login / SSH key" action fires (a producer
    /// `AddRoute`); drives a create-item sheet on the generator screen, prefilled
    /// with the generated value. Cleared when that sheet dismisses.
    var pendingAddCipher: AddCipherPrefill?

    var addAccountRequest: AddAccountKind?

    var pendingEditItem: AddEditPrefill?

    /// Cipher id the Recents sheet asked the vault list to reveal. `MainView`
    /// switches the sidebar to the Vault section on it; `HomeView` consumes it by
    /// selecting (and scrolling to) the matching row once the vault list carries it.
    var pendingRevealSecretId: String?

    /// Nav scope a `keyguard://` deep link asked to open. The active root (MainView /
    /// KeyguardRootiOS) switches its selected section to match, then clears it; the deep
    /// link's screen is already pushed onto that section's stack by the core.
    var pendingDeepLinkScope: String?

    private(set) var navItems: NavItemsSnapshot = NavItemsSnapshot.companion.empty

    /// The top-level sections the shell renders (macOS sidebar / iOS tab bar).
    var navSections: [NavSection] {
        guard navItems.loaded, !navItems.sections.isEmpty else {
            return NavSection.defaults
        }
        return navItems.sections.map(NavSection.init)
    }

    // Multiple unlocked windows share this model. Retain shell observations
    // until the last consumer disappears.
    private let navStackSession = SharedObservation()

    @ObservationIgnored private var navScopeConsumers: [String: Int] = [:]

    private let navItemsObservation = SharedObservation()

    @ObservationIgnored private var navItemsSubscription: BridgeObservation?

    @ObservationIgnored private var navScopeSubs: [String: BridgeObservation] = [:]

    /// Starts the whole-stack session gate for the unlocked lifetime. Call from the
    /// shell (MainView / KeyguardRootiOS); balance with `stopNavStackSession()`.
    func startNavStackSession() {
        navStackSession.acquire {
            let session = core.startNavStackSession()
            let totp = core.observeNavStackTotp { [weak self] states in
                MainActor.assumeIsolated { self?.entryTotp = states }
            }
            return BridgeObservation(cancel: {
                session.cancel()
                totp.cancel()
            })
        }
    }

    func stopNavStackSession() {
        guard navStackSession.release() else { return }
        navScopeSubs.values.forEach { $0.cancel() }
        navScopeSubs = [:]
        navScopeConsumers = [:]
        navStacks = [:]
        entryTotp = [:]
    }

    /// Sets the scope (section / tab) that subsequent producer pushes target. Call
    /// from the shell when the visible section/tab changes.
    func setNavScope(_ scope: String) {
        core.setNavScope(scope: scope)
    }

    /// Observes one section's navigation stack. Call from that section's
    /// `NavStackContainer.onAppear`; balance with `stopNavScopeObservation`.
    func startNavScopeObservation(_ scope: String) {
        navScopeConsumers[scope, default: 0] += 1
        startObservation(\.navScopeSubs[scope]) { deliver in
            BridgeObservation(
                core.observeNavStack(scope: scope) { entries in
                    deliver { $0.navStacks[scope] = entries }
                })
        }
    }

    func stopNavScopeObservation(_ scope: String) {
        guard let consumers = navScopeConsumers[scope], consumers > 0 else { return }
        guard consumers == 1 else {
            navScopeConsumers[scope] = consumers - 1
            return
        }
        navScopeConsumers[scope] = nil
        stopObservation(\.navScopeSubs[scope])
        navStacks[scope] = nil
    }

    /// Pops the top screen instance of a scope (e.g. on user back).
    func popScreen(scope: String) {
        core.popScreen(scope: scope)
    }

    /// Pops every screen instance of a scope back to its root.
    func clearScope(_ scope: String) {
        core.clearNavScope(scope: scope)
    }

    /// Invokes a cipher-detail item action of the stacked entry with `instanceId`.
    func invokeEntryAction(instanceId: Int64, actionId: String) {
        core.invokeEntryAction(instanceId: instanceId, actionId: actionId)
    }

    /// Toggles the favourite flag of a stacked cipher-detail entry.
    func toggleEntryFavorite(instanceId: Int64) {
        core.toggleEntryFavorite(instanceId: instanceId)
    }

    /// Writes text into the addressed list entry's search field.
    func setEntryListQuery(instanceId: Int64, text: String) {
        core.setEntryListQuery(instanceId: instanceId, text: text)
    }

    /// Restarts a failed list source while preserving its inputs.
    func retryEntryList(instanceId: Int64) {
        core.retryEntryList(instanceId: instanceId)
    }

    func openEntryListItem(instanceId: Int64, itemId: String) {
        core.openEntryListItem(instanceId: instanceId, itemId: itemId)
    }

    func toggleEntryListSelection(instanceId: Int64, itemId: String) {
        core.toggleEntryListSelection(instanceId: instanceId, itemId: itemId)
    }

    func clearEntryListSelection(instanceId: Int64) {
        core.clearEntryListSelection(instanceId: instanceId)
    }

    func selectAllEntryListItems(instanceId: Int64) {
        core.selectAllEntryListItems(instanceId: instanceId)
    }

    func requestEntryWordlistAction(
        instanceId: Int64, actionId: String, itemId: String?,
        onResult: @escaping (WordlistActionRequestSnapshot?) -> Void
    ) {
        core.requestEntryWordlistAction(
            instanceId: instanceId, actionId: actionId, itemId: itemId, onResult: onResult)
    }

    func requestEntryEmailRelayAction(
        instanceId: Int64, actionId: String, itemId: String?,
        onResult: @escaping (EmailRelayActionRequestSnapshot?) -> Void
    ) {
        core.requestEntryEmailRelayAction(
            instanceId: instanceId, actionId: actionId, itemId: itemId, onResult: onResult)
    }

    /// Pushes a cipher detail onto the stack (an iPhone vault row tap).
    func pushCipherDetail(itemId: String, accountId: String) {
        core.pushCipherDetail(itemId: itemId, accountId: accountId)
    }

    /// Pushes a service-directory list onto the stack (a watchtower "Tools" row).
    func pushServiceDirectoryList(kind: String, title: String) {
        core.pushServiceDirectoryList(kind: kind, title: title)
    }

    /// Pushes the generator history onto the stack (a generator "Tools" row).
    func pushGeneratorHistory() {
        core.pushGeneratorHistory()
    }

    /// Pushes the email-relay list onto the stack (a generator "Tools" row).
    func pushEmailRelayList() {
        core.pushEmailRelayList()
    }

    /// Pushes the wordlists list onto the stack (a generator "Tools" row).
    func pushWordlistList() {
        core.pushWordlistList()
    }

    /// Pushes a single wordlist's detail onto the stack (a wordlists row tap).
    func pushWordlistDetail(wordlistId: Int64, title: String) {
        core.pushWordlistDetail(wordlistId: wordlistId, title: title)
    }

    /// Pushes a cipher's password history onto the stack (cipher detail header).
    func pushPasswordHistory(itemId: String) {
        core.pushPasswordHistory(itemId: itemId)
    }

    /// Pushes a Send detail onto the stack (an iPhone Send row tap).
    func pushSendDetail(sendId: String, accountId: String) {
        core.pushSendDetail(sendId: sendId, accountId: accountId)
    }

    /// Pushes an account detail onto the stack (an iPhone Settings account-row tap).
    func pushAccountDetail(accountId: String) {
        core.pushAccountDetail(accountId: accountId)
    }

    /// Pushes the "Contact us" feedback screen onto the stack (a Settings → About row).
    func pushFeedback() {
        core.pushFeedback()
    }

    /// Writes `text` into a stacked feedback entry's message field.
    func setEntryFeedbackMessage(instanceId: Int64, text: String) {
        core.setEntryFeedbackMessage(instanceId: instanceId, text: text)
    }

    /// Submits a stacked feedback entry (sends the message via a native mailto:).
    func submitEntryFeedback(instanceId: Int64) {
        core.submitEntryFeedback(instanceId: instanceId)
    }

    /// Writes `text` into a stacked export entry's password field.
    func setExportPassword(instanceId: Int64, text: String) {
        core.setExportPassword(instanceId: instanceId, text: text)
    }

    /// Pushes an organization's collections onto the stack (organizations row tap).
    func pushCollectionsList(accountId: String, organizationId: String?, title: String) {
        core.pushCollectionsList(accountId: accountId, organizationId: organizationId, title: title)
    }

    /// Observes the resolved top-level navigation sections. Started by the
    /// unlocked shell (`MainView` / `KeyguardRootiOS`) and stopped with it.
    func startNavItemsObservation() {
        navItemsObservation.acquire {
            startObservation(\.navItemsSubscription, into: \.navItems, observe: core.observeNavItems)
            return BridgeObservation { [weak self] in
                self?.stopObservation(
                    \.navItemsSubscription, resetting: \.navItems, to: NavItemsSnapshot.companion.empty)
            }
        }
    }

    func stopNavItemsObservation() {
        navItemsObservation.release()
    }
}
