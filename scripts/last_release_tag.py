#!/usr/bin/env python3
"""Print the latest vX.Y.Z tag reachable from HEAD, or nothing if none exist.

Reachability matters: `git tag --list 'v[0-9]*' --sort=-v:refname | head -n1`
picks the globally newest version tag regardless of whether it's an
ancestor of the current commit. A retried run on an older checkout (or any
run on a branch that forked before a later tag landed on another line of
history) would then compute its next version against a tag it never
actually built on top of. Restricting to `git tag --merged HEAD` avoids
that.
"""
import re
import subprocess
import sys

TAG_RE = re.compile(r"^v(\d+)\.(\d+)\.(\d+)$")


def reachable_version_tags(cwd=None):
    out = subprocess.run(
        ["git", "tag", "--merged", "HEAD"], capture_output=True, text=True, check=True, cwd=cwd
    ).stdout
    return [line for line in out.splitlines() if TAG_RE.match(line)]


def latest(tags):
    def key(tag):
        return tuple(int(part) for part in TAG_RE.match(tag).groups())

    return max(tags, key=key) if tags else None


def main():
    tag = latest(reachable_version_tags())
    if tag:
        print(tag)


if __name__ == "__main__":
    sys.exit(main())
