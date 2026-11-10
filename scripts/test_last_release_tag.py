import subprocess
import tempfile
import unittest
from pathlib import Path

from last_release_tag import latest, reachable_version_tags


def run(cwd, *args):
    subprocess.run(["git", *args], cwd=cwd, check=True, capture_output=True)


class LatestTest(unittest.TestCase):
    def test_empty_is_none(self):
        self.assertIsNone(latest([]))

    def test_picks_highest_semver_not_lexicographic(self):
        self.assertEqual(latest(["v1.2.0", "v1.10.0", "v1.3.0"]), "v1.10.0")


class ReachableVersionTagsTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.repo = Path(self.tmp.name)
        run(self.repo, "init", "-q", "-b", "main")
        run(self.repo, "-c", "user.name=t", "-c", "user.email=t@t.com", "commit", "-q", "--allow-empty", "-m", "c1")

    def tearDown(self):
        self.tmp.cleanup()

    def test_ignores_tags_not_on_this_branch(self):
        run(self.repo, "tag", "v1.0.0")
        run(self.repo, "checkout", "-q", "-b", "other")
        run(self.repo, "-c", "user.name=t", "-c", "user.email=t@t.com", "commit", "-q", "--allow-empty", "-m", "c2-other")
        run(self.repo, "tag", "v9.9.9")  # a newer-looking tag on an unrelated line of history

        run(self.repo, "checkout", "-q", "main")
        self.assertEqual(reachable_version_tags(cwd=self.repo), ["v1.0.0"])

    def test_ignores_non_version_tags(self):
        run(self.repo, "tag", "v1.0.0")
        run(self.repo, "tag", "not-a-release")
        self.assertEqual(reachable_version_tags(cwd=self.repo), ["v1.0.0"])

    def test_older_checkout_is_not_fooled_by_a_later_unrelated_tag(self):
        # Simulates an old run retrying: HEAD is behind a tag that exists
        # in the repo but was created on a commit not reachable from here.
        run(self.repo, "tag", "v1.0.0")
        run(self.repo, "-c", "user.name=t", "-c", "user.email=t@t.com", "commit", "-q", "--allow-empty", "-m", "c2")
        run(self.repo, "tag", "v2.0.0")
        run(self.repo, "checkout", "-q", "HEAD~1")  # back to the v1.0.0 commit

        self.assertEqual(latest(reachable_version_tags(cwd=self.repo)), "v1.0.0")


if __name__ == "__main__":
    unittest.main()
