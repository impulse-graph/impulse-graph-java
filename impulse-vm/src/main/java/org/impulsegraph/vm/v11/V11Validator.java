package org.impulsegraph.vm.v11;

import static org.impulsegraph.vm.v11.V11IsaTables.*;

/**
 * Static Load-Time Bytecode Verifier ({@code impulse_vm_validate}) for ImpulseVM v0.11.0.
 *
 * <p>Mirrors {@code V11_isa.decode_word} and {@code V11_validate.validate_program} in
 * {@code impulse-ocaml}:
 * <ol>
 *   <li>Opcode, subop, allowed_flags, and opcode-specific MBZ checks.</li>
 *   <li>Exact 1:1 pairing of {@code FLAG_EXTENDED} with {@code 0xFF OP_EXTENSION_PAYLOAD}.</li>
 *   <li>CFG branch target bounds, non-self-loop, and non-extension-word checks.</li>
 *   <li>Lexical morsel well-formedness, forward-only jumps, thread-private write isolation,
 *       commit aliasing checks, and {@code OP_FILTER_MORSEL} def-before-use on M1/M2.</li>
 *   <li>Rejection of {@code OP_SAMPLE_NEIGHBORS.vec_wr} (subop 1) inside any CFG loop cycle.</li>
 * </ol>
 */
public final class V11Validator {

    public record ValidationResult(int status, int badPc) {
        public static final ValidationResult OK = new ValidationResult(IMPULSE_VM_OK, -1);

        public boolean isOk() {
            return status == IMPULSE_VM_OK;
        }
    }

    private V11Validator() {}

    /** Decode-level validation for a single 64-bit instruction word (opcode, subop, flags, MBZ). */
    public static int validateWord(long w) {
        int code = (int) (w & 0xFFL);
        if (!DEFINED[code]) {
            return IMPULSE_VM_ERR_INVALID_OPCODE;
        }
        int subop = (int) ((w >>> 8) & 0xFL);
        int flags = (int) ((w >>> 12) & 0xFL);
        int s1 = (int) ((w >>> 16) & 0xFL);
        int dst = (int) ((w >>> 20) & 0xFL);
        int s3 = (int) ((w >>> 24) & 0xFL);
        int s2 = (int) ((w >>> 28) & 0xFL);
        int imm32 = (int) (w >>> 32);
        int imm16Lo = imm32 & 0xFFFF;
        int imm16Hi = (imm32 >>> 16) & 0xFFFF;

        if ((VALID_SUBOP_MASK[code] & (1 << subop)) == 0) {
            return IMPULSE_VM_ERR_INVALID_OPCODE;
        }
        if ((flags & (~ALLOWED_FLAGS[code] & 0xF)) != 0) {
            return IMPULSE_VM_ERR_INVALID_OPERAND;
        }

        boolean ext = (flags & FLAG_EXTENDED) != 0;
        boolean accum = (flags & FLAG_ACCUM) != 0;
        boolean scalar = (flags & FLAG_SCALAR_RHS) != 0;
        boolean mask = (flags & FLAG_MASKED_S3) != 0;

        boolean mbzOk = switch (code) {
            case OP_HALT, OP_NOP, OP_RET ->
                    dst == 0 && s1 == 0 && s2 == 0 && s3 == 0 && imm32 == 0;
            case OP_EXTENSION_PAYLOAD ->
                    flags == 0 && subop == 0 && dst == 0 && s1 == 0 && s2 == 0 && s3 == 0;
            case OP_LOAD_CONST -> {
                boolean isExtSubop = subop >= 4 && subop <= 6;
                boolean extValid = (isExtSubop == ext);
                boolean nullValid = (subop != 7) || (imm32 == 0);
                yield extValid && nullValid && s1 == 0 && s2 == 0 && s3 == 0;
            }
            case OP_SPILL, OP_RELOAD ->
                    imm16Lo < 16 && imm16Hi == 0;
            case OP_SCALAR_INT_ALU ->
                    s3 <= 1;
            case OP_JMP ->
                    dst == 0 && s1 == 0 && s2 == 0 && s3 == 0;
            case OP_SPMV ->
                    (subop < 7 || !accum) && (mask || s3 == 0);
            case OP_VEC_CMP -> {
                if (subop == 6) {
                    yield !mask && imm32 == 0;
                } else if (subop == 7) {
                    yield !scalar && (mask || s3 == 0);
                } else {
                    yield mask || s3 == 0;
                }
            }
            case OP_VEC_TERNARY ->
                    subop != 8 || !scalar;
            case OP_VEC_INIT ->
                    s1 == 0;
            case OP_LANE_WALK ->
                    (subop != 8 || s2 == 0) && (mask || s3 == 0);
            case OP_LANE_STATE_WALK -> {
                boolean sumMulOk = (subop != 3 && subop != 8) || !scalar;
                yield sumMulOk && (mask || s3 == 0);
            }
            default -> !isMaskS3Only(code) || mask || s3 == 0;
        };

        return mbzOk ? IMPULSE_VM_OK : IMPULSE_VM_ERR_INVALID_OPERAND;
    }

