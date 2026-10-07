#if os(macOS)
import SwiftUI
import KeyguardShared

struct GpgAgentSettingsSections: View {
    @Environment(GpgAgentModel.self) private var model
    @State private var dialog: Dialog?

    private enum Dialog: String, Identifiable {
        case setup, filters, history
        var id: String { rawValue }
    }

    private var settings: GpgAgentSettingsSnapshot { model.gpgAgentSettings }
    private var status: GpgAgentStatusSnapshot { model.gpgAgentStatus }

    var body: some View {
        Group {
            Section {
                Toggle(
                    L10n.agentTitle(L10n.protocolGpg),
                    isOn: Binding(get: { settings.enabled }, set: { model.setGpgAgentEnabled($0) })
                )
                .disabled(!settings.loaded || (status.state == .unsupported && !settings.enabled))
                .settingsSearchTarget(.gpgEnable)
                statusRow
                    .settingsSearchTarget(.gpgStatus)
                if let diagnostic = status.diagnostic, !diagnostic.isEmpty {
                    Text(diagnostic).font(.callout).foregroundStyle(.secondary).textSelection(.enabled)
                }
                if status.state == .failed && status.enabled {
                    Button(L10n.retry) { model.retryGpgAgent() }
                }
                Button(L10n.agentSetupTitle(L10n.protocolGpg)) { dialog = .setup }
                    .settingsSearchTarget(.gpgSetup)
            } header: {
                Text(L10n.agentTitle(L10n.protocolGpg))
            } footer: {
                Text(L10n.gpgAgentSetupIntro)
            }
            .sheet(item: $dialog) { dialog in
                switch dialog {
                case .setup:
                    ModalSheet(title: L10n.agentSetupTitle(L10n.protocolGpg)) { GpgAgentSetupView() }
                case .filters:
                    AgentFiltersView(
                        title: L10n.agentFiltersTitle(L10n.protocolGpg),
                        lockedText: L10n.agentHistoryResponseVaultLocked,
                        note: L10n.agentFiltersNoteSaveToApply,
                        makeSession: model.makeFiltersSession
                    )
                case .history:
                    ModalSheet(title: L10n.agentHistoryHeaderTitle(L10n.protocolGpg)) { GpgAgentHistoryView() }
                }
            }

            Section {
                optionPicker(
                    L10n.prefItemAgentApprovalWindowTitle,
                    options: settings.approvalWindowOptions,
                    currentTitle: settings.approvalWindowTitle,
                    set: { model.setGpgAgentApprovalWindow(optionId: $0) }
                )
                .settingsSearchTarget(.gpgApproval)
                optionPicker(
                    L10n.prefItemAgentApprovalScopeTitle,
                    options: settings.approvalCachePolicyOptions,
                    currentTitle: settings.approvalCachePolicyTitle,
                    set: { model.setGpgAgentApprovalCachePolicy(optionId: $0) }
                )
                .settingsSearchTarget(.gpgScope)
            } header: {
                Text(L10n.agentApprovalsHeaderTitle)
            } footer: {
                Text(L10n.prefItemGpgAgentApprovalsNote)
            }
            .disabled(!settings.loaded)

            Section {
                Toggle(
                    L10n.prefItemAgentDisplayKeyNamesTitle,
                    isOn: Binding(get: { settings.displayKeyNames }, set: { model.setGpgAgentDisplayKeyNames($0) })
                )
                .disabled(!settings.loaded)
                .settingsSearchTarget(.gpgNames)
            } header: {
                Text(L10n.agentKeysHeaderTitle)
            } footer: {
                Text(L10n.prefItemAgentDisplayKeyNamesNote)
            }

            Section {
                Button {
                    dialog = .filters
                } label: {
                    HStack {
                        Label(
                            L10n.agentFiltersTitle(L10n.protocolGpg), systemImage: "line.3.horizontal.decrease.circle")
                        Spacer()
                        Text(
                            settings.filterActive
                                ? L10n.prefItemAgentFiltersSummaryActive : L10n.prefItemAgentFiltersSummaryAll
                        )
                        .font(.callout).foregroundStyle(.secondary)
                    }
                }
                .settingsSearchTarget(.gpgFilters)
                Button {
                    dialog = .history
                } label: {
                    Label(L10n.prefItemAgentHistoryTitle, systemImage: "clock.arrow.circlepath")
                }
                .settingsSearchTarget(.gpgHistory)
            }
        }

    }

    @ViewBuilder
    private var statusRow: some View {
        switch status.state {
        case .ready:
            Label(L10n.prefItemAgentStatusReady, systemImage: "checkmark.circle.fill").foregroundStyle(.green)
        case .starting:
            HStack {
                ProgressView().controlSize(.small)
                Text(L10n.prefItemAgentStatusStarting).foregroundStyle(.secondary)
            }
        case .failed:
            Label(L10n.prefItemAgentStatusFailed, systemImage: "exclamationmark.triangle.fill")
                .foregroundStyle(.orange)
        case .unsupported:
            Label(L10n.prefItemAgentStatusUnsupported, systemImage: "xmark.circle").foregroundStyle(.secondary)
        default:
            Label(L10n.prefItemAgentStatusStopped, systemImage: "stop.circle").foregroundStyle(.secondary)
        }
    }

    @ViewBuilder
    private func optionPicker(
        _ title: String,
        options: [SettingOptionSnapshot],
        currentTitle: String,
        set: @escaping @MainActor @Sendable (String) -> Void
    ) -> some View {
        if options.isEmpty {
            LabeledContent(title, value: currentTitle)
        } else {
            Picker(
                title,
                selection: Binding(
                    get: { options.first(where: { $0.selected })?.id ?? "" },
                    set: { value in set(value) }
                )
            ) {
                ForEach(options, id: \.id) { option in Text(option.title).tag(option.id) }
            }
        }
    }
}
#endif
