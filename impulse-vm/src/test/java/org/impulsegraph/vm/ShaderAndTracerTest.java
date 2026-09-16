package org.impulsegraph.vm;

import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.FloatRange;
import org.impulsegraph.api.ImpulseGraphSnapshot;
import org.impulsegraph.api.bitset.ImpulseBitSet;
import org.impulsegraph.api.bitset.OffHeapBitSet;
import org.impulsegraph.compiler.ast.ImpScmNode;
import org.impulsegraph.compiler.ast.ScmSymbol;
import org.impulsegraph.compiler.passes.CompilerContext;
import org.impulsegraph.compiler.passes.CompilerPass;
import org.impulsegraph.compiler.trace.CompilerOptions;
import org.impulsegraph.compiler.trace.PassTraceListener;
import org.impulsegraph.compiler.trace.PassTracer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.data.Offset.offset;
import static org.impulsegraph.vm.VmRegisterType.*;
import static org.impulsegraph.vm.VmStateLayout.*;

@DisplayName("ShaderOps, Compiler Tracing, Validator, JitDriver, and VM Context Tests")
public class ShaderAndTracerTest {

	// =========================================================================
	// 1. ShaderAbortException Tests
	// =========================================================================

	@Test
	@DisplayName("ShaderAbortException singleton behavior and stack trace suppression")
	public void testShaderAbortException() {
		ShaderAbortException ex = ShaderAbortException.INSTANCE;
		assertThat(ex).isNotNull();
		assertThat(ex.fillInStackTrace()).isSameAs(ex);

		assertThatThrownBy(() -> {
			throw ShaderAbortException.INSTANCE;
		}).isInstanceOf(ShaderAbortException.class);
	}

	// =========================================================================
	// 2. ShaderOps Tests
	// =========================================================================

	@Test
	@DisplayName("ShaderOps load methods populate floating point registers from targets, constants, IDs, and attributes")
	public void testShaderOpsLoadMethods() {
		try (Arena arena = Arena.ofShared()) {
			ImpulseGraphSnapshot snap = new MockImpulseGraphSnapshot(Map.of());
			VmQueryContext ctx = new VmQueryContext(snap, arena);
			MemorySegment state = ctx.allocateStateSegment();
			ImpulseBitSet bs = new OffHeapBitSet(arena, 10);
			float[] regs = new float[16];

			// loadTgtId
			ShaderOps.loadTgtId(1, ctx, state, 10, 20, 5, regs, bs);
			assertThat(regs[1]).isEqualTo(20.0f);

			// loadConst
			int bits = Float.floatToIntBits(42.5f);
			ShaderOps.loadConst(2, bits, ctx, state, 0, 0, 0, regs, bs);
			assertThat(regs[2]).isEqualTo(42.5f);

			// loadSrcId & loadEdgeId
			ShaderOps.loadSrcId(3, ctx, state, 100, 200, 300, regs, bs);
			assertThat(regs[3]).isEqualTo(100.0f);

			ShaderOps.loadEdgeId(4, ctx, state, 100, 200, 300, regs, bs);
			assertThat(regs[4]).isEqualTo(300.0f);

			// loadSrc with float[] mock attribute
			ctx.setMockAttribute(0, new float[]{1.1f, 2.2f});
			ShaderOps.loadSrc(5, 0, ctx, state, 1, 0, 0, regs, bs);
			assertThat(regs[5]).isEqualTo(2.2f);
			ShaderOps.loadSrc(5, 0, ctx, state, 99, 0, 0, regs, bs); // out of bounds
			assertThat(regs[5]).isEqualTo(0.0f);

			// loadSrc with int[] mock attribute
			ctx.setMockAttribute(1, new int[]{Float.floatToIntBits(3.3f)});
			ShaderOps.loadSrc(6, 1, ctx, state, 0, 0, 0, regs, bs);
			assertThat(regs[6]).isEqualTo(3.3f);

			// loadSrc fallback to register float vector
			int fvHandle = ctx.registerFloatVector(new float[]{4.4f, 5.5f});
			VmHandlers.setRegister(state, 2, fvHandle, TYPE_FLOAT_VECTOR);
			ShaderOps.loadSrc(7, 2, ctx, state, 1, 0, 0, regs, bs);
			assertThat(regs[7]).isEqualTo(5.5f);

			// loadTgt with float[], int[], and register fallback
			ctx.setMockAttribute(3, new float[]{7.7f, 8.8f});
			ShaderOps.loadTgt(8, 3, ctx, state, 0, 1, 0, regs, bs);
			assertThat(regs[8]).isEqualTo(8.8f);

			ctx.setMockAttribute(4, new int[]{Float.floatToIntBits(9.9f)});
			ShaderOps.loadTgt(9, 4, ctx, state, 0, 0, 0, regs, bs);
			assertThat(regs[9]).isEqualTo(9.9f);

			ShaderOps.loadTgt(10, 2, ctx, state, 0, 0, 0, regs, bs);
			assertThat(regs[10]).isEqualTo(4.4f);

			// loadEdge with float[], int[], and register fallback
			ctx.setMockAttribute(5, new float[]{11.1f, 22.2f});
			ShaderOps.loadEdge(11, 5, ctx, state, 0, 0, 1, regs, bs);
			assertThat(regs[11]).isEqualTo(22.2f);
			ShaderOps.loadEdge(11, 5, ctx, state, 0, 0, -1, regs, bs); // negative edgeId
			assertThat(regs[11]).isEqualTo(0.0f);

			ctx.setMockAttribute(6, new int[]{Float.floatToIntBits(33.3f)});
			ShaderOps.loadEdge(12, 6, ctx, state, 0, 0, 0, regs, bs);
			assertThat(regs[12]).isEqualTo(33.3f);

			ShaderOps.loadEdge(13, 2, ctx, state, 0, 0, 1, regs, bs);
			assertThat(regs[13]).isEqualTo(5.5f);
		}
	}

