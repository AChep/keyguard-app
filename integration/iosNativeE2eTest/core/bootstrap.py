#!/usr/bin/env python3
"""Prepare a test session: folder, virtualenv, TLS, servers and accounts.

Safe to rerun: every step skips what already exists. Running servers are
reused, never restarted. Accounts are created with server/bw/bw, which runs
Gradle; the first account command compiles Keyguard and takes minutes.
"""

import argparse
import datetime
import hashlib
import json
import os
import subprocess
import sys
import urllib.request

from lib import bitwarden, session
from lib.config import ACCOUNTS, P12_PASSWORD, PORTS, SERVERS

BW = session.REPO / "server" / "bw" / "bw"
VENV = session.BUILD / "venv"
REQUIREMENTS = session.HARNESS / "requirements.txt"
SERVER_NAMES = (
    "DNS:localhost", "DNS:ios-native-e2e.localhost", "DNS:*.ios-native-e2e.localhost",
    "DNS:keyguard-native-e2e.127.0.0.1.nip.io", "DNS:*.keyguard-native-e2e.127.0.0.1.nip.io",
    "DNS:" + ACCOUNTS["login-branches"].email.split("@", 1)[1],
    "IP:127.0.0.1", "IP:::1",
)


def step(message):
    print(f"• {message}", flush=True)


def run(*command, capture=False):
    return subprocess.run([str(part) for part in command], check=True, text=True,
                          stdout=subprocess.PIPE if capture else None).stdout


def create_session(name):
    if os.environ.get("E2E_SESSION"):
        directory = session.directory()
        step(f"Session {directory} (E2E_SESSION)")
        return directory
    directory = session.BUILD / name
    directory.mkdir(mode=0o700, parents=True, exist_ok=True)
    if session.CURRENT.is_symlink() or session.CURRENT.exists():
        if session.CURRENT.resolve() != directory.resolve():
            session.CURRENT.unlink()
    if not session.CURRENT.exists():
        session.CURRENT.symlink_to(name)
    step(f"Session {directory}")
    return directory


def create_venv():
    digest = hashlib.sha256(REQUIREMENTS.read_bytes()).hexdigest()
    stamp = VENV / "requirements.sha256"
    if stamp.exists() and stamp.read_text() == digest:
        step(f"Virtualenv {VENV} is current")
        return
    step(f"Installing {REQUIREMENTS.name} into {VENV}")
    run(sys.executable, "-m", "venv", VENV)
    run(VENV / "bin" / "python", "-m", "pip", "install", "--quiet", "--disable-pip-version-check",
        "-r", REQUIREMENTS)
    stamp.write_text(digest)


def create_tls(days):
    directory = session.tls("")
    if (directory / "ca.pem").exists():
        step(f"TLS certificates in {directory} already exist")
        return
    step(f"Creating a test CA, server and client certificate valid for {days} days")
    directory.mkdir(mode=0o700, parents=True)
    extensions = directory / "extensions.cnf"
    extensions.write_text(f"""[ca]
basicConstraints = critical, CA:true, pathlen:0
keyUsage = critical, keyCertSign, cRLSign
subjectKeyIdentifier = hash

[server]
basicConstraints = critical, CA:false
keyUsage = critical, digitalSignature, keyEncipherment
extendedKeyUsage = serverAuth
subjectKeyIdentifier = hash
authorityKeyIdentifier = keyid,issuer
subjectAltName = {",".join(SERVER_NAMES)}

[client]
basicConstraints = critical, CA:false
keyUsage = critical, digitalSignature
extendedKeyUsage = clientAuth
subjectKeyIdentifier = hash
authorityKeyIdentifier = keyid,issuer
""")
    os.umask(0o077)
    path = lambda name: directory / name
    run("openssl", "genpkey", "-quiet", "-algorithm", "RSA", "-pkeyopt", "rsa_keygen_bits:3072",
        "-out", path("ca.key"))
    run("openssl", "req", "-x509", "-new", "-key", path("ca.key"), "-sha256", "-days", days,
        "-subj", "/CN=Keyguard native E2E test CA", "-config", extensions,
        "-extensions", "ca", "-out", path("ca.pem"))
    for name, subject in (("server", "localhost"), ("client", "Keyguard native E2E client")):
        run("openssl", "genpkey", "-quiet", "-algorithm", "RSA",
            "-pkeyopt", "rsa_keygen_bits:3072", "-out", path(f"{name}.key"))
        run("openssl", "req", "-new", "-key", path(f"{name}.key"), "-subj", f"/CN={subject}",
            "-out", path(f"{name}.csr"))
        run("openssl", "x509", "-req", "-in", path(f"{name}.csr"), "-CA", path("ca.pem"),
            "-CAkey", path("ca.key"), "-set_serial", f"0x{os.urandom(16).hex()}", "-sha256",
            "-days", days, "-extfile", extensions, "-extensions", name, "-out", path(f"{name}.pem"))
        path(f"{name}.csr").unlink()
    # 3DES/SHA-1 PKCS#12 encryption imports into Apple's keychain.
    run("openssl", "pkcs12", "-export", "-inkey", path("client.key"), "-in", path("client.pem"),
        "-certfile", path("ca.pem"), "-name", "Keyguard native E2E client",
        "-keypbe", "PBE-SHA1-3DES", "-certpbe", "PBE-SHA1-3DES", "-macalg", "sha1",
        "-passout", f"pass:{P12_PASSWORD}", "-out", path("client.p12"))
    run("openssl", "verify", "-CAfile", path("ca.pem"), "-purpose", "sslserver", path("server.pem"))


