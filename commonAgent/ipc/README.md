# keyguard-agent-ipc

The helper side of the private channel between Keyguard and its agent helpers: the
client that connects to the app, plus frames, the launch token and the handshake.
The app-side server is `util/agent-ipc`, which uses this crate only in its tests.