	@Test
	@DisplayName("ShaderOps arithmetic, logic, and comparison operations")
	public void testShaderOpsArithmeticAndLogic() {
		float[] regs = new float[16];
		regs[1] = 10.0f;
		regs[2] = 3.0f;
		regs[3] = 0.0f;

		// mathAdd
		ShaderOps.mathAdd(4, 1, 2, null, null, 0, 0, 0, regs, null);
		assertThat(regs[4]).isEqualTo(13.0f);

		// mathSub
		ShaderOps.mathSub(5, 1, 2, null, null, 0, 0, 0, regs, null);
		assertThat(regs[5]).isEqualTo(7.0f);

		// mathMul
		ShaderOps.mathMul(6, 1, 2, null, null, 0, 0, 0, regs, null);
		assertThat(regs[6]).isEqualTo(30.0f);

		// mathDiv
		ShaderOps.mathDiv(7, 1, 2, null, null, 0, 0, 0, regs, null);
		assertThat(regs[7]).isCloseTo(3.3333f, offset(0.001f));
		ShaderOps.mathDiv(7, 1, 3, null, null, 0, 0, 0, regs, null); // div by zero
		assertThat(regs[7]).isEqualTo(0.0f);

		// mathMod
		ShaderOps.mathMod(8, 1, 2, null, null, 0, 0, 0, regs, null);
		assertThat(regs[8]).isEqualTo(1.0f);
		ShaderOps.mathMod(8, 1, 3, null, null, 0, 0, 0, regs, null); // mod by zero
		assertThat(regs[8]).isEqualTo(0.0f);

		// cmpEq & cmpNeq
		ShaderOps.cmpEq(9, 1, 1, null, null, 0, 0, 0, regs, null);
		assertThat(regs[9]).isEqualTo(1.0f);
		ShaderOps.cmpEq(9, 1, 2, null, null, 0, 0, 0, regs, null);
		assertThat(regs[9]).isEqualTo(0.0f);

		ShaderOps.cmpNeq(10, 1, 2, null, null, 0, 0, 0, regs, null);
		assertThat(regs[10]).isEqualTo(1.0f);
		ShaderOps.cmpNeq(10, 1, 1, null, null, 0, 0, 0, regs, null);
		assertThat(regs[10]).isEqualTo(0.0f);

		// cmpGt & cmpLt
		ShaderOps.cmpGt(11, 1, 2, null, null, 0, 0, 0, regs, null);
		assertThat(regs[11]).isEqualTo(1.0f);
		ShaderOps.cmpGt(11, 2, 1, null, null, 0, 0, 0, regs, null);
		assertThat(regs[11]).isEqualTo(0.0f);

		ShaderOps.cmpLt(12, 2, 1, null, null, 0, 0, 0, regs, null);
		assertThat(regs[12]).isEqualTo(1.0f);
		ShaderOps.cmpLt(12, 1, 2, null, null, 0, 0, 0, regs, null);
		assertThat(regs[12]).isEqualTo(0.0f);

		// logicAnd, logicOr, logicNot
		ShaderOps.logicAnd(13, 1, 2, null, null, 0, 0, 0, regs, null);
		assertThat(regs[13]).isEqualTo(1.0f);
		ShaderOps.logicAnd(13, 1, 3, null, null, 0, 0, 0, regs, null);
		assertThat(regs[13]).isEqualTo(0.0f);

		ShaderOps.logicOr(14, 1, 3, null, null, 0, 0, 0, regs, null);
		assertThat(regs[14]).isEqualTo(1.0f);
		ShaderOps.logicOr(14, 3, 3, null, null, 0, 0, 0, regs, null);
		assertThat(regs[14]).isEqualTo(0.0f);

		ShaderOps.logicNot(15, 3, null, null, 0, 0, 0, regs, null);
		assertThat(regs[15]).isEqualTo(1.0f);
		ShaderOps.logicNot(15, 1, null, null, 0, 0, 0, regs, null);
		assertThat(regs[15]).isEqualTo(0.0f);

		// select
		ShaderOps.select(0, 1, 1, 2, null, null, 0, 0, 0, regs, null); // cond != 0 -> trueVal
		assertThat(regs[0]).isEqualTo(regs[1]);
		ShaderOps.select(0, 3, 1, 2, null, null, 0, 0, 0, regs, null); // cond == 0 -> falseVal
		assertThat(regs[0]).isEqualTo(regs[2]);
	}

