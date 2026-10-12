import base64
import copy
import io
import json
import unittest
from unittest.mock import Mock, patch
from urllib.error import HTTPError

import cla


HEAD = "a" * 40
OTHER_HEAD = "b" * 40
ALICE = {"id": 10, "login": "alice", "type": "User"}
BOB = {"id": 20, "login": "bob", "type": "User"}


def signing_comment(user=ALICE, comment_id=1, body=cla.SIGNING_PHRASE):
    return {
        "id": comment_id, "user": user, "body": body,
        "created_at": "2026-10-12T12:00:00Z",
    }


def commit(*users, overflow=False):
    return {"commit": {"authors": {
        "nodes": [{"user": None if user is None else {
            "databaseId": user["id"], "login": user["login"],
        }} for user in users],
        "pageInfo": {"hasNextPage": overflow},
    }}}


def authors_page(nodes=None, head=HEAD, cursor=None, total=None):
    if nodes is None:
        nodes = [commit(ALICE)]
    return {"data": {"repository": {"pullRequest": {
        "headRefOid": head,
        "commits": {
            "totalCount": len(nodes) if total is None else total,
            "nodes": nodes,
            "pageInfo": {"hasNextPage": cursor is not None, "endCursor": cursor},
        },
    }}}}


def registry_response(records, sha="registry-sha"):
    return {
        "type": "file", "encoding": "base64", "sha": sha,
        "content": base64.b64encode(json.dumps({"signedContributors": records}).encode()).decode(),
    }


class EventTest(unittest.TestCase):
    def event(self, **fields):
        return {"repository": {"full_name": cla.REPOSITORY, "id": 42}, **fields}

    def test_pr_number_comes_from_payload(self):
        event = self.event(pull_request={"number": 123})
        self.assertEqual(cla.event_pull_request("pull_request_target", event), 123)

    def test_signing_and_recheck_require_a_pr_comment(self):
        for body in (cla.SIGNING_PHRASE, "recheck"):
            event = self.event(action="created", issue={"number": 123, "pull_request": {"url": "unused"}}, comment={"body": body})
            self.assertEqual(cla.event_pull_request("issue_comment", event), 123)
            del event["issue"]["pull_request"]
            self.assertIsNone(cla.event_pull_request("issue_comment", event))

    def test_unrelated_or_edited_comments_are_ignored(self):
        for action, body in (("created", "$(malicious)"), ("edited", cla.SIGNING_PHRASE)):
            event = self.event(action=action, issue={"number": 123, "pull_request": {"url": "unused"}}, comment={"body": body})
            self.assertIsNone(cla.event_pull_request("issue_comment", event))

    def test_unexpected_repository_and_invalid_numbers_fail(self):
        with self.assertRaises(cla.ClaError):
            cla.event_pull_request("pull_request_target", {"repository": {"full_name": "attacker/fork"}})
        for number in (True, 0, -1, "123/../../contents/file"):
            with self.assertRaises(cla.ClaError):
                cla.event_pull_request("pull_request_target", self.event(pull_request={"number": number}))


class ContributorsTest(unittest.TestCase):
    def test_all_commit_pages_and_coauthors_are_checked(self):
        api = Mock()
        api.request.side_effect = [
            authors_page([commit(ALICE)] * 100, cursor="next", total=101),
            authors_page([commit(ALICE, BOB)], total=101),
        ]
        self.assertEqual(cla.contributors(api, 123, HEAD), ({10: "alice", 20: "bob"}, False))
        self.assertEqual(api.request.call_args.args[2]["variables"]["cursor"], "next")

    def test_only_explicit_bot_accounts_are_exempt(self):
        api = Mock()
        api.request.return_value = authors_page([commit(
            {"id": 1, "login": "dependabot[bot]"},
            {"id": 2, "login": "github-actions[bot]"},
            {"id": 3, "login": "bot-attacker"},
        )])
        self.assertEqual(cla.contributors(api, 123, HEAD), ({3: "bot-attacker"}, False))

    def test_unidentified_author_is_not_replaced_with_a_committer(self):
        api = Mock()
        node = commit(None, ALICE)
        node["commit"]["committer"] = {"user": BOB}
        api.request.return_value = authors_page([node])
        self.assertEqual(cla.contributors(api, 123, HEAD), ({10: "alice"}, True))

    def test_partial_graphql_result_and_truncation_fail_closed(self):
        results = [
            {**authors_page(), "errors": [{"message": "partial data"}]},
            authors_page([commit(ALICE, overflow=True)]),
            authors_page([], total=1),
            authors_page([commit(ALICE)], total=2),
        ]
        for result in results:
            api = Mock()
            api.request.return_value = result
            with self.subTest(result=result), self.assertRaises(cla.ClaError):
                cla.contributors(api, 123, HEAD)

    def test_head_change_rejects_the_assessment(self):
        api = Mock()
        api.request.return_value = authors_page(head=OTHER_HEAD)
        with self.assertRaises(cla.HeadChanged):
            cla.contributors(api, 123, HEAD)

    def test_invalid_pagination_cursor_fails(self):
        api = Mock()
        api.request.return_value = authors_page(cursor="repeated", total=3)
        with self.assertRaises(cla.ClaError):
            cla.contributors(api, 123, HEAD)


