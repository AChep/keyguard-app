# WebRTC

`util/webrtc` gives Keyguard WebRTC data channels on Android, desktop, iOS and
macOS. A Rust core wraps the [`webrtc`](https://crates.io/crates/webrtc) crate
(0.21, `runtime-tokio` and `crypto-ring`, never `crypto-aws-lc-rs`) and serves
the JVM through JNI and the Apple targets through cinterop. Wear OS never
links it.

## Contract

Rust owns the WebRTC stack and its threads:

- a runtime handle owns one multi-thread tokio runtime (two workers named
  `keyguard-webrtc`). The library keeps no process-wide runtime;
- a peer handle owns one peer connection with at most one ordered, reliable
  data channel, and belongs to one runtime;
- ICE mDNS is off: no `.local` candidates are published or resolved, and no
  socket binds port 5353;
- ICE runs over UDP only: a peer gathers no TCP candidates and ignores the
  remote ones, which the stack would dial whatever the transport policy.

Kotlin owns signaling, framing, retries and policy. Rust makes no callbacks
into Kotlin and writes no logs. Calls block the calling thread:

- requests wait for the stack for at most 10 seconds;
- adding a remote candidate and closing a peer return at once; a later
  failure arrives as an `Error` event;
- events queue per peer (1024 at most, then the stack waits) and
  `peer_next_event` takes them;
- cancel = close: closing a peer, or shutting its runtime down, wakes every
  call blocked on it with the `CLOSED` failure.

## Known limitations

- rtc-sctp 0.21.1 holds the fragments of an unfinished message without a cap:
  to avoid a deadlock ([webrtc-rs#822](https://github.com/webrtc-rs/webrtc/issues/822)),
  it takes the next in-order chunk even when the receive window is full. A
  remote peer that never ends a message can grow the native memory of this
  one until it runs out. Signals are sealed per pair, so only a device that
  was admitted to the group can open a data channel: a malicious member can
  crash the app of another. No release or setting bounds it yet.
- rtc-ice 0.21.1 keeps a peer-reflexive candidate for every new source of an
  authenticated STUN request, without a cap. A peer takes datagrams from the
  addresses it sent to and from at most 64 other sources, and drops the rest.
  A source counts only after its STUN Binding request authenticates with the
  local ICE credentials, so unauthenticated datagrams take no slot. A TURN
  server is an address the peer sent to, so the sources it relays are not
  capped.

## ABI v1

Every export except `abi_version` returns an `int64_t`: a handle, a length,
an amount or zero on success, and a `keyguard-ffi` packed failure (negative)
otherwise. C names carry the `keyguard_webrtc_` prefix; the JNI class is
`com.artemchep.keyguard.util.webrtc.NativeWebrtcJni`.

| Export | Returns |
|---|---|
| `abi_version()` | `1` |
| `runtime_new()` | runtime handle, or `BUSY` while 64 runtimes are live |
| `runtime_shutdown(runtime)` | `0`; closes every peer, then stops the runtime within about 3 s; idempotent |
| `peer_new(runtime, config_json)` | peer handle, or `BUSY` while 1024 peers are live |
| `peer_create_offer(peer, out)` / `peer_create_answer(peer, out)` | SDP length; applies it as the local description; `out` ≥ 64 KiB |
| `peer_set_remote(peer, kind, sdp)` | `0`; `kind` 0 = offer, 1 = answer; `INVALID_STATE` when its candidates take the peer past 256 |
| `peer_add_ice_candidate(peer, candidate_json)` | `0`, or `INVALID_STATE` past 256 per peer, counting the candidates inline in remote descriptions; held until the remote description is set; empty `candidate` and TCP candidates are ignored |
| `peer_create_channel(peer, label)` | `0`; offerer only, before the offer |
| `channel_send(peer, bytes)` | `0`, or `BUSY` while the message would take the buffered amount over 1 MiB; ≤ 64 KiB per message |
| `channel_buffered_amount(peer)` | bytes not yet acknowledged |
| `peer_next_event(peer, out, timeout_ms)` | event length, `0` on timeout, `CLOSED` once closed; `out` ≥ 65537 bytes |
| `peer_close(peer)` | `0`; idempotent |

The configuration is JSON; every field is optional and unknown fields are
ignored:

```json
{
  "iceServers": [{"urls": ["turn:relay.example:3478"], "username": "u", "credential": "c"}],
  "iceTransportPolicy": "all",
  "bindAddresses": ["0.0.0.0:0", "[::]:0"]
}
```

An event is one kind byte and its payload:

| Kind | Event | Payload |
|---|---|---|
| 1 | `IceCandidate` | JSON `{"candidate","sdpMid","sdpMLineIndex"}` |
| 2 | `IceGatheringComplete` | none |
| 3 | `ConnectionState` | one byte: 0 unknown, 1 new, 2 connecting, 3 connected, 4 disconnected, 5 failed, 6 closed |
| 4 | `ChannelOpen` | none |
| 5 | `ChannelMessage` | the message |
| 6 | `ChannelClose` | none |
| 7 | `BufferedLow` | none; the buffered amount fell to 256 KiB |
| 8 | `SelectedPair` | JSON `{"localType","remoteType","protocol"}` |
| 9 | `Error` | `u32` little-endian: 1 remote candidate rejected, 2 STUN/TURN server failed, 3 channel error |

Failures use the bridge domain (3) and these codes: 1 invalid argument, 2
panic, 3 internal, 4 input too long, 5 invalid handle, 6 closed, 7 busy, 8
timeout, 9 invalid state, 10 stack. `include/keyguard_webrtc.h` has the
limits and the failure kinds.

## Kotlin

`WebRtcRuntime.create()`, `runtime.createPeer(WebRtcConfig)` and
`runtime.shutdown()`; `WebRtcPeer` has `createChannel`, `createOffer`,
`createAnswer`, `setRemoteDescription`, `addIceCandidate`, `send`,
`bufferedAmount`, `awaitBufferedAtMost`, `events()` and `close()`. Collect
`events()` once per peer: it polls on `Dispatchers.IO`, completes when the
peer closes, and cancelling its collector closes the peer. `send` suspends
while the channel is busy, until the next `BufferedLow`. Candidates stay the
core's JSON: send `IceCandidate.json` as is, and the other peer's core parses
it in `addIceCandidate`.

## Validation

Run from the repository root:

```sh
cargo fmt -p keyguard-webrtc-core -p keyguard-webrtc-jni -p keyguard-webrtc-c -- --check
cargo clippy -p keyguard-webrtc-core -p keyguard-webrtc-jni -p keyguard-webrtc-c --all-targets --all-features --locked --no-deps -- -D warnings
cargo test -p keyguard-webrtc-core -p keyguard-webrtc-jni -p keyguard-webrtc-c --all-features --locked
./gradlew :util:webrtc:desktopTest :util:webrtc:compileNativeWebrtcAndroidAll
./gradlew --max-workers=1 :util:webrtc:macosArm64Test :util:webrtc:iosSimulatorArm64Test
./gradlew :util:webrtc:detekt :util:webrtc:detektCustomRules
```

The loopback tests bind 127.0.0.1 and need no network access.
