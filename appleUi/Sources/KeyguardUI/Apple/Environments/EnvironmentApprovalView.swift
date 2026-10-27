#if os(macOS)
import AppKit
import SwiftUI
import KeyguardShared

struct EnvironmentApprovalView: View {
    @Environment(EnvironmentAccessModel.self) private var model
    let request: EnvironmentApprovalSnapshot
    @State private var grantError = false

    var body: some View {
        AgentApprovalPrompt(
            requestID: request.id,
            expiresAtEpochMs: request.expiresAtEpochMs,
            title: L10n.environmentsApprovalTitle,
            systemImage: "terminal",
            elevatedAccessHost: .environmentApproval,
            currentRequestID: { model.requests.first?.id },
            resolve: resolve
        ) {
            AgentApprovalDetails(
                rows: AgentApprovalDetail.caller(
                    name: request.callerName ?? L10n.environmentsLocalProcess, path: request.callerPath))
            // The client reports its own name, so it is shown apart from the verified caller.
            if let client = request.clientName {
                Text(L10n.environmentsApprovalClient(client)).foregroundStyle(.secondary)
            }
            if let name = request.environmentName { Text(name) }
            if let account = request.accountName { Text(account).foregroundStyle(.secondary) }
            Text(capabilityTitle)
            if let path = request.path { Text(path).textSelection(.enabled) }
            Text(L10n.environmentsApprovalNote).font(.callout).foregroundStyle(.secondary)
            if showsLocalFileNote {
                Text(L10n.environmentsApprovalLocalFileNote).font(.callout).foregroundStyle(.secondary)
            }
            if grantError { Text(L10n.environmentsGrantErrorWrongFolder).foregroundStyle(.red) }
        }
    }

    private var showsLocalFileNote: Bool {
        switch request.capability {
        case .createLocalEnvFile, .readLocalEnvFile: return true
        default: return false
        }
    }

    private var capabilityTitle: String {
        switch request.capability {
        case .authenticate: return L10n.environmentsCapabilityAuthenticate
        case .listEnvironments: return L10n.environmentsCapabilityList
        case .createEnvironment: return L10n.environmentsCapabilityCreate
        case .renameEnvironment: return L10n.environmentsCapabilityRename
        case .listVariables: return L10n.environmentsCapabilityVariables
        case .appendVariables: return L10n.environmentsCapabilityUpdate
        case .createLocalEnvFile: return L10n.environmentsCapabilityCreateMount
        case .listLocalEnvFiles: return L10n.environmentsCapabilityMounts
        case .readLocalEnvFile: return L10n.environmentsCapabilityRead
        default: return L10n.environmentsApprovalTitle
        }
    }

    private func resolve(requestID: String, approved: Bool, reset: @escaping () -> Void) {
        guard approved, request.capability == .createLocalEnvFile, let path = request.path else {
            model.respond(id: requestID, approved: approved)
            return
        }
        let parent = URL(fileURLWithPath: path).deletingLastPathComponent().resolvingSymlinksInPath()
            .standardizedFileURL
        let panel = NSOpenPanel()
        panel.canChooseDirectories = true
        panel.canChooseFiles = false
        panel.allowsMultipleSelection = false
        panel.directoryURL = parent
        panel.message = L10n.environmentsGrantDirectory
        panel.begin { result in
            guard result == .OK, let url = panel.url else {
                reset()
                return
            }
            guard url.resolvingSymlinksInPath().standardizedFileURL == parent else {
                grantError = true
                reset()
                return
            }
            let access = url.startAccessingSecurityScopedResource()
            defer { if access { url.stopAccessingSecurityScopedResource() } }
            guard let bookmark = url.securityScopedBookmarkToken() else {
                grantError = true
                reset()
                return
            }
            model.provideDirectoryGrant(path: path, bookmark: bookmark)
            model.respond(id: requestID, approved: true)
        }
    }
}
#endif
