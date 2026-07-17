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
    / "publish_analysis_request.py"
)
SPEC = importlib.util.spec_from_file_location(
    "ai_call_flow_analysis_publisher", PUBLISHER_PATH
)
PUBLISHER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(PUBLISHER)


def analysis_request(path="source.kt", line=1, column=1):
    return {
        "version": "1.0",
        "type": "analysis-request",
        "topic": "分析登录按钮到首页的执行路径",
        "entry": {
            "path": path,
            "line": line,
            "column": column,
            "symbol": "com.example.LoginFragment.onLoginClick",
        },
        "strategy": {
            "mode": "static-and-live",
            "scope": "project-code",
        },
    }


def accepted_receipt(request_id="request-id"):
    return {
        "version": "3.0",
        "requestId": request_id,
        "status": "accepted",
        "completedAtEpochMs": 1_784_044_800_521,
        "topic": "分析登录按钮到首页的执行路径",
        "entry": analysis_request()["entry"],
        "generated": {
            "nodeCount": 12,
            "edgeCount": 14,
            "entryNodeId": "login-click",
        },
    }


def rejected_receipt(request_id="request-id"):
    return {
        "version": "3.0",
        "requestId": request_id,
        "status": "rejected",
        "completedAtEpochMs": 1_784_044_800_521,
        "error": {
            "code": "INVALID_ANALYSIS_REQUEST",
            "message": "entry symbol cannot be resolved",
        },
    }


