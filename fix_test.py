import re

with open("impulse-vm/src/test/java/org/impulsegraph/vm/mcdc/VmHandlersSpecializedOpcodesTest.java", "r") as f:
    code = f.read()

# For testVectorDivAndStringOperations
code = code.replace(
    "assertEquals(5.0f, numerators[0]);\n                assertEquals(20.0f, numerators[1]); // Preserved (zero division ignored)\n                assertEquals(6.0f, numerators[2]);",
    "float[] result = ctx.getFloatVector((int) VmHandlers.getRegisterValue(state, 3));\n                assertEquals(5.0f, result[0]);\n                assertEquals(0.0f, result[1]); // New behavior sets 0.0 on zero division\n                assertEquals(6.0f, result[2]);"
)

# For testInlineArraysAndAssertions
code = code.replace(
    "assertEquals(1, set2.cardinality());\n                assertTrue(set2.get(42));",
    "ImpulseBitSet result2 = ctx.getBitset((int) VmHandlers.getRegisterValue(state, 4));\n                assertEquals(1, result2.cardinality());\n                assertTrue(result2.get(42));"
)

with open("impulse-vm/src/test/java/org/impulsegraph/vm/mcdc/VmHandlersSpecializedOpcodesTest.java", "w") as f:
    f.write(code)

print("Fixed tests")
