"""Prevent Markdown soft wraps from inserting spaces into Chinese prose."""

from pathlib import Path
import re
import subprocess
import unittest


REPOSITORY_ROOT = Path(__file__).resolve().parents[1]
CJK_PUNCTUATION = frozenset("，。；：！？、（）【】《》「」『』“”‘’…—")
EXCLUDED_DIRECTORIES = frozenset(
    {".git", ".gradle", ".idea", "__pycache__", "build", "node_modules", "out"}
)
NEW_BLOCK_PATTERN = re.compile(
    r"^(?:#{1,6}\s|[-+*]\s|\d+[.)]\s|>|```|~~~|\||---(?:\s|$))"
)
STANDALONE_BLOCK_PATTERN = re.compile(
    r"^(?:#{1,6}\s|>|```|~~~|\||---(?:\s|$))"
)


def is_han(character: str) -> bool:
    codepoint = ord(character)
    return (
        0x3400 <= codepoint <= 0x4DBF
        or 0x4E00 <= codepoint <= 0x9FFF
        or 0xF900 <= codepoint <= 0xFAFF
        or 0x20000 <= codepoint <= 0x2FA1F
        or 0x30000 <= codepoint <= 0x323AF
    )


def is_cjk_boundary(character: str) -> bool:
    return is_han(character) or character in CJK_PUNCTUATION


def is_indented_code(line: str) -> bool:
    return line.startswith("    ") or line.startswith("\t")


def mask_inline_code(line: str) -> str:
    masked = []
    cursor = 0
    while cursor < len(line):
        opener = line.find("`", cursor)
        if opener < 0:
            masked.append(line[cursor:])
            break
        run_end = opener
        while run_end < len(line) and line[run_end] == "`":
            run_end += 1
        delimiter = line[opener:run_end]
        closer = line.find(delimiter, run_end)
        if closer < 0:
            masked.append(line[cursor:])
            break
        masked.append(line[cursor:opener])
        masked.append("X")
        cursor = closer + len(delimiter)
    return "".join(masked)


def prose_segments(line: str):
    stripped = mask_inline_code(line).lstrip()
    if stripped.startswith("|"):
        return [cell.strip() for cell in stripped.strip("|").split("|")]
    return [stripped]


def has_suspicious_inline_spacing(line: str) -> bool:
    prose = mask_inline_code(line)
    for index, character in enumerate(prose):
        next_index = index + 1
        if next_index >= len(prose) or prose[next_index] not in " \t":
            continue
        while next_index < len(prose) and prose[next_index] in " \t":
            next_index += 1
        if next_index >= len(prose):
            continue
        next_character = prose[next_index]
        if (
            (is_han(character) and is_han(next_character))
            or character in CJK_PUNCTUATION
            or next_character in CJK_PUNCTUATION
        ):
            return True
    return False


def has_suspicious_soft_wrap(line: str, next_line: str) -> bool:
    trailing_whitespace = len(line) - len(line.rstrip(" \t"))
    if trailing_whitespace >= 2:
        return False
    left_text = line.rstrip(" \t")
    right_text = next_line.lstrip()
    if not left_text or not right_text:
        return False
    if (
        STANDALONE_BLOCK_PATTERN.match(left_text.lstrip())
        or is_indented_code(next_line)
        or NEW_BLOCK_PATTERN.match(right_text)
    ):
        return False
    left_character = mask_inline_code(left_text)[-1]
    right_character = mask_inline_code(right_text)[0]
    return (
        (is_han(left_character) and is_han(right_character))
        or left_character in CJK_PUNCTUATION
        or right_character in CJK_PUNCTUATION
    )


def repository_markdown_files():
    result = subprocess.run(
        ["git", "ls-files", "--", "*.md"],
        cwd=REPOSITORY_ROOT,
        text=True,
        capture_output=True,
        check=False,
    )
    if result.returncode == 0:
        return [REPOSITORY_ROOT / line for line in result.stdout.splitlines()]
    return sorted(
        path
        for path in REPOSITORY_ROOT.rglob("*.md")
        if not EXCLUDED_DIRECTORIES.intersection(path.relative_to(REPOSITORY_ROOT).parts)
    )


class MarkdownChineseSpacingTest(unittest.TestCase):
    def test_spacing_detection_rules(self) -> None:
        self.assertTrue(has_suspicious_inline_spacing("中文 中文"))
        self.assertTrue(has_suspicious_inline_spacing("macOS、 Linux"))
        self.assertTrue(has_suspicious_inline_spacing("Android ，插件"))
        self.assertTrue(has_suspicious_inline_spacing("说明…… 继续"))
        self.assertTrue(has_suspicious_inline_spacing("中文， `code`"))
        self.assertFalse(has_suspicious_inline_spacing("中文 English 中文"))
        self.assertFalse(has_suspicious_inline_spacing("示例 `中 文` 内容"))
        self.assertFalse(has_suspicious_inline_spacing("中文 `code` 内容"))
        table_cells = prose_segments("| 示例 `a \\| b` 内容 | macOS、 Linux |")
        self.assertEqual(2, len(table_cells))
        self.assertFalse(has_suspicious_inline_spacing(table_cells[0]))
        self.assertTrue(has_suspicious_inline_spacing(table_cells[1]))
        self.assertTrue(has_suspicious_soft_wrap("中文 ", "继续"))
        self.assertTrue(has_suspicious_soft_wrap("说明，", "English"))
        self.assertTrue(has_suspicious_soft_wrap("Android", "，插件"))
        self.assertFalse(has_suspicious_soft_wrap("中文  ", "继续"))
        self.assertFalse(has_suspicious_soft_wrap("说明。", "- 下一项"))
        self.assertFalse(has_suspicious_soft_wrap("# 标题", "正文"))

    def test_no_suspicious_ascii_spacing_in_cjk_prose(self) -> None:
        findings = []
        for path in repository_markdown_files():
            in_fence = False
            for line_number, line in enumerate(
                path.read_text(encoding="utf-8").splitlines(),
                start=1,
            ):
                stripped = line.lstrip()
                if stripped.startswith("```") or stripped.startswith("~~~"):
                    in_fence = not in_fence
                    continue
                if in_fence or is_indented_code(line):
                    continue
                if any(
                    has_suspicious_inline_spacing(segment)
                    for segment in prose_segments(line)
                ):
                    findings.append(
                        f"{path.relative_to(REPOSITORY_ROOT)}:{line_number}"
                    )
        self.assertEqual([], findings)

    def test_no_cjk_soft_wrap_inside_markdown_paragraphs(self) -> None:
        findings = []
        for path in repository_markdown_files():
            lines = path.read_text(encoding="utf-8").splitlines()
            in_fence = False
            for index, line in enumerate(lines[:-1]):
                stripped = line.lstrip()
                if stripped.startswith("```") or stripped.startswith("~~~"):
                    in_fence = not in_fence
                    continue
                if in_fence or not line or is_indented_code(line):
                    continue
                next_line = lines[index + 1]
                if not next_line.strip():
                    continue
                if has_suspicious_soft_wrap(line, next_line):
                    findings.append(
                        f"{path.relative_to(REPOSITORY_ROOT)}:{index + 1}-{index + 2}"
                    )
        self.assertEqual([], findings)


if __name__ == "__main__":
    unittest.main()
