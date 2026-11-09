# feature/p2p-webrtc

The WebRTC transport of device-to-device sync: `WebRtcTransport` opens a data channel
to another device of the group, over `util/webrtc`, signaled through the protocol's
`Signaling`.

The phone, desktop and Apple roots bind it. `common` never depends on it, and Wear OS
never links it.

## Connecting

Both devices call `connect` for each other. The one with the lexicographically smaller
device id makes the offer, whatever the `ConnectRole`:

1. It creates the data channel `kg`, sends the offer, and repeats it every 2 seconds
   until the answer arrives. It holds its ICE candidates until then.
2. The other device waits up to 20 seconds for an offer, and creates its peer only once
   one arrives. It answers the first offer, and again whenever that offer repeats, then
   trickles its candidates.
3. `connect` returns once the channel opens, or throws `WebRtcTransportException` after
   20 seconds, or as soon as ICE fails.

So a device may start listening late, and no signal is lost to a listener that is not
there yet. Each attempt has a random session id; signals of other attempts, and payloads
that are not WebRTC signals, are ignored.

A signal is the JSON `{t: "webrtc", v: 1, kind: offer|answer|candidate, sid, sdp?, candidate?}`;
the application's `Signaling` encrypts it.

## Streams

The channel is ordered and reliable. A message is at most 64 KiB. `awaitWritable` waits
while more than 512 KiB is buffered. Closing either end, or ICE failing, ends both reads.

The `onSelectedPath` callback reports the selected candidate pair as a `TransportPath`:
`HOST` (same network), `SRFLX` (through STUN) or `RELAY` (through TURN).

## Sessions

`WebRtcTransportFactory` is the protocol's `TransportFactory`: the application roots
that link WebRTC hand it to `common`, which races its transport against relay
forwarding and gives it a head start. Each session gets one `WebRtcTransport` whose runtime starts with the
first connection and shuts down when the session's scope ends. Without
`allowLocalNetwork`, connections go through TURN only.

## Settings

`WebRtcTransportConfig` sets the ICE transport policy (`RELAY` forces TURN, for a "relay
only" test mode), the local bind addresses, the connect timeout and the offer interval.
The ICE servers come from an injected `suspend () -> List<IceServer>`, asked once per
connection; a connection that waits 10 seconds for them goes without them.

## Validation

```sh
./gradlew :feature:p2p-webrtc:desktopTest :feature:p2p-webrtc:checkComposeFree
./gradlew :feature:p2p-webrtc:detekt
./gradlew :feature:p2p-webrtc:detektCustomRules
./gradlew --max-workers=1 :feature:p2p-webrtc:macosArm64Test
```

The loopback tests bind 127.0.0.1 and need no network access.
