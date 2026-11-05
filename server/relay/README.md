# Keyguard relay

The relay helps Keyguard devices sync with each other. It does three things:

- Puts linked devices of one sync group in a WebSocket room, so they can find each other and exchange WebRTC offers, answers and ICE candidates.
- Forwards encrypted frames between two devices when WebRTC can't connect them.
- Hands out STUN URLs and short-lived TURN credentials for a TURN server such as coturn.

It keeps everything in memory. A restart drops all rooms, and devices reconnect.

## What the relay sees

- Room ids. A room id is an HMAC of the sync group's key, so the relay can't tell which group or account it belongs to.
- Peer ids. Devices pick a random one per connection.
- Client IP addresses. The relay uses them for rate limits and never logs them.
- When frames arrive and how big they are. Devices pad sync messages to size steps, so a size shows only a range.

Devices encrypt frame contents end to end, so the relay can't read them. It never sees items, passwords, keys or device names.

coturn sees the IP addresses of devices that use TURN and logs them.

## Self-hosting with Docker

`docker-compose.yml` runs the relay and coturn. coturn uses host networking, which needs Docker on Linux.

1. Point DNS records at the host, for example `relay.example.com` for the relay and `turn.example.com` for coturn. One name for both works too.
2. Create `.env` next to `docker-compose.yml`:

   ```sh
   TURN_REALM=turn.example.com
   # openssl rand -hex 32
   TURN_SECRET=replace-with-a-long-random-value
   STUN_URLS=stun:turn.example.com:3478
   TURN_URLS=turn:turn.example.com:3478?transport=udp,turn:turn.example.com:3478?transport=tcp
   # Optional: makes the relay private.
   RELAY_TOKEN=
   # The relay is behind a proxy on this host, see below.
   TRUST_PROXY=172.16.0.0/12
   ```

3. Start it:

   ```sh
   docker compose up -d --build
   ```

4. Put a TLS reverse proxy in front of `127.0.0.1:7400`, see below.
5. Open these ports in the firewall:

   | Port | Protocol | Used by |
   |---|---|---|
   | 443 | TCP | reverse proxy |
   | 3478 | UDP and TCP | coturn (STUN and TURN) |
   | 49152-49999 | UDP | coturn relay ports |
   | 5349 | TCP | coturn TLS, only if enabled |

6. Check it:

   ```sh
   curl https://relay.example.com/healthz
   curl -X POST https://relay.example.com/v1/ice-servers
   ```

Devices connect to `wss://relay.example.com/v1/ws` and fetch ICE servers from `https://relay.example.com/v1/ice-servers`.

To run the relay alone, without TURN:

```sh
docker build -t keyguard-relay .
docker run -d --name keyguard-relay -p 127.0.0.1:7400:7400 \
  -e STUN_URLS=stun:stun.example.com:3478 keyguard-relay
```

The image holds one static binary on `distroless/static` and runs as a non-root user. `docker build --platform linux/amd64` or `linux/arm64` picks the architecture.

To build from source, run `cargo run --release` in this directory.

## Environment variables

An invalid value stops the relay with exit code 2 before it listens. Error messages name the variable, never the value. Empty values count as unset.

