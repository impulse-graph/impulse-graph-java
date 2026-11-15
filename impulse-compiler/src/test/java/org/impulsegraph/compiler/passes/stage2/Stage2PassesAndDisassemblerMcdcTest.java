package org.impulsegraph.compiler.passes.stage2;

import org.impulsegraph.api.stats.RelationStatistics;
import org.impulsegraph.api.stats.RelationStatistics.Multiplicity;
import org.impulsegraph.compiler.ast.*;
import org.impulsegraph.compiler.emitter.ImpAsmDisassembler;
import org.impulsegraph.compiler.emitter.ImpOpsBytecodeEmitter.EmittedProgram;
import org.impulsegraph.compiler.emitter.ImpOpsBytecodeEmitter.InstructionWord;
import org.impulsegraph.vm.RelationInstructionPatch;
import org.impulsegraph.compiler.passes.CompilerContext;
import org.impulsegraph.compiler.trace.CompilerOptions;
import org.impulsegraph.compiler.trace.PassTracer;
import org.impulsegraph.storage.csr.GraphSnapshot;
import org.impulsegraph.storage.csr.RelationSnapshot;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.List;
import java.util.Map;

import static org.impulsegraph.vm.VmRegisterType.*;
import static org.junit.jupiter.api.Assertions.*;

class Stage2PassesAndDisassemblerMcdcTest {

	private static RelationSnapshot mockRelation(Arena arena, boolean withCsc) {
		MemorySegment rowSeg = arena.allocate(2L * ValueLayout.JAVA_INT.byteSize());
		rowSeg.setAtIndex(ValueLayout.JAVA_INT, 0, 0);
		rowSeg.setAtIndex(ValueLayout.JAVA_INT, 1, 1);
		MemorySegment colSeg = arena.allocate(ValueLayout.JAVA_INT.byteSize());
		colSeg.setAtIndex(ValueLayout.JAVA_INT, 0, 0);
		if (withCsc) {
			return new RelationSnapshot(arena, 1, 1, rowSeg, colSeg, rowSeg, colSeg);
		}
		return new RelationSnapshot(arena, 1, 1, rowSeg, colSeg);
	}

	@Test
	void testDirectionSelectionPassBranches() {
		assertEquals("DirectionSelectionPass", DirectionSelectionPass.INSTANCE.name());

		CompilerOptions disabledOpts = new CompilerOptions(false, false, false, false, false, false, false,
				CompilerOptions.OPT_ALL_DISABLED, null, Map.of());
		CompilerContext disabledCtx = new CompilerContext(null, disabledOpts, new PassTracer(disabledOpts));
		assertNull(DirectionSelectionPass.INSTANCE.transform(null, disabledCtx));
		ScmProgram sampleProg = ScmProgram.of(new ScmWalk("knows", -1, ScmWalk.Direction.AUTO));
		assertSame(sampleProg, DirectionSelectionPass.INSTANCE.transform(sampleProg, disabledCtx));

		CompilerContext enabledCtxNoSnap = new CompilerContext(null, CompilerOptions.DEFAULT,
				new PassTracer(CompilerOptions.DEFAULT));
		ScmWalk autoEmptyRel = new ScmWalk("", -1, ScmWalk.Direction.AUTO);
		ScmWalk outEmpty = (ScmWalk) DirectionSelectionPass.INSTANCE.transform(autoEmptyRel, enabledCtxNoSnap);
		assertEquals(ScmWalk.Direction.FORWARD_CSR, outEmpty.direction());

		try (Arena arena = Arena.ofConfined()) {
			RelationSnapshot cscRel = mockRelation(arena, true);
			RelationSnapshot csrOnlyRel = mockRelation(arena, false);
			GraphSnapshot snap = new GraphSnapshot(arena, Map.of("user_follows", cscRel, "plain", csrOnlyRel));
			CompilerContext ctx = new CompilerContext(snap, CompilerOptions.DEFAULT,
					new PassTracer(CompilerOptions.DEFAULT));

			ScmWalk exactMatch = new ScmWalk("user_follows", -1, ScmWalk.Direction.AUTO,
					List.of(new ScmVectorFilter(ScmSymbol.of("x"))),
					List.of(new ScmWalk("plain", -1, ScmWalk.Direction.AUTO)));
			ScmWalk suffixMatch = new ScmWalk("follows", -1, ScmWalk.Direction.AUTO);
			ScmWalk reverseExplicit = new ScmWalk("missing", -1, ScmWalk.Direction.REVERSE_CSC);
			ScmProgram prog = ScmProgram.of(exactMatch, suffixMatch, reverseExplicit, ScmCollect.bitset());

			ScmProgram out = (ScmProgram) DirectionSelectionPass.INSTANCE.transform(prog, ctx);
			assertEquals(ScmWalk.Direction.FORWARD_CSR, ((ScmWalk) out.steps().get(0)).direction());
			assertEquals(ScmWalk.Direction.FORWARD_CSR,
					((ScmWalk) ((ScmWalk) out.steps().get(0)).subSteps().get(0)).direction());
			assertEquals(ScmWalk.Direction.FORWARD_CSR, ((ScmWalk) out.steps().get(1)).direction());
			assertEquals(ScmWalk.Direction.REVERSE_CSC, ((ScmWalk) out.steps().get(2)).direction());
		}
	}

