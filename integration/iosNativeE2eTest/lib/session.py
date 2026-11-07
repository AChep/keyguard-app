"""The session folder: evidence and private state of one test session.

`e2e bootstrap` creates `build/ios-native-e2e/<name>/` and points
`build/ios-native-e2e/current` at it. Set E2E_SESSION to use another folder.
Files here can contain synthetic vault content and tokens: keep them local.
"""

import json
import os
import ssl
from pathlib import Path

HARNESS = Path(__file__).resolve().parents[1]
REPO = HARNESS.parents[1]
BUILD = REPO / "build" / "ios-native-e2e"
CURRENT = BUILD / "current"


def directory():
    value = os.environ.get("E2E_SESSION")
    if value:
        path = Path(value).expanduser().resolve()
    elif CURRENT.exists():
        path = CURRENT.resolve()
    else:
        raise SystemExit("No test session. Run `e2e bootstrap` or set E2E_SESSION.")
    path.mkdir(mode=0o700, parents=True, exist_ok=True)
    return path


def path(*parts):
    """A path inside the session folder; parent folders are created."""
    result = directory().joinpath(*parts)
    result.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
    return result


def tls(name):
    """A file of the session's test CA, server and client certificates."""
    return directory() / "tls" / name


def serve_tls(server, certificate=None, private_key=None, require_client_cert=False):
    """Wrap an HTTP server's socket in TLS with the session's test server certificate.

    `require_client_cert` accepts only clients with a certificate from the test CA.
    """
    context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    context.load_cert_chain(certificate or tls("server.pem"), private_key or tls("server.key"))
    if require_client_cert:
        context.load_verify_locations(cafile=str(tls("ca.pem")))
        context.verify_mode = ssl.CERT_REQUIRED
    server.socket = context.wrap_socket(server.socket, server_side=True)
    return server


def read_json(file):
    return json.loads(Path(file).read_text())


def write_json(file, value, exclusive=False):
    """Write JSON readable only by the owner. `exclusive` refuses to overwrite."""
    file = Path(file)
    file.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
    flags = os.O_WRONLY | os.O_CREAT | (os.O_EXCL if exclusive else os.O_TRUNC)
    with os.fdopen(os.open(file, flags, 0o600), "w") as output:
        json.dump(value, output, indent=2, ensure_ascii=False)
    file.chmod(0o600)
    return file


def append_jsonl(file, value):
    file = Path(file)
    file.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
    with os.fdopen(os.open(file, os.O_WRONLY | os.O_CREAT | os.O_APPEND, 0o600), "a") as output:
        output.write(json.dumps(value) + "\n")
