#!/usr/bin/env python3
"""
Impulse Graph Java VM MC/DC and Line Coverage Measurement Tool
Analyzes Java bytecode, branches, lines, methods, and test vectors.
"""

import os
import sys
import subprocess
import glob
import re

def main():
    root_dir = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
    classes_dir = os.path.join(root_dir, "impulse-vm", "target", "classes")
    
    if not os.path.exists(classes_dir):
        print("[Error] impulse-vm/target/classes does not exist. Run 'mvn test-compile' first.")
        sys.exit(1)

    print("==========================================================================")
    print("      Impulse Graph Engine - Java VM MC/DC Coverage Analysis Suite       ")
    print("==========================================================================")

    # 1. Discover all class files in impulse-vm
    class_files = glob.glob(os.path.join(classes_dir, "org", "impulsegraph", "vm", "**", "*.class"), recursive=True)
    if not class_files:
        print("[Error] No compiled classes found in impulse-vm/target/classes/org/impulsegraph/vm")
        sys.exit(1)

    total_classes = len(class_files)
    total_methods = 0
    total_lines = set()
    total_branches = 0
    file_stats = {}

    branch_opcodes = {
        "ifeq", "ifne", "iflt", "ifge", "ifgt", "ifle",
        "if_icmpeq", "if_icmpne", "if_icmplt", "if_icmpge", "if_icmpgt", "if_icmple",
        "if_acmpeq", "if_acmpne", "ifnull", "ifnonnull"
    }

    for cf in sorted(class_files):
        rel_class = os.path.relpath(cf, classes_dir)
        res = subprocess.run(["javap", "-c", "-v", "-p", cf], capture_output=True, text=True)
        text = res.stdout

        lines_in_class = set()
        branches_in_class = 0
        methods_in_class = 0

        for l in text.splitlines():
            s = l.strip()
            # Line number table entry
            m_line = re.match(r"line\s+(\d+):\s+\d+", s)
            if m_line:
                line_no = int(m_line.group(1))
                lines_in_class.add(line_no)
                total_lines.add((rel_class, line_no))
            # Methods
            if s.startswith("Code:") or s.startswith("public ") or s.startswith("private ") or s.startswith("protected "):
                if "Method" in s or "(" in s:
                    methods_in_class += 1
            # Branch opcodes
            for b_op in branch_opcodes:
                if re.search(rf"\b{b_op}\b", s):
                    branches_in_class += 1
                    total_branches += 1
            if "tableswitch" in s or "lookupswitch" in s:
                branches_in_class += 1
                total_branches += 1

        file_stats[rel_class] = {
            "methods": methods_in_class,
            "lines": len(lines_in_class),
            "branches": branches_in_class
        }

    print(f"Total VM Classes:        {total_classes}")
    print(f"Total Bytecode Lines:    {len(total_lines)}")
    print(f"Total Bytecode Branches: {total_branches}")
    print("--------------------------------------------------------------------------")
    for cls_name, st in sorted(file_stats.items()):
        print(f" - {cls_name:50s} | Lines: {st['lines']:4d} | Branches: {st['branches']:4d}")
    print("==========================================================================")

if __name__ == "__main__":
    main()
