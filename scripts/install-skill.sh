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
    install_copy "$HOME/.agents/skills"
    install_copy "$HOME/.claude/skills"
    ;;
  codex)
    install_copy "$HOME/.agents/skills"
    ;;
  claude)
    install_copy "$HOME/.claude/skills"
    ;;
  *)
    usage
    ;;
esac
