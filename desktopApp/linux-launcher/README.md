# Linux app launcher

The `bin/Keyguard` launcher of the Linux app image, rebuilt with the fix for
[JDK-8380085](https://bugs.openjdk.org/browse/JDK-8380085).

The launcher forks, and the child sends the launch config (mostly the
classpath, ~15 KB) to the parent over a pipe. The parent reads it with a single
`read()`. Once the user exceeds `fs.pipe-user-pages-soft`, new pipes hold only
8 KB: the read comes back short and the launcher crashes in `setenv()`
([#1475](https://github.com/AChep/keyguard-app/issues/1475),
[#1694](https://github.com/AChep/keyguard-app/issues/1694)).

## Origin

- Sources: [JetBrains/JetBrainsRuntime](https://github.com/JetBrains/JetBrainsRuntime),
  tag `jbr-release-21.0.11b1163.125` (commit `a166c74c11a0`), the runtime we
  bundle. Only the files of the launcher executable, at their original paths.
  The executable must match the bundled `lib/libapplauncher.so`; newer
  jdk21u sources change that interface (JDK-8384468).
- Change: `JDK-8380085.patch`, the upstream fix
  ([openjdk/jdk#30254](https://github.com/openjdk/jdk/pull/30254), commit
  `54c5e41cbaab`), applied to `LinuxLauncher.c` unchanged. It loops `read()`
  and `write()` until the whole buffer is transferred.
- `LICENSE`: the JDK license, GPLv2 with the Classpath exception.

`build.sh <jdk-include-dir> <output-file>` builds it with the host `cc`
(override with `CC`). `:desktopApp:compileLinuxLauncher` runs it, and the app
image tasks copy the result over `bin/Keyguard`.

## License

These are modified GPLv2 + Classpath exception files. The launcher binary
ships in our Linux packages, so its source must be available: this directory
is that source.

## Removal

Delete this directory and the `compileLinuxLauncher` setup in
`desktopApp/build.gradle.kts` once the bundled JetBrains Runtime includes the
JDK-8380085 fix. Upstream it ships in JDK 27; there is no 21 or 25 backport
yet.