| Variable | Default | Description |
|---|---|---|
| `RELAY_BIND` | `0.0.0.0:7400` | Listen address. |
| `RELAY_METRICS_BIND` | unset | Separate Prometheus listener, disabled when unset. No authentication: use loopback or a protected network. With Compose, set `0.0.0.0:7401`; the host port is bound to loopback. |
| `RELAY_TOKEN` | unset | Shared token for `/v1/ws` and `/v1/ice-servers`, at least 16 characters from `A-Z a-z 0-9 - . _ ~`. Unset makes the relay public. |
| `RELAY_MAX_ROOM_PEERS` | `16` | Peers per room. |
| `RELAY_MSGS_PER_SEC` | `50` | Messages per second per connection. |
| `RELAY_BYTES_PER_SEC` | `2097152` | Bytes per second per connection. At least `65536`, one message of the largest size. |
| `RELAY_MAX_CONNS_PER_IP` | `32` | WebSocket connections per client address. HTTP connections that aren't WebSockets yet count separately against the same number. |
| `RELAY_MAX_CONNS` | `10000` | WebSocket connections on the relay. HTTP connections that aren't WebSockets yet count separately against the same number. |
| `RELAY_EGRESS_BYTES_PER_SEC` | `52428800` | Bytes per second the relay forwards in `msg` and binary frames, across all connections. Shared fairly between client addresses. At least `65536`. |
| `RELAY_IDLE_SECS` | `60` | Closes a socket that sent nothing, not even a pong, for this long. |
| `RELAY_MAX_ROOMS` | `10000` | Rooms on the relay. |
| `ICE_REQUESTS_PER_MINUTE` | `10` | TURN credentials per minute per client address. |
| `ICE_TICKETLESS_PER_MINUTE` | `1` | Of those, credentials per minute for requests without a live ticket. `0` gives them STUN only. |
| `ICE_TOTAL_PER_MINUTE` | `300` | TURN credentials per minute on the whole relay, at least `2`. Requests without a live ticket can't take the last half. |
| `STUN_URLS` | unset | Comma-separated `stun:` or `stuns:` URLs. |
| `TURN_URLS` | unset | Comma-separated `turn:` or `turns:` URLs. Needs `TURN_SECRET`. |
| `TURN_SECRET` | unset | coturn's `static-auth-secret`, 16 to 256 characters from `A-Z a-z 0-9 + / = - . _ ~`, without whitespace. Needs `TURN_URLS`. |
| `TURN_TTL_SECONDS` | `43200` | TURN credential lifetime, 60 to 86400. A connection relayed through TURN lasts until its credential expires. |
| `TRUST_PROXY` | unset | Comma-separated proxy addresses or CIDR ranges. See below. |
| `RUST_LOG` | `info` | Log filter, for example `debug` or `keyguard_relay=debug`. |

