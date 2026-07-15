import importlib.util
import json
import os
import stat
import tempfile
import unittest
from pathlib import Path
from unittest import mock


REPOSITORY_ROOT = Path(__file__).resolve().parents[1]
PUBLISHER_PATH = (
    REPOSITORY_ROOT
    / "skills"
    / "ai-call-flow-navigator"
    / "scripts"
    / "publish_call_flow.py"
)
SPEC = importlib.util.spec_from_file_location("ai_call_flow_publisher", PUBLISHER_PATH)
PUBLISHER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(PUBLISHER)


def minimal_call_flow():
    return {
        "version": "1.0",
        "title": "Minimal flow",
        "nodes": [
            {
                "id": "entry",
                "kind": "entry",
                "location": {"path": "source.kt", "line": 1, "column": 1},
                "summary": "Entry point.",
            }
        ],
        "edges": [],
        "entry": "entry",
    }


def large_call_flow_bytes():
    nodes = [
        {
            "id": "n%d" % index,
            "kind": "entry" if index == 0 else "call",
            "location": {"path": "source.kt", "line": 1, "column": 1},
            "summary": "x" * 12_000,
        }
        for index in range(200)
    ]
    edges = [
        {"from": "n%d" % index, "to": "n%d" % (index + 1), "kind": "next"}
        for index in range(199)
    ]
    flow = {
        "version": "1.0",
        "title": "Large flow",
        "nodes": nodes,
        "edges": edges,
        "entry": "n0",
    }
    payload = json.dumps(flow, ensure_ascii=False).encode("utf-8")
    if len(payload) <= 2 * 1024 * 1024:
        raise AssertionError("large Call Flow fixture must exceed 2 MiB")
    return payload


class PublishCallFlowTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.large_payload = large_call_flow_bytes()

    def test_regular_input_reads_complete_large_call_flow(self):
        with tempfile.TemporaryDirectory() as directory:
            input_path = Path(directory) / "large-flow.json"
            input_path.write_bytes(self.large_payload)

            self.assertEqual(self.large_payload, PUBLISHER._read_input(input_path, False))

    def test_delete_input_reads_complete_large_call_flow_before_removal(self):
        with tempfile.TemporaryDirectory() as directory:
            input_path = Path(directory) / "large-flow.json"
            input_path.write_bytes(self.large_payload)
            os.chmod(input_path, 0o600)

            self.assertEqual(self.large_payload, PUBLISHER._read_input(input_path, True))
            self.assertFalse(input_path.exists())

    def test_main_delete_input_accepts_the_explicit_custom_temp_root(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            standard_root = root / "standard-temp"
            custom_root = root / "custom-java-temp"
            standard_root.mkdir()
            custom_root.mkdir()
            input_path = custom_root / "flow.json"
            payload = json.dumps(minimal_call_flow()).encode("utf-8")
            input_path.write_bytes(payload)
            os.chmod(input_path, 0o600)

            def temporary_roots(explicit=None):
                return [custom_root.resolve()] if explicit else [standard_root.resolve()]

            with mock.patch.object(
                PUBLISHER,
                "_temporary_root_candidates",
                side_effect=temporary_roots,
            ), mock.patch.object(PUBLISHER, "_validate_sources"), mock.patch.object(
                PUBLISHER,
                "_exchange_directories",
                return_value=(Path("inbox"), Path("receipts")),
            ), mock.patch.object(
                PUBLISHER,
                "_publish_request",
                return_value=Path("request.json"),
            ), mock.patch.object(PUBLISHER, "_wait_for_receipt", return_value=0):
                result = PUBLISHER.main(
                    [
                        str(input_path),
                        "--delete-input",
                        "--temp-root",
                        str(custom_root),
                    ]
                )

            self.assertEqual(0, result)
            self.assertFalse(input_path.exists())

    def test_main_rejects_non_finite_timeout_before_reading_input(self):
        with mock.patch.object(PUBLISHER, "_read_input") as read_input:
            for timeout in ("nan", "inf", "-inf"):
                with self.subTest(timeout=timeout), self.assertRaisesRegex(
                    PUBLISHER.PublisherError,
                    "--timeout must be greater than 0 and at most 300 seconds",
                ):
                    PUBLISHER.main(["unused.json", "--timeout=" + timeout])
            read_input.assert_not_called()

    def test_publish_request_is_private_atomic_and_never_overwrites(self):
        with tempfile.TemporaryDirectory() as directory:
            inbox = Path(directory)
            os.chmod(inbox, 0o700)
            request_id = "request-id"
            payload = b'{"flow":true}'

            with mock.patch.object(
                PUBLISHER.os,
                "chmod",
                side_effect=NotImplementedError("follow_symlinks is unavailable"),
            ) as chmod:
                published = PUBLISHER._publish_request(inbox, request_id, payload)

            chmod.assert_not_called()
            self.assertEqual(payload, published.read_bytes())
            self.assertFalse((inbox / (".request-%s.tmp" % request_id)).exists())
            if os.name != "nt":
                self.assertEqual(0o600, stat.S_IMODE(published.stat().st_mode))

            with self.assertRaisesRegex(
                PUBLISHER.PublisherError,
                "request destination already exists",
            ):
                PUBLISHER._publish_request(inbox, request_id, b"replacement")
            self.assertEqual(payload, published.read_bytes())
            self.assertFalse((inbox / (".request-%s.tmp" % request_id)).exists())

    def test_unknown_top_level_field_is_rejected(self):
        flow = minimal_call_flow()
        flow["extra"] = True

        with self.assertRaisesRegex(
            PUBLISHER.PublisherError,
            "Call Flow has unsupported fields: extra",
        ):
            PUBLISHER._validate_call_flow(flow)

    def test_deeply_nested_input_is_reported_without_a_traceback(self):
        with mock.patch.object(
            PUBLISHER.json,
            "loads",
            side_effect=RecursionError("maximum recursion depth exceeded"),
        ), self.assertRaisesRegex(
            PUBLISHER.PublisherError,
            "Call Flow JSON is not valid JSON: nesting is too deep",
        ):
            PUBLISHER._parse_json_bytes(b"{}", "Call Flow JSON")

    def test_main_publishes_large_payload_after_adding_delivery_metadata(self):
        captured = {}

        def capture_publish(_inbox, request_id, payload):
            captured["request_id"] = request_id
            captured["payload"] = payload
            return Path("request-%s.json" % request_id)

        with tempfile.TemporaryDirectory() as directory:
            input_path = Path(directory) / "large-flow.json"
            input_path.write_bytes(self.large_payload)
            with mock.patch.object(PUBLISHER, "_validate_sources"), mock.patch.object(
                PUBLISHER,
                "_exchange_directories",
                return_value=(Path("inbox"), Path("receipts")),
            ), mock.patch.object(
                PUBLISHER,
                "_publish_request",
                side_effect=capture_publish,
            ), mock.patch.object(
                PUBLISHER,
                "_wait_for_receipt",
                return_value=0,
            ):
                result = PUBLISHER.main([str(input_path)])

        self.assertEqual(0, result)
        self.assertGreater(len(captured["payload"]), 2 * 1024 * 1024)
        request = json.loads(captured["payload"].decode("utf-8"))
        self.assertEqual(captured["request_id"], request["_delivery"]["requestId"])
        self.assertEqual(200, len(request["nodes"]))

    def test_receipt_reader_keeps_its_independent_size_guard(self):
        with tempfile.TemporaryDirectory() as directory:
            receipt_path = Path(directory) / "receipt-request-id.json"
            receipt_path.write_bytes(b" " * (PUBLISHER.MAX_RECEIPT_BYTES + 2))
            os.chmod(receipt_path, 0o600)

            read_sizes = []
            real_fdopen = os.fdopen

            class RecordingFile:
                def __init__(self, wrapped):
                    self.wrapped = wrapped

                def __enter__(self):
                    self.wrapped.__enter__()
                    return self

                def __exit__(self, *args):
                    return self.wrapped.__exit__(*args)

                def read(self, size=-1):
                    read_sizes.append(size)
                    return self.wrapped.read(size)

            def recording_fdopen(descriptor, mode):
                return RecordingFile(real_fdopen(descriptor, mode))

            with mock.patch.object(
                PUBLISHER.os,
                "fdopen",
                side_effect=recording_fdopen,
            ), self.assertRaises(PUBLISHER.PublisherError):
                PUBLISHER._read_receipt(receipt_path, "request-id")

            self.assertEqual([PUBLISHER.MAX_RECEIPT_BYTES + 1], read_sizes)

    def test_deeply_nested_receipt_is_reported_without_a_traceback(self):
        with tempfile.TemporaryDirectory() as directory:
            receipt_path = Path(directory) / "receipt-request-id.json"
            receipt_path.write_bytes(b"{}")
            os.chmod(receipt_path, 0o600)

            with mock.patch.object(
                PUBLISHER.json,
                "loads",
                side_effect=RecursionError("maximum recursion depth exceeded"),
            ), self.assertRaisesRegex(
                PUBLISHER.PublisherError,
                "plugin receipt is not valid JSON: nesting is too deep",
            ):
                PUBLISHER._read_receipt(receipt_path, "request-id")


if __name__ == "__main__":
    unittest.main()
