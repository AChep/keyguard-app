import SwiftUI
import KeyguardShared

struct EnvironmentVariableRow: View {
    let model: EnvironmentsModel
    let index: Int32
    let variable: EnvironmentVariableSnapshot
    let editable: Bool

    var body: some View {
        // Borderless, so that iOS fires only the tapped button of the row.
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                typeMenu
                EnvironmentTextField(title: L10n.environmentsVariableName, value: variable.name) {
                    model.session.setVariableName(index: index, value: $0)
                }
                .disabled(!editable)
            }
            if let nameError {
                Text(nameError).foregroundStyle(.red)
            }
            switch variable.kind {
            case .value:
                if let value = variable.value {
                    EnvironmentTextField(
                        title: L10n.fieldValue, value: value, concealed: !variable.revealed
                    ) {
                        model.session.setVariableValue(index: index, value: $0)
                    }
                    .disabled(!editable)
                    HStack {
                        Button(variable.revealed ? L10n.hide : L10n.fileActionRevealTitle) {
                            model.session.reveal(index: index)
                        }
                        Button(L10n.copyValue) { model.session.doCopyVariable(index: index) }
                    }
                }
            case .reference:
                if let reference = variable.reference {
                    referenceButton(reference)
                }
            case .unset:
                Text(L10n.environmentsUnset).foregroundStyle(.secondary)
            default:
                Text(L10n.environmentsVariableUnsupported).foregroundStyle(.secondary)
            }
            Button(L10n.remove, role: .destructive) { model.session.removeVariable(index: index) }
                .disabled(!editable)
        }
        .buttonStyle(.borderless)
    }

    private var nameError: String? {
        if variable.nameInvalid { return L10n.environmentsVariableErrorInvalidName }
        if variable.nameDuplicate { return L10n.environmentsVariableErrorDuplicateName }
        return nil
    }

    /// Shows the type of the variable; picking a reference opens the field picker.
    private var typeMenu: some View {
        Menu {
            typeButton(L10n.environmentsVariableTypeValue, systemImage: "textformat", kind: .value)
            typeButton(L10n.environmentsVariableTypeReference, systemImage: "link", kind: .reference)
            typeButton(L10n.environmentsUnset, systemImage: "nosign", kind: .unset)
        } label: {
            Label(L10n.environmentsVariableType, systemImage: variable.kind.systemImage)
                .labelStyle(.iconOnly)
        }
        .menuStyle(.borderlessButton)
        .fixedSize()
        .disabled(!editable)
    }

    private func typeButton(_ title: String, systemImage: String, kind: EnvironmentVariableKind) -> some View {
        Button {
            model.session.setVariableType(index: index, kind: kind)
        } label: {
            Label(title, systemImage: variable.kind == kind ? "checkmark" : systemImage)
        }
    }

    /// The referenced item and field; the value is never shown here.
    private func referenceButton(_ reference: EnvironmentReferenceSnapshot) -> some View {
        Button {
            model.session.pickVariableReference(index: index)
        } label: {
            HStack {
                if let itemName = reference.itemName {
                    Image(systemName: "link")
                    Text("\(itemName) › \(reference.customFieldName ?? L10n.password)")
                } else {
                    Image(systemName: "exclamationmark.triangle")
                        .foregroundStyle(.red)
                    Text(L10n.environmentsReferenceUnavailable)
                        .foregroundStyle(.red)
                }
                Spacer()
                Image(systemName: "chevron.forward")
                    .foregroundStyle(.secondary)
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .disabled(!editable)
    }
}

extension EnvironmentVariableKind {
    fileprivate var systemImage: String {
        switch self {
        case .value: "textformat"
        case .reference: "link"
        case .unset: "nosign"
        default: "exclamationmark.triangle"
        }
    }
}
