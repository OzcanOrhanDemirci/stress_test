# The rule commit subjects are held to, in the one place everything that
# enforces it reads it from: the pipeline, the script you can run before
# pushing, and the hook that runs as a message is written.
#
# A rule written down in three places is three rules waiting to disagree.
#
# This file is sourced, not run. It defines what its callers use.

# type(scope): description
#
# The description may start with a digit, because a release commit is named
# after the version it declares and `chore(release): 0.2.0` is the clearest
# thing that commit can say.
subject_pattern='^(build|chore|ci|docs|feat|fix|perf|refactor|revert|style|test)(\([a-z0-9:._/-]+\))?!?: [a-z0-9].*[^.]$'

# Long enough for a scope and a real sentence, short enough to read in a log.
subject_limit=80

# Says what is wrong with a subject and returns non-zero, or says nothing and
# returns zero.
check_subject() {
  _subject=$1

  if ! printf '%s' "$_subject" | grep -Eq "$subject_pattern"; then
    printf 'not in the convention:\n  %s\n\n' "$_subject"
    return 1
  fi

  if [ "${#_subject}" -gt "$subject_limit" ]; then
    printf '%s characters, over the limit of %s:\n  %s\n\n' \
      "${#_subject}" "$subject_limit" "$_subject"
    return 1
  fi

  return 0
}

subject_convention_help() {
  cat <<'EOF'
Subjects follow Conventional Commits:

  type(scope): summary in the imperative, lowercase, no full stop

type is one of build, chore, ci, docs, feat, fix, perf, refactor, revert, style
or test. scope is the module or feature the change lives in and may be left out
when a change is genuinely project-wide.

CONTRIBUTING.md has the reasoning and an example of a good message body.
EOF
}
