package org.impulsegraph.vm.v11;

import org.impulsegraph.api.ImpulseGraphSnapshot;
import org.impulsegraph.api.bitset.ImpulseBitSet;
import org.impulsegraph.api.bitset.OffHeapBitSet;
import org.impulsegraph.compiler.ast.ImpScmNode;
import org.impulsegraph.compiler.ast.ScmCollect;
import org.impulsegraph.compiler.ast.ScmProgram;
import org.impulsegraph.compiler.emitter.ImpOpsV11BytecodeEmitter;
import org.impulsegraph.vm.VmLaneFrontier;

import java.lang.foreign.Arena;
import java.lang.ref.WeakReference;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Immutable v0.11 compiled execution plan bridging
 * {@link ImpOpsV11BytecodeEmitter} to the 4-stage lifecycle
 * ({@link V11SnapshotAdapter}, {@link V11MemoryPlanner}, {@link V11Workspace},
 * {@link V11Interpreter}) with lock-free blue/green snapshot re-binding.
 */
public final class V11CompiledQuery {

	private static final Map<ImpulseGraphSnapshot, WeakReference<V11SnapshotView>> VIEW_CACHE = Collections
			.synchronizedMap(new WeakHashMap<>());

	private record CachedView(WeakReference<ImpulseGraphSnapshot> snapRef, V11SnapshotView view) {
	}

	private static volatile CachedView lastView;

	public static V11SnapshotView getOrAdaptView(ImpulseGraphSnapshot snapshot) {
		if (snapshot == null) {
			return V11SnapshotAdapter.from(null);
		}
		CachedView cached = lastView;
		if (cached != null && cached.snapRef().get() == snapshot) {
			return cached.view();
		}
		synchronized (VIEW_CACHE) {
			WeakReference<V11SnapshotView> ref = VIEW_CACHE.get(snapshot);
			V11SnapshotView existing = (ref != null) ? ref.get() : null;
			if (existing != null) {
				lastView = new CachedView(new WeakReference<>(snapshot), existing);
				return existing;
			}
		}
		V11SnapshotView adapted = V11SnapshotAdapter.from(snapshot);
		synchronized (VIEW_CACHE) {
			VIEW_CACHE.put(snapshot, new WeakReference<>(adapted));
		}
		lastView = new CachedView(new WeakReference<>(snapshot), adapted);
		return adapted;
	}

	private record BindingState(ImpulseGraphSnapshot snapshot, V11SnapshotView view,
			ImpOpsV11BytecodeEmitter.V11CompiledProgram compiled, int[] domainCards, int maxDomCard,
			V11MemoryPlanner.MemoryPlan memoryPlan) {
	}

	private final ImpScmNode ast;
	private final int startDomainId;
	private final ScmCollect.Format collectFormat;
	private final AtomicReference<BindingState> bindingState;
	private final ThreadLocal<V11Workspace> threadWorkspace = new ThreadLocal<>();

	public V11CompiledQuery(ImpScmNode ast, ImpulseGraphSnapshot initialSnapshot) {
		this(ast, initialSnapshot, 0);
	}

	public V11CompiledQuery(ImpScmNode ast, ImpulseGraphSnapshot initialSnapshot, int startDomainId) {
		this.ast = Objects.requireNonNull(ast, "ast must not be null");
		this.startDomainId = startDomainId;
		this.collectFormat = extractCollectFormat(ast);
		this.bindingState = new AtomicReference<>(bindToSnapshot(initialSnapshot));
	}

	private static ScmCollect.Format extractCollectFormat(ImpScmNode ast) {
		if (ast instanceof ScmProgram prog && !prog.steps().isEmpty()) {
			ImpScmNode last = prog.steps().get(prog.steps().size() - 1);
			if (last instanceof ScmCollect collect) {
				return collect.format();
			}
		}
		return null;
	}

	private BindingState bindToSnapshot(ImpulseGraphSnapshot snapshot) {
		V11SnapshotView view = getOrAdaptView(snapshot);
		ImpOpsV11BytecodeEmitter.V11CompiledProgram compiled = ImpOpsV11BytecodeEmitter.emit(ast, snapshot,
				startDomainId);
		int[] domCards = new int[view.domainCards.length];
		int maxCard = 64;
		for (int i = 0; i < domCards.length; i++) {
			domCards[i] = (int) Math.min(view.domainCards[i], Integer.MAX_VALUE);
			if (domCards[i] > maxCard) {
				maxCard = domCards[i];
			}
		}
		V11MemoryPlanner.MemoryPlan plan = V11MemoryPlanner.plan(compiled.codeWords(), view);
		return new BindingState(snapshot, view, compiled, domCards, maxCard, plan);
	}

