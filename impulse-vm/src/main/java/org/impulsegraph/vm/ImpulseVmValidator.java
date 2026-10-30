package org.impulsegraph.vm;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Java 21+ Ahead-of-Time & Runtime Bytecode Type and Register Contract
 * Validator for ImpulseVM. Performs single-pass abstract type propagation
 * across registers R0..R63 and enforces input/output register-type contracts
 * across all single-lane and multi-lane (0xC0..0xCB) opcodes.
 */
public final class ImpulseVmValidator {

	private ImpulseVmValidator() {
	}

	public enum OpcodeClass {
		IMPLEMENTED, RESERVED, INVALID
	}

	public record OpcodeSignature(int opCode, String mnemonic, int arity, Set<VmRegType> allowedSrc1,
			Set<VmRegType> allowedSrc2, Set<VmRegType> expectedDst) {
	}

	public static final Set<VmRegType> NODE_SOURCE_TYPES = EnumSet.of(VmRegType.T_NULL, VmRegType.T_NODE_ID,
			VmRegType.T_INT64, VmRegType.T_BITSET_HANDLE, VmRegType.T_NODE_VECTOR, VmRegType.T_UINT64_VECTOR);

	public static final Set<VmRegType> NUMERIC_VECTOR_TYPES = EnumSet.of(VmRegType.T_FLOAT_VECTOR,
			VmRegType.T_DOUBLE_VECTOR, VmRegType.T_NODE_VECTOR, VmRegType.T_UINT64_VECTOR);

	public static final Set<VmRegType> ALL_REG_TYPES = EnumSet.allOf(VmRegType.class);

