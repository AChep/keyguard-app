# mTLS network tests

Run the shared network contract against the production HTTP client factories:

```sh
python3 scripts/run_mtls_tests.py -- ./gradlew :common:desktopTest \
  --tests 'com.artemchep.keyguard.provider.bitwarden.transport.MutualTlsNetworkTest'
python3 scripts/run_mtls_tests.py -- ./gradlew :common:macosArm64Test \
  --tests 'com.artemchep.keyguard.provider.bitwarden.transport.MutualTlsNetworkTest'
python3 scripts/run_mtls_tests.py -- ./gradlew :common:iosSimulatorArm64Test \
  --tests 'com.artemchep.keyguard.provider.bitwarden.transport.MutualTlsNetworkTest' \
  --device '<booted simulator UUID>'
python3 scripts/run_mtls_tests.py --android -- ./gradlew :androidApp:connectedNoneDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.artemchep.keyguard.test.transport.MutualTlsNetworkTest
```

The runner needs Python 3 and OpenSSL (`OPENSSL` can select an executable). Android
also needs a connected device or emulator; `--device <ADB serial>` selects one.
The iOS simulator must be booted before running its tests.
Run one platform suite per invocation, or pass `--max-workers=1` when combining
tasks so their connection-count assertions do not overlap.

The runner creates temporary root CAs, an intermediate, RSA and P-256 client
identities, and loopback HTTPS/WebSocket servers. Android reaches them through
temporary `adb reverse` mappings. Cleanup removes the mappings and files even
when Gradle fails. No certificates are installed into system trust stores.

Assertions cover client authentication and intermediate-chain transmission,
certificate replacement/removal, missing and untrusted identities, server CA
and hostname validation, cross-origin redirect rejection, external transfers
without a client identity, and WSS exchanges on a separate notifications origin.
Timeouts fail the test; they do not count as successful rejection.

The extra sources are enabled by `-Pkeyguard.mtlsTests=true`, which the runner
supplies. Enabled tests fail if the fixture is missing. CI runs the suite in the
native macOS, Android instrumentation, and iOS simulator jobs.
The runner also fails if a successful command leaves the fixture unused.
