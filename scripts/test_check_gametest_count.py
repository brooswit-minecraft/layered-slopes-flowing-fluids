import unittest

from check_gametest_count import RuntimeEvidenceError, verify_runtime_log

BATCH_HEADER = "Running test batch 'defaultBatch:0' (6 tests)...\n"


class CheckGameTestCountTest(unittest.TestCase):
    def test_exact_expected_count_passes(self):
        log = BATCH_HEADER + "=== 6 GAME TESTS COMPLETE IN 1.5s ===\nAll 6 required tests passed :)"
        self.assertEqual(verify_runtime_log(log), 6)

    def test_zero_tests_fails_closed(self):
        with self.assertRaises(RuntimeEvidenceError):
            verify_runtime_log("=== 0 GAME TESTS COMPLETE IN 0.1s ===\nAll 0 required tests passed :)")

    def test_fewer_than_expected_fails_closed(self):
        with self.assertRaises(RuntimeEvidenceError):
            verify_runtime_log("=== 5 GAME TESTS COMPLETE IN 1.0s ===\nAll 5 required tests passed :)")

    def test_startup_failure_fails_closed(self):
        with self.assertRaises(RuntimeEvidenceError):
            verify_runtime_log("Failed to start the minecraft server")

    def test_reported_required_failure_fails_closed(self):
        with self.assertRaises(RuntimeEvidenceError):
            verify_runtime_log(BATCH_HEADER
                                + "aresistedrollleavestheblockunchanged failed at 1, 2, 3! expected RESISTED, got ERODED\n"
                                + "1 required tests failed :(")

    def test_mismatched_completed_and_passed_counts_fails_closed(self):
        with self.assertRaises(RuntimeEvidenceError):
            verify_runtime_log(BATCH_HEADER + "=== 6 GAME TESTS COMPLETE IN 1.5s ===\nAll 5 required tests passed :)")

    def test_missing_summary_fails_closed(self):
        with self.assertRaises(RuntimeEvidenceError):
            verify_runtime_log("nothing useful here")


if __name__ == "__main__":
    unittest.main()