`docker-compose.yml` passes every variable above to the relay except `RELAY_BIND`, which must match its port mapping. It also reads `TURN_REALM` (coturn's realm, usually the TURN host name) and the optional `TURN_EXTERNAL_IP` (see coturn below).

A client address is an IPv4 address or an IPv6 /64.

ICE URLs are `scheme:host[:port][?transport=...]`, as WebRTC reads them:

- The scheme is lowercase.
- The host is a DNS name, an IPv4 address or a bracketed IPv6 address, like `[2001:db8::1]`.
- The port is 1 to 65535, without leading zeros.
- `turn:` takes `?transport=udp` or `?transport=tcp`, and `turns:` takes `?transport=tcp`. STUN URLs take no query.

## Reverse proxy and TLS

The relay speaks plain HTTP. Terminate TLS at a reverse proxy and forward WebSocket upgrades. With Caddy:

```
relay.example.com {
    reverse_proxy 127.0.0.1:7400
}
```

With nginx:

```nginx
location / {
    proxy_pass http://127.0.0.1:7400;
    proxy_http_version 1.1;
    proxy_set_header Upgrade $http_upgrade;
    proxy_set_header Connection "upgrade";
    proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
}
```

Behind a proxy, every request comes from the proxy's address. Set `TRUST_PROXY` to that address, and the relay reads `X-Forwarded-For` from the right: the first entry that isn't a `TRUST_PROXY` address is the client. Behind a chain of proxies, such as a CDN in front of nginx, list every hop. It ignores the header on connections from other addresses. A request from the proxy without a usable entry counts under the proxy's own address, and the relay logs a warning the first time. Through the Docker port mapping, the proxy connects from the Compose network gateway. With Docker's default address pools it is inside `172.16.0.0/12`, and `docker network inspect keyguard-relay_default` shows it. The port is bound to loopback, so only processes on the host can reach it.

With `RELAY_TOKEN` set, clients send `Authorization: Bearer <token>` or `?token=<token>`. Keep query strings out of the proxy's access logs.

To enable Prometheus metrics with Compose, add `RELAY_METRICS_BIND=0.0.0.0:7401` to `.env` and recreate the relay. Scrape `http://127.0.0.1:7401/metrics` from the host. Outside Docker, use `RELAY_METRICS_BIND=127.0.0.1:7401`. Unset or empty disables the relay exporter. coturn's exporter listens on `127.0.0.1:9641` with the supplied template.

The metrics listener serves only `/metrics`. The public relay port never serves it. It has no authentication and does not use `RELAY_TOKEN`. Keep its port out of the public reverse proxy. For a remote scraper, use a private network or VPN and restrict access to the scraper. If access crosses the public internet, put an HTTPS proxy with a separate monitoring credential or mTLS in front of the exporter. Do not reuse the client relay token. A scraper in another container must use a private container network or a protected host address; its own loopback does not reach the relay host.

For Prometheus running on the host:

```yaml
scrape_configs:
  - job_name: keyguard-relay
    static_configs:
      - targets: ['127.0.0.1:7401']
  - job_name: keyguard-turn
    static_configs:
      - targets: ['127.0.0.1:9641']
```

Relay metrics have the `keyguard_relay_` prefix:

| Metric | Meaning |
|---|---|
| `rooms`, `joined_peers`, `websocket_connections`, `http_connections` | Current activity. WebSockets include pending upgrades; HTTP counts only connections on the public listener before upgrade. |
| `uptime_seconds`, `build_info` | Process uptime and package version. |
| `websocket_received_frames_total`, `websocket_received_bytes_total` | Text and binary messages read, including invalid and rate-limited messages. Messages rejected inside the WebSocket codec cannot be counted. |
| `websocket_sent_frames_total`, `websocket_sent_bytes_total` | Text and binary messages successfully written, once per recipient. Includes replies and presence messages. Queued, discarded and failed writes do not count. |
| `connection_refusals_total`, `auth_rejections_total`, `protocol_errors_total` | Refusals and errors, with fixed transport or reason labels. |
| `rate_limits_total` | Client frames first delayed by the connection or egress budget, and refused new rooms. Retries do not count again. |
| `websocket_closes_total`, `websocket_send_failures_total` | Idle, slow-consumer and shutdown closes; failed or timed-out sends. |
| `turn_credentials_total` | Credentials issued or withheld by allowance, split by presence of a live ticket. Issuance does not imply TURN use or delivery of the HTTP response. |

Counters reset on restart. Prometheus retains history and handles resets with `rate()` and `increase()`. For example, `rate(keyguard_relay_websocket_sent_bytes_total[5m])` gives outgoing message bytes per second by format. These are application message bytes, without WebSocket, TLS or network overhead, and successful writes do not prove delivery to the peer. Direct WebRTC traffic bypasses the relay; measure TURN traffic with coturn. Labels never include addresses, room ids, peer ids, tickets, credentials or device names. Leave coturn's username labels disabled.

The metrics listener has a separate cap of 4 HTTP connections per client address and 16 total. It shares HTTP header deadlines and the shutdown grace period with the public listener. Monitoring connections do not consume public relay slots.

## coturn

`coturn/turnserver.conf.template` is the coturn configuration. Compose replaces `${TURN_SECRET}` and `${TURN_REALM}` when the container starts, adds a `denied-peer-ip` line for each of the host's own addresses and for `TURN_EXTERNAL_IP`, and writes the result to a file only coturn can read.

Both services reject a `TURN_SECRET` outside the allowed length or character set before starting. Generate one with `openssl rand -hex 32` or `openssl rand -base64 32`.

The relay mints credentials the way coturn's `use-auth-secret` mode expects them:

- `username` is `<expiry as Unix seconds>:<16 hex characters>`. The hex part is the same for every credential of one client address, so coturn's `user-quota` counts allocations per address. It is an HMAC of the address with a key the relay draws at start, so it doesn't reveal the address.
- `credential` is `base64(HMAC-SHA1(TURN_SECRET, username))`.

coturn checks the signature and the expiry, on every refresh of an allocation too: a relayed connection drops once its credential expires, and Keyguard reconnects with a fresh one. The secret never leaves the two servers. A credential can't be revoked on its own. Changing `TURN_SECRET` on both sides invalidates all of them.

The template:

- Relays UDP only, to public addresses. `denied-peer-ip` blocks private, loopback, link-local, multicast and documentation ranges and the host's own addresses, so TURN can't reach the host or its network. Devices on one LAN connect directly and don't need TURN.
- Limits each client address to 32 allocations and the server to 800, and each allocation to 2.5 MB/s. Tune `user-quota`, `total-quota` and `max-bps` to the host. coturn reserves `max-bps` for every allocation out of `bps-capacity`, so setting `bps-capacity` caps allocations at `bps-capacity / max-bps`.
- Uses ports 49152 to 49999 for relaying.
- Logs to stdout. coturn logs client IP addresses. Docker keeps 3 files of 10 MB.

If the host has a private address behind 1:1 NAT, as on AWS or GCP, set `TURN_EXTERNAL_IP` to the public address.

For `turns:` on 5349, mount a certificate into the container, set `cert` and `pkey` in the template, remove `no-tls` and add a `turns:` URL to `TURN_URLS`. Keyguard apps use `turn:` URLs over UDP only and skip `turns:` and `?transport=tcp`, so TLS only helps other WebRTC clients.

coturn 4.18 keeps its admin CLI, DTLS and RFC 5780 NAT discovery off by default. The old `no-cli` and `no-dtls` options are deprecated and log errors, so the template leaves them out.

## Limits

| Limit | Result |
|---|---|
| Message larger than 64 KiB (65,536 bytes) | `frame_too_big` error, then close 1009. Above twice the limit, the connection drops without reading the rest. |
| Over the message or byte rate | The relay stops reading the connection until the budget allows the frame. Nothing is dropped. Both budgets allow a one-second burst. Pongs don't count. |
| Room has `RELAY_MAX_ROOM_PEERS` peers | `room_full` error on join. |
| `RELAY_MAX_ROOMS` rooms exist | `rate_limited` error on a join that would create a room. |
| `RELAY_MAX_CONNS_PER_IP` connections from one address | HTTP 429 before the WebSocket upgrade. |
| `RELAY_MAX_CONNS` connections on the relay | HTTP 503 before the WebSocket upgrade. |
| `RELAY_MAX_CONNS_PER_IP` HTTP connections from one address that aren't WebSockets yet | New connections from it close without an answer. Connections from a `TRUST_PROXY` address count only toward `RELAY_MAX_CONNS`. |
| `RELAY_MAX_CONNS` HTTP connections on the relay that aren't WebSockets yet | New connections close without an answer. |
| The relay forwards more than `RELAY_EGRESS_BYTES_PER_SEC` | The relay stops reading the sender until the budget allows its `msg` or binary frame. After 5 seconds it drops the frame and sends `rate_limited`. Each copy of a broadcast counts. A broadcast may overdraw the one-second burst. |
| Less than half of `RELAY_EGRESS_BYTES_PER_SEC` is left | The same, but only for addresses above their fair share. A fair share is `RELAY_EGRESS_BYTES_PER_SEC` divided by the addresses that sent in the last second. Other addresses go on. |
| No frame and no pong for `RELAY_IDLE_SECS` | Close 1000. The relay pings every 25 seconds, or every half of the idle time if that is shorter. |
| A device doesn't read and 128 frames queue up for it | Close 1013. |
| A device doesn't read a reply for 10 seconds | The connection drops. This includes replies to WebSocket pings. |
| HTTP request headers are incomplete after 30 seconds | The connection drops. |
| An HTTP request body is incomplete 10 seconds after its headers | HTTP 408, then the connection closes. |
| Over `ICE_REQUESTS_PER_MINUTE` or `ICE_TOTAL_PER_MINUTE`, or over `ICE_TICKETLESS_PER_MINUTE` without a live ticket | `/v1/ice-servers` answers with STUN only and `"ttlSeconds":60`. |
| 10 wrong tokens from one address in a minute | HTTP 429 with `Retry-After` on `/v1/ws` and `/v1/ice-servers`, even with the right token. One more try every 6 seconds. |

Client pings and unsolicited pongs count toward both budgets. One matching reply to each server heartbeat is exempt. WebSocket pings still receive their required pong reply.

On shutdown, the relay stops accepting connections and gives HTTP requests and WebSockets two seconds to finish. WebSockets receive close 1001 when writable; remaining connections are dropped at the deadline.

## Protocol

`GET /healthz` returns `{"status":"ok"}`. It never needs the token. Room and connection counts are available only through private metrics.

`POST /v1/ice-servers` takes an optional body `{"ticket":"<32 hex>"}` with the ticket from `joined`. A ticket is live while its connection is in a room and has sent a frame in the last 5 minutes. A request with a live ticket gets the full `ICE_REQUESTS_PER_MINUTE`. Any other body counts as no ticket. The endpoint returns:

```json
{
  "iceServers": [
    { "urls": ["stun:turn.example.com:3478"] },
    {
      "urls": ["turn:turn.example.com:3478?transport=udp"],
      "username": "1767225600:0123456789abcdef",
      "credential": "base64(HMAC-SHA1(TURN_SECRET, username))"
    }
  ],
  "ttlSeconds": 43200
}
```

The STUN entry is missing without `STUN_URLS`, and the TURN entry without `TURN_URLS`. Past the TURN allowance, the TURN entry is missing too and `ttlSeconds` is `60`, so clients ask again within a minute. Errors are `401 {"error":"unauthorized"}` and, after too many wrong tokens, `429 {"error":"rate_limited"}`. A body that doesn't arrive within 10 seconds gets `408` without a body.

`GET /v1/ws` is a WebSocket. One connection is in at most one room at a time. Text frames are JSON objects with a `t` field. Unknown fields are ignored.

| Client sends | Relay answers |
|---|---|
| `{"t":"join","room":"<64 hex>","peer":"<32 hex>"}` | `{"t":"joined","peers":["<32 hex>",...],"ticket":"<32 hex>"}` with the other peers and the connection's ticket for `/v1/ice-servers`. They get `{"t":"presence","peer":"<32 hex>","state":"join"}`. |
| `{"t":"pub","to":"<32 hex>","data":"<base64>"}` | The target gets `{"t":"msg","from":"<32 hex>","data":"<base64>"}`. |
| `{"t":"pub","to":"*","data":"<base64>"}` | Every other peer in the room gets `msg`. |
| `{"t":"leave"}` | The others get `presence` with `"state":"leave"`. A closed connection leaves too. |
| `{"t":"ping"}` | `{"t":"pong"}` |

Ids are lowercase hex. `data` is standard base64 with padding. The sender never gets its own messages back. A message to a peer that isn't in the room is dropped.

Binary frames carry relay-forwarded bytes. The client sends `0x02 | target peer id (16 bytes) | payload`, and the target gets `0x02 | sender peer id (16 bytes) | payload`. The relay forwards the payload as is.

Errors are `{"t":"err","code":"<code>"}`:

| Code | Meaning | Closes |
|---|---|---|
| `unauthorized` | Missing or wrong token. | yes, 1008 |
| `bad_frame` | Malformed JSON, unknown `t`, bad id or base64, bad binary header, or a second `join`. | yes, 1008 |
| `frame_too_big` | Message larger than 64 KiB. | yes, 1009 |
| `rate_limited` | A frame waited 5 seconds for `RELAY_EGRESS_BYTES_PER_SEC` and was dropped, or the relay has `RELAY_MAX_ROOMS` rooms. | no |
| `room_full` | The room has `RELAY_MAX_ROOM_PEERS` peers. | no |
| `peer_taken` | Another connection in the room uses this peer id. | no |
| `not_joined` | A well-formed `pub`, `leave` or binary frame before `join`. | no |
