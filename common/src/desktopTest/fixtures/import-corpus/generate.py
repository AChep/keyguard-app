#!/usr/bin/env python3
"""Regenerates the import corpus files that real exporters write.

Test-only and not part of any build. ``bitwarden``, ``keepassxc`` and ``all``
run inside the container of ``compose.yaml``; ``checksums`` needs only the
standard library and runs anywhere. See
``common/src/desktopTest/resources/import-corpus/README.md``.

Every value written here is synthetic. The passwords below are public test
material and must never be reused.
"""

import argparse
import base64
import datetime
import hashlib
import hmac
import json
import os
import ssl
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.request
from pathlib import Path

HERE = Path(__file__).resolve().parent
CORPUS = HERE.parents[1] / "resources" / "import-corpus"
SSH_CORPUS = HERE.parents[1] / "resources" / "ssh-import-corpus"

PROVENANCE_HEADER = ["resource", "exporter", "exporter_version", "server", "generated"]
# Exports live in one directory per exporter; the files next to them are
# bookkeeping and are not checksummed.
EXPORT_DIRECTORIES = ["bitwarden", "keepassxc", "keyguard"]

KEEPASSXC_DB_PASSWORD = "corpus-db-password"

BITWARDEN_MASTER_PASSWORD = "corpus-master-password"
BITWARDEN_EXPORT_PASSWORD = "corpus-export-password"
BITWARDEN_PBKDF2 = {"kdf": 0, "kdfIterations": 600_000}
BITWARDEN_ARGON2ID = {"kdf": 1, "kdfIterations": 3, "kdfMemory": 64, "kdfParallelism": 4}

# Bitwarden's enumerations, as its export writes them.
URI_MATCH_HOST = 1
FIELD_TEXT = 0
FIELD_HIDDEN = 1
FIELD_BOOLEAN = 2
FIELD_LINKED = 3
LINKED_LOGIN_USERNAME = 100


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("command", choices=["all", "bitwarden", "keepassxc", "checksums", "certs"])
    command = parser.parse_args().command
    if command == "certs":
        write_certificates(Path(os.environ["CERTS_DIR"]))
        return
    if command in ("all", "keepassxc"):
        generate_keepassxc()
    if command in ("all", "bitwarden"):
        generate_bitwarden()
    if command in ("all", "checksums"):
        write_checksums()
    if command != "checksums":
        hand_back_to_host_user()


#
# KeePassXC
#


def generate_keepassxc():
    seed = HERE / "seed" / "keepassxc.xml"
    with tempfile.TemporaryDirectory() as work:
        database = Path(work) / "corpus.kdbx"
        run(
            ["keepassxc-cli", "import", "--quiet", "--set-password", str(seed), str(database)],
            stdin=f"{KEEPASSXC_DB_PASSWORD}\n{KEEPASSXC_DB_PASSWORD}\n",
        )
        csv = run(
            ["keepassxc-cli", "export", "--quiet", "--format", "csv", str(database)],
            stdin=f"{KEEPASSXC_DB_PASSWORD}\n",
            text=False,
        )
    write_export("keepassxc/database.csv", csv)
    version = run(["keepassxc-cli", "--version"]).strip()
    write_provenance([["keepassxc/database.csv", "keepassxc-cli", version, "-", today()]])


#
# Bitwarden
#


def generate_bitwarden():
    server = os.environ["VAULTWARDEN_URL"]
    wait_until_alive(server)

    main_account = BitwardenAccount(server, "corpus@example.com", BITWARDEN_PBKDF2)
    main_account.register()
    main_account.login()
    seed_bitwarden(main_account)
    exports = {
        "bitwarden/vault.csv": main_account.export("csv"),
        "bitwarden/vault.json": main_account.export("json"),
        "bitwarden/vault-protected-pbkdf2.json": main_account.export(
            "encrypted_json",
            password=BITWARDEN_EXPORT_PASSWORD,
        ),
        "bitwarden/vault-account-encrypted.json": main_account.export("encrypted_json"),
    }

    # A second account, to learn whether a password-protected export
    # follows the account's KDF.
    argon2_account = BitwardenAccount(server, "corpus-argon2@example.com", BITWARDEN_ARGON2ID)
    argon2_account.register()
    argon2_account.login()
    argon2_account.create_item(secure_note("Argon2 account note", "Exported with an Argon2id account."))
    argon2_export = argon2_account.export("encrypted_json", password=BITWARDEN_EXPORT_PASSWORD)
    argon2_kdf = json.loads(argon2_export)["kdfType"]
    if argon2_kdf == BITWARDEN_ARGON2ID["kdf"]:
        exports["bitwarden/vault-protected-argon2id.json"] = argon2_export
    else:
        log(f"The protected export of the Argon2id account uses KDF {argon2_kdf}; not kept.")
        (CORPUS / "bitwarden" / "vault-protected-argon2id.json").unlink(missing_ok=True)

    for resource, data in exports.items():
        write_export(resource, data)
    exporter_version = main_account.bw("--version").strip()
    server_name = f"vaultwarden {os.environ['VAULTWARDEN_VERSION']}"
    write_provenance([
        [resource, "@bitwarden/cli", exporter_version, server_name, today()]
        for resource in exports
    ])


