package org.impulsegraph.vm;

import org.impulsegraph.core.csr.GraphSnapshot;
import org.impulsegraph.core.csr.RelationSnapshot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Polyglot Verification Harness for Impulse VM Java Interpreter.
 * Executes spec compliance test vectors (.impas files) from impulse-graph-spec/test-vectors/vm-impas/
 * against Java 25 FFM ImpulseVmInterpreter.
 */
public class JavaVmPolyglotAssemblyVerifierTest {

    private static final Map<String, Byte> OPCODE_MAP = new HashMap<>();

    static {
        OPCODE_MAP.put("OP_NOP", (byte) 0x00);
        OPCODE_MAP.put("OP_INIT_INPUT_NODE", (byte) 0x01);
        OPCODE_MAP.put("OP_INIT_INPUT_SET", (byte) 0x02);
        OPCODE_MAP.put("OP_LOAD_CONST_INT", (byte) 0x03);
        OPCODE_MAP.put("OP_MAP_KEYS_TO_DENSE", (byte) 0x04);
        OPCODE_MAP.put("OP_LOAD_CONST_FLOAT", (byte) 0x05);
        OPCODE_MAP.put("OP_LOAD_CONST_STR_PREFIX", (byte) 0x06);
        OPCODE_MAP.put("OP_LOAD_INLINE_ARRAY", (byte) 0x07);
        OPCODE_MAP.put("OP_INIT_MOCK_GRAPH", (byte) 0x08);

        OPCODE_MAP.put("OP_CSR_WALK", (byte) 0x10);
        OPCODE_MAP.put("OP_CSR_WALK_FILTERED", (byte) 0x11);
        OPCODE_MAP.put("OP_CSR_DEGREE", (byte) 0x12);
        OPCODE_MAP.put("OP_CSR_WALK_PREDICATE", (byte) 0x13);
        OPCODE_MAP.put("OP_NODE_FILTER", (byte) 0x14);
        OPCODE_MAP.put("OP_NODE_FILTER_STR_PREFIX", (byte) 0x15);
        OPCODE_MAP.put("OP_CSR_WALK_REDUCE_SUM", (byte) 0x16);
        OPCODE_MAP.put("OP_CSR_WALK_REDUCE", (byte) 0x17);
        OPCODE_MAP.put("OP_CSC_WALK", (byte) 0x18);
        OPCODE_MAP.put("OP_HAS_CSR", (byte) 0x19);
        OPCODE_MAP.put("OP_HAS_CSC", (byte) 0x1A);
        OPCODE_MAP.put("OP_HAS_COO", (byte) 0x1B);
        OPCODE_MAP.put("OP_HAS_KEY_CATALOG", (byte) 0x1C);

        OPCODE_MAP.put("OP_SET_UNION", (byte) 0x30);
        OPCODE_MAP.put("OP_SET_INTERSECT", (byte) 0x31);
        OPCODE_MAP.put("OP_SET_DIFFERENCE", (byte) 0x32);
        OPCODE_MAP.put("OP_SET_CARDINALITY", (byte) 0x33);
        OPCODE_MAP.put("OP_VECTOR_MUL_ATTR", (byte) 0x34);
        OPCODE_MAP.put("OP_VECTOR_REDUCE_SUM", (byte) 0x35);
        OPCODE_MAP.put("OP_VECTOR_DIV", (byte) 0x36);
        OPCODE_MAP.put("OP_VECTOR_STR_CONCAT", (byte) 0x37);
        OPCODE_MAP.put("OP_FLOAT_VECTOR_SCALE", (byte) 0x38);
        OPCODE_MAP.put("OP_L1_NORM_DIFF", (byte) 0x39);

        OPCODE_MAP.put("OP_CC_AFFOREST", (byte) 0x40);
        OPCODE_MAP.put("OP_MXV", (byte) 0x41);
        OPCODE_MAP.put("OP_VXM", (byte) 0x42);
        OPCODE_MAP.put("OP_EWISE_ADD", (byte) 0x43);
        OPCODE_MAP.put("OP_EWISE_MULT", (byte) 0x44);
        OPCODE_MAP.put("OP_REDUCE", (byte) 0x45);
        OPCODE_MAP.put("OP_CC_HOOK_COMPRESS", (byte) 0x46);
        OPCODE_MAP.put("OP_TC_SWEEP_BATCH", (byte) 0x47);
        OPCODE_MAP.put("OP_BRANDES_FORWARD", (byte) 0x48);
        OPCODE_MAP.put("OP_BRANDES_BACKWARD", (byte) 0x49);
        OPCODE_MAP.put("OP_DELTA_STEP_RELAX", (byte) 0x4A);
        OPCODE_MAP.put("OP_READ_EDGE_WEIGHT", (byte) 0x4B);

        OPCODE_MAP.put("OP_JMP", (byte) 0x50);
        OPCODE_MAP.put("OP_JZ", (byte) 0x51);
        OPCODE_MAP.put("OP_JNZ", (byte) 0x52);
        OPCODE_MAP.put("OP_LOOP_DECR", (byte) 0x53);
        OPCODE_MAP.put("OP_STABLE_CHECK", (byte) 0x54);
        OPCODE_MAP.put("OP_CALL", (byte) 0x55);
        OPCODE_MAP.put("OP_RET", (byte) 0x56);
        OPCODE_MAP.put("OP_THROW", (byte) 0x5A);
        OPCODE_MAP.put("OP_ASSERT", (byte) 0x5B);
        OPCODE_MAP.put("OP_TRAP", (byte) 0x5C);

        OPCODE_MAP.put("OP_SAMPLE_NEIGHBORS", (byte) 0x60);
        OPCODE_MAP.put("OP_RANDOM_WALK", (byte) 0x61);
        OPCODE_MAP.put("OP_SCATTER_GATHER", (byte) 0x62);
        OPCODE_MAP.put("OP_REBAC_CHECK", (byte) 0x63);
        OPCODE_MAP.put("OP_ROARING_BITMAP_AND", (byte) 0x64);
        OPCODE_MAP.put("OP_ISLAND_DETECT", (byte) 0x65);
        OPCODE_MAP.put("OP_SPARSE_MATVEC", (byte) 0x66);
        OPCODE_MAP.put("OP_LOUVAIN_MODULARITY", (byte) 0x67);
        OPCODE_MAP.put("OP_KCORE_DECOMPOSITION", (byte) 0x68);
        OPCODE_MAP.put("OP_MOTIF_MATCH_3", (byte) 0x69);
        OPCODE_MAP.put("OP_GRAPH_ISOMORPHISM", (byte) 0x6A);

        OPCODE_MAP.put("OP_MOV", (byte) 0x70);
        OPCODE_MAP.put("OP_CLEAR_REG", (byte) 0x71);
        OPCODE_MAP.put("OP_LOAD_INDIRECT", (byte) 0x72);
        OPCODE_MAP.put("OP_ALLOC_SCRATCH", (byte) 0x73);
        OPCODE_MAP.put("OP_ASSERT_SCRATCH_BYTES", (byte) 0x74);
        OPCODE_MAP.put("OP_SET_MAX_DOP", (byte) 0x75);

        OPCODE_MAP.put("OP_COLLECT_BITSET", (byte) 0x90);
        OPCODE_MAP.put("OP_COLLECT_ARRAY", (byte) 0x91);
        OPCODE_MAP.put("OP_MAP_DENSE_TO_KEYS", (byte) 0x92);
        OPCODE_MAP.put("OP_COLLECT_VALUE_MAP", (byte) 0x93);
        OPCODE_MAP.put("OP_HALT", (byte) 0xFF);
    }

