package org.impulsegraph.compiler.emitter;

import org.impulsegraph.compiler.ast.*;
import static org.impulsegraph.vm.VmRegisterType.*;
import org.impulsegraph.compiler.passes.stage2.RegisterAllocationPass;
import org.impulsegraph.compiler.passes.stage2.RegisterAllocationPass.RegisterAssignment;
import org.impulsegraph.api.ImpulseGraphSnapshot;
import org.impulsegraph.vm.CompiledQuery;
import org.impulsegraph.vm.RelationInstructionPatch;

import java.io.ByteArrayOutputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.impulsegraph.vm.*;
import static org.impulsegraph.vm.VmStateLayout.*;

/**
 * Low-level bytecode emitter compiling bound ImpScheme ASTs into native 8-byte
 * off-heap impOps instructions.
 */
public final class ImpOpsBytecodeEmitter {

	public record InstructionWord(byte opcode, byte flags, short dstReg, int payload) {
	}

	public record EmittedProgram(MemorySegment programSegment, long instructionCount,
			List<RelationInstructionPatch> patches, Map<String, Integer> relationIdMap,
			List<InstructionWord> instructionList, List<String> stringPool, MemorySegment inlineDataSegment) {
		public EmittedProgram(MemorySegment programSegment, long instructionCount,
				List<RelationInstructionPatch> patches, Map<String, Integer> relationIdMap,
				List<InstructionWord> instructionList, List<String> stringPool) {
			this(programSegment, instructionCount, patches, relationIdMap, instructionList, stringPool, null);
		}
	}

	private ImpOpsBytecodeEmitter() {
	}

	public static final byte FLAG_HALT_ON_EMPTY = 0x01;
	public static final byte FLAG_INPUT_SEED = 0x02;
	public static final byte OP_LOAD_INLINE_SET = (byte) 0x07;