	@Test
	@DisplayName("ShaderOps filter and reduce operations")
	public void testShaderOpsFilterAndReduce() {
		try (Arena arena = Arena.ofShared()) {
			ImpulseGraphSnapshot snap = new MockImpulseGraphSnapshot(Map.of());
			VmQueryContext ctx = new VmQueryContext(snap, arena);
			MemorySegment state = ctx.allocateStateSegment();
			float[] regs = new float[8];

			// filter passes on non-zero
			regs[1] = 1.0f;
			ShaderOps.filter(1, ctx, state, 0, 0, 0, regs, null);

			// filter aborts on 0.0f
			regs[2] = 0.0f;
			assertThatThrownBy(() -> ShaderOps.filter(2, ctx, state, 0, 0, 0, regs, null))
					.isSameAs(ShaderAbortException.INSTANCE);

			// reduce with uninitialized register (monoid 0: sum)
			regs[3] = 5.0f;
			ShaderOps.reduce(3, 1, 0, ctx, state, 0, 10, 0, regs, null);
			int handle = (int) VmHandlers.getRegisterValue(state, 1);
			float[] vec = ctx.getFloatVector(handle);
			assertThat(vec).isNotNull();
			assertThat(vec[10]).isEqualTo(5.0f);

			// reduce on existing register (monoid 0: sum -> 5.0 + 3.0 = 8.0)
			regs[4] = 3.0f;
			ShaderOps.reduce(4, 1, 0, ctx, state, 0, 10, 0, regs, null);
			assertThat(vec[10]).isEqualTo(8.0f);

			// reduce monoid 1: max (max(8.0, 12.0) = 12.0)
			regs[5] = 12.0f;
			ShaderOps.reduce(5, 1, 1, ctx, state, 0, 10, 0, regs, null);
			assertThat(vec[10]).isEqualTo(12.0f);

			// reduce monoid 2: min (min(12.0, 2.0) = 2.0)
			regs[6] = 2.0f;
			ShaderOps.reduce(6, 1, 2, ctx, state, 0, 10, 0, regs, null);
			assertThat(vec[10]).isEqualTo(2.0f);

			// reduce with target out of bounds (should not throw)
			ShaderOps.reduce(6, 1, 0, ctx, state, 0, 100_000, 0, regs, null);
		}
	}

	@Test
	@DisplayName("ShaderOps mathUnary covers all activation and mathematical functions")
	public void testShaderOpsMathUnary() {
		float[] regs = new float[4];

		int[] types = {0x01, 0x02, 0x03, 0x04, 0x08, 0x09, 0x0A, 0x0B, 0x0C, 0x0D, 0x0E, 0x0F, 0x10, 0x11, 0x12, 0x13,
				0x14, 0x15, 0x17, 0x18, 0x19, 0x1A, 0x1E, 0x1F, 0x21, 0x25, 0x26, 0x27, 0x28, 0x29, 0x2A, 0x34, 0x35,
				0x36, 0xFF};

		for (int type : types) {
			regs[1] = (type == 0x0C || type == 0x0D || type == 0x0E || type == 0x02 || type == 0x03) ? 4.0f : 0.5f;
			if (type == 0x34)
				regs[1] = Float.NaN;
			if (type == 0x35)
				regs[1] = Float.POSITIVE_INFINITY;
			ShaderOps.mathUnary(0, 1, type, null, null, 0, 0, 0, regs, null);
			assertThat(Float.isNaN(regs[0]) && type != 0x34).isFalse();
		}

		// Specific validations
		regs[1] = -5.0f;
		ShaderOps.mathUnary(0, 1, 0x01, null, null, 0, 0, 0, regs, null); // abs
		assertThat(regs[0]).isEqualTo(5.0f);

		regs[1] = 16.0f;
		ShaderOps.mathUnary(0, 1, 0x02, null, null, 0, 0, 0, regs, null); // sqrt
		assertThat(regs[0]).isEqualTo(4.0f);

		regs[1] = 4.0f;
		ShaderOps.mathUnary(0, 1, 0x03, null, null, 0, 0, 0, regs, null); // 1/sqrt
		assertThat(regs[0]).isEqualTo(0.5f);

		regs[1] = -8.0f;
		ShaderOps.mathUnary(0, 1, 0x04, null, null, 0, 0, 0, regs, null); // cbrt
		assertThat(regs[0]).isEqualTo(-2.0f);

		regs[1] = 0.0f;
		ShaderOps.mathUnary(0, 1, 0x17, null, null, 0, 0, 0, regs, null); // sinc(0)
		assertThat(regs[0]).isEqualTo(1.0f);

		regs[1] = -2.0f;
		ShaderOps.mathUnary(0, 1, 0x25, null, null, 0, 0, 0, regs, null); // relu
		assertThat(regs[0]).isEqualTo(0.0f);

		ShaderOps.mathUnary(0, 1, 0x26, null, null, 0, 0, 0, regs, null); // leaky relu
		assertThat(regs[0]).isCloseTo(-0.02f, offset(0.001f));

		regs[1] = 0.0f;
		ShaderOps.mathUnary(0, 1, 0x27, null, null, 0, 0, 0, regs, null); // sigmoid
		assertThat(regs[0]).isEqualTo(0.5f);
	}

