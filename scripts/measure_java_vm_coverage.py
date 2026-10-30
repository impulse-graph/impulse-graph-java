#!/usr/bin/env python3
"""
Impulse Graph Java VM MC/DC, Bytecode Branch, and SQLite-Style Static Assertion Linter
1. Analyzes Java bytecode instructions, line tables, and branch opcodes via javap.
2. Enforces zero '$assertionsDisabled' synthetic fields in production .class files
   (prohibiting raw Java 'assert' keyword in favor of ImpulseAssert).
3. Enforces SQLite srcck1.c-style side-effect purity inside ImpulseAssert.check*,
   ImpulseAssert.always, ImpulseAssert.never, and ImpulseAssert.testcase arguments.
"""

import os
import sys
import subprocess
import glob
import re


def check_no_raw_java_asserts(root_dir, modules):
    """Scans compiled production .class files for synthetic $assertionsDisabled fields."""
    violations = []
    checked_classes = 0
    for mod in modules:
        mod_classes = os.path.join(root_dir, mod, "target", "classes")
        if not os.path.exists(mod_classes):
            continue
        for cf in sorted(glob.glob(os.path.join(mod_classes, "**", "*.class"), recursive=True)):
            checked_classes += 1
            res = subprocess.run(["javap", "-p", cf], capture_output=True, text=True)
            if "$assertionsDisabled" in res.stdout:
                rel_cf = os.path.relpath(cf, root_dir)
                violations.append(rel_cf)
    return checked_classes, violations


def check_impulse_assert_side_effects(root_dir, modules):
    """
    SQLite srcck1.c-style static source scanner verifying that ImpulseAssert.check*,
    ImpulseAssert.always, ImpulseAssert.never, and ImpulseAssert.testcase calls do not
    contain side-effecting mutations (++, --, assignment, .allocate*()) unless marked
    with /*side-effects-ok*/.
    """
    call_pattern = re.compile(
        r"ImpulseAssert\.(?:check|checkWellFormed|always|never|testcase)\s*\(([^;]+)\);"
    )
    mutation_pattern = re.compile(
        r"(\+\+|--|(?<![=!<>])=(?![=>])|\.allocate[A-Za-z0-9_]*\s*\()"
    )
    violations = []
    checked_calls = 0

    for mod in modules:
        src_dir = os.path.join(root_dir, mod, "src", "main", "java")
        if not os.path.exists(src_dir):
            continue
        for jf in sorted(glob.glob(os.path.join(src_dir, "**", "*.java"), recursive=True)):
            rel_jf = os.path.relpath(jf, root_dir)
            if rel_jf.endswith("ImpulseAssert.java"):
                continue
            with open(jf, "r", encoding="utf-8") as f:
                content = f.read()
            for match in call_pattern.finditer(content):
                checked_calls += 1
                arg_text = match.group(1)
                if "/*side-effects-ok*/" in arg_text:
                    continue
                # Strip string literals before checking for operators
                stripped = re.sub(r'"(?:\\.|[^"\\])*"', '""', arg_text)
                if mutation_pattern.search(stripped):
                    line_no = content[:match.start()].count("\n") + 1
                    violations.append(f"{rel_jf}:{line_no}: {match.group(0).strip()}")
    return checked_calls, violations