	public static EmittedProgram emit(ImpScmNode ast, ImpulseGraphSnapshot snapshot, Arena arena) {
		Objects.requireNonNull(ast, "ast must not be null");
		Objects.requireNonNull(arena, "arena must not be null");

		List<InstructionWord> instrList = new ArrayList<>();
		List<RelationInstructionPatch> patches = new ArrayList<>();
		Map<String, Integer> relationIdMap = new HashMap<>();
		List<String> stringPool = new ArrayList<>();
		ByteArrayOutputStream inlineBytes = new ByteArrayOutputStream();

		RegisterAssignment regAlloc = RegisterAllocationPass.allocate(ast);

		short currentReg = 0;
		boolean firstStepEmitted = false;

		if (ast instanceof ScmProgram prog) {
			List<ImpScmNode> progSteps = prog.steps();
			for (int stepIdx = 0; stepIdx < progSteps.size(); stepIdx++) {
				ImpScmNode step = progSteps.get(stepIdx);
				boolean allowHaltOnEmpty = canRemainingHaltOnEmpty(progSteps, stepIdx + 1);
				short srcReg = regAlloc.srcRegisters().getOrDefault(step, currentReg);
				short dstReg = regAlloc.dstRegisters().getOrDefault(step, (short) (1 - currentReg));
				currentReg = dstReg;

				if (step instanceof ScmWalk walk) {
					int relId = walk.relationId();
					String logicalRelName = walk.relationName();
					if (relId < 0 && !logicalRelName.isEmpty() && snapshot != null) {
						relId = resolveRelationId(snapshot, logicalRelName);
					}
					if (relId >= 0) {
						relationIdMap.put(logicalRelName, relId);
					}

					org.impulsegraph.api.RelationSnapshot boundRel = resolveRelationSnapshot(snapshot, relId,
							logicalRelName);
					if (walk.direction() == ScmWalk.Direction.REVERSE_CSC && boundRel != null && !boundRel.hasCsc()
							&& walk.shaderSteps().isEmpty()) {
						if (!firstStepEmitted) {
							instrList.add(new InstructionWord(OP_INIT_INPUT_SET, (byte) 0, srcReg, 0));
							firstStepEmitted = true;
						}
						int poolIdx = internString(stringPool, logicalRelName + "\0");
						int payload = ((poolIdx & 0xFFFF) << 16) | (srcReg & 0xFFFF);
						instrList.add(new InstructionWord(OP_CSR_WALK_FILTERED, (byte) 0x60, dstReg, payload));
						continue;
					}
					boolean useCsc = walk.direction() == ScmWalk.Direction.REVERSE_CSC
							&& (boundRel == null || boundRel.hasCsc());
					boolean isStream = !walk.shaderSteps().isEmpty();
					byte opcode = (byte) (useCsc
							? (isStream ? OP_CSC_WALK_STREAM : OP_CSC_WALK)
							: (isStream ? OP_CSR_WALK_STREAM : OP_CSR_WALK));

					byte flags = allowHaltOnEmpty ? FLAG_HALT_ON_EMPTY : 0;
					if (!firstStepEmitted) {
						flags |= FLAG_INPUT_SEED;
						firstStepEmitted = true;
					}

					int payload = ((relId & 0xFFFF) << 16) | (srcReg & 0xFFFF);

					if (isStream) {
						boolean hasCelShader = false;
						for (ImpScmNode shaderStep : walk.shaderSteps()) {
							if (shaderStep instanceof ScmCelExpr) {
								hasCelShader = true;
								break;
							}
						}
						if (hasCelShader) {
							if ((flags & FLAG_INPUT_SEED) != 0) {
								instrList.add(new InstructionWord(OP_INIT_INPUT_NODE, (byte) 0, srcReg, 0));
							}
							for (ImpScmNode shaderStep : walk.shaderSteps()) {
								emitPreStreamCelLoads(shaderStep, Math.max(0, relId), instrList, stringPool);
							}
						}
						// Store the index of the WALK_STREAM instruction so we can patch its
						// shaderPcStart flag later
						int walkInstrIdx = instrList.size();
						patches.add(new RelationInstructionPatch(walkInstrIdx, logicalRelName, srcReg, dstReg));
						instrList.add(new InstructionWord(opcode, flags, dstReg, payload));

						// Emit shader block
						instrList.add(new InstructionWord((byte) OP_STREAM_FUNC_BEGIN, (byte) 0, (short) 0, 0));
						int shaderPcStart = instrList.size();
						if (shaderPcStart < 0 || shaderPcStart > 255) {
							throw new IllegalStateException(
									"Shader PC start exceeds 8-bit flags range (0..255): " + shaderPcStart);
						}

						// Patch the WALK_STREAM instruction with the start PC of the shader
						InstructionWord walkInstr = instrList.get(walkInstrIdx);
						instrList.set(walkInstrIdx, new InstructionWord(walkInstr.opcode(), (byte) shaderPcStart,
								walkInstr.dstReg(), walkInstr.payload()));

						StreamRegisterAllocator sRegAlloc = new StreamRegisterAllocator();
						for (ImpScmNode shaderStep : walk.shaderSteps()) {
							emitStreamStep(shaderStep, instrList, sRegAlloc, snapshot);
						}

						// Emit Yield
						instrList.add(new InstructionWord((byte) OP_STREAM_YIELD, (byte) 0, (short) 0, 0));
						instrList.add(new InstructionWord((byte) OP_STREAM_FUNC_END, (byte) 0, (short) 0, 0));

					} else {
						long pc = instrList.size();
						patches.add(new RelationInstructionPatch(pc, logicalRelName, srcReg, dstReg));
						instrList.add(new InstructionWord(opcode, flags, dstReg, payload));
					}
				} else if (step instanceof ScmWalk2Hop hop2) {
					int rel1Id = hop2.relation1Id();
					int rel2Id = hop2.relation2Id();
					if (rel1Id < 0 && !hop2.relation1Name().isEmpty() && snapshot != null) {
						rel1Id = resolveRelationId(snapshot, hop2.relation1Name());
					}
					if (rel2Id < 0 && !hop2.relation2Name().isEmpty() && snapshot != null) {
						rel2Id = resolveRelationId(snapshot, hop2.relation2Name());
					}
					if (rel1Id >= 0)
						relationIdMap.put(hop2.relation1Name(), rel1Id);
					if (rel2Id >= 0)
						relationIdMap.put(hop2.relation2Name(), rel2Id);

					byte flags = allowHaltOnEmpty ? FLAG_HALT_ON_EMPTY : 0;
					if (!firstStepEmitted) {
						flags |= FLAG_INPUT_SEED;
						firstStepEmitted = true;
					}

					int payload = ((rel2Id & 0xFFFF) << 16) | (rel1Id & 0xFFFF);
					instrList.add(new InstructionWord(OP_CSR_WALK_2HOP, flags, dstReg, payload));
				} else if (step instanceof ScmStreamFilter sf) {
					if (!firstStepEmitted) {
						instrList.add(new InstructionWord(OP_INIT_INPUT_SET, (byte) 0, srcReg, 0));
						firstStepEmitted = true;
					}
					String celText = (sf.predicate() instanceof ScmCelExpr ce)
							? ce.rawText()
							: sf.predicate().toScmString();
					int poolIdx = internString(stringPool, "\0" + celText);
					int payload = ((poolIdx & 0xFFFF) << 16) | (srcReg & 0xFFFF);
					instrList.add(new InstructionWord(OP_NODE_FILTER, (byte) 0x40, dstReg, payload));
				} else if (step instanceof ScmVectorFilter) {
					byte flags = 0;
					if (!firstStepEmitted) {
						flags |= FLAG_INPUT_SEED;
						firstStepEmitted = true;
					}
					int payload = ((srcReg & 0xFFFF) << 16) | (srcReg & 0xFFFF);
					instrList.add(new InstructionWord(OP_NODE_FILTER, flags, dstReg, payload));
				} else if (step instanceof ScmReduce red) {
					byte opcode = switch (red.op()) {
						case SUM, COUNT -> OP_VECTOR_REDUCE_SUM;
						default -> OP_REDUCE;
					};
					int opId = switch (red.op()) {
						case SUM, COUNT -> BINARY_OP_ADD;
						case MIN -> BINARY_OP_MIN;
						case MAX -> BINARY_OP_MAX;
						case ARGMIN -> BINARY_OP_ARGMIN;
						case ARGMAX -> BINARY_OP_ARGMAX;
						case FIRST -> 5;
					};
					int payload = (opId << 16) | (srcReg & 0xFFFF);
					instrList.add(new InstructionWord(opcode, (byte) 0, srcReg, payload));
					currentReg = srcReg;
				} else if (step instanceof ScmCollect collect) {
					byte flags = 0;
					if (!firstStepEmitted) {
						flags |= FLAG_INPUT_SEED;
						firstStepEmitted = true;
					}
					byte opcode = switch (collect.format()) {
						case VECTOR, LIST -> OP_COLLECT_ARRAY;
						case KEYS -> OP_MAP_DENSE_TO_KEYS;
						case VALUE_MAP -> OP_COLLECT_VALUE_MAP;
						default -> OP_COLLECT_BITSET;
					};
					instrList.add(new InstructionWord(opcode, flags, srcReg, (srcReg << 16) | srcReg));
					currentReg = srcReg;
				} else if (step instanceof ScmLaneInitSeeds laneSeeds) {
					firstStepEmitted = true;
					short seedReg = (short) laneSeeds.seedReg();
					if (!laneSeeds.seeds().isEmpty()) {
						int inlinePayload = appendInlineIntArray(inlineBytes, laneSeeds.seeds());
						instrList.add(new InstructionWord(OP_LOAD_INLINE_ARRAY, (byte) 0x01, seedReg, inlinePayload));
					}
					int payload = seedReg & 0xFFFF;
					instrList.add(
							new InstructionWord(OP_LANE_INIT_SEEDS, (byte) laneSeeds.widthFlag(), dstReg, payload));
				} else if (step instanceof ScmLaneInitGroups laneGroups) {
					firstStepEmitted = true;
					short pairsReg = (short) laneGroups.pairsReg();
					if (!laneGroups.nodeGroupPairs().isEmpty()) {
						short groupsReg = (short) (pairsReg + 1);
						List<Long> nodesList = new ArrayList<>();
						List<Long> groupsList = new ArrayList<>();
						List<Long> pairs = laneGroups.nodeGroupPairs();
						for (int i = 0; i + 1 < pairs.size(); i += 2) {
							nodesList.add(pairs.get(i));
							groupsList.add(pairs.get(i + 1));
						}
						int nodesPayload = appendInlineIntArray(inlineBytes, nodesList);
						int groupsPayload = appendInlineIntArray(inlineBytes, groupsList);
						instrList.add(new InstructionWord(OP_LOAD_INLINE_ARRAY, (byte) 0x01, pairsReg, nodesPayload));
						instrList.add(new InstructionWord(OP_LOAD_INLINE_ARRAY, (byte) 0x01, groupsReg, groupsPayload));
						int payload = (pairsReg & 0xFF) | ((groupsReg & 0xFF) << 8)
								| ((laneGroups.numGroups() & 0xFFFF) << 16);
						instrList.add(new InstructionWord(OP_LANE_INIT_GROUPS, (byte) 0, dstReg, payload));
					} else {
						int payload = (pairsReg & 0xFFFF) | ((laneGroups.numGroups() & 0xFFFF) << 16);
						instrList.add(new InstructionWord(OP_LANE_INIT_GROUPS, (byte) 0, dstReg, payload));
					}
				} else if (step instanceof ScmLaneSetEdgeMask edgeMask) {
					int relId = edgeMask.relationId();
					if (relId < 0 && !edgeMask.relationName().isEmpty() && snapshot != null) {
						relId = resolveRelationId(snapshot, edgeMask.relationName());
					}
					if (relId < 0) {
						relId = 0;
					}
					if (!edgeMask.relationName().isEmpty()) {
						relationIdMap.put(edgeMask.relationName(), relId);
					}
					short edgeReg = (short) edgeMask.pairsReg();
					if (!edgeMask.edgeLanePairs().isEmpty()) {
						List<Long> pairs = edgeMask.edgeLanePairs();
						for (int i = 0; i + 1 < pairs.size(); i += 2) {
							int edgeIdx = pairs.get(i).intValue();
							int laneIdx = pairs.get(i + 1).intValue();
							instrList.add(new InstructionWord(OP_LOAD_CONST_INT, (byte) 0, edgeReg, edgeIdx));
							int payload = (srcReg & 0xFF) | ((edgeReg & 0xFF) << 8) | ((relId & 0xFFFF) << 16);
							instrList.add(new InstructionWord(OP_LANE_SET_EDGE_MASK, (byte) (laneIdx & 0x3F), srcReg,
									payload));
						}
					} else {
						int payload = (srcReg & 0xFF) | ((edgeReg & 0xFF) << 8) | ((relId & 0xFFFF) << 16);
						instrList.add(new InstructionWord(OP_LANE_SET_EDGE_MASK, (byte) 0, srcReg, payload));
					}
					currentReg = srcReg;
				} else if (step instanceof ScmLaneWalk laneWalk) {
					int relId = laneWalk.relationId();
					if (relId < 0 && !laneWalk.relationName().isEmpty() && snapshot != null) {
						relId = resolveRelationId(snapshot, laneWalk.relationName());
					}
					if (relId < 0) {
						relId = 0;
					}
					if (!laneWalk.relationName().isEmpty()) {
						relationIdMap.put(laneWalk.relationName(), relId);
					}
					long pc = instrList.size();
					patches.add(new RelationInstructionPatch(pc, laneWalk.relationName(), srcReg, dstReg));
					byte opcode = laneWalk.direction() == ScmWalk.Direction.REVERSE_CSC
							? OP_LANE_CSC_WALK
							: OP_LANE_CSR_WALK;
					byte flags = (byte) (laneWalk.fixedPoint() ? 0x04 : 0x00);
					int payload = (srcReg & 0xFFFF) | ((relId & 0xFFFF) << 16);
					instrList.add(new InstructionWord(opcode, flags, dstReg, payload));
				} else if (step instanceof ScmLanePruneMask pruneMask) {
					if (!pruneMask.denyNodes().isEmpty()) {
						short denyReg = 14;
						int setPayload = appendInlineIntArray(inlineBytes, pruneMask.denyNodes());
						instrList.add(new InstructionWord(OP_LOAD_INLINE_SET, (byte) 0, denyReg, setPayload));
						int payload = (srcReg & 0xFF) | ((denyReg & 0xFF) << 8);
						instrList.add(new InstructionWord(OP_LANE_PRUNE_MASK, (byte) 0, srcReg, payload));
					} else {
						int payload = (srcReg & 0xFF) | ((pruneMask.arg0() & 0xFFFF) << 8)
								| ((pruneMask.arg1() & 0xFF) << 24);
						instrList
								.add(new InstructionWord(OP_LANE_PRUNE_MASK, (byte) pruneMask.mode(), srcReg, payload));
					}
					currentReg = srcReg;
				} else if (step instanceof ScmLaneFilterQuorum quorum) {
					byte flags = (byte) (quorum.maskReg() > 0 ? 1 : 0);
					int payload = (quorum.maskReg() > 0)
							? ((srcReg & 0xFF) | ((quorum.minQuorum() & 0xFF) << 8) | ((quorum.maskReg() & 0xFF) << 16))
							: ((srcReg & 0xFFFF) | ((quorum.minQuorum() & 0xFFFF) << 16));
					instrList.add(new InstructionWord(OP_LANE_FILTER_QUORUM, flags, dstReg, payload));
				} else if (step instanceof ScmLaneReduceAnd) {
					int payload = srcReg & 0xFFFF;
					instrList.add(new InstructionWord(OP_LANE_REDUCE_AND, (byte) 0, dstReg, payload));
				} else if (step instanceof ScmLaneReduceAttr reduceAttr) {
					int payload = (srcReg & 0xFF) | ((reduceAttr.attrId() & 0xFF) << 8)
							| ((reduceAttr.reduceOp() & 0xFFFF) << 16);
					instrList.add(new InstructionWord(OP_LANE_REDUCE_ATTR, (byte) 0, dstReg, payload));
				} else if (step instanceof ScmLaneSetCover setCover) {
					int maskReg = setCover.maskReg() & 0xFF;
					if (setCover.targetMask() > 0 && maskReg > 0 && maskReg < 64) {
						instrList.add(new InstructionWord(OP_LOAD_CONST_INT, (byte) 0, (short) maskReg,
								(int) setCover.targetMask()));
					}
					int payload = (srcReg & 0xFF) | (maskReg << 8) | ((setCover.maxNodes() & 0xFFFF) << 16);
					instrList.add(new InstructionWord(OP_LANE_SET_COVER, (byte) setCover.mode(), dstReg, payload));
				} else if (step instanceof ScmVectorAnnSearch ann) {
					firstStepEmitted = true;
					short qReg = (short) ann.queryReg();
					if (ann.queryVector().length > 0) {
						int inlinePayload = appendInlineFloatArray(inlineBytes, ann.queryVector());
						instrList.add(new InstructionWord(OP_LOAD_INLINE_ARRAY, (byte) 0, qReg, inlinePayload));
					}
					byte flags = (byte) (ann.fusedLaneFrontier() ? 1 : 0);
					int payload = (qReg & 0xFF) | ((ann.topK() & 0xFF) << 8) | ((ann.indexOrAttrId() & 0xFFFF) << 16);
					instrList.add(new InstructionWord(OP_VECTOR_ANN_SEARCH, flags, dstReg, payload));
				} else if (step instanceof ScmVectorRerankDot rerank) {
					short qReg = (short) rerank.queryReg();
					if (!rerank.attrName().isEmpty() && !rerank.attrName().startsWith("reg:")) {
						if (!firstStepEmitted) {
							instrList.add(new InstructionWord(OP_INIT_INPUT_SET, (byte) 0, srcReg, 0));
							firstStepEmitted = true;
						}
						if (rerank.queryVector().length > 0) {
							int inlinePayload = appendInlineFloatArray(inlineBytes, rerank.queryVector());
							instrList.add(new InstructionWord(OP_LOAD_INLINE_ARRAY, (byte) 0, qReg, inlinePayload));
						}
						int poolIdx = internString(stringPool, rerank.attrName());
						int payload = (srcReg & 0xFF) | ((qReg & 0xFF) << 8) | ((poolIdx & 0xFFFF) << 16);
						instrList.add(new InstructionWord(OP_VECTOR_RERANK_DOT,
								(byte) (0x40 | (rerank.metric() & 0x03)), dstReg, payload));
					} else {
						if (rerank.queryVector().length > 0) {
							int inlinePayload = appendInlineFloatArray(inlineBytes, rerank.queryVector());
							instrList.add(new InstructionWord(OP_LOAD_INLINE_ARRAY, (byte) 0, qReg, inlinePayload));
						}
						int payload = (srcReg & 0xFF) | ((qReg & 0xFF) << 8) | ((rerank.attrId() & 0xFFFF) << 16);
						instrList.add(
								new InstructionWord(OP_VECTOR_RERANK_DOT, (byte) rerank.metric(), dstReg, payload));
					}
				} else if (step instanceof ScmFixpoint fix) {
					short startReg = srcReg;
					if (!firstStepEmitted) {
						instrList.add(new InstructionWord(OP_INIT_INPUT_NODE, (byte) 0, startReg, 0));
						firstStepEmitted = true;
					}
					emitRepeatUntilStable(fix.body().steps(), snapshot, instrList, patches, relationIdMap, stringPool,
							startReg);
					currentReg = startReg;
				} else if (step instanceof ScmSetOp setOp) {
					short startReg = srcReg;
					if (!firstStepEmitted) {
						instrList.add(new InstructionWord(OP_INIT_INPUT_NODE, (byte) 0, startReg, 0));
						firstStepEmitted = true;
					}
					currentReg = emitSetOp(setOp, snapshot, instrList, patches, relationIdMap, stringPool, startReg,
							dstReg);
				} else if (step instanceof ScmList list && !list.elements().isEmpty()) {
					ImpScmNode head = list.elements().get(0);
					String opName = head instanceof ScmSymbol sym ? sym.name() : "";

					if ("cel-domain-filter".equalsIgnoreCase(opName)) {
						String dom = stringArg(list, 1);
						String cel = stringArg(list, 2);
						if (!firstStepEmitted) {
							instrList.add(new InstructionWord(OP_INIT_INPUT_SET, (byte) 0, srcReg, 0));
							firstStepEmitted = true;
						}
						int poolIdx = internString(stringPool, dom + "\0" + cel);
						int payload = ((poolIdx & 0xFFFF) << 16) | (srcReg & 0xFFFF);
						instrList.add(new InstructionWord(OP_NODE_FILTER, (byte) 0x40, dstReg, payload));
					} else if ("cel-edge-walk".equalsIgnoreCase(opName)) {
						String dir = stringArg(list, 1);
						String relName = stringArg(list, 2);
						String cel = stringArg(list, 3);
						if (!firstStepEmitted) {
							instrList.add(new InstructionWord(OP_INIT_INPUT_SET, (byte) 0, srcReg, 0));
							firstStepEmitted = true;
						}
						boolean isRev = "in".equalsIgnoreCase(dir);
						int poolIdx = internString(stringPool, relName + "\0" + cel);
						int payload = ((poolIdx & 0xFFFF) << 16) | (srcReg & 0xFFFF);
						instrList.add(new InstructionWord(OP_CSR_WALK_FILTERED, (byte) (isRev ? 0x60 : 0x40), dstReg,
								payload));
					} else if ("repeat-exact".equalsIgnoreCase(opName)) {
						int repeatCount = intArg(list, 1, 0);
						short startReg = srcReg;
						if (!firstStepEmitted) {
							instrList.add(new InstructionWord(OP_INIT_INPUT_SET, (byte) 0, startReg, 0));
							firstStepEmitted = true;
						}
						ScmProgram subProg = (list.elements().size() > 2
								&& list.elements().get(2) instanceof ScmProgram sp) ? sp : null;
						if (repeatCount > 0 && subProg != null && !subProg.steps().isEmpty()) {
							short countReg = 2;
							instrList.add(new InstructionWord(OP_LOAD_CONST_INT, (byte) 0, countReg, repeatCount));
							long loopStartPc = instrList.size();
							short subOutReg = emitSubSteps(subProg.steps(), snapshot, instrList, patches, relationIdMap,
									stringPool, startReg);
							if (subOutReg != startReg) {
								instrList.add(new InstructionWord(OP_SWAP_REG, (byte) 0, startReg, (int) subOutReg));
							}
							int loopEndPc = instrList.size();
							int relOffset = (int) (loopStartPc - loopEndPc);
							instrList.add(new InstructionWord(OP_LOOP_DECR, (byte) 0, countReg, relOffset));
						}
						currentReg = startReg;
					} else if ("fixpoint-max-hops".equalsIgnoreCase(opName)) {
						int maxHops = intArg(list, 1, 0);
						short startReg = srcReg;
						if (!firstStepEmitted) {
							instrList.add(new InstructionWord(OP_INIT_INPUT_SET, (byte) 0, startReg, 0));
							firstStepEmitted = true;
						}
						ScmProgram subProg = (list.elements().size() > 2
								&& list.elements().get(2) instanceof ScmProgram sp) ? sp : null;
						if (maxHops > 0 && subProg != null && !subProg.steps().isEmpty()) {
							short accReg = 2;
							short countReg = 3;
							instrList.add(new InstructionWord(OP_MOV, (byte) 0, accReg, (int) startReg));
							instrList.add(new InstructionWord(OP_LOAD_CONST_INT, (byte) 0, countReg, maxHops));
							long loopStartPc = instrList.size();
							short subOutReg = emitSubSteps(subProg.steps(), snapshot, instrList, patches, relationIdMap,
									stringPool, startReg);
							int checkPayload = subOutReg & 0xFFFF;
							instrList.add(new InstructionWord(OP_STABLE_CHECK, (byte) 0, accReg, checkPayload));
							int jzPc = instrList.size();
							instrList.add(new InstructionWord(OP_JZ, (byte) 0, (short) 0, 0));
							int diffPayload = ((accReg & 0xFFFF) << 16) | (subOutReg & 0xFFFF);
							instrList.add(new InstructionWord(OP_SET_DIFFERENCE, (byte) 0, startReg, diffPayload));
							int unionPayload = ((startReg & 0xFFFF) << 16) | (accReg & 0xFFFF);
							instrList.add(new InstructionWord(OP_SET_UNION, (byte) 0, accReg, unionPayload));
							int loopEndPc = instrList.size();
							int relOffset = (int) (loopStartPc - loopEndPc);
							instrList.add(new InstructionWord(OP_LOOP_DECR, (byte) 0, countReg, relOffset));
							int loopExitPc = instrList.size();
							instrList.set(jzPc,
									new InstructionWord(OP_JZ, (byte) 0, (short) 0, (int) (loopExitPc - jzPc)));
							instrList.add(new InstructionWord(OP_MOV, (byte) 0, startReg, (int) accReg));
						}
						currentReg = startReg;
					} else if ("set-op-nodes".equalsIgnoreCase(opName)) {
						String kindStr = stringArg(list, 1);
						String csv = stringArg(list, 2);
						if (!firstStepEmitted) {
							instrList.add(new InstructionWord(OP_INIT_INPUT_SET, (byte) 0, srcReg, 0));
							firstStepEmitted = true;
						}
						short rhsReg = 7;
						if (csv.isEmpty()) {
							instrList.add(new InstructionWord(OP_CLEAR_REG, (byte) 0, rhsReg, 0));
						} else {
							String[] parts = csv.split(",");
							List<Long> rhsNodes = new ArrayList<>(parts.length);
							for (String p : parts) {
								if (!p.isEmpty()) {
									rhsNodes.add(Long.parseLong(p));
								}
							}
							if (rhsNodes.isEmpty()) {
								instrList.add(new InstructionWord(OP_CLEAR_REG, (byte) 0, rhsReg, 0));
							} else if (rhsNodes.size() <= 65535 && inlineBytes.size() + rhsNodes.size() * 4 <= 60000) {
								int inlinePayload = appendInlineIntArray(inlineBytes, rhsNodes);
								instrList.add(
										new InstructionWord(OP_LOAD_INLINE_ARRAY, (byte) 0x01, rhsReg, inlinePayload));
							} else {
								int poolIdx = internString(stringPool, csv);
								instrList.add(new InstructionWord(OP_LOAD_INLINE_ARRAY, (byte) 0x41, rhsReg,
										poolIdx & 0xFFFF));
							}
						}
						byte setOpcode = switch (kindStr.toLowerCase()) {
							case "intersect" -> OP_SET_INTERSECT;
							case "difference", "diff" -> OP_SET_DIFFERENCE;
							default -> OP_SET_UNION;
						};
						int payload = ((rhsReg & 0xFFFF) << 16) | (srcReg & 0xFFFF);
						instrList.add(new InstructionWord(setOpcode, (byte) 0, dstReg, payload));
					} else if ("cel-domain-reduce".equalsIgnoreCase(opName)) {
						String redKindStr = stringArg(list, 1);
						String dom = stringArg(list, 2);
						String expr = stringArg(list, 3);
						if (!firstStepEmitted) {
							instrList.add(new InstructionWord(OP_INIT_INPUT_SET, (byte) 0, srcReg, 0));
							firstStepEmitted = true;
						}
						int redKind = switch (redKindStr.toLowerCase()) {
							case "min" -> BINARY_OP_MIN;
							case "max" -> BINARY_OP_MAX;
							default -> BINARY_OP_ADD;
						};
						int poolIdx = internString(stringPool, dom + "\0" + expr);
						int payload = ((poolIdx & 0xFFFF) << 16) | (srcReg & 0xFFFF);
						instrList
								.add(new InstructionWord(OP_REDUCE, (byte) (0x40 | (redKind & 0x0F)), srcReg, payload));
						currentReg = srcReg;
					} else if ("lane-init-domain-seeds".equalsIgnoreCase(opName)) {
						firstStepEmitted = true;
						String dom = stringArg(list, 1);
						List<Long> seeds = new ArrayList<>();
						for (int i = 2; i < list.elements().size(); i++) {
							seeds.add((long) intArg(list, i, 0));
						}
						short seedReg = 8;
						if (!seeds.isEmpty()) {
							int inlinePayload = appendInlineIntArray(inlineBytes, seeds);
							instrList.add(
									new InstructionWord(OP_LOAD_INLINE_ARRAY, (byte) 0x01, seedReg, inlinePayload));
						} else {
							instrList.add(new InstructionWord(OP_CLEAR_REG, (byte) 0, seedReg, 0));
						}
						int poolIdx = internString(stringPool, dom);
						int payload = (seedReg & 0xFFFF) | ((poolIdx & 0xFFFF) << 16);
						instrList.add(new InstructionWord(OP_LANE_INIT_SEEDS, (byte) 0x40, dstReg, payload));
					} else if ("lane-init-domain-groups".equalsIgnoreCase(opName)) {
						firstStepEmitted = true;
						String dom = stringArg(list, 1);
						int numGroups = intArg(list, 2, 0);
						short pairsReg = 8;
						short groupsReg = 9;
						List<Long> nodesList = new ArrayList<>();
						List<Long> groupsList = new ArrayList<>();
						for (int i = 3; i + 1 < list.elements().size(); i += 2) {
							nodesList.add((long) intArg(list, i, 0));
							groupsList.add((long) intArg(list, i + 1, 0));
						}
						if (!nodesList.isEmpty()) {
							int nodesPayload = appendInlineIntArray(inlineBytes, nodesList);
							int groupsPayload = appendInlineIntArray(inlineBytes, groupsList);
							instrList.add(
									new InstructionWord(OP_LOAD_INLINE_ARRAY, (byte) 0x01, pairsReg, nodesPayload));
							instrList.add(
									new InstructionWord(OP_LOAD_INLINE_ARRAY, (byte) 0x01, groupsReg, groupsPayload));
						} else {
							instrList.add(new InstructionWord(OP_CLEAR_REG, (byte) 0, pairsReg, 0));
							instrList.add(new InstructionWord(OP_CLEAR_REG, (byte) 0, groupsReg, 0));
						}
						int poolIdx = internString(stringPool, dom + "\0" + numGroups);
						int payload = (pairsReg & 0xFF) | ((groupsReg & 0xFF) << 8) | ((poolIdx & 0xFFFF) << 16);
						instrList.add(new InstructionWord(OP_LANE_INIT_GROUPS, (byte) 0x40, dstReg, payload));
					} else if ("lane-walk-both".equalsIgnoreCase(opName)) {
						String relName = stringArg(list, 1);
						int relId = resolveRelationId(snapshot, relName);
						relationIdMap.put(relName, relId);
						long pc = instrList.size();
						patches.add(new RelationInstructionPatch(pc, relName, srcReg, dstReg));
						int payload = (srcReg & 0xFFFF) | ((relId & 0xFFFF) << 16);
						instrList.add(new InstructionWord(OP_LANE_CSR_WALK, (byte) 0x08, dstReg, payload));
					} else if ("lane-walk-union".equalsIgnoreCase(opName)) {
						String dir = stringArg(list, 1);
						StringBuilder joined = new StringBuilder();
						for (int i = 2; i < list.elements().size(); i++) {
							if (joined.length() > 0) {
								joined.append('\0');
							}
							joined.append(stringArg(list, i));
						}
						int poolIdx = internString(stringPool, joined.toString());
						byte opcode = "in".equalsIgnoreCase(dir) ? OP_LANE_CSC_WALK : OP_LANE_CSR_WALK;
						int payload = (srcReg & 0xFFFF) | ((poolIdx & 0xFFFF) << 16);
						instrList.add(new InstructionWord(opcode, (byte) 0x40, dstReg, payload));
					} else if ("lane-walk-where".equalsIgnoreCase(opName)) {
						String dir = stringArg(list, 1);
						String relName = stringArg(list, 2);
						String cel = stringArg(list, 3);
						int poolIdx = internString(stringPool, relName + "\0" + cel);
						byte opcode = "in".equalsIgnoreCase(dir) ? OP_LANE_CSC_WALK : OP_LANE_CSR_WALK;
						int payload = (srcReg & 0xFFFF) | ((poolIdx & 0xFFFF) << 16);
						instrList.add(new InstructionWord(opcode, (byte) 0x20, dstReg, payload));
					} else if ("lane-fixpoint-max-hops".equalsIgnoreCase(opName)) {
						String relName = stringArg(list, 1);
						int maxHops = intArg(list, 2, 0);
						int poolIdx = internString(stringPool, relName + "\0" + maxHops);
						int payload = (srcReg & 0xFFFF) | ((poolIdx & 0xFFFF) << 16);
						instrList.add(new InstructionWord(OP_LANE_CSR_WALK, (byte) 0x44, dstReg, payload));
					} else if ("lane-filter-cel".equalsIgnoreCase(opName)) {
						String dom = stringArg(list, 1);
						String cel = stringArg(list, 2);
						int poolIdx = internString(stringPool, dom + "\0" + cel);
						int payload = (srcReg & 0xFF) | ((poolIdx & 0xFFFF) << 16);
						instrList.add(new InstructionWord(OP_LANE_PRUNE_MASK, (byte) 0x40, srcReg, payload));
						currentReg = srcReg;
					} else if ("lane-quorum-inplace".equalsIgnoreCase(opName)) {
						int minK = intArg(list, 1, 1);
						String reqStr = stringArg(list, 2);
						String forbStr = stringArg(list, 3);
						int poolIdx = internString(stringPool, minK + "\0" + reqStr + "\0" + forbStr);
						int payload = (srcReg & 0xFF) | ((poolIdx & 0xFFFF) << 16);
						instrList.add(new InstructionWord(OP_LANE_PRUNE_MASK, (byte) 0x20, srcReg, payload));
						currentReg = srcReg;
					} else if ("repeat".equalsIgnoreCase(opName)) {
						int repeatCount = 1;
						if (list.elements().size() > 1 && list.elements().get(1) instanceof ScmLiteral.ScmInt cnt) {
							repeatCount = (int) cnt.value();
						}
						short startReg = srcReg;
						if (!firstStepEmitted) {
							instrList.add(new InstructionWord(OP_INIT_INPUT_NODE, (byte) 0, startReg, 0));
							firstStepEmitted = true;
						}
						ScmProgram subProg = (list.elements().size() > 2
								&& list.elements().get(2) instanceof ScmProgram sp) ? sp : null;
						if (repeatCount > 1 && isReflexiveSubProgram(subProg, snapshot)) {
							short accReg = 3;
							instrList.add(new InstructionWord(OP_MOV, (byte) 0, accReg, (int) startReg));
							short countReg = 2; // R2
							instrList.add(new InstructionWord(OP_LOAD_CONST_INT, (byte) 0, countReg, repeatCount));
							long loopStartPc = instrList.size();

							short subOutReg = emitSubSteps(subProg.steps(), snapshot, instrList, patches, relationIdMap,
									stringPool, startReg);

							int diffPayload = ((accReg & 0xFFFF) << 16) | (subOutReg & 0xFFFF);
							instrList.add(new InstructionWord(OP_SET_DIFFERENCE, (byte) 0, startReg, diffPayload));

							int jzPc = instrList.size();
							instrList.add(new InstructionWord(OP_JZ, (byte) 0, (short) 0, 0));

							int unionPayload = ((startReg & 0xFFFF) << 16) | (accReg & 0xFFFF);
							instrList.add(new InstructionWord(OP_SET_UNION, (byte) 0, accReg, unionPayload));

							int loopEndPc = instrList.size();
							int relOffset = (int) (loopStartPc - loopEndPc);
							instrList.add(new InstructionWord(OP_LOOP_DECR, (byte) 0, countReg, relOffset));

							int loopExitPc = instrList.size();
							instrList.set(jzPc,
									new InstructionWord(OP_JZ, (byte) 0, (short) 0, (int) (loopExitPc - jzPc)));

							instrList.add(new InstructionWord(OP_SWAP_REG, (byte) 0, startReg, (int) accReg));
							currentReg = startReg;
						} else {
							short countReg = 2; // R2
							instrList.add(new InstructionWord(OP_LOAD_CONST_INT, (byte) 0, countReg, repeatCount));
							long loopStartPc = instrList.size();

							short subOutReg = startReg;
							if (subProg != null) {
								subOutReg = emitSubSteps(subProg.steps(), snapshot, instrList, patches, relationIdMap,
										stringPool, startReg);
							}
							// Swap destination register back to startReg for next iteration in O(1)
							if (subOutReg != startReg) {
								instrList.add(new InstructionWord(OP_SWAP_REG, (byte) 0, startReg, (int) subOutReg));
							}
							int loopEndPc = instrList.size();
							int relOffset = (int) (loopStartPc - loopEndPc);
							instrList.add(new InstructionWord(OP_LOOP_DECR, (byte) 0, countReg, relOffset));
							currentReg = startReg;
						}
					} else if ("repeat-until-stable".equalsIgnoreCase(opName)) {
						short startReg = srcReg;
						if (!firstStepEmitted) {
							instrList.add(new InstructionWord(OP_INIT_INPUT_NODE, (byte) 0, startReg, 0));
							firstStepEmitted = true;
						}
						// Accumulator register for all reached nodes across iterations
						short accReg = 2;
						// Initialize accReg with seed in startReg
						instrList.add(new InstructionWord(OP_MOV, (byte) 0, accReg, (int) startReg));

						long loopStartPc = instrList.size();

						short subOutReg = startReg;
						if (list.elements().size() > 1 && list.elements().get(1) instanceof ScmProgram subProg) {
							subOutReg = emitSubSteps(subProg.steps(), snapshot, instrList, patches, relationIdMap,
									stringPool, startReg);
						}

						// Check if newly reached nodes (subOutReg) are already a subset of accReg
						int checkPayload = subOutReg & 0xFFFF;
						instrList.add(new InstructionWord(OP_STABLE_CHECK, (byte) 0, accReg, checkPayload));

						// Placeholder for OP_JZ loopExit
						int jzPc = instrList.size();
						instrList.add(new InstructionWord(OP_JZ, (byte) 0, (short) 0, 0));

						// Compute delta frontier for next iteration: startReg = subOutReg \ accReg
						int diffPayload = ((accReg & 0xFFFF) << 16) | (subOutReg & 0xFFFF);
						instrList.add(new InstructionWord(OP_SET_DIFFERENCE, (byte) 0, startReg, diffPayload));

						// Union newly discovered frontier into accReg: accReg = accReg | startReg
						int unionPayload = ((startReg & 0xFFFF) << 16) | (accReg & 0xFFFF);
						instrList.add(new InstructionWord(OP_SET_UNION, (byte) 0, accReg, unionPayload));

						// Unconditional jump back to loopStartPc
						int jmpPc = instrList.size();
						instrList.add(new InstructionWord(OP_JMP, (byte) 0, (short) 0, (int) (loopStartPc - jmpPc)));

						// loopExit point: patch OP_JZ with relative offset to loopExitPc
						int loopExitPc = instrList.size();
						instrList.set(jzPc, new InstructionWord(OP_JZ, (byte) 0, (short) 0, (int) (loopExitPc - jzPc)));

						// Return full accumulated visited set
						instrList.add(new InstructionWord(OP_MOV, (byte) 0, startReg, (int) accReg));
						currentReg = startReg;
					} else if ("project-expression".equalsIgnoreCase(opName)) {
						String attrName = "";
						if (list.elements().size() > 1 && list.elements().get(1) instanceof ScmSymbol sym) {
							attrName = sym.name();
						}
						int nameIdx = internString(stringPool, attrName);
						int payload = ((srcReg & 0xFFFF) << 16) | (nameIdx & 0xFFFF);
						instrList.add(new InstructionWord(OP_VECTOR_LOAD_ATTR, (byte) 0, dstReg, payload));
					} else if ("island-detect".equalsIgnoreCase(opName)) {
						instrList.add(new InstructionWord(OP_ISLAND_DETECT, (byte) 0, dstReg, 0));
					} else if ("rebac-check".equalsIgnoreCase(opName)) {
						instrList.add(new InstructionWord(OP_REBAC_CHECK, (byte) 0, dstReg, 0));
					} else if ("motif-match-3".equalsIgnoreCase(opName)) {
						instrList.add(new InstructionWord(OP_MOTIF_MATCH_3, (byte) 0, dstReg, 0));
					}
				}
			}
		}

		// Always end program with OP_HALT
		instrList.add(new InstructionWord(OP_HALT, (byte) 0, (short) 0, 0));

		// Allocate off-heap instruction memory segment
		long count = instrList.size();
		MemorySegment programSeg = arena.allocate(INSTRUCTION_LAYOUT.byteSize() * count);

		for (int i = 0; i < count; i++) {
			long off = (long) i * 8; // 8-byte instruction layout
			InstructionWord word = instrList.get(i);
			INSTR_OPCODE_HANDLE.set(programSeg, off, word.opcode());
			INSTR_FLAGS_HANDLE.set(programSeg, off, word.flags());
			INSTR_DST_REG_HANDLE.set(programSeg, off, word.dstReg());
			INSTR_PAYLOAD_HANDLE.set(programSeg, off, word.payload());
		}

		MemorySegment inlineDataSeg = null;
		if (inlineBytes.size() > 0) {
			byte[] raw = inlineBytes.toByteArray();
			inlineDataSeg = arena.allocate(raw.length);
			inlineDataSeg.copyFrom(MemorySegment.ofArray(raw));
		}

		return new EmittedProgram(programSeg, count, patches, relationIdMap, instrList, stringPool, inlineDataSeg);
	}

