"""Synthetic artifacts and mocked HTTPS only; no credentials, GPG or real HTTP."""
import argparse
from contextlib import redirect_stderr
from dataclasses import replace
import hashlib
import importlib.util
import io
import json
from pathlib import Path
import sys
import tempfile
import threading
import unittest
from unittest import mock
from zipfile import ZipFile, ZIP_STORED

SCRIPT = Path(__file__).parents[1] / "central-publish.py"
SPEC = importlib.util.spec_from_file_location("central_publish", SCRIPT)
publish = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = publish
SPEC.loader.exec_module(publish)

VERSION = "1.0.0"
COMMIT = "1" * 40
ID = "11111111-2222-3333-4444-555555555555"
ENV = {"MANTRA_CENTRAL_USERNAME": "synthetic-user", "MANTRA_CENTRAL_PASSWORD": "synthetic-secret"}


def fixture_bundle(version=VERSION, pom_version=None):
    output = io.BytesIO()
    primaries = []
    with ZipFile(output, "w", compression=ZIP_STORED) as archive:
        for module in publish.MODULES:
            for suffix in publish.SUFFIXES:
                name = f"{publish.GROUP_PATH}/{module}/{version}/{module}-{version}{suffix}"
                data = (f'<project xmlns="http://maven.apache.org/POM/4.0.0"><groupId>{publish.GROUP}</groupId>'
                        f'<artifactId>{module}</artifactId><version>{pom_version or version}</version></project>').encode() if suffix == ".pom" else b"synthetic signed artifact; not executed"
                archive.writestr(name, data)
                archive.writestr(name + ".asc", b"synthetic signature; not a PGP packet")
                for algorithm in publish.HASHES:
                    archive.writestr(name + "." + algorithm, hashlib.new(algorithm, data).hexdigest() + "\n")
                primaries.append({"path": name, "bytes": len(data), "sha256": hashlib.sha256(data).hexdigest()})
    data = output.getvalue()
    review = {"status": "LOCAL_VERIFIED_BUNDLE_NOT_UPLOADED", "published": False,
              "group": publish.GROUP, "version": version, "libraries": list(publish.MODULES),
              "normeinDependency": "com.xqiou:normein-dsl:0.3.0", "signerFingerprint": "A" * 40,
              "entryCount": 144, "primaryArtifacts": primaries, "bundleBytes": len(data),
              "bundleSha256": hashlib.sha256(data).hexdigest()}
    return data, review


def status(state, purls=True, errors=None):
    body = {"deploymentId": ID, "deploymentState": state}
    if purls:
        body["purls"] = [f"pkg:maven/{publish.GROUP}/{module}@{VERSION}" for module in publish.MODULES]
    if errors is not None:
        body["errors"] = errors
    return json.dumps(body).encode()


class MockSocket:
    def __init__(self):
        self.timeout = None
        self.interrupted = threading.Event()

    def settimeout(self, value):
        self.timeout = value

    def shutdown(self, _):
        self.interrupted.set()


class MockResponse:
    def __init__(self, code, data=b"", headers=None, blocked=False):
        self.status, self.data = code, data
        self.headers = headers or {}
        self.closed, self.blocked = False, blocked
        self.socket = None

    def getheader(self, name):
        return self.headers.get(name)

    def read(self, amount):
        if self.blocked:
            if not self.socket.interrupted.wait(1):
                raise AssertionError("The deadline failed to interrupt the mocked peer")
            return b""
        result, self.data = self.data[:amount], self.data[amount:]
        return result

    def close(self):
        self.closed = True


class MockConnection:
    def __init__(self, response):
        self.response = response
        self.sock = MockSocket()
        response.socket = self.sock
        self.headers, self.body = {}, bytearray()
        self.closed = False
        self.debuglevel = 99

    def set_debuglevel(self, level):
        self.debuglevel = level

    def putrequest(self, method, path):
        self.method, self.path = method, path

    def putheader(self, name, value):
        self.headers[name] = value

    def endheaders(self):
        pass

    def send(self, data):
        self.body.extend(data)

    def getresponse(self):
        return self.response

    def close(self):
        self.closed = True


class CentralPublishTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory(prefix="mantra-central-http-test-")
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name).resolve()
        self.data, self.review = fixture_bundle()
        self.bundle = self.root / "bundle.zip"
        self.bundle.write_bytes(self.data)
        self.review_file = self.root / "review.json"
        self.review_file.write_text(json.dumps(self.review))
        self.receipt = self.root / "deployment.json"
        self.requests = []

    def args(self, operation="upload", **changes):
        fields = dict(operation=operation, version=VERSION, source_commit=COMMIT,
                      bundle_sha256=self.review["bundleSha256"], bundle=self.bundle,
                      review_receipt=self.review_file, deployment_receipt=self.receipt, deployment_id=ID)
        fields.update(changes)
        return argparse.Namespace(**fields)

    def factory(self, *responses, bounds=publish.Bounds()):
        pending = iter(responses)

        def connect(host, port, timeout):
            self.assertEqual((host, port), ("central.sonatype.com", 443))
            self.assertGreater(timeout, 0)
            connection = MockConnection(next(pending))
            self.requests.append(connection)
            return connection

        return lambda authorization: publish.PortalClient(authorization, bounds, connect)

    def upload(self, *responses):
        return publish.operate(self.args(), ENV, self.factory(MockResponse(201, ID.encode()), *responses))

    def test_upload_is_user_managed_and_does_not_status_or_publish(self):
        result = self.upload()
        self.assertEqual(len(self.requests), 1)
        request = self.requests[0]
        self.assertEqual(request.method, "POST")
        self.assertIn("/api/v1/publisher/upload?publishingType=USER_MANAGED&name=mantra-1.0.0-", request.path)
        self.assertIn(b'name="bundle"; filename="mantra.zip"', request.body)
        self.assertIn(self.data, request.body)
        self.assertEqual(int(request.headers["Content-Length"]), len(request.body))
        self.assertEqual(request.debuglevel, 0)
        self.assertTrue(request.closed)
        self.assertTrue(request.response.closed)
        self.assertEqual(result["deploymentId"], ID)
        self.assertFalse(result["published"])
        self.assertFalse(result["publishAttempted"])
        self.assertEqual(json.loads(self.receipt.read_text()), result)
        self.assertNotIn("synthetic-secret", self.receipt.read_text())
        self.assertNotIn(request.headers["Authorization"], self.receipt.read_text())

    def test_publish_requires_fresh_validated_status_then_only_records_acceptance(self):
        self.upload()
        result = publish.operate(self.args("publish"), ENV,
                                 self.factory(MockResponse(200, status("VALIDATED")), MockResponse(204)))
        self.assertEqual(len(self.requests), 3)
        self.assertEqual(self.requests[-2].path, "/api/v1/publisher/status?id=" + ID)
        self.assertEqual(self.requests[-1].path, "/api/v1/publisher/deployment/" + ID)
        self.assertEqual(result["phase"], "PUBLISH_REQUEST_ACCEPTED")
        self.assertEqual(result["lastObservedState"], "VALIDATED")
        self.assertFalse(result["published"])
        self.assertTrue(result["publishAttempted"])
        with self.assertRaises(publish.PublishError):
            publish.operate(self.args("publish"), ENV, self.factory())
        self.assertEqual(len(self.requests), 3)

    def test_only_observed_published_status_claims_publication(self):
        self.upload()
        result = publish.operate(self.args("status"), ENV, self.factory(MockResponse(200, status("PUBLISHED"))))
        self.assertTrue(result["published"])
        self.assertEqual(result["lastObservedState"], "PUBLISHED")
        self.assertEqual(result["phase"], "REMOTE_STATE_OBSERVED")

    def test_every_status_is_one_request_without_polling_and_failed_keeps_deployment(self):
        self.upload()
        for state in sorted(publish.STATES):
            with self.subTest(state=state):
                count = len(self.requests)
                result = publish.operate(self.args("status"), ENV, self.factory(MockResponse(200, status(state))))
                self.assertEqual(len(self.requests), count + 1)
                self.assertEqual(result["lastObservedState"], state)
                self.assertEqual(result["published"], state == "PUBLISHED")
                self.assertEqual(result["deploymentId"], ID)

    def test_not_validated_never_sends_publish(self):
        self.upload()
        for state in ["PENDING", "VALIDATING", "FAILED", "PUBLISHING", "PUBLISHED"]:
            with self.subTest(state=state), self.assertRaises(publish.PublishError):
                publish.operate(self.args("publish"), ENV, self.factory(MockResponse(200, status(state))))
        self.assertFalse(json.loads(self.receipt.read_text())["publishAttempted"])
        self.assertFalse(any("/publisher/deployment/" in item.path for item in self.requests))

    def test_wrong_source_version_hash_or_deployment_is_refused_before_http(self):
        self.upload()
        count = len(self.requests)
        for changes in [{"source_commit": "2" * 40}, {"version": "1.0.1"}, {"bundle_sha256": "0" * 64},
                        {"deployment_id": "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"}]:
            with self.subTest(changes=changes), self.assertRaises(publish.PublishError):
                publish.operate(self.args("publish", **changes), ENV, self.factory())
        self.assertEqual(len(self.requests), count)

    def test_bundle_or_review_tampering_is_refused_before_credentials_or_http(self):
        for target, replacement in [(self.bundle, self.data + b"changed"),
                                    (self.review_file, json.dumps({**self.review, "libraries": ["mantra-core"]}).encode())]:
            original = target.read_bytes()
            target.write_bytes(replacement)
            with self.subTest(target=target.name), self.assertRaises(publish.PublishError):
                publish.operate(self.args(), {}, self.factory())
            self.assertFalse(self.receipt.exists())
            target.write_bytes(original)
        self.assertEqual(self.requests, [])

    def test_matching_review_hash_does_not_hide_wrong_bundle_pom_gav(self):
        data, review = fixture_bundle(pom_version="1.0.1")
        identity = publish.expected_identity(VERSION, COMMIT, hashlib.sha256(data).hexdigest())
        with self.assertRaisesRegex(publish.PublishError, "POM GAV mismatch"):
            publish.verify_bundle(data, json.dumps(review).encode(), identity, publish.Bounds())

    def test_review_primary_hash_and_source_commit_are_checked(self):
        for mutate in [lambda value: value["primaryArtifacts"][0].update(sha256="0" * 64),
                       lambda value: value.update(sourceCommit="2" * 40)]:
            review = json.loads(json.dumps(self.review))
            mutate(review)
            with self.subTest(review=review), self.assertRaises(publish.PublishError):
                publish.verify_bundle(self.data, json.dumps(review).encode(),
                                      publish.expected_identity(VERSION, COMMIT, self.review["bundleSha256"]), publish.Bounds())

    def test_existing_receipt_and_lock_are_never_overwritten_or_uploaded(self):
        self.receipt.write_bytes(b"historical receipt")
        with self.assertRaises(publish.PublishError):
            publish.operate(self.args(), ENV, self.factory())
        self.assertEqual(self.receipt.read_bytes(), b"historical receipt")
        self.receipt.unlink()
        lock = self.receipt.with_name(self.receipt.name + ".lock")
        lock.write_bytes(b"another operation")
        with self.assertRaises(publish.PublishError):
            publish.operate(self.args(), ENV, self.factory())
        self.assertEqual(lock.read_bytes(), b"another operation")
        self.assertEqual(self.requests, [])

    def test_upload_unknown_outcome_is_saved_and_never_reuploaded(self):
        with self.assertRaises(publish.PublishError):
            publish.operate(self.args(), ENV, self.factory(MockResponse(503, b"synthetic-secret")))
        result = json.loads(self.receipt.read_text())
        self.assertEqual(result["phase"], "UPLOAD_OUTCOME_UNKNOWN")
        self.assertIsNone(result["deploymentId"])
        with self.assertRaises(publish.PublishError):
            publish.operate(self.args(), ENV, self.factory())
        self.assertEqual(len(self.requests), 1)
        self.assertNotIn("synthetic-secret", self.receipt.read_text())

    def test_publish_unknown_outcome_is_saved_with_id_and_no_automatic_retry(self):
        self.upload()
        with self.assertRaises(publish.PublishError):
            publish.operate(self.args("publish"), ENV,
                            self.factory(MockResponse(200, status("VALIDATED")), MockResponse(503)))
        result = json.loads(self.receipt.read_text())
        self.assertEqual(result["phase"], "PUBLISH_OUTCOME_UNKNOWN")
        self.assertEqual(result["deploymentId"], ID)
        self.assertTrue(result["publishAttempted"])
        with self.assertRaises(publish.PublishError):
            publish.operate(self.args("publish"), ENV, self.factory())
        self.assertEqual(len(self.requests), 3)

    def test_redirect_never_forwards_authentication_to_any_other_host(self):
        response = MockResponse(302, headers={"Location": "https://untrusted.invalid/token"})
        with self.assertRaises(publish.PublishError):
            publish.operate(self.args(), ENV, self.factory(response))
        self.assertEqual(len(self.requests), 1)
        self.assertTrue(self.requests[0].closed)
        self.assertTrue(response.closed)

    def test_response_declared_and_actual_bytes_are_bounded(self):
        for response in [MockResponse(201, ID.encode(), {"Content-Length": "100000000"}),
                         MockResponse(201, b"X" * 129)]:
            with self.subTest(response=response):
                client = self.factory(response, bounds=replace(publish.Bounds(), response_bytes=128))("Bearer synthetic")
                with self.assertRaises(publish.PublishError):
                    client.upload(b"bundle", "synthetic-name")
                self.assertTrue(self.requests[-1].closed)
                self.assertTrue(response.closed)

    def test_active_deadline_interrupts_a_blocked_mock_peer_and_closes_resources(self):
        response = MockResponse(201, blocked=True)
        client = self.factory(response, bounds=replace(publish.Bounds(), request_seconds=0.02))("Bearer synthetic")
        with self.assertRaises(publish.PublishError):
            client.upload(b"bundle", "synthetic-name")
        self.assertTrue(self.requests[-1].sock.interrupted.is_set())
        self.assertTrue(self.requests[-1].closed)
        self.assertTrue(response.closed)

    def test_early_status_can_have_empty_artifacts_but_validated_requires_all_six(self):
        for state, expected in [("PENDING", True), ("VALIDATING", True), ("VALIDATED", False), ("PUBLISHED", False)]:
            response = json.dumps({"deploymentId": ID, "deploymentState": state, "purls": []}).encode()
            client = self.factory(MockResponse(200, response))("Bearer synthetic")
            if expected:
                self.assertEqual(client.status(ID, publish.expected_identity(VERSION, COMMIT, "0" * 64)["gavs"])["deploymentState"], state)
            else:
                with self.assertRaises(publish.PublishError):
                    client.status(ID, publish.expected_identity(VERSION, COMMIT, "0" * 64)["gavs"])

    def test_status_wrong_identity_purls_or_state_cannot_authorize_publish(self):
        self.upload()
        for change in [{"deploymentId": "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"},
                       {"deploymentState": "NEW_UNKNOWN_STATE"}, {"deploymentState": {}},
                       {"purls": ["pkg:maven/unreviewed/component@1.0.0"]}]:
            response = json.loads(status("VALIDATED")); response.update(change)
            with self.subTest(change=change), self.assertRaises(publish.PublishError):
                publish.operate(self.args("publish"), ENV, self.factory(MockResponse(200, json.dumps(response).encode())))
        self.assertFalse(any("/publisher/deployment/" in item.path for item in self.requests))

    def test_remote_errors_are_counted_without_echoing_body_or_reflected_credentials(self):
        self.upload()
        result = publish.operate(self.args("status"), ENV,
                                 self.factory(MockResponse(200, status("FAILED", errors={"synthetic-secret": ["Bearer synthetic"]}))))
        self.assertEqual(result["remoteErrorCount"], 1)
        self.assertNotIn("synthetic-secret", json.dumps(result))
        self.assertNotIn("Bearer synthetic", json.dumps(result))

    def test_invalid_local_lifecycle_cannot_be_reflected_into_output(self):
        self.upload()
        result = json.loads(self.receipt.read_text()); result["phase"] = "synthetic-secret"
        self.receipt.write_text(json.dumps(result))
        with self.assertRaises(publish.PublishError):
            publish.operate(self.args("status"), ENV, self.factory())
        self.assertEqual(len(self.requests), 1)

    def test_bad_upload_id_is_unknown_outcome_and_no_publish_response_claim_is_success(self):
        for body in [b"not-an-id", ID.encode() + b" synthetic-secret", b'"' + ID.encode() + b'"']:
            client = self.factory(MockResponse(201, body))("Bearer synthetic")
            with self.subTest(body=body), self.assertRaises(publish.PublishError):
                client.upload(b"bundle", "name")
        client = self.factory(MockResponse(204, b"unexpected-body"))("Bearer synthetic")
        with self.assertRaises(publish.PublishError):
            client.publish(ID)

    def test_bundle_and_receipt_limits_and_symlink_are_checked_before_http(self):
        for bounds in [replace(publish.Bounds(), bundle_bytes=10), replace(publish.Bounds(), receipt_bytes=10)]:
            with self.subTest(bounds=bounds), self.assertRaises(publish.PublishError):
                publish.operate(self.args(), ENV, self.factory(), bounds)
        original = self.bundle.with_name("original.zip"); self.bundle.rename(original); self.bundle.symlink_to(original)
        with self.assertRaises(publish.PublishError):
            publish.operate(self.args(), ENV, self.factory())
        self.assertEqual(self.requests, [])

    def test_cli_rejects_credentials_remote_host_or_delete_without_echoing_arguments(self):
        for argv in [["delete", "--token", "synthetic-secret"], ["upload", "--endpoint", "https://untrusted.invalid"],
                     ["--password", "synthetic-secret"]]:
            output = io.StringIO()
            with self.subTest(argv=argv), redirect_stderr(output), self.assertRaises(SystemExit) as failure:
                publish.main(argv)
            self.assertEqual(failure.exception.code, 2)
            self.assertNotIn("synthetic-secret", output.getvalue())

    def test_cli_failure_never_echoes_exception_or_untrusted_receipt_payload(self):
        output = io.StringIO()
        argv = ["status", "--deployment-receipt", str(self.receipt), "--version", VERSION,
                "--source-commit", COMMIT, "--bundle-sha256", self.review["bundleSha256"], "--deployment-id", ID]
        with mock.patch.object(publish, "operate", side_effect=publish.PublishError("synthetic-secret")), \
                redirect_stderr(output), self.assertRaises(SystemExit) as failure:
            publish.main(argv)
        self.assertEqual(failure.exception.code, 1)
        self.assertNotIn("synthetic-secret", output.getvalue())


