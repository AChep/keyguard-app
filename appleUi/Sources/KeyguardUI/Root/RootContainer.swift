import SwiftUI
import UniformTypeIdentifiers
import KeyguardShared

public struct RootContainer<Main: View>: View {
    @Environment(AddItemModel.self) private var addItemModel
    @Environment(QuickSearchModel.self) private var quickSearchModel
    @Environment(VaultSessionModel.self) private var authModel
    @Environment(DialogsModel.self) private var dialogsModel
    @Environment(NavigationModel.self) private var navigationModel
    @Environment(AppPreferencesModel.self) private var preferencesModel
    @Environment(\.colorScheme) private var colorScheme
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    private let main: () -> Main

    /// The app-level dialog currently handed to SwiftUI's item-based sheet. The
    /// actual source of truth stays in `DialogsModel`; this preserves the route
    /// being dismissed so the sheet binding closes the right shared producer.
    @State private var presentedAppDialogRoute: RootAppSheetRoute?

    /// Whether the first-run onboarding sheet is open. Surfacing it also stamps the
    /// onboarding instant (`OnboardingView.onAppear`), so the banner self-dismisses.
    @State private var onboardingPresented = false

    public init(@ViewBuilder main: @escaping () -> Main) {
        self.main = main
    }

    public var body: some View {
        VStack(spacing: 0) {
            switch authModel.status {
            case .loading:
                ProgressView(L10n.vaultLoadingText)
            case .needsCreate:
                MasterPasswordView(mode: .create)
            case .locked:
                #if os(iOS)
                NavigationStack {
                    MasterPasswordView(mode: .unlock)
                }
                #else
                MasterPasswordView(mode: .unlock)
                #endif
            case .unlocked:
                if !authModel.hasOnboarded {
                    OnboardingBanner { onboardingPresented = true }
                }
                main()
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .environment(\.locale, AppLocalization.shared.locale)
        .animation(reduceMotion ? nil : .default, value: authModel.status)
        .animation(reduceMotion ? nil : .default, value: authModel.hasOnboarded)
        .modifier(Fido2PromptModifier(isEnabled: !quickSearchModel.quickSearchVisible))
        .sheet(isPresented: $onboardingPresented) { OnboardingView() }
        // App-level dialogs share one item-based route so SwiftUI only ever sees
        // one modal for this concern. `RootAppSheetRoute.preferred` owns priority.
        .sheet(item: appDialogRoute) { route in
            appDialogContent(route)
                .appToastOverlay()
        }
        // The edit-an-existing-item sheet (cipher / Send detail "edit", vault list
        // "clone") is surfaced at the root so it presents above whatever screen
        // triggered it; the shared producer runs pre-filled from the stashed model.
        .sheet(
            item: Binding(
                get: { navigationModel.pendingEditItem },
                set: { navigationModel.pendingEditItem = $0 }
            ), onDismiss: addItemModel.stopAddFormObservation
        ) { request in
            AddItemSheet(
                mode: request.isSend ? .send : .cipher,
                editRequest: request
            )
        }
        .sheet(
            item: Binding(
                get: { navigationModel.pendingAddCipher },
                set: { navigationModel.pendingAddCipher = $0 }
            ), onDismiss: addItemModel.stopAddFormObservation
        ) { prefill in
            AddItemSheet(mode: .cipher, prefill: prefill)
        }
        // A shared-producer-initiated add-account navigation (e.g. quick search
        // with zero accounts) lands here; the primary UX is the vault screen's
        // add-account menu, which pushes the same destinations.
        .sheet(
            item: Binding(
                get: { navigationModel.addAccountRequest },
                set: { navigationModel.addAccountRequest = $0 }
            )
        ) { kind in
            NavigationStack {
                AddAccountDestination(kind: kind)
            }
            .appToastOverlay()
        }
        #if os(iOS)
        // iOS has no NSOpenPanel; a running form's / dialog's file request is
        // presented here as the system document picker. The choice routes back into
        // whichever producer continuation raised it via `resolveFilePicker`.
        .pendingFileImporter {
            !$0.presentsInAddForm && !$0.presentsInBackupSetup && !$0.presentsInKeePassLogin
        }
        #endif
        .onChange(of: preferredAppDialogRoute, initial: true) { _, route in
            presentedAppDialogRoute = route
        }
        // Mirror the effective appearance into the shared bridge so headless
        // producers (attachment-preview syntax highlighting) follow it.
        .onChange(of: colorScheme, initial: true) { _, scheme in
            preferencesModel.setInterfaceDarkMode(scheme == .dark)
        }
        // Native sheets cover the presenting window's overlay. App-level dialogs
        // and account login sheets host their own feedback, including bad passwords.
        .appToastOverlay(isEnabled: presentedAppDialogRoute == nil && navigationModel.addAccountRequest == nil)
    }

    private var preferredAppDialogRoute: RootAppSheetRoute? {
        RootAppSheetRoute.preferred(in: dialogsModel, isAddFormActive: addItemModel.isAddFormActive)
    }

    private var appDialogRoute: Binding<RootAppSheetRoute?> {
        Binding(
            get: { presentedAppDialogRoute },
            set: { route in
                if let route {
                    presentedAppDialogRoute = route
                } else {
                    let dismissedRoute = presentedAppDialogRoute
                    presentedAppDialogRoute = nil
                    dismissedRoute?.dismiss(in: dialogsModel)
                }
            }
        )
    }

    @ViewBuilder
    private func appDialogContent(_ route: RootAppSheetRoute) -> some View {
        switch route {
        case .passwordMemory:
            PasswordMemoryView()
        case .largeType:
            LargeTypeView()
        case .barcode:
            BarcodeView()
        case .passkeyCredential:
            PasskeyCredentialView()
        case .attachmentPreview:
            AttachmentPreviewView()
        case .confirmation:
            ConfirmationDialogView()
        case .elevatedAccess:
            ElevatedAccessDialogView()
        case .serviceInfo:
            ServiceInfoDialogView()
        case .emailLeak:
            EmailLeakView()
        case .passwordLeak:
            PasswordLeakView()
        case .websiteLeak:
            WebsiteLeakView()
        case .colorPicker:
            ColorPickerView()
        case .infoDialog:
            InfoDialogView()
        case .accountPicker:
            AccountPickerView()
        }
    }
}

private struct OnboardingBanner: View {
    let onTap: () -> Void

    var body: some View {
        VStack(spacing: 0) {
            Button(action: onTap) {
                HStack(spacing: 12) {
                    Image(systemName: "info.circle")
                        .foregroundStyle(.tint)
                        .accessibilityHidden(true)
                    Text(L10n.learnMore)
                        .font(.headline)
                        .frame(maxWidth: .infinity, alignment: .leading)
                    Image(systemName: "chevron.forward")
                        .font(.caption.weight(.semibold))
                        .foregroundStyle(.secondary)
                        .accessibilityHidden(true)
                }
                .padding(.horizontal, 16)
                .padding(.vertical, 10)
                .frame(maxWidth: .infinity)
                .touchTarget()
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .background(.tint.opacity(0.12))
            Divider()
        }
    }
}