    private static boolean isMaskS3Only(int code) {
        return switch (code) {
            case OP_WALK, OP_WALK_FILTERED, OP_DEGREE,
                    OP_WALK_MORSEL, OP_MORSEL_COMMIT, OP_GRAPH_KERNEL,
                    OP_GATHER_NODE_ATTR, OP_GATHER_EDGE_ATTR, OP_GATHER_VEC,
                    OP_VEC_CMP, OP_VEC_3VL_OP, OP_VEC_STR_PRED, OP_VEC_MATH_ARITH,
                    OP_VEC_MATH_EXP_LOG, OP_VEC_MATH_TRIG_HYP, OP_VEC_MATH_ACT,
                    OP_VEC_INT_BITWISE, OP_COALESCE, OP_VEC_REDUCE, OP_TOP_K,
                    OP_COLLECT, OP_VECTOR_ANN_SEARCH, OP_VECTOR_RERANK -> true;
            default -> false;
        };
    }

    /** Full 3-pass program verification matching {@code V11_validate.validate_program}. */
    public static ValidationResult validate(long[] words) {
        if (words == null || words.length == 0) {
            return new ValidationResult(IMPULSE_VM_ERR_VALIDATION_FAILED, 0);
        }
        final int n = words.length;

        // Pass 1: Decode words and verify 128-bit FLAG_EXTENDED <-> 0xFF pairing
        int pc = 0;
        while (pc < n) {
            long w = words[pc];
            int st = validateWord(w);
            if (st != IMPULSE_VM_OK) {
                return new ValidationResult(st, pc);
            }
            int op = (int) (w & 0xFFL);
            boolean ext = ((w >>> 12) & FLAG_EXTENDED) != 0;
            if (op == OP_EXTENSION_PAYLOAD) {
                return new ValidationResult(IMPULSE_VM_ERR_VALIDATION_FAILED, pc);
            }
            if (ext) {
                if (pc + 1 >= n) {
                    return new ValidationResult(IMPULSE_VM_ERR_VALIDATION_FAILED, pc);
                }
                long w2 = words[pc + 1];
                int st2 = validateWord(w2);
                if (st2 != IMPULSE_VM_OK) {
                    return new ValidationResult(st2, pc + 1);
                }
                if ((int) (w2 & 0xFFL) != OP_EXTENSION_PAYLOAD) {
                    return new ValidationResult(IMPULSE_VM_ERR_VALIDATION_FAILED, pc + 1);
                }
                pc += 2;
            } else {
                pc += 1;
            }
        }

        // Pass 2: Verify CFG targets and lexical morsel blocks
        int mHdrPc = -1;
        int mEndPc = -1;
        int mOuterSrcF = -1;
        int mOuterMaskF = -1;
        boolean[][] mJoinWritten = null;
        boolean[] mWrittenV = null;
        boolean[] inMorselInterior = new boolean[n];

        pc = 0;
        while (pc < n) {
            long w = words[pc];
            int op = (int) (w & 0xFFL);
            int subop = (int) ((w >>> 8) & 0xFL);
            int flags = (int) ((w >>> 12) & 0xFL);
            int s1 = (int) ((w >>> 16) & 0xFL);
            int dst = (int) ((w >>> 20) & 0xFL);
            int s3 = (int) ((w >>> 24) & 0xFL);
            int s2 = (int) ((w >>> 28) & 0xFL);
            int imm32 = (int) (w >>> 32);
            int imm16Hi = (imm32 >>> 16) & 0xFFFF;
            boolean ext = (flags & FLAG_EXTENDED) != 0;
            boolean mask = (flags & FLAG_MASKED_S3) != 0;
            boolean scalar = (flags & FLAG_SCALAR_RHS) != 0;
            int nextPc = ext ? pc + 2 : pc + 1;

            if (mHdrPc < 0) {
                if (op == OP_MORSEL_COMMIT || op == OP_MORSEL_END) {
                    return new ValidationResult(IMPULSE_VM_ERR_VALIDATION_FAILED, pc);
                } else if (op == OP_WALK_MORSEL || op == OP_FILTER_MORSEL) {
                    int endOff = imm16Hi;
                    int endPc = pc + endOff;
                    if (endOff < 1 || endPc >= n || s1 >= 8 || (mask && s3 >= 8)) {
                        return new ValidationResult(IMPULSE_VM_ERR_VALIDATION_FAILED, pc);
                    }
                    long endW = words[endPc];
                    int endOp = (int) (endW & 0xFFL);
                    int endImm16Hi = (int) ((endW >>> 48) & 0xFFFFL);
                    if (endOp != OP_MORSEL_END || endImm16Hi != endOff) {
                        return new ValidationResult(IMPULSE_VM_ERR_VALIDATION_FAILED, pc);
                    }
                    for (int mp = pc + 1; mp <= endPc; mp++) {
                        inMorselInterior[mp] = true;
                    }
                    boolean isFilter = (op == OP_FILTER_MORSEL);
                    mHdrPc = pc;
                    mEndPc = endPc;
                    mOuterSrcF = s1;
                    mOuterMaskF = mask ? s3 : -1;
                    mJoinWritten = new boolean[endPc - pc + 1][];
                    mWrittenV = new boolean[8];
                    mWrittenV[0] = true; // M0
                    mWrittenV[3] = true; // M3
                    if (!isFilter) {
                        mWrittenV[1] = true; // M1
                        mWrittenV[2] = true; // M2
                    }
                } else if (op == OP_JMP || op == OP_JCC || op == OP_LOOP_DECR || op == OP_CALL) {
                    int st = checkJumpTarget(words, pc, imm32);
                    if (st != IMPULSE_VM_OK) {
                        return new ValidationResult(st, pc);
                    }
                }
            } else {
                if (pc == mEndPc) {
                    mHdrPc = -1;
                    mEndPc = -1;
                    mJoinWritten = null;
                    mWrittenV = null;
                } else {
                    boolean[] jwAtPc = mJoinWritten[pc - mHdrPc];
                    if (jwAtPc != null) {
                        for (int k = 0; k < 8; k++) {
                            mWrittenV[k] &= jwAtPc[k];
                        }
                    }
                    // Check uninitialized morsel vector reads (e.g. M1/M2 inside OP_FILTER_MORSEL)
                    if (hasUninitializedMorselVRead(op, subop, scalar, dst, s1, s2, s3, mWrittenV)) {
                        return new ValidationResult(IMPULSE_VM_ERR_VALIDATION_FAILED, pc);
                    }
                    int role = MORSEL_ROLE[op];
                    switch (role) {
                        case MORSEL_FORBIDDEN, MORSEL_HEADER, MORSEL_END_ONLY -> {
                            return new ValidationResult(IMPULSE_VM_ERR_VALIDATION_FAILED, pc);
                        }
                        case MORSEL_FWD_JUMP_ONLY -> {
                            boolean jccOk = (op == OP_JMP)
                                    || (op == OP_JCC && subop >= 6 && subop <= 9 && (subop < 8 || s1 >= 8));
                            if (!jccOk || imm32 <= 0) {
                                return new ValidationResult(IMPULSE_VM_ERR_VALIDATION_FAILED, pc);
                            }
                            int st = checkJumpTarget(words, pc, imm32);
                            int tgt = pc + imm32;
                            if (st != IMPULSE_VM_OK || tgt > mEndPc) {
                                return new ValidationResult(IMPULSE_VM_ERR_VALIDATION_FAILED, pc);
                            }
                            int rel = tgt - mHdrPc;
                            if (mJoinWritten[rel] == null) {
                                mJoinWritten[rel] = mWrittenV.clone();
                            } else {
                                for (int k = 0; k < 8; k++) {
                                    mJoinWritten[rel][k] &= mWrittenV[k];
                                }
                            }
                        }
                        case MORSEL_COMMIT_ONLY -> {
                            boolean s1Ok = (subop == 8) || (s1 >= 8);
                            boolean s2Ok = (subop == 0) || (subop == 8) || scalar || (s2 >= 8);
                            boolean s3Ok = !mask || (s3 >= 8);
                            boolean aliasesOuterF = (subop == 0 || subop == 1)
                                    && (dst == mOuterSrcF || dst == mOuterMaskF);
                            if (dst >= 8 || !s1Ok || !s2Ok || !s3Ok || aliasesOuterF) {
                                return new ValidationResult(IMPULSE_VM_ERR_VALIDATION_FAILED, pc);
                            }
                        }
                        case MORSEL_BODY_V_ONLY -> {
                            if (subop != 2) {
                                return new ValidationResult(IMPULSE_VM_ERR_VALIDATION_FAILED, pc);
                            }
                            if (op == OP_SPILL) {
                                if (s1 < 8) {
                                    return new ValidationResult(IMPULSE_VM_ERR_VALIDATION_FAILED, pc);
                                }
                            } else if (op == OP_RELOAD) {
                                if (dst < 8) {
                                    return new ValidationResult(IMPULSE_VM_ERR_VALIDATION_FAILED, pc);
                                }
                                mWrittenV[dst - 8] = true;
                            } else {
                                return new ValidationResult(IMPULSE_VM_ERR_VALIDATION_FAILED, pc);
                            }
                        }
                        case MORSEL_BODY_PAIR_ONLY, MORSEL_BODY_CAST_ONLY, MORSEL_BODY_OK -> {
                            if (role == MORSEL_BODY_PAIR_ONLY && subop < 4) {
                                return new ValidationResult(IMPULSE_VM_ERR_VALIDATION_FAILED, pc);
                            }
                            if (role == MORSEL_BODY_CAST_ONLY && subop != 2) {
                                return new ValidationResult(IMPULSE_VM_ERR_VALIDATION_FAILED, pc);
                            }
                            if (mask && s3 < 8) {
                                return new ValidationResult(IMPULSE_VM_ERR_VALIDATION_FAILED, pc);
                            }
                            int writeKind = writesBankInsideMorsel(op, subop);
                            if (writeKind == WRITE_R) {
                                return new ValidationResult(IMPULSE_VM_ERR_VALIDATION_FAILED, pc);
                            } else if (writeKind == WRITE_F) {
                                if (dst < 8 || (op == OP_SWAP && s1 < 8)) {
                                    return new ValidationResult(IMPULSE_VM_ERR_VALIDATION_FAILED, pc);
                                }
                            } else if (writeKind == WRITE_V) {
                                if (dst < 8 || (op == OP_SWAP && s1 < 8)) {
                                    return new ValidationResult(IMPULSE_VM_ERR_VALIDATION_FAILED, pc);
                                }
                                mWrittenV[dst - 8] = true;
                            }
                        }
                        default -> {
                            return new ValidationResult(IMPULSE_VM_ERR_VALIDATION_FAILED, pc);
                        }
                    }
                }
            }
            pc = nextPc;
        }
        if (mHdrPc >= 0) {
            return new ValidationResult(IMPULSE_VM_ERR_VALIDATION_FAILED, mHdrPc);
        }

        // Pass 2b: Forbid outer branches/calls from jumping into the interior of any morsel block
        pc = 0;
        while (pc < n) {
            long w = words[pc];
            int op = (int) (w & 0xFFL);
            boolean ext = ((w >>> 12) & FLAG_EXTENDED) != 0;
            if (!inMorselInterior[pc] && (op == OP_JMP || op == OP_JCC || op == OP_LOOP_DECR || op == OP_CALL)) {
                int tgt = pc + (int) (w >>> 32);
                if (tgt >= 0 && tgt < n && inMorselInterior[tgt]) {
                    return new ValidationResult(IMPULSE_VM_ERR_VALIDATION_FAILED, pc);
                }
            }
            pc += ext ? 2 : 1;
        }

        // Pass 3: Reject OP_SAMPLE_NEIGHBORS.vec_wr (subop=1) inside any CFG loop cycle (including CALL edges)
        pc = 0;
        while (pc < n) {
            long w = words[pc];
            int op = (int) (w & 0xFFL);
            int subop = (int) ((w >>> 8) & 0xFL);
            boolean ext = ((w >>> 12) & FLAG_EXTENDED) != 0;
            if (op == OP_SAMPLE_NEIGHBORS && subop == 1 && isInCfgCycle(words, pc)) {
                return new ValidationResult(IMPULSE_VM_ERR_VALIDATION_FAILED, pc);
            }
            pc += ext ? 2 : 1;
        }

        return ValidationResult.OK;
    }

