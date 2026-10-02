import Foundation
import Observation
import KeyguardShared

typealias CipherDetailModel = DetailSessionModel<ItemDetailTarget, CipherDetailFrame, any CipherDetailSessionSource>

/// One cipher detail frame. TOTP ticks update [totp] in place, so they re-render the code rows only.
struct CipherDetailFrame {
    let detail: VaultDetailSnapshot
    let totp: CipherDetailTotp
}

/// The TOTP channel of one detail session.
@MainActor
@Observable
final class CipherDetailTotp {
    fileprivate(set) var value: VaultDetailTotpSnapshot?

    /// TOTP that belongs to another cipher (a frame the detail moved past) is never shown.
    func state(cipherId: String, rowId: String) -> TotpFieldSnapshot? {
        guard let value, value.cipherId == cipherId else { return nil }
        return value.states[rowId]
    }
}

extension DetailSessionModel
where Target == ItemDetailTarget, Snapshot == CipherDetailFrame, Session == any CipherDetailSessionSource {
    static func cipherDetail(core: KeyguardCore) -> CipherDetailModel {
        cipherDetail { core.makeCipherDetailSession(itemId: $0.itemId, accountId: $0.accountId) }
    }

    static func cipherDetail(
        makeSession: @escaping (ItemDetailTarget) -> any CipherDetailSessionSource
    ) -> CipherDetailModel {
        CipherDetailModel(makeSession: makeSession) { session, onChange in
            let totp = CipherDetailTotp()
            return session.subscribe(
                onChange: { onChange(CipherDetailFrame(detail: $0, totp: totp)) },
                onTotpChange: { value in Task { @MainActor in totp.value = value } }
            )
        }
    }
}
