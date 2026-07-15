import importlib.util
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest
from unittest import mock


REPOSITORY_ROOT = Path(__file__).resolve().parents[1]
CORE_INSTALLER = REPOSITORY_ROOT / "scripts" / "install-skill.py"
SHELL_INSTALLER = REPOSITORY_ROOT / "scripts" / "install-skill.sh"
POWERSHELL_INSTALLER = REPOSITORY_ROOT / "scripts" / "install-skill.ps1"
SOURCE_SKILL = REPOSITORY_ROOT / "skills" / "ai-call-flow-navigator"
CLAUDE_METADATA = (
    'disable-model-invocation: true\n'
    'argument-hint: "<topic>"\n'
)


def load_installer_module():
    specification = importlib.util.spec_from_file_location(
        "ai_call_flow_skill_installer",
        CORE_INSTALLER,
    )
    if specification is None or specification.loader is None:
        raise AssertionError("cannot load the canonical Skill installer")
    module = importlib.util.module_from_spec(specification)
    specification.loader.exec_module(module)
    return module


INSTALLER = load_installer_module()


class InstallSkillTestCase(unittest.TestCase):
    def install_one(self, source: Path, destination_root: Path, platform: str) -> None:
        INSTALLER.install_copies(source, ((destination_root, platform),))

    def assert_no_transaction_artifacts(self, destination_root: Path) -> None:
        if not destination_root.exists():
            return
        names = [entry.name for entry in destination_root.iterdir()]
        self.assertFalse(
            any(
                name.startswith(".ai-call-flow-navigator.install.")
                or name.startswith(".ai-call-flow-navigator.backup.")
                for name in names
            ),
            names,
        )

    def copy_source_skill(self, root: Path) -> Path:
        source = root / "source-skill"
        shutil.copytree(SOURCE_SKILL, source)
        return source

    def create_existing_all_installations(self, root: Path):
        home = root / "home"
        destinations = {
            "codex": home / ".agents/skills/ai-call-flow-navigator",
            "claude": home / ".claude/skills/ai-call-flow-navigator",
        }
        for platform, destination in destinations.items():
            destination.mkdir(parents=True)
            (destination / "old-marker").write_text(
                "old-%s" % platform,
                encoding="utf-8",
            )
        return home, destinations

    def assert_existing_all_installations_restored(self, destinations) -> None:
        for platform, destination in destinations.items():
            self.assertEqual(
                "old-%s" % platform,
                (destination / "old-marker").read_text(encoding="utf-8"),
            )
            self.assertFalse((destination / "SKILL.md").exists())
            self.assert_no_transaction_artifacts(destination.parent)

    def run_default_installer(self, home: Path) -> None:
        environment = os.environ.copy()
        environment["HOME"] = str(home)
        environment["USERPROFILE"] = str(home)
        command = [sys.executable, str(CORE_INSTALLER)]
        result = subprocess.run(
            command,
            cwd=REPOSITORY_ROOT,
            env=environment,
            text=True,
            capture_output=True,
            check=False,
        )
        if result.returncode != 0:
            self.fail(
                "installer failed with %d:\nstdout:\n%s\nstderr:\n%s"
                % (result.returncode, result.stdout, result.stderr)
            )


