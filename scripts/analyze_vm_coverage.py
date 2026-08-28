#!/usr/bin/env python3
"""
Analyzes test coverage across impulse-vm classes based on test cases and vector executions.
"""

import os
import glob
import xml.etree.ElementTree as ET

def main():
    root = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
    reports = glob.glob(os.path.join(root, "impulse-vm", "target", "surefire-reports", "TEST-*.xml"))
    
    total_tests = 0
    total_failures = 0
    total_errors = 0
    total_time = 0.0

    print("==========================================================================")
    print("                Java ImpulseVM Test Execution Summary                     ")
    print("==========================================================================")

    for r in sorted(reports):
        tree = ET.parse(r)
        ts = tree.getroot()
        name = ts.attrib.get("name", "")
        tests = int(ts.attrib.get("tests", 0))
        failures = int(ts.attrib.get("failures", 0))
        errors = int(ts.attrib.get("errors", 0))
        time_sec = float(ts.attrib.get("time", 0.0))

        total_tests += tests
        total_failures += failures
        total_errors += errors
        total_time += time_sec

        short_name = name.split(".")[-1]
        status = "PASS" if (failures == 0 and errors == 0) else "FAIL"
        print(f"[{status}] {short_name:48s} | Tests: {tests:3d} | Time: {time_sec:6.3f}s")

    print("--------------------------------------------------------------------------")
    print(f"Total Test Suites:   {len(reports)}")
    print(f"Total Test Cases:    {total_tests} (Passed: {total_tests - total_failures - total_errors}, Failures: {total_failures}, Errors: {total_errors})")
    print(f"Total VM Time:       {total_time:.3f}s")
    print("==========================================================================")

if __name__ == "__main__":
    main()
