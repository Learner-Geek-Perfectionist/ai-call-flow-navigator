#!/bin/sh
set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
REPOSITORY_ROOT=$(CDPATH= cd -- "$SCRIPT_DIR/.." && pwd)
SOURCE="$REPOSITORY_ROOT/skills/ai-call-flow-navigator"
TARGET=${1:-all}

usage() {
  echo "Usage: $0 [all|codex|claude]" >&2
  exit 2
}

install_copy() {
  destination_root=$1
  platform=$2
  destination="$destination_root/ai-call-flow-navigator"
  stage="$destination_root/.ai-call-flow-navigator.install.$$"
  backup="$destination_root/.ai-call-flow-navigator.backup.$$"

  mkdir -p "$destination_root"
  rm -rf "$stage" "$backup"
  if ! cp -R "$SOURCE" "$stage"; then
    rm -rf "$stage"
    echo "Cannot stage the new Skill for $destination" >&2
    return 1
  fi

  if test "$platform" = claude; then
    skill_file="$stage/SKILL.md"
    transformed_skill="$stage/.SKILL.md.claude.$$"
    if ! awk '
      NR == 1 {
        line = $0
        carriage_return = ""
        if (sub(/\r$/, "", line)) {
          carriage_return = "\r"
        }
        if (line != "---") {
          exit 10
        }
        print $0
        in_frontmatter = 1
        next
      }
      in_frontmatter {
        line = $0
        sub(/\r$/, "", line)
        if (line ~ /^disable-model-invocation[[:space:]]*:/ ||
            line ~ /^argument-hint[[:space:]]*:/) {
          exit 11
        }
        if (line == "---") {
          print "disable-model-invocation: true" carriage_return
          print "argument-hint: \"<topic>\"" carriage_return
          print $0
          in_frontmatter = 0
          found_closing_delimiter = 1
          next
        }
      }
      { print }
      END {
        if (!found_closing_delimiter) {
          exit 12
        }
      }
    ' "$skill_file" > "$transformed_skill"; then
      rm -rf "$stage"
      echo "Cannot prepare the Claude Skill metadata for $destination" >&2
      return 1
    fi
    if ! mv "$transformed_skill" "$skill_file"; then
      rm -rf "$stage"
      echo "Cannot activate the Claude Skill metadata for $destination" >&2
      return 1
    fi
  fi

  had_previous=false
  if test -e "$destination" || test -L "$destination"; then
    if ! mv "$destination" "$backup"; then
      rm -rf "$stage"
      echo "Cannot preserve the existing Skill at $destination" >&2
      return 1
    fi
    had_previous=true
  fi

  if ! mv "$stage" "$destination"; then
    rm -rf "$stage"
    if test "$had_previous" = true; then
      mv "$backup" "$destination"
    fi
    echo "Cannot activate the new Skill at $destination" >&2
    return 1
  fi
  rm -rf "$backup" || true
  echo "Installed $destination"
}

case "$TARGET" in
  all)
    install_copy "$HOME/.agents/skills" codex
    install_copy "$HOME/.claude/skills" claude
    ;;
  codex)
    install_copy "$HOME/.agents/skills" codex
    ;;
  claude)
    install_copy "$HOME/.claude/skills" claude
    ;;
  *)
    usage
    ;;
esac