	public synchronized void rebind(ImpulseGraphSnapshot newSnapshot) {
		Objects.requireNonNull(newSnapshot, "newSnapshot must not be null");
		BindingState cur = bindingState.get();
		if (cur.snapshot() == newSnapshot) {
			return;
		}
		bindingState.set(bindToSnapshot(newSnapshot));
	}

	public ImpulseGraphSnapshot currentSnapshot() {
		return bindingState.get().snapshot();
	}

	public long instructionCount() {
		return bindingState.get().compiled().codeWords().length;
	}

	public V11MemoryPlanner.MemoryPlan memoryPlan() {
		return bindingState.get().memoryPlan();
	}

	public Object execute(ImpulseGraphSnapshot snapshot, Object input, Arena executionArena) {
		BindingState state = bindingState.get();
		if (snapshot != null && snapshot != state.snapshot()) {
			rebind(snapshot);
			state = bindingState.get();
		}

		ImpulseGraphSnapshot targetSnapshot = state.snapshot();
		if (targetSnapshot != null) {
			targetSnapshot.enterQuery();
		}
		try {
			V11Workspace ws = threadWorkspace.get();
			if (ws == null || ws.maxDomainCard < state.maxDomCard()) {
				ws = new V11Workspace(state.maxDomCard());
				threadWorkspace.set(ws);
			}
			ws.reset();
			state.compiled().container().loadIntoWorkspace(ws);

			int seedDom = state.compiled().startDomainId();
			long[] seedIds = extractSeedIds(input);
			ws.params.put(0, new V11Workspace.ParamFrontier(seedDom, seedIds));

			V11Interpreter.SnapshotBinding binding = new V11Interpreter.SnapshotBinding(state.domainCards(),
					state.view());
			int status = V11Interpreter.executePreValidated(state.compiled().codeWords(), binding, ws, Long.MAX_VALUE);
			if (status != V11IsaTables.IMPULSE_VM_OK) {
				throw new IllegalStateException(
						"ImpulseVM v0.11 execution failed: status=" + status + " at pc=" + ws.pc);
			}

			return materializeOutput(ws, state, executionArena);
		} finally {
			if (targetSnapshot != null) {
				targetSnapshot.exitQuery();
			}
		}
	}

	private static long[] extractSeedIds(Object input) {
		if (input == null) {
			return new long[0];
		}
		if (input instanceof Number n) {
			return new long[]{n.longValue()};
		}
		if (input instanceof long[] la) {
			return la;
		}
		if (input instanceof int[] ia) {
			long[] out = new long[ia.length];
			for (int i = 0; i < ia.length; i++) {
				out[i] = ia[i];
			}
			return out;
		}
		if (input instanceof ImpulseBitSet bs) {
			int card = (int) bs.cardinality();
			long[] out = new long[card];
			int idx = 0;
			for (int u = bs.nextSetBit(0); u >= 0 && idx < card; u = bs.nextSetBit(u + 1)) {
				out[idx++] = u;
			}
			return out;
		}
		return new long[0];
	}

