# Launch at login

Independent KMP/Rust utility for registration of installed desktop entry points.
The public API performs blocking OS calls; application adapters should call it on
an IO dispatcher. Status queries never create or repair registrations; the one
exception is Flatpak's first status read, described below. Only an explicit `setEnabled`
call changes OS registration. `STALE` means the entry launches
a missing executable, or this executable with other arguments. An entry that
launches another existing install is `ENABLED`, and the app leaves it alone. An
executable that cannot be inspected, for example behind an unreadable directory,
is `UNKNOWN`.

| Target | Backend | Status limitations |
| --- | --- | --- |
| `MacOSMainApp` | `SMAppService.mainAppService` | macOS 13+; actual bundle/signing and approval rules apply. A never-registered app can report `NotFound`, read as `DISABLED`; a failed status lookup reports it too, so disabling still unregisters. A registration whose immediate status read still shows the app unregistered is a system error. |
| `WindowsStartup`, packaged | WinRT `StartupTask` (`taskId`) | Used when the process has package identity. Requires a declared disabled-by-default task. Query and enable require Windows build 19041+ for startup arguments; disable remains available on older supported versions. User disabling is preserved. `ENABLED_BY_POLICY` and `DISABLED_BY_POLICY` are distinct read-only states; `setEnabled` returns them without attempting a change. |
| `WindowsStartup`, unpackaged | Per-user Startup folder shortcut | Reads, and never writes, the shortcut's `StartupApproved\StartupFolder` value: an odd first byte (disabled in Task Manager or Settings) reports `DISABLED_BY_USER`, checked before staleness. |
| `XdgAutostart` | Owned per-user XDG desktop entry | Reports `Hidden` and GNOME-disabled entries as `DISABLED`, since enabling rewrites them. Use the persistent AppImage path, never its mounted executable. Refuses a launcher path that contains `%`: desktop environments look it up without undoing the `%%` escape. |
| `FlatpakBackground` | XDG Background portal | Waits for the actual Response, including cancellation/denial. The portal has no status query, so a granted request writes a marker at the sandbox's `$XDG_CONFIG_HOME/keyguard/launch-at-login`. Status reads the marker. The first operation in a process repeats the request only when that operation is a status read and the marker exists; a denial deletes the marker. The portal reports "Don't allow" and a revoked permission as cancelled with `background=false`; both count as a denial. A success with `autostart=false` means the portal failed to save the autostart file: enabling reports a system error, and the marker stays. |

Launch commands are argument arrays, not shell strings. The app resolves a durable
installed launcher and passes the automatic-start intent. The utility does not
decide whether a window or tray item is shown.

The MSIX version gate uses `AnalyticsInfo.DeviceFamilyVersion`, independent of
application compatibility manifests. Builds below 19041 report `UNAVAILABLE`
instead of enabling a task that would ignore the `uap10:Parameters` login intent.

On JVM macOS, call `prepareLaunchDetection()` before starting the AppKit/AWT event
loop. A process-lifetime notification observer samples the launch AppleEvent while
it is being dispatched. `launchedAtLogin()` reads a positive cached result; native
macOS can also sample it directly during launch dispatch. Registration is not
evidence that the current launch came from login.

Tests use temporary XDG roots and invalid ABI requests. They never register the
developer's application or edit the real Startup folder, login items, or portal
permissions. Package-level login/logout, approval/revocation, moving/upgrading,
and uninstall behavior still require the corresponding signed/package fixtures
on each operating system. Cross-compilation alone is not package validation.

[Test fixtures](tests/README.md) cover backend failures, stale entries, policy
dispatch, native COM persistence, and D-Bus response races. The guarded registration
helper is reserved for disposable OS package/session acceptance tests.
