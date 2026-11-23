import sys
import xml.etree.ElementTree as ET


MINIMUM_LINE_COVERAGE_PERCENT = 87

report = ET.parse(sys.argv[1]).getroot()
counter = report.find("counter[@type='LINE']")
if counter is None:
    sys.exit("JaCoCo report has no line coverage counter.")

covered = int(counter.attrib["covered"])
total = covered + int(counter.attrib["missed"])
if not total:
    sys.exit("JaCoCo report contains no measurable lines.")

print(f"Line coverage: {covered / total:.2%} ({covered}/{total} lines)")
if covered * 100 < MINIMUM_LINE_COVERAGE_PERCENT * total:
    sys.exit(f"Line coverage is below the required {MINIMUM_LINE_COVERAGE_PERCENT}%.")
