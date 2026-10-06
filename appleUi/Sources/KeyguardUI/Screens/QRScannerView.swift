#if os(iOS)
import SwiftUI
import AVFoundation
import VisionKit

struct QRScannerView: View {
    @Environment(\.dismiss) private var dismiss
    @Environment(\.scenePhase) private var scenePhase

    /// Called with the first recognised QR payload; the sheet then dismisses itself.
    let onScanned: (String) -> Void

    @State private var authorization = AVCaptureDevice.authorizationStatus(for: .video)
    @State private var requestingAccess = false
    @State private var scannerError: String?

    var body: some View {
        NavigationStack {
            content
                .navigationTitle(L10n.scanqrTitle)
                .navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) {
                        Button(L10n.cancel) { dismiss() }
                    }
                }
        }
        .onChange(of: scenePhase, initial: true) { _, phase in
            if phase == .active {
                // Returning from Settings keeps the sheet mounted, so onAppear
                // alone would leave a newly granted permission in the denied UI.
                authorization = AVCaptureDevice.authorizationStatus(for: .video)
            }
        }
        .task(id: requestingAccess) {
            guard requestingAccess else { return }
            _ = await AVCaptureDevice.requestAccess(for: .video)
            guard !Task.isCancelled else { return }
            authorization = AVCaptureDevice.authorizationStatus(for: .video)
            requestingAccess = false
        }
    }

    @ViewBuilder
    private var content: some View {
        switch authorization {
        case .authorized:
            scanner
        case .notDetermined:
            requestAccess
        default:
            // .denied / .restricted: point the user at Settings.
            permissionDenied
        }
    }

    /// Falls back to a notice where VisionKit can't run (the Simulator reports `isSupported == false`).
    @ViewBuilder
    private var scanner: some View {
        if let scannerError {
            ContentUnavailableView {
                Label(L10n.scanqrTitle, systemImage: "qrcode.viewfinder")
            } description: {
                Text(scannerError)
            } actions: {
                Button(L10n.retry) { self.scannerError = nil }
            }
        } else if DataScannerViewController.isSupported && DataScannerViewController.isAvailable {
            DataScannerRepresentable(isActive: scenePhase == .active) { value in
                onScanned(value)
                dismiss()
            } onFailure: { error in
                scannerError = error.localizedDescription
            }
            .ignoresSafeArea()
        } else {
            notice(
                systemImage: "qrcode.viewfinder",
                title: L10n.scanqrTitle,
                message: L10n.scanqrCameraUnavailableText
            )
        }
    }

    private var requestAccess: some View {
        notice(
            systemImage: "camera",
            title: L10n.prefItemPermissionCameraTitle,
            message: L10n.scanqrCameraPermissionRequiredText
        ) {
            Button(L10n.grantPermission) {
                requestingAccess = true
            }
            .buttonStyle(.borderedProminent)
            .disabled(requestingAccess)
        }
    }

    private var permissionDenied: some View {
        notice(
            systemImage: "camera.fill",
            title: L10n.prefItemPermissionCameraTitle,
            message: L10n.scanqrCameraPermissionRequiredText
        ) {
            if let url = URL(string: UIApplication.openSettingsURLString) {
                Link(L10n.settingsMainHeaderTitle, destination: url)
                    .buttonStyle(.borderedProminent)
            }
        }
    }

    private func notice(
        systemImage: String,
        title: String,
        message: String,
        @ViewBuilder action: () -> some View = { EmptyView() }
    ) -> some View {
        VStack(spacing: 12) {
            Image(systemName: systemImage)
                .font(.system(size: 40))
                .foregroundStyle(.secondary)
                .accessibilityHidden(true)
            Text(title)
                .font(.headline)
            Text(message)
                .font(.callout)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
            action()
                .padding(.top, 4)
        }
        .frame(maxWidth: 320)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .padding()
    }
}

private struct DataScannerRepresentable: UIViewControllerRepresentable {
    let isActive: Bool
    let onFound: (String) -> Void
    let onFailure: (Error) -> Void

    func makeUIViewController(context: Context) -> DataScannerViewController {
        let controller = DataScannerViewController(
            recognizedDataTypes: [.barcode(symbologies: [.qr])],
            qualityLevel: .balanced,
            recognizesMultipleItems: false,
            isHighFrameRateTrackingEnabled: false,
            isGuidanceEnabled: true,
            isHighlightingEnabled: true
        )
        controller.delegate = context.coordinator
        return controller
    }

    func updateUIViewController(_ controller: DataScannerViewController, context: Context) {
        context.coordinator.onFound = onFound
        context.coordinator.onFailure = onFailure
        if isActive {
            context.coordinator.start(controller)
        } else {
            context.coordinator.stop(controller)
        }
    }

    static func dismantleUIViewController(_ controller: DataScannerViewController, coordinator: Coordinator) {
        coordinator.stop(controller)
    }

    func makeCoordinator() -> Coordinator { Coordinator(onFound: onFound, onFailure: onFailure) }

    @MainActor
    final class Coordinator: NSObject, DataScannerViewControllerDelegate {
        var onFound: (String) -> Void
        var onFailure: (Error) -> Void
        private var startTask: Task<Void, Never>?
        private var isActive = false
        /// Latches after the first hit so a multi-frame recognition fires once.
        private var didFind = false

        init(onFound: @escaping (String) -> Void, onFailure: @escaping (Error) -> Void) {
            self.onFound = onFound
            self.onFailure = onFailure
        }

        func start(_ controller: DataScannerViewController) {
            isActive = true
            guard !didFind, !controller.isScanning, startTask == nil else { return }
            // Starting can fail even after the availability check. Defer until
            // after the representable update before publishing an error to SwiftUI.
            startTask = Task { @MainActor [weak self, weak controller] in
                guard let self, let controller, !Task.isCancelled else { return }
                defer { startTask = nil }
                do {
                    try controller.startScanning()
                } catch {
                    onFailure(error)
                }
            }
        }

        func stop(_ controller: DataScannerViewController) {
            isActive = false
            startTask?.cancel()
            startTask = nil
            controller.stopScanning()
        }

        func dataScanner(
            _ dataScanner: DataScannerViewController,
            becameUnavailableWithError error: DataScannerViewController.ScanningUnavailable
        ) {
            guard isActive else { return }
            onFailure(error)
        }

        func dataScanner(
            _ dataScanner: DataScannerViewController,
            didAdd addedItems: [RecognizedItem],
            allItems: [RecognizedItem]
        ) {
            report(addedItems)
        }

        func dataScanner(
            _ dataScanner: DataScannerViewController,
            didTapOn item: RecognizedItem
        ) {
            report([item])
        }

        private func report(_ items: [RecognizedItem]) {
            guard isActive, !didFind else { return }
            for item in items {
                if case let .barcode(barcode) = item, let value = barcode.payloadStringValue {
                    didFind = true
                    onFound(value)
                    return
                }
            }
        }
    }
}
#endif
