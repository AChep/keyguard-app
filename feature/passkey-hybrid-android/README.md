# Cross-device passkeys

This Android UI answers a nearby browser's FIDO hybrid QR request using passkeys
from the unlocked vault, or creates and saves a new passkey. Its route factory adds
**Use passkeys on another device** to the main vault menu.

## Boundaries

- `util:fido-hybrid` owns QR decoding, bounded CBOR, CTAP, PRF extension framing,
  and the encrypted transaction. Its authenticator interface returns typed
  WebAuthn results; CTAP encoding and transferred response-buffer cleanup stay in
  this module. `HybridRelay` keeps the advertised domain ID and relay URL together.
- `util:crypto` implements Noise, traffic encryption, and CTAP PIN/UV key agreement
  in Rust. `util:webauthn` creates credentials and signs assertions.
- `feature:passkey-hybrid-presentation` owns the session state, selection and
  verification sequence, cancellation, and save-before-success behavior. It is
  Compose-free and has no application-module dependencies. Its `HybridVault`
  interface carries identifiers and WebAuthn models, without application models.
- `common` supplies `HybridVaultAdapter`, which handles visible accounts, fresh
  credential reads, write checks, persistence, and vault-session identity. Other
  platform entry points can reuse this adapter.
- This module supplies Bluetooth advertising, runtime permissions, Compose UI,
  navigation, and dependency composition. Android and desktop JVM targets share
  `OkHttpHybridTunnelConnector` from `util:fido-hybrid`'s `jvmCommonMain` source set.

A platform that handles the QR code in the app binds `PasskeyHybridRouteFactory`;
without a binding, the vault menu has no QR action. Apple system-managed
credential-provider flows can continue using their native entry points; the
custom hybrid transaction is independent of them.

## Lifetime and compatibility

QR and session secrets are never persisted. Requests expire after two minutes;
leaving the screen or retiring the vault cancels the operation. Credentials are
read again after verification, and write access is checked again before saving.
The shared transport checks the vault guard before every send, including the
handshake. Successful typed results transfer their signature and PRF buffers to
the protocol layer, which clears them after encoding, including encoding failures.

QR-initiated registration and sign-in use Google's `cable.ua5v.com` relay.
The `hmac-secret` and `hmac-secret-mc` extensions support PIN/UV protocols 1 and 2.
Their salts are already hashed by the browser, so the PRF uses the same stored
secret and outputs as Keyguard's other providers. Creation saves the credential
before answering the browser. Remembered-device pairing and custom relays are
not implemented.

Tests cover malformed inputs, independent Noise/AES-GCM vectors, an exchange with
an independent JCA browser, typed response cleanup, shared presentation behavior,
and application vault policy. Real phone/browser interoperability and BLE
advertising still require physical-device validation. Additional platform UIs and
Bluetooth adapters are not implemented.