	private Object materializeOutput(V11Workspace ws, BindingState state, Arena executionArena) {
		V11Vector out = ws.getOutput(0);
		Arena arena = (executionArena != null) ? executionArena : Arena.ofAuto();
		if (out == null) {
			// Fallback when program had no explicit OP_COLLECT (e.g. MultiLane collect)
			int fReg = state.compiled().finalFReg();
			if (fReg >= 0 && fReg < 8 && ws.isFInitialized(fReg)) {
				V11Frontier f = ws.fRegs[fReg];
				if (f.multiLane) {
					return frontierToLaneFrontier(f);
				}
				return frontierToBitSet(f, arena);
			}
			for (int f = 0; f < 8; f++) {
				if (ws.isFInitialized(f)) {
					if (ws.fRegs[f].multiLane) {
						return frontierToLaneFrontier(ws.fRegs[f]);
					}
					return frontierToBitSet(ws.fRegs[f], arena);
				}
			}
			return new OffHeapBitSet(arena, Math.max(64, state.maxDomCard()));
		}

		if (collectFormat == ScmCollect.Format.COUNT || collectFormat == ScmCollect.Format.SCALAR
				|| (collectFormat == null && out.length == 1 && (out.elemTag == V11Vector.TAG_F64
						|| out.elemTag == V11Vector.TAG_I64 || out.elemTag == V11Vector.TAG_U64))) {
			if (out.length > 0 && out.isValidAt(0)) {
				if (out.elemTag == V11Vector.TAG_F64) {
					return Double.valueOf(out.f64Data[0]);
				}
				if (out.elemTag == V11Vector.TAG_I64 || out.elemTag == V11Vector.TAG_U64) {
					return Long.valueOf(out.i64Data[0]);
				}
			}
			return (collectFormat == null) ? Double.valueOf(Double.NaN) : Long.valueOf(0L);
		}

		switch (out.elemTag) {
			case V11Vector.TAG_BOOL_3VL -> {
				int n = Math.max(1, out.length);
				OffHeapBitSet bs = new OffHeapBitSet(arena, n);
				int words = (out.length + 63) >>> 6;
				if (out.boolDataBits != null) {
					for (int w = 0; w < words; w++) {
						long word = out.boolDataBits[w];
						while (word != 0L) {
							int bit = Long.numberOfTrailingZeros(word);
							int node = (w << 6) + bit;
							if (node < out.length) {
								bs.set(node);
							}
							word &= (word - 1L);
						}
					}
				}
				return bs;
			}
			case V11Vector.TAG_I64, V11Vector.TAG_U64 -> {
				int[] arr = new int[out.length];
				for (int i = 0; i < out.length; i++) {
					arr[i] = (int) out.i64Data[i];
				}
				return arr;
			}
			case V11Vector.TAG_F64 -> {
				float[] arr = new float[out.length];
				for (int i = 0; i < out.length; i++) {
					arr[i] = (float) out.f64Data[i];
				}
				return arr;
			}
			case V11Vector.TAG_STR -> {
				String[] arr = new String[out.length];
				System.arraycopy(out.strData, 0, arr, 0, out.length);
				return arr;
			}
			default -> {
				return new OffHeapBitSet(arena, Math.max(64, state.maxDomCard()));
			}
		}
	}

	private static VmLaneFrontier frontierToLaneFrontier(V11Frontier lf) {
		int n = Math.max(0, lf.domainCard);
		long[] lanes = new long[n];
		for (int i = 0; i < lf.mlCount; i++) {
			int u = (int) lf.mlNodes[i];
			if (u >= 0 && u < n) {
				lanes[u] = lf.mlMasks[i];
			}
		}
		int ac = lf.activeLaneCount;
		long activeMask = ac >= 64 ? -1L : (ac <= 0 ? 0L : (1L << ac) - 1L);
		return new VmLaneFrontier(VmLaneFrontier.selectLaneWidth(ac), ac, activeMask, n, lanes, null);
	}

	private static OffHeapBitSet frontierToBitSet(V11Frontier f, Arena arena) {
		int cap = Math.max(1, f.domainCard);
		OffHeapBitSet bs = new OffHeapBitSet(arena, cap);
		for (int u : f.toSortedArray()) {
			if (u >= 0 && u < cap) {
				bs.set(u);
			}
		}
		return bs;
	}

	public String disassemble() {
		long[] words = bindingState.get().compiled().codeWords();
		StringBuilder sb = new StringBuilder();
		sb.append("=========================================================================\n");
		sb.append("               IMPULSE VM BYTECODE DISASSEMBLY (v0.11.0)                 \n");
		sb.append("=========================================================================\n");
		sb.append("Instruction Count: ").append(words.length).append('\n');
		sb.append("-------------------------------------------------------------------------\n");
		for (int pc = 0; pc < words.length; pc++) {
			long w = words[pc];
			int op = (int) (w & 0xFFL);
			int sub = (int) ((w >>> 8) & 0xFL);
			int fl = (int) ((w >>> 12) & 0xFL);
			int dst = (int) ((w >>> 16) & 0xFL);
			int s1 = (int) ((w >>> 20) & 0xFL);
			int s2 = (int) ((w >>> 24) & 0xFL);
			int s3 = (int) ((w >>> 28) & 0xFL);
			int imm = (int) (w >>> 32);
			String name = (op >= 0 && op < V11IsaTables.OPCODE_NAME.length && V11IsaTables.OPCODE_NAME[op] != null)
					? V11IsaTables.OPCODE_NAME[op]
					: String.format("OP_0x%02X", op);
			sb.append(String.format("0x%04X: %-24s [sub=%d, fl=0x%X, dst=%d, s1=%d, s2=%d, s3=%d, imm=0x%08X]\n", pc,
					name, sub, fl, dst, s1, s2, s3, imm));
		}
		sb.append("=========================================================================\n");
		return sb.toString();
	}
}