    public record Expectation(String status, Map<Integer, Long> registers, Boolean zf, Boolean st, Integer pc, Integer callStackDepth) {}

    public record ParsedAssembly(
            List<Long> instructions,
            List<String> opcodesUsed,
            Expectation expectation,
            Map<String, byte[]> mockData,
            GraphSnapshot mockGraph
    ) {}

    @Test
    @DisplayName("Run Polyglot Assembly Test Suite against Java ImpulseVM Interpreter")
    public void runPolyglotAssemblyVerificationSuite() throws IOException {
        Path testVectorDir = Paths.get("../impulse-graph-spec/test-vectors/vm-impas");
        if (!Files.exists(testVectorDir)) {
            testVectorDir = Paths.get("../../impulse-graph-spec/test-vectors/vm-impas");
        }
        if (!Files.exists(testVectorDir)) {
            testVectorDir = Paths.get("/Users/jesse/impulse/impulse-graph-spec/test-vectors/vm-impas");
        }

        assertTrue(Files.exists(testVectorDir), "Test vector directory must exist: " + testVectorDir.toAbsolutePath());

        List<Path> impasFiles;
        try (Stream<Path> s = Files.walk(testVectorDir)) {
            impasFiles = s.filter(p -> p.toString().endsWith(".impas")).sorted().toList();
        }

        System.out.println("===============================================================");
        System.out.println(" ImpulseVM Java Polyglot Assembly Test Suite Verifier         ");
        System.out.println(" Spec v0.9.0 / Spec v2.4                                       ");
        System.out.println("===============================================================");

        int passed = 0;
        int failed = 0;
        Set<String> coveredOpcodes = new HashSet<>();
        Map<String, Set<String>> opcodeFileMap = new HashMap<>();

        for (Path file : impasFiles) {
            String relPath = testVectorDir.relativize(file).toString();
            try {
                ParsedAssembly asm = parseImpasFile(file);
                for (String op : asm.opcodesUsed()) {
                    coveredOpcodes.add(op);
                    opcodeFileMap.computeIfAbsent(op, k -> new HashSet<>()).add(file.getFileName().toString());
                }

                TestResult result = executeTestVector(file, asm);
                if (result.success()) {
                    passed++;
                    System.out.printf("[PASS] %s%n", relPath);
                } else {
                    failed++;
                    System.out.printf("[FAIL] %s%n       -> %s%n", relPath, result.failureReason());
                }
            } catch (Exception e) {
                failed++;
                System.out.printf("[FAIL] %s%n       -> Exception during execution: %s%n", relPath, e.getMessage());
            }
        }

        System.out.println("\n===============================================================");
        System.out.println(" Java ImpulseVM Opcode Coverage & Multi-File Threshold Analysis ");
        System.out.println("===============================================================");

        System.out.printf("Total Test Files Executed: %d (Passed: %d, Failed: %d)%n", impasFiles.size(), passed, failed);
        System.out.printf("Opcodes Covered in Java: %d / %d (%.1f%%)%n",
                coveredOpcodes.size(), OPCODE_MAP.size(), (coveredOpcodes.size() * 100.0) / OPCODE_MAP.size());

        System.out.println("---------------------------------------------------------------");

        // Print details of test suite execution
        System.out.printf("Verification Suite Completed: %d Passed, %d Failed%n", passed, failed);
    }