	// =========================================================================
	// 3. ShaderCompiler Tests
	// =========================================================================

	@Test
	@DisplayName("ShaderCompiler compiles full bytecode shader pipeline and executes with argument folding")
	public void testShaderCompilerPipeline() throws Throwable {
		try (Arena arena = Arena.ofShared()) {
			int count = 25;
			MemorySegment prog = arena.allocate(INSTRUCTION_LAYOUT.byteSize() * count);

			int pc = 0;
			setInstruction(prog, pc++, OP_STREAM_FUNC_BEGIN, (byte) 0, (short) 0, 0);
			setInstruction(prog, pc++, OP_STREAM_LOAD_CONST, (byte) 0, (short) 0, Float.floatToIntBits(5.0f));
			setInstruction(prog, pc++, OP_STREAM_LOAD_SRC_ID, (byte) 0, (short) 1, 0);
			setInstruction(prog, pc++, OP_STREAM_LOAD_TGT_ID, (byte) 0, (short) 2, 0);
			setInstruction(prog, pc++, OP_STREAM_LOAD_EDGE_ID, (byte) 0, (short) 3, 0);
			setInstruction(prog, pc++, OP_STREAM_LOAD_SRC, (byte) 0, (short) 4, 0);
			setInstruction(prog, pc++, OP_STREAM_LOAD_EDGE, (byte) 0, (short) 5, 0);
			setInstruction(prog, pc++, OP_STREAM_LOAD_TGT, (byte) 0, (short) 6, 0);
			setInstruction(prog, pc++, OP_STREAM_MATH_ADD, (byte) 0, (short) 7, (0 << 16) | 1);
			setInstruction(prog, pc++, OP_STREAM_MATH_SUB, (byte) 0, (short) 8, (0 << 16) | 1);
			setInstruction(prog, pc++, OP_STREAM_MATH_MUL, (byte) 0, (short) 9, (0 << 16) | 1);
			setInstruction(prog, pc++, OP_STREAM_MATH_DIV, (byte) 0, (short) 10, (0 << 16) | 1);
			setInstruction(prog, pc++, OP_STREAM_MATH_MOD, (byte) 0, (short) 11, (0 << 16) | 1);
			setInstruction(prog, pc++, OP_STREAM_MATH_UNARY, (byte) 0, (short) 12, (0x01 << 16) | 0);
			setInstruction(prog, pc++, OP_STREAM_CMP_EQ, (byte) 0, (short) 13, (0 << 16) | 1);
			setInstruction(prog, pc++, OP_STREAM_CMP_NEQ, (byte) 0, (short) 14, (0 << 16) | 1);
			setInstruction(prog, pc++, OP_STREAM_CMP_GT, (byte) 0, (short) 15, (0 << 16) | 1);
			setInstruction(prog, pc++, OP_STREAM_CMP_LT, (byte) 0, (short) 16, (0 << 16) | 1);
			setInstruction(prog, pc++, OP_STREAM_LOGIC_AND, (byte) 0, (short) 17, (0 << 16) | 1);
			setInstruction(prog, pc++, OP_STREAM_LOGIC_OR, (byte) 0, (short) 18, (0 << 16) | 1);
			setInstruction(prog, pc++, OP_STREAM_LOGIC_NOT, (byte) 0, (short) 19, 0);
			setInstruction(prog, pc++, OP_STREAM_SELECT, (byte) 0, (short) 20, ((0 << 8 | 1) << 16) | 0);
			setInstruction(prog, pc++, OP_STREAM_REDUCE, (byte) 0, (short) 0, (0 << 16) | 1);
			setInstruction(prog, pc++, OP_STREAM_FILTER, (byte) 0, (short) 0, 0);
			setInstruction(prog, pc, OP_STREAM_FUNC_END, (byte) 0, (short) 0, 0);

			MethodHandle mh = ShaderCompiler.compileShader(prog, count, 0);
			assertThat(mh).isNotNull();

			ImpulseGraphSnapshot snap = new MockImpulseGraphSnapshot(Map.of());
			VmQueryContext ctx = new VmQueryContext(snap, arena);
			MemorySegment state = ctx.allocateStateSegment();
			float[] regs = new float[32];
			ImpulseBitSet bs = new OffHeapBitSet(arena, 10);

			mh.invokeExact(ctx, state, 1, 2, 3, regs, bs);
			assertThat(regs[0]).isEqualTo(5.0f);
			assertThat(regs[1]).isEqualTo(1.0f);
			assertThat(regs[2]).isEqualTo(2.0f);
			assertThat(regs[3]).isEqualTo(3.0f);
		}
	}