    private static int checkJumpTarget(long[] words, int pc, int offset) {
        long tgtL = (long) pc + (long) offset;
        if (offset == 0 || tgtL < 0 || tgtL >= words.length) {
            return IMPULSE_VM_ERR_OUT_OF_BOUNDS;
        }
        int tgt = (int) tgtL;
        if ((int) (words[tgt] & 0xFFL) == OP_EXTENSION_PAYLOAD) {
            return IMPULSE_VM_ERR_VALIDATION_FAILED;
        }
        return IMPULSE_VM_OK;
    }

    private static boolean isInCfgCycle(long[] words, int targetPc) {
        final int n = words.length;
        boolean[] visited = new boolean[n];
        int[] stack = new int[n];
        int top = 0;
        int start = targetPc + ((((words[targetPc] >>> 12) & FLAG_EXTENDED) != 0) ? 2 : 1);
        if (start >= 0 && start < n) {
            if (start == targetPc) return true;
            visited[start] = true;
            stack[top++] = start;
        }
        while (top > 0) {
            int cur = stack[--top];
            long w = words[cur];
            int op = (int) (w & 0xFFL);
            boolean ext = ((w >>> 12) & FLAG_EXTENDED) != 0;
            int nextPc = cur + (ext ? 2 : 1);
            int imm32 = (int) (w >>> 32);
            switch (op) {
                case OP_HALT, OP_TRAP, OP_THROW, OP_RET -> {}
                case OP_JMP -> {
                    int t = cur + imm32;
                    if (t == targetPc) return true;
                    if (t >= 0 && t < n && !visited[t]) {
                        visited[t] = true;
                        stack[top++] = t;
                    }
                }
                case OP_JCC, OP_LOOP_DECR, OP_CALL -> {
                    int t = cur + imm32;
                    if (t == targetPc || nextPc == targetPc) return true;
                    if (nextPc >= 0 && nextPc < n && !visited[nextPc]) {
                        visited[nextPc] = true;
                        stack[top++] = nextPc;
                    }
                    if (t >= 0 && t < n && !visited[t]) {
                        visited[t] = true;
                        stack[top++] = t;
                    }
                }
                default -> {
                    if (nextPc == targetPc) return true;
                    if (nextPc >= 0 && nextPc < n && !visited[nextPc]) {
                        visited[nextPc] = true;
                        stack[top++] = nextPc;
                    }
                }
            }
        }
        return false;
    }