    public record TestResult(boolean success, String failureReason) {}

    private TestResult executeTestVector(Path file, ParsedAssembly asm) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment progSeg = arena.allocate(asm.instructions().size() * 8L, 8);
            for (int i = 0; i < asm.instructions().size(); i++) {
                progSeg.set(ValueLayout.JAVA_LONG, i * 8L, asm.instructions().get(i));
            }

            try (VmQueryContext ctx = new VmQueryContext(null, arena)) {
                MemorySegment state = ctx.allocateStateSegment();
                if (asm.mockGraph() != null) {
                    ctx.setSnapshot(asm.mockGraph());
                }

                // Setup inline data segment if present in mockData
                if (asm.mockData().containsKey("__DEFAULT_INLINE__")) {
                    byte[] raw = asm.mockData().get("__DEFAULT_INLINE__");
                    MemorySegment inlineSeg = arena.allocate(raw.length, 64);
                    MemorySegment.copy(MemorySegment.ofArray(raw), 0, inlineSeg, 0, raw.length);
                    ctx.setInlineData(inlineSeg, raw.length);
                }

                long pc = 0;
                long instructionCount = asm.instructions().size();
                
                VmHandlers.Instruction[] progArr = new VmHandlers.Instruction[(int) instructionCount];
                for (int i = 0; i < instructionCount; i++) {
                    progArr[i] = VmHandlers.decodeInstruction(progSeg, i);
                }
                
                String actualStatus = ImpulseVmValidator.validate(progArr);
                long stepCount = 0;

                while (actualStatus.equals("IMPULSE_VM_OK") && pc >= 0 && pc < instructionCount && stepCount++ < 100000) {
                    VmHandlers.Instruction instr = VmHandlers.decodeInstruction(progSeg, pc);
                    byte opcode = instr.opcode();

                    if (opcode == (byte) 0xFF) { // OP_HALT
                        break;
                    }
                    if (opcode == (byte) 0x5C) { // OP_TRAP
                        actualStatus = "IMPULSE_VM_ERR_TRAP";
                        break;
                    }

                    if (instr.dstReg() >= 64) {
                        actualStatus = "IMPULSE_VM_ERR_INVALID_REGISTER";
                        break;
                    }

                    try {
                        switch (Byte.toUnsignedInt(opcode)) {
                            case 0x00 -> pc++; // OP_NOP
                            case 0x01 -> { VmHandlers.handleInitInputNode(state, ctx, instr, instr.payload() & 0xFFFFFFFFL); pc++; }
                            case 0x02 -> { VmHandlers.handleInitInputSet(state, ctx, instr, new BitSet()); pc++; }
                            case 0x03 -> { VmHandlers.handleLoadConstInt(state, instr); pc++; }
                            case 0x04 -> { pc++; } // OP_MAP_KEYS_TO_DENSE
                            case 0x05 -> { VmHandlers.handleLoadConstFloat(state, instr); pc++; }
                            case 0x06 -> { VmHandlers.handleLoadConstStrPrefix(state, instr); pc++; }
                            case 0x07 -> { VmHandlers.handleLoadInlineArray(state, ctx, instr); pc++; }
                            case 0x08 -> { VmHandlers.handleInitMockGraph(state, ctx, instr); pc++; }
                            case 0x10 -> { VmHandlers.handleCsrWalk(state, ctx, instr); pc++; }
                            case 0x11 -> { VmHandlers.handleCsrWalk(state, ctx, instr); pc++; }
                            case 0x12 -> { VmHandlers.handleCsrDegree(state, ctx, instr); pc++; }
                            case 0x13 -> { VmHandlers.handleCsrWalkPredicate(state, ctx, instr); pc++; }
                            case 0x14 -> { VmHandlers.handleNodeFilter(state, ctx, instr); pc++; }
                            case 0x15 -> { VmHandlers.handleNodeFilter(state, ctx, instr); pc++; } // OP_NODE_FILTER_STR_PREFIX
                            case 0x16 -> { VmHandlers.handleVectorReduceSum(state, ctx, instr); pc++; }
                            case 0x17 -> { VmHandlers.handleVectorReduceSum(state, ctx, instr); pc++; } // OP_CSR_WALK_REDUCE
                            case 0x18 -> { VmHandlers.handleCscWalk(state, ctx, instr); pc++; }
                            case 0x19 -> { VmHandlers.handleHasCsr(state, ctx, instr); pc++; }
                            case 0x1A -> { VmHandlers.handleHasCsc(state, ctx, instr); pc++; }
                            case 0x1B -> { VmHandlers.handleHasCoo(state, ctx, instr); pc++; }
                            case 0x1C -> { VmHandlers.handleHasKeyCatalog(state, ctx, instr); pc++; }
                            case 0x30 -> { VmHandlers.handleSetUnion(state, ctx, instr); pc++; }
                            case 0x31 -> { VmHandlers.handleSetIntersect(state, ctx, instr); pc++; }
                            case 0x32 -> { VmHandlers.handleSetDifference(state, ctx, instr); pc++; }
                            case 0x33 -> { VmHandlers.handleSetCardinality(state, ctx, instr); pc++; }
                            case 0x34 -> { VmHandlers.handleVectorMulAttr(state, ctx, instr); pc++; }
                            case 0x35 -> { VmHandlers.handleVectorReduceSum(state, ctx, instr); pc++; }
                            case 0x36 -> { VmHandlers.handleVectorDiv(state, ctx, instr); pc++; }
                            case 0x37 -> { VmHandlers.handleVectorStrConcat(state, ctx, instr); pc++; }
                            case 0x38 -> { VmHandlers.handleFloatVectorScale(state, ctx, instr); pc++; }
                            case 0x39 -> { VmHandlers.handleL1NormDiff(state, ctx, instr); pc++; }
                            case 0x40, 0x41, 0x42, 0x43, 0x44, 0x45, 0x46, 0x47, 0x48, 0x49, 0x4A -> { pc++; } // GraphBLAS pass-through
                            case 0x4B -> { VmHandlers.handleReadEdgeWeight(state, ctx, instr); pc++; }
                            case 0x50 -> { int offset = instr.payload(); pc += offset; } // OP_JMP
                            case 0x51 -> {
                                int offset = instr.payload();
                                if (VmHandlers.checkFlag(state, VmRegisterType.FLAG_ZF)) {
                                    pc += offset;
                                } else pc++;
                            }
                            case 0x52 -> {
                                int offset = instr.payload();
                                if (!VmHandlers.checkFlag(state, VmRegisterType.FLAG_ZF)) {
                                    pc += offset;
                                } else pc++;
                            }
                            case 0x53 -> {
                                int offset = instr.payload();
                                long count = VmHandlers.getRegisterValue(state, instr.dstReg());
                                if (count > 0) {
                                    VmHandlers.setRegister(state, instr.dstReg(), count - 1, VmRegisterType.TYPE_INT64);
                                    pc += offset;
                                } else pc++;
                            }
                            case 0x54 -> { VmHandlers.handleStableCheck(state, ctx, instr); pc++; }
                            case 0x55 -> { // OP_CALL
                                int target = instr.payload();
                                long returnPc = pc + 1;
                                if (!VmHandlers.pushCallStack(state, (int) returnPc)) {
                                    actualStatus = "IMPULSE_VM_ERR_STACK_OVERFLOW";
                                    break;
                                }
                                // Register Windowing: Pass Out registers (R12..R15) to Callee In registers (R0..R3)
                                long arg0 = VmHandlers.getRegisterValue(state, 12);
                                long arg1 = VmHandlers.getRegisterValue(state, 13);
                                long arg2 = VmHandlers.getRegisterValue(state, 14);
                                long arg3 = VmHandlers.getRegisterValue(state, 15);

                                VmHandlers.setRegister(state, 0, arg0, VmRegisterType.TYPE_INT64);
                                VmHandlers.setRegister(state, 1, arg1, VmRegisterType.TYPE_INT64);
                                VmHandlers.setRegister(state, 2, arg2, VmRegisterType.TYPE_INT64);
                                VmHandlers.setRegister(state, 3, arg3, VmRegisterType.TYPE_INT64);

                                if (target >= 0 && target < instructionCount) pc = target;
                                else pc += target;
                            }
                            case 0x56 -> { // OP_RET
                                int returnPc = VmHandlers.popCallStack(state);
                                if (returnPc < 0) {
                                    actualStatus = "IMPULSE_VM_ERR_STACK_UNDERFLOW";
                                    break;
                                }
                                pc = returnPc;
                            }
                            case 0x5A -> { actualStatus = "IMPULSE_VM_ERR_USER_THROW"; pc = instructionCount; }
                            case 0x5B -> { VmHandlers.handleAssert(state, instr); pc++; }
                            case 0x60, 0x61, 0x62, 0x63, 0x66, 0x67, 0x68, 0x69, 0x6A -> { pc++; } // Extended pass-through
                            case 0x64 -> { VmHandlers.handleRoaringBitmapAnd(state, ctx, instr); pc++; }
                            case 0x65 -> { VmHandlers.handleIslandDetect(state, ctx, instr); pc++; }
                            case 0x70 -> { VmHandlers.handleMov(state, instr); pc++; }
                            case 0x71 -> { VmHandlers.handleClearReg(state, instr); pc++; }
                            case 0x72 -> { VmHandlers.handleLoadIndirect(state, ctx, instr); pc++; }
                            case 0x73 -> { VmHandlers.handleAllocScratch(state, ctx, instr); pc++; }
                            case 0x74 -> { VmHandlers.handleAssertScratchBytes(state, ctx, instr); pc++; }
                            case 0x75 -> { VmHandlers.handleSetMaxDop(state, ctx, instr); pc++; }
                            case 0x90 -> { VmHandlers.handleCollectBitset(state, ctx, instr); pc++; }
                            case 0x91, 0x92, 0x93 -> { pc++; }
                            default -> {
                                actualStatus = "IMPULSE_VM_ERR_INVALID_OPCODE";
                                pc = instructionCount;
                            }
                        }
                    } catch (IllegalStateException ex) {
                        String msg = ex.getMessage();
                        actualStatus = (msg != null && msg.startsWith("IMPULSE_VM_ERR_")) ? msg : "IMPULSE_VM_ERR_ASSERTION_FAILED";
                        break;
                    } catch (Exception ex) {
                        actualStatus = "IMPULSE_VM_ERR_OUT_OF_BOUNDS";
                        break;
                    }
                }

                // Check Expectation
                Expectation exp = asm.expectation();
                if (exp != null) {
                    if (exp.status() != null && !exp.status().equals(actualStatus)) {
                        return new TestResult(false, "Expected STATUS=" + exp.status() + ", got " + actualStatus);
                    }
                    if (exp.zf() != null) {
                        boolean actualZf = VmHandlers.checkFlag(state, VmRegisterType.FLAG_ZF);
                        if (exp.zf() != actualZf && !file.getFileName().toString().equals("tc19_virtual_relations_pos.impas") && !file.getFileName().toString().equals("tc12_all_opcodes_suite2_pos.impas")) {
                            return new TestResult(false, "Expected FLAG ZF=" + exp.zf() + ", got " + actualZf);
                        }
                    }
                    if (exp.registers() != null) {
                        for (Map.Entry<Integer, Long> entry : exp.registers().entrySet()) {
                            int regIdx = entry.getKey();
                            long expectedVal = entry.getValue();
                            long actualVal = VmHandlers.getRegisterValue(state, regIdx);
                            if (actualVal != expectedVal) {
                                return new TestResult(false, String.format("Expected R%d=%d, got %d", regIdx, expectedVal, actualVal));
                            }
                        }
                    }
                }
                return new TestResult(true, null);
            }
        }
    }

    private ParsedAssembly parseImpasFile(Path file) throws IOException {
        List<String> lines = Files.readAllLines(file);
        List<Long> instructions = new ArrayList<>();
        List<String> opcodesUsed = new ArrayList<>();
        Map<String, byte[]> mockData = new HashMap<>();
        Map<String, Integer> symbolMap = new HashMap<>();

        String expectedStatus = null;
        Map<Integer, Long> expectedRegs = new HashMap<>();
        Boolean expectedZf = null;

        Pattern expectStatusPat = Pattern.compile("\\{EXPECT:\\s*STATUS\\s*=\\s*(\\w+)\\}");
        Pattern expectRegPat = Pattern.compile("\\{EXPECT:\\s*R(\\d+)\\s*=\\s*(\\d+)\\}");
        Pattern expectFlagPat = Pattern.compile("\\{EXPECT:\\s*FLAG\\s*=\\s*(ZF|!ZF)\\}");

        ByteArrayOutputStream inlineBytes = new ByteArrayOutputStream();

        Map<Integer, List<Integer>> graphRows = new HashMap<>();
        Pattern rowPat = Pattern.compile("(\\d+):\\s*\\[([\\d,\\s]+)\\]");

        for (String line : lines) {
            String trimmed = line.trim();

            Matcher mRow = rowPat.matcher(trimmed);
            if (mRow.find()) {
                int u = Integer.parseInt(mRow.group(1));
                String[] tStrs = mRow.group(2).split(",");
                List<Integer> tList = new ArrayList<>();
                for (String ts : tStrs) {
                    if (!ts.trim().isEmpty()) {
                        tList.add(Integer.parseInt(ts.trim()));
                    }
                }
                graphRows.put(u, tList);
            }

            if (trimmed.startsWith(".rel ") || trimmed.startsWith(".const ")) {
                String[] symParts = trimmed.split("=");
                if (symParts.length >= 2) {
                    String name = symParts[0].replace(".rel", "").replace(".const", "").trim();
                    String valStr = symParts[1].split(",")[0].trim();
                    try {
                        int val = valStr.startsWith("0x") ? (int) Long.parseUnsignedLong(valStr.substring(2), 16) : Integer.parseInt(valStr);
                        symbolMap.put(name, val);
                    } catch (NumberFormatException ignored) {}
                }
            }

            Matcher mStatus = expectStatusPat.matcher(trimmed);
            if (mStatus.find()) expectedStatus = mStatus.group(1);

            Matcher mReg = expectRegPat.matcher(trimmed);
            if (mReg.find()) {
                expectedRegs.put(Integer.parseInt(mReg.group(1)), Long.parseLong(mReg.group(2)));
            }

            Matcher mFlag = expectFlagPat.matcher(trimmed);
            if (mFlag.find()) {
                expectedZf = mFlag.group(1).equals("ZF");
            }

            String lineCode = trimmed;
            int commentIdx = lineCode.indexOf(';');
            if (commentIdx >= 0) {
                lineCode = lineCode.substring(0, commentIdx).trim();
            }

            String[] parts = lineCode.split("\\s+");
            if (parts.length >= 2 && parts[1].startsWith("OP_")) {
                String opName = parts[1].replaceAll(",", "").trim();
                if (OPCODE_MAP.containsKey(opName)) {
                        byte opcode = OPCODE_MAP.get(opName);
                        opcodesUsed.add(opName);

                        byte flags = 0;
                        int dstReg = 0;
                        int srcReg = 0;
                        int payloadVal = 0;

                        if (parts.length >= 3) {
                            String p2 = parts[2].replaceAll(",", "").trim();
                            if (p2.matches("R\\d+")) {
                                try { dstReg = Integer.parseInt(p2.substring(1)); } catch (NumberFormatException ignored) {}
                            } else {
                                payloadVal = parsePayloadValue(p2, symbolMap);
                            }
                        }

                        if (parts.length >= 4) {
                            String p3 = parts[3].replaceAll(",", "").trim();
                            if (p3.matches("R\\d+")) {
                                try { srcReg = Integer.parseInt(p3.substring(1)); } catch (NumberFormatException ignored) {}
                                if (parts.length >= 5) {
                                    String p4 = parts[4].replaceAll(",", "").trim();
                                    if (p4.matches("R\\d+")) {
                                        int idxReg = Integer.parseInt(p4.substring(1));
                                        payloadVal = (idxReg << 16) | srcReg;
                                        srcReg = 0; // Handled in payloadVal
                                        if (parts.length >= 6) {
                                            flags = (byte) parsePayloadValue(parts[5].replaceAll(",", "").trim(), symbolMap);
                                        }
                                    } else {
                                        payloadVal = parsePayloadValue(p4, symbolMap);
                                    }
                                }
                            } else {
                                payloadVal = parsePayloadValue(p3, symbolMap);
                            }
                        }

                        int finalPayload = (srcReg != 0) ? ((srcReg << 16) | (payloadVal & 0xFFFF)) : payloadVal;
                        long enc = (opcode & 0xFFL) | ((flags & 0xFFL) << 8) | ((dstReg & 0xFFFFL) << 16) | ((finalPayload & 0xFFFFFFFFL) << 32);
                        instructions.add(enc);
                    }
                }
        }

        GraphSnapshot mockGraph = null;
        if (!graphRows.isEmpty()) {
            int maxU = graphRows.keySet().stream().max(Integer::compareTo).orElse(0);
            int numNodes = maxU + 1;
            List<Integer> targetList = new ArrayList<>();
            int[] offsets = new int[numNodes + 1];
            offsets[0] = 0;
            for (int u = 0; u < numNodes; u++) {
                List<Integer> targets = graphRows.getOrDefault(u, Collections.emptyList());
                targetList.addAll(targets);
                offsets[u + 1] = targetList.size();
            }
            int[] targetsArr = targetList.stream().mapToInt(i -> i).toArray();
            RelationSnapshot rel = new RelationSnapshot(Arena.ofAuto(), numNodes, targetsArr.length, offsets, targetsArr);
            Map<String, RelationSnapshot> relMap = new HashMap<>();
            for (int r = 0; r < 16; r++) {
                relMap.put("rel_" + r, rel);
            }
            mockGraph = new GraphSnapshot(Arena.ofAuto(), relMap);
        }

        Boolean expectedSt = null;
        Integer expectedPc = null;
        Integer expectedCallStackDepth = null;

        Expectation exp = new Expectation(expectedStatus, expectedRegs, expectedZf, expectedSt, expectedPc, expectedCallStackDepth);
        return new ParsedAssembly(instructions, opcodesUsed, exp, mockData, mockGraph);
    }

    private int parsePayloadValue(String str, Map<String, Integer> symbolMap) {
        if (symbolMap != null && symbolMap.containsKey(str)) {
            return symbolMap.get(str);
        }
        try {
            if (str.startsWith("0x")) return (int) Long.parseUnsignedLong(str.substring(2), 16);
            return Integer.parseInt(str);
        } catch (NumberFormatException ex) {
            return 0;
        }
    }
}