def seed_bitwarden(account):
    work_folder = account.create_folder("Work")
    email_folder = account.create_folder("Work/Email")

    mail = account.create_item({
        "type": 1,
        "name": "Mail",
        "folderId": email_folder,
        "favorite": True,
        "reprompt": 1,
        "notes": "First line\nSecond line",
        "login": {
            "uris": [
                {"uri": "https://mail.example.com", "match": URI_MATCH_HOST},
                {"uri": "androidapp://com.example.mail", "match": None},
            ],
            "username": "alice@example.com",
            "password": "old password",
            "totp": "otpauth://totp/Example:alice@example.com?secret=JBSWY3DPEHPK3PXP&issuer=Example",
            "fido2Credentials": [passkey("mail.example.com")],
        },
        "fields": [
            {"name": "PIN", "value": "1234", "type": FIELD_TEXT},
            {"name": "Recovery", "value": "abcd-efgh", "type": FIELD_HIDDEN},
            {"name": "Remember me", "value": "true", "type": FIELD_BOOLEAN},
            {"name": "Login", "value": None, "type": FIELD_LINKED, "linkedId": LINKED_LOGIN_USERNAME},
        ],
    })
    # Changing the password is what gives an item its password history.
    account.edit_item(mail, lambda item: item["login"].update(password="correct horse battery staple"))

    account.create_item(secure_note("Wi-Fi", 'The password is "42", with a comma.', folder=work_folder))
    manual = account.create_item(secure_note("Manual", "Has an attachment."))
    account.create_attachment(manual, "manual.txt", "Synthetic attachment of the import corpus.\n")

    account.create_item({
        "type": 3,
        "name": "Visa",
        "card": {
            "cardholderName": "Alice Example",
            "brand": "Visa",
            "number": "4111111111111111",
            "expMonth": "12",
            "expYear": "2030",
            "code": "123",
        },
    })
    account.create_item({
        "type": 4,
        "name": "Home",
        "identity": {
            "title": "Ms",
            "firstName": "Alice",
            "lastName": "Example",
            "address1": "12 Sample Road",
            "city": "Springfield",
            "postalCode": "62701",
            "country": "US",
            "email": "alice@example.com",
            "phone": "+1 555 0100",
        },
    })
    account.create_item({
        "type": 5,
        "name": "Server key",
        "sshKey": ssh_key("openssh/id_ed25519"),
    })
    account.create_item({
        "type": 1,
        "name": "Untitled site",
        "login": {
            "uris": [{"uri": "https://plain.example.org", "match": None}],
            "username": "plain-user",
            "password": " spaced \"and\" quoted, ",
        },
        "notes": "Ünïcödé — 日本語",
    })

    # Archived items stay in every export, with their archive date.
    forum = account.create_item({
        "type": 1,
        "name": "Old forum",
        "login": {
            "uris": [{"uri": "https://forum.example.org", "match": None}],
            "username": "bob",
            "password": "older password",
        },
    })
    account.archive_item(forum)
    account.archive_item(account.create_item(secure_note("Old note", "Kept for later.")))


def secure_note(name, notes, folder=None):
    return {"type": 2, "name": name, "notes": notes, "folderId": folder, "secureNote": {"type": 0}}


