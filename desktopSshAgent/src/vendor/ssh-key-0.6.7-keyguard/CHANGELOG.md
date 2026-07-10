# Changelog
All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## 0.6.7 (2024-10-15)
### Fixed
- Parsing `AuthorizedKeys` with whitespace in comments ([#289])
- `mpint` decoding in ECDSA signatures ([#290], [#291])

[#289]: https://github.com/RustCrypto/SSH/pull/289
[#290]: https://github.com/RustCrypto/SSH/pull/290
[#291]: https://github.com/RustCrypto/SSH/pull/291

## 0.6.6 (2024-04-11)
### Added
- impl `decode_as` for `KeypairData` ([#211])

### Changed
- clarify SSH vs OpenSSH formats ([#206])

### Fixed
- fix `certificate::OptionsMap` encoding ([#207])
- fixup `EcdsaPrivateKey` Debug impl ([#210])

[#206]: https://github.com/RustCrypto/SSH/pull/206
[#207]: https://github.com/RustCrypto/SSH/pull/207
[#210]: https://github.com/RustCrypto/SSH/pull/210
[#211]: https://github.com/RustCrypto/SSH/pull/211

## 0.6.5 (2024-03-12)
### Added
- `Sk*` constructors ([#201], [#204])

### Changed
- Simplify DSA signature encoding ([#193])

### Fixed
- Correct erroneous signature constants ([#202])

[#193]: https://github.com/RustCrypto/SSH/pull/193
[#201]: https://github.com/RustCrypto/SSH/pull/201
[#202]: https://github.com/RustCrypto/SSH/pull/202
[#204]: https://github.com/RustCrypto/SSH/pull/204

## 0.6.4 (2024-01-11)
### Added
- `Algorithm::Other` signature support ([#189])

### Fixed
- Add newline to `PublicKey::write_openssh_file` output ([#188])
- `DsaKeypair::try_sign` format error ([#191])

[#188]: https://github.com/RustCrypto/SSH/pull/188
[#189]: https://github.com/RustCrypto/SSH/pull/189
[#191]: https://github.com/RustCrypto/SSH/pull/191

## 0.6.3 (2023-11-20)
### Added
- `SkEcdsaSha2NistP256` signature validation ([#169])
- `p521` feature ([#180])

### Changed
- Maximum certificate timestamp time is now `i64::MAX` ([#175])

### Fixed
- Handle leading zeroes in `Mpint::from_positive_bytes` ([#171])

[#169]: https://github.com/RustCrypto/SSH/pull/169
[#171]: https://github.com/RustCrypto/SSH/pull/171
[#175]: https://github.com/RustCrypto/SSH/pull/175
[#180]: https://github.com/RustCrypto/SSH/pull/180

## 0.6.2 (2023-10-15)
### Added
- `SshSig` usage examples ([#166], [#167])

[#166]: https://github.com/RustCrypto/SSH/pull/166
[#167]: https://github.com/RustCrypto/SSH/pull/167

## 0.6.1 (2023-08-15)
### Fixed
- `minimal-versions` correctness for `sec1` dependency ([#154])

[#154]: https://github.com/RustCrypto/SSH/pull/154

## 0.6.0 (2023-08-13)
### Added
- Partial support for U2F signature verification ([#44])
- Support for `aes256-gcm@openssh.com` encryption ([#75])
- "randomart" public key fingerprint visualizations ([#77])
- `PrivateKey::encrypt_with_cipher` ([#79])
- Propagate `ssh_key::Error` through `signature::Error` ([#82])
- `crypto` feature ([#83])
- Support for AES-CBC, ChaCha20Poly1305, and TDES encryption ([#118])
- Basic support for nonstandard SSH key algorithms ([#136])
- Impl `Hash` for `PublicKey` and its parts ([#145], [#149])

### Changed
- Bump `signature` crate dependency to v2 ([#58])
- Use `ssh_key::Error` as error type for `TryFrom<&[u8]>` impl on `Signature` ([#59])
- Bump elliptic curve and password hash deps; MSRV 1.65 ([#66])
  - `bcrypt-pbkdf` v0.10
  - `dsa` v0.6
  - `p256` v0.13
  - `p384` v0.13
  - `sec1` v0.7
- Use `&mut impl CryptoRngCore` for RNGs ([#67])
- Make `certificate::Builder::new` fallible ([#71])
- Rename `MPInt` => `Mpint` ([#76])
- Split `AlgorithmUnknown` and `AlgorithmUnsupported` ([#81])
- Bump `rsa` dependency to v0.9 ([#107])
- Extract symmetric encryption into `ssh-cipher` crate ([#125])
- Bump `ed25519-dalek` dependency to v2 ([#146])
- Bump `ssh-encoding` dependency to v0.2 ([#147])

### Fixed
- DSA signature encoding ([#115])
- `certificate::Builder::new_with_validity_times` ([#143])

[#44]: https://github.com/RustCrypto/SSH/pull/44
[#58]: https://github.com/RustCrypto/SSH/pull/58
[#59]: https://github.com/RustCrypto/SSH/pull/59
[#66]: https://github.com/RustCrypto/SSH/pull/66
[#67]: https://github.com/RustCrypto/SSH/pull/67
[#71]: https://github.com/RustCrypto/SSH/pull/71
[#75]: https://github.com/RustCrypto/SSH/pull/75
[#76]: https://github.com/RustCrypto/SSH/pull/76
[#77]: https://github.com/RustCrypto/SSH/pull/77
[#79]: https://github.com/RustCrypto/SSH/pull/79
[#81]: https://github.com/RustCrypto/SSH/pull/81
[#82]: https://github.com/RustCrypto/SSH/pull/82
[#83]: https://github.com/RustCrypto/SSH/pull/83
[#107]: https://github.com/RustCrypto/SSH/pull/107
[#115]: https://github.com/RustCrypto/SSH/pull/115
[#118]: https://github.com/RustCrypto/SSH/pull/118
[#125]: https://github.com/RustCrypto/SSH/pull/125
[#136]: https://github.com/RustCrypto/SSH/pull/136
[#143]: https://github.com/RustCrypto/SSH/pull/143
[#145]: https://github.com/RustCrypto/SSH/pull/145
[#146]: https://github.com/RustCrypto/SSH/pull/146
[#147]: https://github.com/RustCrypto/SSH/pull/147
[#149]: https://github.com/RustCrypto/SSH/pull/149

## 0.5.1 (2022-10-25)
### Changed
- README.md improvements ([#41])

[#41]: https://github.com/RustCrypto/SSH/pull/41

## 0.5.0 (2022-10-25)
### Added
- `p384` feature ([#21])
- `dsa` feature ([#22], [#23])
- "[sshsig]" support ([#28])

### Changed
- Bump `p256` to v0.11 ([#10])
- Bump MSRV to 1.60 ([#16])
- Bump `rsa` to v0.7 ([#20])
- Use `ssh-encoding` encoding crate ([#29], [#37])

### Removed
- `fingerprint` feature removed, now always-on ([#27])

[#10]: https://github.com/RustCrypto/SSH/pull/10
[#16]: https://github.com/RustCrypto/SSH/pull/16
[#20]: https://github.com/RustCrypto/SSH/pull/20
[#21]: https://github.com/RustCrypto/SSH/pull/21
[#22]: https://github.com/RustCrypto/SSH/pull/22
[#23]: https://github.com/RustCrypto/SSH/pull/23
[#27]: https://github.com/RustCrypto/SSH/pull/27
[#28]: https://github.com/RustCrypto/SSH/pull/28
[#29]: https://github.com/RustCrypto/SSH/pull/29
[#37]: https://github.com/RustCrypto/SSH/pull/37
[sshsig]: https://cvsweb.openbsd.org/src/usr.bin/ssh/PROTOCOL.sshsig?annotate=HEAD

## 0.4.3 (2022-09-25)
### Changed
- Move source code repository to <https://github.com/RustCrypto/SSH> ([#1])

[#1]: https://github.com/RustCrypto/SSH/pull/1

## 0.4.2 (2022-05-02)
### Added
- Support for parsing keys out of the ssh known_hosts file format
- Export `RsaPrivateKey`
- `From` conversions between algorithmic-specific key types and `PublicKey`/`PrivateKey`

## 0.4.1 (2022-04-26)
### Added
- Internal `UnixTime` helper type

### Changed
- Bump `pem-rfc7468` dependency to v0.6.0
- Further restrict maximum allowed timestamps

## 0.4.0 (2022-04-12)
### Added
- Private key decryption support
- Private key encryption support
- Ed25519 keygen/sign/verify support using `ed25519-dalek`
- Private key encryption
- Certificate decoder
- Certificate encoder
- Certificate validation support
- FIDO/U2F (`sk-*`) certificate and key support
- `certificate::Builder` (i.e. SSH CA support)
- ECDSA/NIST P-256 keygen/sign/verify support using `p256` crate
- RSA keygen/sign/verify support using `rsa` crate
- SHA-512 fingerprint support
- `serde` support

### Changed
- Consolidate `KdfAlg` and `KdfOpts` into `Kdf`
- Rename `CipherAlg` => `Cipher`

### Removed
- `PrivateKey::kdf_alg`

## 0.3.0 (2022-03-16)
### Added
- `FromStr` impls for key types
- `PublicKey` encoder
- `AuthorizedKeys` parser
- `PrivateKey::public_key` and `From` conversions
- `PrivateKey` encoder
- Validate private key padding bytes
- File I/O methods for `PrivateKey` and `PublicKey`
- SHA-256 fingerprint support

### Changed
- Use `pem-rfc7468` for private key PEM parser
- Make `PublicKey`/`PrivateKey` fields private

## 0.2.0 (2021-12-29)
### Added
- OpenSSH private key decoder
- `MPInt::as_positive_bytes`

### Changed
- `MPInt` validates the correct number of leading zeroes are used

## 0.1.0 (2021-12-02)
- Initial release