	public static final List<OpcodeSignature> NORMATIVE_OPCODE_CONTRACTS = List.of(
			new OpcodeSignature(0x00, "OP_HALT", 0, Set.of(), Set.of(), Set.of()),
			new OpcodeSignature(0x01, "OP_NOP", 0, Set.of(), Set.of(), Set.of()),
			new OpcodeSignature(0x02, "OP_INIT_INPUT_NODE", 0, Set.of(), Set.of(), EnumSet.of(VmRegType.T_NODE_ID)),
			new OpcodeSignature(0x03, "OP_INIT_INPUT_SET", 0, Set.of(), Set.of(),
					EnumSet.of(VmRegType.T_BITSET_HANDLE)),
			new OpcodeSignature(0x04, "OP_LOAD_CONST_INT", 0, Set.of(), Set.of(), EnumSet.of(VmRegType.T_INT64)),
			new OpcodeSignature(0x05, "OP_MAP_KEYS_TO_DENSE", 0, Set.of(), Set.of(),
					EnumSet.of(VmRegType.T_BITSET_HANDLE)),
			new OpcodeSignature(0x06, "OP_LOAD_CONST_FLOAT", 0, Set.of(), Set.of(), EnumSet.of(VmRegType.T_FLOAT)),
			new OpcodeSignature(0x07, "OP_LOAD_CONST_STR_PREFIX", 0, Set.of(), Set.of(), EnumSet.of(VmRegType.T_INT64)),
			new OpcodeSignature(0x08, "OP_LOAD_INLINE_ARRAY", 0, Set.of(), Set.of(),
					EnumSet.of(VmRegType.T_FLOAT_VECTOR, VmRegType.T_NODE_VECTOR)),
			new OpcodeSignature(0x09, "OP_INIT_MOCK_GRAPH", 0, Set.of(), Set.of(), EnumSet.of(VmRegType.T_INT64)),
			new OpcodeSignature(0x0A, "OP_LOAD_INLINE_SET", 0, Set.of(), Set.of(),
					EnumSet.of(VmRegType.T_BITSET_HANDLE)),
			new OpcodeSignature(0x0F, "OP_CSR_WALK_STATE", 1,
					EnumSet.of(VmRegType.T_NODE_ID, VmRegType.T_BITSET_HANDLE, VmRegType.T_NODE_VECTOR), Set.of(),
					EnumSet.of(VmRegType.T_FRONTIER_STATE)),
			new OpcodeSignature(0x10, "OP_CSR_WALK", 1, NODE_SOURCE_TYPES, Set.of(),
					EnumSet.of(VmRegType.T_BITSET_HANDLE)),
			new OpcodeSignature(0x12, "OP_CSR_DEGREE", 1, NODE_SOURCE_TYPES, Set.of(), EnumSet.of(VmRegType.T_INT64)),
			new OpcodeSignature(0x18, "OP_CSC_WALK", 1, NODE_SOURCE_TYPES, Set.of(),
					EnumSet.of(VmRegType.T_BITSET_HANDLE)),
			new OpcodeSignature(0x1D, "OP_ADAPTIVE_WALK", 1, NODE_SOURCE_TYPES, Set.of(),
					EnumSet.of(VmRegType.T_BITSET_HANDLE)),
			new OpcodeSignature(0x20, "OP_VEC_CMP_EQ", 2, EnumSet.of(VmRegType.T_FLOAT_VECTOR),
					EnumSet.of(VmRegType.T_FLOAT_VECTOR, VmRegType.T_FLOAT), EnumSet.of(VmRegType.T_BITSET_HANDLE)),
			new OpcodeSignature(0x21, "OP_VEC_CMP_GT", 2, EnumSet.of(VmRegType.T_FLOAT_VECTOR),
					EnumSet.of(VmRegType.T_FLOAT_VECTOR, VmRegType.T_FLOAT), EnumSet.of(VmRegType.T_BITSET_HANDLE)),
			new OpcodeSignature(0x22, "OP_VEC_CMP_LT", 2, EnumSet.of(VmRegType.T_FLOAT_VECTOR),
					EnumSet.of(VmRegType.T_FLOAT_VECTOR, VmRegType.T_FLOAT), EnumSet.of(VmRegType.T_BITSET_HANDLE)),
			new OpcodeSignature(0x2D, "OP_VEC_MATH_UNARY", 1,
					EnumSet.of(VmRegType.T_FLOAT, VmRegType.T_FLOAT_VECTOR, VmRegType.T_DOUBLE_VECTOR), Set.of(),
					EnumSet.of(VmRegType.T_FLOAT, VmRegType.T_FLOAT_VECTOR)),
			new OpcodeSignature(0x2E, "OP_VEC_MATH_BINARY", 2,
					EnumSet.of(VmRegType.T_FLOAT, VmRegType.T_FLOAT_VECTOR, VmRegType.T_DOUBLE_VECTOR),
					EnumSet.of(VmRegType.T_FLOAT, VmRegType.T_FLOAT_VECTOR, VmRegType.T_DOUBLE_VECTOR),
					EnumSet.of(VmRegType.T_FLOAT, VmRegType.T_FLOAT_VECTOR)),
			new OpcodeSignature(0x30, "OP_SET_UNION", 2, NODE_SOURCE_TYPES, NODE_SOURCE_TYPES,
					EnumSet.of(VmRegType.T_BITSET_HANDLE)),
			new OpcodeSignature(0x31, "OP_SET_INTERSECT", 2, NODE_SOURCE_TYPES, NODE_SOURCE_TYPES,
					EnumSet.of(VmRegType.T_BITSET_HANDLE)),
			new OpcodeSignature(0x32, "OP_SET_DIFFERENCE", 2, NODE_SOURCE_TYPES, NODE_SOURCE_TYPES,
					EnumSet.of(VmRegType.T_BITSET_HANDLE)),
			new OpcodeSignature(0x33, "OP_SET_CARDINALITY", 1, NODE_SOURCE_TYPES, Set.of(),
					EnumSet.of(VmRegType.T_INT64)),
			new OpcodeSignature(0x35, "OP_VECTOR_REDUCE_SUM", 1, NUMERIC_VECTOR_TYPES, Set.of(),
					EnumSet.of(VmRegType.T_FLOAT, VmRegType.T_DOUBLE, VmRegType.T_INT64)),
			new OpcodeSignature(0x3A, "OP_PROJECT_STATE", 1, EnumSet.of(VmRegType.T_FRONTIER_STATE), Set.of(),
					EnumSet.of(VmRegType.T_FRONTIER_STATE)),
			new OpcodeSignature(0x3C, "OP_EXTRACT_VALIDITY", 1,
					EnumSet.of(VmRegType.T_FLOAT_VECTOR, VmRegType.T_NODE_VECTOR, VmRegType.T_UINT64_VECTOR), Set.of(),
					EnumSet.of(VmRegType.T_BITSET_HANDLE)),
			new OpcodeSignature(0x45, "OP_REDUCE", 1,
					EnumSet.of(VmRegType.T_FLOAT_VECTOR, VmRegType.T_DOUBLE_VECTOR, VmRegType.T_NODE_VECTOR,
							VmRegType.T_UINT64_VECTOR, VmRegType.T_BITSET_HANDLE),
					Set.of(),
					EnumSet.of(VmRegType.T_FLOAT, VmRegType.T_DOUBLE, VmRegType.T_INT64, VmRegType.T_NODE_ID)),
			new OpcodeSignature(0x70, "OP_MOV", 1, ALL_REG_TYPES, Set.of(), ALL_REG_TYPES),
			new OpcodeSignature(0x71, "OP_CLEAR_REG", 0, Set.of(), Set.of(), EnumSet.of(VmRegType.T_NULL)),
			// Multi-Lane Bit-Sliced Opcodes (0xC0 .. 0xCB)
			new OpcodeSignature(0xC0, "OP_LANE_INIT_SEEDS", 0, Set.of(), Set.of(),
					EnumSet.of(VmRegType.T_LANE_FRONTIER)),
			new OpcodeSignature(0xC1, "OP_LANE_INIT_GROUPS", 0, Set.of(), Set.of(),
					EnumSet.of(VmRegType.T_LANE_FRONTIER)),
			new OpcodeSignature(0xC2, "OP_LANE_SET_EDGE_MASK", 1, EnumSet.of(VmRegType.T_LANE_FRONTIER), Set.of(),
					EnumSet.of(VmRegType.T_LANE_FRONTIER)),
			new OpcodeSignature(0xC3, "OP_LANE_CSR_WALK", 1, EnumSet.of(VmRegType.T_LANE_FRONTIER), Set.of(),
					EnumSet.of(VmRegType.T_LANE_FRONTIER)),
			new OpcodeSignature(0xC4, "OP_LANE_CSC_WALK", 1, EnumSet.of(VmRegType.T_LANE_FRONTIER), Set.of(),
					EnumSet.of(VmRegType.T_LANE_FRONTIER)),
			new OpcodeSignature(0xC5, "OP_LANE_PRUNE_MASK", 1, EnumSet.of(VmRegType.T_LANE_FRONTIER), Set.of(),
					EnumSet.of(VmRegType.T_LANE_FRONTIER)),
			new OpcodeSignature(0xC6, "OP_LANE_FILTER_QUORUM", 1, EnumSet.of(VmRegType.T_LANE_FRONTIER), Set.of(),
					EnumSet.of(VmRegType.T_BITSET_HANDLE)),
			new OpcodeSignature(0xC7, "OP_LANE_REDUCE_AND", 1, EnumSet.of(VmRegType.T_LANE_FRONTIER), Set.of(),
					EnumSet.of(VmRegType.T_INT64)),
			new OpcodeSignature(0xC8, "OP_LANE_REDUCE_ATTR", 1, EnumSet.of(VmRegType.T_LANE_FRONTIER), Set.of(),
					EnumSet.of(VmRegType.T_FLOAT_VECTOR, VmRegType.T_DOUBLE_VECTOR, VmRegType.T_UINT64_VECTOR,
							VmRegType.T_NODE_VECTOR)),
			new OpcodeSignature(0xC9, "OP_LANE_SET_COVER", 1, EnumSet.of(VmRegType.T_LANE_FRONTIER), Set.of(),
					EnumSet.of(VmRegType.T_NODE_VECTOR, VmRegType.T_UINT64_VECTOR)),
			new OpcodeSignature(0xCA, "OP_VECTOR_ANN_SEARCH", 1, EnumSet.of(VmRegType.T_FLOAT_VECTOR), Set.of(),
					EnumSet.of(VmRegType.T_BITSET_HANDLE, VmRegType.T_NODE_VECTOR, VmRegType.T_LANE_FRONTIER)),
			new OpcodeSignature(0xCB, "OP_VECTOR_RERANK_DOT", 2,
					EnumSet.of(VmRegType.T_BITSET_HANDLE, VmRegType.T_LANE_FRONTIER),
					EnumSet.of(VmRegType.T_FLOAT_VECTOR), EnumSet.of(VmRegType.T_FLOAT_VECTOR)));