class SignaturesTest(unittest.TestCase):
    def test_only_a_contributors_own_exact_comment_can_sign(self):
        comments = [
            signing_comment(BOB),
            signing_comment(body="@alice " + cla.SIGNING_PHRASE),
            signing_comment(body=cla.SIGNING_PHRASE + "\n$(malicious)"),
            signing_comment({**ALICE, "type": "Bot"}),
            signing_comment({**ALICE, "id": "10"}),
            signing_comment(comment_id=9),
            signing_comment(comment_id=10),
        ]
        records = cla.signing_records(comments, {10: "alice"}, 123, 42)
        self.assertEqual(len(records), 1)
        self.assertEqual(records[0], {
            "id": 10, "name": "alice", "comment_id": 9,
            "created_at": "2026-10-12T12:00:00Z", "pullRequestNo": 123, "repoId": 42,
        })

    def test_account_rename_preserves_signature_identity(self):
        records = cla.signing_records([signing_comment({**ALICE, "login": "renamed"})], {10: "alice"}, 123, 42)
        api = Mock()
        api.request.return_value = registry_response([{"id": 10, "name": "old-name"}])
        self.assertEqual(cla.SignatureStore(api).merge(records), {10})
        self.assertEqual(api.request.call_count, 1)

    def test_empty_repository_is_initialized_only_on_signing(self):
        api = Mock()
        api.request.side_effect = [cla.ApiError(404, "GET", "/registry"), {}]
        records = cla.signing_records([signing_comment()], {10: "alice"}, 123, 42)
        self.assertEqual(cla.SignatureStore(api).merge(records), {10})
        method, path, data = api.request.call_args.args
        self.assertEqual(method, "PUT")
        self.assertIn("AChep/keyguard-cla/contents/version1/cla.json", path)
        self.assertEqual(data["branch"], "master")
        self.assertNotIn("sha", data)
        self.assertEqual(json.loads(base64.b64decode(data["content"])), {"signedContributors": records})

    def test_missing_registry_does_not_approve_humans_or_write_empty_data(self):
        api = Mock()
        api.request.side_effect = cla.ApiError(404, "GET", "/registry")
        self.assertEqual(cla.SignatureStore(api).merge([]), set())
        self.assertEqual(api.request.call_count, 1)

    @patch("cla.time.sleep")
    def test_concurrent_signature_updates_are_merged(self, sleep):
        for code in (409, 422):
            api = Mock()
            api.request.side_effect = [
                registry_response([], "old"), cla.ApiError(code, "PUT", "/registry"),
                registry_response([{"id": 20}], "new"), {},
            ]
            self.assertEqual(cla.SignatureStore(api).merge([{"id": 10}]), {10, 20})
            data = api.request.call_args.args[2]
            self.assertEqual(data["sha"], "new")
            self.assertEqual(json.loads(base64.b64decode(data["content"]))["signedContributors"], [{"id": 20}, {"id": 10}])

    @patch("cla.time.sleep")
    def test_retry_after_an_ambiguous_write_is_idempotent(self, sleep):
        api = Mock()
        api.request.side_effect = [
            registry_response([]), cla.ApiError(409, "PUT", "/registry"),
            registry_response([{"id": 10}]),
        ]
        self.assertEqual(cla.SignatureStore(api).merge([{"id": 10}]), {10})
        self.assertEqual(api.request.call_count, 3)

    def test_api_failures_and_invalid_registry_fail_closed(self):
        for code in (401, 403, 429, 500):
            api = Mock()
            api.request.side_effect = cla.ApiError(code, "GET", "/registry")
            with self.subTest(code=code), self.assertRaises(cla.ApiError):
                cla.SignatureStore(api).merge([])
        for records in ([{"id": True}], [{"id": "10"}], [None]):
            api = Mock()
            api.request.return_value = registry_response(records)
            with self.subTest(records=records), self.assertRaises(cla.ClaError):
                cla.SignatureStore(api).merge([])

    @patch("cla.time.sleep")
    def test_conflict_retries_are_bounded(self, sleep):
        api = Mock()
        api.request.side_effect = [item for _ in range(5) for item in (
            registry_response([]), cla.ApiError(409, "PUT", "/registry"),
        )]
        with self.assertRaises(cla.ApiError):
            cla.SignatureStore(api).merge([{"id": 10}])
        self.assertEqual(api.request.call_count, 10)