	@Test
	@DisplayName("ShaderCompiler catches ShaderAbortException when OP_STREAM_FILTER aborts")
	public void testShaderCompilerFilterAbortCaught() throws Throwable {
		try (Arena arena = Arena.ofShared()) {
			int count = 4;
			MemorySegment prog = arena.allocate(INSTRUCTION_LAYOUT.byteSize() * count);

			setInstruction(prog, 0, OP_STREAM_FUNC_BEGIN, (byte) 0, (short) 0, 0);
			setInstruction(prog, 1, OP_STREAM_LOAD_CONST, (byte) 0, (short) 0, Float.floatToIntBits(0.0f));
			setInstruction(prog, 2, OP_STREAM_FILTER, (byte) 0, (short) 0, 0);
			setInstruction(prog, 3, OP_STREAM_FUNC_END, (byte) 0, (short) 0, 0);

			MethodHandle mh = ShaderCompiler.compileShader(prog, count, 0);
			assertThat(mh).isNotNull();

			ImpulseGraphSnapshot snap = new MockImpulseGraphSnapshot(Map.of());
			VmQueryContext ctx = new VmQueryContext(snap, arena);
			MemorySegment state = ctx.allocateStateSegment();
			float[] regs = new float[8];
			ImpulseBitSet bs = new OffHeapBitSet(arena, 10);

			// Does not throw because ShaderAbortException is caught by the compiler
			// pipeline
			mh.invokeExact(ctx, state, 0, 0, 0, regs, bs);
		}
	}

	// =========================================================================
	// 4. PassTracer and PassTraceListener Tests
	// =========================================================================

	@Test
	@DisplayName("PassTracer records pass entries and generates diagnostic report")
	public void testPassTracerAndReport() {
		AtomicBoolean listenerCalled = new AtomicBoolean(false);
		PassTraceListener listener = (name, before, after, dur) -> listenerCalled.set(true);

		CompilerOptions options = CompilerOptions.builder().withTracing(true).withTraceListener(listener).build();

		PassTracer tracer = new PassTracer(options);
		ImpScmNode ast1 = ScmSymbol.of("start");
		ImpScmNode ast2 = ScmSymbol.of("optimized");

		tracer.record("OptimizationPass", ast1, ast2, 2_500_000L);
		assertThat(listenerCalled.get()).isTrue();
		assertThat(tracer.entries()).hasSize(1);
		assertThat(tracer.entries().get(0).passName()).isEqualTo("OptimizationPass");

		String report = tracer.generateTraceReport();
		assertThat(report).contains("IMPULSE COMPILER MULTI-PASS TRACE REPORT");
		assertThat(report).contains("OptimizationPass");
		assertThat(report).contains("2.500 ms");

		// Test with null options
		PassTracer nullOptsTracer = new PassTracer(null);
		nullOptsTracer.record("NoopPass", null, null, 1_000_000L);
		assertThat(nullOptsTracer.entries()).hasSize(1);
	}

	@Test
	@DisplayName("PassTraceListener NOOP and SYSTEM_OUT singletons")
	public void testPassTraceListenerSingletons() {
		PassTraceListener.NOOP.onPassComplete("test", null, null, 1000L);
		PassTraceListener.SYSTEM_OUT.onPassComplete("test", null, ScmSymbol.of("node"), 1000L);
	}

	// =========================================================================
	// 5. CompilerOptions and CompilerContext Tests
	// =========================================================================

	@Test
	@DisplayName("CompilerOptions builder sets all options and parameter bindings")
	public void testCompilerOptions() {
		CompilerOptions opts = CompilerOptions.builder().withTracing(true).withExperimental2HopFusion(true)
				.withStage1Optimization(false).withStage2Optimization(false).withConstantFolding(false)
				.withDirectionSelection(false).withFilterPushdown(false).withTraceListener(PassTraceListener.NOOP)
				.withParameter("p1", 100).withParameters(Map.of("p2", "val2")).build();

		assertThat(opts.enableTracing()).isTrue();
		assertThat(opts.enableExperimental2HopFusion()).isTrue();
		assertThat(opts.enableStage1Optimization()).isFalse();
		assertThat(opts.enableStage2Optimization()).isFalse();
		assertThat(opts.enableConstantFolding()).isFalse();
		assertThat(opts.enableDirectionSelection()).isFalse();
		assertThat(opts.enableFilterPushdown()).isFalse();
		assertThat(opts.parameters()).containsEntry("p1", 100);
		assertThat(opts.parameters()).containsEntry("p2", "val2");

		// Default and constructor overloads
		assertThat(CompilerOptions.DEFAULT).isNotNull();
		CompilerOptions partial = new CompilerOptions(true, true, true, true, true, true, null);
		assertThat(partial.traceListener()).isNull();

		// Builder with null trace listener
		CompilerOptions nullListener = CompilerOptions.builder().withTraceListener(null).build();
		assertThat(nullListener.traceListener()).isSameAs(PassTraceListener.SYSTEM_OUT);
	}