class InstallSkillCoreTest(InstallSkillTestCase):
    def test_all_installs_platform_specific_frontmatter_without_changing_source(self) -> None:
        source_before = (SOURCE_SKILL / "SKILL.md").read_bytes()
        source_text = source_before.decode("utf-8")
        self.assertNotIn("disable-model-invocation:", source_text)
        self.assertNotIn("argument-hint:", source_text)

        with tempfile.TemporaryDirectory(prefix="call-flow-skill-install-") as directory:
            home = Path(directory) / "Home With Spaces"
            self.run_default_installer(home)

            codex_skill = home / ".agents/skills/ai-call-flow-navigator/SKILL.md"
            codex_openai_metadata = (
                home
                / ".agents/skills/ai-call-flow-navigator/agents/openai.yaml"
            )
            claude_skill = home / ".claude/skills/ai-call-flow-navigator/SKILL.md"
            self.assertEqual(source_before, codex_skill.read_bytes())
            self.assertIn(
                "\npolicy:\n  allow_implicit_invocation: false\n",
                "\n" + codex_openai_metadata.read_text(encoding="utf-8"),
            )

            claude_text = claude_skill.read_text(encoding="utf-8")
            self.assertEqual(1, claude_text.count("disable-model-invocation: true"))
            self.assertEqual(1, claude_text.count('argument-hint: "<topic>"'))
            self.assertEqual(source_text, claude_text.replace(CLAUDE_METADATA, "", 1))
            self.assertEqual(source_before, (SOURCE_SKILL / "SKILL.md").read_bytes())
            self.assert_no_transaction_artifacts(home / ".agents/skills")
            self.assert_no_transaction_artifacts(home / ".claude/skills")

    def test_target_selection_and_replacement(self) -> None:
        with tempfile.TemporaryDirectory(prefix="call-flow-skill-target-") as directory:
            root = Path(directory)
            source = self.copy_source_skill(root)

            codex_home = root / "codex-home"
            existing = codex_home / ".agents/skills/ai-call-flow-navigator"
            existing.mkdir(parents=True)
            (existing / "old-marker").write_text("old", encoding="utf-8")

            INSTALLER.install_target(source, codex_home, "codex")

            self.assertFalse((existing / "old-marker").exists())
            self.assertFalse((codex_home / ".claude/skills").exists())
            self.assert_no_transaction_artifacts(codex_home / ".agents/skills")

            claude_home = root / "claude-home"
            INSTALLER.install_target(source, claude_home, "claude")
            self.assertFalse((claude_home / ".agents/skills").exists())
            claude_text = (
                claude_home / ".claude/skills/ai-call-flow-navigator/SKILL.md"
            ).read_text(encoding="utf-8")
            self.assertIn("disable-model-invocation: true", claude_text)
            self.assertIn('argument-hint: "<topic>"', claude_text)
            self.assert_no_transaction_artifacts(claude_home / ".claude/skills")

    def test_activation_failure_restores_previous_skill(self) -> None:
        with tempfile.TemporaryDirectory(prefix="call-flow-skill-rollback-") as directory:
            root = Path(directory)
            source = self.copy_source_skill(root)
            destination_root = root / "home/.agents/skills"
            destination = destination_root / "ai-call-flow-navigator"
            destination.mkdir(parents=True)
            marker = destination / "keep-me"
            marker.write_text("preserved", encoding="utf-8")

            real_replace = INSTALLER.os.replace

            def fail_activation(source_path, destination_path):
                source_entry = Path(source_path)
                destination_entry = Path(destination_path)
                if (
                    source_entry.name.startswith(
                        ".ai-call-flow-navigator.install."
                    )
                    and destination_entry == destination
                ):
                    raise OSError("injected activation failure")
                return real_replace(source_path, destination_path)

            with mock.patch.object(
                INSTALLER.os,
                "replace",
                side_effect=fail_activation,
            ):
                with self.assertRaisesRegex(
                    INSTALLER.InstallError,
                    "Cannot activate the new Skill",
                ):
                    self.install_one(source, destination_root, "codex")

            self.assertEqual("preserved", marker.read_text(encoding="utf-8"))
            self.assert_no_transaction_artifacts(destination_root)

    def test_all_staging_failure_leaves_both_previous_skills_unchanged(self) -> None:
        with tempfile.TemporaryDirectory(prefix="call-flow-skill-all-stage-") as directory:
            root = Path(directory)
            source = self.copy_source_skill(root)
            home, destinations = self.create_existing_all_installations(root)
            claude_root = destinations["claude"].parent
            real_copytree = INSTALLER.shutil.copytree

            def fail_claude_staging(source_path, destination_path, *args, **kwargs):
                destination_entry = Path(destination_path)
                if destination_entry.parent == claude_root:
                    raise OSError("injected Claude staging failure")
                return real_copytree(source_path, destination_path, *args, **kwargs)

            with mock.patch.object(
                INSTALLER.shutil,
                "copytree",
                side_effect=fail_claude_staging,
            ):
                with self.assertRaisesRegex(
                    INSTALLER.InstallError,
                    "Cannot stage the new Skill",
                ):
                    INSTALLER.install_target(source, home, "all")

            self.assert_existing_all_installations_restored(destinations)

    def test_all_metadata_failure_leaves_both_previous_skills_unchanged(self) -> None:
        with tempfile.TemporaryDirectory(prefix="call-flow-skill-all-metadata-") as directory:
            root = Path(directory)
            source = self.copy_source_skill(root)
            (source / "SKILL.md").write_text(
                "missing-frontmatter\n",
                encoding="utf-8",
            )
            home, destinations = self.create_existing_all_installations(root)

            with self.assertRaisesRegex(
                INSTALLER.InstallError,
                "Cannot prepare the Claude Skill metadata",
            ):
                INSTALLER.install_target(source, home, "all")

            self.assert_existing_all_installations_restored(destinations)

    def test_all_second_backup_failure_rolls_back_first_target(self) -> None:
        with tempfile.TemporaryDirectory(prefix="call-flow-skill-all-backup-") as directory:
            root = Path(directory)
            source = self.copy_source_skill(root)
            home, destinations = self.create_existing_all_installations(root)
            claude_destination = destinations["claude"]
            real_replace = INSTALLER.os.replace

            def fail_claude_backup(source_path, destination_path):
                source_entry = Path(source_path)
                destination_entry = Path(destination_path)
                if (
                    source_entry == claude_destination
                    and destination_entry.name.startswith(
                        ".ai-call-flow-navigator.backup."
                    )
                ):
                    raise OSError("injected Claude backup failure")
                return real_replace(source_path, destination_path)

            with mock.patch.object(
                INSTALLER.os,
                "replace",
                side_effect=fail_claude_backup,
            ):
                with self.assertRaisesRegex(
                    INSTALLER.InstallError,
                    "Cannot preserve the existing Skill",
                ):
                    INSTALLER.install_target(source, home, "all")

            self.assert_existing_all_installations_restored(destinations)

    def test_all_second_activation_failure_rolls_back_first_target(self) -> None:
        with tempfile.TemporaryDirectory(prefix="call-flow-skill-all-activate-") as directory:
            root = Path(directory)
            source = self.copy_source_skill(root)
            home, destinations = self.create_existing_all_installations(root)
            claude_destination = destinations["claude"]
            real_replace = INSTALLER.os.replace

            def fail_claude_activation(source_path, destination_path):
                source_entry = Path(source_path)
                destination_entry = Path(destination_path)
                if (
                    source_entry.name.startswith(
                        ".ai-call-flow-navigator.install."
                    )
                    and source_entry.parent == claude_destination.parent
                    and destination_entry == claude_destination
                ):
                    raise OSError("injected Claude activation failure")
                return real_replace(source_path, destination_path)

            with mock.patch.object(
                INSTALLER.os,
                "replace",
                side_effect=fail_claude_activation,
            ):
                with self.assertRaisesRegex(
                    INSTALLER.InstallError,
                    "Cannot activate the new Skill",
                ):
                    INSTALLER.install_target(source, home, "all")

            self.assert_existing_all_installations_restored(destinations)

    def test_invalid_frontmatter_does_not_replace_existing_claude_skill(self) -> None:
        with tempfile.TemporaryDirectory(prefix="call-flow-skill-invalid-") as directory:
            root = Path(directory)
            source = self.copy_source_skill(root)
            (source / "SKILL.md").write_text(
                "missing-frontmatter\n",
                encoding="utf-8",
            )

            destination_root = root / "home/.claude/skills"
            existing = destination_root / "ai-call-flow-navigator"
            existing.mkdir(parents=True)
            marker = existing / "keep-me"
            marker.write_text("preserved", encoding="utf-8")

            with self.assertRaisesRegex(
                INSTALLER.InstallError,
                "valid YAML frontmatter",
            ):
                self.install_one(source, destination_root, "claude")

            self.assertEqual("preserved", marker.read_text(encoding="utf-8"))
            self.assert_no_transaction_artifacts(destination_root)

    def test_claude_frontmatter_accepts_trailing_space_and_preserves_crlf(self) -> None:
        with tempfile.TemporaryDirectory(prefix="call-flow-skill-crlf-") as directory:
            root = Path(directory)
            source = self.copy_source_skill(root)
            skill_file = source / "SKILL.md"
            source_crlf = skill_file.read_bytes().replace(b"\n", b"\r\n")
            source_crlf = source_crlf.replace(
                b"---\r\n",
                b"--- \t\r\n",
                1,
            )
            source_crlf = source_crlf.replace(
                b"\r\n---\r\n",
                b"\r\n--- \t\r\n",
                1,
            )
            skill_file.write_bytes(source_crlf)

            destination_root = root / "home/.claude/skills"
            self.install_one(source, destination_root, "claude")

            installed = (
                destination_root / "ai-call-flow-navigator/SKILL.md"
            ).read_bytes()
            metadata = CLAUDE_METADATA.replace("\n", "\r\n").encode("utf-8")
            self.assertEqual(1, installed.count(metadata))
            self.assertEqual(source_crlf, installed.replace(metadata, b"", 1))
            self.assert_no_transaction_artifacts(destination_root)

    def test_broken_destination_symlink_is_replaced_and_backup_is_removed(self) -> None:
        with tempfile.TemporaryDirectory(prefix="call-flow-skill-symlink-") as directory:
            root = Path(directory)
            source = self.copy_source_skill(root)
            destination_root = root / "home/.agents/skills"
            destination_root.mkdir(parents=True)
            destination = destination_root / "ai-call-flow-navigator"
            try:
                destination.symlink_to(
                    root / "missing-skill-target",
                    target_is_directory=True,
                )
            except (NotImplementedError, OSError) as error:
                self.skipTest("symbolic links are unavailable: %s" % error)

            self.assertTrue(os.path.lexists(str(destination)))
            self.assertFalse(destination.exists())

            self.install_one(source, destination_root, "codex")

            self.assertTrue(destination.is_dir())
            self.assertFalse(destination.is_symlink())
            self.assertTrue((destination / "SKILL.md").is_file())
            self.assert_no_transaction_artifacts(destination_root)

    def test_staging_excludes_local_generated_artifacts(self) -> None:
        with tempfile.TemporaryDirectory(prefix="call-flow-skill-ignore-") as directory:
            root = Path(directory)
            source = self.copy_source_skill(root)
            (source / ".DS_Store").write_bytes(b"desktop metadata")
            for suffix in ("pyc", "pyo", "pyd"):
                (source / ("generated.%s" % suffix)).write_bytes(b"generated")
            cache = source / "nested/__pycache__"
            cache.mkdir(parents=True)
            (cache / "cached.pyc").write_bytes(b"cache")
            pytest_cache = source / "nested/.pytest_cache"
            pytest_cache.mkdir()
            (pytest_cache / "README.md").write_text("cache", encoding="utf-8")
            (source / "nested/.DS_Store").write_bytes(b"nested metadata")
            (source / "nested/keep.py").write_text(
                "KEEP = True\n",
                encoding="utf-8",
            )

            destination_root = root / "home/.agents/skills"
            self.install_one(source, destination_root, "codex")
            installed = destination_root / "ai-call-flow-navigator"

            installed_names = [path.name for path in installed.rglob("*")]
            self.assertNotIn("__pycache__", installed_names)
            self.assertNotIn(".pytest_cache", installed_names)
            self.assertNotIn(".DS_Store", installed_names)
            for suffix in ("pyc", "pyo", "pyd"):
                self.assertFalse(list(installed.rglob("*.%s" % suffix)))
            self.assertTrue((installed / "nested/keep.py").is_file())


