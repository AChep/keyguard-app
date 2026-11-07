"""Fixed synthetic servers, accounts and loopback ports of the harness.

Every value here is synthetic. Server-generated IDs are never stored: scripts
log in with these credentials and read IDs from the server.
"""

from dataclasses import dataclass, field

# The app under test: a dedicated bundle ID and App Group, see README.md.
APP_BUNDLE_ID = "com.artemchep.keyguard.ios.e2e"
APP_GROUP_ID = "group.com.artemchep.keyguard.ios.e2e"

# Local app vault created by `e2e baseline`.
LOCAL_PASSWORD = "Local-E2e-2026!"

# Password of the PKCS#12 client identities that bootstrap and fixtures create.
P12_PASSWORD = "P12-E2e-2026!"

# Loopback ports of the fixture processes. Vaultwarden ports are in SERVERS.
PORTS = {
    "driver": 8233,
    "wordlist": 8234,
    "webdav": 8235,
    "s3": 8236,
    "s3-tls": 8237,
    "oidc": 8238,
    "mtls": 8239,
    "autofill-web": 8240,
    "download-transport": 8241,
    "smtp": 8242,
    "smtp-api": 8243,
    "mail-tls": 8245,
    "simplelogin": 8246,
    "otp-web": 8247,
    "passkey-errors-web": 8248,
    "gpg-keyserver": 8251,
    "passkey-prf-web": 8252,
    "icon-network": 8253,
    "icon-network-alternate": 8254,
    "login-branch-http": 8255,
    "login-branch-tls": 8256,
}


@dataclass(frozen=True)
class Server:
    key: str
    port: int
    # DOMAIN of the server: the URL the app and the web vault use.
    public: str = ""
    web_vault: bool = False
    env: dict = field(default_factory=dict)

    @property
    def url(self):
        """Direct loopback API URL, used by the oracles."""
        return f"http://127.0.0.1:{self.port}"

    @property
    def domain(self):
        return self.public or self.url


SERVERS = {
    server.key: server for server in (
        Server("main", 8222),
        # SSO: the app reaches it through `e2e sso_tls` on 8224.
        Server("sso", 8223, public="https://127.0.0.1:8224", web_vault=True, env={
            "SSO_ENABLED": "true",
            "SSO_ONLY": "false",
            "SSO_AUTHORITY": f"http://host.docker.internal:{PORTS['oidc']}",
            "SSO_CLIENT_ID": "keyguard-native-e2e",
            "SSO_CLIENT_SECRET": "Oidc-Client-E2e-2026!",
            "SSO_SIGNUPS_ALLOWED": "false",
            "SSO_SIGNUPS_MATCH_EMAIL": "true",
            "SSO_AUTH_ONLY_NOT_SESSION": "true",
        }),
        # Mail: delivers only to `e2e smtp_sink`.
        Server("mail", 8244, web_vault=True, env={
            "SMTP_HOST": "host.docker.internal",
            "SMTP_PORT": str(PORTS["smtp"]),
            "SMTP_SECURITY": "off",
            "SMTP_FROM": "keyguard@keyguard.test",
            "SMTP_FROM_NAME": "Keyguard Native E2E",
            "SMTP_EMBED_IMAGES": "false",
            "SMTP_DEBUG": "false",
            "SMTP_TIMEOUT": "10",
        }),
    )
}


@dataclass(frozen=True)
class Account:
    key: str
    email: str
    password: str
    name: str
    server: Server
    # `bw seed` options, or None for an empty vault.
    seed: tuple = None
    # Authenticator 2FA, enabled by `bw totp` after seeding.
    totp: bool = False
    # Name of an organization this account owns, created by `bw org`.
    organization: str = None


def _account(key, email, password, name, server="main", **kwargs):
    return Account(key, email, password, name, SERVERS[server], **kwargs)


ACCOUNTS = {
    account.key: account for account in (
        _account("main", "ios-e2e@keyguard.test", "IosE2e-Test-2026!", "iOS E2E",
                 seed=("--folders", "2", "--logins", "4", "--notes", "2",
                       "--attachment", "--send", "text")),
        _account("org", "ios-org@keyguard.test", "Ios-Org-E2e-2026!", "iOS E2E Organization",
                 organization="iOS E2E Shared Vault"),
        _account("org-member", "ios-org-member@keyguard.test", "Ios-Org-Member-E2e-2026!",
                 "iOS E2E Organization Member"),
        _account("2fa", "ios-2fa@keyguard.test", "Ios-2fa-E2e-2026!", "iOS E2E Two Factor",
                 seed=("--folders", "1", "--logins", "1", "--notes", "0"), totp=True),
        # Its domain is served by `e2e login_branch_fixture` as local-only DNS-SD records.
        _account("login-branches", "ios-login-branches@kg-ios-login-branches.local",
                 "Ios-Login-Branches-E2e-2026!", "iOS E2E Login Branches",
                 seed=("--folders", "0", "--logins", "1", "--notes", "0")),
        _account("sso", "ios-sso@keyguard.test", "Ios-Sso-E2e-2026!", "iOS E2E SSO", "sso"),
        _account("download", "ios-download@keyguard.test", "Ios-Download-E2e-2026!",
                 "iOS E2E Download", "sso",
                 seed=("--folders", "0", "--logins", "1", "--notes", "0", "--attachment")),
        _account("backup", "ios-backup@keyguard.test", "Ios-Backup-E2e-2026!",
                 "iOS E2E Backup", "sso"),
        _account("import", "ios-import@keyguard.test", "Ios-Import-E2e-2026!",
                 "iOS E2E Import", "sso"),
        _account("send", "ios-send@keyguard.test", "Ios-Send-E2e-2026!", "iOS E2E Send", "mail"),
    )
}


def account(key):
    try:
        return ACCOUNTS[key]
    except KeyError:
        raise SystemExit(f"Unknown account {key!r}; known: {', '.join(ACCOUNTS)}") from None


# The Login that `e2e baseline` creates and `e2e smoke` checks.
BASELINE_LOGIN = {
    "name": "iOS E2E Login",
    "username": "ios-updated",
    "password": "E2e-Entry-2026!",
    "uri": "https://ios-e2e.example/login",
}
