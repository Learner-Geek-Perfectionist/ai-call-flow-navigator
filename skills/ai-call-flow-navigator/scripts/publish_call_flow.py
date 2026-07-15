#!/usr/bin/env python3
"""Validate and atomically deliver a semantic Call Flow to Android Studio."""

from __future__ import print_function

import argparse
import json
import os
import re
import stat
import subprocess
import sys
import tempfile
import time
import uuid
from pathlib import Path


CALL_FLOW_VERSION = "1.0"
DELIVERY_VERSION = "2.0"
CHANNEL_DIRECTORY = "youngx-ai-call-flow-navigator"
PROTOCOL_DIRECTORY = "file-ipc-v2"
MAX_RECEIPT_BYTES = 1024 * 1024
MAX_INT32 = 2_147_483_647

NODE_KINDS = {
    "entry", "declaration", "call", "branch", "return", "async", "callback", "note"
}
EDGE_KINDS = {
    "next", "step_into", "step_over", "step_out", "branch_true", "branch_false",
    "return", "async", "callback"
}
WINDOWS_ABSOLUTE_PATH = re.compile(r"^[A-Za-z]:[/\\]")
URI_LIKE_PATH = re.compile(r"^[A-Za-z][A-Za-z0-9+.-]*:")


class PublisherError(Exception):
    """A user-actionable validation or delivery failure."""


def _parser():
    parser = argparse.ArgumentParser(
        description=(
            "Validate a Call Flow content JSON file, add delivery metadata, and send it "
            "to the local AI Call Flow Navigator Android Studio plugin."
        )
    )
    parser.add_argument("flow_json", help="UTF-8 Call Flow content JSON without _delivery")
    parser.add_argument(
        "--temp-root",
        help=(
            "Android Studio's java.io.tmpdir. Omit for the operating system default; "
            "use only when Android Studio was launched with a custom value."
        ),
    )
    parser.add_argument(
        "--timeout",
        type=float,
        default=30.0,
        help="seconds to wait for a plugin receipt (default: 30)",
    )
    parser.add_argument(
        "--expires-in",
        type=int,
        default=120,
        help="request lifetime in seconds (default: 120; allowed: 10-300)",
    )
    parser.add_argument(
        "--keep-receipt",
        action="store_true",
        help="leave the consumed receipt in the temporary receipts directory",
    )
    parser.add_argument(
        "--delete-input",
        action="store_true",
        help=(
            "securely delete the semantic input after reading; requires a private regular file "
            "inside the system temporary directory and outside the source project"
        ),
    )
    return parser


def _reject_json_constant(value):
    raise PublisherError("JSON constant %s is not allowed" % value)