class WorkflowApi:
    """Fake API exposes external behavior, including PR changes between calls."""
    def __init__(self, comments=None):
        self.pr = {"state": "open", "head": {"sha": HEAD}}
        self.comments = comments or []
        self.graphql = authors_page()
        self.requests = []
        self.pr_reads = 0
        self.on_pr_read = lambda _: None

    def request(self, method, path, data=None):
        self.requests.append((method, path, copy.deepcopy(data)))
        if method == "GET" and "/pulls/" in path:
            self.pr_reads += 1
            self.on_pr_read(self.pr_reads)
            return copy.deepcopy(self.pr)
        if path == "/graphql":
            return copy.deepcopy(self.graphql)
        return {}

    def paginate(self, path):
        return iter(self.comments)

    @property
    def statuses(self):
        return [(path.rsplit("/", 1)[-1], data["state"]) for _, path, data in self.requests if "/statuses/" in path]


class ClaCheckTest(unittest.TestCase):
    def run_check(self, api, store=None, action="opened"):
        if store is None:
            store = Mock()
            store.merge.return_value = set()
        cla.ClaCheck(api, store, "https://github.com/AChep/keyguard-app/actions/runs/1").run(123, 42, action)
        return store

    def test_unsigned_contributor_produces_a_failing_status_and_instructions(self):
        api = WorkflowApi()
        self.run_check(api)
        self.assertEqual(api.statuses, [(HEAD, "pending"), (HEAD, "failure")])
        body = next(data["body"] for method, path, data in api.requests if method == "POST" and path.endswith("/comments"))
        self.assertIn("@alice", body)
        self.assertIn(cla.SIGNING_PHRASE, body)

    def test_all_signatures_are_recovered_when_events_are_coalesced(self):
        api = WorkflowApi([signing_comment(ALICE), signing_comment(BOB, 2)])
        api.graphql = authors_page([commit(ALICE, BOB)])
        store = Mock()
        store.merge.side_effect = lambda records: {record["id"] for record in records}
        self.run_check(api, store, action="created")
        self.assertEqual(api.statuses[-1], (HEAD, "success"))
        self.assertEqual({record["id"] for record in store.merge.call_args.args[0]}, {10, 20})

    def test_unknown_coauthor_blocks_even_if_known_authors_signed(self):
        api = WorkflowApi()
        api.graphql = authors_page([commit(ALICE, None)])
        store = Mock()
        store.merge.return_value = {10}
        self.run_check(api, store)
        self.assertEqual(api.statuses[-1], (HEAD, "failure"))

    def test_registry_failure_clears_an_earlier_success_and_errors(self):
        api = WorkflowApi()
        store = Mock()
        store.merge.side_effect = cla.ApiError(403, "GET", "/registry")
        with self.assertRaises(cla.ApiError):
            self.run_check(api, store)
        self.assertEqual(api.statuses, [(HEAD, "pending"), (HEAD, "error")])

    def test_signature_is_not_accepted_until_the_registry_write_succeeds(self):
        api = WorkflowApi([signing_comment()])
        registry_api = Mock()
        registry_api.request.side_effect = [
            cla.ApiError(404, "GET", "/registry"),
            cla.ApiError(403, "PUT", "/registry"),
        ]
        with self.assertRaises(cla.ApiError):
            self.run_check(api, cla.SignatureStore(registry_api))
        self.assertEqual(api.statuses, [(HEAD, "pending"), (HEAD, "error")])

    def test_changing_head_never_receives_a_stale_success(self):
        api = WorkflowApi()
        api.graphql = authors_page(head=OTHER_HEAD)
        with self.assertRaises(cla.ClaError):
            self.run_check(api)
        self.assertTrue(api.statuses)
        self.assertTrue(all(state == "pending" for _, state in api.statuses))

    def test_force_push_restarts_assessment_for_the_new_head(self):
        api = WorkflowApi()

        def force_push(read):
            if read == 2:
                api.pr["head"]["sha"] = OTHER_HEAD
                api.graphql = authors_page([commit(BOB)], head=OTHER_HEAD)

        api.on_pr_read = force_push
        store = Mock()
        store.merge.return_value = {10}
        self.run_check(api, store)
        self.assertEqual(api.statuses, [(HEAD, "pending"), (OTHER_HEAD, "pending"), (OTHER_HEAD, "failure")])

    def test_stale_event_uses_live_pr_head(self):
        api = WorkflowApi()
        api.pr["head"]["sha"] = OTHER_HEAD
        api.graphql = authors_page(head=OTHER_HEAD)
        self.run_check(api)
        self.assertEqual(api.statuses, [(OTHER_HEAD, "pending"), (OTHER_HEAD, "failure")])

    def test_only_our_bot_comment_is_updated(self):
        spoof = signing_comment(ALICE, 7, cla.COMMENT_MARKER + " forged")
        real = signing_comment({"id": 41898282, "login": cla.BOT_LOGIN, "type": "Bot"}, 8, cla.COMMENT_MARKER + " old")
        api = WorkflowApi([spoof, real])
        self.run_check(api)
        updates = [(method, path) for method, path, _ in api.requests if method == "PATCH"]
        self.assertEqual(updates, [("PATCH", f"/repos/{cla.REPOSITORY}/issues/comments/8")])

    def test_identical_bot_comment_is_not_rewritten(self):
        body = cla.comment_body({10: "alice"}, set(), False)
        api = WorkflowApi([signing_comment({"id": 41898282, "login": cla.BOT_LOGIN, "type": "Bot"}, 8, body)])
        self.run_check(api)
        self.assertFalse(any(method == "PATCH" or path.endswith("/comments") for method, path, _ in api.requests))

    def test_only_merged_closed_prs_are_locked(self):
        for merged in (False, True):
            api = WorkflowApi()
            api.pr = {"state": "closed", "merged": merged, "locked": False}
            store = self.run_check(api, action="closed")
            store.merge.assert_not_called()
            locks = [path for method, path, _ in api.requests if method == "PUT"]
            self.assertEqual(len(locks), int(merged))
            self.assertEqual(api.statuses, [])