class CredentialAndJsonTest(unittest.TestCase):
    def test_only_explicit_complete_environment_mappings_are_accepted(self):
        aliases = {"CENTRAL_TOKEN_USERNAME": "synthetic-user", "CENTRAL_TOKEN_PASSWORD": "synthetic-secret"}
        expected = "Bearer c3ludGhldGljLXVzZXI6c3ludGhldGljLXNlY3JldA=="
        self.assertEqual(publish.authentication(ENV), expected)
        self.assertEqual(publish.authentication(aliases), expected)
        self.assertEqual(publish.authentication({**ENV, **aliases}), expected)
        for environment in [{}, {"SIGNING_KEY": "synthetic-secret", "SIGNING_PASSWORD": "synthetic-secret"},
                            {"MANTRA_CENTRAL_USERNAME": "partial", **aliases},
                            {**ENV, **aliases, "CENTRAL_TOKEN_USERNAME": "different"},
                            {**ENV, "MANTRA_CENTRAL_PASSWORD": "newline\nsecret"}]:
            with self.subTest(environment=environment), self.assertRaises(publish.PublishError) as failure:
                publish.authentication(environment)
            self.assertNotIn("synthetic-secret", str(failure.exception))

    def test_strict_json_rejects_duplicates_trailing_nonfinite_and_non_objects(self):
        for data in [b'{"deploymentId":1,"deploymentId":2}', b'{}{}', b'{"x":NaN}', b'[]', b'\xff']:
            with self.subTest(data=data), self.assertRaises(publish.PublishError):
                publish.strict_json(data)

    def test_release_identity_refuses_rc_snapshot_alias_commit_and_malformed_hash(self):
        for version, commit, digest in [("1.0.0-rc.1", COMMIT, "0" * 64), ("1.0.0-SNAPSHOT", COMMIT, "0" * 64),
                                        (VERSION, "main", "0" * 64), (VERSION, COMMIT, "sha256:abc")]:
            with self.subTest(version=version, commit=commit), self.assertRaises(publish.PublishError):
                publish.expected_identity(version, commit, digest)


if __name__ == "__main__":
    unittest.main()
