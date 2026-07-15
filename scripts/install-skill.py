#!/usr/bin/env python3
"""Install the canonical Skill for Codex, Claude Code, or both."""

import argparse
import fnmatch
import os
from pathlib import Path
import re
import shutil
import sys
import uuid


MINIMUM_PYTHON = (3, 8)
SKILL_DIRECTORY_NAME = "ai-call-flow-navigator"
CLAUDE_METADATA = (
    'disable-model-invocation: true\n'
    'argument-hint: "<topic>"\n'
)
FRONTMATTER_DELIMITER = re.compile(r"^---[ \t]*\r?$", re.MULTILINE)
CLAUDE_ONLY_KEY = re.compile(
    r"^(disable-model-invocation|argument-hint)[ \t]*:",
    re.MULTILINE,
)


class InstallError(RuntimeError):
    """An installation failure that is safe to show to the user."""


class PreparedInstall:
    """A staged Skill copy whose previous installation is still recoverable."""

    def __init__(self, destination_root):
        transaction_id = uuid.uuid4().hex
        self.destination = destination_root / SKILL_DIRECTORY_NAME
        self.stage = destination_root / (
            ".%s.install.%s" % (SKILL_DIRECTORY_NAME, transaction_id)
        )
        self.backup = destination_root / (
            ".%s.backup.%s" % (SKILL_DIRECTORY_NAME, transaction_id)
        )
        self.previous_moved = False
        self.activated = False


def path_exists(path):
    """Return whether a path entry exists, including a broken symbolic link."""

    return os.path.lexists(str(path))


def remove_path(path):
    """Remove a file, link, or directory without following directory links."""

    if not path_exists(path):
        return
    if path.is_symlink() or not path.is_dir():
        path.unlink()
    else:
        shutil.rmtree(str(path))


def ignore_generated_artifacts(_directory, names):
    """Exclude local caches and desktop metadata from installed Skill copies."""

    return [
        name
        for name in names
        if name in ("__pycache__", ".pytest_cache", ".DS_Store")
        or fnmatch.fnmatchcase(name, "*.py[cod]")
    ]


def set_claude_explicit_invocation_metadata(stage):
    skill_file = stage / "SKILL.md"
    temporary_skill_file = stage / (
        ".SKILL.md.claude." + uuid.uuid4().hex
    )

    try:
        try:
            content = skill_file.read_bytes().decode("utf-8", errors="strict")
        except (OSError, UnicodeError) as error:
            raise InstallError("SKILL.md is not readable UTF-8: %s" % error)

        delimiters = list(FRONTMATTER_DELIMITER.finditer(content))
        if len(delimiters) < 2 or delimiters[0].start() != 0:
            raise InstallError(
                "SKILL.md does not contain valid YAML frontmatter delimiters."
            )

        closing_delimiter = delimiters[1]
        frontmatter = content[:closing_delimiter.start()]
        if CLAUDE_ONLY_KEY.search(frontmatter):
            raise InstallError(
                "The canonical SKILL.md contains Claude-only invocation metadata."
            )

        first_newline_index = content.find("\n")
        if first_newline_index < 0:
            raise InstallError("SKILL.md frontmatter does not contain a line ending.")
        newline = (
            "\r\n"
            if first_newline_index > 0
            and content[first_newline_index - 1] == "\r"
            else "\n"
        )
        metadata = CLAUDE_METADATA.replace("\n", newline)
        claude_content = (
            content[:closing_delimiter.start()]
            + metadata
            + content[closing_delimiter.start():]
        )

        try:
            with temporary_skill_file.open("xb") as output:
                output.write(claude_content.encode("utf-8"))
                output.flush()
                os.fsync(output.fileno())
            os.replace(str(temporary_skill_file), str(skill_file))
        except OSError as error:
            raise InstallError(
                "Cannot activate the Claude Skill metadata: %s" % error
            )
    finally:
        if path_exists(temporary_skill_file):
            remove_path(temporary_skill_file)


