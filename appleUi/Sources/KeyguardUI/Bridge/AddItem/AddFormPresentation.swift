/// The shared presentation lifetime specialized for vault-item and Send editors.
typealias AddFormPresentation = FormPresentation<AddFormModel>

extension FormPresentation where Model == AddFormModel {
    convenience init() { self.init(stop: { $0.stopAddFormObservation() }) }
}
