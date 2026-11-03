# Windows plugin ABI verification

The headers in `headers/` are unmodified files from
[microsoft/webauthn commit ef82c157125a0490e05f6ea82a7adb1b8e1bad08](https://github.com/microsoft/webauthn/tree/ef82c157125a0490e05f6ea82a7adb1b8e1bad08),
under the included MIT license. `headers.sha256` pins their bytes, including
`webauthn.h`, which defines the structures embedded by the plugin headers.
The local `.gitattributes` preserves their bytes on Windows checkouts.

From the repository root, with Visual Studio C++ tools and Windows SDK
**10.0.26100.0** installed:

```powershell
./util/windows-passkeys/abi/check.ps1 -Architecture x64
./util/windows-passkeys/abi/check.ps1 -Architecture arm64
```

Run the ARM64 command on ARM64 Windows. `-CompileOnly` checks the C++ declarations
without executing a cross-compiled probe. The script logs the MSVC toolset used;
the initial x64 verification used MSVC 14.44.35207. CI uses the same SDK and
independently regenerates the assertions on x64 and ARM64, requiring both to
produce the same assertions.

`probe.cpp` reads sizes, alignments and offsets from the pinned headers. It also
checks export and COM method signatures; `iid.cpp` obtains the UUID from MIDL's
declaration. The probe uses the C pointer spellings of `REFCLSID` and `REFGUID`:
C++ `offsetof` cannot measure reference members' storage reliably. Rust's opaque
allocation pointers and read-only views intentionally differ in pointee type or
mutability without changing their C representation.

The generated `abi_layout.rs` contains compile-time assertions, including
signature checks against the aliases used by the loader. The script checks every
loader name/type pair against the probe. Decoder structures are prefixes: their
size is checked against the first omitted field, not the full Microsoft type.
Encoder fixtures are checked whenever the Rust test target is compiled, even
when the integration tests are ignored. Ordinary builds need no C++ compiler
for these assertions and download no headers.

To update the pin, review all three headers together, replace their checksums,
update the probe and provenance comments, and run `check.ps1 -Update` on x64.
Review the generated diff and verify it independently on ARM64. Keep required
exports distinct from the optional status callback pair. Pinning verifies our
declarations; it does not validate arbitrary future DLL implementations.