def prepare_install(source, destination_root, platform):
    if platform not in ("codex", "claude"):
        raise ValueError("unsupported Skill platform: %s" % platform)

    prepared = PreparedInstall(destination_root)
    try:
        try:
            destination_root.mkdir(parents=True, exist_ok=True)
            shutil.copytree(
                str(source),
                str(prepared.stage),
                symlinks=True,
                ignore=ignore_generated_artifacts,
            )
        except OSError as error:
            raise InstallError(
                "Cannot stage the new Skill for %s: %s"
                % (prepared.destination, error)
            )

        if platform == "claude":
            try:
                set_claude_explicit_invocation_metadata(prepared.stage)
            except InstallError as error:
                raise InstallError(
                    "Cannot prepare the Claude Skill metadata for %s: %s"
                    % (prepared.destination, error)
                )
        return prepared
    except Exception:
        if path_exists(prepared.stage):
            remove_path(prepared.stage)
        raise


def rollback_install(prepared):
    if prepared.activated:
        try:
            remove_path(prepared.destination)
        except OSError as error:
            raise InstallError(
                "Cannot remove the new Skill at %s during rollback: %s"
                % (prepared.destination, error)
            )
        prepared.activated = False

    if prepared.previous_moved:
        if path_exists(prepared.destination):
            raise InstallError(
                "Cannot restore the previous Skill at %s because the path is occupied."
                % prepared.destination
            )
        try:
            os.replace(str(prepared.backup), str(prepared.destination))
        except OSError as error:
            raise InstallError(
                "Cannot restore the previous Skill at %s: %s"
                % (prepared.destination, error)
            )
        prepared.previous_moved = False


def commit_install(prepared):
    if path_exists(prepared.destination):
        try:
            os.replace(str(prepared.destination), str(prepared.backup))
        except OSError as error:
            raise InstallError(
                "Cannot preserve the existing Skill at %s: %s"
                % (prepared.destination, error)
            )
        prepared.previous_moved = True

    try:
        os.replace(str(prepared.stage), str(prepared.destination))
        prepared.activated = True
    except OSError as activation_error:
        try:
            rollback_install(prepared)
        except InstallError as rollback_error:
            raise InstallError(
                "Cannot activate the new Skill at %s: %s; %s"
                % (prepared.destination, activation_error, rollback_error)
            )
        raise InstallError(
            "Cannot activate the new Skill at %s: %s"
            % (prepared.destination, activation_error)
        )


def finalize_install(prepared):
    if prepared.previous_moved:
        try:
            remove_path(prepared.backup)
        except OSError:
            # Every target is active now. A stale backup is safer than reporting
            # transaction failure after the commit has already completed.
            pass
        prepared.previous_moved = False


def install_copies(source, targets):
    prepared_installs = []
    try:
        for destination_root, platform in targets:
            prepared_installs.append(
                prepare_install(source, destination_root, platform)
            )

        committed_installs = []
        try:
            for prepared in prepared_installs:
                commit_install(prepared)
                committed_installs.append(prepared)
        except InstallError as commit_error:
            rollback_errors = []
            for committed in reversed(committed_installs):
                try:
                    rollback_install(committed)
                except InstallError as rollback_error:
                    rollback_errors.append(str(rollback_error))
            if rollback_errors:
                raise InstallError(
                    "%s; transaction rollback also failed: %s"
                    % (commit_error, "; ".join(rollback_errors))
                )
            raise

        for prepared in prepared_installs:
            finalize_install(prepared)
        for prepared in prepared_installs:
            print("Installed %s" % prepared.destination)
    finally:
        for prepared in prepared_installs:
            if path_exists(prepared.stage):
                remove_path(prepared.stage)


def install_target(source, home, target):
    targets = {
        "codex": ((home / ".agents" / "skills", "codex"),),
        "claude": ((home / ".claude" / "skills", "claude"),),
        "all": (
            (home / ".agents" / "skills", "codex"),
            (home / ".claude" / "skills", "claude"),
        ),
    }
    install_copies(source, targets[target])


def parse_arguments(arguments):
    parser = argparse.ArgumentParser(
        description="Install the AI Call Flow Navigator Skill."
    )
    parser.add_argument(
        "target",
        nargs="?",
        default="all",
        choices=("all", "codex", "claude"),
    )
    return parser.parse_args(arguments)


def main(arguments=None):
    if sys.version_info < MINIMUM_PYTHON:
        print(
            "Python 3.8 or newer is required to install the Skill.",
            file=sys.stderr,
        )
        return 1

    options = parse_arguments(sys.argv[1:] if arguments is None else arguments)
    repository_root = Path(__file__).resolve().parent.parent
    source = repository_root / "skills" / SKILL_DIRECTORY_NAME
    home = Path.home()
    try:
        install_target(source, home, options.target)
    except InstallError as error:
        print(str(error), file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