def passkey(rp_id):
    from cryptography.hazmat.primitives import serialization
    from cryptography.hazmat.primitives.asymmetric import ec

    key = ec.generate_private_key(ec.SECP256R1())
    pkcs8 = key.private_bytes(
        serialization.Encoding.DER,
        serialization.PrivateFormat.PKCS8,
        serialization.NoEncryption(),
    )
    return {
        "credentialId": "8c0a1f6e-1c55-4bde-9b8f-4f1f2b7c9d10",
        "keyType": "public-key",
        "keyAlgorithm": "ECDSA",
        "keyCurve": "P-256",
        "keyValue": b64url(pkcs8),
        "rpId": rp_id,
        "rpName": "Example Mail",
        "userHandle": b64url(b"alice"),
        "userName": "alice@example.com",
        "userDisplayName": "Alice Example",
        "counter": "0",
        "discoverable": "true",
        "creationDate": "2024-01-01T00:00:00.000Z",
    }


def ssh_key(path):
    private_key = (SSH_CORPUS / path).read_text()
    public_key = (SSH_CORPUS / f"{path}.pub").read_text().strip()
    blob = base64.b64decode(public_key.split()[1])
    digest = base64.b64encode(hashlib.sha256(blob).digest()).decode().rstrip("=")
    return {"privateKey": private_key, "publicKey": public_key, "keyFingerprint": f"SHA256:{digest}"}


class BitwardenAccount:
    """A Vaultwarden account, driven through the real Bitwarden CLI."""

    def __init__(self, server, email, kdf):
        self.server = server
        self.email = email
        self.kdf = kdf
        self.home = Path(tempfile.mkdtemp(prefix="bw-"))
        self.session = None

    def register(self):
        """Signs up the way the web vault does; the CLI cannot."""
        keys = bitwarden_registration_keys(self.email, BITWARDEN_MASTER_PASSWORD, self.kdf)
        token = post_json(
            f"{self.server}/identity/accounts/register/send-verification-email",
            {"email": self.email, "name": "Import corpus"},
        )
        post_json(
            f"{self.server}/identity/accounts/register/finish",
            {
                "email": self.email,
                "emailVerificationToken": token,
                "masterPasswordHint": None,
                **keys,
                **self.kdf,
            },
        )

    def login(self):
        self.bw("config", "server", self.server)
        # A successful login decrypts the user key, which proves the
        # registration crypto right.
        self.session = self.bw("login", self.email, BITWARDEN_MASTER_PASSWORD, "--raw").strip()

    def bw(self, *args, text=True):
        env = {
            **os.environ,
            "HOME": str(self.home),
            "BITWARDENCLI_APPDATA_DIR": str(self.home / "appdata"),
        }
        if self.session:
            env["BW_SESSION"] = self.session
        return run(["bw", "--nointeraction", *args], env=env, text=text)

    def create_folder(self, name):
        return json.loads(self.bw("create", "folder", encode({"name": name})))["id"]

    def create_item(self, item):
        template = json.loads(self.bw("get", "template", "item"))
        # The template carries placeholder notes; an item without notes has none.
        created = json.loads(self.bw("create", "item", encode({**template, "notes": None, **item})))
        return created["id"]

    def edit_item(self, item_id, change):
        item = json.loads(self.bw("get", "item", item_id))
        change(item)
        self.bw("edit", "item", item_id, encode(item))

    def archive_item(self, item_id):
        self.bw("archive", "item", item_id)

    def create_attachment(self, item_id, name, text):
        directory = Path(tempfile.mkdtemp(prefix="attachment-"))
        file = directory / name
        file.write_text(text)
        self.bw("create", "attachment", "--file", str(file), "--itemid", item_id)

    def export(self, export_format, password=None):
        self.bw("sync")
        output = self.home / f"export-{export_format}-{password is not None}"
        args = ["export", "--format", export_format, "--output", str(output)]
        if password is not None:
            args += ["--password", password]
        self.bw(*args)
        return output.read_bytes()