    private static final int WRITE_NONE = 0;
    private static final int WRITE_R = 1;
    private static final int WRITE_F = 2;
    private static final int WRITE_V = 3;

    private static int writesBankInsideMorsel(int op, int subop) {
        return switch (op) {
            case OP_NOP, OP_JMP, OP_JCC -> WRITE_NONE;
            case OP_MOV -> switch (subop) {
                case 0, 3 -> WRITE_R;
                case 1, 4 -> WRITE_F;
                default -> WRITE_V;
            };
            case OP_SWAP -> switch (subop) {
                case 0 -> WRITE_R;
                case 1 -> WRITE_F;
                default -> WRITE_V;
            };
            case OP_SET_OP, OP_SET_COMPLEMENT -> WRITE_F;
            case OP_SET_CONVERT -> (subop == 1 || subop == 2) ? WRITE_F : WRITE_V;
            case OP_GATHER_NODE_ATTR -> (subop == 2) ? WRITE_R : WRITE_V;
            case OP_GATHER_EDGE_ATTR -> (subop == 1) ? WRITE_R : WRITE_V;
            case OP_VEC_3VL_OP -> (subop == 11) ? WRITE_NONE : WRITE_V;
            case OP_GATHER_VEC, OP_VEC_CMP, OP_VEC_STR_PRED,
                    OP_VEC_MATH_ARITH, OP_VEC_MATH_EXP_LOG, OP_VEC_MATH_TRIG_HYP,
                    OP_VEC_MATH_ACT, OP_VEC_INT_BITWISE, OP_VEC_TERNARY,
                    OP_VEC_BLEND, OP_COALESCE, OP_VEC_POINT, OP_VECTOR_RERANK -> WRITE_V;
            default -> WRITE_NONE;
        };
    }