	@Test
	void testKernelFusionPassBranches() {
		assertEquals("KernelFusionPass", KernelFusionPass.INSTANCE.name());
		CompilerContext disabledCtx = new CompilerContext(null, CompilerOptions.DEFAULT,
				new PassTracer(CompilerOptions.DEFAULT));
		assertNull(KernelFusionPass.INSTANCE.transform(null, disabledCtx));

		CompilerOptions fusionOpts = new CompilerOptions(false, false, false, false, false, false, true, 0, null,
				Map.of());
		CompilerContext fusionNoSnap = new CompilerContext(null, fusionOpts, new PassTracer(fusionOpts));
		ScmSymbol sym = ScmSymbol.of("sym");
		assertSame(sym, KernelFusionPass.INSTANCE.transform(sym, fusionNoSnap));

		// Non-fusible cases: reverse walk, walk with shaderSteps, walk with subSteps
		ScmWalk wFwd = ScmWalk.forward("r1");
		ScmWalk wRev = new ScmWalk("r2", 0, ScmWalk.Direction.REVERSE_CSC);
		ScmWalk wFiltered = ScmWalk.forward("r3", ScmSymbol.of("p"));
		ScmWalk wSub = new ScmWalk("r4", 0, ScmWalk.Direction.FORWARD_CSR, List.of(), List.of(ScmCollect.bitset()));
		ScmProgram nonFusible = ScmProgram.of(wRev, wFwd, wFiltered, wFwd, wSub, wFwd);
		ScmProgram nonFusibleOut = (ScmProgram) KernelFusionPass.INSTANCE.transform(nonFusible, fusionNoSnap);
		assertEquals(6, nonFusibleOut.steps().size());

		// Fusible without snapshot
		ScmProgram fusible = ScmProgram.of(ScmWalk.forward("r1"), ScmWalk.forward("r2"), ScmCollect.bitset());
		ScmProgram fusedOut = (ScmProgram) KernelFusionPass.INSTANCE.transform(fusible, fusionNoSnap);
		assertEquals(2, fusedOut.steps().size());
		assertInstanceOf(ScmWalk2Hop.class, fusedOut.steps().get(0));

		// With snapshot statistics: high-multiplicity MANY_TO_MANY blocks fusion,
		// ONE_TO_ONE allows fusion
		try (Arena arena = Arena.ofConfined()) {
			RelationSnapshot r1 = mockRelation(arena, false);
			GraphSnapshot snap = new GraphSnapshot(arena, Map.of("many", r1, "one", r1));
			snap.getGraphStatistics().putRelationStatistics("many", new RelationStatistics(10, 100, 10, 10, 10.0, 1.0,
					10, 10, 10, 0.1, null, Multiplicity.MANY_TO_MANY, 10, 10.0, false, false, false));
			snap.getGraphStatistics().putRelationStatistics("one", new RelationStatistics(10, 10, 10, 1, 1.0, 0.0, 1, 1,
					1, 0.1, null, Multiplicity.ONE_TO_ONE, 1, 1.0, false, false, false));
			CompilerContext ctx = new CompilerContext(snap, fusionOpts, new PassTracer(fusionOpts));

			ScmProgram blocked = ScmProgram.of(ScmWalk.forward("many"), ScmWalk.forward("one"));
			assertEquals(2, ((ScmProgram) KernelFusionPass.INSTANCE.transform(blocked, ctx)).steps().size());

			ScmProgram allowed = ScmProgram.of(ScmWalk.forward("one"), ScmWalk.forward("many"));
			assertEquals(1, ((ScmProgram) KernelFusionPass.INSTANCE.transform(allowed, ctx)).steps().size());
		}
	}

	@Test
	void testImpAsmDisassemblerAllInstructionComments() {
		List<InstructionWord> words = List.of(new InstructionWord(OP_INIT_INPUT_NODE, (byte) 0, (short) 0, 0),
				new InstructionWord(OP_CSR_WALK, (byte) 0x03, (short) 1, (2 << 16)),
				new InstructionWord(OP_CSC_WALK, (byte) 0x00, (short) 2, (3 << 16) | 1),
				new InstructionWord(OP_CSR_WALK_FILTERED, (byte) 0x02, (short) 3, (4 << 16) | 2),
				new InstructionWord(OP_CSR_WALK_2HOP, (byte) 0x03, (short) 4, (5 << 16) | 1),
				new InstructionWord(OP_NODE_FILTER, (byte) 0, (short) 5, (4 << 16)),
				new InstructionWord(OP_COLLECT_BITSET, (byte) 0, (short) 5, 0),
				new InstructionWord((byte) 0xFE, (byte) 0, (short) 0, 0),
				new InstructionWord(OP_HALT, (byte) 0, (short) 0, 0));
		EmittedProgram prog = new EmittedProgram(MemorySegment.NULL, words.size(),
				List.of(new RelationInstructionPatch(1L, "knows", (short) 2, (short) 1)), Map.of("knows", 2), words,
				List.of());

		String asm = ImpAsmDisassembler.disassemble(prog);
		assertTrue(asm.contains(".version 0.9.0"));
		assertTrue(asm.contains(".instructions 9"));
		assertTrue(asm.contains("Load input node ID into R0"));
		assertTrue(asm.contains("(\"knows\") [seed-inlined] [early-exit]"));
		assertTrue(asm.contains("rel[3] (\"rel_3\")"));
		assertTrue(asm.contains("Fused 2-Hop CSR Walk dst=R4 via rel[1] o rel[5] [seed-inlined] [early-exit]"));
		assertTrue(asm.contains("Vector node filter src=R4 -> dst=R5"));
		assertTrue(asm.contains("Collect active result bitset from R5"));
		assertTrue(asm.contains("OP_UNKNOWN_0xFE"));
		assertTrue(asm.contains("Execution complete"));
	}
}
