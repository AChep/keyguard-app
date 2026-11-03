# Local Vaultwarden mTLS fixture

Uses the image and defaults from `../vaultwarden.env`, with nginx terminating TLS
and requiring a client certificate on port 8443. Port 8444 serves the HTTPS web
vault for registration without a client certificate. All published ports listen
on loopback only; port 18080 is reserved for local HTTP CLI setup.
The database lives in RAM and is lost when Vaultwarden stops.

From this directory:

```sh
./generate-certificates.sh
docker compose --env-file ../vaultwarden.env up -d --wait
```

The generator requires Python 3 and OpenSSL 3.4+. On macOS, use Homebrew OpenSSL
if the system version is too old. Set `OPENSSL` to its executable path if needed.
It refuses to overwrite existing fixtures.

| File in `fixtures/` | Use |
| --- | --- |
| `ca.pem` | Test root CA to trust on the client device |
| `server.pem` / `server-key.pem` | HTTPS server identity, mounted by nginx |
| `client.p12` | Initial client identity to import into Keyguard |
| `client-replacement.p12` | Second identity for testing certificate replacement |

All certificates are valid for 100 calendar years from generation. Both PKCS#12
files use the password **`keyguard-test`**. Generated files, including private keys,
are ignored by Git. These are local test credentials.

## Use in Keyguard

1. Trust `fixtures/ca.pem` on the client device. On macOS, import it into the login
   keychain with Keychain Access and allow SSL trust. Restart the browser and Keyguard afterward.
   The generator and server do not change system trust settings.
2. Create an account at <https://localhost:8444/#/register>. This HTTPS setup port
   does not require a client certificate. Use this URL for browser registration;
   the HTTP CLI port is not the browser setup page.
3. Add a custom Bitwarden server: **`https://localhost:8443`**.
4. Import `fixtures/client.p12` as the client certificate and sign in.
5. In Settings > Accounts > the account > Security > Client certificate,
   replace it with `fixtures/client-replacement.p12`, save, and sync.

Removing the client certificate should make future connections to port 8443 fail.
Both client identities remain accepted by the server; replacement does not revoke
the first certificate. The PKCS#12 identity does not itself make the server CA trusted.

The server certificate covers `localhost`, `127.0.0.1`, `::1`, and Android emulator
host alias `10.0.2.2`. The default port bindings do not expose the server to physical
devices on the LAN.

## Verify and stop

```sh
# HTTPS web vault and API, without a client identity.
curl --cacert fixtures/ca.pem https://localhost:8444/api/config

# Succeeds with server verification and a client identity.
curl --cacert fixtures/ca.pem \
  --cert fixtures/client.pem --key fixtures/client-key.pem \
  https://localhost:8443/alive

# Rejected by nginx: no client certificate.
curl --cacert fixtures/ca.pem https://localhost:8443/alive

docker compose --env-file ../vaultwarden.env logs proxy
docker compose --env-file ../vaultwarden.env down
```

The proxy log includes the client certificate subject, so replacement is visible.
WebSocket forwarding is enabled for live sync notifications, following the
[nginx WebSocket documentation](https://nginx.org/en/docs/http/websocket.html).
TLS termination follows Vaultwarden's
[reverse proxy guidance](https://github.com/dani-garcia/vaultwarden/wiki/Proxy-examples).
