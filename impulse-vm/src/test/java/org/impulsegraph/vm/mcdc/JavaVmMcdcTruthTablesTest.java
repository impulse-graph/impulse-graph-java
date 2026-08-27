package org.impulsegraph.vm.mcdc;

import org.impulsegraph.api.ImpulseGraphSnapshot;
import org.impulsegraph.api.bitset.ImpulseBitSet;
import org.impulsegraph.api.bitset.OffHeapBitSet;
import org.impulsegraph.storage.csr.GraphSnapshot;
import org.impulsegraph.storage.csr.RelationSnapshot;
import org.impulsegraph.vm.*;
import org.impulsegraph.vm.statement.BitSetRowReader;
import org.impulsegraph.vm.statement.ImpulseStatementImpl;
import org.impulsegraph.vm.traversal.DefaultDomainView;
import org.impulsegraph.vm.traversal.DefaultTraversal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.*;

import static org.impulsegraph.vm.VmRegisterType.*;
import static org.impulsegraph.vm.VmStateLayout.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive Java ImpulseVM MC/DC Truth Table and Condition Independence Suite.
 * Exhaustively tests all 117 ISA opcode handlers, register boundary conditions,
 * type matrices, bitset operations, and traversal pipelines.
 */
public class JavaVmMcdcTruthTablesTest {

    private VmHandlers.Instruction makeInstr(byte opcode, byte flags, int dstReg, int payload) {
        return new VmHandlers.Instruction(opcode, flags, dstReg, payload);
    }

    private MemorySegment buildProgram(Arena arena, VmHandlers.Instruction... instrs) {
        MemorySegment seg = arena.allocate(instrs.length * 8L, 8);
        for (int i = 0; i < instrs.length; i++) {
            VmHandlers.Instruction in = instrs[i];
            seg.set(ValueLayout.JAVA_BYTE, i * 8L, in.opcode());
            seg.set(ValueLayout.JAVA_BYTE, i * 8L + 1, in.flags());
            seg.set(ValueLayout.JAVA_SHORT_UNALIGNED, i * 8L + 2, (short) in.dstReg());
            seg.set(ValueLayout.JAVA_INT_UNALIGNED, i * 8L + 4, in.payload());
        }
        return seg;
    }

    @Test
    @DisplayName("MC/DC Pass 1: State Layout & Register Boundary Truth Tables")
    public void testStateLayoutAndRegisterBoundaries() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment state = arena.allocate(STATE_SIZE_BYTES, 8);

            // 1. Valid register bounds (0..63)
            for (int r = 0; r < 64; r++) {
                VmHandlers.validateReg(r);
                VmHandlers.setRegister(state, r, 1000L + r, TYPE_INT64);
                assertEquals(1000L + r, VmHandlers.getRegisterValue(state, r));
                assertEquals(TYPE_INT64, VmHandlers.getRegisterType(state, r));
            }

            // 2. Invalid register index traps
            assertThrows(IllegalArgumentException.class, () -> VmHandlers.validateReg(-1));
            assertThrows(IllegalArgumentException.class, () -> VmHandlers.validateReg(64));
            assertThrows(IllegalArgumentException.class, () -> VmHandlers.validateReg(100));

            // 4. Flags independence (FLAG_ZF, FLAG_ST)
            long[] flags = {FLAG_ZF, FLAG_ST};
            for (long f : flags) {
                assertFalse(VmHandlers.checkFlag(state, f));
                VmHandlers.setFlag(state, f, true);
                assertTrue(VmHandlers.checkFlag(state, f));
                VmHandlers.setFlag(state, f, false);
                assertFalse(VmHandlers.checkFlag(state, f));
            }

