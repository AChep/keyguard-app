"""Handle CLA events using GitHub APIs only; never fetch or execute PR code.

Setup: create CLA_REPOSITORY_TOKEN in keyguard-app with a fine-grained PAT
granting Contents read/write on AChep/keyguard-cla only. The first signature
creates version1/cla.json on master, including in an empty repository. Existing
local signatures are not imported. Require the commit status named "CLA" in
repository rules; the event-handler job itself is not the CLA decision.

Keep version1 tied to SAPCLA.md. A changed agreement needs a new registry path
and signing phrase, so old comments cannot sign a new agreement implicitly.
"""

import base64
import json
import os
import re
import sys
import time
from pathlib import Path
from urllib.error import HTTPError
from urllib.parse import urlencode
from urllib.request import HTTPRedirectHandler, Request, build_opener


REPOSITORY = "AChep/keyguard-app"
SIGNATURE_REPOSITORY = "AChep/keyguard-cla"
SIGNATURE_PATH = "version1/cla.json"
SIGNATURE_BRANCH = "master"
DOCUMENT_URL = f"https://github.com/{REPOSITORY}/blob/master/SAPCLA.md"
SIGNING_PHRASE = "I have read the CLA Document and I hereby sign the CLA"
COMMENT_MARKER = "<!-- keyguard-cla:version1 -->"
STATUS_CONTEXT = "CLA"
BOT_LOGIN = "github-actions[bot]"
# Exact GitHub identities only; a username prefix such as bot* is not proof
# that the account is a bot.
EXEMPT_LOGINS = {"dependabot[bot]", BOT_LOGIN}
LOGIN_PATTERN = re.compile(r"[a-zA-Z0-9][a-zA-Z0-9-]{0,38}(?:\[bot\])?\Z")

CONTRIBUTORS_QUERY = """
query($owner: String!, $name: String!, $number: Int!, $cursor: String) {
  repository(owner: $owner, name: $name) {
    pullRequest(number: $number) {
      headRefOid
      commits(first: 100, after: $cursor) {
        totalCount
        nodes {
          commit {
            authors(first: 100) {
              nodes { user { databaseId login } }
              pageInfo { hasNextPage }
            }
          }
        }
        pageInfo { hasNextPage endCursor }
      }
    }
  }
}
"""


class ClaError(Exception):
    pass


class HeadChanged(ClaError):
    pass


class ApiError(ClaError):
    def __init__(self, status, method, path):
        self.status = status
        super().__init__(f"GitHub API {method} {path} returned HTTP {status}")