class PublishAnalysisRequestTest(unittest.TestCase):
    def test_exact_analysis_request_is_accepted(self):
        request = analysis_request()

        self.assertIs(request, PUBLISHER._validate_analysis_request(request))

    def test_only_analysis_request_top_level_shape_is_accepted(self):
        for field in ("projectRoot", "nodes", "edges", "contexts", "frames"):
            request = analysis_request()
            request[field] = []
            with self.subTest(field=field), self.assertRaisesRegex(
                PUBLISHER.PublisherError,
                "analysis request has unsupported fields: %s" % field,
            ):
                PUBLISHER._validate_analysis_request(request)

        request = analysis_request()
        request["_delivery"] = {}
        with self.assertRaisesRegex(
            PUBLISHER.PublisherError,
            "analysis request has unsupported fields: _delivery",
        ):
            PUBLISHER._validate_analysis_request(request)

    def test_version_type_entry_and_strategy_are_exact(self):
        cases = (
            (("version",), "2.0", 'version must be "1.0"'),
            (("type",), "call-flow", 'type must be "analysis-request"'),
            (("entry", "symbol"), " ", "symbol must be a non-blank string"),
            (
                ("strategy", "mode"),
                "static",
                'strategy.mode must be "static-and-live"',
            ),
            (
                ("strategy", "scope"),
                "workspace",
                'strategy.scope must be "project-code"',
            ),
        )
        for path, value, message in cases:
            request = analysis_request()
            target = request
            for segment in path[:-1]:
                target = target[segment]
            target[path[-1]] = value
            with self.subTest(path=path), self.assertRaisesRegex(
                PUBLISHER.PublisherError, message
            ):
                PUBLISHER._validate_analysis_request(request)

        request = analysis_request()
        del request["entry"]["symbol"]
        with self.assertRaisesRegex(
            PUBLISHER.PublisherError,
            "analysis request.entry is missing: symbol",
        ):
            PUBLISHER._validate_analysis_request(request)

        request = analysis_request()
        request["strategy"]["depth"] = 3
        with self.assertRaisesRegex(
            PUBLISHER.PublisherError,
            "analysis request.strategy has unsupported fields: depth",
        ):
            PUBLISHER._validate_analysis_request(request)

    def test_entry_path_and_coordinates_are_validated(self):
        invalid_paths = (
            "/tmp/source.kt",
            r"C:\\project\\source.kt",
            "file:source.kt",
            "src\\source.kt",
            "../source.kt",
            "src//source.kt",
        )
        for path in invalid_paths:
            request = analysis_request(path=path)
            with self.subTest(path=path), self.assertRaises(PUBLISHER.PublisherError):
                PUBLISHER._validate_analysis_request(request)

        for field, value in (("line", 0), ("column", True), ("column", 2**31)):
            request = analysis_request()
            request["entry"][field] = value
            with self.subTest(field=field, value=value), self.assertRaises(
                PUBLISHER.PublisherError
            ):
                PUBLISHER._validate_analysis_request(request)

    def test_entry_source_is_inside_project_and_uses_utf16_columns(self):
        with tempfile.TemporaryDirectory() as directory:
            project = Path(directory) / "project"
            project.mkdir()
            source = project / "source.kt"
            source.write_text("fun entry() = \"😀\"\n", encoding="utf-8")

            request = analysis_request(column=19)
            PUBLISHER._validate_entry_source(request, project)

            request["entry"]["column"] = 20
            with self.assertRaisesRegex(
                PUBLISHER.PublisherError,
                "column exceeds the UTF-16 source line length",
            ):
                PUBLISHER._validate_entry_source(request, project)

            request = analysis_request(line=2)
            PUBLISHER._validate_entry_source(request, project)

            request["entry"]["line"] = 3
            with self.assertRaisesRegex(
                PUBLISHER.PublisherError,
                "line exceeds the source line count",
            ):
                PUBLISHER._validate_entry_source(request, project)

    @unittest.skipIf(os.name == "nt", "symlink creation is not generally available")
    def test_entry_source_symlink_cannot_escape_project(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            project = root / "project"
            project.mkdir()
            outside = root / "outside.kt"
            outside.write_text("fun outside() = Unit\n", encoding="utf-8")
            (project / "source.kt").symlink_to(outside)

            with self.assertRaisesRegex(
                PUBLISHER.PublisherError,
                "not a source file inside the current project",
            ):
                PUBLISHER._validate_entry_source(analysis_request(), project)

    def test_json_parser_rejects_duplicates_and_constants_without_a_size_limit(self):
        with self.assertRaisesRegex(
            PUBLISHER.PublisherError, "duplicate JSON field: version"
        ):
            PUBLISHER._parse_json_bytes(
                b'{"version":"1.0","version":"1.0"}', "analysis request JSON"
            )

        with self.assertRaisesRegex(PUBLISHER.PublisherError, "NaN is not allowed"):
            PUBLISHER._parse_json_bytes(b'{"value":NaN}', "analysis request JSON")

        large = b" " * (2 * 1024 * 1024 + 1) + b'{"value":1}'
        self.assertEqual(
            {"value": 1},
            PUBLISHER._parse_json_bytes(large, "analysis request JSON"),
        )

        with mock.patch.object(
            PUBLISHER.json,
            "loads",
            side_effect=RecursionError("maximum recursion depth exceeded"),
        ), self.assertRaisesRegex(
            PUBLISHER.PublisherError,
            "analysis request JSON is not valid JSON: nesting is too deep",
        ):
            PUBLISHER._parse_json_bytes(b"{}", "analysis request JSON")

    def test_delete_input_reads_then_removes_private_temporary_file(self):
        payload = json.dumps(analysis_request()).encode("utf-8")
        with tempfile.TemporaryDirectory() as directory:
            input_path = Path(directory) / "analysis.json"
            input_path.write_bytes(payload)
            os.chmod(input_path, 0o600)

            self.assertEqual(
                payload,
                PUBLISHER._read_input(input_path, True),
            )
            self.assertFalse(input_path.exists())

    def test_delete_input_refuses_project_file(self):
        with tempfile.TemporaryDirectory() as directory:
            project = Path(directory) / "project"
            project.mkdir()
            input_path = project / "analysis.json"
            input_path.write_text(json.dumps(analysis_request()), encoding="utf-8")
            os.chmod(input_path, 0o600)
            with mock.patch.object(
                PUBLISHER.Path, "cwd", return_value=project
            ), self.assertRaisesRegex(
                PUBLISHER.PublisherError,
                "refuses to delete a file inside the source project",
            ):
                PUBLISHER._read_input(input_path, True)
            self.assertTrue(input_path.exists())

    def test_publish_request_is_private_atomic_and_never_overwrites(self):
        with tempfile.TemporaryDirectory() as directory:
            inbox = Path(directory)
            os.chmod(inbox, 0o700)
            final = PUBLISHER._publish_request(inbox, "request-id", b"first")

            self.assertEqual(b"first", final.read_bytes())
            if os.name != "nt":
                self.assertEqual(0, stat.S_IMODE(final.stat().st_mode) & 0o077)
            self.assertFalse((inbox / ".request-request-id.tmp").exists())

            with self.assertRaisesRegex(
                PUBLISHER.PublisherError, "refusing to overwrite"
            ):
                PUBLISHER._publish_request(inbox, "request-id", b"second")
            self.assertEqual(b"first", final.read_bytes())

    def test_main_injects_only_v3_delivery_metadata(self):
        captured = {}

        def capture_publish(_inbox, request_id, payload):
            captured["request_id"] = request_id
            captured["payload"] = payload
            return Path("request-%s.json" % request_id)

        with tempfile.TemporaryDirectory() as directory:
            input_path = Path(directory) / "analysis.json"
            input_path.write_text(json.dumps(analysis_request()), encoding="utf-8")
            with mock.patch.object(
                PUBLISHER, "_validate_entry_source"
            ), mock.patch.object(
                PUBLISHER,
                "_exchange_directories",
                return_value=(Path("inbox"), Path("receipts")),
            ), mock.patch.object(
                PUBLISHER, "_publish_request", side_effect=capture_publish
            ), mock.patch.object(
                PUBLISHER, "_wait_for_receipt", return_value=0
            ):
                result = PUBLISHER.main([str(input_path)])

        self.assertEqual(0, result)
        payload = json.loads(captured["payload"].decode("utf-8"))
        self.assertEqual(
            {
                "version",
                "type",
                "topic",
                "entry",
                "strategy",
                "_delivery",
            },
            set(payload),
        )
        self.assertNotIn("projectRoot", payload)
        self.assertNotIn("nodes", payload)
        self.assertEqual(
            {
                "version",
                "requestId",
                "createdAtEpochMs",
                "expiresAtEpochMs",
            },
            set(payload["_delivery"]),
        )
        self.assertEqual("3.0", payload["_delivery"]["version"])
        self.assertEqual(
            captured["request_id"], payload["_delivery"]["requestId"]
        )

    def test_main_rejects_invalid_timeout_before_reading_input(self):
        for timeout in ("nan", "inf", "0", "301"):
            with self.subTest(timeout=timeout), mock.patch.object(
                PUBLISHER, "_read_input"
            ) as read_input, self.assertRaises(PUBLISHER.PublisherError):
                PUBLISHER.main(["missing.json", "--timeout", timeout])
            read_input.assert_not_called()

    def test_v3_protocol_directory_is_the_only_exchange_directory(self):
        self.assertEqual("file-ipc-v3", PUBLISHER.PROTOCOL_DIRECTORY)
        self.assertEqual("3.0", PUBLISHER.DELIVERY_VERSION)

    def test_accepted_and_rejected_v3_receipts_are_validated(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "receipt-request-id.json"
            for receipt in (accepted_receipt(), rejected_receipt()):
                path.write_text(json.dumps(receipt), encoding="utf-8")
                os.chmod(path, 0o600)
                self.assertEqual(
                    receipt["status"],
                    PUBLISHER._read_receipt(path, "request-id")["status"],
                )

    def test_receipts_reject_legacy_or_extra_fields(self):
        cases = []
        legacy = accepted_receipt()
        legacy["version"] = "2.0"
        cases.append((legacy, "unsupported version"))

        wrong_request = accepted_receipt()
        wrong_request["requestId"] = "other"
        cases.append((wrong_request, "requestId does not match"))

        extra = accepted_receipt()
        extra["callFlowFile"] = "/tmp/flow.json"
        cases.append((extra, "unsupported fields: callFlowFile"))

        invalid_generated = accepted_receipt()
        invalid_generated["generated"]["nodeCount"] = 0
        cases.append((invalid_generated, "nodeCount must be at least 1"))

        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "receipt-request-id.json"
            for receipt, message in cases:
                path.write_text(json.dumps(receipt), encoding="utf-8")
                os.chmod(path, 0o600)
                with self.subTest(message=message), self.assertRaisesRegex(
                    PUBLISHER.PublisherError, message
                ):
                    PUBLISHER._read_receipt(path, "request-id")

    def test_receipt_reader_has_no_total_json_size_limit(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "receipt-request-id.json"
            path.write_bytes(
                b" " * (2 * 1024 * 1024 + 1)
                + json.dumps(accepted_receipt()).encode("utf-8")
            )
            os.chmod(path, 0o600)
            self.assertEqual(
                "accepted",
                PUBLISHER._read_receipt(path, "request-id")["status"],
            )


if __name__ == "__main__":
    unittest.main()