class GitHubClientTest(unittest.TestCase):
    def test_reads_all_comment_pages(self):
        api = cla.GitHub("test-token", "TEST_TOKEN")
        api.request = Mock(side_effect=[[{"id": n} for n in range(100)], [{"id": 100}]])
        self.assertEqual(len(list(api.paginate("/comments"))), 101)
        self.assertEqual(api.request.call_args.args[1], "/comments?per_page=100&page=2")

    def test_credentials_never_follow_redirects(self):
        handler = cla.NoRedirects()
        self.assertIsNone(handler.redirect_request(None, None, 302, "", {}, "https://attacker.example"))

    def test_missing_token_and_unexpected_paths_fail_before_network_access(self):
        for token, path in ((None, "/repos"), ("test-token", "//attacker.example"), ("test-token", "https://attacker.example")):
            api = cla.GitHub(token, "TEST_TOKEN")
            api.opener = Mock()
            with self.assertRaises(cla.ClaError):
                api.request("GET", path)
            api.opener.open.assert_not_called()

    def test_api_errors_do_not_log_token_or_response_content(self):
        api = cla.GitHub("test-token", "TEST_TOKEN")
        api.opener = Mock()
        api.opener.open.side_effect = HTTPError("https://api.github.com/test", 403, "denied", {}, io.BytesIO(b"secret response"))
        with self.assertRaises(cla.ApiError) as raised:
            api.request("GET", "/test")
        self.assertNotIn("test-token", str(raised.exception))
        self.assertNotIn("secret response", str(raised.exception))


if __name__ == "__main__":
    unittest.main()