class InstallSkillWrapperTest(InstallSkillTestCase):
    def test_shell_wrapper_skips_incompatible_python_and_forwards_arguments(self) -> None:
        if not Path("/bin/sh").is_file():
            self.skipTest("/bin/sh is not available")

        with tempfile.TemporaryDirectory(prefix="call-flow-shell-wrapper-") as directory:
            root = Path(directory)
            scripts = root / "scripts"
            commands = root / "commands"
            scripts.mkdir()
            commands.mkdir()
            shutil.copy2(SHELL_INSTALLER, scripts / "install-skill.sh")
            (scripts / "install-skill.py").write_text("# forwarded path\n", encoding="utf-8")

            incompatible_python = commands / "python3"
            incompatible_python.write_text("#!/bin/sh\nexit 1\n", encoding="utf-8")
            incompatible_python.chmod(0o755)

            compatible_python = commands / "python"
            compatible_python.write_text(
                "#!/bin/sh\n"
                "if test \"$1\" = \"-c\"; then exit 0; fi\n"
                ": > \"$CAPTURE_FILE\"\n"
                "for argument do\n"
                "  printf '%s\\n' \"$argument\" >> \"$CAPTURE_FILE\"\n"
                "done\n",
                encoding="utf-8",
            )
            compatible_python.chmod(0o755)

            dirname_command = shutil.which("dirname")
            self.assertIsNotNone(dirname_command)
            (commands / "dirname").symlink_to(dirname_command)
            (scripts / "install-skill.sh").chmod(0o755)

            capture_file = root / "arguments.txt"
            environment = os.environ.copy()
            environment["PATH"] = os.pathsep.join((str(scripts), str(commands)))
            environment["CAPTURE_FILE"] = str(capture_file)
            result = subprocess.run(
                [
                    "install-skill.sh",
                    "claude",
                    "topic with spaces",
                ],
                cwd=root,
                env=environment,
                text=True,
                capture_output=True,
                check=False,
            )

            self.assertEqual(0, result.returncode, result.stderr)
            self.assertEqual(
                [
                    str(scripts / "install-skill.py"),
                    "claude",
                    "topic with spaces",
                ],
                capture_file.read_text(encoding="utf-8").splitlines(),
            )

    def test_powershell_wrapper_forwards_arguments(self) -> None:
        powershell = shutil.which("pwsh") or shutil.which("powershell")
        if powershell is None:
            self.skipTest("PowerShell is not available")

        with tempfile.TemporaryDirectory(prefix="call-flow-ps-wrapper-") as directory:
            root = Path(directory)
            scripts = root / "scripts"
            scripts.mkdir()
            shutil.copy2(POWERSHELL_INSTALLER, scripts / "install-skill.ps1")
            (scripts / "install-skill.py").write_text(
                "import json\n"
                "import os\n"
                "from pathlib import Path\n"
                "import sys\n"
                "Path(os.environ['CAPTURE_FILE']).write_text(\n"
                "    json.dumps(sys.argv[1:]), encoding='utf-8'\n"
                ")\n",
                encoding="utf-8",
            )

            capture_file = root / "arguments.json"
            environment = os.environ.copy()
            environment["CAPTURE_FILE"] = str(capture_file)
            result = subprocess.run(
                [
                    powershell,
                    "-NoProfile",
                    "-ExecutionPolicy",
                    "Bypass",
                    "-File",
                    str(scripts / "install-skill.ps1"),
                    "claude",
                    "topic with spaces",
                ],
                env=environment,
                text=True,
                capture_output=True,
                check=False,
            )

            self.assertEqual(0, result.returncode, result.stderr)
            self.assertEqual(
                ["claude", "topic with spaces"],
                json.loads(capture_file.read_text(encoding="utf-8")),
            )

    def test_wrappers_only_select_python_and_forward_to_the_core(self) -> None:
        shell_text = SHELL_INSTALLER.read_text(encoding="utf-8")
        powershell_text = POWERSHELL_INSTALLER.read_text(encoding="utf-8")

        for text in (shell_text, powershell_text):
            self.assertIn("install-skill.py", text)
            self.assertNotIn("disable-model-invocation", text)
            self.assertNotIn("ai-call-flow-navigator.backup", text)
        self.assertNotIn("Copy-Item", powershell_text)
        self.assertNotIn("Move-Item", powershell_text)


if __name__ == "__main__":
    unittest.main()
