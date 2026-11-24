#!/usr/bin/env python3
"""Adds a one-line test result to the job summary from JUnit XML files.

Usage: test-summary.py <title> <glob> [<glob> ...]
"""
import glob
import os
import sys
import xml.etree.ElementTree as ET

title, patterns = sys.argv[1], sys.argv[2:]
tests = failures = errors = skipped = 0
files = [f for p in patterns for f in glob.glob(p, recursive=True)]
for path in files:
    root = ET.parse(path).getroot()
    suites = [root] if root.tag == "testsuite" else root.iter("testsuite")
    for suite in suites:
        tests += int(suite.get("tests", 0))
        failures += int(suite.get("failures", 0))
        errors += int(suite.get("errors", 0))
        skipped += int(suite.get("skipped", 0))

line = f"**{title}:** {tests} tests, {failures} failed, {errors} errors, {skipped} skipped ({len(files)} report files)"
print(line)
summary = os.environ.get("GITHUB_STEP_SUMMARY")
if summary:
    with open(summary, "a") as out:
        out.write(line + "\n")