def bitwarden_registration_keys(email, password, kdf):
    """The keys a Bitwarden client sends when it creates an account."""
    from cryptography.hazmat.primitives import hashes, serialization
    from cryptography.hazmat.primitives.asymmetric import rsa
    from cryptography.hazmat.primitives.kdf.hkdf import HKDFExpand

    salt = email.strip().lower().encode()
    if kdf["kdf"] == 0:
        master_key = hashlib.pbkdf2_hmac("sha256", password.encode(), salt, kdf["kdfIterations"], 32)
    else:
        from argon2.low_level import Type, hash_secret_raw

        master_key = hash_secret_raw(
            secret=password.encode(),
            salt=hashlib.sha256(salt).digest(),
            time_cost=kdf["kdfIterations"],
            memory_cost=kdf["kdfMemory"] * 1024,
            parallelism=kdf["kdfParallelism"],
            hash_len=32,
            type=Type.ID,
        )
    master_password_hash = hashlib.pbkdf2_hmac("sha256", master_key, password.encode(), 1, 32)

    def stretch(info):
        return HKDFExpand(algorithm=hashes.SHA256(), length=32, info=info).derive(master_key)

    user_key = os.urandom(64)
    private_key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
    public_der = private_key.public_key().public_bytes(
        serialization.Encoding.DER,
        serialization.PublicFormat.SubjectPublicKeyInfo,
    )
    private_der = private_key.private_bytes(
        serialization.Encoding.DER,
        serialization.PrivateFormat.PKCS8,
        serialization.NoEncryption(),
    )
    return {
        "masterPasswordHash": b64(master_password_hash),
        "key": enc_string(user_key, stretch(b"enc"), stretch(b"mac")),
        "keys": {
            "publicKey": b64(public_der),
            "encryptedPrivateKey": enc_string(private_der, user_key[:32], user_key[32:]),
        },
    }


def enc_string(plaintext, enc_key, mac_key):
    """An `EncString` of type 2: AES-256-CBC, then HMAC-SHA256 over IV and data."""
    from cryptography.hazmat.primitives import padding
    from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes

    iv = os.urandom(16)
    padder = padding.PKCS7(128).padder()
    padded = padder.update(plaintext) + padder.finalize()
    encryptor = Cipher(algorithms.AES(enc_key), modes.CBC(iv)).encryptor()
    data = encryptor.update(padded) + encryptor.finalize()
    mac = hmac.new(mac_key, iv + data, hashlib.sha256).digest()
    return f"2.{b64(iv)}|{b64(data)}|{b64(mac)}"


def write_certificates(directory):
    """A throwaway CA and a certificate for the `vaultwarden` host, kept for
    as long as the volume lives so a running server keeps matching them."""
    from cryptography import x509
    from cryptography.hazmat.primitives import hashes, serialization
    from cryptography.hazmat.primitives.asymmetric import ec
    from cryptography.x509.oid import ExtendedKeyUsageOID, NameOID

    if (directory / "cert.pem").exists():
        return
    now = datetime.datetime.now(datetime.timezone.utc)
    validity = datetime.timedelta(days=3650)

    def name(common_name):
        return x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, common_name)])

    ca_key = ec.generate_private_key(ec.SECP256R1())
    ca = (
        x509.CertificateBuilder()
        .subject_name(name("Keyguard import corpus CA"))
        .issuer_name(name("Keyguard import corpus CA"))
        .public_key(ca_key.public_key())
        .serial_number(x509.random_serial_number())
        .not_valid_before(now - datetime.timedelta(days=1))
        .not_valid_after(now + validity)
        .add_extension(x509.BasicConstraints(ca=True, path_length=0), critical=True)
        # Strict verifiers, such as Python 3.13 by default, need the key identifiers.
        .add_extension(x509.SubjectKeyIdentifier.from_public_key(ca_key.public_key()), critical=False)
        .add_extension(
            x509.KeyUsage(
                digital_signature=False, content_commitment=False, key_encipherment=False,
                data_encipherment=False, key_agreement=False, key_cert_sign=True,
                crl_sign=True, encipher_only=False, decipher_only=False,
            ),
            critical=True,
        )
        .sign(ca_key, hashes.SHA256())
    )
    key = ec.generate_private_key(ec.SECP256R1())
    cert = (
        x509.CertificateBuilder()
        .subject_name(name("vaultwarden"))
        .issuer_name(ca.subject)
        .public_key(key.public_key())
        .serial_number(x509.random_serial_number())
        .not_valid_before(now - datetime.timedelta(days=1))
        .not_valid_after(now + validity)
        .add_extension(x509.SubjectAlternativeName([x509.DNSName("vaultwarden")]), critical=False)
        .add_extension(x509.ExtendedKeyUsage([ExtendedKeyUsageOID.SERVER_AUTH]), critical=False)
        .add_extension(x509.SubjectKeyIdentifier.from_public_key(key.public_key()), critical=False)
        .add_extension(
            x509.AuthorityKeyIdentifier.from_issuer_public_key(ca_key.public_key()),
            critical=False,
        )
        .sign(ca_key, hashes.SHA256())
    )
    directory.mkdir(parents=True, exist_ok=True)
    (directory / "ca.pem").write_bytes(ca.public_bytes(serialization.Encoding.PEM))
    (directory / "key.pem").write_bytes(
        key.private_bytes(
            serialization.Encoding.PEM,
            serialization.PrivateFormat.PKCS8,
            serialization.NoEncryption(),
        ),
    )
    (directory / "cert.pem").write_bytes(cert.public_bytes(serialization.Encoding.PEM))
    log("Wrote the throwaway TLS certificates.")