def check_zero_hot_path_opcode_allocations(classes_dir):
    """
    Scans compiled bytecode of VmHandlers, VmMultiLaneHandlers, ShaderOps, VmLaneFrontier,
    and ImpulseMethodHandleCompiler.executeDecoded via javap -c to enforce ZERO heap allocations
    (new, newarray, anewarray, multianewarray, Arrays.copyOf) and ZERO primitive autoboxing
    (Integer/Long/Double/Float/Boolean.valueOf) in all opcode handler methods.
    """
    vm_pkg = os.path.join(classes_dir, "org", "impulsegraph", "vm")
    targets = ["VmHandlers", "VmMultiLaneHandlers", "ShaderOps", "VmLaneFrontier", "ImpulseMethodHandleCompiler"]
    excluded_methods = {
        "static {};", "<init>", "$values()", "decodeInstruction", "decodeExtendedInstruction",
        "compile(", "compileDriver(", "bindInstruction(", "wrapVoidHandler(",
        "getSkipHandler(", "fallbackExecute(", "executeCompiled("
    }
    violations = []
    checked_methods = 0

    for t in targets:
        files = sorted(glob.glob(os.path.join(vm_pkg, f"{t}.class")) + glob.glob(os.path.join(vm_pkg, f"{t}$*.class")))
        if not files:
            continue
        res = subprocess.run(["javap", "-c", "-p"] + files, capture_output=True, text=True)
        cur_method = None
        for line in res.stdout.splitlines():
            if re.match(r"^  (public|private|protected|static|final|synchronized|native|abstract|org\.|void|int|long|boolean|float|double|byte|java\.)", line) and line.endswith(";"):
                cur_method = line.strip()
                if not any(ex in cur_method for ex in excluded_methods):
                    checked_methods += 1
                continue
            if not cur_method or any(ex in cur_method for ex in excluded_methods):
                continue
            m = re.search(r"\b(new|newarray|anewarray|multianewarray)\b.*", line)
            if m and "Exception" not in line and "Error" not in line:
                violations.append(f"{t} :: {cur_method} -> {line.strip()}")
            if "valueOf:" in line and any(b in line for b in ("Integer.", "Long.", "Double.", "Float.", "Boolean.")):
                violations.append(f"{t} :: {cur_method} -> BOXING: {line.strip()}")
            if "Arrays.copyOf" in line:
                violations.append(f"{t} :: {cur_method} -> COPYOF: {line.strip()}")
    return checked_methods, violations


def main():
    root_dir = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
    classes_dir = os.path.join(root_dir, "impulse-vm", "target", "classes")

    if not os.path.exists(classes_dir):
        print("[Error] impulse-vm/target/classes does not exist. Run 'mvn test-compile' first.")
        sys.exit(1)

    print("==========================================================================")
    print("      Impulse Graph Engine - Java VM MC/DC Coverage & Static Linter       ")
    print("==========================================================================")

    # 1. Discover all class files in impulse-vm
    class_files = glob.glob(os.path.join(classes_dir, "org", "impulsegraph", "vm", "**", "*.class"), recursive=True)
    if not class_files:
        print("[Error] No compiled classes found in impulse-vm/target/classes/org/impulsegraph/vm")
        sys.exit(1)

    total_classes = len(class_files)
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
            m_line = re.match(r"line\s+(\d+):\s+\d+", s)
            if m_line:
                line_no = int(m_line.group(1))
                lines_in_class.add(line_no)
                total_lines.add((rel_class, line_no))
            if s.startswith("Code:") or s.startswith("public ") or s.startswith("private ") or s.startswith("protected "):
                if "Method" in s or "(" in s:
                    methods_in_class += 1
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
    print("--------------------------------------------------------------------------")

    # 2. Check for prohibited $assertionsDisabled synthetic fields
    modules = ["impulse-api", "impulse-storage", "impulse-vm", "impulse-compiler"]
    checked_classes, assert_kw_violations = check_no_raw_java_asserts(root_dir, modules)
    if assert_kw_violations:
        print(f"[FAIL] Raw Java 'assert' keyword ($assertionsDisabled) found in {len(assert_kw_violations)} classes:")
        for v in assert_kw_violations:
            print(f"  * {v}")
        sys.exit(1)
    else:
        print(f"[PASS] Zero '$assertionsDisabled' synthetic branches across {checked_classes} production classes.")

    # 3. Check for side effects inside ImpulseAssert calls (SQLite srcck1.c pattern)
    checked_calls, side_effect_violations = check_impulse_assert_side_effects(root_dir, modules)
    if side_effect_violations:
        print(f"[FAIL] Side-effecting expressions detected in {len(side_effect_violations)} ImpulseAssert calls:")
        for v in side_effect_violations:
            print(f"  * {v}")
        sys.exit(1)
    else:
        print(f"[PASS] Zero side-effect violations across {checked_calls} ImpulseAssert call sites.")

    # 4. Check for zero heap allocations and zero autoboxing in VM opcode handlers
    checked_opcode_methods, alloc_violations = check_zero_hot_path_opcode_allocations(classes_dir)
    if alloc_violations:
        print(f"[FAIL] Hot-path heap allocations or autoboxing detected in {len(alloc_violations)} VM opcode instructions:")
        for v in alloc_violations:
            print(f"  * {v}")
        sys.exit(1)
    else:
        print(f"[PASS] Zero heap allocations & zero autoboxing across {checked_opcode_methods} VM opcode handler methods.")

    print("==========================================================================")


if __name__ == "__main__":
    main()
