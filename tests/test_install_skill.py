import os
from pathlib import Path
import shlex
import shutil
import subprocess
import tempfile
import unittest


REPOSITORY_ROOT = Path(__file__).resolve().parents[1]
SHELL_INSTALLER = REPOSITORY_ROOT / "scripts" / "install-skill.sh"
SOURCE_SKILL = REPOSITORY_ROOT / "skills" / "ai-call-flow-navigator"
CLAUDE_METADATA = (
    'disable-model-invocation: true\n'
    'argument-hint: "<topic>"\n'
)


class InstallSkillShellTest(unittest.TestCase):
    def run_installer(
        self,
        target: str,
        home: Path,
        installer: Path = SHELL_INSTALLER,
        *,
        expect_success: bool = True,
        path_prefix=None,
    ) -> subprocess.CompletedProcess[str]:
        environment = os.environ.copy()
        environment["HOME"] = str(home)
        if path_prefix is not None:
            environment["PATH"] = os.pathsep.join(
                (str(path_prefix), environment.get("PATH", ""))
            )
        result = subprocess.run(
            ["/bin/sh", str(installer), target],
            cwd=installer.parent.parent,
            env=environment,
            text=True,
            capture_output=True,
            check=False,
        )
        if expect_success and result.returncode != 0:
            self.fail(
                f"installer failed with {result.returncode}:\n"
                f"stdout:\n{result.stdout}\nstderr:\n{result.stderr}"
            )
        return result

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

    def test_all_installs_platform_specific_frontmatter_without_changing_source(self) -> None:
        source_before = (SOURCE_SKILL / "SKILL.md").read_bytes()
        source_text = source_before.decode("utf-8")
        self.assertNotIn("disable-model-invocation:", source_text)
        self.assertNotIn("argument-hint:", source_text)

        with tempfile.TemporaryDirectory(prefix="call-flow-skill-install-") as directory:
            home = Path(directory) / "Home With Spaces"
            self.run_installer("all", home)

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
            codex_home = Path(directory) / "codex-home"
            existing = codex_home / ".agents/skills/ai-call-flow-navigator"
            existing.mkdir(parents=True)
            (existing / "old-marker").write_text("old", encoding="utf-8")

            self.run_installer("codex", codex_home)

            self.assertFalse((existing / "old-marker").exists())
            self.assertFalse((codex_home / ".claude/skills").exists())
            self.assert_no_transaction_artifacts(codex_home / ".agents/skills")

            claude_home = Path(directory) / "claude-home"
            self.run_installer("claude", claude_home)
            self.assertFalse((claude_home / ".agents/skills").exists())
            claude_text = (
                claude_home / ".claude/skills/ai-call-flow-navigator/SKILL.md"
            ).read_text(encoding="utf-8")
            self.assertIn("disable-model-invocation: true", claude_text)
            self.assertIn('argument-hint: "<topic>"', claude_text)
            self.assert_no_transaction_artifacts(claude_home / ".claude/skills")

    def test_activation_failure_restores_previous_skill(self) -> None:
        real_mv = shutil.which("mv")
        self.assertIsNotNone(real_mv)

        with tempfile.TemporaryDirectory(prefix="call-flow-skill-rollback-") as directory:
            root = Path(directory)
            home = root / "home"
            existing = home / ".agents/skills/ai-call-flow-navigator"
            existing.mkdir(parents=True)
            marker = existing / "keep-me"
            marker.write_text("preserved", encoding="utf-8")

            command_directory = root / "commands"
            command_directory.mkdir()
            injected_mv = command_directory / "mv"
            injected_mv.write_text(
                "#!/bin/sh\n"
                "case \"$1\" in\n"
                "  */.ai-call-flow-navigator.install.*)\n"
                "    case \"$2\" in\n"
                "      */ai-call-flow-navigator) exit 99 ;;\n"
                "    esac\n"
                "    ;;\n"
                "esac\n"
                f"exec {shlex.quote(real_mv)} \"$@\"\n",
                encoding="utf-8",
            )
            injected_mv.chmod(0o755)

            result = self.run_installer(
                "codex",
                home,
                expect_success=False,
                path_prefix=command_directory,
            )

            self.assertNotEqual(0, result.returncode)
            self.assertEqual("preserved", marker.read_text(encoding="utf-8"))
            self.assert_no_transaction_artifacts(home / ".agents/skills")

    def test_invalid_frontmatter_does_not_replace_existing_claude_skill(self) -> None:
        with tempfile.TemporaryDirectory(prefix="call-flow-skill-invalid-") as directory:
            fixture_root = Path(directory) / "fixture-repository"
            (fixture_root / "scripts").mkdir(parents=True)
            shutil.copy2(SHELL_INSTALLER, fixture_root / "scripts/install-skill.sh")
            shutil.copytree(
                SOURCE_SKILL,
                fixture_root / "skills/ai-call-flow-navigator",
            )
            (fixture_root / "skills/ai-call-flow-navigator/SKILL.md").write_text(
                "missing-frontmatter\n",
                encoding="utf-8",
            )

            home = Path(directory) / "home"
            existing = home / ".claude/skills/ai-call-flow-navigator"
            existing.mkdir(parents=True)
            marker = existing / "keep-me"
            marker.write_text("preserved", encoding="utf-8")

            result = self.run_installer(
                "claude",
                home,
                fixture_root / "scripts/install-skill.sh",
                expect_success=False,
            )

            self.assertNotEqual(0, result.returncode)
            self.assertEqual("preserved", marker.read_text(encoding="utf-8"))
            self.assert_no_transaction_artifacts(home / ".claude/skills")

    def test_claude_install_preserves_crlf_line_endings(self) -> None:
        with tempfile.TemporaryDirectory(prefix="call-flow-skill-crlf-") as directory:
            fixture_root = Path(directory) / "fixture-repository"
            (fixture_root / "scripts").mkdir(parents=True)
            shutil.copy2(SHELL_INSTALLER, fixture_root / "scripts/install-skill.sh")
            shutil.copytree(
                SOURCE_SKILL,
                fixture_root / "skills/ai-call-flow-navigator",
            )
            skill_file = fixture_root / "skills/ai-call-flow-navigator/SKILL.md"
            source_crlf = skill_file.read_bytes().replace(b"\n", b"\r\n")
            skill_file.write_bytes(source_crlf)

            home = Path(directory) / "home"
            self.run_installer(
                "claude",
                home,
                fixture_root / "scripts/install-skill.sh",
            )

            installed = (
                home / ".claude/skills/ai-call-flow-navigator/SKILL.md"
            ).read_bytes()
            metadata = CLAUDE_METADATA.replace("\n", "\r\n").encode("utf-8")
            self.assertEqual(source_crlf, installed.replace(metadata, b"", 1))


if __name__ == "__main__":
    unittest.main()