def trust_tls(device):
    step(f"Trusting the test CA on simulator {device}")
    run("xcrun", "simctl", "keychain", device, "add-root-cert", session.tls("ca.pem"))


def alive(url):
    try:
        with urllib.request.urlopen(url + "/alive", timeout=2) as response:
            return response.status == 200
    except OSError:
        return False


def start_server(server):
    if alive(server.url):
        step(f"Server {server.key} on {server.url} is running; reusing it")
        return
    step(f"Starting server {server.key} on {server.url}")
    command = [BW, "start", "--port", server.port, "--domain", server.domain]
    if server.web_vault:
        command.append("--web-vault")
    for key, value in server.env.items():
        command += ["--env", f"{key}={value}"]
    run(*command)


def bw(command, account, *options):
    output = run(BW, command, "--server", account.server.url, "--email", account.email,
                 "--password", account.password, *options, "--json", capture=True)
    return json.loads(output.strip().splitlines()[-1])


def exists(account):
    status, body = bitwarden.token_request(account, f"native-e2e-bootstrap-{account.key}")
    # An account with 2FA answers with a challenge instead of a token.
    return status == 200 or (isinstance(body, dict) and "TwoFactorProviders" in body)


def smtp_sink_running():
    try:
        url = f"http://127.0.0.1:{PORTS['smtp-api']}/health"
        with urllib.request.urlopen(url, timeout=2) as response:
            return json.load(response).get("sinkOnly") is True
    except OSError:
        return False


def create_account(account):
    if exists(account):
        step(f"Account {account.key} <{account.email}> exists")
        return
    step(f"Creating account {account.key} <{account.email}> on {account.server.url}")
    bw("register", account, "--name", account.name)
    if account.seed:
        bw("seed", account, *account.seed)
    if account.organization:
        bw("org", account, "--name", account.organization)
    if account.totp:
        secret = bw("totp", account)["secret"]
        session.write_json(session.path("accounts", f"{account.key}-totp.json"),
                           {"email": account.email, "secret": secret})


def describe(account):
    entry = {"email": account.email, "server": account.server.key, "id": None}
    if account.totp:
        return entry
    try:
        oracle = bitwarden.Session(account, f"native-e2e-bootstrap-{account.key}")
    except bitwarden.ServerError:
        return entry
    entry["id"] = oracle.account_id
    entry["organizations"] = {item["name"]: item["id"]
                              for item in oracle.profile.get("organizations") or []}
    return entry


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--session", default=datetime.date.today().isoformat(),
                        help="Session folder name under build/ios-native-e2e (default: today)")
    parser.add_argument("--device", default=os.environ.get("E2E_DEVICE"),
                        help="Dedicated simulator UDID whose keychain trusts the test CA "
                             "(default: $E2E_DEVICE)")
    parser.add_argument("--servers", default=",".join(SERVERS),
                        help=f"Servers to start, comma-separated (default: {','.join(SERVERS)})")
    parser.add_argument("--days", default="7", help="Certificate validity in days (default: 7)")
    args = parser.parse_args()
    servers = [SERVERS[key] for key in args.servers.split(",") if key]

    directory = create_session(args.session)
    create_venv()
    create_tls(args.days)
    if args.device:
        trust_tls(args.device)
    else:
        step("No --device or E2E_DEVICE: the simulator does not trust the test CA yet")
    for server in servers:
        start_server(server)
    accounts = [account for account in ACCOUNTS.values() if account.server in servers]
    for account in accounts:
        if account.server.key == "mail" and not smtp_sink_running():
            step(f"Skipping {account.key}: start `e2e smtp_sink` first, then rerun bootstrap")
            continue
        create_account(account)

    record = session.path("session.json")
    state = session.read_json(record) if record.exists() else {
        "created": datetime.datetime.now(datetime.timezone.utc).isoformat()}
    state["device"] = args.device or state.get("device")
    state["servers"] = {server.key: {"url": server.url, "domain": server.domain}
                        for server in servers}
    state["accounts"] = {account.key: describe(account) for account in accounts}
    session.write_json(record, state)
    step(f"Wrote {record}")
    print(f"\nReady. Evidence goes to {directory}.")


if __name__ == "__main__":
    main()
