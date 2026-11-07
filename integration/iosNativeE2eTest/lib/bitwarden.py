"""Independent Bitwarden client for the synthetic PBKDF2 accounts.

Mirrors VaultwardenAccounts.kt in integration/bitwardenE2eTest. Uses Python's
standard library and OpenSSL, never Keyguard's codecs, database or bridge.
Requests go only to the account's loopback server, without proxies or
redirects. Tokens and keys stay in memory; callers must not print them.
Send recipient helpers perform real accesses, which increment access counts.
"""

import base64
import hashlib
import hmac
import json
import os
import subprocess
import tempfile
import urllib.error
import urllib.parse
import urllib.request
from functools import cached_property
from pathlib import Path

# Match BitwardenPersona.CLIENT_VERSION. Vaultwarden omits newer item types
# when a sync request presents itself as a legacy client without this header.
CLIENT_HEADERS = {"Bitwarden-Client-Version": "2026.2.1"}


class ServerError(Exception):
    def __init__(self, status, body):
        super().__init__(f"HTTP {status}: {body}")
        self.status = status
        self.body = body


class _NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *_args, **_kwargs):
        return None


OPENER = urllib.request.build_opener(urllib.request.ProxyHandler({}), _NoRedirect())


def call(base, path, body=None, token=None, method=None, form=False, headers=None):
    """One loopback request. Returns (status, parsed body); never raises on HTTP errors.

    A dict body is sent as JSON, or as a form with `form`; bytes are sent as is.
    """
    parsed = urllib.parse.urlparse(base)
    if parsed.hostname not in ("127.0.0.1", "localhost"):
        raise ValueError("Only loopback servers are allowed")
    if not path.startswith("/") or path.startswith("//") or "://" in path:
        raise ValueError("Only paths on the fixed loopback server are allowed")
    headers = {**CLIENT_HEADERS, **(headers or {})}
    if token:
        headers["Authorization"] = "Bearer " + token
    data = None
    if isinstance(body, bytes):
        data = body
    elif body is not None:
        data = (urllib.parse.urlencode(body) if form else json.dumps(body)).encode()
        headers["Content-Type"] = (
            "application/x-www-form-urlencoded" if form else "application/json"
        )
    req = urllib.request.Request(base + path, data, headers, method=method)
    try:
        response = OPENER.open(req, timeout=15)
    except urllib.error.HTTPError as error:
        response = error
    with response:
        content = response.read()
    if not content:
        return response.status, None
    try:
        return response.status, json.loads(content)
    except json.JSONDecodeError:
        return response.status, content.decode(errors="replace")


def request(base, path, body=None, token=None, method=None, form=False):
    """Like call(), but raises ServerError on a non-2xx status and returns the body."""
    status, result = call(base, path, body, token, method, form)
    if not 200 <= status < 300:
        raise ServerError(status, result)
    return result


# Crypto ------------------------------------------------------------------------


def decrypt(value, key):
    """Decrypt a type-2 (AES-256-CBC + HMAC-SHA256) cipher string; None stays None."""
    if not value:
        return None
    kind, payload = value.split(".", 1)
    if kind != "2":
        raise ValueError(f"Unexpected cipher type: {kind}")
    iv, encrypted, mac = map(base64.b64decode, payload.split("|"))
    expected = hmac.digest(key[32:], iv + encrypted, "sha256")
    if not hmac.compare_digest(mac, expected):
        raise ValueError("Cipher MAC mismatch")
    return subprocess.run(
        ["openssl", "enc", "-aes-256-cbc", "-d", "-K", key[:32].hex(), "-iv", iv.hex()],
        input=encrypted, capture_output=True, check=True,
    ).stdout


def encrypt(value, key):
    """Encrypt str or bytes into a type-2 cipher string."""
    data = value.encode() if isinstance(value, str) else value
    iv = os.urandom(16)
    encrypted = subprocess.run(
        ["openssl", "enc", "-aes-256-cbc", "-K", key[:32].hex(), "-iv", iv.hex()],
        input=data, capture_output=True, check=True,
    ).stdout
    mac = hmac.digest(key[32:], iv + encrypted, "sha256")
    return "2." + "|".join(base64.b64encode(part).decode() for part in (iv, encrypted, mac))


def _rsa_oaep_sha1(mode, der, data, key_flags):
    with tempfile.TemporaryDirectory(prefix="keyguard-e2e-rsa-") as directory:
        path = Path(directory) / "key.der"
        path.write_bytes(der)
        path.chmod(0o600)
        return subprocess.run(
            ["openssl", "pkeyutl", mode, *key_flags, "-inkey", str(path), "-keyform", "DER",
             "-pkeyopt", "rsa_padding_mode:oaep", "-pkeyopt", "rsa_oaep_md:sha1",
             "-pkeyopt", "rsa_mgf1_md:sha1"],
            input=data, capture_output=True, check=True,
        ).stdout


