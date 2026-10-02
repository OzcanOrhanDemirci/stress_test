#!/usr/bin/env bash
#
# Checks commit subjects against the convention written down in CONTRIBUTING.md.
#
# A convention that is only described is a suggestion. This is the thing that
# makes it a rule, which is also why it lives in a file you can run rather than
# only in a workflow: before pushing, run it.
#
#   .github/scripts/check-commit-subjects.sh                 # origin/main..HEAD
#   .github/scripts/check-commit-subjects.sh <base> <head>   # an explicit range
#
# The rule itself is in commit-subject-rule.sh, next to this file, because the
# hook in .githooks applies the same one.
set -euo pipefail

# shellcheck source=.github/scripts/commit-subject-rule.sh
. "$(dirname "$0")/commit-subject-rule.sh"

if [ -n "${2:-}" ]; then
  range="$1..$2"
elif [ -n "${1:-}" ]; then
  range="$1"
else
  range="origin/main..HEAD"
fi

failed=0
checked=0

while IFS= read -r subject; do
  [ -z "$subject" ] && continue

  # A merge commit's subject is written by Git, not by a person. History here is
  # kept linear so these should not appear, but a check that fails on something
  # nobody typed teaches people to ignore it.
  case "$subject" in
    "Merge "*) continue ;;
  esac

  checked=$((checked + 1))

  if ! check_subject "$subject"; then
    failed=1
  fi
done < <(git log --format=%s "$range")

if [ "$failed" -ne 0 ]; then
  subject_convention_help
  exit 1
fi

printf '%s commit subject(s) checked, all in the convention.\n' "$checked"