	private static boolean canRemainingHaltOnEmpty(List<ImpScmNode> steps, int fromIdx) {
		for (int j = fromIdx; j < steps.size(); j++) {
			ImpScmNode rem = steps.get(j);
			if (rem instanceof ScmWalk w) {
				if (!w.shaderSteps().isEmpty()) {
					return false;
				}
			} else if (rem instanceof ScmWalk2Hop) {
				// pure walk
			} else if (rem instanceof ScmCollect c) {
				if (c.format() != ScmCollect.Format.BITSET) {
					return false;
				}
			} else {
				return false;
			}
		}
		return true;
	}

	private static int internString(List<String> stringPool, String value) {
		String v = value != null ? value : "";
		int idx = stringPool.indexOf(v);
		if (idx < 0) {
			idx = stringPool.size();
			stringPool.add(v);
		}
		return idx;
	}

	private static String stringArg(ScmList list, int idx) {
		if (idx < 0 || idx >= list.elements().size()) {
			return "";
		}
		ImpScmNode n = list.elements().get(idx);
		if (n instanceof ScmLiteral.ScmString s) {
			return s.value();
		}
		if (n instanceof ScmSymbol sym) {
			return sym.name();
		}
		if (n instanceof ScmLiteral.ScmInt iv) {
			return Long.toString(iv.value());
		}
		return n.toScmString();
	}

