import re

with open("impulse-vm/src/main/java/org/impulsegraph/vm/VmHandlers.java", "r") as f:
    code = f.read()

# Fix handleVectorReduceSum
code = re.sub(
    r"public static void handleVectorReduceSum\(MemorySegment state, VmQueryContext ctx, Instruction instr\) {",
    r"public static Object handleVectorReduceSum(MemorySegment state, VmQueryContext ctx, Instruction instr) {",
    code
)

code = re.sub(
    r"setRegister\(state, dst, Float\.floatToRawIntBits\(fsum\), TYPE_FLOAT\);\n\s*return;",
    r"setRegister(state, dst, Float.floatToRawIntBits(fsum), TYPE_FLOAT);\n            return (double) fsum;",
    code
)

code = re.sub(
    r"setRegister\(state, dst, Double\.doubleToRawLongBits\(totalSum\), TYPE_DOUBLE\);\n\s*return;",
    r"setRegister(state, dst, Double.doubleToRawLongBits(totalSum), TYPE_DOUBLE);\n            return totalSum;",
    code
)

code = re.sub(
    r"setRegister\(state, dst, 0L, TYPE_FLOAT\);\n\s*return;",
    r"setRegister(state, dst, 0L, TYPE_FLOAT);\n        return 0.0;",
    code
)

# Fix handleCooWalkFiltered
coo_walk_old = """        if (srcType == TYPE_BITSET_HANDLE) {
            ImpulseBitSet bsSrc = ctx.getBitset((int) getRegisterValue(state, src));
            if (bsSrc != null && relSnap != null) {
                MemorySegment rowOff = relSnap.getRowOffsetsSegment();
                MemorySegment colIdx = relSnap.getColumnTargetsSegment();
                int nodeCount = relSnap.getNodeCount();
                for (int u = bsSrc.nextSetBit(0); u >= 0 && u < nodeCount; u = bsSrc.nextSetBit(u + 1)) {
                    int start = rowOff.getAtIndex(ValueLayout.JAVA_INT_UNALIGNED, u);
                    int end = rowOff.getAtIndex(ValueLayout.JAVA_INT_UNALIGNED, u + 1);
                    for (int i = start; i < end; i++) {
                        int v = colIdx.getAtIndex(ValueLayout.JAVA_INT_UNALIGNED, i);
                        boolean match = (fltBs != null) ? fltBs.get(v) : (v == scalarFlt);
                        if (match) outBs.set(v);
                    }
                }
            }
        } else if (srcType == TYPE_NODE_ID || srcType == TYPE_INT64) {
            int u = (int) getRegisterValue(state, src);
            if (relSnap != null && u >= 0 && u < relSnap.getNodeCount()) {
                MemorySegment rowOff = relSnap.getRowOffsetsSegment();
                MemorySegment colIdx = relSnap.getColumnTargetsSegment();
                int start = rowOff.getAtIndex(ValueLayout.JAVA_INT_UNALIGNED, u);
                int end = rowOff.getAtIndex(ValueLayout.JAVA_INT_UNALIGNED, u + 1);
                for (int i = start; i < end; i++) {
                    int v = colIdx.getAtIndex(ValueLayout.JAVA_INT_UNALIGNED, i);
                    boolean match = (fltBs != null) ? fltBs.get(v) : (v == scalarFlt);
                    if (match) outBs.set(v);
                }
            }
        }"""

coo_walk_new = """        int tmpH = ctx.acquireBitset();
        ImpulseBitSet tmpBs = ctx.getBitset(tmpH);

        if (srcType == TYPE_BITSET_HANDLE) {
            ImpulseBitSet bsSrc = ctx.getBitset((int) getRegisterValue(state, src));
            if (bsSrc != null && relSnap != null) {
                for (int u = bsSrc.nextSetBit(0); u >= 0; u = bsSrc.nextSetBit(u + 1)) {
                    tmpBs.clear();
                    relSnap.copyTargetsSimd(u, tmpBs);
                    for (int v = tmpBs.nextSetBit(0); v >= 0; v = tmpBs.nextSetBit(v + 1)) {
                        boolean match = (fltBs != null) ? fltBs.get(v) : (v == scalarFlt);
                        if (match) outBs.set(v);
                    }
                }
            }
        } else if (srcType == TYPE_NODE_ID || srcType == TYPE_INT64) {
            int u = (int) getRegisterValue(state, src);
            if (relSnap != null) {
                tmpBs.clear();
                relSnap.copyTargetsSimd(u, tmpBs);
                for (int v = tmpBs.nextSetBit(0); v >= 0; v = tmpBs.nextSetBit(v + 1)) {
                    boolean match = (fltBs != null) ? fltBs.get(v) : (v == scalarFlt);
                    if (match) outBs.set(v);
                }
            }
        }
        ctx.releaseBitset(tmpH);"""

if coo_walk_old in code:
    code = code.replace(coo_walk_old, coo_walk_new)
else:
    print("Could not find coo_walk_old")

with open("impulse-vm/src/main/java/org/impulsegraph/vm/VmHandlers.java", "w") as f:
    f.write(code)

print("Done VmHandlers")