class NoRedirects(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        # Never forward either credential to a redirect destination.
        return None


class GitHub:
    def __init__(self, token, credential_name):
        self.token = token
        self.credential_name = credential_name
        self.opener = build_opener(NoRedirects())

    def request(self, method, path, data=None):
        if not self.token:
            raise ClaError(f"Missing {self.credential_name}")
        if not path.startswith("/") or path.startswith("//"):
            raise ClaError("Expected a GitHub API path")
        request = Request(
            "https://api.github.com" + path,
            data=json.dumps(data).encode("utf-8") if data is not None else None,
            headers={
                "Authorization": f"Bearer {self.token}",
                "Accept": "application/vnd.github+json",
                "Content-Type": "application/json",
                "X-GitHub-Api-Version": "2022-11-28",
                "User-Agent": "keyguard-cla",
            },
            method=method,
        )
        try:
            with self.opener.open(request, timeout=30) as response:
                body = response.read()
        except HTTPError as error:
            # API response bodies can contain untrusted input. Do not log them.
            status = error.code
            error.close()
            raise ApiError(status, method, path) from None
        return json.loads(body) if body else None

    def paginate(self, path):
        for page in range(1, 1001):
            items = self.request("GET", f"{path}?per_page=100&page={page}")
            if not isinstance(items, list):
                raise ClaError("Expected a paginated GitHub API list")
            yield from items
            if len(items) < 100:
                return
        raise ClaError("GitHub API pagination limit exceeded")


def user_identity(user):
    if not isinstance(user, dict):
        return None
    user_id = user.get("databaseId", user.get("id"))
    login = user.get("login")
    if type(user_id) is not int or user_id <= 0:
        return None
    if not isinstance(login, str) or not LOGIN_PATTERN.fullmatch(login):
        return None
    return user_id, login


def contributors(api, number, head):
    """Include every commit author/co-author, without a committer fallback."""
    owner, name = REPOSITORY.split("/")
    cursor = None
    seen_cursors = set()
    people = {}
    unknown = False
    count = 0
    while True:
        result = api.request("POST", "/graphql", {
            "query": CONTRIBUTORS_QUERY,
            "variables": {"owner": owner, "name": name, "number": number, "cursor": cursor},
        })
        if result.get("errors"):
            raise ClaError("GitHub could not enumerate all PR authors")
        pr = result["data"]["repository"]["pullRequest"]
        if pr["headRefOid"] != head:
            raise HeadChanged("PR head changed while reading its authors")
        commits = pr["commits"]
        for node in commits["nodes"]:
            count += 1
            authors = node["commit"]["authors"]
            if authors["pageInfo"]["hasNextPage"]:
                raise ClaError("A commit has more than 100 authors; manual review required")
            if not authors["nodes"]:
                unknown = True
            for author in authors["nodes"]:
                identity = user_identity(author["user"])
                if identity is None:
                    unknown = True
                elif identity[1].lower() not in EXEMPT_LOGINS:
                    people[identity[0]] = identity[1]
        info = commits["pageInfo"]
        if not info["hasNextPage"]:
            if not count or count != commits["totalCount"]:
                raise ClaError("GitHub returned an incomplete PR commit list")
            return people, unknown
        cursor = info["endCursor"]
        if not cursor or cursor in seen_cursors:
            raise ClaError("GitHub returned an invalid commit page cursor")
        seen_cursors.add(cursor)


def signing_records(comments, people, number, repository_id):
    records = {}
    for comment in comments:
        if comment.get("body") != SIGNING_PHRASE:
            continue
        identity = user_identity(comment.get("user"))
        if identity is None or identity[0] not in people:
            continue
        if comment["user"].get("type") != "User":
            continue
        comment_id = comment.get("id")
        created_at = comment.get("created_at")
        if type(comment_id) is not int or not isinstance(created_at, str):
            raise ClaError("GitHub returned an incomplete signing comment")
        user_id, login = identity
        records.setdefault(user_id, {
            "name": login,
            "id": user_id,
            "comment_id": comment_id,
            "created_at": created_at,
            "repoId": repository_id,
            "pullRequestNo": number,
        })
    return list(records.values())


class SignatureStore:
    def __init__(self, api):
        self.api = api
        self.path = f"/repos/{SIGNATURE_REPOSITORY}/contents/{SIGNATURE_PATH}"

    def read(self):
        try:
            result = self.api.request("GET", self.path + "?" + urlencode({"ref": SIGNATURE_BRANCH}))
        except ApiError as error:
            if error.status == 404:
                # Missing data never grants a signature. The first signer can
                # initialize the file; authentication/write failures still fail.
                return {"signedContributors": []}, None
            raise
        if result.get("encoding") != "base64" or result.get("type") != "file":
            raise ClaError("Expected a base64-encoded signature registry file")
        registry = json.loads(base64.b64decode(result["content"]).decode("utf-8"))
        if not isinstance(registry, dict) or not isinstance(registry.get("signedContributors"), list):
            raise ClaError("Invalid signature registry")
        for record in registry["signedContributors"]:
            if not isinstance(record, dict) or type(record.get("id")) is not int or record["id"] <= 0:
                raise ClaError("Invalid contributor in signature registry")
        return registry, result["sha"]

    def merge(self, records):
        for attempt in range(5):
            registry, sha = self.read()
            signed = {record["id"] for record in registry["signedContributors"]}
            additions = [record for record in records if record["id"] not in signed]
            if not additions:
                return signed
            registry["signedContributors"].extend(additions)
            data = {
                "message": "chore(cla): record contributor signatures",
                "content": base64.b64encode((json.dumps(registry, indent=2) + "\n").encode()).decode(),
                "branch": SIGNATURE_BRANCH,
            }
            if sha is not None:
                data["sha"] = sha
            try:
                self.api.request("PUT", self.path, data)
                return signed | {record["id"] for record in additions}
            except ApiError as error:
                # A concurrent writer can change the SHA or create the file.
                if error.status not in (409, 422) or attempt == 4:
                    raise
                time.sleep(2 ** attempt)
        raise ClaError("Could not update signature registry")


def comment_body(people, signed, unknown):
    missing = sorted(login for user_id, login in people.items() if user_id not in signed)
    lines = [COMMENT_MARKER, "## Contributor License Agreement", ""]
    if not missing and not unknown:
        lines.append("All identified contributors have signed the CLA. Thank you!")
    else:
        lines.extend([
            f"Please read the [CLA]({DOCUMENT_URL}) and sign by posting this exact comment:",
            "", f"> {SIGNING_PHRASE}", "",
        ])
        if missing:
            lines.append("Waiting for: " + ", ".join(f"@{login}" for login in missing) + ".")
        if unknown:
            lines.append(
                "Some commit authors could not be matched to GitHub accounts. "
                "Associate their commit email addresses with their accounts, then comment `recheck`."
            )
        lines.extend(["", "Each author signs for themselves. Comment `recheck` to check again."])
    return "\n".join(lines)


class ClaCheck:
    def __init__(self, api, store, target_url):
        self.api = api
        self.store = store
        self.target_url = target_url
        self.repo_path = f"/repos/{REPOSITORY}"

    def status(self, head, state, description):
        self.api.request("POST", f"{self.repo_path}/statuses/{head}", {
            "state": state, "context": STATUS_CONTEXT,
            "description": description, "target_url": self.target_url,
        })

    def run(self, number, repository_id, action):
        for _ in range(3):
            pr = self.api.request("GET", f"{self.repo_path}/pulls/{number}")
            if pr["state"] != "open":
                if action == "closed" and pr.get("merged") and not pr.get("locked"):
                    self.api.request("PUT", f"{self.repo_path}/issues/{number}/lock", {"lock_reason": "resolved"})
                return
            head = pr["head"]["sha"]
            self.status(head, "pending", "Checking contributor signatures")
            try:
                people, unknown = contributors(self.api, number, head)
                comments = list(self.api.paginate(f"{self.repo_path}/issues/{number}/comments"))
                records = signing_records(comments, people, number, repository_id)
                signed = self.store.merge(records)
                latest = self.api.request("GET", f"{self.repo_path}/pulls/{number}")
                if latest["state"] != "open":
                    return
                if latest["head"]["sha"] != head:
                    raise HeadChanged("PR head changed while checking signatures")
                body = comment_body(people, signed, unknown)
                existing = next((comment for comment in comments
                                 if comment.get("user", {}).get("login") == BOT_LOGIN
                                 and comment["user"].get("type") == "Bot"
                                 and (comment.get("body") or "").startswith(COMMENT_MARKER)), None)
                if existing is None:
                    self.api.request("POST", f"{self.repo_path}/issues/{number}/comments", {"body": body})
                elif existing["body"] != body:
                    self.api.request("PATCH", f"{self.repo_path}/issues/comments/{existing['id']}", {"body": body})
                passed = not unknown and people.keys() <= signed
                self.status(head, "success" if passed else "failure",
                            "All contributors have signed the CLA" if passed else "Contributor signatures or author identification required")
                return
            except HeadChanged:
                # Never apply an assessment of an old commit list to a new SHA.
                continue
            except Exception:
                self.status(head, "error", "CLA check failed; inspect the workflow run and retry")
                raise
        raise ClaError("PR kept changing; comment recheck to retry")


def event_pull_request(event_name, event):
    if event.get("repository", {}).get("full_name") != REPOSITORY:
        raise ClaError("Unexpected event repository")
    if event_name == "pull_request_target":
        number = event["pull_request"]["number"]
    elif event_name == "issue_comment":
        if event.get("action") != "created" or not event.get("issue", {}).get("pull_request"):
            return None
        if event.get("comment", {}).get("body") not in (SIGNING_PHRASE, "recheck"):
            return None
        number = event["issue"]["number"]
    else:
        raise ClaError("Unsupported CLA event")
    if type(number) is not int or number <= 0:
        raise ClaError("Invalid PR number")
    return number


def main():
    event = json.loads(Path(os.environ["GITHUB_EVENT_PATH"]).read_text(encoding="utf-8"))
    number = event_pull_request(os.environ["GITHUB_EVENT_NAME"], event)
    if number is None:
        return
    api = GitHub(os.environ.get("GITHUB_TOKEN"), "GITHUB_TOKEN")
    store = SignatureStore(GitHub(os.environ.get("CLA_REPOSITORY_TOKEN"), "CLA_REPOSITORY_TOKEN"))
    target_url = f"https://github.com/{REPOSITORY}/actions/runs/{os.environ['GITHUB_RUN_ID']}"
    ClaCheck(api, store, target_url).run(number, event["repository"]["id"], event["action"])


if __name__ == "__main__":
    try:
        main()
    except Exception as error:
        print(f"CLA check failed: {error}", file=sys.stderr)
        sys.exit(1)