	private static int intArg(ScmList list, int idx, int defVal) {
		if (idx < 0 || idx >= list.elements().size()) {
			return defVal;
		}
		ImpScmNode n = list.elements().get(idx);
		if (n instanceof ScmLiteral.ScmInt iv) {
			return (int) iv.value();
		}
		if (n instanceof ScmLiteral.ScmString s) {
			try {
				return Integer.parseInt(s.value());
			} catch (NumberFormatException ignored) {
			}
		}
		return defVal;
	}

	private static int appendInlineIntArray(ByteArrayOutputStream inlineBytes, List<Long> vals) {
		int offset = inlineBytes.size();
		int count = vals.size();
		for (Long v : vals) {
			int iv = v != null ? v.intValue() : 0;
			inlineBytes.write(iv & 0xFF);
			inlineBytes.write((iv >>> 8) & 0xFF);
			inlineBytes.write((iv >>> 16) & 0xFF);
			inlineBytes.write((iv >>> 24) & 0xFF);
		}
		return ((count & 0xFFFF) << 16) | (offset & 0xFFFF);
	}

	private static int appendInlineFloatArray(ByteArrayOutputStream inlineBytes, float[] vals) {
		int offset = inlineBytes.size();
		int count = vals.length;
		for (float f : vals) {
			int bits = Float.floatToRawIntBits(f);
			inlineBytes.write(bits & 0xFF);
			inlineBytes.write((bits >>> 8) & 0xFF);
			inlineBytes.write((bits >>> 16) & 0xFF);
			inlineBytes.write((bits >>> 24) & 0xFF);
		}
		return ((count & 0xFFFF) << 16) | (offset & 0xFFFF);
	}

