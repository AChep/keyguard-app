# Native crypto policy

`native-crypto-policy` validates the repository's structured RustSec exception
policy. It checks that `.github/native-crypto-exceptions.toml` contains complete,
unique, unexpired exceptions and that its advisory set exactly matches the
RustSec advisories ignored by `.github/native-crypto-deny.toml`.

Run it from the repository root with:

```sh
cargo run --manifest-path util/crypto/tools/native-crypto-policy/Cargo.toml --locked -- \
  --repository .
```

CI may also pass `--require-external-tracking-issue` to require every exception
to link to an external issue. `--today YYYY-MM-DD` is available for deterministic
policy tests.