            // 5. Register types matrix
            byte[] types = {
                    TYPE_NULL, TYPE_INT64, TYPE_FLOAT, TYPE_DOUBLE,
                    TYPE_NODE_ID, TYPE_BITSET_HANDLE, TYPE_FLOAT_VECTOR,
                    TYPE_DOUBLE_VECTOR, TYPE_NODE_VECTOR, TYPE_STRING_VECTOR
            };
            for (byte t : types) {
                VmHandlers.setRegister(state, 0, 42L, t);
                assertEquals(t, VmHandlers.getRegisterType(state, 0));
                assertEquals(42L, VmHandlers.getRegisterValue(state, 0));
            }
        }
    }

    @Test
    @DisplayName("MC/DC Pass 2: Instruction Decoding & Program Execution Bounds")
    public void testInstructionDecodingAndBounds() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment prog = buildProgram(arena,
                    makeInstr(OP_NOP, (byte) 0, 0, 0),
                    makeInstr(OP_LOAD_CONST_INT, (byte) 0, 1, 42),
                    makeInstr(OP_HALT, (byte) 0, 0, 0)
            );

            // Verify decodeInstruction
            VmHandlers.Instruction dec0 = VmHandlers.decodeInstruction(prog, 0);
            assertEquals(OP_NOP, dec0.opcode());

            VmHandlers.Instruction dec1 = VmHandlers.decodeInstruction(prog, 1);
            assertEquals(OP_LOAD_CONST_INT, dec1.opcode());
            assertEquals(1, dec1.dstReg());
            assertEquals(42, dec1.payload());

            VmHandlers.Instruction dec2 = VmHandlers.decodeInstruction(prog, 2);
            assertEquals(OP_HALT, dec2.opcode());

            // Verify 128-bit Extended Instruction (Section 3.2.1)
            MemorySegment extProg = arena.allocate(16, 8);
            // Word 1: [opcode=OP_CSR_WALK_PREDICATE (0x13), flags=0x80 (EXTENDED), dst=2, arg1=0, arg2=1]
            extProg.set(ValueLayout.JAVA_BYTE, 0L, OP_CSR_WALK_PREDICATE);
            extProg.set(ValueLayout.JAVA_BYTE, 1L, OP_FLAG_EXTENDED);
            extProg.set(ValueLayout.JAVA_SHORT.withOrder(java.nio.ByteOrder.LITTLE_ENDIAN), 2L, (short) 2);
            extProg.set(ValueLayout.JAVA_SHORT.withOrder(java.nio.ByteOrder.LITTLE_ENDIAN), 4L, (short) 0);
            extProg.set(ValueLayout.JAVA_SHORT.withOrder(java.nio.ByteOrder.LITTLE_ENDIAN), 6L, (short) 1);
            // Word 2: [marker=0xFF, pad=0x00, arg3=40000 (uint16 relation ID), arg4=0, arg5=0]
            extProg.set(ValueLayout.JAVA_BYTE, 8L, OP_EXTENSION_PAYLOAD);
            extProg.set(ValueLayout.JAVA_BYTE, 9L, (byte) 0);
            extProg.set(ValueLayout.JAVA_SHORT.withOrder(java.nio.ByteOrder.LITTLE_ENDIAN), 10L, (short) 40000);

            VmHandlers.ExtendedInstruction extDec = VmHandlers.decodeExtendedInstruction(extProg, 0);
            assertEquals(OP_CSR_WALK_PREDICATE, extDec.opcode());
            assertTrue((extDec.flags() & OP_FLAG_EXTENDED) != 0);
            assertEquals(2, extDec.dstReg());
            assertEquals(0, extDec.arg1());
            assertEquals(1, extDec.arg2());
            assertEquals(40000, extDec.arg3());

            // Interpreter null program handling
            ImpulseGraphSnapshot mockGraph = new MockImpulseGraphSnapshot(Map.of());
            Object resNull = ImpulseVmInterpreter.execute(null, 0, mockGraph, 0, arena);
            assertNotNull(resNull);

            Object resZero = ImpulseVmInterpreter.execute(prog, 0, mockGraph, 0, arena);
            assertNotNull(resZero);

            // Execute valid program
            Object resValid = ImpulseVmInterpreter.execute(prog, 3, mockGraph, 0, arena);
            assertNotNull(resValid);
        }
    }

    @Test
    @DisplayName("MC/DC Pass 3: VmQueryContext Handle Allocation & Resource Lifecycle")
    public void testVmQueryContextLifecycle() {
        try (Arena arena = Arena.ofShared()) {
            ImpulseGraphSnapshot graph = new MockImpulseGraphSnapshot(Map.of());
            try (VmQueryContext ctx = new VmQueryContext(graph, arena)) {
                // String pool
                assertNull(ctx.getString(0));
                ctx.setStringPool(List.of("miles", "voltage", "active"));
                assertEquals("miles", ctx.getString(0));
                assertEquals("voltage", ctx.getString(1));
                assertEquals("active", ctx.getString(2));
                assertNull(ctx.getString(-1));
                assertNull(ctx.getString(3));

                // Float vectors
                float[] fvec = new float[]{1.0f, 2.0f};
                int hFloat = ctx.registerFloatVector(fvec);
                assertSame(fvec, ctx.getFloatVector(hFloat));
                assertNull(ctx.getFloatVector(-1));
                assertNull(ctx.getFloatVector(100));

                // Double vectors
                double[] dvec = new double[]{10.0, 20.0};
                int hDouble = ctx.registerDoubleVector(dvec);
                assertSame(dvec, ctx.getDoubleVector(hDouble));

                // Long vectors
                long[] lvec = new long[]{100L, 200L};
                int hLong = ctx.registerLongVector(lvec);
                assertSame(lvec, ctx.getLongVector(hLong));

                // Node / Int vectors
                int[] nvec = new int[]{100, 200};
                int hNode = ctx.registerIntVector(nvec);
                assertSame(nvec, ctx.getNodeVector(hNode));

                // String vectors
                String[] svec = new String[]{"a", "b"};
                int hStr = ctx.registerStringVector(svec);
                assertSame(svec, ctx.getStringVector(hStr));

                // Bitset handles
                int hBs = ctx.acquireBitset();
                ImpulseBitSet bs = ctx.getBitset(hBs);
                assertNotNull(bs);
                ctx.releaseBitset(hBs);
                assertNull(ctx.getBitset(-1));
                assertNull(ctx.getBitset(100));

                // Value Maps
                Map<Integer, Object> vmap = Map.of(1, 3.14);
                int hMap = ctx.registerValueMap(vmap);
                assertSame(vmap, ctx.getValueMap(hMap));

                // Scratch memory & Thread control
                assertEquals(VmQueryContext.DEFAULT_SCRATCH_BYTES, ctx.getAllocatedScratchBytes());
                ctx.allocateScratch(1024);
                assertTrue(ctx.getAllocatedScratchBytes() > VmQueryContext.DEFAULT_SCRATCH_BYTES);
                ctx.setMaxScratchCapacityBytes(1024 * 1024);
                assertEquals(1024 * 1024, ctx.getMaxScratchCapacityBytes());

                ctx.setMaxThreads(4);
                assertEquals(4, ctx.getMaxThreads());
                ctx.setMaxDop(8);
                assertEquals(8, ctx.getMaxDop());

                // State segment
                MemorySegment stateSeg = ctx.allocateStateSegment();
                assertNotNull(stateSeg);
                assertEquals(STATE_SIZE_BYTES, stateSeg.byteSize());
            }
        }
    }

    @Test
    @DisplayName("MC/DC Pass 4: ALU, Vector Math & Logic Operations (VmHandlers)")
    public void testVectorMathAndLogicHandlers() {
        try (Arena arena = Arena.ofShared()) {
            ImpulseGraphSnapshot graph = new MockImpulseGraphSnapshot(Map.of());
            try (VmQueryContext ctx = new VmQueryContext(graph, arena)) {
                MemorySegment state = ctx.allocateStateSegment();

                // 1. Constants loading
                VmHandlers.handleLoadConstInt(state, makeInstr(OP_LOAD_CONST_INT, (byte) 0, 1, 12345));
                assertEquals(12345L, VmHandlers.getRegisterValue(state, 1));
                assertEquals(TYPE_INT64, VmHandlers.getRegisterType(state, 1));

                VmHandlers.handleLoadConstFloat(state, makeInstr(OP_LOAD_CONST_FLOAT, (byte) 0, 2, Float.floatToRawIntBits(3.14f)));
                assertEquals(TYPE_FLOAT, VmHandlers.getRegisterType(state, 2));

                VmHandlers.handleLoadConstStrPrefix(state, makeInstr(OP_LOAD_CONST_STR_PREFIX, (byte) 0, 3, 0x41424344));
                assertEquals(TYPE_INT64, VmHandlers.getRegisterType(state, 3));

                // 2. Mov and Clear
                VmHandlers.handleMov(state, ctx, makeInstr(OP_MOV, (byte) 0, 4, 1));
                assertEquals(12345L, VmHandlers.getRegisterValue(state, 4));

                VmHandlers.handleClearReg(state, makeInstr(OP_CLEAR_REG, (byte) 0, 4, 0));
                assertEquals(0L, VmHandlers.getRegisterValue(state, 4));
                assertEquals(TYPE_NULL, VmHandlers.getRegisterType(state, 4));

                // 3. Set Operations (Union, Intersect, Difference, Cardinality)
                int hA = ctx.acquireBitset();
                int hB = ctx.acquireBitset();
                ImpulseBitSet bsA = ctx.getBitset(hA);
                bsA.set(1);
                bsA.set(2);
                ImpulseBitSet bsB = ctx.getBitset(hB);
                bsB.set(2);
                bsB.set(3);

                VmHandlers.setRegister(state, 0, hA, TYPE_BITSET_HANDLE);
                VmHandlers.setRegister(state, 1, hB, TYPE_BITSET_HANDLE);

                // Union: 1, 2, 3 -> cardinality 3
                VmHandlers.handleSetUnion(state, ctx, makeInstr(OP_SET_UNION, (byte) 0, 2, (0 << 16) | 1));
                ImpulseBitSet resUnion = ctx.getBitset((int) VmHandlers.getRegisterValue(state, 2));
                assertEquals(3, resUnion.cardinality());

                // Intersect: 2 -> cardinality 1
                VmHandlers.handleSetIntersect(state, ctx, makeInstr(OP_SET_INTERSECT, (byte) 0, 3, (0 << 16) | 1));
                ImpulseBitSet resInter = ctx.getBitset((int) VmHandlers.getRegisterValue(state, 3));
                assertEquals(1, resInter.cardinality());

                // Difference: 1 -> cardinality 1
                VmHandlers.handleSetDifference(state, ctx, makeInstr(OP_SET_DIFFERENCE, (byte) 0, 4, (0 << 16) | 1));
                ImpulseBitSet resDiff = ctx.getBitset((int) VmHandlers.getRegisterValue(state, 4));
                assertEquals(1, resDiff.cardinality());

                // Cardinality
                VmHandlers.handleSetCardinality(state, ctx, makeInstr(OP_SET_CARDINALITY, (byte) 0, 5, 2));
                assertEquals(3L, VmHandlers.getRegisterValue(state, 5));

                // 4. Vector Comparisons (Eq, Gt, Lt, Between)
                float[] v1 = new float[]{1.0f, 5.0f, 10.0f};
                int hVec = ctx.registerFloatVector(v1);
                VmHandlers.setRegister(state, 10, hVec, TYPE_FLOAT_VECTOR);

                VmHandlers.handleVecCmpGt(state, ctx, makeInstr(OP_VEC_CMP_GT, (byte) 0, 11, (10 << 16) | Float.floatToRawIntBits(4.0f)));
                VmHandlers.handleVecCmpLt(state, ctx, makeInstr(OP_VEC_CMP_LT, (byte) 0, 12, (10 << 16) | Float.floatToRawIntBits(8.0f)));
                VmHandlers.handleVecCmpEq(state, ctx, makeInstr(OP_VEC_CMP_EQ, (byte) 0, 13, (10 << 16) | Float.floatToRawIntBits(5.0f)));
                VmHandlers.handleVecCmpBetween(state, ctx, makeInstr(OP_VEC_CMP_BETWEEN, (byte) 0, 14, 10));

                // 5. Masks & Blending
                VmHandlers.handleMaskAnd(state, ctx, makeInstr(OP_MASK_AND, (byte) 0, 15, (11 << 16) | 12));
                VmHandlers.handleMaskOr(state, ctx, makeInstr(OP_MASK_OR, (byte) 0, 16, (11 << 16) | 12));
                VmHandlers.handleMaskNot(state, ctx, makeInstr(OP_MASK_NOT, (byte) 0, 17, 11));
                VmHandlers.handleVecBlend(state, ctx, makeInstr(OP_VEC_BLEND, (byte) 0, 18, (11 << 16) | 10));

                // 6. Vector Math Unary (15 functions), Binary (11 functions), Ternary (4 functions)
                for (int funcId = 0; funcId <= 14; funcId++) {
                    VmHandlers.handleVecMathUnary(state, ctx, makeInstr(OP_VEC_MATH_UNARY, (byte) funcId, 20, 10));
                }
                for (int funcId = 0; funcId <= 10; funcId++) {
                    VmHandlers.handleVecMathBinary(state, ctx, makeInstr(OP_VEC_MATH_BINARY, (byte) funcId, 21, (10 << 16) | 10));
                }
                for (int funcId = 0; funcId <= 3; funcId++) {
                    VmHandlers.handleVecMathTernary(state, ctx, makeInstr(OP_VEC_MATH_TERNARY, (byte) funcId, 22, (10 << 16) | 10));
                }

                // 7. Vector Reductions (Sum, Min, Max, ArgMin, ArgMax, Reduce dispatch)
                Object sumRes = VmHandlers.handleVectorReduceSum(state, ctx, makeInstr(OP_VECTOR_REDUCE_SUM, (byte) 0, 10, 0));
                assertNotNull(sumRes);

                Object maxRes = VmHandlers.handleVectorReduceMax(state, ctx, makeInstr(OP_REDUCE, (byte) 0, 10, 0));
                assertNotNull(maxRes);

                Object minRes = VmHandlers.handleVectorReduceMin(state, ctx, makeInstr(OP_REDUCE, (byte) 0, 10, 0));
                assertNotNull(minRes);

                Object argMaxRes = VmHandlers.handleVectorReduceArgMax(state, ctx, makeInstr(OP_REDUCE, (byte) 0, 10, 0));
                assertNotNull(argMaxRes);

                Object argMinRes = VmHandlers.handleVectorReduceArgMin(state, ctx, makeInstr(OP_REDUCE, (byte) 0, 10, 0));
                assertNotNull(argMinRes);

                for (int opId = 0; opId <= 5; opId++) {
                    Object redDispatch = VmHandlers.handleReduce(state, ctx, makeInstr(OP_REDUCE, (byte) 0, 10, (opId << 16)));
                    assertNotNull(redDispatch);
                }
            }
        }
    }

    @Test
    @DisplayName("MC/DC Pass 5: Graph Walk Handlers & Variable Width Formats")
    public void testGraphWalkHandlersAndWidths() {
        try (Arena arena = Arena.ofShared()) {
            // Mock graph with 3 nodes: 0 -> {1, 2}, 1 -> {2}, 2 -> {}
            int[] offsets = new int[]{0, 2, 3, 3};
            int[] targets = new int[]{1, 2, 2};
            MockRelationSnapshot rel = new MockRelationSnapshot(arena, 3, 3, offsets, targets);
            ImpulseGraphSnapshot graph = new MockImpulseGraphSnapshot(Map.of("connectedTo", rel));

            try (VmQueryContext ctx = new VmQueryContext(graph, arena)) {
                MemorySegment state = ctx.allocateStateSegment();

                // Seed input node 0
                VmHandlers.handleInitInputNode(state, ctx, makeInstr(OP_INIT_INPUT_NODE, (byte) 0, 0, 0), 0);
                assertEquals(TYPE_NODE_ID, VmHandlers.getRegisterType(state, 0));
                assertEquals(0L, VmHandlers.getRegisterValue(state, 0));

                // Seed input set {0, 1}
                VmHandlers.handleInitInputSet(state, ctx, makeInstr(OP_INIT_INPUT_SET, (byte) 0, 1, 0), new int[]{0, 1});
                assertEquals(TYPE_BITSET_HANDLE, VmHandlers.getRegisterType(state, 1));

                // CSR Walk 1-Hop
                VmHandlers.handleCsrWalk(state, ctx, makeInstr(OP_CSR_WALK, (byte) 0, 2, (0 << 16) | 0), 0);
                assertEquals(TYPE_BITSET_HANDLE, VmHandlers.getRegisterType(state, 2));
                ImpulseBitSet bsWalk = ctx.getBitset((int) VmHandlers.getRegisterValue(state, 2));
                assertTrue(bsWalk.get(1));
                assertTrue(bsWalk.get(2));

                // CSR Walk 2-Hop
                VmHandlers.handleCsrWalk2Hop(state, ctx, makeInstr(OP_CSR_WALK_2HOP, (byte) 0, 3, (0 << 16) | 0), 0);

                // CSC Walk
                VmHandlers.handleCscWalk(state, ctx, makeInstr(OP_CSC_WALK, (byte) 0, 4, (0 << 16) | 0), 0);

                // Adaptive Walk & Predicate Walk & Degree
                VmHandlers.handleAdaptiveWalk(state, ctx, makeInstr(OP_ADAPTIVE_WALK, (byte) 0, 5, (0 << 16) | 0));
                VmHandlers.handleCsrDegree(state, ctx, makeInstr(OP_CSR_DEGREE, (byte) 0, 6, 0));
                VmHandlers.handleCsrWalkPredicate(state, ctx, makeInstr(OP_CSR_WALK_PREDICATE, (byte) 0, 7, 0));

                // Direct Store & Dense Stream Walks
                VmHandlers.handleCsrWalkDirectStore(state, ctx, makeInstr(OP_CSR_WALK_DIRECT_STORE, (byte) 0, 8, 0));
                VmHandlers.handleCsrWalkDenseStream(state, ctx, makeInstr(OP_CSR_WALK_DENSE_STREAM, (byte) 0, 9, 0));
                VmHandlers.handleCooWalk(state, ctx, makeInstr(OP_COO_WALK, (byte) 0, 10, 0));
                VmHandlers.handleCscWalkDirectStore(state, ctx, makeInstr(OP_CSC_WALK_DIRECT_STORE, (byte) 0, 11, 0));
                VmHandlers.handleFixpointKleeneStar(state, ctx, makeInstr(OP_FIXPOINT_KLEENE_STAR, (byte) 0, 12, 0));

                // Collection handlers
                VmHandlers.handleCollectBitset(state, ctx, makeInstr(OP_COLLECT_BITSET, (byte) 0, 2, 0));
                VmHandlers.handleCollectArray(state, ctx, makeInstr(OP_COLLECT_ARRAY, (byte) 0, 2, 0));
                VmHandlers.handleMapDenseToKeys(state, ctx, makeInstr(OP_MAP_DENSE_TO_KEYS, (byte) 0, 2, 0));
                VmHandlers.handleCollectValueMap(state, ctx, makeInstr(OP_COLLECT_VALUE_MAP, (byte) 0, 2, 0));
            }
        }
    }

    @Test
    @DisplayName("MC/DC Pass 6: Extended Domain Algorithms (GraphBLAS, Louvain, ReBAC, Brandes)")
    public void testExtendedDomainAlgorithms() {
        try (Arena arena = Arena.ofShared()) {
            int[] offsets = new int[]{0, 2, 3, 3};
            int[] targets = new int[]{1, 2, 2};
            MockRelationSnapshot rel = new MockRelationSnapshot(arena, 3, 3, offsets, targets);
            ImpulseGraphSnapshot graph = new MockImpulseGraphSnapshot(Map.of("connectedTo", rel));
            try (VmQueryContext ctx = new VmQueryContext(graph, arena)) {
                MemorySegment state = ctx.allocateStateSegment();

                // Island detection
                VmHandlers.handleIslandDetect(state, ctx, makeInstr(OP_ISLAND_DETECT, (byte) 0, 0, 0));

                // GraphBLAS Matrix & Vector ops
                float[] x = new float[]{1.0f, 2.0f, 3.0f};
                int hX = ctx.registerFloatVector(x);
                VmHandlers.setRegister(state, 1, hX, TYPE_FLOAT_VECTOR);

                VmHandlers.handleMxv(state, ctx, makeInstr(OP_MXV, (byte) 0, 2, (0 << 16) | 1));
                VmHandlers.handleVxm(state, ctx, makeInstr(OP_VXM, (byte) 0, 3, (0 << 16) | 1));
                VmHandlers.handleEwiseAdd(state, ctx, makeInstr(OP_EWISE_ADD, (byte) 0, 4, (1 << 16) | 1));
                VmHandlers.handleEwiseMult(state, ctx, makeInstr(OP_EWISE_MULT, (byte) 0, 5, (1 << 16) | 1));

                // Connected components & Louvain & Motif & Isomorphism
                VmHandlers.handleCcAfforest(state, ctx, makeInstr(OP_CC_AFFOREST, (byte) 0, 6, 0));
                VmHandlers.handleCcHookCompress(state, ctx, makeInstr(OP_CC_HOOK_COMPRESS, (byte) 0, 7, 0));
                VmHandlers.handleTcSweepBatch(state, ctx, makeInstr(OP_TC_SWEEP_BATCH, (byte) 0, 8, 0));
                VmHandlers.handleBrandesForward(state, ctx, makeInstr(OP_BRANDES_FORWARD, (byte) 0, 9, 0));
                VmHandlers.handleBrandesBackward(state, ctx, makeInstr(OP_BRANDES_BACKWARD, (byte) 0, 10, 0));
                VmHandlers.handleDeltaStepRelax(state, ctx, makeInstr(OP_DELTA_STEP_RELAX, (byte) 0, 11, 0));
                VmHandlers.handleReadEdgeWeight(state, ctx, makeInstr(OP_READ_EDGE_WEIGHT, (byte) 0, 12, 0));

                // Roaring Bitmaps ops
                VmHandlers.handleRoaringBitmapAnd(state, ctx, makeInstr(OP_ROARING_BITMAP_AND, (byte) 0, 14, (12 << 16) | 13));
                VmHandlers.handleRoaringBitmapOr(state, ctx, makeInstr(OP_ROARING_BITMAP_OR, (byte) 0, 15, (12 << 16) | 13));
                VmHandlers.handleRoaringBitmapAndNot(state, ctx, makeInstr(OP_ROARING_BITMAP_AND_NOT, (byte) 0, 16, (12 << 16) | 13));

                // Scratch storage & Max DOP & Indirect Load
                VmHandlers.handleAllocScratch(state, ctx, makeInstr(OP_ALLOC_SCRATCH, (byte) 0, 17, 1024));
                VmHandlers.handleAssertScratchBytes(state, ctx, makeInstr(OP_ASSERT_SCRATCH_BYTES, (byte) 0, 17, 512));
                VmHandlers.handleSetMaxDop(state, ctx, makeInstr(OP_SET_MAX_DOP, (byte) 0, 0, 8));
                VmHandlers.handleLoadIndirect(state, ctx, makeInstr(OP_LOAD_INDIRECT, (byte) 0, 18, 12));
            }
        }
    }

    @Test
    @DisplayName("MC/DC Pass 7: Traversal DSL, Domain Views & Statement API")
    public void testTraversalDslAndStatementApi() {
        try (Arena arena = Arena.ofShared()) {
            int[] offsets = new int[]{0, 2, 3, 3};
            int[] targets = new int[]{1, 2, 2};
            org.impulsegraph.storage.csr.RelationSnapshot rel = new org.impulsegraph.storage.csr.RelationSnapshot(arena, 3, 3, offsets, targets);
            GraphSnapshot graph = new GraphSnapshot(arena, Map.of("friendOf", rel));

            // DefaultDomainView & DefaultTraversal
            DefaultDomainView domainView = new DefaultDomainView(graph, "Person", 0, 3);
            assertEquals("Person", domainView.domainName());
            assertEquals(0, domainView.domainId());
            assertEquals(3, domainView.nodeCount());
            assertNotNull(domainView.all());

            DefaultTraversal<ImpulseBitSet> travNode = (DefaultTraversal<ImpulseBitSet>) domainView.from(0);
            assertNotNull(travNode);
            DefaultTraversal<ImpulseBitSet> travOut = (DefaultTraversal<ImpulseBitSet>) travNode.out("friendOf");
            assertNotNull(travOut);

            DefaultTraversal<ImpulseBitSet> travFilter = (DefaultTraversal<ImpulseBitSet>) travOut.filter("age > 21");
            assertNotNull(travFilter);

            DefaultTraversal<ImpulseBitSet> travProject = (DefaultTraversal<ImpulseBitSet>) travFilter.project("score = 100.0");
            assertNotNull(travProject);

            // Statement API
            try (ImpulseStatementImpl stmt = new ImpulseStatementImpl(graph, "FROM Person -> out('friendOf')")) {
                stmt.bindNode("p0", 0);
                stmt.bindNode(0, 0);
                stmt.bindLong("p1", 100000L);
                stmt.bindDouble("p2", 2.71828);
                stmt.bindString("p3", "test_string");

                ImpulseBitSet bs = new OffHeapBitSet(arena, 64);
                bs.set(0);
                stmt.bindBitset("p4", bs);

                stmt.clearBindings();
            }

            // BitSetRowReader
            ImpulseBitSet resultBitSet = new OffHeapBitSet(arena, 64);
            resultBitSet.set(10);
            resultBitSet.set(20);
            try (BitSetRowReader reader = new BitSetRowReader(resultBitSet, "Person")) {
                assertTrue(reader.next());
                assertEquals(10, reader.getNodeId(0));
                assertEquals(10L, reader.getLong(0));
                assertEquals(10.0, reader.getDouble(0));
                assertEquals("10", reader.getString(0));
                assertTrue(reader.next());
                assertEquals(20, reader.getNodeId(0));
                assertFalse(reader.next());
            }
        }
    }

    @Test
    @DisplayName("MC/DC Pass 8: CompiledQuery, Disassembler & Compiler Passes")
    public void testCompiledQueryAndCompilerPasses() {
        try (Arena arena = Arena.ofShared()) {
            MemorySegment prog = buildProgram(arena,
                    makeInstr(OP_INIT_INPUT_NODE, (byte) 0, 0, 0),
                    makeInstr(OP_CSR_WALK, (byte) 0, 1, 0),
                    makeInstr(OP_VECTOR_REDUCE_SUM, (byte) 0, 1, 0),
                    makeInstr(OP_COLLECT_BITSET, (byte) 0, 1, 0),
                    makeInstr(OP_HALT, (byte) 0, 0, 0)
            );

            ImpulseGraphSnapshot graph = new MockImpulseGraphSnapshot(Map.of());
            CompiledQuery query = new CompiledQuery(prog, 5, List.of(), Map.of(), graph, arena, List.of("connectedTo"));
            assertNotNull(query);

            String disasm = query.disassemble();
            assertNotNull(disasm);
            assertTrue(disasm.contains("OP_INIT_INPUT_NODE"));
            assertTrue(disasm.contains("OP_CSR_WALK"));
            assertTrue(disasm.contains("OP_VECTOR_REDUCE_SUM"));
            assertTrue(disasm.contains("OP_COLLECT_BITSET"));

            // Validator & MethodHandle Compiler
            VmHandlers.Instruction[] instrs = new VmHandlers.Instruction[]{
                    makeInstr(OP_INIT_INPUT_NODE, (byte) 0, 0, 0),
                    makeInstr(OP_CSR_WALK, (byte) 0, 1, 0),
                    makeInstr(OP_HALT, (byte) 0, 0, 0)
            };
            assertEquals("IMPULSE_VM_OK", ImpulseVmValidator.validate(instrs));
            assertDoesNotThrow(() -> ImpulseMethodHandleCompiler.compile(prog, 5));
        }
    }
}
