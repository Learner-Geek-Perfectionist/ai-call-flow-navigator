import importlib.util
import json
import os
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

    def test_main_publishes_large_payload_after_adding_delivery_metadata(self):
        captured = {}

        def capture_publish(inbox, request_id, payload):
            captured["inbox"] = inbox
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
            receipt_path.write_bytes(b" " * (PUBLISHER.MAX_RECEIPT_BYTES + 1))
            os.chmod(receipt_path, 0o600)

            with self.assertRaises(PUBLISHER.PublisherError):
                PUBLISHER._read_receipt(receipt_path, "request-id")


if __name__ == "__main__":
    unittest.main()