def _object_without_duplicates(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise PublisherError("duplicate JSON field: %s" % key)
        result[key] = value
    return result


def _parse_json_bytes(data, label, maximum=None):
    if maximum is not None and len(data) > maximum:
        raise PublisherError("%s is larger than %d bytes" % (label, maximum))
    try:
        text = data.decode("utf-8")
    except UnicodeDecodeError as error:
        raise PublisherError("%s is not valid UTF-8: %s" % (label, error))
    try:
        return json.loads(
            text,
            object_pairs_hook=_object_without_duplicates,
            parse_constant=_reject_json_constant,
        )
    except PublisherError:
        raise
    except (TypeError, ValueError) as error:
        raise PublisherError("%s is not valid JSON: %s" % (label, error))


def _expect_object(value, name):
    if not isinstance(value, dict):
        raise PublisherError("%s must be a JSON object" % name)
    return value


def _expect_exact_fields(value, name, required, optional=()):
    keys = set(value)
    missing = set(required) - keys
    unknown = keys - set(required) - set(optional)
    if missing:
        raise PublisherError("%s is missing: %s" % (name, ", ".join(sorted(missing))))
    if unknown:
        raise PublisherError("%s has unsupported fields: %s" % (name, ", ".join(sorted(unknown))))


def _is_iso_control(character):
    code = ord(character)
    return code <= 0x1F or 0x7F <= code <= 0x9F


def _utf16_length(value):
    try:
        return len(value.encode("utf-16-le")) // 2
    except UnicodeEncodeError as error:
        raise PublisherError("text contains an invalid Unicode surrogate") from error


def _text(value, name, maximum, identifier=False):
    if not isinstance(value, str) or not value.strip():
        raise PublisherError("%s must be a non-blank string" % name)
    if _utf16_length(value) > maximum:
        raise PublisherError("%s must be at most %d characters" % (name, maximum))
    if identifier and value != value.strip():
        raise PublisherError("%s must not have leading or trailing whitespace" % name)
    if identifier and any(_is_iso_control(character) for character in value):
        raise PublisherError("%s must not contain control characters" % name)
    return value


def _enum(value, name, supported):
    if not isinstance(value, str) or value not in supported:
        raise PublisherError("%s is not supported: %r" % (name, value))
    return value


def _optional_text(container, field, name, maximum):
    if field not in container:
        return None
    return _text(container[field], name, maximum)


def _positive_int(value, name):
    if isinstance(value, bool) or not isinstance(value, int):
        raise PublisherError("%s must be an integer" % name)
    if value < 1 or value > MAX_INT32:
        raise PublisherError("%s must be between 1 and %d" % (name, MAX_INT32))
    return value


def _validate_relative_path(value, name):
    path = _text(value, name, 4096)
    if path != path.strip():
        raise PublisherError("%s must not have leading or trailing whitespace" % name)
    if (
        path.startswith("/")
        or path.startswith("\\")
        or WINDOWS_ABSOLUTE_PATH.match(path)
        or URI_LIKE_PATH.match(path)
    ):
        raise PublisherError("%s must be a repository-relative path" % name)
    if "\\" in path:
        raise PublisherError("%s must use forward slashes" % name)
    if any(_is_iso_control(character) for character in path):
        raise PublisherError("%s must not contain control characters" % name)
    segments = path.split("/")
    if any(segment in ("", ".", "..") for segment in segments):
        raise PublisherError("%s must be a normalized repository-relative path" % name)
    return path


def _validate_location(value, name):
    location = _expect_object(value, name)
    _expect_exact_fields(
        location,
        name,
        ("path", "line", "column"),
        ("endLine", "endColumn", "symbol", "anchorText"),
    )
    _validate_relative_path(location["path"], name + ".path")
    line = _positive_int(location["line"], name + ".line")
    column = _positive_int(location["column"], name + ".column")

    has_end_line = "endLine" in location
    has_end_column = "endColumn" in location
    if has_end_line != has_end_column:
        raise PublisherError("%s.endLine and %s.endColumn must be provided together" % (name, name))
    if has_end_line:
        end_line = _positive_int(location["endLine"], name + ".endLine")
        end_column = _positive_int(location["endColumn"], name + ".endColumn")
        if (end_line, end_column) < (line, column):
            raise PublisherError("%s end position must not precede its start" % name)

    _optional_text(location, "symbol", name + ".symbol", 512)
    _optional_text(location, "anchorText", name + ".anchorText", 16384)
    return location


def _validate_call_flow(value):
    flow = _expect_object(value, "Call Flow")
    if "_delivery" in flow:
        raise PublisherError("Call Flow content must not contain _delivery; the publisher generates it")
    _expect_exact_fields(
        flow,
        "Call Flow",
        ("version", "title", "nodes", "edges", "entry"),
        ("project",),
    )
    if flow["version"] != CALL_FLOW_VERSION:
        raise PublisherError('Call Flow.version must be "%s"' % CALL_FLOW_VERSION)
    _text(flow["title"], "Call Flow.title", 512)

    if "project" in flow:
        project = _expect_object(flow["project"], "Call Flow.project")
        _expect_exact_fields(project, "Call Flow.project", ("revision",))
        _text(project["revision"], "Call Flow.project.revision", 4096)

    nodes = flow["nodes"]
    if not isinstance(nodes, list) or not nodes:
        raise PublisherError("Call Flow.nodes must be a non-empty array")
    if len(nodes) > 10_000:
        raise PublisherError("Call Flow.nodes must contain at most 10000 items")

    identifiers = set()
    for index, raw_node in enumerate(nodes):
        name = "Call Flow.nodes[%d]" % index
        node = _expect_object(raw_node, name)
        _expect_exact_fields(node, name, ("id", "kind", "location", "summary"))
        identifier = _text(node["id"], name + ".id", 256, identifier=True)
        if identifier in identifiers:
            raise PublisherError("duplicate node id: %s" % identifier)
        identifiers.add(identifier)
        _enum(node["kind"], name + ".kind", NODE_KINDS)
        _validate_location(node["location"], name + ".location")
        _text(node["summary"], name + ".summary", 16384)

    edges = flow["edges"]
    if not isinstance(edges, list):
        raise PublisherError("Call Flow.edges must be an array")
    if len(edges) > 50_000:
        raise PublisherError("Call Flow.edges must contain at most 50000 items")
    for index, raw_edge in enumerate(edges):
        name = "Call Flow.edges[%d]" % index
        edge = _expect_object(raw_edge, name)
        _expect_exact_fields(edge, name, ("from", "to", "kind"), ("label",))
        source = _text(edge["from"], name + ".from", 256, identifier=True)
        target = _text(edge["to"], name + ".to", 256, identifier=True)
        if source not in identifiers:
            raise PublisherError("%s.from references an unknown node: %s" % (name, source))
        if target not in identifiers:
            raise PublisherError("%s.to references an unknown node: %s" % (name, target))
        _enum(edge["kind"], name + ".kind", EDGE_KINDS)
        _optional_text(edge, "label", name + ".label", 512)

    entry = _text(flow["entry"], "Call Flow.entry", 256, identifier=True)
    if entry not in identifiers:
        raise PublisherError("Call Flow.entry references an unknown node: %s" % entry)
    return flow


def _source_lines(path):
    try:
        data = path.read_bytes()
        text = data.decode("utf-8-sig")
    except OSError as error:
        raise PublisherError("cannot read source file %s: %s" % (path, error))
    except UnicodeDecodeError as error:
        raise PublisherError("source file is not UTF-8: %s (%s)" % (path, error))
    return text.replace("\r\n", "\n").replace("\r", "\n").split("\n")


def _validate_coordinate(lines, line, column, name):
    if line > len(lines):
        raise PublisherError("%s line %d is outside a %d-line source file" % (name, line, len(lines)))
    maximum_column = _utf16_length(lines[line - 1]) + 1
    if column > maximum_column:
        raise PublisherError(
            "%s column %d is outside source line %d (maximum %d)"
            % (name, column, line, maximum_column)
        )


def _utf16_column(line, codepoint_index):
    return _utf16_length(line[:codepoint_index]) + 1


def _validate_sources(flow, source_root):
    try:
        real_root = source_root.resolve(strict=True)
    except OSError as error:
        raise PublisherError("cannot resolve the current source root: %s" % error)
    if not real_root.is_dir():
        raise PublisherError("current source root is not a directory: %s" % real_root)

    cache = {}
    for index, node in enumerate(flow["nodes"]):
        location = node["location"]
        relative = location["path"]
        name = "Call Flow.nodes[%d].location" % index
        candidate = real_root.joinpath(*relative.split("/"))
        try:
            real_candidate = candidate.resolve(strict=True)
            real_candidate.relative_to(real_root)
        except (OSError, ValueError) as error:
            raise PublisherError("%s.path is not a source file inside the current repository: %s" % (name, relative)) from error
        if not real_candidate.is_file():
            raise PublisherError("%s.path is not a regular source file: %s" % (name, relative))

        key = str(real_candidate)
        lines = cache.get(key)
        if lines is None:
            lines = _source_lines(real_candidate)
            cache[key] = lines

        line = location["line"]
        column = location["column"]
        _validate_coordinate(lines, line, column, name)
        if "endLine" in location:
            _validate_coordinate(lines, location["endLine"], location["endColumn"], name + " end")

        anchor = location.get("anchorText")
        if anchor is not None:
            if "\n" in anchor or "\r" in anchor:
                raise PublisherError("%s.anchorText must stay on one source line" % name)
            first = max(0, line - 1 - 20)
            last = min(len(lines), line + 20)
            matches = []
            for line_index in range(first, last):
                offset = 0
                while True:
                    found = lines[line_index].find(anchor, offset)
                    if found < 0:
                        break
                    matches.append((line_index + 1, _utf16_column(lines[line_index], found)))
                    offset = found + 1
            if not matches:
                raise PublisherError("%s.anchorText was not found within 20 lines" % name)
            if len(matches) > 1:
                raise PublisherError("%s.anchorText is ambiguous within 20 lines" % name)
            if matches[0] != (line, column):
                raise PublisherError(
                    "%s coordinates are %d:%d but anchorText starts at %d:%d"
                    % (name, line, column, matches[0][0], matches[0][1])
                )


def _current_uid():
    getter = getattr(os, "getuid", None)
    return getter() if getter is not None else None


def _lstat(path, label):
    try:
        return path.lstat()
    except OSError as error:
        raise PublisherError("%s does not exist or cannot be inspected: %s" % (label, error))


def _verify_owner(info, label):
    uid = _current_uid()
    if uid is not None and info.st_uid != uid:
        raise PublisherError("%s is not owned by the current user" % label)


def _existing_private_directory(path, label):
    info = _lstat(path, label)
    if stat.S_ISLNK(info.st_mode) or not stat.S_ISDIR(info.st_mode):
        raise PublisherError("%s must be a real directory, not a symlink" % label)
    _verify_owner(info, label)
    if _current_uid() is not None and info.st_mode & 0o077:
        raise PublisherError("%s permissions are not private" % label)
    return path


def _existing_private_file(path, label):
    info = _lstat(path, label)
    if stat.S_ISLNK(info.st_mode) or not stat.S_ISREG(info.st_mode):
        raise PublisherError("%s must be a real regular file, not a symlink" % label)
    _verify_owner(info, label)
    if _current_uid() is not None and info.st_mode & 0o077:
        raise PublisherError("%s permissions are not private" % label)
    return path


def _is_within(path, root):
    try:
        path.relative_to(root)
        return True
    except ValueError:
        return False


def _temporary_root_candidates(explicit=None):
    if explicit:
        raw_candidates = [Path(explicit).expanduser()]
    else:
        raw_candidates = [Path(tempfile.gettempdir())]
        for name in ("TMPDIR", "TEMP", "TMP"):
            value = os.environ.get(name)
            if value:
                raw_candidates.append(Path(value).expanduser())
        if sys.platform == "darwin":
            try:
                result = subprocess.run(
                    ["/usr/bin/getconf", "DARWIN_USER_TEMP_DIR"],
                    check=True,
                    capture_output=True,
                    text=True,
                    timeout=2,
                )
                value = result.stdout.strip()
                if value:
                    raw_candidates.append(Path(value))
            except (OSError, subprocess.SubprocessError):
                pass
        elif os.name == "nt":
            local_app_data = os.environ.get("LOCALAPPDATA")
            if local_app_data:
                raw_candidates.append(Path(local_app_data) / "Temp")
        else:
            raw_candidates.extend((Path("/tmp"), Path("/var/tmp")))

    resolved = []
    seen = set()
    for candidate in raw_candidates:
        try:
            real = candidate.resolve(strict=True)
        except OSError:
            continue
        key = os.path.normcase(str(real))
        if key not in seen and real.is_dir():
            seen.add(key)
            resolved.append(real)
    if explicit and not resolved:
        raise PublisherError("cannot resolve --temp-root as an existing directory")
    return resolved


def _read_input(path, delete_input):
    if not delete_input:
        try:
            with path.open("rb") as input_file:
                return input_file.read()
        except OSError as error:
            raise PublisherError("cannot read Call Flow JSON: %s" % error)

    if not path.is_absolute():
        raise PublisherError("--delete-input requires an absolute secure temporary-file path")

    label = "temporary Call Flow JSON"
    _existing_private_file(path, label)
    initial = _lstat(path, label)
    if initial.st_nlink != 1:
        raise PublisherError("temporary Call Flow JSON must not have hard links")
    try:
        real_input = path.resolve(strict=True)
        real_source = Path.cwd().resolve(strict=True)
    except OSError as error:
        raise PublisherError("cannot resolve the temporary Call Flow JSON: %s" % error)
    temporary_roots = _temporary_root_candidates()
    if not any(_is_within(real_input, root) for root in temporary_roots):
        raise PublisherError("--delete-input only deletes files inside a recognized system temporary directory")
    if _is_within(real_input, real_source):
        raise PublisherError("--delete-input refuses to delete a file inside the source project")

    flags = os.O_RDONLY
    if hasattr(os, "O_BINARY"):
        flags |= os.O_BINARY
    if hasattr(os, "O_NOFOLLOW"):
        flags |= os.O_NOFOLLOW
    descriptor = None
    try:
        descriptor = os.open(str(path), flags)
        opened = os.fstat(descriptor)
        if not stat.S_ISREG(opened.st_mode):
            raise PublisherError("temporary Call Flow JSON is not a regular file")
        _verify_owner(opened, label)
        if _current_uid() is not None and opened.st_mode & 0o077:
            raise PublisherError("temporary Call Flow JSON permissions are not private")
        if (opened.st_dev, opened.st_ino) != (initial.st_dev, initial.st_ino):
            raise PublisherError("temporary Call Flow JSON changed before it could be read")
        with os.fdopen(descriptor, "rb") as input_file:
            descriptor = None
            data = input_file.read()
    except OSError as error:
        raise PublisherError("cannot securely read Call Flow JSON: %s" % error)
    finally:
        if descriptor is not None:
            os.close(descriptor)

    current = _lstat(path, label)
    if (current.st_dev, current.st_ino) != (initial.st_dev, initial.st_ino):
        raise PublisherError("temporary Call Flow JSON changed before it could be deleted")
    try:
        path.unlink()
    except OSError as error:
        raise PublisherError("Call Flow JSON was read but could not be deleted: %s" % error)
    return data


def _exchange_at_root(temp_root):
    channel = _existing_private_directory(
        temp_root / CHANNEL_DIRECTORY,
        "AI Call Flow channel directory",
    )
    protocol = _existing_private_directory(
        channel / PROTOCOL_DIRECTORY,
        "AI Call Flow file-ipc-v2 directory",
    )
    inbox = _existing_private_directory(protocol / "inbox", "AI Call Flow inbox")
    _existing_private_directory(protocol / "processing", "AI Call Flow processing directory")
    receipts = _existing_private_directory(protocol / "receipts", "AI Call Flow receipts directory")
    _existing_private_file(protocol / ".consumer.lock", "AI Call Flow consumer lock")
    return inbox, receipts


def _exchange_directories(temp_root_argument):
    candidates = _temporary_root_candidates(temp_root_argument)
    matches = []
    failures = []
    for candidate in candidates:
        protocol = candidate / CHANNEL_DIRECTORY / PROTOCOL_DIRECTORY
        try:
            protocol.lstat()
        except FileNotFoundError:
            continue
        except OSError as error:
            failures.append("%s: %s" % (candidate, error))
            continue
        try:
            matches.append((candidate, _exchange_at_root(candidate)))
        except PublisherError as error:
            failures.append("%s: %s" % (candidate, error))

    if len(matches) == 1:
        return matches[0][1]
    if len(matches) > 1:
        roots = ", ".join(str(root) for root, _ in matches)
        raise PublisherError(
            "multiple AI Call Flow inboxes were found (%s); pass the Android Studio java.io.tmpdir "
            "with --temp-root" % roots
        )
    checked = ", ".join(str(path) for path in candidates) or "no usable temporary directories"
    detail = (" Invalid candidates: " + "; ".join(failures)) if failures else ""
    raise PublisherError(
        "AI Call Flow inbox was not found. Open the project in Android Studio with the plugin "
        "enabled. Checked: %s.%s" % (checked, detail)
    )


def _directory_fsync(path):
    if os.name == "nt":
        return
    descriptor = None
    try:
        descriptor = os.open(str(path), os.O_RDONLY)
        os.fsync(descriptor)
    except OSError:
        pass
    finally:
        if descriptor is not None:
            os.close(descriptor)


def _publish_request(inbox, request_id, payload):
    temporary = inbox / (".request-%s.tmp" % request_id)
    final = inbox / ("request-%s.json" % request_id)
    flags = os.O_WRONLY | os.O_CREAT | os.O_EXCL
    if hasattr(os, "O_BINARY"):
        flags |= os.O_BINARY
    if hasattr(os, "O_NOFOLLOW"):
        flags |= os.O_NOFOLLOW

    descriptor = None
    try:
        descriptor = os.open(str(temporary), flags, 0o600)
        with os.fdopen(descriptor, "wb") as output:
            descriptor = None
            output.write(payload)
            output.flush()
            os.fsync(output.fileno())
        if os.name != "nt":
            os.chmod(str(temporary), 0o600, follow_symlinks=False)

        try:
            try:
                os.link(str(temporary), str(final), follow_symlinks=False)
            except TypeError:
                os.link(str(temporary), str(final))
        except FileExistsError as error:
            raise PublisherError("request destination already exists; refusing to overwrite it") from error
        except OSError as error:
            raise PublisherError(
                "cannot atomically publish without overwriting; the inbox filesystem must support hard links: %s"
                % error
            )
        try:
            temporary.unlink()
        except OSError as error:
            print(
                "warning: request was published but its temporary hard link could not be removed: %s"
                % error,
                file=sys.stderr,
            )
        _directory_fsync(inbox)
        return final
    except FileExistsError as error:
        raise PublisherError("temporary request already exists; refusing to overwrite it") from error
    finally:
        if descriptor is not None:
            os.close(descriptor)
        try:
            temporary.unlink()
        except FileNotFoundError:
            pass
        except OSError:
            pass


def _read_receipt(path, request_id):
    _existing_private_file(path, "AI Call Flow receipt")
    try:
        data = path.read_bytes()
    except OSError as error:
        raise PublisherError("cannot read plugin receipt: %s" % error)
    receipt = _expect_object(_parse_json_bytes(data, "plugin receipt", MAX_RECEIPT_BYTES), "plugin receipt")
    if receipt.get("version") != DELIVERY_VERSION:
        raise PublisherError("plugin receipt has an unsupported version")
    if receipt.get("requestId") != request_id:
        raise PublisherError("plugin receipt requestId does not match the request")
    if receipt.get("status") not in ("accepted", "rejected"):
        raise PublisherError("plugin receipt has an unsupported status")
    completed = receipt.get("completedAtEpochMs")
    if isinstance(completed, bool) or not isinstance(completed, int) or completed < 0:
        raise PublisherError("plugin receipt has an invalid completedAtEpochMs")
    if receipt["status"] == "accepted":
        _text(receipt.get("callFlowFile"), "plugin receipt.callFlowFile", 32768)
        node_count = receipt.get("nodeCount")
        edge_count = receipt.get("edgeCount")
        if isinstance(node_count, bool) or not isinstance(node_count, int) or node_count < 1:
            raise PublisherError("plugin receipt has an invalid nodeCount")
        if isinstance(edge_count, bool) or not isinstance(edge_count, int) or edge_count < 0:
            raise PublisherError("plugin receipt has an invalid edgeCount")
        _text(receipt.get("entry"), "plugin receipt.entry", 256, identifier=True)
    else:
        error = _expect_object(receipt.get("error"), "plugin receipt.error")
        _text(error.get("code"), "plugin receipt.error.code", 256, identifier=True)
        _text(error.get("message"), "plugin receipt.error.message", 16384)
    return receipt


def _print_publisher_outcome(status, request_id, receipt_path, message, retry_safe):
    outcome = {
        "publisherStatus": status,
        "requestId": request_id,
        "receiptFile": str(receipt_path),
        "retrySafe": retry_safe,
        "message": message,
    }
    print(json.dumps(outcome, ensure_ascii=False, indent=2, sort_keys=True))


def _wait_for_receipt(receipts, final_request, request_id, timeout, keep_receipt):
    receipt_path = receipts / ("receipt-%s.json" % request_id)
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        try:
            receipt_path.lstat()
        except FileNotFoundError:
            receipt = None
        except OSError as error:
            _print_publisher_outcome(
                "unknown",
                request_id,
                receipt_path,
                "The request was published, but its receipt cannot be inspected: %s" % error,
                False,
            )
            return 3
        else:
            try:
                receipt = _read_receipt(receipt_path, request_id)
            except PublisherError as error:
                _print_publisher_outcome(
                    "unknown",
                    request_id,
                    receipt_path,
                    "The request was published, but its receipt is invalid: %s" % error,
                    False,
                )
                return 3
        if receipt is not None:
            if not keep_receipt:
                try:
                    receipt_path.unlink()
                except FileNotFoundError:
                    pass
                except OSError as error:
                    print(
                        "warning: receipt was read but could not be removed: %s" % error,
                        file=sys.stderr,
                    )
            print(json.dumps(receipt, ensure_ascii=False, indent=2, sort_keys=True))
            return 0 if receipt["status"] == "accepted" else 2
        time.sleep(0.1)

    pending_removed = False
    try:
        final_request.unlink()
        pending_removed = True
    except FileNotFoundError:
        pass
    except OSError:
        pass
    if pending_removed:
        _print_publisher_outcome(
            "not_delivered",
            request_id,
            receipt_path,
            "Timed out; the request was still pending and was removed before Android Studio loaded it.",
            True,
        )
        return 1
    _print_publisher_outcome(
        "unknown",
        request_id,
        receipt_path,
        "Timed out after Android Studio may have claimed the request; check this receipt path and "
        "the Call Flow tool window before doing anything else.",
        False,
    )
    return 3


def main(argv=None):
    args = _parser().parse_args(argv)
    if args.timeout <= 0 or args.timeout > 300:
        raise PublisherError("--timeout must be greater than 0 and at most 300 seconds")
    if args.expires_in < 10 or args.expires_in > 300:
        raise PublisherError("--expires-in must be between 10 and 300 seconds")

    input_path = Path(args.flow_json).expanduser()
    input_data = _read_input(input_path, args.delete_input)
    flow = _validate_call_flow(_parse_json_bytes(input_data, "Call Flow JSON"))
    _validate_sources(flow, Path.cwd())

    inbox, receipts = _exchange_directories(args.temp_root)
    request_id = str(uuid.uuid4())
    created = int(time.time() * 1000)
    request = dict(flow)
    request["_delivery"] = {
        "version": DELIVERY_VERSION,
        "requestId": request_id,
        "createdAtEpochMs": created,
        "expiresAtEpochMs": created + args.expires_in * 1000,
    }
    payload = (json.dumps(request, ensure_ascii=False, indent=2) + "\n").encode("utf-8")
    final_request = _publish_request(inbox, request_id, payload)
    return _wait_for_receipt(
        receipts,
        final_request,
        request_id,
        args.timeout,
        args.keep_receipt,
    )


if __name__ == "__main__":
    try:
        sys.exit(main())
    except PublisherError as error:
        print("error: %s" % error, file=sys.stderr)
        sys.exit(1)