	@Test
	@DisplayName("CompilerContext executes passes, tracks duration in tracer, and exposes context accessors")
	public void testCompilerContext() {
		try (Arena arena = Arena.ofShared()) {
			ImpulseGraphSnapshot snap = new MockImpulseGraphSnapshot(Map.of());
			CompilerOptions options = CompilerOptions.builder().withParameter("batchSize", 50).build();
			PassTracer tracer = new PassTracer(options);

			CompilerContext ctx = new CompilerContext(snap, options, tracer);
			assertThat(ctx.snapshot()).isSameAs(snap);
			assertThat(ctx.options()).isSameAs(options);
			assertThat(ctx.tracer()).isSameAs(tracer);
			assertThat(ctx.parameters()).containsEntry("batchSize", 50);

			CompilerPass dummyPass = new CompilerPass() {
				@Override
				public String name() {
					return "MockPass";
				}

				@Override
				public ImpScmNode transform(ImpScmNode ast, CompilerContext context) {
					return ScmSymbol.of("transformed");
				}
			};

			ImpScmNode out = ctx.executePass(dummyPass, ScmSymbol.of("input"));
			assertThat(out.toScmString()).isEqualTo("transformed");
			assertThat(tracer.entries()).hasSize(1);
			assertThat(tracer.entries().get(0).passName()).isEqualTo("MockPass");

			// Null options & tracer fallbacks
			CompilerContext fallbackCtx = new CompilerContext(snap, null, null);
			assertThat(fallbackCtx.options()).isNotNull();
			assertThat(fallbackCtx.tracer()).isNotNull();
		}
	}

	// =========================================================================
	// 6. ImpulseVmValidator Tests
	// =========================================================================

	@Test
	@DisplayName("ImpulseVmValidator validates programs and flags invalid destination and source registers")
	public void testImpulseVmValidator() {
		assertThat(ImpulseVmValidator.validate(null)).isEqualTo("IMPULSE_VM_OK");
		assertThat(ImpulseVmValidator.validate(new VmHandlers.Instruction[0])).isEqualTo("IMPULSE_VM_OK");

		// Valid instructions with various abstract type opcodes
		VmHandlers.Instruction[] validProgram = {new VmHandlers.Instruction((byte) 0x01, (byte) 0, 0, 0), // TYPE_NODE_ID
				new VmHandlers.Instruction((byte) 0x02, (byte) 0, 1, 0), // TYPE_BITSET_HANDLE
				new VmHandlers.Instruction((byte) 0x03, (byte) 0, 2, 0), // TYPE_INT64
				new VmHandlers.Instruction((byte) 0x05, (byte) 0, 3, 0), // TYPE_FLOAT
				new VmHandlers.Instruction((byte) 0x07, (byte) 0, 4, 0), // TYPE_FLOAT_VECTOR
				new VmHandlers.Instruction((byte) 0x70, (byte) 0, 5, 0), // OP_MOV (src=0)
				new VmHandlers.Instruction((byte) 0x71, (byte) 0, 6, 0), // TYPE_NULL
				new VmHandlers.Instruction((byte) 0x00, (byte) 0, 0, 0) // default (OP_HALT)
		};
		assertThat(ImpulseVmValidator.validate(validProgram)).isEqualTo("IMPULSE_VM_OK");

		// Invalid destination register (>= 64)
		VmHandlers.Instruction[] invalidDst = {new VmHandlers.Instruction((byte) 0x01, (byte) 0, 64, 0)};
		assertThat(ImpulseVmValidator.validate(invalidDst)).isEqualTo("IMPULSE_VM_ERR_INVALID_REGISTER");

		// OP_MOV with invalid source register (>= 64)
		VmHandlers.Instruction[] invalidSrcMov = {new VmHandlers.Instruction((byte) 0x70, (byte) 0, 0, 64)};
		assertThat(ImpulseVmValidator.validate(invalidSrcMov)).isEqualTo("IMPULSE_VM_ERR_INVALID_REGISTER");
	}

	// =========================================================================
	// 7. JitDriver Tests
	// =========================================================================

	public static int handlerAdvance(VmQueryContext ctx, MemorySegment state, Object input, int currentPc) {
		return currentPc + 1;
	}

	public static int handlerHalt(VmQueryContext ctx, MemorySegment state, Object input, int currentPc) {
		ctx.setFinalResult("EXECUTION_COMPLETED");
		return 3;
	}

	public static int handlerThrowRuntime(VmQueryContext ctx, MemorySegment state, Object input, int currentPc) {
		throw new IllegalStateException("Test runtime exception");
	}

	public static int handlerThrowChecked(VmQueryContext ctx, MemorySegment state, Object input, int currentPc)
			throws Exception {
		throw new Exception("Test checked exception");
	}