def rsa_unwrap(value, private_key):
    """Decrypt a type-4 (RSA-OAEP/SHA-1) cipher string with a DER private key."""
    kind, payload = value.split(".", 1)
    if kind != "4":
        raise ValueError("Expected an RSA-OAEP/SHA-1 key")
    return _rsa_oaep_sha1("-decrypt", private_key, base64.b64decode(payload), [])


def rsa_wrap(data, public_key):
    """Encrypt bytes for a DER public key into a type-4 cipher string."""
    return "4." + base64.b64encode(_rsa_oaep_sha1("-encrypt", public_key, data, ["-pubin"])).decode()


def master_key(account):
    return hashlib.pbkdf2_hmac(
        "sha256", account.password.encode(), account.email.encode(), 600_000, 32)


def password_hash(account):
    return base64.b64encode(hashlib.pbkdf2_hmac(
        "sha256", master_key(account), account.password.encode(), 1, 32)).decode()


def _stretch(master):
    return hmac.digest(master, b"enc\x01", "sha256") + hmac.digest(master, b"mac\x01", "sha256")


# Sessions ----------------------------------------------------------------------


def token_request(account, device, extra=None):
    """Password grant for the account. Returns (status, body) without raising."""
    body = {
        "grant_type": "password", "username": account.email, "password": password_hash(account),
        "scope": "api offline_access", "client_id": "desktop", "deviceType": "8",
        "deviceIdentifier": device, "deviceName": device, **(extra or {}),
    }
    return call(account.server.url, "/identity/connect/token", body, form=True)


class Session:
    """An authenticated oracle session for one synthetic account."""

    def __init__(self, account, device=None):
        self.account = account
        self.server = account.server.url
        status, login = token_request(account, device or f"native-e2e-oracle-{account.key}")
        if status != 200:
            raise ServerError(status, login)
        self.token = login["access_token"]
        self.user_key = decrypt(login["Key"], _stretch(master_key(account)))
        self.sync = self.request("/api/sync")
        if self.profile["email"].lower() != account.email:
            raise ValueError("Unexpected fixture account")

    @property
    def profile(self):
        return self.sync["profile"]

    @property
    def account_id(self):
        return self.profile["id"]

    def call(self, path, body=None, method=None, form=False):
        return call(self.server, path, body, self.token, method, form)

    def request(self, path, body=None, method=None, form=False):
        return request(self.server, path, body, self.token, method, form)

    def refresh(self):
        self.sync = self.request("/api/sync")
        self.__dict__.pop("org_keys", None)
        return self.sync

    @cached_property
    def private_key(self):
        return decrypt(self.profile["privateKey"], self.user_key)

    @cached_property
    def org_keys(self):
        keys = {}
        for item in self.profile.get("organizations") or []:
            key = rsa_unwrap(item["key"], self.private_key)
            if len(key) != 64:
                raise ValueError("Unexpected organization key length")
            keys[item["id"]] = key
        return keys

    def organization_id(self, name):
        matches = [item["id"] for item in self.profile.get("organizations") or []
                   if item.get("name") == name]
        if len(matches) != 1:
            raise ValueError(f"Expected one organization named {name!r}")
        return matches[0]

    def cipher_key(self, item):
        owner = self.org_keys[item["organizationId"]] if item.get("organizationId") else self.user_key
        return decrypt(item.get("key"), owner) or owner

    def cipher(self, ident):
        """Raw server cipher and its decryption key."""
        raw = self.request("/api/ciphers/" + ident)
        return raw, self.cipher_key(raw)

    def ciphers(self):
        return [decode_cipher(item, self.cipher_key(item)) for item in self.sync["ciphers"]]

    def find(self, name):
        """The ID of the one live cipher with this decrypted name."""
        matches = [item["id"] for item in self.ciphers()
                   if item["name"] == name and not item["deletedDate"]]
        if len(matches) != 1:
            raise ValueError(f"Expected one item named {name!r}, found {len(matches)}")
        return matches[0]


def decode_cipher(item, key):
    text = lambda value: decrypt(value, key).decode() if value else None
    login = item.get("login") or {}
    card = item.get("card") or {}
    identity = item.get("identity") or {}
    ssh_key = item.get("sshKey") or {}
    return {
        "id": item["id"], "type": item["type"], "name": text(item.get("name")),
        "notes": text(item.get("notes")), "deletedDate": item.get("deletedDate"),
        "favorite": item.get("favorite"), "folderId": item.get("folderId"),
        "username": text(login.get("username")), "password": text(login.get("password")),
        "totp": text(login.get("totp")),
        "uris": [text(uri.get("uri")) for uri in login.get("uris") or []],
        "cardNumber": text(card.get("number")),
        "card": {field: text(value) for field, value in card.items() if isinstance(value, str)},
        "identity": {field: text(value) for field, value in identity.items() if isinstance(value, str)},
        "sshKey": {field: text(value) for field, value in ssh_key.items() if isinstance(value, str)},
        "fields": [{"name": text(field.get("name")), "value": text(field.get("value")),
                    "type": field.get("type"), "linkedId": field.get("linkedId")}
                   for field in item.get("fields") or []],
        "reprompt": item.get("reprompt"),
        "attachments": [{"id": attachment["id"], "fileName": text(attachment.get("fileName")),
                         "size": attachment.get("size")}
                        for attachment in item.get("attachments") or []],
    }


