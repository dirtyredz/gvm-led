"""unittest for camera.parse_shot_path using the real three-line `snapshot.mjs shot` output format."""
import unittest

from camera import parse_shot_path

REAL = (
    "log: C:\\Users\\dirty\\projects\\agent-eye\\.tmp\\owner-runs\\snapshot-1.log\n"
    '{"path":"C:\\\\Users\\\\dirty\\\\projects\\\\agent-eye\\\\.tmp\\\\agent-eye\\\\latest.jpg","capturedAt":"2026-10-26T20:46:50Z","sequence":7}\n'
    "OK (exit 0) log: C:\\Users\\dirty\\projects\\agent-eye\\.tmp\\owner-runs\\snapshot-1.log\n"
)


class ParseShotTest(unittest.TestCase):
    def test_three_line_output(self):
        self.assertEqual(parse_shot_path(REAL), "C:\\Users\\dirty\\projects\\agent-eye\\.tmp\\agent-eye\\latest.jpg")

    def test_bare_json_and_missing(self):
        self.assertEqual(parse_shot_path('{"path":"x.jpg"}'), "x.jpg")
        with self.assertRaises(ValueError):
            parse_shot_path("log: a\nOK (exit 0) log: a\n")


if __name__ == "__main__":
    unittest.main()