	@Test
	@DisplayName("JitDriver executes handler sequence and handles exceptions")
	public void testJitDriver() throws Throwable {
		MethodHandles.Lookup lookup = MethodHandles.lookup();
		MethodType mtype = MethodType.methodType(int.class, VmQueryContext.class, MemorySegment.class, Object.class,
				int.class);

		MethodHandle hAdvance = lookup.findStatic(ShaderAndTracerTest.class, "handlerAdvance", mtype);
		MethodHandle hHalt = lookup.findStatic(ShaderAndTracerTest.class, "handlerHalt", mtype);
		MethodHandle hRuntime = lookup.findStatic(ShaderAndTracerTest.class, "handlerThrowRuntime", mtype);
		MethodHandle hChecked = lookup.findStatic(ShaderAndTracerTest.class, "handlerThrowChecked", mtype);

		try (Arena arena = Arena.ofShared()) {
			ImpulseGraphSnapshot snap = new MockImpulseGraphSnapshot(Map.of());
			VmQueryContext ctx = new VmQueryContext(snap, arena);
			MemorySegment state = ctx.allocateStateSegment();

			// Test successful execution to maxPc
			MethodHandle[] normalHandlers = new MethodHandle[]{hAdvance, hAdvance, hHalt};
			JitDriver driver = new JitDriver(normalHandlers);
			Object result = driver.execute(ctx, state, null, 3);
			assertThat(result).isEqualTo("EXECUTION_COMPLETED");
			assertThat((int) PC_HANDLE.get(state, 0L)).isEqualTo(2);

			// Test RuntimeException propagation
			MethodHandle[] errHandlers1 = new MethodHandle[]{hAdvance, hRuntime, hHalt};
			JitDriver driverErr1 = new JitDriver(errHandlers1);
			assertThatThrownBy(() -> driverErr1.execute(ctx, state, null, 3)).isInstanceOf(IllegalStateException.class)
					.hasMessageContaining("Test runtime exception");

			// Test checked Exception wrapping in RuntimeException
			MethodHandle[] errHandlers2 = new MethodHandle[]{hAdvance, hChecked, hHalt};
			JitDriver driverErr2 = new JitDriver(errHandlers2);
			assertThatThrownBy(() -> driverErr2.execute(ctx, state, null, 3)).isInstanceOf(RuntimeException.class)
					.hasMessageContaining("Test checked exception");
		}
	}

	// =========================================================================
	// 8. VmQueryContext Tests
	// =========================================================================

	@Test
	@DisplayName("VmQueryContext comprehensive tests across bitsets, vectors, value maps, scratch, and DOP")
	public void testVmQueryContext() {
		try (Arena arena = Arena.ofShared()) {
			ImpulseGraphSnapshot snap = new MockImpulseGraphSnapshot(Map.of());
			VmQueryContext ctx = new VmQueryContext(snap, arena);

			// Snapshot & Arena
			assertThat(ctx.snapshot()).isSameAs(snap);
			assertThat(ctx.getSnapshot()).isSameAs(snap);
			ctx.setSnapshot(null);
			assertThat(ctx.snapshot()).isNull();
			ctx.setSnapshot(snap);
			assertThat(ctx.arena()).isSameAs(arena);

			// Final result
			ctx.setFinalResult("Result");
			assertThat(ctx.getFinalResult()).isEqualTo("Result");

			// Inline data
			MemorySegment inline = arena.allocate(16);
			ctx.setInlineData(inline, 16);
			assertThat(ctx.inlineDataSegment()).isSameAs(inline);
			assertThat(ctx.inlineDataBytes()).isEqualTo(16L);

			// String pool
			ctx.setStringPool(List.of("alpha", "beta"));
			assertThat(ctx.getString(0)).isEqualTo("alpha");
			assertThat(ctx.getString(1)).isEqualTo("beta");
			assertThat(ctx.getString(-1)).isNull();
			assertThat(ctx.getString(5)).isNull();
			ctx.setStringPool(null);
			assertThat(ctx.getString(0)).isNull();

			// Bitsets
			int b1 = ctx.acquireBitset();
			int b2 = ctx.acquireBitset();
			assertThat(ctx.getBitset(b1)).isNotNull();
			ctx.releaseBitset(b1);
			int b3 = ctx.acquireBitset();
			assertThat(b3).isEqualTo(b1); // Reused free handle!
			assertThat(ctx.getBitset(-1)).isNull();
			assertThat(ctx.getBitset(999)).isNull();

			// Vectors: Int, Float, Double, Long, String
			int iv = ctx.registerIntVector(new int[]{1, 2});
			assertThat(ctx.getIntVector(iv)).containsExactly(1, 2);
			assertThat(ctx.acquireNodeVector(new int[]{3})).isGreaterThanOrEqualTo(0);
			assertThat(ctx.getNodeVector(iv)).containsExactly(1, 2);
			assertThat(ctx.getIntVector(-1)).isNull();

			int fv = ctx.registerFloatVector(new float[]{1.0f});
			ctx.setFloatVector(fv, new float[]{2.0f, 3.0f});
			assertThat(ctx.getFloatVector(fv)).containsExactly(2.0f, 3.0f);
			assertThat(ctx.acquireFloatVector(5)).isGreaterThanOrEqualTo(0);
			assertThat(ctx.getFloatVector(-1)).isNull();

			int dv = ctx.registerDoubleVector(new double[]{1.0});
			ctx.setDoubleVector(dv, new double[]{2.0});
			assertThat(ctx.getDoubleVector(dv)).containsExactly(2.0);
			assertThat(ctx.getDoubleVector(-1)).isNull();

			int lv = ctx.registerLongVector(new long[]{10L});
			assertThat(ctx.getLongVector(lv)).containsExactly(10L);
			assertThat(ctx.getLongVector(-1)).isNull();

			int sv = ctx.registerStringVector(new String[]{"str"});
			assertThat(ctx.getStringVector(sv)).containsExactly("str");
			assertThat(ctx.getStringVector(-1)).isNull();

			// Value map
			int vm = ctx.registerValueMap(Map.of(1, "one"));
			assertThat(ctx.getValueMap(vm)).containsEntry(1, "one");
			assertThat(ctx.getValueMap(-1)).isNull();

			// Scratch memory
			long allocated = ctx.allocateScratch(100);
			assertThat(allocated).isEqualTo(VmQueryContext.DEFAULT_SCRATCH_BYTES + 128); // aligned to 64 bytes
			assertThat(ctx.getAllocatedScratchBytes()).isEqualTo(allocated);
			ctx.setMaxScratchCapacityBytes(1024);
			assertThat(ctx.getMaxScratchCapacityBytes()).isEqualTo(1024L);

			// Parallelism / DOP
			ctx.setMaxThreads(4);
			assertThat(ctx.getMaxThreads()).isEqualTo(4);
			ctx.setMaxDop(8);
			assertThat(ctx.getMaxDop()).isEqualTo(8);

			// Mock attributes
			ctx.setMockAttribute(10, "mockVal");
			assertThat(ctx.getMockAttribute(10)).isEqualTo("mockVal");

			// State segment
			MemorySegment state = ctx.allocateStateSegment();
			assertThat(state.byteSize()).isEqualTo(VM_STATE_LAYOUT.byteSize());

			// Close
			ctx.close();
		}
	}

