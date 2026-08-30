with open("impulse-vm/src/main/java/org/impulsegraph/vm/VmHandlers.java", "r") as f:
    code = f.read()

old_str = "setRegister(state, dst, 0L, TYPE_FLOAT);\n    }"
new_str = "setRegister(state, dst, 0L, TYPE_FLOAT);\n        return 0.0;\n    }"
if old_str in code:
    code = code.replace(old_str, new_str)
    with open("impulse-vm/src/main/java/org/impulsegraph/vm/VmHandlers.java", "w") as f:
        f.write(code)
    print("Fixed")
else:
    print("Not found")