def tls_context():
    return ssl.create_default_context(cafile=str(Path(os.environ["CERTS_DIR"]) / "ca.pem"))


def wait_until_alive(server, timeout=120):
    deadline = time.monotonic() + timeout
    while True:
        try:
            with urllib.request.urlopen(f"{server}/alive", timeout=5, context=tls_context()) as response:
                if response.status == 200:
                    return
        except (urllib.error.URLError, ConnectionError):
            pass
        if time.monotonic() > deadline:
            sys.exit(f"Vaultwarden at {server} did not come up within {timeout}s.")
        time.sleep(1)


def post_json(url, body):
    request = urllib.request.Request(
        url,
        data=json.dumps(body).encode(),
        headers={"Content-Type": "application/json", "Accept": "application/json"},
        method="POST",
    )
    try:
        with urllib.request.urlopen(request, timeout=30, context=tls_context()) as response:
            text = response.read().decode()
    except urllib.error.HTTPError as e:
        sys.exit(f"POST {url} failed with {e.code}: {e.read().decode()}")
    return json.loads(text) if text else None


#
# Bookkeeping
#


def write_checksums():
    lines = []
    for directory in EXPORT_DIRECTORIES:
        for file in sorted((CORPUS / directory).rglob("*")):
            if file.is_file():
                digest = hashlib.sha256(file.read_bytes()).hexdigest()
                lines.append(f"{digest}  {file.relative_to(CORPUS).as_posix()}")
    (CORPUS / "checksums.sha256").write_text("\n".join(lines) + "\n")
    log(f"Wrote {len(lines)} checksums.")


def write_export(resource, data):
    file = CORPUS / resource
    file.parent.mkdir(parents=True, exist_ok=True)
    file.write_bytes(data if isinstance(data, bytes) else data.encode())
    log(f"Wrote {resource}.")


def write_provenance(rows):
    """Replaces the rows of the given resources, keeping every other row."""
    file = CORPUS / "provenance.tsv"
    existing = []
    if file.exists():
        existing = [line.split("\t") for line in file.read_text().splitlines()[1:] if line.strip()]
    resources = {row[0] for row in rows}
    updated = sorted([row for row in existing if row[0] not in resources] + rows)
    lines = ["\t".join(PROVENANCE_HEADER)] + ["\t".join(row) for row in updated]
    file.write_text("\n".join(lines) + "\n")


def hand_back_to_host_user():
    """Inside the container the files are written as root; give them to the
    owner of the corpus directory, so the host can edit them."""
    if os.geteuid() != 0:
        return
    owner = CORPUS.stat()
    for path in [CORPUS, *CORPUS.rglob("*")]:
        os.chown(path, owner.st_uid, owner.st_gid)


def run(args, stdin=None, env=None, text=True):
    result = subprocess.run(
        args,
        input=stdin.encode() if isinstance(stdin, str) and not text else stdin,
        env=env,
        capture_output=True,
        text=text,
    )
    if result.returncode != 0:
        stderr = result.stderr if text else result.stderr.decode(errors="replace")
        sys.exit(f"{' '.join(args[:3])} failed with {result.returncode}: {stderr}")
    return result.stdout


def encode(value):
    return base64.b64encode(json.dumps(value).encode()).decode()


def b64(data):
    return base64.b64encode(data).decode()


def b64url(data):
    return base64.urlsafe_b64encode(data).decode().rstrip("=")


def today():
    return datetime.datetime.now(datetime.timezone.utc).date().isoformat()


def log(message):
    print(message, file=sys.stderr)


if __name__ == "__main__":
    main()
