import SwiftUI
import KeyguardShared

/// Links this app with the apps on the user's other devices. This device, what it
/// syncs, and how linking works are the same linked or not, so the user sets them up
/// before the first pairing. Not linked yet, it links a device; linked, it shows the
/// devices of the link, linking another one, and unlinking.
///
/// The pairing screens open on top of this one, not on the settings stack: pushing an
/// entry would drop this screen where a view link opened it, as on iPhone.
struct AppLinkSettingsView: View {
    @State private var model: AppLinkModel
    @State private var destination: AppLinkDestination?
    private let title: String
    private let sessions: SessionFactory

    init(title: String, sessions: SessionFactory) {
        self.title = title
        self.sessions = sessions
        _model = State(wrappedValue: AppLinkModel(makeSource: { sessions.makeAppLinkSession() }))
    }

    private var snapshot: AppLinkSnapshot { model.snapshot }

    var body: some View {
        Group {
            if !snapshot.loaded {
                LoadingIndicator()
            } else if snapshot.linked {
                AppLinkLinkedView(
                    snapshot: snapshot,
                    makeDevicesSession: { [sessions] in sessions.makeAppLinkDevicesSession() },
                    thisDevice: { thisDeviceSection },
                    categories: { categoriesSection },
                    addDevice: { scan in destination = .pair(scan: scan) },
                    rename: model.rename,
                    openConnection: { destination = .connection }
                )
            } else {
                unlinkedForm
            }
        }
        .navigationTitle(title)
        .observing(start: model.start, stop: model.stop)
        .navigationDestination(item: $destination) { destination in
            screen(destination)
        }
    }

    private var unlinkedForm: some View {
        SettingsForm {
            Section {
                AppLinkUnlinkedHero()
            }
            .listRowBackground(Color.clear)

            if snapshot.premiumRequired {
                Section {
                    KeyguardPremiumNote(text: L10n.appLinkPremiumNote)
                }
            }

            Section {
                KeyguardPairMenuRow(
                    title: L10n.appLinkLinkDeviceTitle,
                    text: L10n.appLinkLinkDeviceText,
                    systemImage: "link.badge.plus"
                ) { scan in
                    destination = .pair(scan: scan)
                }
                .disabled(!snapshot.canLink)
            }

            thisDeviceSection
            categoriesSection
            AppLinkInfoSection(notes: AppLinkInfoSection.howItWorks)
        }
    }

    /// This device: its name and the relay it reaches the other devices through.
    private var thisDeviceSection: some View {
        Section(L10n.appLinkThisDevice) {
            Button(action: model.rename) {
                LabeledContent {
                    Text(snapshot.deviceLabel)
                        .lineLimit(1)
                } label: {
                    Label(L10n.appLinkDeviceNameLabel, systemImage: PairingStageView.thisDeviceSymbol)
                }
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityHint(L10n.rename)
            Button {
                destination = .connection
            } label: {
                HStack {
                    LabeledContent {
                        VStack(alignment: .trailing, spacing: 2) {
                            Text(snapshot.relayText)
                            if let lanText = snapshot.lanText {
                                Text(lanText)
                                    .font(.footnote)
                            }
                        }
                    } label: {
                        Label(L10n.appLinkConnectionTitle, systemImage: "server.rack")
                    }
                    Image(systemName: "chevron.forward")
                        .font(.footnote.weight(.semibold))
                        .foregroundStyle(.tertiary)
                        .accessibilityHidden(true)
                }
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
        }
    }

    /// What this device syncs; set before the first link, too.
    private var categoriesSection: some View {
        Section {
            ForEach(snapshot.categories, id: \.key) { category in
                Toggle(
                    isOn: Binding(
                        get: { category.checked },
                        set: { model.setCategory(key: category.key, value: $0) }
                    )
                ) {
                    Text(category.title)
                }
                .disabled(!category.enabled)
            }
        } header: {
            Text(L10n.appLinkSyncSectionTitle)
        }
    }

    @ViewBuilder
    private func screen(_ destination: AppLinkDestination) -> some View {
        switch destination {
        case .pair(let scan):
            KeyguardPairView(
                makeSource: { [sessions] in sessions.makeAppLinkPairingSession(scan: scan) },
                title: L10n.appLinkLinkDeviceTitle,
                premiumNote: L10n.appLinkPremiumNote,
                addAnotherTitle: L10n.appLinkLinkAnotherDevice,
                onComplete: { self.destination = nil }
            )
        case .connection:
            AppLinkConnectionView(
                makeSource: { [sessions] in sessions.makeAppLinkConnectionSession() }
            )
        }
    }
}

/// A screen the linked devices settings open.
private enum AppLinkDestination: Hashable {
    /// Links a device; this device scans the code of the other one, or shows its own.
    case pair(scan: Bool)
    /// How this device reaches the other ones.
    case connection
}

/// The devices of the link as a mesh, this device, what it syncs, and unlinking.
private struct AppLinkLinkedView<ThisDevice: View, Categories: View>: View {
    let snapshot: AppLinkSnapshot
    @ViewBuilder let thisDevice: () -> ThisDevice
    @ViewBuilder let categories: () -> Categories
    /// Links another device, scanning its code if `true`, or showing one.
    let addDevice: @MainActor (_ scan: Bool) -> Void
    /// Renames this device; the mesh offers it, as the this device section does.
    let rename: @MainActor () -> Void
    /// Opens the relay settings of this device.
    let openConnection: @MainActor () -> Void
    @State private var devicesModel: KeyguardDevicesModel

    init(
        snapshot: AppLinkSnapshot,
        makeDevicesSession: @escaping () -> KeyguardDevicesSession,
        @ViewBuilder thisDevice: @escaping () -> ThisDevice,
        @ViewBuilder categories: @escaping () -> Categories,
        addDevice: @escaping @MainActor (_ scan: Bool) -> Void,
        rename: @escaping @MainActor () -> Void,
        openConnection: @escaping @MainActor () -> Void
    ) {
        self.snapshot = snapshot
        self.thisDevice = thisDevice
        self.categories = categories
        self.addDevice = addDevice
        self.rename = rename
        self.openConnection = openConnection
        _devicesModel = State(wrappedValue: KeyguardDevicesModel(makeSource: { makeDevicesSession() }))
    }

    private var devices: KeyguardDevicesSnapshot { devicesModel.snapshot }

    var body: some View {
        Group {
            if devices.loaded {
                SettingsForm {
                    Section {
                        VStack(spacing: 8) {
                            AppLinkMeshView(
                                peers: devices.devices.filter { !$0.isThisDevice },
                                model: devicesModel,
                                thisDeviceLabel: snapshot.deviceLabel,
                                rename: rename,
                                openConnection: openConnection
                            )
                            KeyguardDevicesHeaderRow(header: devices.header)
                        }
                        .frame(maxWidth: .infinity)
                        KeyguardDevicesRemovedRow(snapshot: devices)
                    }
                    .listRowBackground(Color.clear)
                    if snapshot.premiumRequired {
                        Section {
                            KeyguardPremiumNote(text: L10n.appLinkPremiumNote)
                        }
                    }
                    KeyguardDevicesListSection(
                        title: L10n.appLinkOtherDevicesSectionTitle,
                        model: devicesModel,
                        addDevice: devices.canAddDevice ? addDevice : nil
                    )
                    thisDevice()
                    categories()
                    KeyguardDevicesLeaveSection(model: devicesModel)
                    AppLinkInfoSection(notes: AppLinkInfoSection.howItWorks)
                }
            } else {
                LoadingIndicator()
            }
        }
        .observing(start: devicesModel.start, stop: devicesModel.stop)
    }
}
