import SwiftUI
import KeyguardShared

struct DatePickerSheet: View {
    @Environment(FilePickerModel.self) private var filePickerModel
    @Environment(\.dismiss) private var dismiss

    #if os(iOS)
    @Environment(\.horizontalSizeClass) private var horizontalSizeClass
    #endif

    let request: AddDatePickerRequest

    @State private var selectedMonth: Int
    @State private var selectedYear: Int
    @State private var selection: Date

    init(request: AddDatePickerRequest) {
        self.request = request
        _selection = State(initialValue: Self.initialDate(request))
        _selectedMonth = State(initialValue: Int(request.month))
        _selectedYear = State(initialValue: Int(request.year))
    }

    private var isTime: Bool { request.kind == AddDatePickerKind.time }

    var body: some View {
        ModalSheet(
            title: isTime ? L10n.time : L10n.datepickerTitle,
            width: 420,
            height: isTime ? 320 : 460,
            detents: [.medium],
            dismissLabel: L10n.cancel
        ) {
            VStack {
                if request.kind == AddDatePickerKind.monthYear {
                    HStack {
                        Picker(L10n.cardExpiryMonth, selection: $selectedMonth) {
                            ForEach(1...12, id: \.self) { month in
                                Text(Self.calendar.monthSymbols[month - 1]).tag(month)
                            }
                        }
                        Picker(L10n.cardExpiryYear, selection: $selectedYear) {
                            ForEach(Int(request.minYear)...Int(request.maxYear), id: \.self) { year in
                                Text(String(year)).tag(year)
                            }
                        }
                    }
                    #if os(iOS)
                    .pickerStyle(.wheel)
                    #endif
                } else if isTime {
                    timePicker
                } else {
                    DatePicker(
                        L10n.date,
                        selection: $selection,
                        in: dateRange,
                        displayedComponents: [.date]
                    )
                    .datePickerStyle(.graphical)
                    .labelsHidden()
                }
            }
            .padding(20)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
        } actions: {
            Button(L10n.ok) {
                confirm()
                dismiss()
            }
            .keyboardShortcut(.defaultAction)
        }
        .environment(\.calendar, Self.calendar)
    }

    @ViewBuilder
    private var timePicker: some View {
        let picker = DatePicker(
            L10n.time,
            selection: $selection,
            displayedComponents: [.hourAndMinute]
        )
        .labelsHidden()
        #if os(iOS)
        // Wheel only on compact width (iPhone); leave iPad regular-width to the
        // system default `.compact` field, which reads better in that context.
        if horizontalSizeClass == .compact {
            picker.datePickerStyle(.wheel)
        } else {
            picker
        }
        #else
        picker
        #endif
    }

    private func confirm() {
        if request.kind == AddDatePickerKind.monthYear {
            filePickerModel.resolveDatePicker(
                year: Int32(selectedYear), month: Int32(selectedMonth),
                day: 1, hour: 0, minute: 0
            )
            return
        }
        let comps = Self.calendar.dateComponents(
            [.year, .month, .day, .hour, .minute],
            from: selection
        )
        filePickerModel.resolveDatePicker(
            year: Int32(comps.year ?? 0),
            month: Int32(comps.month ?? 1),
            day: Int32(comps.day ?? 1),
            hour: Int32(comps.hour ?? 0),
            minute: Int32(comps.minute ?? 0)
        )
    }

    private var dateRange: ClosedRange<Date> {
        guard request.hasRange else {
            return Date.distantPast...Date.distantFuture
        }
        var lower = DateComponents()
        lower.year = Int(request.minYear)
        lower.month = Int(request.minMonth)
        lower.day = Int(request.minDay)
        var upper = DateComponents()
        upper.year = Int(request.maxYear)
        upper.month = Int(request.maxMonth)
        upper.day = Int(request.maxDay)
        let cal = Self.calendar
        let lowerDate = cal.date(from: lower) ?? Date.distantPast
        let upperDate = cal.date(from: upper) ?? Date.distantFuture
        // Guard against an inverted range (defensive — the shared range is ordered).
        return lowerDate <= upperDate ? lowerDate...upperDate : Date.distantPast...Date.distantFuture
    }

    private static func initialDate(_ request: AddDatePickerRequest) -> Date {
        var comps = DateComponents()
        if request.kind == AddDatePickerKind.time {
            let now = calendar.dateComponents([.year, .month, .day], from: .now)
            comps.year = now.year
            comps.month = now.month
            comps.day = now.day
            comps.hour = Int(request.hour)
            comps.minute = Int(request.minute)
        } else {
            comps.year = Int(request.year)
            comps.month = Int(request.month)
            comps.day = Int(request.day)
        }
        return calendar.date(from: comps) ?? .now
    }

    // Kotlin LocalDate and card expiry fields carry Gregorian components,
    // regardless of the calendar selected in the device's regional settings.
    private static var calendar: Calendar {
        var calendar = Calendar(identifier: .gregorian)
        calendar.locale = .current
        calendar.timeZone = .current
        return calendar
    }
}

/// Kotlin data classes are not `Identifiable`, so this keys `.sheet(item:)` by the request id.
struct PendingDatePicker: Identifiable {
    let id: String
    let request: AddDatePickerRequest

    init(_ request: AddDatePickerRequest) {
        self.id = request.requestId
        self.request = request
    }
}
