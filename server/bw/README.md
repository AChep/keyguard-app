# Bitwarden test server

A Bitwarden-compatible server for testing Keyguard: [Vaultwarden](https://github.com/dani-garcia/vaultwarden)
in Docker, API only. Its database lives on a tmpfs, so it is in RAM and is gone when the container
stops. [`vaultwarden.env`](vaultwarden.env) holds the image pin and every server setting.

## Quick start

```bash
server/bw/bw start  # http://127.0.0.1:8000
server/bw/bw register --email me@keyguard.test --password 'test password'
server/bw/bw seed --email me@keyguard.test --password 'test password'
# Keyguard: add a Bitwarden account, pick Custom (self-hosted), enter http://127.0.0.1:8000
server/bw/bw totp --email me@keyguard.test --password 'test password'  # optional 2FA
server/bw/bw stop
```

## Commands

| Command    | Options                                                                                            | Effect                                                                     |
|------------|----------------------------------------------------------------------------------------------------|----------------------------------------------------------------------------|
| `run`      | server options                                                                                     | Runs in the foreground. Ctrl+C stops it.                                   |
| `start`    | server options                                                                                     | Runs in the background, waits up to 60 s for `GET /alive`, prints the URL. |
| `stop`     | `[--port N \| --all]`                                                                              | Removes a server. Without options, removes the only one.                   |
| `status`   |                                                                                                    | Lists servers and probes `/alive`. Exits 1 when none run.                  |
| `logs`     | `[--port N] [-f]`                                                                                  | Prints the server log.                                                     |
| `register` | `--email E --password P [--name N] [--argon2] [--pbkdf2-iterations N]`                             | Creates an account. PBKDF2 with 600000 iterations by default.              |
| `seed`     | `--email E --password P [--folders N] [--logins N] [--notes N] [--attachment] [--send text\|file]` | Adds folders, logins and notes, and optionally an attachment and a Send.   |
| `totp`     | `--email E --password P`                                                                           | Turns on authenticator 2FA and prints the secret.                          |
| `code`     | `--secret S`                                                                                       | Prints the current TOTP code. Needs no server.                             |
| `org`      | `--email E --password P --name N [--member EMAIL]`                                                 | Creates an organization. `--member` adds another account to it.            |
| `send`     | `--email E --password P --text T [--password-protect P]`                                           | Creates a text Send.                                                       |
| `attach`   | `--email E --password P --cipher ID --file PATH`                                                   | Uploads a file as an attachment to an item.                                |

Account commands run through Gradle. All except `code` take `--server URL`. Without it, `bw` uses
the only running server. All take `--json`.

Containers are named `keyguard-bw-<port>` and carry the `com.artemchep.keyguard.bw` label. Several
servers can run on different ports.

Server options for `run` and `start`:

| Option         | Default                   | Effect                                                           |
|----------------|---------------------------|------------------------------------------------------------------|
| `--port N`     | `8000`                    | Host port.                                                       |
| `--bind ADDR`  | `127.0.0.1`               | Host address. `0.0.0.0` lets an Android emulator use `10.0.2.2`. |
| `--domain URL` | `http://127.0.0.1:<port>` | Public base URL, passed as `DOMAIN`.                             |
| `--web-vault`  | off                       | Enables the web vault.                                           |

For an Android emulator:

```bash
server/bw/bw start --bind 0.0.0.0 --domain http://10.0.2.2:8000
```

`--bind 0.0.0.0` also exposes the tokenless `/admin` API to your network.

## Output

Account commands print human-readable lines, then one JSON object as the last line of stdout.
`--json` prints only the JSON line. Keyguard's own request log goes to stderr.

The first account command compiles Keyguard and takes minutes. A failed command exits non-zero.

## Requirements

- Docker: Docker Desktop, OrbStack, Colima or Podman. `bw` calls the `docker` CLI with its current
  context.
- `curl`, for `start` and `status`.
- A JDK to run Gradle, for the account commands.

## Manual web vault

```bash
server/bw/bw start --web-vault
```

Open `http://127.0.0.1:8000/#/register` to create an account in a browser. `127.0.0.1` is a secure
context, so the web vault works over plain HTTP.

Without `--web-vault`, `GET /` returns 404. `/api`, `/identity` and `/notifications/hub` still work,
and `GET /alive` answers once the database is up.

## Settings

[`vaultwarden.env`](vaultwarden.env) explains each setting. `DOMAIN` is added at launch, because it
carries the host port. The container listens on port 80; the host port maps onto it.

## E2E tests

`integration/bitwardenE2eTest` uses Testcontainers. It starts the pinned image, reads
`vaultwarden.env`, picks a free host port, sets `DOMAIN` and waits for `GET /alive`. The suite
shares one server. A test that overrides settings, for example to lower a rate limit, starts its
own.

```bash
./gradlew :integration:bitwardenE2eTest:bitwardenE2eTest
```

The task first runs `docker version` and fails if the daemon does not answer. Testcontainers
pulls its `ryuk` cleanup sidecar from Docker Hub on the first run. CI disables it, because the
runner is ephemeral.

Testcontainers finds Docker Desktop on its own. Other runtimes need environment variables:

```bash
# OrbStack
export DOCKER_HOST="unix://$HOME/.orbstack/run/docker.sock"

# Colima
export DOCKER_HOST="unix://${HOME}/.colima/default/docker.sock"
export TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock

# Rootless Podman
export TESTCONTAINERS_RYUK_DISABLED=true
```

See the [Testcontainers docs](https://java.testcontainers.org/supported_docker_environment/) for
other setups.

## Bump the image

Edit `VAULTWARDEN_IMAGE` in `vaultwarden.env`. Get the digest for a tag:

```bash
docker buildx imagetools inspect ghcr.io/dani-garcia/vaultwarden:<tag>
```

Or read the `Docker-Content-Digest` header of a registry `HEAD` request for the manifest. Pin the
multi-arch index digest, not a per-platform one. It is the same on Docker Hub
(`docker.io/vaultwarden/server`).

## Limits

- No SMTP, so no email 2FA and no invite emails.
- Keyguard refuses PBKDF2 below 600000 iterations and Argon2id below 2 iterations or 16 MiB. The
  `register` defaults pass; a lower `--pbkdf2-iterations` does not.
- CI runs the suite on Linux only. GitHub macOS runners have no Docker.
- Vaultwarden is AGPL-3.0. This repo only runs the published image and does not vendor its code.
