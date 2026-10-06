import SwiftUI
import Observation
import KeyguardShared
import UniformTypeIdentifiers

@MainActor
@Observable
final class FilePickerModel {
    private let core: KeyguardCore

    init(core: KeyguardCore) {
        self.core = core
    }

    @ObservationIgnored private var started = false
    let session = FilePickerSession()

    func start() {
        guard !started else { return }
        started = true
        core.setAddDatePickerRequestHandler { [weak self] request in
            Task { @MainActor [weak self] in
                self?.pendingDatePicker = PendingDatePicker(request)
            }
        }
        core.setConfirmationFilePickerRequestHandler { [weak self] request in
            Task { @MainActor [weak self] in
                self?.session.presentFilePicker(
                    for: request,
                    resolve: { [weak self] requestId, uri, name, size, _ in
                        Task { @MainActor [weak self] in
                            self?.core.resolveConfirmationFilePicker(
                                requestId: requestId,
                                uri: uri,
                                name: name,
                                size: size
                            )
                        }
                    },
                    cancel: { [weak self] requestId in
                        Task { @MainActor [weak self] in
                            self?.core.cancelConfirmationFilePicker(requestId: requestId)
                        }
                    }
                )
            }
        }

    }

    var pendingDatePicker: PendingDatePicker?

    /// Date requests read year/month/day; time requests read hour/minute.
    func resolveDatePicker(requestId: String, year: Int32, month: Int32, day: Int32, hour: Int32, minute: Int32) {
        guard let pending = pendingDatePicker, pending.request.requestId == requestId else { return }
        pendingDatePicker = nil
        core.resolveAddDatePicker(
            requestId: pending.request.requestId,
            year: year,
            month: month,
            day: day,
            hour: hour,
            minute: minute
        )
    }

    func cancelDatePicker() {
        guard let pending = pendingDatePicker else { return }
        pendingDatePicker = nil
        core.cancelAddDatePicker(requestId: pending.request.requestId)
    }

}