	private static void emitRepeatUntilStable(List<ImpScmNode> subSteps, ImpulseGraphSnapshot snapshot,
			List<InstructionWord> instrList, List<RelationInstructionPatch> patches, Map<String, Integer> relationIdMap,
			List<String> stringPool, short startReg) {
		short accReg = 2;
		instrList.add(new InstructionWord(OP_MOV, (byte) 0, accReg, (int) startReg));
		long loopStartPc = instrList.size();
		short subOutReg = emitSubSteps(subSteps, snapshot, instrList, patches, relationIdMap, stringPool, startReg);
		int checkPayload = subOutReg & 0xFFFF;
		instrList.add(new InstructionWord(OP_STABLE_CHECK, (byte) 0, accReg, checkPayload));
		int jzPc = instrList.size();
		instrList.add(new InstructionWord(OP_JZ, (byte) 0, (short) 0, 0));
		int diffPayload = ((accReg & 0xFFFF) << 16) | (subOutReg & 0xFFFF);
		instrList.add(new InstructionWord(OP_SET_DIFFERENCE, (byte) 0, startReg, diffPayload));
		int unionPayload = ((startReg & 0xFFFF) << 16) | (accReg & 0xFFFF);
		instrList.add(new InstructionWord(OP_SET_UNION, (byte) 0, accReg, unionPayload));
		int jmpPc = instrList.size();
		instrList.add(new InstructionWord(OP_JMP, (byte) 0, (short) 0, (int) (loopStartPc - jmpPc)));
		int loopExitPc = instrList.size();
		instrList.set(jzPc, new InstructionWord(OP_JZ, (byte) 0, (short) 0, (int) (loopExitPc - jzPc)));
		instrList.add(new InstructionWord(OP_MOV, (byte) 0, startReg, (int) accReg));
	}