	private static final Map<Integer, OpcodeSignature> CONTRACTS_BY_OP = NORMATIVE_OPCODE_CONTRACTS.stream()
			.collect(Collectors.toUnmodifiableMap(OpcodeSignature::opCode, Function.identity()));

	public static OpcodeSignature findOpcodeContract(int opcode) {
		return CONTRACTS_BY_OP.get(opcode);
	}

	/**
	 * Total classifier for all 256 possible 8-bit opcode bytes (matching OCaml's
	 * {@code classify_opcode}).
	 */
	public static OpcodeClass classifyOpcode(int op) {
		if (op < 0 || op > 0xFF || op == 0x0C || (op >= 0x96 && op <= 0x9F) || op == 0xBF || op >= 0xCC) {
			return OpcodeClass.INVALID;
		}
		if (op == 0x0D || op == 0x28 || op == 0x29 || op == 0x2B || op == 0x2C || op == 0x3E || op == 0x3F || op == 0x4F
				|| op == 0x59 || (op >= 0x5D && op <= 0x5F) || (op >= 0x6D && op <= 0x6F)
				|| (op >= 0x76 && op <= 0x7F)) {
			return OpcodeClass.RESERVED;
		}
		return OpcodeClass.IMPLEMENTED;
	}

	public static boolean isStrictTypedOpcode(int op) {
		return switch (op) {
			case 0x10, 0x12, 0x18, 0x1D, 0x2D, 0x2E, 0x30, 0x31, 0x32, 0x33, 0x3A, 0x3C, 0xC2, 0xC3, 0xC4, 0xC5, 0xC6,
					0xC7, 0xC8, 0xC9, 0xCA, 0xCB ->
				true;
			default -> false;
		};
	}

