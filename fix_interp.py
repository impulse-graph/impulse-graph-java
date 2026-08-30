with open("impulse-vm/src/main/java/org/impulsegraph/vm/ImpulseVmInterpreter.java", "r") as f:
    code = f.read()

old_sum = "VmHandlers.handleVectorReduceSum(state, ctx, instr);"
new_sum = "finalResult = VmHandlers.handleVectorReduceSum(state, ctx, instr);"

if old_sum in code:
    code = code.replace(old_sum, new_sum)
    with open("impulse-vm/src/main/java/org/impulsegraph/vm/ImpulseVmInterpreter.java", "w") as f:
        f.write(code)
    print("Done interp")
else:
    print("Not found interp")