	private static List<ImpScmNode> asSteps(ImpScmNode node) {
		if (node instanceof ScmProgram p) {
			return p.steps();
		}
		return List.of(node);
	}

	private static short emitSetOp(ScmSetOp setOp, ImpulseGraphSnapshot snapshot, List<InstructionWord> instrList,
			List<RelationInstructionPatch> patches, Map<String, Integer> relationIdMap, List<String> stringPool,
			short startReg, short dstReg) {
		short seedSaveReg = 6;
		short lhsSaveReg = 7;
		if (setOp.lhs() != null) {
			instrList.add(new InstructionWord(OP_MOV, (byte) 0, seedSaveReg, (int) startReg));
			short lhsOutReg = emitSubSteps(asSteps(setOp.lhs()), snapshot, instrList, patches, relationIdMap,
					stringPool, startReg);
			instrList.add(new InstructionWord(OP_MOV, (byte) 0, lhsSaveReg, (int) lhsOutReg));
			instrList.add(new InstructionWord(OP_MOV, (byte) 0, startReg, (int) seedSaveReg));
		} else {
			instrList.add(new InstructionWord(OP_MOV, (byte) 0, lhsSaveReg, (int) startReg));
		}
		short rhsOutReg = emitSubSteps(asSteps(setOp.rhs()), snapshot, instrList, patches, relationIdMap, stringPool,
				startReg);
		byte opcode = switch (setOp.kind()) {
			case UNION -> OP_SET_UNION;
			case INTERSECT -> OP_SET_INTERSECT;
			case DIFF -> OP_SET_DIFFERENCE;
		};
		int payload = ((rhsOutReg & 0xFFFF) << 16) | (lhsSaveReg & 0xFFFF);
		instrList.add(new InstructionWord(opcode, (byte) 0, dstReg, payload));
		return dstReg;
	}