def snapshot(account):
    """Every cipher of the account, independently decrypted."""
    return Session(account).ciphers()


def others(items, ident):
    """Items other than `ident`, sorted by ID, to check that a write touched only one item."""
    return sorted((item for item in items if item["id"] != ident), key=lambda item: item["id"])


# Sends -------------------------------------------------------------------------


def send_key(material):
    extracted = hmac.digest(b"bitwarden-send", material, "sha256")
    first = hmac.digest(extracted, b"send\x01", "sha256")
    return first + hmac.digest(extracted, first + b"send\x02", "sha256")


def decode_send(item, user_key):
    key = send_key(decrypt(item["key"], user_key))
    text = lambda value: decrypt(value, key).decode() if value else None
    return {
        **{field: item.get(field) for field in (
            "id", "accessId", "type", "accessCount", "maxAccessCount", "expirationDate",
            "deletionDate", "disabled", "hideEmail", "authType",
        )},
        "name": text(item.get("name")), "notes": text(item.get("notes")),
        "text": text((item.get("text") or {}).get("text")),
        "hidden": (item.get("text") or {}).get("hidden"),
        "fileName": text((item.get("file") or {}).get("fileName")),
        "fileId": (item.get("file") or {}).get("id"),
        "hasPassword": bool(item.get("password")),
    }


def send_snapshot(account):
    session = Session(account)
    return [decode_send(item, session.user_key) for item in session.sync.get("sends") or []]


def access_send(account, name, password=None):
    """Open a synthetic Send as a recipient; a successful access increments its count."""
    session = Session(account)
    item = next(item for item in session.sync.get("sends") or []
                if decode_send(item, session.user_key)["name"] == name)
    material = decrypt(item["key"], session.user_key)
    encoded = base64.urlsafe_b64encode(material).decode().rstrip("=")
    return access_link(account.server, f'{account.server.url}/#/send/{item["accessId"]}/{encoded}',
                       password)


def access_link(server, url, password=None):
    """Read a copied native share link without authenticating as the owner."""
    parsed = urllib.parse.urlparse(url)
    if f"{parsed.scheme}://{parsed.netloc}" not in (server.url, server.domain):
        raise ValueError("Only the dedicated local server is supported")
    parts = parsed.fragment.split("/")
    if len(parts) != 4 or parts[:2] != ["", "send"]:
        raise ValueError("Unexpected Send link")
    material = base64.urlsafe_b64decode(parts[3] + "=" * (-len(parts[3]) % 4))
    if len(material) != 16:
        raise ValueError("Unexpected Send key material")
    form = {"grant_type": "send_access", "client_id": "keyguard-e2e", "send_id": parts[2]}
    if password is not None:
        form["password_hash_b64"] = base64.b64encode(
            hashlib.pbkdf2_hmac("sha256", password.encode(), material, 100_000, 32)
        ).decode()
    token = request(server.url, "/identity/connect/token", form, form=True)["access_token"]
    accessed = request(server.url, "/api/sends/access", b"", token)
    key = send_key(material)
    content = (accessed.get("text") or {}).get("text")
    result = {"name": decrypt(accessed["name"], key).decode(),
              "text": decrypt(content, key).decode() if content else None,
              "hidden": (accessed.get("text") or {}).get("hidden")}
    if file := accessed.get("file"):
        download_url = request(server.url, "/api/sends/access/file/" + file["id"], b"", token)["url"]
        download = urllib.parse.urlparse(download_url)
        if f"{download.scheme}://{download.netloc}" not in (server.url, server.domain):
            raise ValueError("Unexpected file download server")
        with OPENER.open(server.url + download.path + (
                "?" + download.query if download.query else ""), timeout=15) as response:
            frame = response.read()
        if len(frame) <= 49 or frame[0] != 2:
            raise ValueError("Unexpected encrypted file frame")
        encoded = [base64.b64encode(part).decode() for part in (frame[1:17], frame[49:], frame[17:49])]
        plaintext = decrypt("2." + "|".join(encoded), key)
        result.update(fileName=decrypt(file["fileName"], key).decode(),
                      size=len(plaintext), sha256=hashlib.sha256(plaintext).hexdigest())
    return result
