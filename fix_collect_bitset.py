import re

with open("impulse-vm/src/main/java/org/impulsegraph/vm/VmHandlers.java", "r") as f:
    lines = f.readlines()

start_idx = -1
end_idx = -1

for i, line in enumerate(lines):
    if "public static Object handleCollectBitset(MemorySegment state, VmQueryContext ctx, Instruction instr, Object input) {" in line:
        start_idx = i
        break

if start_idx != -1:
    for i in range(start_idx, len(lines)):
        if "return null;" in lines[i] and "}" in lines[i+1]:
            end_idx = i + 1
            break

if start_idx != -1 and end_idx != -1:
    with open("/tmp/collect_bitset.txt", "r") as f:
        replacement = f.readlines()
    
    new_lines = lines[:start_idx] + replacement + lines[end_idx+1:]
    
    with open("impulse-vm/src/main/java/org/impulsegraph/vm/VmHandlers.java", "w") as f:
        f.writelines(new_lines)
    print("Successfully replaced handleCollectBitset")
else:
    print("Could not find start or end bounds")

