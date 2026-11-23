import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


class ReportCoverageTest(unittest.TestCase):
    def test_coverage_at_the_threshold_passes(self):
        result = self.run_report(covered=87, missed=13)

        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("Line coverage: 87.00% (87/100 lines)", result.stdout)

    def test_coverage_below_the_threshold_fails_even_if_it_rounds_up(self):
        result = self.run_report(covered=86999, missed=13001)

        self.assertEqual(result.returncode, 1)
        self.assertIn("Line coverage: 87.00%", result.stdout)
        self.assertIn("Line coverage is below the required 87%.", result.stderr)

    def run_report(self, covered, missed):
        with tempfile.TemporaryDirectory() as directory:
            report = Path(directory) / "jacoco.xml"
            report.write_text(
                f'<report><counter type="LINE" covered="{covered}" missed="{missed}"/></report>',
                encoding="utf-8",
            )
            return subprocess.run(
                [sys.executable, Path(__file__).with_name("report-coverage.py"), report],
                capture_output=True,
                text=True,
                check=False,
            )


if __name__ == "__main__":
    unittest.main()
