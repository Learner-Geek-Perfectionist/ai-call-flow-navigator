#!/usr/bin/env python3
"""Validate and atomically deliver one analysis request to Android Studio."""

import argparse
import json
import math
import os
import re
import stat
import subprocess
import sys
import tempfile
import time
import uuid
from pathlib import Path


REQUEST_VERSION = "1.0"
REQUEST_TYPE = "analysis-request"
DELIVERY_VERSION = "3.0"
STRATEGY_MODE = "static-and-live"
STRATEGY_SCOPE = "project-code"
CHANNEL_DIRECTORY = "youngx-ai-call-flow-navigator"
PROTOCOL_DIRECTORY = "file-ipc-v3"
MAX_INT32 = 2_147_483_647

WINDOWS_ABSOLUTE_PATH = re.compile(r"^[A-Za-z]:[/\\]")
URI_LIKE_PATH = re.compile(r"^[A-Za-z][A-Za-z0-9+.-]*:")


class PublisherError(Exception):
    """A user-actionable validation or delivery failure."""


def _parser():
    parser = argparse.ArgumentParser(
        description=(
            "Validate an AI Call Flow analysis request, add delivery metadata, and "
            "send it to the local Android Studio plugin."
        )
    )
    parser.add_argument(
        "request_json",
        help="UTF-8 analysis request JSON without _delivery",
    )
    parser.add_argument(
        "--temp-root",
        help=(
            "Android Studio's java.io.tmpdir. Omit for operating-system defaults; "
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
            "delete the input after reading; requires a private regular file in a "
            "recognized system temporary directory and outside the source project"
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


def _parse_json_bytes(data, label):
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
    except RecursionError as error:
        raise PublisherError(
            "%s is not valid JSON: nesting is too deep" % label
        ) from error
    except (TypeError, ValueError) as error:
        raise PublisherError("%s is not valid JSON: %s" % (label, error))


def _expect_object(value, name):
    if not isinstance(value, dict):
        raise PublisherError("%s must be a JSON object" % name)
    return value


def _expect_exact_fields(value, name, required):
    keys = set(value)
    missing = set(required) - keys
    unknown = keys - set(required)
    if missing:
        raise PublisherError(
            "%s is missing: %s" % (name, ", ".join(sorted(missing)))
        )
    if unknown:
        raise PublisherError(
            "%s has unsupported fields: %s"
            % (name, ", ".join(sorted(unknown)))
        )


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


def _positive_int(value, name):
    if isinstance(value, bool) or not isinstance(value, int):
        raise PublisherError("%s must be an integer" % name)
    if value < 1 or value > MAX_INT32:
        raise PublisherError("%s must be between 1 and %d" % (name, MAX_INT32))
    return value


def _non_negative_int(value, name):
    if isinstance(value, bool) or not isinstance(value, int) or value < 0:
        raise PublisherError("%s must be a non-negative integer" % name)
    return value


def _validate_relative_path(value, name):
    path = _text(value, name, 4096, identifier=True)
    if (
        path.startswith("/")
        or path.startswith("\\")
        or WINDOWS_ABSOLUTE_PATH.match(path)
        or URI_LIKE_PATH.match(path)
    ):
        raise PublisherError("%s must be a project-relative path" % name)
    if "\\" in path:
        raise PublisherError("%s must use forward slashes" % name)
    segments = path.split("/")
    if any(segment in ("", ".", "..") for segment in segments):
        raise PublisherError(
            "%s must be a normalized project-relative path" % name
        )
    return path


def _validate_entry(value, name="analysis request.entry"):
    entry = _expect_object(value, name)
    _expect_exact_fields(entry, name, ("path", "line", "column", "symbol"))
    _validate_relative_path(entry["path"], name + ".path")
    _positive_int(entry["line"], name + ".line")
    _positive_int(entry["column"], name + ".column")
    _text(entry["symbol"], name + ".symbol", 512, identifier=True)
    return entry


def _validate_analysis_request(value):
    request = _expect_object(value, "analysis request")
    _expect_exact_fields(
        request,
        "analysis request",
        ("version", "type", "topic", "entry", "strategy"),
    )
    if request["version"] != REQUEST_VERSION:
        raise PublisherError(
            'analysis request.version must be "%s"' % REQUEST_VERSION
        )
    if request["type"] != REQUEST_TYPE:
        raise PublisherError(
            'analysis request.type must be "%s"' % REQUEST_TYPE
        )
    _text(request["topic"], "analysis request.topic", 16384)
    _validate_entry(request["entry"])

    strategy = _expect_object(request["strategy"], "analysis request.strategy")
    _expect_exact_fields(
        strategy,
        "analysis request.strategy",
        ("mode", "scope"),
    )
    if strategy["mode"] != STRATEGY_MODE:
        raise PublisherError(
            'analysis request.strategy.mode must be "%s"' % STRATEGY_MODE
        )
    if strategy["scope"] != STRATEGY_SCOPE:
        raise PublisherError(
            'analysis request.strategy.scope must be "%s"' % STRATEGY_SCOPE
        )
    return request


def _source_lines(path):
    try:
        source = path.read_bytes().decode("utf-8")
    except UnicodeDecodeError as error:
        raise PublisherError("entry source is not valid UTF-8: %s" % error)
    except OSError as error:
        raise PublisherError("cannot read entry source: %s" % error)
    return source.replace("\r\n", "\n").replace("\r", "\n").split("\n")


def _validate_entry_source(request, source_root):
    try:
        real_root = source_root.resolve(strict=True)
    except OSError as error:
        raise PublisherError("cannot resolve the current project root: %s" % error)
    if not real_root.is_dir():
        raise PublisherError("current project root is not a directory: %s" % real_root)

    entry = request["entry"]
    relative = entry["path"]
    candidate = real_root.joinpath(*relative.split("/"))
    try:
        real_candidate = candidate.resolve(strict=True)
        real_candidate.relative_to(real_root)
    except (OSError, ValueError) as error:
        raise PublisherError(
            "analysis request.entry.path is not a source file inside the current project: %s"
            % relative
        ) from error
    if not real_candidate.is_file():
        raise PublisherError(
            "analysis request.entry.path is not a regular source file: %s" % relative
        )

    lines = _source_lines(real_candidate)
    line = entry["line"]
    if line > len(lines):
        raise PublisherError(
            "analysis request.entry.line exceeds the source line count"
        )
    maximum_column = _utf16_length(lines[line - 1]) + 1
    if entry["column"] > maximum_column:
        raise PublisherError(
            "analysis request.entry.column exceeds the UTF-16 source line length"
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
    return info


def _read_private_file(path, label, initial):
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
            raise PublisherError("%s is not a regular file" % label)
        _verify_owner(opened, label)
        if _current_uid() is not None and opened.st_mode & 0o077:
            raise PublisherError("%s permissions are not private" % label)
        if (opened.st_dev, opened.st_ino) != (initial.st_dev, initial.st_ino):
            raise PublisherError("%s changed before it could be read" % label)
        with os.fdopen(descriptor, "rb") as input_file:
            descriptor = None
            return input_file.read()
    except OSError as error:
        raise PublisherError("cannot securely read %s: %s" % (label, error))
    finally:
        if descriptor is not None:
            os.close(descriptor)


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


def _read_input(path, delete_input, temp_root_argument=None):
    if not delete_input:
        try:
            with path.open("rb") as input_file:
                return input_file.read()
        except OSError as error:
            raise PublisherError("cannot read analysis request JSON: %s" % error)

    if not path.is_absolute():
        raise PublisherError("--delete-input requires an absolute secure temporary-file path")

    label = "temporary analysis request JSON"
    initial = _existing_private_file(path, label)
    if initial.st_nlink != 1:
        raise PublisherError("temporary analysis request JSON must not have hard links")
    try:
        real_input = path.resolve(strict=True)
        real_source = Path.cwd().resolve(strict=True)
    except OSError as error:
        raise PublisherError("cannot resolve the temporary analysis request JSON: %s" % error)

    temporary_roots = _temporary_root_candidates()
    if temp_root_argument:
        for root in _temporary_root_candidates(temp_root_argument):
            if root not in temporary_roots:
                temporary_roots.append(root)
    if not any(_is_within(real_input, root) for root in temporary_roots):
        raise PublisherError(
            "--delete-input only deletes files inside a recognized system temporary directory"
        )
    if _is_within(real_input, real_source):
        raise PublisherError("--delete-input refuses to delete a file inside the source project")

    data = _read_private_file(path, label, initial)
    current = _lstat(path, label)
    if (current.st_dev, current.st_ino) != (initial.st_dev, initial.st_ino):
        raise PublisherError("temporary analysis request JSON changed before deletion")
    try:
        path.unlink()
    except OSError as error:
        raise PublisherError(
            "analysis request JSON was read but could not be deleted: %s" % error
        )
    return data


def _exchange_at_root(temp_root):
    channel = _existing_private_directory(
        temp_root / CHANNEL_DIRECTORY,
        "AI Call Flow channel directory",
    )
    protocol = _existing_private_directory(
        channel / PROTOCOL_DIRECTORY,
        "AI Call Flow file-ipc-v3 directory",
    )
    inbox = _existing_private_directory(protocol / "inbox", "AI Call Flow inbox")
    _existing_private_directory(
        protocol / "processing", "AI Call Flow processing directory"
    )
    receipts = _existing_private_directory(
        protocol / "receipts", "AI Call Flow receipts directory"
    )
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
            "multiple AI Call Flow inboxes were found (%s); pass Android Studio's "
            "java.io.tmpdir with --temp-root" % roots
        )
    checked = ", ".join(str(path) for path in candidates) or "no usable temporary directories"
    detail = (" Invalid candidates: " + "; ".join(failures)) if failures else ""
    raise PublisherError(
        "AI Call Flow file-ipc-v3 inbox was not found. Open the project in Android Studio "
        "with the plugin enabled. Checked: %s.%s" % (checked, detail)
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
        try:
            os.link(str(temporary), str(final), follow_symlinks=False)
        except FileExistsError as error:
            raise PublisherError(
                "request destination already exists; refusing to overwrite it"
            ) from error
        except OSError as error:
            raise PublisherError(
                "cannot atomically publish without overwriting; the inbox filesystem "
                "must support hard links: %s" % error
            )
        try:
            temporary.unlink()
        except OSError as error:
            print(
                "warning: request was published but its temporary hard link could not "
                "be removed: %s" % error,
                file=sys.stderr,
            )
        _directory_fsync(inbox)
        return final
    except FileExistsError as error:
        raise PublisherError(
            "temporary request already exists; refusing to overwrite it"
        ) from error
    finally:
        if descriptor is not None:
            os.close(descriptor)
        try:
            temporary.unlink()
        except (FileNotFoundError, OSError):
            pass


def _validate_receipt_entry(value):
    return _validate_entry(value, "plugin receipt.entry")


def _read_receipt(path, request_id):
    label = "AI Call Flow receipt"
    initial = _existing_private_file(path, label)
    data = _read_private_file(path, label, initial)
    receipt = _expect_object(
        _parse_json_bytes(data, "plugin receipt"),
        "plugin receipt",
    )
    if receipt.get("version") != DELIVERY_VERSION:
        raise PublisherError("plugin receipt has an unsupported version")
    if receipt.get("requestId") != request_id:
        raise PublisherError("plugin receipt requestId does not match the request")
    status = receipt.get("status")
    if status not in ("accepted", "rejected"):
        raise PublisherError("plugin receipt has an unsupported status")
    completed = receipt.get("completedAtEpochMs")
    if isinstance(completed, bool) or not isinstance(completed, int) or completed < 0:
        raise PublisherError("plugin receipt has an invalid completedAtEpochMs")

    if status == "accepted":
        _expect_exact_fields(
            receipt,
            "plugin receipt",
            (
                "version",
                "requestId",
                "status",
                "completedAtEpochMs",
                "topic",
                "entry",
                "generated",
            ),
        )
        _text(receipt["topic"], "plugin receipt.topic", 16384)
        _validate_receipt_entry(receipt["entry"])
        generated = _expect_object(receipt["generated"], "plugin receipt.generated")
        _expect_exact_fields(
            generated,
            "plugin receipt.generated",
            ("nodeCount", "edgeCount", "entryNodeId"),
        )
        if _non_negative_int(
            generated["nodeCount"], "plugin receipt.generated.nodeCount"
        ) < 1:
            raise PublisherError("plugin receipt.generated.nodeCount must be at least 1")
        _non_negative_int(
            generated["edgeCount"], "plugin receipt.generated.edgeCount"
        )
        _text(
            generated["entryNodeId"],
            "plugin receipt.generated.entryNodeId",
            256,
            identifier=True,
        )
    else:
        _expect_exact_fields(
            receipt,
            "plugin receipt",
            ("version", "requestId", "status", "completedAtEpochMs", "error"),
        )
        error = _expect_object(receipt["error"], "plugin receipt.error")
        _expect_exact_fields(error, "plugin receipt.error", ("code", "message"))
        _text(error["code"], "plugin receipt.error.code", 256, identifier=True)
        _text(error["message"], "plugin receipt.error.message", 16384)
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
        "Timed out after Android Studio may have claimed the request; check this receipt "
        "path and the Call Flow tool window before doing anything else.",
        False,
    )
    return 3


def main(argv=None):
    args = _parser().parse_args(argv)
    if not math.isfinite(args.timeout) or args.timeout <= 0 or args.timeout > 300:
        raise PublisherError("--timeout must be greater than 0 and at most 300 seconds")
    if args.expires_in < 10 or args.expires_in > 300:
        raise PublisherError("--expires-in must be between 10 and 300 seconds")

    input_path = Path(args.request_json).expanduser()
    input_data = _read_input(input_path, args.delete_input, args.temp_root)
    request = _validate_analysis_request(
        _parse_json_bytes(input_data, "analysis request JSON")
    )
    _validate_entry_source(request, Path.cwd())

    inbox, receipts = _exchange_directories(args.temp_root)
    request_id = str(uuid.uuid4())
    created = int(time.time() * 1000)
    payload_object = dict(request)
    payload_object["_delivery"] = {
        "version": DELIVERY_VERSION,
        "requestId": request_id,
        "createdAtEpochMs": created,
        "expiresAtEpochMs": created + args.expires_in * 1000,
    }
    payload = (
        json.dumps(payload_object, ensure_ascii=False, indent=2) + "\n"
    ).encode("utf-8")
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
