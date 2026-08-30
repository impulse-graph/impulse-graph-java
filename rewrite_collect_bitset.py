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
        if "return outBs;" in lines[i] and "}" in lines[i+1] and "public static void handleFloatVectorScale" in lines[i+4]:
            end_idx = i + 1
            break

if start_idx != -1 and end_idx != -1:
    new_func = """    public static Object handleCollectBitset(MemorySegment state, VmQueryContext ctx, Instruction instr, Object input) {
        int dst = instr.dstReg();
        validateReg(dst);
        int srcReg = instr.payload() & 0xFFFF;
        if (srcReg == 0 && instr.dstReg() != 0 && (instr.payload() & 0xFFFF) == 0) {
            srcReg = instr.dstReg();
        }
        validateReg(srcReg);
        long val = getRegisterValue(state, srcReg);
        byte typeTag = getRegisterType(state, srcReg);

        if ((instr.flags() & FLAG_INPUT_SEED) != 0 && input != null) {
            int outHandle = ctx.acquireBitset();
            ImpulseBitSet outBs = ctx.getBitset(outHandle);
            if (input instanceof Number n) {
                outBs.set(n.intValue());
            } else if (input instanceof ImpulseBitSet inBs) {
                outBs.or(inBs);
            } else if (input instanceof long[] arr) {
                for (long v : arr) outBs.set((int) v);
            } else if (input instanceof int[] arr) {
                for (int v : arr) outBs.set(v);
            } else if (input instanceof Iterable<?> it) {
                for (Object elem : it) {
                    if (elem instanceof Number num) outBs.set(num.intValue());
                }
            }
            if (typeTag == TYPE_BITSET_HANDLE) {
                ImpulseBitSet bs = ctx.getBitset((int) val);
                if (bs != null && bs != outBs) outBs.or(bs);
            } else if (typeTag == TYPE_NODE_ID || typeTag == TYPE_INT64) {
                outBs.set((int) val);
            }
            setRegister(state, dst, outHandle, TYPE_BITSET_HANDLE);
            return outBs;
        }

        setRegister(state, dst, val, typeTag);

        if (typeTag == TYPE_BITSET_HANDLE) {
            return ctx.getBitset((int) val);
        } else if (typeTag == TYPE_NODE_ID || typeTag == TYPE_INT64) {
            int outHandle = ctx.acquireBitset();
            ImpulseBitSet outBs = ctx.getBitset(outHandle);
            outBs.set((int) val);
            return outBs;
        }
        return null;
    }
"""
    new_lines = lines[:start_idx] + [new_func] + lines[end_idx+1:]
    
    with open("impulse-vm/src/main/java/org/impulsegraph/vm/VmHandlers.java", "w") as f:
        f.writelines(new_lines)
    print("Successfully replaced handleCollectBitset")
else:
    print("Could not find start or end bounds", start_idx, end_idx)
