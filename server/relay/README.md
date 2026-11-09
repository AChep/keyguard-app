# Keyguard relay

The relay helps Keyguard devices sync with each other:

- Puts linked devices in WebSocket rooms, so they can find each other and exchange WebRTC offers, answers and ICE candidates.
- Forwards encrypted frames between two devices when WebRTC can't connect them.
- Hands out STUN URLs and short-lived TURN credentials for a TURN server such as coturn.
- Optionally stores sealed items that one device leaves for another, so devices that are never online at the same time still sync. See [Storage](#storage).

Rooms live in memory. A restart drops them, and devices reconnect. Stored items live on disk and survive restarts.

## What the relay sees

- Room ids. Devices derive them from a secret they share, so the relay can't tell which group or account a room belongs to.
- Peer ids. The relay draws a random one for each connection.
- Which rooms each connection is in. Rooms on one connection belong to one device.
- Client IP addresses. The relay uses them for rate limits and never logs them.
- When frames arrive and how big they are. Devices pad sync messages to size steps, so a size shows only a range.
- With storage on: how big stored items are, when they are written and read, and the ticket of the connection that asks. The ticket ties mailbox requests to one WebSocket connection.

Devices encrypt frame contents and stored items end to end, so the relay can't read them. It never sees vault items, passwords, keys or device names.

A relay can still stop devices from syncing, or drop stored items. On Android and desktop, a malicious relay can also send WebSocket messages large enough to make Keyguard run out of memory. Use a relay you trust.

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
   curl -f https://relay.example.com/healthz
   curl -f -X POST https://relay.example.com/v1/ice-servers
   ```

   With `RELAY_TOKEN` set, add `-H "Authorization: Bearer <token>"` to the second command.

Devices connect to `wss://relay.example.com/v1/ws` and fetch ICE servers from `https://relay.example.com/v1/ice-servers`. Compose turns [storage](#storage) on, on the `mailbox` volume.

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
| `RELAY_TOKEN` | unset | Shared token for `/v1/ws`, `/v1/ice-servers` and `/v1/mailbox`, at least 16 characters from `A-Z a-z 0-9 - . _ ~`. Unset makes the relay public. |
| `RELAY_MAX_ROOM_PEERS` | `4` | Peers per room. Keyguard rooms hold two devices; the rest leaves room for a reconnecting device. |
| `RELAY_MSGS_PER_SEC` | `50` | Messages per second per connection. |
| `RELAY_BYTES_PER_SEC` | `2097152` | Bytes per second per connection. At least `65536`, one message of the largest size, or `262144` with storage on, so a blob uploads in about a minute. Bytes read from the socket get the same budget plus 14 bytes of framing per message of `RELAY_MSGS_PER_SEC`. |
| `RELAY_MAX_CONNS_PER_IP` | `32` | WebSocket connections per client address. HTTP connections that aren't WebSockets yet count separately against the same number. |
| `RELAY_MAX_CONNS` | `10000` | WebSocket connections on the relay. HTTP connections that aren't WebSockets yet count separately against the same number. |
| `RELAY_WS_REQUESTS_PER_SEC` | `10` | WebSocket endpoint requests per second per client address, including malformed upgrades. |
| `RELAY_WS_TOTAL_PER_SEC` | `200` | WebSocket endpoint requests per second across all addresses. |
| `RELAY_ICE_REQUESTS_PER_SEC` | `20` | ICE endpoint requests per second per client address, including requests that issue no TURN credentials. |
| `RELAY_ICE_TOTAL_PER_SEC` | `500` | ICE endpoint requests per second across all addresses. |
| `RELAY_EGRESS_BYTES_PER_SEC` | `52428800` | Bytes per second the relay forwards in `msg` and binary frames, across all connections. Shared fairly between client addresses. At least `65536`. |
| `RELAY_IDLE_SECS` | `60` | Closes a socket that sent nothing, not even a pong, for this long. |
| `RELAY_MAX_ROOMS` | `10000` | Rooms on the relay. |
| `RELAY_MAX_ROOMS_PER_CONN` | `16` | Rooms one connection can be in, 1 to 256. |
| `RELAY_MAX_ROOMS_PER_IP` | `64`, twice `RELAY_MAX_CONNS_PER_IP` | Rooms the connections of one client address can be in. A room counts once, however many of them are in it. |
| `ICE_REQUESTS_PER_MINUTE` | `10` | TURN credentials per minute per client address. |
| `ICE_TICKETLESS_PER_MINUTE` | `0` | Of those, credentials per minute for requests without a live ticket. `0` gives them STUN only. |
| `ICE_TOTAL_PER_MINUTE` | `300` | TURN credentials per minute on the whole relay, at least `2`. Requests without a live ticket can't take the last half. |
| `STUN_URLS` | unset | Comma-separated `stun:` or `stuns:` URLs. |
| `TURN_URLS` | unset | Comma-separated `turn:` or `turns:` URLs. Needs `TURN_SECRET`. |
| `TURN_SECRET` | unset | coturn's `static-auth-secret`, 32 to 256 characters from `A-Z a-z 0-9 + / = - . _ ~`, without whitespace. Needs `TURN_URLS`. |
| `TURN_TTL_SECONDS` | `7200` | TURN credential lifetime, 60 to 86400. Keyguard moves a connection relayed through TURN to a new one at 90% of it. |
| `TRUST_PROXY` | unset | Comma-separated proxy addresses or CIDR ranges. See below. |
| `RELAY_MAILBOX_DIR` | unset | Data directory for [storage](#storage), an absolute path. Unset turns storage off. |
| `RELAY_MAILBOX_MAX_BYTES` | `4294967296` (4 GiB) | Stored bytes on the relay, at least `17825792`. |
| `RELAY_MAILBOX_MIN_FREE_BYTES` | `1073741824` (1 GiB) | Free space the relay leaves on the volume. |
| `RELAY_MAILBOX_MAX_ITEMS` | `100000` | Stored items on the relay. |
| `RELAY_MAILBOX_TTL_SECONDS` | `604800` (7 days) | Longest item lifetime, 3600 to 2592000. |
| `RELAY_MAILBOX_BYTES_PER_IP` | `134217728` (128 MiB) | Stored bytes per client address, at least `17825792`. |
| `RELAY_MAILBOX_ITEMS_PER_IP` | `1024` | Stored items per client address. |
| `RELAY_MAILBOX_REQUESTS_PER_MINUTE` | `120` | Mailbox requests per minute per client address. |
| `RELAY_MAILBOX_UPLOAD_BYTES_PER_MINUTE` | `67108864` (64 MiB) | Uploaded bytes per minute per client address, at least `17825792`. |
| `RUST_LOG` | `info` | Log filter, for example `debug` or `keyguard_relay=debug`. |

`docker-compose.yml` passes every variable above to the relay except `RELAY_BIND`, which must match its port mapping. It sets `RELAY_MAILBOX_DIR` to `/data` unless `.env` sets it; an empty value turns storage off. The container is read-only but for the `mailbox` volume at `/data`, so keep the directory there or under it. It also reads `TURN_REALM` (coturn's realm, usually the TURN host name) and the optional `TURN_EXTERNAL_IP` (see coturn below).

A client address is an IPv4 address or an IPv6 /64. The /64s of one IPv6 /48 share 8 times each per-address limit: connections, rooms, endpoint requests, TURN credentials, wrong tokens and storage quotas. Tunnel brokers hand out /48s for free, so one host could otherwise take a relay-wide limit alone. Egress fair shares and coturn's `user-quota` stay per /64.

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
    proxy_set_header X-Forwarded-For $remote_addr;
    # Mailbox blobs are up to 17 MiB. Pass uploads and downloads on as they
    # flow, so the relay's checks and deadlines apply and nginx stores
    # nothing on its disk.
    client_max_body_size 17m;
    proxy_request_buffering off;
    proxy_buffering off;
}
```

Behind a proxy, every request comes from the proxy's address. Set `TRUST_PROXY` to that address, and the relay reads `X-Forwarded-For` from the right: the first entry that isn't a `TRUST_PROXY` address is the client. The proxy must replace the header, not append to it: a client inside a `TRUST_PROXY` range, such as a LAN or VPN address in `172.16.0.0/12`, could otherwise put any address before its own. Caddy replaces it by default, and the nginx example sets it to `$remote_addr`. Behind a CDN in front of nginx, let nginx take the client from the CDN with `set_real_ip_from` and `real_ip_header X-Forwarded-For`, so `$remote_addr` is the client. It ignores the header on connections from other addresses. A request from the proxy without a usable entry counts under the proxy's own address, and the relay logs a warning the first time. Through the Docker port mapping, the proxy connects from the Compose network gateway. With Docker's default address pools it is inside `172.16.0.0/12`, and `docker network inspect keyguard-relay_default` shows it. The port is bound to loopback, so only processes on the host can reach it.

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
| `rooms`, `joined_peers`, `websocket_connections`, `http_connections` | Current activity. `joined_peers` counts room memberships, so a connection in two rooms counts twice. WebSockets include pending upgrades; HTTP counts only connections on the public listener before upgrade. |
| `uptime_seconds`, `build_info` | Process uptime and package version. |
| `websocket_received_frames_total`, `websocket_received_bytes_total` | Text and binary messages read, including invalid and rate-limited messages. Messages rejected inside the WebSocket codec cannot be counted. |
| `websocket_received_control_frames_total`, `websocket_received_control_bytes_total` | Ping and pong frames read, including heartbeat replies and rate-limited frames. Each frame counts once. |
| `websocket_heartbeat_replies_total` | Matching pong replies exempted from the connection budgets, at most once per heartbeat. |
| `websocket_sent_frames_total`, `websocket_sent_bytes_total` | Text and binary messages successfully written, once per recipient. Includes replies and presence messages. Queued, discarded and failed writes do not count. |
| `connection_refusals_total`, `auth_rejections_total`, `protocol_errors_total` | Refusals and errors, with fixed transport or reason labels. |
| `http_rate_limits_total` | Endpoint requests refused by per-address, per-/48 or total admission budgets, or by a full address tracker. Fixed endpoint and reason labels. |
| `rate_limits_total` | Client frames first delayed by the connection or egress budget, and joins refused by `RELAY_MAX_ROOMS` (`rooms`) or `RELAY_MAX_ROOMS_PER_IP` (`address_rooms`, or `network_rooms` for an IPv6 /48). Retries do not count again. |
| `websocket_closes_total`, `websocket_send_failures_total` | Idle, slow-consumer and shutdown closes; failed or timed-out sends. |
| `turn_credentials_total` | Credentials issued or withheld by allowance, split by presence of a live ticket. Issuance does not imply TURN use or delivery of the HTTP response. |
| `mailbox_items`, `mailbox_bytes` | Stored items and their bytes, by `kind`. These and the rows below exist only with storage. |
| `mailbox_limit_bytes`, `mailbox_available` | The current byte limit, and `1` while the data directory works. |
| `mailbox_requests_total` | Mailbox requests by `op` and `result`. Requests refused by admission count in `http_rate_limits_total` instead. |
| `mailbox_evictions_total` | Items the relay deleted: `expired`, or trimmed for disk space (`pressure`) or the item cap (`items`). |
| `mailbox_write_failures_total` | Uploads that failed to create, write, sync or rename their file. |

Counters reset on restart. Prometheus retains history and handles resets with `rate()` and `increase()`. For example, `rate(keyguard_relay_websocket_sent_bytes_total[5m])` gives outgoing message bytes per second by format. These are application message bytes, without WebSocket, TLS or network overhead, and successful writes do not prove delivery to the peer. Direct WebRTC traffic bypasses the relay; measure TURN traffic with coturn. Labels never include addresses, room ids, peer ids, item ids, tickets, credentials or device names. Leave coturn's username labels disabled.

The metrics listener has a separate cap of 4 HTTP connections per client address and 16 total. It shares HTTP header deadlines and the shutdown grace period with the public listener. Monitoring connections do not consume public relay slots.

## coturn

`coturn/turnserver.conf.template` is the coturn configuration. Compose fills in the secret, realm and limits when the container starts and writes the result to a file only coturn can read.

Both services reject a `TURN_SECRET` outside the allowed length or character set before starting. Generate one with `openssl rand -hex 32` or `openssl rand -base64 32`.

Compose accepts these coturn limits in `.env`. Invalid values stop coturn before it starts. Use integers without leading zeros.

| Variable | Default | Description |
|---|---|---|
| `TURN_USER_QUOTA` | `32` | Allocations per client address, 1 to 1000000. |
| `TURN_TOTAL_QUOTA` | `800` | Allocations across coturn, 1 to 1000000. |
| `TURN_MAX_BPS` | `2500000` | Bytes per second per allocation, 1 to 1073741824. |
| `TURN_BPS_CAPACITY` | `0` | Aggregate bandwidth reservation capacity in bytes per second, up to 1099511627776. `0` leaves it unlimited; a nonzero value must be at least `TURN_MAX_BPS`. |

The relay mints credentials the way coturn's `use-auth-secret` mode expects them:

- `username` is `<expiry as Unix seconds>:<16 hex characters>`. The hex part is the same for every credential of one client address, so coturn's `user-quota` counts allocations per address. It is an HMAC of the address with a key the relay draws at start, so it doesn't reveal the address.
- `credential` is `base64(HMAC-SHA1(TURN_SECRET, username))`.

coturn checks the signature and the expiry, on every refresh of an allocation too: a relayed connection drops once its credential expires. Keyguard reconnects with a fresh one before that. The secret never leaves the two servers. A credential can't be revoked on its own. Changing `TURN_SECRET` on both sides invalidates all of them.

The template:

- Relays UDP only, to public addresses. `denied-peer-ip` blocks private, loopback, link-local, multicast and documentation ranges, so TURN can't reach the host's private networks. Devices on one LAN connect directly and don't need TURN.
- Leaves the host's public addresses open to TURN clients over UDP, on every port: two devices that both use TURN connect through their relay ports there. Bind UDP services that the firewall hides, such as a DNS resolver, to other addresses.
- Applies the allocation and bandwidth limits above. coturn reserves `TURN_MAX_BPS` for every allocation out of `TURN_BPS_CAPACITY`, so a nonzero capacity also caps allocations at `TURN_BPS_CAPACITY / TURN_MAX_BPS`.
- Uses ports 49152 to 49999 for relaying.
- Logs to stdout. coturn logs client IP addresses. Docker keeps 3 files of 10 MB.

If the host has a private address behind 1:1 NAT, as on AWS or GCP, set `TURN_EXTERNAL_IP` to the public address.

For `turns:` on 5349, mount a certificate into the container, set `cert` and `pkey` in the template, remove `no-tls` and add a `turns:` URL to `TURN_URLS`. Keyguard apps use `turn:` URLs over UDP only and skip `turns:` and `?transport=tcp`, so TLS only helps other WebRTC clients.

coturn 4.18 keeps its admin CLI, DTLS and RFC 5780 NAT discovery off by default. The old `no-cli` and `no-dtls` options are deprecated and log errors, so the template leaves them out.

## Storage

With `RELAY_MAILBOX_DIR` set, devices leave sealed items on the relay for each other: the latest state of a device, and blobs. A device that comes online later collects them. Without it, the relay forwards live only.

- Items are sealed between two devices. The relay can't read or change them.
- Each item is a file in `items/`, named by the SHA-256 of its id. An upload goes to `tmp/` first, then replaces the item in one rename. The disk holds no item ids and no client addresses.
- An item expires after the lifetime its writer asks for, at most `RELAY_MAILBOX_TTL_SECONDS`. The relay deletes expired items every minute.
- Restarts keep items. On start, the relay deletes expired and malformed items and unfinished uploads. Restored items count toward the relay-wide limits, but toward no client address. The relay serves meanwhile; mailbox requests get `503` until it has read the directory.

Storage is a cache. The relay shrinks it to keep `RELAY_MAILBOX_MIN_FREE_BYTES` free on the volume:

- Every 30 seconds, and after a failed write, the byte limit becomes `RELAY_MAILBOX_MAX_BYTES`, or the stored bytes plus free space minus `RELAY_MAILBOX_MIN_FREE_BYTES` if that is less.
- Over the byte limit or `RELAY_MAILBOX_MAX_ITEMS`, the relay deletes items down to 90% of it: expired items first, then blobs, then state, oldest first.
- Per-address byte quotas shrink by the same ratio.
- At a limit of 0, the relay deletes every item and refuses writes. Devices keep syncing live.

If the directory can't be created, listed or written, the relay drops its items and answers mailbox requests with `503`. It tries again every minute. Rooms, forwarding, ICE and `/healthz` keep working. Disk trouble never stops the relay; only invalid settings do.

Compose mounts the named volume `mailbox` at `/data`, which the image's non-root user owns. The rest of the container stays read-only. `docker compose down -v` deletes the volume and its items.

## Limits

| Limit | Result |
|---|---|
| Message larger than 64 KiB (65,536 bytes) | `frame_too_big` error, then close 1009. Above twice the limit, the connection drops without reading the rest. |
| Over the message or byte rate | The relay stops reading the connection until the budget allows the frame. Nothing is dropped. Both budgets allow a one-second burst. Only matching heartbeat replies are exempt. The relay also stops reading past the byte budget for raw socket bytes, which counts framing and messages that never complete, such as endless empty fragments. |
| Room has `RELAY_MAX_ROOM_PEERS` peers | `room_full` error on join. |
| `RELAY_MAX_ROOMS` rooms exist | `rate_limited` error on a join that would create a room. |
| A connection is in `RELAY_MAX_ROOMS_PER_CONN` rooms | `too_many_rooms` error on join. |
| The connections of one address are in `RELAY_MAX_ROOMS_PER_IP` rooms, or those of one IPv6 /48 in 8 times as many | `rate_limited` error on a join of another room. |
| `RELAY_MAX_CONNS_PER_IP` connections from one address, or 8 times as many from one IPv6 /48 | HTTP 429 before the WebSocket upgrade. |
| `RELAY_MAX_CONNS` connections on the relay | HTTP 503 before the WebSocket upgrade. |
| Over `RELAY_WS_REQUESTS_PER_SEC` or `RELAY_ICE_REQUESTS_PER_SEC`, or 8 times that from one IPv6 /48 | HTTP 429 with `Retry-After: 1` before authentication, upgrade parsing or reading an ICE body. |
| Over `RELAY_WS_TOTAL_PER_SEC` or `RELAY_ICE_TOTAL_PER_SEC`, or the endpoint's address tracker is full | HTTP 503 with `Retry-After: 1`. Each tracker holds at most 100000 addresses and removes refilled entries. |
| `RELAY_MAX_CONNS_PER_IP` HTTP connections from one address that aren't WebSockets yet | New connections from it close without an answer. Connections from a `TRUST_PROXY` address count only toward `RELAY_MAX_CONNS`. |
| `RELAY_MAX_CONNS` HTTP connections on the relay that aren't WebSockets yet | New connections close without an answer. |
| The relay forwards more than `RELAY_EGRESS_BYTES_PER_SEC` | The relay stops reading the sender until the budget allows its `msg` or binary frame. After 5 seconds it drops the frame and sends `rate_limited`. Each copy of a broadcast counts. A broadcast may overdraw the one-second burst. |
| Less than half of `RELAY_EGRESS_BYTES_PER_SEC` is left | The same, but only for addresses above their fair share. A fair share is `RELAY_EGRESS_BYTES_PER_SEC` divided by the addresses that sent in the last second. Other addresses go on. |
| No frame and no pong for `RELAY_IDLE_SECS` | Close 1000. The relay pings every 25 seconds, or every half of the idle time if that is shorter. |
| A device doesn't read and 128 frames from its rooms queue up for it | Close 1013. |
| A device doesn't read a reply for 10 seconds | The connection drops. This includes replies to WebSocket pings. |
| A client takes no bytes of an HTTP response for 30 seconds | The connection drops. |
| HTTP request headers are incomplete after 30 seconds | The connection drops. |
| An HTTP request body is incomplete 10 seconds after its headers | HTTP 408, then the connection closes. |
| Over `ICE_REQUESTS_PER_MINUTE` or `ICE_TOTAL_PER_MINUTE`, or over `ICE_TICKETLESS_PER_MINUTE` without a live ticket | `/v1/ice-servers` answers with STUN only and `"ttlSeconds":60`. |
| Over `RELAY_MAILBOX_REQUESTS_PER_MINUTE`, or 8 times that from one IPv6 /48 | HTTP 429 `rate_limited`, with `Retry-After` set to the time one request refills. 100 times `RELAY_MAILBOX_REQUESTS_PER_MINUTE` across all addresses gets HTTP 503. |
| Over `RELAY_MAILBOX_UPLOAD_BYTES_PER_MINUTE`, or 8 times that from one IPv6 /48 | HTTP 429 `rate_limited` with `Retry-After`. One upload may overdraw the allowance; the next one waits for the refill. |
| A mailbox item over the cap of its kind | HTTP 413 `item_too_big`, before the body is read. |
| An item doesn't fit `RELAY_MAILBOX_MAX_BYTES`, `RELAY_MAILBOX_MAX_ITEMS` or the quotas of its address or IPv6 /48 | HTTP 507 `storage_full` with `Retry-After: 300`. A replacement needs room only for what it adds. Uploads in flight may go over `RELAY_MAILBOX_MAX_BYTES`, or the lower limit under disk pressure, by half of `RELAY_MAILBOX_MIN_FREE_BYTES`. |
| A mailbox upload is incomplete 120 seconds after its headers | HTTP 408 `timeout`. |
| A mailbox download is incomplete 120 seconds after its headers | The relay closes the connection. |
| 10 wrong tokens from one address, or 80 from one IPv6 /48, in a minute | Further wrong tokens get HTTP 429 with `Retry-After` on `/v1/ws`, `/v1/ice-servers` and `/v1/mailbox`. One more wrong attempt every 6 seconds. Valid tokens remain accepted and do not clear the failed-attempt budget. |

Rate budgets, the frame queue and the idle timeout are per connection, shared by all its rooms.

HTTP admission budgets are per endpoint, survive reconnects and allow a one-second burst, or a one-minute burst for the mailbox. WebSocket and ICE budgets are independent of each other, concurrent connection limits and TURN credential allowances. Valid tokens still use HTTP admission budgets.

Client pings and unsolicited, mismatched or replayed pongs count toward both connection budgets. One matching reply to the latest server heartbeat is exempt. WebSocket pings still receive their required pong reply.

On shutdown, the relay stops accepting connections and gives HTTP requests and WebSockets two seconds to finish. WebSockets receive close 1001 when writable; remaining connections are dropped at the deadline.

## Protocol

`GET /healthz` returns `{"status":"ok"}`. It never needs the token. Room and connection counts are available only through private metrics.

`POST /v1/ice-servers` takes an optional body `{"ticket":"<32 hex>"}` with the ticket from `joined`. A ticket is live while its connection is in at least one room and has sent a frame in the last 5 minutes. A request with a live ticket gets the full `ICE_REQUESTS_PER_MINUTE`. Any other body counts as no ticket. Requests without a live ticket get `ICE_TICKETLESS_PER_MINUTE`: STUN only by default. The endpoint returns:

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
  "ttlSeconds": 7200
}
```

The STUN entry is missing without `STUN_URLS`, and the TURN entry without `TURN_URLS`. Past the TURN allowance, the TURN entry is missing too and `ttlSeconds` is `60`, so clients ask again within a minute. Errors are `401 {"error":"unauthorized"}` and `429` or `503 {"error":"rate_limited"}` for the limits above. A body that doesn't arrive within 10 seconds gets `408` without a body.

`GET /v1/ws` is a WebSocket. One connection can be in up to `RELAY_MAX_ROOMS_PER_CONN` rooms, with one peer id in all of them. Text frames are JSON objects with a `t` field. Unknown fields are ignored.

The relay sends `welcome` first:

```json
{
  "t": "welcome",
  "v": 1,
  "peer": "<32 hex>",
  "features": ["room-forward"],
  "limits": {
    "maxMessageBytes": 65536,
    "messagesPerSecond": 50,
    "bytesPerSecond": 2097152,
    "roomsPerConnection": 16,
    "peersPerRoom": 4,
    "queuedFrames": 128,
    "idleSeconds": 60
  }
}
```

- `v` is the protocol version. A change that breaks clients raises it.
- `peer` is the connection's peer id. The relay draws it at random, and it stays the same in every room until the connection closes.
- `features` lists what the relay supports beyond version 1. Ignore unknown entries. `room-forward` is the binary frame below. `mailbox` is the mailbox API below, listed while storage is configured, even when it is full or unavailable.
- `limits` holds this relay's settings. `maxMessageBytes` applies to text and binary messages. `messagesPerSecond` and `bytesPerSecond` are the connection's budgets, each with a one-second burst. `queuedFrames` is the frame queue, and `idleSeconds` the idle timeout. With `mailbox`, it adds `mailboxStateBytes`, `mailboxBlobBytes` and `mailboxTtlSeconds`. Ignore unknown fields.

Without the right token, the relay sends `unauthorized` instead of `welcome`.

| Client sends | Relay answers |
|---|---|
| `{"t":"join","room":"<64 hex>"}` | `{"t":"joined","room":"<64 hex>","peers":["<32 hex>",...],"ticket":"<32 hex>"}` with the other peers in the room and the connection's ticket for `/v1/ice-servers`. They get `{"t":"presence","room":"<64 hex>","peer":"<32 hex>","state":"join"}`. |
| `{"t":"pub","room":"<64 hex>","to":"<32 hex>","data":"<base64>"}` | The target in the room gets `{"t":"msg","room":"<64 hex>","from":"<32 hex>","data":"<base64>"}`. |
| `{"t":"pub","room":"<64 hex>","to":"*","data":"<base64>"}` | Every other peer in the room gets `msg`. |
| `{"t":"leave","room":"<64 hex>"}` | The others in the room get `presence` with `"state":"leave"`. Without `room`, leaves every room. A closed connection leaves every room too. |
| `{"t":"ping"}` | `{"t":"pong"}` |

- The ticket is the same on every `joined` of a connection. Unlike the peer id, other peers never see it.
- `pub` can leave out `room` while the connection is in one room.

Ids are lowercase hex. `data` is standard base64 with padding. The sender never gets its own messages back. A message to a peer that isn't in the room is dropped.

Binary frames carry relay-forwarded bytes within one room. The client sends `0x03 | room id (32 bytes) | target peer id (16 bytes) | payload`, and the target gets `0x03 | room id (32 bytes) | sender peer id (16 bytes) | payload`. A frame for a peer that isn't in the room, or for a room the connection isn't in, is dropped without an error. The relay forwards the payload as is.

A text frame with an unknown `t`, or a binary frame with an unknown first byte, gets `unknown_type`. The connection stays open, and the frame counts toward the budgets like any other.

Errors are `{"t":"err","code":"<code>"}`. When `room_full`, `rate_limited` or `too_many_rooms` refuse a `join`, they add `"room":"<64 hex>"`.

| Code | Meaning | Closes |
|---|---|---|
| `unauthorized` | Missing or wrong token. | yes, 1008 |
| `bad_frame` | Malformed JSON, a missing or non-string `t`, a known `t` with bad fields, a bad id or base64, or a binary frame that is empty or shorter than its header. A `join` of a room the connection is in. A `pub` without `room` from a connection in several rooms. | yes, 1008 |
| `frame_too_big` | Message larger than 64 KiB. | yes, 1009 |
| `rate_limited` | A frame waited 5 seconds for `RELAY_EGRESS_BYTES_PER_SEC` and was dropped, the relay has `RELAY_MAX_ROOMS` rooms, or the client address or its IPv6 /48 is at its room limit. | no |
| `room_full` | The room has `RELAY_MAX_ROOM_PEERS` peers. | no |
| `too_many_rooms` | The connection is in `RELAY_MAX_ROOMS_PER_CONN` rooms. | no |
| `not_joined` | A well-formed `pub` or `leave` before `join`, or for a room the connection isn't in. | no |
| `unknown_type` | A text frame with an unknown `t`, or a binary frame with an unknown first byte. | no |

### Mailbox

`/v1/mailbox/state` and `/v1/mailbox/blob` take `PUT`, `GET`, `HEAD` and `DELETE`; other methods get `405`. They exist only with storage. Otherwise every `/v1/mailbox/` path answers `404 {"error":"not_found"}`. Every request carries:

| Header | Value |
|---|---|
| `Authorization` | `Bearer <token>`, when the relay has `RELAY_TOKEN`. |
| `X-Keyguard-Ticket` | The ticket from `joined`, live as for `/v1/ice-servers`. 32 hex. |
| `X-Keyguard-Item` | The item id, 64 hex. It is a capability, so it goes in a header: proxies log URLs. |

A state item holds up to 1,048,576 bytes and a blob up to 17,825,792 bytes. These caps are part of the protocol. An item exists under one kind.

- `PUT` stores the body in place of the item. It needs `Content-Length` within the cap and `X-Keyguard-Tag`, 32 hex the relay stores as is. `X-Keyguard-Ttl` asks for a lifetime in seconds, from 60 to `RELAY_MAILBOX_TTL_SECONDS`; without it, the item gets the longest. It answers `204` with `X-Keyguard-Expires` in Unix seconds. A replaced item counts toward the address that replaced it. With `If-None-Match: *`, a live item is kept and the answer is `412`, before any of the body is read: the devices of a group write each shared blob once.
- `GET` answers `200` with the body, `X-Keyguard-Tag`, `X-Keyguard-Expires`, `ETag` and `Accept-Ranges: bytes`. With `If-None-Match` set to the item's tag, quoted or not, it answers `304`.
- `GET` with `Range: bytes=N-` resumes a download. It answers `206` with the body from byte `N` and `Content-Range`, or `416` with `Content-Range: bytes */<length>` when `N` is at or past the end. With `If-Range` other than the item's `ETag`, or another range form, it answers `200` with the whole body. `If-None-Match` is checked first.
- `ETag` is `"<created>-<length>"`: the upload time in Unix seconds and the body length, both in lowercase hex. An item uploaded again in a later second gets a new `ETag`.
- `HEAD` answers like `GET`, without the body. It ignores `Range`.
- `DELETE` answers `204`. With `If-Match` set to another tag, it answers `412`.

Errors are `{"error":"<code>"}`:

| Status | Code | Meaning |
|---|---|---|
| 400 | `bad_request` | A missing or malformed item id, tag, lifetime or `Content-Length`, a chunked body, or a body shorter than `Content-Length`. |
| 401 | `unauthorized` | Missing or wrong token. |
| 403 | `ticket_required` | No live ticket. |
| 404 | `not_found` | No item of this kind, or it expired. |
| 408 | `timeout` | The upload didn't finish within 120 seconds. |
| 412 | `tag_mismatch` | `If-Match` names another tag. |
| 412 | `item_exists` | `PUT` with `If-None-Match: *` for a live item. |
| 413 | `item_too_big` | `Content-Length` is over the cap of the kind. |
| 429 | `rate_limited` | Over a request or upload allowance, or too many wrong tokens. With `Retry-After`. |
| 503 | `storage_unavailable` | The data directory doesn't work. With `Retry-After: 60`. |
| 507 | `storage_full` | A limit or quota has no room for the item. With `Retry-After: 300`. |
