import SwiftUI
import KeyguardShared

extension View {
    /// Presents the WebDAV and S3 settings sheets of `model`.
    func remoteSettingsSheets(_ model: RemoteSettingsModel?) -> some View {
        modifier(RemoteSettingsSheetsModifier(model: model))
    }

    /// Presents the WebDAV and S3 settings sheets the screen of `entry` opened.
    func remoteSettingsSheets(for entry: ScreenEntrySnapshot) -> some View {
        modifier(EntryRemoteSettingsSheets(entry: entry))
    }
}

private struct RemoteSettingsSheetsModifier: ViewModifier {
    let model: RemoteSettingsModel?

    func body(content: Content) -> some View {
        content
            .sheet(
                isPresented: Binding(
                    get: { model?.webDav != nil },
                    set: { if !$0 { model?.dismissWebDav() } }
                )
            ) {
                if let model, let snapshot = model.webDav {
                    WebDavSettingsSheet(sessionId: snapshot.id)
                        .environment(model)
                }
            }
            .sheet(
                isPresented: Binding(
                    get: { model?.s3 != nil },
                    set: { if !$0 { model?.dismissS3() } }
                )
            ) {
                if let model, let snapshot = model.s3 {
                    S3SettingsSheet(sessionId: snapshot.id)
                        .environment(model)
                }
            }
    }
}

/// A stack entry publishes its sheets with its snapshot, and takes their input through its
/// own `RemoteSettingsSheets`.
private struct EntryRemoteSettingsSheets: ViewModifier {
    let entry: ScreenEntrySnapshot
    @Environment(NavigationModel.self) private var navigationModel
    @State private var model: RemoteSettingsModel?

    func body(content: Content) -> some View {
        content
            .onChange(of: entry.webDavSettings, initial: true) { _, snapshot in
                resolveModel(create: snapshot != nil)?.receiveWebDav(snapshot)
            }
            .onChange(of: entry.s3Settings, initial: true) { _, snapshot in
                resolveModel(create: snapshot != nil)?.receiveS3(snapshot)
            }
            .remoteSettingsSheets(model)
    }

    private func resolveModel(create: Bool) -> RemoteSettingsModel? {
        if let model { return model }
        guard create, let source = navigationModel.remoteSettingsSheets(instanceId: entry.instanceId) else {
            return nil
        }
        let created = RemoteSettingsModel(source: source)
        model = created
        return created
    }
}