    private static boolean checkUninit(int reg, boolean[] writtenV) {
        return reg >= 8 && !writtenV[reg - 8];
    }

    private static boolean hasUninitializedMorselVRead(
            int op, int subop, boolean scalar, int dst, int s1, int s2, int s3, boolean[] writtenV) {
        return switch (op) {
            case OP_MOV -> subop == 2 && checkUninit(s1, writtenV);
            case OP_SWAP -> subop == 2 && (checkUninit(dst, writtenV) || checkUninit(s1, writtenV));
            case OP_SPILL -> subop == 2 && checkUninit(s1, writtenV);
            case OP_SET_CONVERT -> (subop == 1 || subop == 2) && checkUninit(s1, writtenV);
            case OP_GATHER_NODE_ATTR, OP_GATHER_EDGE_ATTR -> subop == 0 && checkUninit(s1, writtenV);
            case OP_GATHER_VEC -> checkUninit(s1, writtenV) || checkUninit(s2, writtenV);
            case OP_VEC_CMP -> checkUninit(s1, writtenV)
                    || (subop != 7 && !scalar && checkUninit(s2, writtenV))
                    || (subop == 6 && !scalar && checkUninit(s3, writtenV));
            case OP_VEC_3VL_OP -> checkUninit(s1, writtenV)
                    || ((subop == 0 || subop == 1 || subop == 3) && !scalar && checkUninit(s2, writtenV));
            case OP_VEC_STR_PRED, OP_COALESCE ->
                    checkUninit(s1, writtenV) || (!scalar && checkUninit(s2, writtenV));
            case OP_VEC_MATH_ARITH ->
                    checkUninit(s1, writtenV) || (subop <= 7 && !scalar && checkUninit(s2, writtenV));
            case OP_VEC_MATH_EXP_LOG ->
                    checkUninit(s1, writtenV) || ((subop == 8 || subop == 10) && !scalar && checkUninit(s2, writtenV));
            case OP_VEC_MATH_TRIG_HYP ->
                    checkUninit(s1, writtenV) || (subop == 6 && !scalar && checkUninit(s2, writtenV));
            case OP_VEC_MATH_ACT ->
                    checkUninit(s1, writtenV) || (subop == 1 && !scalar && checkUninit(s2, writtenV));
            case OP_VEC_INT_BITWISE ->
                    checkUninit(s1, writtenV)
                            || ((subop <= 2 || (subop >= 4 && subop <= 9)) && !scalar && checkUninit(s2, writtenV));
            case OP_VEC_TERNARY -> checkUninit(s1, writtenV)
                    || (!scalar && checkUninit(s2, writtenV))
                    || ((subop == 0 || subop == 2 || subop == 4 || subop == 6) && checkUninit(s3, writtenV));
            case OP_VEC_BLEND -> checkUninit(s1, writtenV)
                    || (!scalar && checkUninit(s2, writtenV))
                    || ((subop == 0 || subop == 2) && checkUninit(s3, writtenV));
            case OP_VECTOR_RERANK ->
                    subop >= 4 && (checkUninit(s1, writtenV) || checkUninit(s2, writtenV));
            case OP_VEC_POINT -> subop == 2 && checkUninit(s1, writtenV);
            case OP_MORSEL_COMMIT -> (subop <= 7 && checkUninit(s1, writtenV))
                    || (subop >= 1 && subop <= 7 && !scalar && checkUninit(s2, writtenV));
            default -> false;
        };
    }
}