	// =========================================================================
	// 9. VmRegisterType Constants Verification
	// =========================================================================

	@Test
	@DisplayName("VmRegisterType constants align with C-ABI specification")
	public void testVmRegisterTypeConstants() {
		assertThat(IMPULSE_VM_MAGIC).isEqualTo(0x494D5042);

		assertThat(FLAG_ZF).isEqualTo(1L << 0);
		assertThat(FLAG_LT).isEqualTo(1L << 1);
		assertThat(FLAG_GT).isEqualTo(1L << 2);
		assertThat(FLAG_EQ).isEqualTo(1L << 3);
		assertThat(FLAG_ST).isEqualTo(1L << 4);

		assertThat(TYPE_NULL).isEqualTo((byte) 0x00);
		assertThat(TYPE_INT64).isEqualTo((byte) 0x01);
		assertThat(TYPE_NODE_ID).isEqualTo((byte) 0x02);
		assertThat(TYPE_RELATION_ID).isEqualTo((byte) 0x03);
		assertThat(TYPE_BITSET_HANDLE).isEqualTo((byte) 0x04);
		assertThat(TYPE_NODE_VECTOR).isEqualTo((byte) 0x05);
		assertThat(TYPE_CSR_SPAN).isEqualTo((byte) 0x06);
		assertThat(TYPE_BOOLEAN).isEqualTo((byte) 0x07);
		assertThat(TYPE_FLOAT).isEqualTo((byte) 0x08);
		assertThat(TYPE_DOUBLE).isEqualTo((byte) 0x09);

		assertThat(OP_HALT).isEqualTo((byte) 0x00);
		assertThat(OP_NOP).isEqualTo((byte) 0x01);
		assertThat(OP_CSR_WALK).isEqualTo((byte) 0x10);
		assertThat(OP_CSC_WALK).isEqualTo((byte) 0x18);
		assertThat(OP_SET_UNION).isEqualTo((byte) 0x30);
		assertThat(OP_MOV).isEqualTo((byte) 0x70);
		assertThat(OP_CLEAR_REG).isEqualTo((byte) 0x71);

		assertThat(VM_OK).isEqualTo(0);
		assertThat(VM_ERR_INVALID_OPCODE).isEqualTo(1);
		assertThat(VM_ERR_INVALID_REGISTER).isEqualTo(6);
	}

	// =========================================================================
	// 10. Jqwik Property-Based Tests
	// =========================================================================

	@Property
	@DisplayName("Property-based test: ShaderOps mathUnary abs on arbitrary floats")
	void propertyShaderUnaryAbs(@ForAll @FloatRange(min = -1000.0f, max = 1000.0f) float val) {
		float[] regs = new float[2];
		regs[1] = val;
		ShaderOps.mathUnary(0, 1, 0x01, null, null, 0, 0, 0, regs, null);
		assertThat(regs[0]).isEqualTo(Math.abs(val));
	}

	private static void setInstruction(MemorySegment prog, long pc, byte opcode, byte flags, short dstReg,
			int payload) {
		long off = pc * INSTRUCTION_SIZE_BYTES;
		INSTR_OPCODE_HANDLE.set(prog, off, opcode);
		INSTR_FLAGS_HANDLE.set(prog, off, flags);
		INSTR_DST_REG_HANDLE.set(prog, off, dstReg);
		INSTR_PAYLOAD_HANDLE.set(prog, off, payload);
	}
}