	public static String validate(VmHandlers.Instruction[] program) {
		if (program == null || program.length == 0) {
			return "IMPULSE_VM_OK";
		}

		VmRegType[] abstractTypes = new VmRegType[64];
		for (int i = 0; i < 64; i++) {
			abstractTypes[i] = VmRegType.T_NULL;
		}

		for (VmHandlers.Instruction inst : program) {
			int opcode = Byte.toUnsignedInt(inst.opcode());
			int dst = inst.dstReg();
			int payload = inst.payload();
			int pU16Low = payload & 0xFFFF;
			int pU16High = (payload >>> 16) & 0xFFFF;
			int pU8_0 = payload & 0xFF;
			int pU8_1 = (payload >>> 8) & 0xFF;

			OpcodeClass cls = classifyOpcode(opcode);
			if (cls == OpcodeClass.RESERVED) {
				return "IMPULSE_VM_ERR_RESERVED_OPCODE";
			}
			if (cls == OpcodeClass.INVALID && opcode != 0x0C && opcode != 0x0B) {
				return "IMPULSE_VM_ERR_INVALID_OPCODE";
			}

			if ((dst < 0 || dst >= 64) && opcode != 0x09 && opcode != 0x0B && opcode != 0x0C) {
				return "IMPULSE_VM_ERR_INVALID_REGISTER";
			}

			switch (opcode) {
				case 0x70 -> { // OP_MOV
					int src = pU16High != 0 ? pU16High : pU16Low;
					if (src < 0 || src >= 64) {
						return "IMPULSE_VM_ERR_INVALID_REGISTER";
					}
					abstractTypes[dst] = abstractTypes[src];
				}
				case 0x71 -> abstractTypes[dst] = VmRegType.T_NULL;
				case 0x02 -> abstractTypes[dst] = VmRegType.T_NODE_ID;
				case 0x03, 0x05, 0x0A -> abstractTypes[dst] = VmRegType.T_BITSET_HANDLE;
				case 0x04 -> abstractTypes[dst] = VmRegType.T_INT64;
				case 0x06 -> abstractTypes[dst] = VmRegType.T_FLOAT;
				case 0x07 -> abstractTypes[dst] = VmRegType.T_FLOAT_VECTOR;
				case 0x08 -> abstractTypes[dst] = VmRegType.T_FLOAT_VECTOR;
				case 0x10, 0x11, 0x18, 0x1D -> {
					if (pU16Low >= 64) {
						return "IMPULSE_VM_ERR_INVALID_REGISTER";
					}
					if (!abstractTypes[pU16Low].isValidNodeSource()) {
						return "IMPULSE_VM_ERR_INVALID_REGISTER";
					}
					abstractTypes[dst] = VmRegType.T_BITSET_HANDLE;
				}
				case 0x12, 0x33 -> {
					if (pU16Low >= 64 || !abstractTypes[pU16Low].isValidNodeSource()) {
						return "IMPULSE_VM_ERR_INVALID_REGISTER";
					}
					abstractTypes[dst] = VmRegType.T_INT64;
				}
				case 0x30, 0x31, 0x32 -> {
					if (pU16Low >= 64 || pU16High >= 64 || !abstractTypes[pU16Low].isValidBitsetOperand()
							|| !abstractTypes[pU16High].isValidBitsetOperand()) {
						return "IMPULSE_VM_ERR_INVALID_REGISTER";
					}
					abstractTypes[dst] = VmRegType.T_BITSET_HANDLE;
				}
				case 0x16, 0x35 -> abstractTypes[dst] = VmRegType.T_FLOAT;
				case 0xC0, 0xC1 -> abstractTypes[dst] = VmRegType.T_LANE_FRONTIER;
				case 0xC2 -> {
					if (pU8_0 >= 64 || !abstractTypes[pU8_0].isValidLaneFrontier()) {
						return "IMPULSE_VM_ERR_INVALID_REGISTER";
					}
					abstractTypes[dst] = VmRegType.T_LANE_FRONTIER;
				}
				case 0xC3, 0xC4 -> {
					if (pU16Low >= 64 || !abstractTypes[pU16Low].isValidLaneFrontier()) {
						return "IMPULSE_VM_ERR_INVALID_REGISTER";
					}
					abstractTypes[dst] = VmRegType.T_LANE_FRONTIER;
				}
				case 0xC5 -> {
					if (pU8_0 >= 64 || !abstractTypes[pU8_0].isValidLaneFrontier()) {
						return "IMPULSE_VM_ERR_INVALID_REGISTER";
					}
					abstractTypes[dst] = VmRegType.T_LANE_FRONTIER;
				}
				case 0xC6 -> {
					int src = (inst.flags() & 1) != 0 ? pU8_0 : pU16Low;
					if (src >= 64 || !abstractTypes[src].isValidLaneFrontier()) {
						return "IMPULSE_VM_ERR_INVALID_REGISTER";
					}
					abstractTypes[dst] = VmRegType.T_BITSET_HANDLE;
				}
				case 0xC7 -> {
					if (pU16Low >= 64 || !abstractTypes[pU16Low].isValidLaneFrontier()) {
						return "IMPULSE_VM_ERR_INVALID_REGISTER";
					}
					abstractTypes[dst] = VmRegType.T_INT64;
				}
				case 0xC8 -> {
					if (pU8_0 >= 64 || !abstractTypes[pU8_0].isValidLaneFrontier()) {
						return "IMPULSE_VM_ERR_INVALID_REGISTER";
					}
					int redOp = pU16High;
					abstractTypes[dst] = (redOp == 6 || redOp == 7)
							? VmRegType.T_UINT64_VECTOR
							: VmRegType.T_FLOAT_VECTOR;
				}
				case 0xC9 -> {
					if (pU8_0 >= 64 || !abstractTypes[pU8_0].isValidLaneFrontier()) {
						return "IMPULSE_VM_ERR_INVALID_REGISTER";
					}
					abstractTypes[dst] = VmRegType.T_NODE_VECTOR;
				}
				case 0xCA -> {
					if (pU8_0 >= 64 || abstractTypes[pU8_0] != VmRegType.T_FLOAT_VECTOR) {
						return "IMPULSE_VM_ERR_INVALID_REGISTER";
					}
					abstractTypes[dst] = ((inst.flags() & 1) != 0)
							? VmRegType.T_LANE_FRONTIER
							: VmRegType.T_BITSET_HANDLE;
				}
				case 0xCB -> {
					if (pU8_0 >= 64 || pU8_1 >= 64) {
						return "IMPULSE_VM_ERR_INVALID_REGISTER";
					}
					VmRegType t1 = abstractTypes[pU8_0];
					VmRegType t2 = abstractTypes[pU8_1];
					if ((t1 != VmRegType.T_BITSET_HANDLE && t1 != VmRegType.T_LANE_FRONTIER)
							|| t2 != VmRegType.T_FLOAT_VECTOR) {
						return "IMPULSE_VM_ERR_INVALID_REGISTER";
					}
					abstractTypes[dst] = VmRegType.T_FLOAT_VECTOR;
				}
				default -> {
				}
			}
		}
		return "IMPULSE_VM_OK";
	}
}
