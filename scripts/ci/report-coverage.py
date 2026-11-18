import sys
import xml.etree.ElementTree as ET


report = ET.parse(sys.argv[1]).getroot()
counter = report.find("counter[@type='LINE']")
if counter is None:
    sys.exit("JaCoCo report has no line coverage counter.")

covered = int(counter.attrib["covered"])
total = covered + int(counter.attrib["missed"])
if not total:
    sys.exit("JaCoCo report contains no measurable lines.")

print(f"Line coverage: {covered / total:.2%} ({covered}/{total} lines)")