	private static boolean isReflexiveSubProgram(ScmProgram subProg, ImpulseGraphSnapshot snapshot) {
		if (subProg == null || snapshot == null || subProg.steps().size() != 1) {
			return false;
		}
		if (!(subProg.steps().get(0) instanceof ScmWalk walk)) {
			return false;
		}
		org.impulsegraph.api.RelationSnapshot rel = snapshot.getRelationSnapshot(walk.relationName());
		if (rel == null) {
			int relId = resolveRelationId(snapshot, walk.relationName());
			rel = snapshot.getRelationSnapshot(relId);
		}
		if (rel == null || rel.getEdgeCount() <= 0 || rel.getNodeCount() <= 0) {
			return false;
		}
		int checked = 0;
		for (int u = 0, n = rel.getNodeCount(); u < n && checked < 8; u++) {
			if (rel.getDegree(u) > 0) {
				checked++;
				boolean hasSelf = false;
				for (int t : rel.getTargets(u)) {
					if (t == u) {
						hasSelf = true;
						break;
					}
				}
				if (!hasSelf) {
					return false;
				}
			}
		}
		return checked > 0;
	}

	private static short emitSubSteps(List<ImpScmNode> subSteps, ImpulseGraphSnapshot snapshot,
			List<InstructionWord> instrList, List<RelationInstructionPatch> patches, Map<String, Integer> relationIdMap,
			List<String> stringPool, short startReg) {
		short currentReg = startReg;
		for (ImpScmNode step : subSteps) {
			if (step instanceof ScmWalk walk) {
				int relId = walk.relationId();
				String logicalRelName = walk.relationName();
				if (relId < 0 && !logicalRelName.isEmpty() && snapshot != null) {
					relId = resolveRelationId(snapshot, logicalRelName);
				}
				if (relId >= 0) {
					relationIdMap.put(logicalRelName, relId);
				}

				short srcReg = currentReg;
				short dstReg = (short) (1 - currentReg);
				currentReg = dstReg;

				org.impulsegraph.api.RelationSnapshot boundRel = resolveRelationSnapshot(snapshot, relId,
						logicalRelName);
				if (walk.direction() == ScmWalk.Direction.REVERSE_CSC && boundRel != null && !boundRel.hasCsc()
						&& walk.shaderSteps().isEmpty()) {
					int poolIdx = internString(stringPool, logicalRelName + "\0");
					int payload = ((poolIdx & 0xFFFF) << 16) | (srcReg & 0xFFFF);
					instrList.add(new InstructionWord(OP_CSR_WALK_FILTERED, (byte) 0x60, dstReg, payload));
					continue;
				}

				long pc = instrList.size();
				patches.add(new RelationInstructionPatch(pc, logicalRelName, srcReg, dstReg));

				boolean useCsc = walk.direction() == ScmWalk.Direction.REVERSE_CSC
						&& (boundRel == null || boundRel.hasCsc());
				byte opcode = useCsc ? OP_CSC_WALK : OP_CSR_WALK;
				int payload = ((relId & 0xFFFF) << 16) | (srcReg & 0xFFFF);
				instrList.add(new InstructionWord(opcode, (byte) 0, dstReg, payload));
			} else if (step instanceof ScmFixpoint fix) {
				emitRepeatUntilStable(fix.body().steps(), snapshot, instrList, patches, relationIdMap, stringPool,
						currentReg);
			} else if (step instanceof ScmSetOp setOp) {
				short dstReg = (short) (1 - currentReg);
				currentReg = emitSetOp(setOp, snapshot, instrList, patches, relationIdMap, stringPool, currentReg,
						dstReg);
			} else if (step instanceof ScmList list && !list.elements().isEmpty()
					&& list.elements().get(0) instanceof ScmSymbol sym) {
				String subOp = sym.name();
				if ("cel-domain-filter".equalsIgnoreCase(subOp)) {
					short srcReg = currentReg;
					short dstReg = (short) (1 - currentReg);
					currentReg = dstReg;
					int poolIdx = internString(stringPool, stringArg(list, 1) + "\0" + stringArg(list, 2));
					int payload = ((poolIdx & 0xFFFF) << 16) | (srcReg & 0xFFFF);
					instrList.add(new InstructionWord(OP_NODE_FILTER, (byte) 0x40, dstReg, payload));
				} else if ("cel-edge-walk".equalsIgnoreCase(subOp)) {
					short srcReg = currentReg;
					short dstReg = (short) (1 - currentReg);
					currentReg = dstReg;
					boolean isRev = "in".equalsIgnoreCase(stringArg(list, 1));
					int poolIdx = internString(stringPool, stringArg(list, 2) + "\0" + stringArg(list, 3));
					int payload = ((poolIdx & 0xFFFF) << 16) | (srcReg & 0xFFFF);
					instrList.add(
							new InstructionWord(OP_CSR_WALK_FILTERED, (byte) (isRev ? 0x60 : 0x40), dstReg, payload));
				}
			}
		}
		return currentReg;
	}

	private static org.impulsegraph.api.RelationSnapshot resolveRelationSnapshot(ImpulseGraphSnapshot snapshot,
			int relId, String relName) {
		if (snapshot == null) {
			return null;
		}
		if (relName != null && !relName.isEmpty()) {
			org.impulsegraph.api.RelationSnapshot byName = snapshot.getRelationSnapshot(relName);
			if (byName != null) {
				return byName;
			}
		}
		if (relId >= 0) {
			org.impulsegraph.api.RelationSnapshot byId = snapshot.getRelationSnapshot(relId);
			if (byId != null) {
				return byId;
			}
			var map = snapshot.getAllRelationSnapshots();
			int idx = 0;
			for (org.impulsegraph.api.RelationSnapshot rel : map.values()) {
				if (idx == relId) {
					return rel;
				}
				idx++;
			}
		}
		return null;
	}

	private static int resolveRelationId(ImpulseGraphSnapshot snapshot, String relName) {
		if (snapshot == null || relName == null)
			return 0;
		org.impulsegraph.api.RelationSnapshot targetRel = snapshot.getRelationSnapshot(relName);
		if (targetRel != null) {
			if (targetRel.getRelationId() >= 0
					&& snapshot.getRelationSnapshot(targetRel.getRelationId()) == targetRel) {
				return targetRel.getRelationId();
			}
			for (int i = 0; i < 65536; i++) {
				org.impulsegraph.api.RelationSnapshot r = snapshot.getRelationSnapshot(i);
				if (r == null) {
					if (i >= snapshot.getAllRelationSnapshots().size() && i > 10)
						break;
					continue;
				}
				if (r == targetRel || r.equals(targetRel)) {
					return i;
				}
			}
		}
		var map = snapshot.getAllRelationSnapshots();
		if (map.containsKey(relName)) {
			int idx = 0;
			for (String key : map.keySet()) {
				if (key.equalsIgnoreCase(relName))
					return idx;
				idx++;
			}
		}
		int idx = 0;
		for (String key : map.keySet()) {
			if (key.endsWith("_" + relName) || key.endsWith(relName)
					|| key.toLowerCase().endsWith(relName.toLowerCase())) {
				return idx;
			}
			idx++;
		}
		return 0;
	}

	public static CompiledQuery compileToExecutable(ImpScmNode ast, ImpulseGraphSnapshot snapshot, Arena arena) {
		EmittedProgram emitted = emit(ast, snapshot, arena);
		return new CompiledQuery(emitted.programSegment(), emitted.instructionCount(), emitted.patches(),
				emitted.relationIdMap(), snapshot, arena, emitted.stringPool(), emitted.inlineDataSegment());
	}

	private static class StreamRegisterAllocator {
		private int nextReg = 1; // 0 is TGT_ID implicitly
		public int allocate() {
			if (nextReg >= 16)
				throw new RuntimeException("Stream register exhaustion");
			return nextReg++;
		}
	}

	private static void emitPreStreamCelLoads(ImpScmNode node, int relId, List<InstructionWord> instrList,
			List<String> stringPool) {
		if (node instanceof ScmCelExpr cel) {
			String scm = cel.celAst() != null ? cel.celAst().toString() : cel.rawText();
			ImpScmNode parsed = org.impulsegraph.compiler.ast.parser.ImpScmParser.parse(scm);
			emitPreStreamCelLoads(parsed, relId, instrList, stringPool);
		} else if (node instanceof ScmList list && !list.elements().isEmpty()) {
			if (list.elements().get(0) instanceof ScmSymbol sym && sym.name().equals("get-attr")
					&& list.elements().size() > 2 && list.elements().get(2) instanceof ScmLiteral.ScmString attrLit) {
				String attr = attrLit.value();
				int strIdx = stringPool.indexOf(attr);
				if (strIdx < 0) {
					strIdx = stringPool.size();
					stringPool.add(attr);
				}
				int loadPayload = ((strIdx & 0xFFFF) << 16) | (relId & 0xFFFF);
				instrList.add(new InstructionWord(OP_LOAD_COLUMN_VECTOR, (byte) 0, (short) 15, loadPayload));
			} else {
				for (ImpScmNode child : list.elements()) {
					emitPreStreamCelLoads(child, relId, instrList, stringPool);
				}
			}
		}
	}

	private static void emitStreamStep(ImpScmNode node, List<InstructionWord> instrList, StreamRegisterAllocator alloc,
			ImpulseGraphSnapshot snapshot) {
		if (node instanceof ScmStreamFilter filter) {
			int reg = emitStreamExpr(filter.predicate(), instrList, alloc, snapshot);
			instrList.add(new InstructionWord(OP_STREAM_FILTER, (byte) 0, (short) reg, 0));
		} else if (node instanceof ScmStreamProject project) {
			int reg = emitStreamExpr(project.expr(), instrList, alloc, snapshot);
			instrList.add(new InstructionWord(OP_STREAM_SCATTER_REDUCE, (byte) 0, (short) reg,
					(5 & 0xFFFF) | ((0 & 0xFF) << 16)));
		} else if (node instanceof ScmCelExpr cel) {
			String scm = cel.celAst() != null ? cel.celAst().toString() : cel.rawText();
			ImpScmNode parsed = org.impulsegraph.compiler.ast.parser.ImpScmParser.parse(scm);
			int reg = emitStreamExpr(parsed, instrList, alloc, snapshot);
			instrList.add(new InstructionWord(OP_STREAM_FILTER, (byte) 0, (short) reg, 0));
		} else if (node instanceof ScmList list && !list.elements().isEmpty()
				&& list.elements().get(0) instanceof ScmSymbol sym && !sym.name().equals("project-state")
				&& !sym.name().equals("filter-attr")) {
			int reg = emitStreamExpr(list, instrList, alloc, snapshot);
			instrList.add(new InstructionWord(OP_STREAM_FILTER, (byte) 0, (short) reg, 0));
		}
	}

	private static int emitStreamExpr(ImpScmNode expr, List<InstructionWord> instrList, StreamRegisterAllocator alloc,
			ImpulseGraphSnapshot snapshot) {
		if (expr instanceof ScmList list && !list.elements().isEmpty()) {
			ImpScmNode head = list.elements().get(0);
			if (head instanceof ScmSymbol sym) {
				String op = sym.name();
				if (op.equals("stream-cmp-gt") || op.equals("vec-cmp-gt")) {
					int lhs = emitStreamExpr(list.elements().get(1), instrList, alloc, snapshot);
					int rhs = emitStreamExpr(list.elements().get(2), instrList, alloc, snapshot);
					int dst = alloc.allocate();
					instrList.add(new InstructionWord(OP_STREAM_CMP_GT, (byte) 0, (short) dst,
							(lhs & 0xFFFF) | ((rhs & 0xFFFF) << 16)));
					return dst;
				} else if (op.equals("stream-cmp-lt") || op.equals("vec-cmp-lt")) {
					int lhs = emitStreamExpr(list.elements().get(1), instrList, alloc, snapshot);
					int rhs = emitStreamExpr(list.elements().get(2), instrList, alloc, snapshot);
					int dst = alloc.allocate();
					instrList.add(new InstructionWord(OP_STREAM_CMP_LT, (byte) 0, (short) dst,
							(lhs & 0xFFFF) | ((rhs & 0xFFFF) << 16)));
					return dst;
				} else if (op.equals("stream-cmp-eq") || op.equals("vec-cmp-eq")) {
					int lhs = emitStreamExpr(list.elements().get(1), instrList, alloc, snapshot);
					int rhs = emitStreamExpr(list.elements().get(2), instrList, alloc, snapshot);
					int dst = alloc.allocate();
					instrList.add(new InstructionWord(OP_STREAM_CMP_EQ, (byte) 0, (short) dst,
							(lhs & 0xFFFF) | ((rhs & 0xFFFF) << 16)));
					return dst;
				} else if (op.equals("stream-cmp-neq") || op.equals("vec-cmp-neq")) {
					int lhs = emitStreamExpr(list.elements().get(1), instrList, alloc, snapshot);
					int rhs = emitStreamExpr(list.elements().get(2), instrList, alloc, snapshot);
					int dst = alloc.allocate();
					instrList.add(new InstructionWord(OP_STREAM_CMP_NEQ, (byte) 0, (short) dst,
							(lhs & 0xFFFF) | ((rhs & 0xFFFF) << 16)));
					return dst;
				} else if (op.equals("stream-load-attr") || op.equals("get-attr")) {
					int dst = alloc.allocate();
					int attrRegOrId = op.equals("get-attr") ? 15 : 0;
					instrList.add(new InstructionWord(OP_STREAM_LOAD_TGT, (byte) 0, (short) dst, attrRegOrId));
					return dst;
				} else if (op.equals("stream-logic-and") || op.equals("mask-and")) {
					int lhs = emitStreamExpr(list.elements().get(1), instrList, alloc, snapshot);
					int rhs = emitStreamExpr(list.elements().get(2), instrList, alloc, snapshot);
					int dst = alloc.allocate();
					instrList.add(new InstructionWord(OP_STREAM_LOGIC_AND, (byte) 0, (short) dst,
							(lhs & 0xFFFF) | ((rhs & 0xFFFF) << 16)));
					return dst;
				} else if (op.equals("stream-logic-or") || op.equals("mask-or")) {
					int lhs = emitStreamExpr(list.elements().get(1), instrList, alloc, snapshot);
					int rhs = emitStreamExpr(list.elements().get(2), instrList, alloc, snapshot);
					int dst = alloc.allocate();
					instrList.add(new InstructionWord(OP_STREAM_LOGIC_OR, (byte) 0, (short) dst,
							(lhs & 0xFFFF) | ((rhs & 0xFFFF) << 16)));
					return dst;
				} else if (op.equals("stream-logic-not") || op.equals("mask-not")) {
					int lhs = emitStreamExpr(list.elements().get(1), instrList, alloc, snapshot);
					int dst = alloc.allocate();
					instrList.add(new InstructionWord(OP_STREAM_LOGIC_NOT, (byte) 0, (short) dst, lhs & 0xFFFF));
					return dst;
				}
			}
		} else if (expr instanceof ScmLiteral lit) {
			int dst = alloc.allocate();
			int payload = 0;
			if (lit instanceof ScmLiteral.ScmInt i) {
				payload = Float.floatToRawIntBits((float) i.value());
			} else if (lit instanceof ScmLiteral.ScmFloat f) {
				payload = Float.floatToRawIntBits((float) f.value());
			}
			instrList.add(new InstructionWord((byte) OP_STREAM_LOAD_CONST, (byte) 0, (short) dst, payload));
			return dst;
		}

		int dst = alloc.allocate();
		instrList.add(new InstructionWord(OP_STREAM_LOAD_CONST, (byte) 0, (short) dst, 0));
		return dst;
	}

}